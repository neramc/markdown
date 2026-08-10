//! Syntax highlighting for the *Markdown source* shown in the editor.
//!
//! This is a line-oriented lexer rather than a walk over comrak's AST, and that is deliberate. An
//! editor highlights text that is usually mid-edit — an unclosed fence, a half-typed link, a table
//! whose delimiter row does not exist yet. A structural parse of that input produces a tree that
//! disagrees with what the user sees, so colours flicker between keystrokes. A lexer colours exactly
//! the characters that are there.
//!
//! All offsets produced here are UTF-16 code units, measured from the start of the document.

use crate::theme::{EditorStyle, StyleSpan};

/// One source character, tagged with where it starts in UTF-16 units.
#[derive(Debug, Clone, Copy)]
struct Char {
    value: char,
    offset: usize,
    width: usize,
}

/// Block context carried across lines.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum LineMode {
    Normal,
    FrontMatter,
    FencedCode,
}

/// Highlights lines `first_line..=last_line` (zero-based, inclusive).
///
/// Lines before the window are still scanned, because whether a line sits inside a code fence or a
/// front-matter block can only be known from everything above it — but no spans are emitted for
/// them, so highlighting a viewport costs time proportional to the document's line count rather
/// than its size.
pub fn highlight(text: &str, first_line: usize, last_line: usize) -> Vec<StyleSpan> {
    let mut spans = Vec::new();
    // A leading `---` only opens front matter when a closing delimiter exists further down;
    // otherwise it is a thematic break. Deciding this up front keeps the editor's colours consistent
    // with what comrak does for the preview.
    let mut mode = if has_front_matter(text) { LineMode::FrontMatter } else { LineMode::Normal };
    let mut fence_marker: Option<(char, usize)> = None;
    let mut offset = 0usize;
    let mut previous_was_text = false;

    for (index, raw_line) in text.split_inclusive('\n').enumerate() {
        let line = raw_line.strip_suffix('\n').unwrap_or(raw_line);
        let line = line.strip_suffix('\r').unwrap_or(line);
        let emit = index >= first_line && index <= last_line;

        let chars = index_chars(line, offset);
        let line_width: usize = raw_line.chars().map(char::len_utf16).sum();

        let (next_mode, is_text) =
            scan_line(&chars, index, mode, previous_was_text, &mut fence_marker, emit, &mut spans);
        mode = next_mode;
        previous_was_text = is_text;
        offset += line_width;
    }

    spans
}

/// True when the document opens a front-matter block that is actually closed.
fn has_front_matter(text: &str) -> bool {
    let mut lines = text.lines();
    if lines.next().map(str::trim) != Some("---") {
        return false;
    }
    lines.any(|line| line.trim() == "---")
}

/// Tags every character of the line with its absolute UTF-16 offset.
fn index_chars(line: &str, base: usize) -> Vec<Char> {
    let mut offset = base;
    line.chars()
        .map(|value| {
            let width = value.len_utf16();
            let character = Char { value, offset, width };
            offset += width;
            character
        })
        .collect()
}

/// Offset just past the last character of the line (or `fallback` for an empty line).
fn line_end(chars: &[Char], fallback: usize) -> usize {
    chars.last().map_or(fallback, |c| c.offset + c.width)
}

fn push(spans: &mut Vec<StyleSpan>, emit: bool, start: usize, end: usize, style: EditorStyle) {
    if emit && let Some(span) = StyleSpan::new(start, end, style) {
        spans.push(span);
    }
}

/// Byte-offset slice that yields `""` rather than panicking on a bad boundary.
fn remainder(text: &str, byte_offset: usize) -> &str {
    text.get(byte_offset..).unwrap_or("")
}

