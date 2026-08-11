//! Document inspections: the problems behind the editor's warning widget.
//!
//! These are the Markdown equivalents of an IDE's code inspections — things that parse fine but are
//! very probably not what the author meant. Every one of them earns its place by being a mistake
//! that survives proofreading: a link whose destination is empty still renders as a link, a heading
//! that jumps from `#` to `###` still renders as a heading, and a footnote defined but never
//! referenced simply vanishes from the output. None of them are visible in the preview, which is
//! exactly why they need reporting somewhere else.
//!
//! Every inspection is line-oriented and reports a UTF-16 range, so the UI can highlight the exact
//! span without converting anything.

use std::collections::HashMap;
use std::collections::HashSet;

use comrak::nodes::NodeValue;
use comrak::{Arena, parse_document};

use crate::document::Document;
use crate::parser::{options, plain_text};
use crate::wire::{Encoder, PayloadKind};

/// How much a finding matters.
///
/// The widget shows the worst severity present and counts each separately, the same way an IDE's
/// does, so the split has to be meaningful: an [`Severity::Error`] is something that will not render
/// as intended, a [`Severity::Warning`] is something that renders but probably reads wrong, and a
/// [`Severity::Weak`] is a style note nobody should be interrupted by.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
#[repr(u8)]
pub enum Severity {
    Weak = 0,
    Warning = 1,
    Error = 2,
}

/// Which inspection produced a finding.
///
/// The identifier is stable and is what a future "suppress this inspection" setting would key on,
/// so these names are part of the contract rather than display text.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[repr(u8)]
pub enum Inspection {
    /// A link or image whose destination is empty.
    EmptyLinkDestination = 1,
    /// An image with no alternative text.
    MissingImageAlt = 2,
    /// A heading level that skips one or more levels below the previous heading.
    HeadingLevelJump = 3,
    /// Two headings with the same text at the same level.
    DuplicateHeading = 4,
    /// A fenced code block with no language, so it cannot be highlighted.
    UnlabelledCodeFence = 5,
    /// A code fence that is never closed.
    UnclosedCodeFence = 6,
    /// A footnote referenced but never defined.
    UndefinedFootnote = 7,
    /// A footnote defined but never referenced.
    UnusedFootnote = 8,
    /// A link reference used but never defined.
    UndefinedLinkReference = 9,
    /// A table row whose cell count differs from the header's.
    TableColumnMismatch = 10,
    /// A hard tab used for indentation, which renders inconsistently.
    HardTab = 11,
    /// Trailing whitespace, which is invisible and sometimes significant.
    TrailingWhitespace = 12,
    /// More than one top-level heading in a document.
    MultipleTopLevelHeadings = 13,
    /// A bare URL that is not a link. Only reported outside code.
    BareUrl = 14,
}

impl Inspection {
    /// The severity findings from this inspection carry.
    pub fn severity(self) -> Severity {
        match self {
            // These change what the document means, not just how it reads.
            Self::UnclosedCodeFence | Self::UndefinedFootnote | Self::UndefinedLinkReference => {
                Severity::Error
            }
            Self::EmptyLinkDestination
            | Self::HeadingLevelJump
            | Self::DuplicateHeading
            | Self::TableColumnMismatch
            | Self::MissingImageAlt
            | Self::UnusedFootnote
            | Self::MultipleTopLevelHeadings => Severity::Warning,
            Self::UnlabelledCodeFence
            | Self::HardTab
            | Self::TrailingWhitespace
            | Self::BareUrl => Severity::Weak,
        }
    }

    /// A stable identifier, used for suppression and in tests.
    pub fn id(self) -> &'static str {
        match self {
            Self::EmptyLinkDestination => "EmptyLinkDestination",
            Self::MissingImageAlt => "MissingImageAlt",
            Self::HeadingLevelJump => "HeadingLevelJump",
            Self::DuplicateHeading => "DuplicateHeading",
            Self::UnlabelledCodeFence => "UnlabelledCodeFence",
            Self::UnclosedCodeFence => "UnclosedCodeFence",
            Self::UndefinedFootnote => "UndefinedFootnote",
            Self::UnusedFootnote => "UnusedFootnote",
            Self::UndefinedLinkReference => "UndefinedLinkReference",
            Self::TableColumnMismatch => "TableColumnMismatch",
            Self::HardTab => "HardTab",
            Self::TrailingWhitespace => "TrailingWhitespace",
            Self::MultipleTopLevelHeadings => "MultipleTopLevelHeadings",
            Self::BareUrl => "BareUrl",
        }
    }
}