/// Scans one line, returning the mode for the next line and whether this line is paragraph text
/// (which is what makes a following run of `=` or `-` a setext heading rather than a break).
fn scan_line(
    chars: &[Char],
    line_index: usize,
    mode: LineMode,
    previous_was_text: bool,
    fence_marker: &mut Option<(char, usize)>,
    emit: bool,
    spans: &mut Vec<StyleSpan>,
) -> (LineMode, bool) {
    let start = chars.first().map_or(0, |c| c.offset);
    let end = line_end(chars, start);
    let text: String = chars.iter().map(|c| c.value).collect();
    let trimmed = text.trim_start();
    let indent = text.len() - trimmed.len();

    match mode {
        LineMode::FrontMatter => {
            push(spans, emit, start, end, EditorStyle::FrontMatter);
            // Line 0 is the opening delimiter, not the closing one.
            let closes = line_index > 0 && trimmed == "---";
            return (if closes { LineMode::Normal } else { LineMode::FrontMatter }, false);
        }
        LineMode::FencedCode => {
            // Fence delimiters and the code they wrap share a style: in the source view the whole
            // block should read as one unit, and the syntax colouring of the code itself belongs to
            // the preview, not the editor.
            push(spans, emit, start, end, EditorStyle::CodeFence);
            let closes = fence_marker.is_some_and(|(marker, length)| closing_fence(trimmed, marker, length));
            if closes {
                *fence_marker = None;
                return (LineMode::Normal, false);
            }
            return (LineMode::FencedCode, false);
        }
        LineMode::Normal => {}
    }

    // Opening code fence: ``` or ~~~ followed by an optional info string.
    if let Some((marker, length)) = opening_fence(trimmed) {
        let fence_start = start + utf16_prefix(chars, indent);
        let fence_end = fence_start + length;
        push(spans, emit, fence_start, fence_end, EditorStyle::CodeFence);
        push(spans, emit, fence_end, end, EditorStyle::CodeFenceInfo);
        *fence_marker = Some((marker, length));
        return (LineMode::FencedCode, false);
    }

    // A setext underline only underlines something -- without paragraph text above it, a run of
    // dashes is a thematic break instead. Checking this first keeps `Title\n---` a heading and a
    // bare `---` a rule.
    if previous_was_text
        && !trimmed.is_empty()
        && (trimmed.chars().all(|c| c == '=') || trimmed.chars().all(|c| c == '-'))
    {
        push(spans, emit, start, end, EditorStyle::Heading);
        return (LineMode::Normal, false);
    }

    if is_thematic_break(trimmed) {
        push(spans, emit, start, end, EditorStyle::ThematicBreak);
        return (LineMode::Normal, false);
    }

    // ATX heading — the whole line, so the text reads as a heading in the editor too.
    if trimmed.starts_with('#') {
        let hashes = trimmed.chars().take_while(|c| *c == '#').count();
        if (1..=6).contains(&hashes) && trimmed.chars().nth(hashes).is_none_or(char::is_whitespace) {
            push(spans, emit, start, end, EditorStyle::Heading);
            return (LineMode::Normal, false);
        }
    }

    let mut cursor = indent;

    // Block quote markers, possibly nested (`> > text`). `cursor` is a byte offset that only ever
    // advances over ASCII markers, so it always stays on a character boundary.
    while remainder(&text, cursor).starts_with('>') {
        let marker_start = start + utf16_prefix(chars, cursor);
        cursor += 1;
        let marker_end = start + utf16_prefix(chars, cursor);
        push(spans, emit, marker_start, marker_end, EditorStyle::BlockQuote);
        while remainder(&text, cursor).starts_with(' ') {
            cursor += 1;
        }
    }

    // List marker.
    if let Some(marker_length) = list_marker_length(remainder(&text, cursor)) {
        let marker_start = start + utf16_prefix(chars, cursor);
        let marker_end = start + utf16_prefix(chars, cursor + marker_length);
        push(spans, emit, marker_start, marker_end, EditorStyle::ListMarker);
        cursor += marker_length;
        while remainder(&text, cursor).starts_with(' ') {
            cursor += 1;
        }

        // Task marker directly after a list marker.
        let rest = remainder(&text, cursor);
        if rest.len() >= 3 && rest.starts_with('[') && rest[2..].starts_with(']') {
            let inner = rest.as_bytes()[1];
            if inner == b' ' || inner == b'x' || inner == b'X' {
                let task_start = start + utf16_prefix(chars, cursor);
                let task_end = start + utf16_prefix(chars, cursor + 3);
                push(spans, emit, task_start, task_end, EditorStyle::TaskMarker);
                cursor += 3;
            }
        }
    }

    let inline_start = text.get(..cursor).map_or(0, |prefix| prefix.chars().count());

    // Table pipes.
    if remainder(&text, cursor).contains('|') {
        for character in chars.iter().skip(inline_start) {
            if character.value == '|' {
                push(spans, emit, character.offset, character.offset + character.width, EditorStyle::TableDelimiter);
            }
        }
    }

    scan_inline(chars, inline_start, emit, spans);
    (LineMode::Normal, !trimmed.is_empty())
}

/// UTF-16 width of the first `byte_offset` bytes of the line.
fn utf16_prefix(chars: &[Char], byte_offset: usize) -> usize {
    let mut bytes = 0usize;
    let mut units = 0usize;
    for character in chars {
        if bytes >= byte_offset {
            break;
        }
        bytes += character.value.len_utf8();
        units += character.width;
    }
    units
}

fn opening_fence(trimmed: &str) -> Option<(char, usize)> {
    for marker in ['`', '~'] {
        let length = trimmed.chars().take_while(|c| *c == marker).count();
        if length >= 3 {
            // An info string may not contain a backtick for backtick fences.
            if marker == '`' && trimmed[length..].contains('`') {
                return None;
            }
            return Some((marker, length));
        }
    }
    None
}

fn closing_fence(trimmed: &str, marker: char, length: usize) -> bool {
    let run = trimmed.chars().take_while(|c| *c == marker).count();
    run >= length && trimmed.chars().skip(run).all(char::is_whitespace)
}

fn is_thematic_break(trimmed: &str) -> bool {
    for marker in ['*', '-', '_'] {
        let relevant: String = trimmed.chars().filter(|c| !c.is_whitespace()).collect();
        if relevant.len() >= 3 && relevant.chars().all(|c| c == marker) {
            return true;
        }
    }
    false
}

/// Length in bytes of a leading list marker (`- `, `* `, `1. `, `12) `), if present.
fn list_marker_length(rest: &str) -> Option<usize> {
    let bytes = rest.as_bytes();
    if bytes.is_empty() {
        return None;
    }
    if matches!(bytes[0], b'-' | b'*' | b'+') && bytes.get(1).is_none_or(|b| *b == b' ') {
        return Some(1);
    }
    let digits = rest.chars().take_while(char::is_ascii_digit).count();
    if (1..=9).contains(&digits)
        && let Some(delimiter) = bytes.get(digits)
        && matches!(delimiter, b'.' | b')')
        && bytes.get(digits + 1).is_none_or(|b| *b == b' ')
    {
        return Some(digits + 1);
    }
    None
}

/// Scans inline constructs starting at character index `from`.
fn scan_inline(chars: &[Char], from: usize, emit: bool, spans: &mut Vec<StyleSpan>) {
    let mut index = from;
    while index < chars.len() {
        let current = chars[index].value;
        match current {
            '`' => {
                let run = run_length(chars, index, '`');
                if let Some(close) = find_run(chars, index + run, '`', run) {
                    let end = chars[close + run - 1].offset + chars[close + run - 1].width;
                    push(spans, emit, chars[index].offset, end, EditorStyle::InlineCode);
                    index = close + run;
                    continue;
                }
                index += run;
            }
            '*' | '_' => {
                let run = run_length(chars, index, current);
                if let Some(close) = find_run(chars, index + run, current, run) {
                    let end = chars[close + run - 1].offset + chars[close + run - 1].width;
                    let style = if run >= 2 { EditorStyle::Strong } else { EditorStyle::Emphasis };
                    push(spans, emit, chars[index].offset, end, style);
                    index = close + run;
                    continue;
                }
                index += run;
            }
            '~' => {
                let run = run_length(chars, index, '~');
                if run >= 2
                    && let Some(close) = find_run(chars, index + run, '~', 2)
                {
                    let end = chars[close + 1].offset + chars[close + 1].width;
                    push(spans, emit, chars[index].offset, end, EditorStyle::Strikethrough);
                    index = close + 2;
                    continue;
                }
                index += run;
            }
            '!' if chars.get(index + 1).is_some_and(|c| c.value == '[') => {
                index = scan_link(chars, index, index + 1, EditorStyle::Image, emit, spans);
            }
            '[' => {
                if chars.get(index + 1).is_some_and(|c| c.value == '^')
                    && let Some(close) = find_char(chars, index + 1, ']')
                {
                    let end = chars[close].offset + chars[close].width;
                    push(spans, emit, chars[index].offset, end, EditorStyle::FootnoteReference);
                    index = close + 1;
                    continue;
                }
                index = scan_link(chars, index, index, EditorStyle::LinkText, emit, spans);
            }
            '<' => {
                if let Some(close) = find_char(chars, index + 1, '>') {
                    let content: String = chars[index + 1..close].iter().map(|c| c.value).collect();
                    let end = chars[close].offset + chars[close].width;
                    let style = if content.contains("://") || content.contains('@') {
                        EditorStyle::AutoLink
                    } else {
                        EditorStyle::HtmlTag
                    };
                    // Only treat it as markup when it has no leading space.
                    if !content.is_empty() && !content.starts_with(' ') {
                        push(spans, emit, chars[index].offset, end, style);
                        index = close + 1;
                        continue;
                    }
                }
                index += 1;
            }
            _ => index += 1,
        }
    }
}