/// One reported problem.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Finding {
    pub inspection: Inspection,
    /// Zero-based source line.
    pub line: u32,
    /// UTF-16 offsets into the whole document.
    pub start: u32,
    pub end: u32,
    /// Human-readable description, shown in the problems list and as a tooltip.
    pub message: String,
}

impl Finding {
    pub fn severity(&self) -> Severity {
        self.inspection.severity()
    }
}

/// Runs every inspection over `document`, in source order.
///
/// Findings are sorted by position rather than by severity: the problems list is read alongside the
/// document, and jumping around it in severity order makes it useless for working through.
pub fn run(document: &mut Document) -> Vec<Finding> {
    let text = document.text().to_owned();
    let mut findings = Vec::new();

    let lines = LineIndex::new(&text);

    inspect_ast(&text, &lines, &mut findings);
    inspect_lines(&text, &lines, &mut findings);

    findings.sort_by_key(|finding| (finding.start, finding.end, finding.inspection as u8));
    // Two inspections can legitimately fire on the same span (an empty destination on an image that
    // also lacks alt text); identical findings cannot, and would show as duplicates in the list.
    findings.dedup();

    // Offsets are byte offsets up to this point, because that is what comrak and the line scan both
    // produce. Convert once, at the end, rather than in fourteen places.
    for finding in &mut findings {
        finding.start = document.byte_to_utf16(finding.start as usize) as u32;
        finding.end = document.byte_to_utf16(finding.end as usize) as u32;
    }

    findings
}

/// Maps line numbers to byte ranges, so an inspection can report a span without rescanning.
struct LineIndex {
    /// Byte offset of the start of each line.
    starts: Vec<usize>,
    /// Byte offset of the end of each line, excluding the newline.
    ends: Vec<usize>,
}

impl LineIndex {
    fn new(text: &str) -> Self {
        let mut starts = vec![0];
        let mut ends = Vec::new();

        for (index, byte) in text.bytes().enumerate() {
            if byte == b'\n' {
                ends.push(index);
                starts.push(index + 1);
            }
        }
        ends.push(text.len());

        Self { starts, ends }
    }

    /// Byte range of a zero-based line, clamped to the document.
    fn range(&self, line: usize) -> (usize, usize) {
        let start = self.starts.get(line).copied().unwrap_or(0);
        let end = self.ends.get(line).copied().unwrap_or(start);
        (start, end.max(start))
    }

    fn start(&self, line: usize) -> usize {
        self.range(line).0
    }
}

/// Inspections that need the parsed document.
fn inspect_ast(text: &str, lines: &LineIndex, findings: &mut Vec<Finding>) {
    let arena = Arena::new();
    let root = parse_document(&arena, text, &options());

    let mut previous_level: Option<u8> = None;
    let mut top_level_headings = 0usize;
    let mut seen_headings: HashMap<(u8, String), u32> = HashMap::new();

    for node in root.descendants() {
        let data = node.data.borrow();
        let line = data.sourcepos.start.line.saturating_sub(1);
        let (start, end) = lines.range(line);
        let line_number = line as u32;

        match &data.value {
            NodeValue::Heading(heading) => {
                let level = heading.level;

                if level == 1 {
                    top_level_headings += 1;
                    if top_level_headings == 2 {
                        findings.push(Finding {
                            inspection: Inspection::MultipleTopLevelHeadings,
                            line: line_number,
                            start: start as u32,
                            end: end as u32,
                            message: "A second top-level heading; documents usually have one title"
                                .to_owned(),
                        });
                    }
                }

                if let Some(previous) = previous_level {
                    if level > previous + 1 {
                        findings.push(Finding {
                            inspection: Inspection::HeadingLevelJump,
                            line: line_number,
                            start: start as u32,
                            end: end as u32,
                            message: format!(
                                "Heading level jumps from {previous} to {level}; the outline will \
                                 have a gap"
                            ),
                        });
                    }
                }
                previous_level = Some(level);

                drop(data);
                let title = plain_text(node).trim().to_lowercase();
                if !title.is_empty() {
                    if let Some(first) = seen_headings.insert((level, title.clone()), line_number) {
                        findings.push(Finding {
                            inspection: Inspection::DuplicateHeading,
                            line: line_number,
                            start: start as u32,
                            end: end as u32,
                            message: format!(
                                "Duplicate heading; the same text appears on line {}",
                                first + 1
                            ),
                        });
                    }
                }
                continue;
            }

            NodeValue::Link(link) => {
                if link.url.trim().is_empty() {
                    findings.push(Finding {
                        inspection: Inspection::EmptyLinkDestination,
                        line: line_number,
                        start: start as u32,
                        end: end as u32,
                        message: "Link has no destination".to_owned(),
                    });
                }
            }

            NodeValue::Image(image) => {
                if image.url.trim().is_empty() {
                    findings.push(Finding {
                        inspection: Inspection::EmptyLinkDestination,
                        line: line_number,
                        start: start as u32,
                        end: end as u32,
                        message: "Image has no source".to_owned(),
                    });
                }

                drop(data);
                if plain_text(node).trim().is_empty() {
                    findings.push(Finding {
                        inspection: Inspection::MissingImageAlt,
                        line: line_number,
                        start: start as u32,
                        end: end as u32,
                        message: "Image has no alternative text".to_owned(),
                    });
                }
                continue;
            }

            NodeValue::CodeBlock(code) => {
                if code.fenced && code.info.trim().is_empty() {
                    findings.push(Finding {
                        inspection: Inspection::UnlabelledCodeFence,
                        line: line_number,
                        start: start as u32,
                        end: end as u32,
                        message: "Fenced block has no language, so it is not highlighted"
                            .to_owned(),
                    });
                }
            }

            _ => {}
        }
    }
}