/// Handles `[text](url)` / `![alt](url)`. `bracket` is the index of `[`, `outer` the index the span
/// should start at (the `!` for images). Returns the index to continue scanning from.
fn scan_link(
    chars: &[Char],
    outer: usize,
    bracket: usize,
    text_style: EditorStyle,
    emit: bool,
    spans: &mut Vec<StyleSpan>,
) -> usize {
    let Some(close_bracket) = find_char(chars, bracket + 1, ']') else {
        return outer + 1;
    };
    let text_end = chars[close_bracket].offset + chars[close_bracket].width;
    push(spans, emit, chars[outer].offset, text_end, text_style);

    if chars.get(close_bracket + 1).is_some_and(|c| c.value == '(')
        && let Some(close_paren) = find_char(chars, close_bracket + 2, ')')
    {
        let url_end = chars[close_paren].offset + chars[close_paren].width;
        push(spans, emit, chars[close_bracket + 1].offset, url_end, EditorStyle::LinkUrl);
        return close_paren + 1;
    }
    close_bracket + 1
}

fn run_length(chars: &[Char], start: usize, marker: char) -> usize {
    chars[start..].iter().take_while(|c| c.value == marker).count()
}

/// Finds a run of at least `length` `marker` characters at or after `from`.
fn find_run(chars: &[Char], from: usize, marker: char, length: usize) -> Option<usize> {
    let mut index = from;
    while index < chars.len() {
        if chars[index].value == marker {
            let run = run_length(chars, index, marker);
            if run >= length {
                return Some(index);
            }
            index += run;
        } else {
            index += 1;
        }
    }
    None
}