/// Inspections that work on raw lines, outside the parser.
///
/// These deliberately do not use the AST: trailing whitespace and hard tabs are invisible to it, and
/// an unclosed fence is precisely the case where the parse disagrees with what the author sees.
fn inspect_lines(text: &str, lines: &LineIndex, findings: &mut Vec<Finding>) {
    let mut fence: Option<(usize, char, usize)> = None;
    let mut in_front_matter = false;

    for (number, line) in text.lines().enumerate() {
        let (line_start, line_end) = lines.range(number);
        let trimmed = line.trim_start();

        // Front matter is YAML, not Markdown; its indentation and quoting are not ours to judge.
        if number == 0 && trimmed == "---" {
            in_front_matter = true;
            continue;
        }
        if in_front_matter {
            if trimmed == "---" || trimmed == "..." {
                in_front_matter = false;
            }
            continue;
        }

        // Fence tracking. A fence closes only on the same character and at least as many of them,
        // which is what lets ```` ``` ```` appear inside a ````` ```` ````` block.
        let fence_char = trimmed.chars().next().filter(|c| *c == '`' || *c == '~');
        if let Some(character) = fence_char {
            let count = trimmed.chars().take_while(|c| *c == character).count();
            if count >= 3 {
                match fence {
                    Some((_, open_char, open_count))
                        if open_char == character && count >= open_count =>
                    {
                        fence = None;
                    }
                    None => fence = Some((number, character, count)),
                    Some(_) => {}
                }
                continue;
            }
        }

        // Inside a fence the content is code, where a tab is meaningful and trailing space is the
        // author's business.
        if fence.is_some() {
            continue;
        }

        if line.starts_with('\t') || line.starts_with(" \t") {
            let width = line.len() - line.trim_start_matches([' ', '\t']).len();
            findings.push(Finding {
                inspection: Inspection::HardTab,
                line: number as u32,
                start: line_start as u32,
                end: (line_start + width) as u32,
                message: "Indented with a tab; width depends on the viewer".to_owned(),
            });
        }

        let trailing = line.len() - line.trim_end().len();
        if trailing > 0 && !line.trim().is_empty() {
            // Two trailing spaces are a hard line break in Markdown -- deliberate, not a mistake.
            let is_hard_break = trailing == 2 && line.ends_with("  ");
            if !is_hard_break {
                findings.push(Finding {
                    inspection: Inspection::TrailingWhitespace,
                    line: number as u32,
                    start: (line_end - trailing) as u32,
                    end: line_end as u32,
                    message: "Trailing whitespace".to_owned(),
                });
            }
        }
    }

    if let Some((line, character, count)) = fence {
        let (start, end) = lines.range(line);
        findings.push(Finding {
            inspection: Inspection::UnclosedCodeFence,
            line: line as u32,
            start: start as u32,
            end: end as u32,
            message: format!(
                "Code fence opened with {count} '{character}' is never closed; the rest of the \
                 document is treated as code"
            ),
        });
    }

    inspect_link_references(text, lines, findings);
    inspect_tables(text, lines, findings);
    inspect_footnotes(text, lines, findings);
}