fn find_char(chars: &[Char], from: usize, target: char) -> Option<usize> {
    chars.get(from..)?.iter().position(|c| c.value == target).map(|position| position + from)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn spans_of(text: &str, style: EditorStyle) -> Vec<(usize, usize)> {
        highlight(text, 0, usize::MAX)
            .into_iter()
            .filter(|span| span.style == style)
            .map(|span| (span.start, span.end))
            .collect()
    }

    #[test]
    fn highlights_atx_headings() {
        assert_eq!(spans_of("# Title\n", EditorStyle::Heading), vec![(0, 7)]);
        assert_eq!(spans_of("###### Six\n", EditorStyle::Heading), vec![(0, 10)]);
        assert!(spans_of("####### Seven\n", EditorStyle::Heading).is_empty());
        assert!(spans_of("#NoSpace\n", EditorStyle::Heading).is_empty());
    }

    #[test]
    fn highlights_fenced_code_including_info_string() {
        let text = "```rust\nfn main() {}\n```\n";
        let fences = spans_of(text, EditorStyle::CodeFence);
        assert_eq!(fences.first(), Some(&(0, 3)));
        assert_eq!(spans_of(text, EditorStyle::CodeFenceInfo), vec![(3, 7)]);
        assert_eq!(fences.len(), 3, "opening fence, body and closing fence");
    }

    #[test]
    fn does_not_highlight_inline_markup_inside_a_fence() {
        assert!(spans_of("```\n*not emphasis*\n```\n", EditorStyle::Emphasis).is_empty());
    }

    #[test]
    fn an_unclosed_fence_keeps_colouring_to_the_end() {
        // Mid-typing state: the user has opened a fence and not closed it yet.
        assert_eq!(spans_of("```\nstill code\nand more\n", EditorStyle::CodeFence).len(), 3);
    }

    #[test]
    fn a_leading_dash_rule_is_only_front_matter_when_it_closes() {
        assert_eq!(spans_of("---\nsome text\n", EditorStyle::ThematicBreak), vec![(0, 3)]);
        assert!(spans_of("---\nsome text\n", EditorStyle::FrontMatter).is_empty());
        assert_eq!(spans_of("---\nkey: v\n---\n", EditorStyle::FrontMatter).len(), 3);
    }

    #[test]
    fn distinguishes_setext_headings_from_thematic_breaks() {
        // The same three dashes mean different things depending on what precedes them.
        assert_eq!(spans_of("Title\n---\n", EditorStyle::Heading), vec![(6, 9)]);
        assert!(spans_of("Title\n---\n", EditorStyle::ThematicBreak).is_empty());
        assert_eq!(spans_of("Para\n\n---\n", EditorStyle::ThematicBreak), vec![(6, 9)]);
        assert_eq!(spans_of("Title\n===\n", EditorStyle::Heading), vec![(6, 9)]);
    }

    #[test]
    fn highlights_emphasis_strong_and_strikethrough() {
        assert_eq!(spans_of("*em*\n", EditorStyle::Emphasis), vec![(0, 4)]);
        assert_eq!(spans_of("**strong**\n", EditorStyle::Strong), vec![(0, 10)]);
        assert_eq!(spans_of("~~gone~~\n", EditorStyle::Strikethrough), vec![(0, 8)]);
    }

    #[test]
    fn highlights_inline_code() {
        assert_eq!(spans_of("use `code` here\n", EditorStyle::InlineCode), vec![(4, 10)]);
        // An unmatched backtick must not swallow the rest of the line.
        assert!(spans_of("unmatched ` tick\n", EditorStyle::InlineCode).is_empty());
    }

    #[test]
    fn highlights_links_and_images() {
        let text = "[label](https://example.com)\n";
        assert_eq!(spans_of(text, EditorStyle::LinkText), vec![(0, 7)]);
        assert_eq!(spans_of(text, EditorStyle::LinkUrl), vec![(7, 28)]);
        assert_eq!(spans_of("![alt](img.png)\n", EditorStyle::Image), vec![(0, 6)]);
    }

    #[test]
    fn highlights_list_and_task_markers() {
        assert_eq!(spans_of("- item\n", EditorStyle::ListMarker), vec![(0, 1)]);
        assert_eq!(spans_of("12) item\n", EditorStyle::ListMarker), vec![(0, 3)]);

        let task = "- [x] done\n";
        assert_eq!(spans_of(task, EditorStyle::ListMarker), vec![(0, 1)]);
        assert_eq!(spans_of(task, EditorStyle::TaskMarker), vec![(2, 5)]);
    }

    #[test]
    fn highlights_block_quotes_tables_and_autolinks() {
        assert_eq!(spans_of("> quoted\n", EditorStyle::BlockQuote), vec![(0, 1)]);
        assert_eq!(spans_of("| a | b |\n", EditorStyle::TableDelimiter).len(), 3);
        assert_eq!(spans_of("<https://example.com>\n", EditorStyle::AutoLink), vec![(0, 21)]);
        assert_eq!(spans_of("<br/>\n", EditorStyle::HtmlTag), vec![(0, 5)]);
    }

    #[test]
    fn offsets_are_utf16_not_bytes() {
        // "한국어" is 3 UTF-16 units but 9 UTF-8 bytes; emphasis must start at 4, not 10.
        assert_eq!(spans_of("한국어 *강조*\n", EditorStyle::Emphasis), vec![(4, 8)]);
    }

    #[test]
    fn astral_characters_advance_two_units() {
        // The emoji occupies units 0..2 and the space 2..3, so emphasis starts at 3.
        assert_eq!(spans_of("🪶 *em*\n", EditorStyle::Emphasis), vec![(3, 7)]);
    }

    #[test]
    fn windowing_limits_emitted_spans_but_not_fence_state() {
        let text = "```\ncode\n```\n# Heading\n";
        let windowed = highlight(text, 3, 3);
        assert_eq!(windowed.len(), 1);
        assert_eq!(windowed[0].style, EditorStyle::Heading);

        // Requesting a line inside the fence still knows it is inside the fence.
        let inside = highlight(text, 1, 1);
        assert_eq!(inside.len(), 1);
        assert_eq!(inside[0].style, EditorStyle::CodeFence);
    }

    #[test]
    fn handles_empty_whitespace_and_crlf_documents() {
        assert!(highlight("", 0, usize::MAX).is_empty());
        assert!(highlight("\n\n\n", 0, usize::MAX).is_empty());
        assert_eq!(spans_of("# Title\r\n\r\ntext\r\n", EditorStyle::Heading), vec![(0, 7)]);
    }

    #[test]
    fn plain_prose_produces_no_spans() {
        assert!(highlight("Just a normal paragraph with no markup at all.\n", 0, usize::MAX).is_empty());
    }
}