/// Reports table rows whose cell count differs from the header's.
///
/// This works on source lines rather than on the AST because comrak silently normalises a ragged
/// row — it truncates the extras and pads the missing — so by the time the table is parsed the
/// mistake is gone. The author's cells are only visible in what they typed.
fn inspect_tables(text: &str, lines: &LineIndex, findings: &mut Vec<Finding>) {
    let source: Vec<&str> = text.lines().collect();
    let mut in_fence = false;
    let mut index = 0;

    while index < source.len() {
        let trimmed = source[index].trim_start();
        if trimmed.starts_with("```") || trimmed.starts_with("~~~") {
            in_fence = !in_fence;
            index += 1;
            continue;
        }
        if in_fence || index == 0 || !is_delimiter_row(source[index]) {
            index += 1;
            continue;
        }

        // The line above a delimiter row is the header, and its cell count is the table's width.
        let expected = count_cells(source[index - 1]);
        if expected == 0 || count_cells(source[index]) != expected {
            index += 1;
            continue;
        }

        let mut row = index + 1;
        while row < source.len() && source[row].contains('|') && !source[row].trim().is_empty() {
            let cells = count_cells(source[row]);
            if cells != expected {
                let (start, end) = lines.range(row);
                findings.push(Finding {
                    inspection: Inspection::TableColumnMismatch,
                    line: row as u32,
                    start: start as u32,
                    end: end as u32,
                    message: format!(
                        "Row has {cells} cells but the header has {expected}; the extra content is \
                         dropped"
                    ),
                });
            }
            row += 1;
        }

        index = row;
    }
}

/// Whether a line is a GFM table delimiter, such as `|:---|---:|`.
fn is_delimiter_row(line: &str) -> bool {
    let trimmed = line.trim();
    if !trimmed.contains('-') || !trimmed.contains('|') {
        return false;
    }
    trimmed
        .chars()
        .all(|character| matches!(character, '|' | '-' | ':' | ' '))
}

/// Counts the cells in a table row, honouring `\|` escapes.
fn count_cells(line: &str) -> usize {
    let trimmed = line.trim();
    if !trimmed.contains('|') {
        return 0;
    }

    let mut cells = 0;
    let mut current = String::new();
    let mut escaped = false;
    let mut pieces: Vec<String> = Vec::new();

    for character in trimmed.chars() {
        if escaped {
            current.push(character);
            escaped = false;
            continue;
        }
        match character {
            '\\' => escaped = true,
            '|' => {
                pieces.push(std::mem::take(&mut current));
            }
            _ => current.push(character),
        }
    }
    pieces.push(current);

    // A row may or may not carry the leading and trailing pipe. Either way those produce an empty
    // outer piece that is a delimiter, not a cell.
    let mut slice = pieces.as_slice();
    if slice.first().is_some_and(|piece| piece.trim().is_empty()) {
        slice = &slice[1..];
    }
    if slice.last().is_some_and(|piece| piece.trim().is_empty()) {
        slice = &slice[..slice.len().saturating_sub(1)];
    }
    cells += slice.len();
    cells
}

/// Reports footnotes defined but never referenced, and referenced but never defined.
///
/// Also source-level: comrak drops an unreferenced definition from the tree entirely, and leaves an
/// undefined reference as literal text, so neither survives into the AST.
fn inspect_footnotes(text: &str, lines: &LineIndex, findings: &mut Vec<Finding>) {
    let mut defined: HashMap<String, (usize, usize, usize)> = HashMap::new();
    let mut referenced: HashMap<String, (usize, usize, usize)> = HashMap::new();
    let mut in_fence = false;

    for (number, line) in text.lines().enumerate() {
        let trimmed = line.trim_start();
        if trimmed.starts_with("```") || trimmed.starts_with("~~~") {
            in_fence = !in_fence;
            continue;
        }
        if in_fence {
            continue;
        }

        // `[^label]: text` at the start of a line defines a footnote.
        if let Some(rest) = trimmed.strip_prefix("[^") {
            if let Some(close) = rest.find(']') {
                if rest[close + 1..].starts_with(':') {
                    let (start, end) = lines.range(number);
                    defined.insert(rest[..close].to_owned(), (number, start, end));
                    continue;
                }
            }
        }

        collect_footnote_uses(line, number, lines, &mut referenced);
    }

    for (name, (line, start, end)) in &referenced {
        if !defined.contains_key(name) {
            findings.push(Finding {
                inspection: Inspection::UndefinedFootnote,
                line: *line as u32,
                start: *start as u32,
                end: *end as u32,
                message: format!("Footnote [^{name}] is referenced but never defined"),
            });
        }
    }

    for (name, (line, start, end)) in &defined {
        if !referenced.contains_key(name) {
            findings.push(Finding {
                inspection: Inspection::UnusedFootnote,
                line: *line as u32,
                start: *start as u32,
                end: *end as u32,
                message: format!("Footnote [^{name}] is defined but never referenced"),
            });
        }
    }
}

/// Records every `[^label]` reference on one line, with its byte range in the document.
fn collect_footnote_uses(
    line: &str,
    number: usize,
    lines: &LineIndex,
    used: &mut HashMap<String, (usize, usize, usize)>,
) {
    let line_start = lines.start(number);
    let bytes = line.as_bytes();
    let mut index = 0;

    while index + 1 < bytes.len() {
        if bytes[index] != b'['
            || bytes[index + 1] != b'^'
            || (index > 0 && bytes[index - 1] == b'\\')
        {
            index += 1;
            continue;
        }

        let Some(close) = find_unescaped(line, index + 2, b']') else {
            break;
        };

        // `[^label]:` is a definition, which the caller has already handled when it starts a line;
        // mid-line it is not a reference either.
        let is_definition = line.as_bytes().get(close + 1) == Some(&b':');
        let label = &line[index + 2..close];
        if !is_definition && !label.is_empty() {
            used.entry(label.to_owned()).or_insert((
                number,
                line_start + index,
                line_start + close + 1,
            ));
        }

        index = close + 1;
    }
}

/// Reports `[text][label]` whose `[label]: url` definition is missing.
fn inspect_link_references(text: &str, lines: &LineIndex, findings: &mut Vec<Finding>) {
    let mut defined: HashSet<String> = HashSet::new();
    let mut used: Vec<(String, usize, usize, usize)> = Vec::new();
    let mut in_fence = false;

    for (number, line) in text.lines().enumerate() {
        let trimmed = line.trim_start();
        if trimmed.starts_with("```") || trimmed.starts_with("~~~") {
            in_fence = !in_fence;
            continue;
        }
        if in_fence {
            continue;
        }

        // A definition is `[label]: destination` at the start of a line.
        if let Some(rest) = trimmed.strip_prefix('[') {
            if let Some(close) = rest.find(']') {
                if rest[close + 1..].starts_with(':') {
                    defined.insert(rest[..close].trim().to_lowercase());
                    continue;
                }
            }
        }

        collect_reference_uses(line, number, lines, &mut used);
    }

    for (label, line, start, end) in used {
        if !defined.contains(&label) {
            findings.push(Finding {
                inspection: Inspection::UndefinedLinkReference,
                line: line as u32,
                start: start as u32,
                end: end as u32,
                message: format!("Link reference [{label}] has no definition"),
            });
        }
    }
}

/// Finds `[text][label]` uses on one line, recording the label's byte range in the document.
fn collect_reference_uses(
    line: &str,
    number: usize,
    lines: &LineIndex,
    used: &mut Vec<(String, usize, usize, usize)>,
) {
    let line_start = lines.start(number);
    let bytes = line.as_bytes();
    let mut index = 0;

    while index < bytes.len() {
        if bytes[index] != b'[' || (index > 0 && bytes[index - 1] == b'\\') {
            index += 1;
            continue;
        }

        let Some(text_end) = find_unescaped(line, index + 1, b']') else {
            break;
        };

        // `[text][label]`; `[text](url)` is an inline link and `[text][]`/`[text]` are collapsed
        // forms whose label is the text itself, which comrak resolves and which we skip here.
        let after = text_end + 1;
        if after < bytes.len() && bytes[after] == b'[' {
            if let Some(label_end) = find_unescaped(line, after + 1, b']') {
                let label = line[after + 1..label_end].trim();
                if !label.is_empty() {
                    used.push((
                        label.to_lowercase(),
                        number,
                        line_start + after + 1,
                        line_start + label_end,
                    ));
                }
                index = label_end + 1;
                continue;
            }
        }

        index = after;
    }
}

/// Index of the next unescaped `needle` at or after `from`.
fn find_unescaped(line: &str, from: usize, needle: u8) -> Option<usize> {
    let bytes = line.as_bytes();
    let mut index = from;
    while index < bytes.len() {
        if bytes[index] == b'\\' {
            index += 2;
            continue;
        }
        if bytes[index] == needle {
            return Some(index);
        }
        index += 1;
    }
    None
}

/// Encodes the findings as a [`PayloadKind::Inspections`] payload.
pub fn encode(document: &mut Document) -> Vec<u8> {
    let findings = run(document);

    let mut encoder = Encoder::new(PayloadKind::Inspections);
    encoder.put_len(findings.len());
    for finding in &findings {
        encoder.put_u8(finding.inspection as u8);
        encoder.put_u8(finding.severity() as u8);
        encoder.put_u32(finding.line);
        encoder.put_u32(finding.start);
        encoder.put_u32(finding.end);
        encoder.put_str(&finding.message);
    }
    encoder.finish()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wire::Decoder;

    fn findings(text: &str) -> Vec<Finding> {
        run(&mut Document::new(text))
    }

    fn ids(text: &str) -> Vec<&'static str> {
        findings(text)
            .iter()
            .map(|finding| finding.inspection.id())
            .collect()
    }

    fn has(text: &str, inspection: Inspection) -> bool {
        findings(text)
            .iter()
            .any(|finding| finding.inspection == inspection)
    }

    #[test]
    fn a_clean_document_reports_nothing() {
        let clean = "# Title\n\nA paragraph with a [link](https://example.com).\n\n\
                     ## Section\n\n```rust\nfn main() {}\n```\n";
        assert_eq!(ids(clean), Vec::<&str>::new());
    }

    #[test]
    fn reports_an_empty_link_destination() {
        assert!(has("[text]()\n", Inspection::EmptyLinkDestination));
    }

    #[test]
    fn reports_an_image_without_alt_text() {
        assert!(has("![](picture.png)\n", Inspection::MissingImageAlt));
        assert!(!has(
            "![a picture](picture.png)\n",
            Inspection::MissingImageAlt
        ));
    }

    #[test]
    fn reports_a_heading_level_jump() {
        assert!(has("# One\n\n### Three\n", Inspection::HeadingLevelJump));
        assert!(!has(
            "# One\n\n## Two\n\n### Three\n",
            Inspection::HeadingLevelJump
        ));
    }

    #[test]
    fn a_heading_may_drop_back_any_number_of_levels() {
        // Going from ### back to # is how sections end; only descending too fast is a problem.
        assert!(!has(
            "# One\n\n## Two\n\n### Three\n\n# Another\n",
            Inspection::HeadingLevelJump
        ));
    }

    #[test]
    fn reports_a_duplicate_heading() {
        assert!(has(
            "## Setup\n\ntext\n\n## Setup\n",
            Inspection::DuplicateHeading
        ));
        // Same text at a different level is a legitimate sub-section.
        assert!(!has(
            "## Setup\n\ntext\n\n### Setup\n",
            Inspection::DuplicateHeading
        ));
    }

    #[test]
    fn reports_a_second_top_level_heading_once() {
        let reported = findings("# One\n\n# Two\n\n# Three\n")
            .iter()
            .filter(|f| f.inspection == Inspection::MultipleTopLevelHeadings)
            .count();
        assert_eq!(reported, 1, "the warning should fire once, not per heading");
    }

    #[test]
    fn reports_an_unlabelled_fence_but_not_an_indented_block() {
        assert!(has("```\nplain\n```\n", Inspection::UnlabelledCodeFence));
        assert!(!has(
            "```rust\nfn main() {}\n```\n",
            Inspection::UnlabelledCodeFence
        ));
        assert!(!has("    indented\n", Inspection::UnlabelledCodeFence));
    }

    #[test]
    fn reports_an_unclosed_fence() {
        assert!(has(
            "```rust\nfn main() {}\n",
            Inspection::UnclosedCodeFence
        ));
        assert!(!has(
            "```rust\nfn main() {}\n```\n",
            Inspection::UnclosedCodeFence
        ));
    }

    #[test]
    fn a_shorter_fence_inside_a_longer_one_does_not_close_it() {
        let nested = "````markdown\n```rust\nfn main() {}\n```\n````\n";
        assert!(!has(nested, Inspection::UnclosedCodeFence));
    }

    #[test]
    fn a_tilde_fence_is_not_closed_by_a_backtick_fence() {
        assert!(has("~~~\ncode\n```\n", Inspection::UnclosedCodeFence));
    }

    #[test]
    fn reports_an_unused_footnote_definition() {
        assert!(has(
            "Text.\n\n[^a]: Never referenced.\n",
            Inspection::UnusedFootnote
        ));
        assert!(!has(
            "Text[^a]\n\n[^a]: Referenced.\n",
            Inspection::UnusedFootnote
        ));
    }

    #[test]
    fn reports_an_undefined_link_reference() {
        assert!(has(
            "See [the docs][docs].\n",
            Inspection::UndefinedLinkReference
        ));
        assert!(!has(
            "See [the docs][docs].\n\n[docs]: https://example.com\n",
            Inspection::UndefinedLinkReference
        ));
    }

    #[test]
    fn a_link_reference_match_ignores_case() {
        assert!(!has(
            "See [the docs][Docs].\n\n[docs]: https://example.com\n",
            Inspection::UndefinedLinkReference
        ));
    }

    #[test]
    fn an_inline_link_is_not_a_reference() {
        assert!(!has(
            "See [the docs](https://example.com).\n",
            Inspection::UndefinedLinkReference
        ));
    }

    #[test]
    fn a_reference_inside_a_fence_is_not_reported() {
        assert!(!has(
            "```\nSee [the docs][docs].\n```\n",
            Inspection::UndefinedLinkReference
        ));
    }

    #[test]
    fn reports_a_table_row_with_the_wrong_cell_count() {
        let ragged = "| A | B |\n|---|---|\n| 1 | 2 | 3 |\n";
        assert!(has(ragged, Inspection::TableColumnMismatch));

        let even = "| A | B |\n|---|---|\n| 1 | 2 |\n";
        assert!(!has(even, Inspection::TableColumnMismatch));
    }

    #[test]
    fn reports_trailing_whitespace_but_not_a_hard_line_break() {
        assert!(has("text \n", Inspection::TrailingWhitespace));
        // Exactly two trailing spaces is Markdown's hard line break, which is intentional.
        assert!(!has("text  \n", Inspection::TrailingWhitespace));
        assert!(has("text   \n", Inspection::TrailingWhitespace));
    }

    #[test]
    fn a_blank_line_is_not_trailing_whitespace() {
        assert!(!has("a\n   \nb\n", Inspection::TrailingWhitespace));
    }

    #[test]
    fn reports_a_hard_tab_used_for_indentation() {
        assert!(has("\tindented\n", Inspection::HardTab));
        assert!(!has("    indented\n", Inspection::HardTab));
    }

    #[test]
    fn content_inside_a_fence_is_left_alone() {
        // Code is where tabs and trailing spaces are the author's business, and flagging them makes
        // the widget useless for any document containing a Makefile or a Go snippet.
        let fenced = "```make\nall:\n\tcargo build \n```\n";
        assert!(!has(fenced, Inspection::HardTab));
        assert!(!has(fenced, Inspection::TrailingWhitespace));
    }

    #[test]
    fn front_matter_is_left_alone() {
        let with_front_matter = "---\ntitle: A \nkey:\tvalue\n---\n\n# Title\n";
        assert!(!has(with_front_matter, Inspection::TrailingWhitespace));
        assert!(!has(with_front_matter, Inspection::HardTab));
    }

    #[test]
    fn findings_are_ordered_by_position() {
        let text = "# One\n\n### Three\n\n[a]()\n\ntext \n";
        let positions: Vec<u32> = findings(text).iter().map(|f| f.start).collect();
        let mut sorted = positions.clone();
        sorted.sort_unstable();
        assert_eq!(positions, sorted);
    }

    #[test]
    fn offsets_are_utf16_code_units() {
        // "# 제목" is 3 UTF-16 units before the trailing space; a byte offset would report 8.
        let text = "# 제목\n\ntext \n";
        let trailing = findings(text)
            .into_iter()
            .find(|f| f.inspection == Inspection::TrailingWhitespace)
            .expect("expected the trailing-space finding");

        let expected = text[..text.find("text ").unwrap() + 4]
            .encode_utf16()
            .count();
        assert_eq!(trailing.start as usize, expected);
    }

    #[test]
    fn severity_splits_by_what_breaks() {
        assert_eq!(Inspection::UnclosedCodeFence.severity(), Severity::Error);
        assert_eq!(Inspection::HeadingLevelJump.severity(), Severity::Warning);
        assert_eq!(Inspection::TrailingWhitespace.severity(), Severity::Weak);
    }

    #[test]
    fn encoding_round_trips() {
        let mut document = Document::new("# One\n\n### Three\n\ntext \n");
        let bytes = encode(&mut document);
        let expected = run(&mut document);

        let (mut decoder, kind) = Decoder::new(&bytes).expect("valid payload");
        assert_eq!(kind, PayloadKind::Inspections);

        let count = decoder.u32().expect("count") as usize;
        assert_eq!(count, expected.len());
        assert!(count > 0, "the fixture should report something");

        for finding in &expected {
            assert_eq!(decoder.u8().expect("inspection"), finding.inspection as u8);
            assert_eq!(decoder.u8().expect("severity"), finding.severity() as u8);
            assert_eq!(decoder.u32().expect("line"), finding.line);
            assert_eq!(decoder.u32().expect("start"), finding.start);
            assert_eq!(decoder.u32().expect("end"), finding.end);
            assert_eq!(decoder.string().expect("message"), finding.message);
        }
    }

    #[test]
    fn an_empty_document_reports_nothing() {
        assert!(findings("").is_empty());
    }

    #[test]
    fn a_short_table_row_is_reported_too() {
        assert!(has(
            "| A | B | C |\n|---|---|---|\n| 1 | 2 |\n",
            Inspection::TableColumnMismatch
        ));
    }

    #[test]
    fn an_escaped_pipe_is_cell_content_not_a_separator() {
        // `a \| b` is one cell containing a pipe. Counting it as two reports every table using an
        // escaped pipe, which is most tables that document shell commands.
        assert_eq!(count_cells(r"| a \| b | c |"), 2);
        assert!(!has(
            "| A | B |\n|---|---|\n| a \\| b | c |\n",
            Inspection::TableColumnMismatch
        ));
    }

    #[test]
    fn a_row_without_outer_pipes_counts_the_same() {
        assert_eq!(count_cells("a | b | c"), 3);
        assert_eq!(count_cells("| a | b | c |"), 3);
        assert!(!has(
            "A | B\n--- | ---\n1 | 2\n",
            Inspection::TableColumnMismatch
        ));
    }

    #[test]
    fn a_table_inside_a_fence_is_not_inspected() {
        assert!(!has(
            "```\n| A | B |\n|---|---|\n| 1 | 2 | 3 |\n```\n",
            Inspection::TableColumnMismatch
        ));
    }

    #[test]
    fn two_tables_are_inspected_independently() {
        let two = "| A | B |\n|---|---|\n| 1 | 2 |\n\n| C |\n|---|\n| 3 | 4 |\n";
        let reported: Vec<u32> = findings(two)
            .iter()
            .filter(|f| f.inspection == Inspection::TableColumnMismatch)
            .map(|f| f.line)
            .collect();
        assert_eq!(reported, vec![6], "only the second table's row is ragged");
    }

    #[test]
    fn reports_an_undefined_footnote_reference() {
        assert!(has("Text[^missing]\n", Inspection::UndefinedFootnote));
        assert!(!has(
            "Text[^a]\n\n[^a]: Defined.\n",
            Inspection::UndefinedFootnote
        ));
    }

    #[test]
    fn an_undefined_footnote_is_reported_where_it_is_used() {
        let finding = findings("line one\n\nText[^missing]\n")
            .into_iter()
            .find(|f| f.inspection == Inspection::UndefinedFootnote)
            .expect("expected the finding");

        assert_eq!(
            finding.line, 2,
            "should point at the reference, not the top of the file"
        );
    }

    #[test]
    fn a_footnote_inside_a_fence_is_ignored() {
        assert!(!has("```\nText[^a]\n```\n", Inspection::UndefinedFootnote));
        assert!(!has(
            "```\n[^a]: definition\n```\n",
            Inspection::UnusedFootnote
        ));
    }

    #[test]
    fn a_footnote_referenced_twice_is_still_used_once() {
        let text = "A[^a] and B[^a]\n\n[^a]: Defined.\n";
        assert!(!has(text, Inspection::UnusedFootnote));
        assert!(!has(text, Inspection::UndefinedFootnote));
    }

    #[test]
    fn every_finding_reports_a_range_inside_the_document() {
        // The UI slices the buffer with these offsets; one past the end is a crash, not a mis-report.
        let text = "# One\n\n### Three\n\n![](x)\n\n[a][undefined]\n\ntext \t\n\n```\nopen\n";
        let length = text.encode_utf16().count() as u32;

        for finding in findings(text) {
            assert!(finding.start <= length, "{finding:?} starts past the end");
            assert!(finding.end <= length, "{finding:?} ends past the end");
            assert!(finding.start <= finding.end, "{finding:?} is inverted");
        }
    }
}
