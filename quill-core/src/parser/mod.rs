//! Markdown parsing: comrak's AST walked straight into the QWIRE block stream.
//!
//! There is deliberately no intermediate Rust tree. comrak already owns an arena-allocated AST, so
//! building a second tree only to serialise it would double the allocation cost of every
//! keystroke-triggered re-parse.

pub mod ir;

use comrak::nodes::{AlertType, AstNode, ListDelimType, ListType, NodeValue, TableAlignment};
use comrak::{Arena, Options, parse_document};

use crate::flavour::Flavour;
use crate::wire::{Encoder, PayloadKind};

/// The parser configuration a dialect asks for.
///
/// This is the only place [`crate::flavour::Extensions`] is turned into comrak's own option struct,
/// so "which dialect is this document" is decided once, in `flavour.rs`, and applied once, here.
pub fn options_for(flavour: Flavour) -> Options<'static> {
    let extensions = flavour.extensions();
    let mut options = Options::default();

    options.extension.strikethrough = extensions.strikethrough;
    options.extension.table = extensions.tables;
    options.extension.autolink = extensions.autolink;
    options.extension.tasklist = extensions.tasklist;
    options.extension.footnotes = extensions.footnotes;
    options.extension.inline_footnotes = extensions.inline_footnotes;
    options.extension.alerts = extensions.alerts;
    options.extension.tagfilter = extensions.tagfilter;
    options.extension.description_lists = extensions.description_lists;
    options.extension.math_dollars = extensions.math_dollars;
    options.extension.math_latex = extensions.math_latex;
    options.extension.superscript = extensions.sub_superscript;
    options.extension.subscript = extensions.sub_superscript;
    options.extension.highlight = extensions.highlight;
    options.extension.underline = extensions.underline;
    options.extension.multiline_block_quotes = extensions.multiline_block_quotes;
    options.extension.block_directive = extensions.block_directives;
    options.extension.header_attributes = extensions.header_attributes;
    options.extension.shortcodes = extensions.shortcodes;
    // `header_ids` is deliberately *not* applied here. comrak implements it by injecting an empty
    // `<a class="anchor">` into every heading, which is what a published page wants and what a
    // preview pane does not: on screen it is an invisible element that nonetheless takes a click
    // and shifts the caret. The exporter turns it on for standalone documents instead, which is
    // where a `#section` link has somewhere to point.
    //
    // `[[target|title]]`, the form MyST and every wiki that grew out of it use.
    options.extension.wikilinks_title_after_pipe = extensions.wikilinks;

    // Front matter is not a dialect feature: every one of these is written in files that open with
    // a YAML block, and parsing it as a thematic break followed by a setext heading is wrong in all
    // of them.
    options.extension.front_matter_delimiter = Some("---".to_owned());

    options.parse.smart = false;
    // Source positions are what make editor <-> preview scroll synchronisation possible.
    options.render.sourcepos = true;
    // Raw HTML is not trusted by default; the preview renders it as literal text.
    options.render.r#unsafe = flavour.allows_raw_html();
    options.render.hardbreaks = false;

    options
}

/// Parser configuration for the default dialect.
///
/// Used by the derivations that are about a document's *shape* rather than its dialect — the
/// outline, the word count, the inspections. Reading those under the widest common set means a
/// heading is a heading whichever dialect the file is in.
pub fn options() -> Options<'static> {
    options_for(Flavour::default())
}

/// Zero-based inclusive line range for a node.
fn line_range(node: &AstNode<'_>) -> (u32, u32) {
    let sourcepos = node.data.borrow().sourcepos;
    // comrak reports 1-based lines; the JVM side works with 0-based ones.
    let start = sourcepos.start.line.saturating_sub(1) as u32;
    let end = sourcepos.end.line.saturating_sub(1) as u32;
    (start, end.max(start))
}

/// Flattens a subtree to its plain text, used for image alt text and outline titles.
///
/// The lifetime is named on both the reference and the node because comrak's arena nodes are
/// invariant over their lifetime; `&AstNode<'_>` does not compile for anything that walks children.
pub fn plain_text<'a>(node: &'a AstNode<'a>) -> String {
    let mut output = String::new();
    collect_plain_text(node, &mut output);
    output
}

fn collect_plain_text<'a>(node: &'a AstNode<'a>, output: &mut String) {
    match &node.data.borrow().value {
        NodeValue::Text(text) => output.push_str(text),
        NodeValue::Code(code) => output.push_str(&code.literal),
        NodeValue::SoftBreak | NodeValue::LineBreak => output.push(' '),
        _ => {}
    }
    for child in node.children() {
        collect_plain_text(child, output);
    }
}

fn alignment_tag(alignment: TableAlignment) -> u8 {
    match alignment {
        TableAlignment::None => ir::alignment::NONE,
        TableAlignment::Left => ir::alignment::LEFT,
        TableAlignment::Center => ir::alignment::CENTER,
        TableAlignment::Right => ir::alignment::RIGHT,
    }
}

fn alert_tag(alert_type: AlertType) -> u8 {
    match alert_type {
        AlertType::Note => ir::alert::NOTE,
        AlertType::Tip => ir::alert::TIP,
        AlertType::Important => ir::alert::IMPORTANT,
        AlertType::Warning => ir::alert::WARNING,
        AlertType::Caution => ir::alert::CAUTION,
    }
}

/// Encodes a document's blocks as a [`PayloadKind::Blocks`] payload, read in [`Flavour`]'s dialect.
///
/// The *original* text is parsed rather than [`crate::flavour::prepare`]'s rewrite, because every
/// block here carries a source line range and the rewrite does not preserve line numbering for
/// every dialect. A block IR whose line ranges point at the wrong lines would break scroll
/// synchronisation and caret-following in the structure view, which is worse than showing a Markdoc
/// annotation as a paragraph.
pub fn encode_blocks(text: &str, flavour: Flavour) -> Vec<u8> {
    let arena = Arena::new();
    let root = parse_document(&arena, text, &options_for(flavour));

    let mut encoder = Encoder::new(PayloadKind::Blocks);
    let count_slot = encoder.reserve_u32();
    let mut count = 0usize;
    for child in root.children() {
        if encode_block(child, 0, &mut encoder) {
            count += 1;
        }
    }
    encoder.patch_u32(count_slot, count);
    encoder.finish()
}

/// Writes one block node. Returns `false` for nodes with no visual representation, so the caller
/// can keep its child count accurate.
fn encode_block<'a>(node: &'a AstNode<'a>, list_level: u32, encoder: &mut Encoder) -> bool {
    let (line_start, line_end) = line_range(node);
    let value = node.data.borrow().value.clone();

    match value {
        NodeValue::Paragraph => {
            write_header(encoder, ir::block::PARAGRAPH, line_start, line_end);
            encode_inline_children(node, encoder);
        }
        NodeValue::Heading(heading) => {
            write_header(encoder, ir::block::HEADING, line_start, line_end);
            encoder.put_u8(heading.level);
            encode_inline_children(node, encoder);
        }
        // A multi-line block quote and a MyST directive are both containers whose contents are
        // ordinary blocks. Rendering them as a quote keeps the nesting the writer sees, which is
        // the part the structure view and the scroll synchroniser depend on.
        NodeValue::BlockQuote
        | NodeValue::MultilineBlockQuote(_)
        | NodeValue::BlockDirective(_) => {
            write_header(encoder, ir::block::BLOCK_QUOTE, line_start, line_end);
            encode_block_children(node, list_level, encoder);
        }
        // A definition list is a list of terms, each with its details indented beneath — which is
        // exactly a tight bullet list of items in the vocabulary the IR already has.
        NodeValue::DescriptionList => {
            write_header(encoder, ir::block::UNORDERED_LIST, line_start, line_end);
            encoder.put_bool(true);
            encoder.put_str("-");
            encode_block_children(node, list_level + 1, encoder);
        }
        NodeValue::DescriptionItem(_) => {
            write_header(encoder, ir::block::LIST_ITEM, line_start, line_end);
            encoder.put_u32(list_level.saturating_sub(1));
            encoder.put_u8(ir::task::NONE);
            encode_block_children(node, list_level, encoder);
        }
        NodeValue::DescriptionTerm | NodeValue::DescriptionDetails => {
            write_header(encoder, ir::block::PARAGRAPH, line_start, line_end);
            encode_inline_children(node, encoder);
        }
        // Display maths is a block of LaTeX. It goes over as a fenced block tagged `math`, which
        // gives the preview a language to switch on and the editor something to leave alone.
        NodeValue::Math(math) if math.display_math => {
            write_header(encoder, ir::block::FENCED_CODE_BLOCK, line_start, line_end);
            encoder.put_opt_str(Some("math"));
            encoder.put_str(&math.literal);
            encoder.put_u32(0);
        }
        NodeValue::List(list) => {
            let tag = match list.list_type {
                ListType::Ordered => ir::block::ORDERED_LIST,
                ListType::Bullet => ir::block::UNORDERED_LIST,
            };
            write_header(encoder, tag, line_start, line_end);
            encoder.put_bool(list.tight);
            match list.list_type {
                ListType::Ordered => {
                    encoder.put_len(list.start);
                    encoder.put_str(match list.delimiter {
                        ListDelimType::Period => ".",
                        ListDelimType::Paren => ")",
                    });
                }
                ListType::Bullet => {
                    let bullet = char::from(if list.bullet_char == 0 {
                        b'-'
                    } else {
                        list.bullet_char
                    });
                    encoder.put_str(&bullet.to_string());
                }
            }
            encode_block_children(node, list_level + 1, encoder);
        }
        NodeValue::Item(_) => {
            write_header(encoder, ir::block::LIST_ITEM, line_start, line_end);
            encoder.put_u32(list_level.saturating_sub(1));
            encoder.put_u8(ir::task::NONE);
            encode_block_children(node, list_level, encoder);
        }
        NodeValue::TaskItem(task_item) => {
            write_header(encoder, ir::block::LIST_ITEM, line_start, line_end);
            encoder.put_u32(list_level.saturating_sub(1));
            // comrak stores the character inside the brackets: `None` for `[ ]`, otherwise the
            // check mark that was used (`x`, `X`, ...).
            let state = match task_item.symbol {
                Some(_) => ir::task::CHECKED,
                None => ir::task::UNCHECKED,
            };
            encoder.put_u8(state);
            encode_block_children(node, list_level, encoder);
        }
        NodeValue::CodeBlock(code_block) => {
            if code_block.fenced {
                write_header(encoder, ir::block::FENCED_CODE_BLOCK, line_start, line_end);
                // The info string may carry more than a language ("rust,no_run"); Jewel wants the
                // bare language token.
                let language = code_block
                    .info
                    .split(|c: char| c == ',' || c.is_whitespace())
                    .next()
                    .unwrap_or("");
                encoder.put_opt_str(if language.is_empty() {
                    None
                } else {
                    Some(language)
                });
                encoder.put_str(&code_block.literal);
            } else {
                write_header(
                    encoder,
                    ir::block::INDENTED_CODE_BLOCK,
                    line_start,
                    line_end,
                );
                encoder.put_str(&code_block.literal);
            }
            encoder.put_u32(0);
        }
        NodeValue::HtmlBlock(html) => {
            write_header(encoder, ir::block::HTML_BLOCK, line_start, line_end);
            encoder.put_str(&html.literal);
            encoder.put_u32(0);
        }
        NodeValue::ThematicBreak => {
            write_header(encoder, ir::block::THEMATIC_BREAK, line_start, line_end);
            encoder.put_u32(0);
        }
        NodeValue::FrontMatter(content) => {
            write_header(encoder, ir::block::FRONT_MATTER, line_start, line_end);
            encoder.put_str(&content);
            encoder.put_u32(0);
        }
        NodeValue::Table(table) => {
            write_header(encoder, ir::block::TABLE, line_start, line_end);
            encoder.put_len(table.num_columns);
            encoder.put_len(table.alignments.len());
            for alignment in &table.alignments {
                encoder.put_u8(alignment_tag(*alignment));
            }
            encode_block_children(node, list_level, encoder);
        }
        NodeValue::TableRow(is_header) => {
            write_header(encoder, ir::block::TABLE_ROW, line_start, line_end);
            encoder.put_bool(is_header);
            encode_block_children(node, list_level, encoder);
        }
        NodeValue::TableCell => {
            write_header(encoder, ir::block::TABLE_CELL, line_start, line_end);
            encode_inline_children(node, encoder);
        }
        NodeValue::FootnoteDefinition(definition) => {
            write_header(
                encoder,
                ir::block::FOOTNOTE_DEFINITION,
                line_start,
                line_end,
            );
            encoder.put_str(&definition.name);
            encode_block_children(node, list_level, encoder);
        }
        NodeValue::Alert(alert) => {
            write_header(encoder, ir::block::ALERT, line_start, line_end);
            encoder.put_u8(alert_tag(alert.alert_type));
            encoder.put_opt_str(alert.title.as_deref());
            encode_block_children(node, list_level, encoder);
        }
        // Description lists, math blocks and the other niche extensions are disabled in
        // `options()`; if one ever appears, degrade to a paragraph rather than dropping content.
        _ => {
            if node.children().next().is_none() {
                return false;
            }
            write_header(encoder, ir::block::PARAGRAPH, line_start, line_end);
            encode_inline_children(node, encoder);
        }
    }
    true
}

fn write_header(encoder: &mut Encoder, tag: u8, line_start: u32, line_end: u32) {
    encoder.put_u8(tag);
    encoder.put_u32(line_start);
    encoder.put_u32(line_end);
}

fn encode_block_children<'a>(node: &'a AstNode<'a>, list_level: u32, encoder: &mut Encoder) {
    let count_slot = encoder.reserve_u32();
    let mut count = 0usize;
    for child in node.children() {
        if encode_block(child, list_level, encoder) {
            count += 1;
        }
    }
    encoder.patch_u32(count_slot, count);
}

fn encode_inline_children<'a>(node: &'a AstNode<'a>, encoder: &mut Encoder) {
    let count_slot = encoder.reserve_u32();
    let mut count = 0usize;
    for child in node.children() {
        if encode_inline(child, encoder) {
            count += 1;
        }
    }
    encoder.patch_u32(count_slot, count);
}

/// Writes one inline node. Inline nodes carry no line range: Jewel's `InlineMarkdown` has no use for
/// it, and scroll synchronisation works at block granularity.
fn encode_inline<'a>(node: &'a AstNode<'a>, encoder: &mut Encoder) -> bool {
    let value = node.data.borrow().value.clone();

    match value {
        NodeValue::Text(text) => {
            encoder.put_u8(ir::inline::TEXT);
            encoder.put_str(&text);
            encoder.put_u32(0);
        }
        NodeValue::Emph => {
            encoder.put_u8(ir::inline::EMPHASIS);
            encoder.put_str("*");
            encode_inline_children(node, encoder);
        }
        NodeValue::Strong => {
            encoder.put_u8(ir::inline::STRONG_EMPHASIS);
            encoder.put_str("**");
            encode_inline_children(node, encoder);
        }
        NodeValue::Strikethrough => {
            encoder.put_u8(ir::inline::STRIKETHROUGH);
            encoder.put_str("~~");
            encode_inline_children(node, encoder);
        }
        NodeValue::Code(code) => {
            encoder.put_u8(ir::inline::CODE);
            encoder.put_str(&code.literal);
            encoder.put_u32(0);
        }
        // Inline maths reaches the IR as code, which is the closest true statement the vocabulary
        // can make: a run of source that is not prose and must not be re-wrapped or spell-checked.
        NodeValue::Math(math) => {
            encoder.put_u8(ir::inline::CODE);
            encoder.put_str(&math.literal);
            encoder.put_u32(0);
        }
        // The dialects' own emphases. Each keeps its source marker so a round trip through the IR
        // still says which dialect wrote it, rather than flattening every one of them to italics.
        NodeValue::Underline => {
            encoder.put_u8(ir::inline::EMPHASIS);
            encoder.put_str("__");
            encode_inline_children(node, encoder);
        }
        NodeValue::Highlight => {
            encoder.put_u8(ir::inline::EMPHASIS);
            encoder.put_str("==");
            encode_inline_children(node, encoder);
        }
        NodeValue::Superscript => {
            encoder.put_u8(ir::inline::EMPHASIS);
            encoder.put_str("^");
            encode_inline_children(node, encoder);
        }
        NodeValue::Subscript => {
            encoder.put_u8(ir::inline::EMPHASIS);
            encoder.put_str("~");
            encode_inline_children(node, encoder);
        }
        // A wiki link's target is its URL; its children are the title, if one was given after the
        // pipe, and otherwise the target itself.
        NodeValue::WikiLink(link) => {
            encoder.put_u8(ir::inline::LINK);
            encoder.put_str(&link.url);
            encoder.put_opt_str(None);
            encode_inline_children(node, encoder);
        }
        NodeValue::Link(link) => {
            encoder.put_u8(ir::inline::LINK);
            encoder.put_str(&link.url);
            encoder.put_opt_str(if link.title.is_empty() {
                None
            } else {
                Some(&link.title)
            });
            encode_inline_children(node, encoder);
        }
        NodeValue::Image(link) => {
            encoder.put_u8(ir::inline::IMAGE);
            encoder.put_str(&link.url);
            encoder.put_str(&plain_text(node));
            encoder.put_opt_str(if link.title.is_empty() {
                None
            } else {
                Some(&link.title)
            });
            encode_inline_children(node, encoder);
        }
        NodeValue::HtmlInline(html) => {
            encoder.put_u8(ir::inline::HTML_INLINE);
            encoder.put_str(&html);
            encoder.put_u32(0);
        }
        NodeValue::SoftBreak => {
            encoder.put_u8(ir::inline::SOFT_LINE_BREAK);
            encoder.put_u32(0);
        }
        NodeValue::LineBreak => {
            encoder.put_u8(ir::inline::HARD_LINE_BREAK);
            encoder.put_u32(0);
        }
        NodeValue::FootnoteReference(reference) => {
            encoder.put_u8(ir::inline::FOOTNOTE_REFERENCE);
            encoder.put_str(&reference.name);
            encoder.put_u32(0);
        }
        // Anything else (escaped runs, wiki links, ...) still has readable text content.
        _ => {
            let text = plain_text(node);
            if text.is_empty() {
                return false;
            }
            encoder.put_u8(ir::inline::TEXT);
            encoder.put_str(&text);
            encoder.put_u32(0);
        }
    }
    true
}

/// Converts a document to HTML using the dialect's own rules.
///
/// This is the single conversion the preview and the export both go through, which is what keeps
/// what you see on screen and what you publish from drifting apart.
pub fn to_html_for(text: &str, flavour: Flavour) -> String {
    let prepared = crate::flavour::prepare(text, flavour);

    let mut render_options = options_for(flavour);
    // Markdoc's annotations were rewritten into `<div>` wrappers a moment ago, so its own output
    // has to survive rather than be escaped back into visible angle brackets.
    render_options.render.r#unsafe = flavour.allows_raw_html() || flavour == Flavour::Markdoc;
    render_options.render.sourcepos = false;

    let arena = Arena::new();
    let root = parse_document(&arena, &prepared.text, &render_options);
    let mut html = String::new();
    // format_html writes through std::fmt::Write and only fails if the sink fails, which writing
    // into a String cannot.
    if comrak::format_html(root, &render_options, &mut html).is_err() {
        return String::new();
    }
    html
}

/// Renders the document to HTML with the default dialect's extension set.
pub fn to_html(text: &str, allow_raw_html: bool) -> String {
    let mut render_options = options();
    render_options.render.r#unsafe = allow_raw_html;
    render_options.render.sourcepos = false;

    let arena = Arena::new();
    let root = parse_document(&arena, text, &render_options);
    let mut output = String::new();
    // format_html writes through std::fmt::Write and only fails if the sink fails, which writing
    // into a String cannot.
    if comrak::format_html(root, &render_options, &mut output).is_err() {
        return String::new();
    }
    output
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wire::Decoder;

    /// The block IR under the default dialect, which is what most of these cases are about.
    fn encode_blocks_gfm(text: &str) -> Vec<u8> {
        encode_blocks(text, Flavour::Gfm)
    }

    /// Reads a block header and returns `(tag, line_start, line_end)`.
    fn read_header(decoder: &mut Decoder<'_>) -> (u8, u32, u32) {
        (
            decoder.u8().unwrap(),
            decoder.u32().unwrap(),
            decoder.u32().unwrap(),
        )
    }

    #[test]
    fn encodes_a_heading_and_paragraph() {
        let bytes = encode_blocks_gfm("# Title\n\nSome *text*.\n");
        let (mut decoder, kind) = Decoder::new(&bytes).unwrap();
        assert_eq!(kind, PayloadKind::Blocks);
        assert_eq!(decoder.u32().unwrap(), 2, "two top-level blocks");

        let (tag, start, _) = read_header(&mut decoder);
        assert_eq!(tag, ir::block::HEADING);
        assert_eq!(start, 0);
        assert_eq!(decoder.u8().unwrap(), 1, "heading level");
        assert_eq!(decoder.u32().unwrap(), 1, "one inline child");
        assert_eq!(decoder.u8().unwrap(), ir::inline::TEXT);
        assert_eq!(decoder.string().unwrap(), "Title");
        assert_eq!(decoder.u32().unwrap(), 0);

        let (tag, start, _) = read_header(&mut decoder);
        assert_eq!(tag, ir::block::PARAGRAPH);
        assert_eq!(start, 2, "paragraph starts on the third line, zero-based");
    }

    #[test]
    fn encodes_fenced_code_with_language() {
        let bytes = encode_blocks_gfm("```rust,no_run\nfn main() {}\n```\n");
        let (mut decoder, _) = Decoder::new(&bytes).unwrap();
        assert_eq!(decoder.u32().unwrap(), 1);

        let (tag, _, _) = read_header(&mut decoder);
        assert_eq!(tag, ir::block::FENCED_CODE_BLOCK);
        // The info string is trimmed to the bare language token.
        assert_eq!(decoder.opt_string().unwrap(), Some("rust".to_owned()));
        assert_eq!(decoder.string().unwrap(), "fn main() {}\n");
    }

    #[test]
    fn encodes_fenced_code_without_language_as_absent() {
        let bytes = encode_blocks_gfm("```\nplain\n```\n");
        let (mut decoder, _) = Decoder::new(&bytes).unwrap();
        decoder.u32().unwrap();
        read_header(&mut decoder);
        assert_eq!(decoder.opt_string().unwrap(), None);
    }

    #[test]
    fn encodes_task_list_state() {
        let bytes = encode_blocks_gfm("- [x] done\n- [ ] todo\n");
        let (mut decoder, _) = Decoder::new(&bytes).unwrap();
        assert_eq!(decoder.u32().unwrap(), 1, "a single list block");

        let (tag, _, _) = read_header(&mut decoder);
        assert_eq!(tag, ir::block::UNORDERED_LIST);
        let _tight = decoder.bool().unwrap();
        let _marker = decoder.string().unwrap();
        assert_eq!(decoder.u32().unwrap(), 2, "two items");

        let (tag, _, _) = read_header(&mut decoder);
        assert_eq!(tag, ir::block::LIST_ITEM);
        assert_eq!(decoder.u32().unwrap(), 0, "nesting level");
        assert_eq!(decoder.u8().unwrap(), ir::task::CHECKED);
    }

    #[test]
    fn encodes_ordered_list_start_and_delimiter() {
        let bytes = encode_blocks_gfm("3) first\n4) second\n");
        let (mut decoder, _) = Decoder::new(&bytes).unwrap();
        decoder.u32().unwrap();
        let (tag, _, _) = read_header(&mut decoder);
        assert_eq!(tag, ir::block::ORDERED_LIST);
        let _tight = decoder.bool().unwrap();
        assert_eq!(decoder.u32().unwrap(), 3, "list starts at 3");
        assert_eq!(decoder.string().unwrap(), ")");
    }

    #[test]
    fn encodes_gfm_table_alignments() {
        let bytes = encode_blocks_gfm("| a | b |\n|:--|--:|\n| 1 | 2 |\n");
        let (mut decoder, _) = Decoder::new(&bytes).unwrap();
        decoder.u32().unwrap();

        let (tag, _, _) = read_header(&mut decoder);
        assert_eq!(tag, ir::block::TABLE);
        assert_eq!(decoder.u32().unwrap(), 2, "column count");
        assert_eq!(decoder.u32().unwrap(), 2, "alignment count");
        assert_eq!(decoder.u8().unwrap(), ir::alignment::LEFT);
        assert_eq!(decoder.u8().unwrap(), ir::alignment::RIGHT);
    }

    #[test]
    fn encodes_strikethrough_and_links() {
        let bytes = encode_blocks_gfm("~~gone~~ and [text](https://example.com \"t\")\n");
        let (mut decoder, _) = Decoder::new(&bytes).unwrap();
        decoder.u32().unwrap();
        read_header(&mut decoder);
        assert!(decoder.u32().unwrap() >= 3);

        assert_eq!(decoder.u8().unwrap(), ir::inline::STRIKETHROUGH);
        assert_eq!(decoder.string().unwrap(), "~~");
        assert_eq!(decoder.u32().unwrap(), 1);
        assert_eq!(decoder.u8().unwrap(), ir::inline::TEXT);
        assert_eq!(decoder.string().unwrap(), "gone");
    }

    #[test]
    fn handles_empty_input() {
        let bytes = encode_blocks_gfm("");
        let (mut decoder, kind) = Decoder::new(&bytes).unwrap();
        assert_eq!(kind, PayloadKind::Blocks);
        assert_eq!(decoder.u32().unwrap(), 0);
        assert!(decoder.is_exhausted());
    }

    #[test]
    fn renders_html_and_escapes_raw_html_by_default() {
        let html = to_html("# Hi\n\n<script>alert(1)</script>\n", false);
        assert!(html.contains("<h1>Hi</h1>"));
        assert!(
            !html.contains("<script>"),
            "raw HTML must be escaped unless explicitly allowed"
        );

        // Allowing raw HTML lets markup through, but not scripting: the default dialect is GFM,
        // and GitHub's tag filter is part of it. A `<script>` in a Markdown file somebody sent you
        // stays inert whatever the render flag says.
        assert!(to_html("<figure>x</figure>\n", true).contains("<figure>"));
        assert!(!to_html("<script>x</script>\n", true).contains("<script>"));
    }

    #[test]
    fn renders_gfm_extensions_to_html() {
        let html = to_html("| a |\n|---|\n| 1 |\n\n~~x~~\n", false);
        assert!(html.contains("<table>"));
        assert!(html.contains("<del>"));
    }

    // ---------------------------------------------------------------- dialects

    #[test]
    fn maths_is_maths_in_myst_and_literal_text_in_commonmark() {
        // The single clearest demonstration that the dialect reaches the parser: the same three
        // characters are a formula in one dialect and three characters in another.
        let source = "The value $x^2$ matters.\n";

        let myst = to_html_for(source, Flavour::MyST);
        assert!(
            myst.contains("data-math-style"),
            "MyST should read $..$ as maths, got: {myst}",
        );

        let commonmark = to_html_for(source, Flavour::CommonMark);
        assert!(
            commonmark.contains("$x^2$"),
            "CommonMark has no maths and must leave the dollars alone, got: {commonmark}",
        );
    }

    #[test]
    fn tables_are_a_github_and_pandoc_feature_and_not_a_commonmark_one() {
        let source = "| a | b |\n|---|---|\n| 1 | 2 |\n";

        for dialect in [Flavour::Gfm, Flavour::Pandoc, Flavour::MultiMarkdown] {
            assert!(
                to_html_for(source, dialect).contains("<table>"),
                "{dialect} should have tables",
            );
        }
        assert!(
            !to_html_for(source, Flavour::CommonMark).contains("<table>"),
            "CommonMark has no tables; the pipes are ordinary text",
        );
    }

    #[test]
    fn pandoc_reads_subscripts_that_gfm_leaves_as_tildes() {
        let source = "H~2~O\n";
        assert!(to_html_for(source, Flavour::Pandoc).contains("<sub>"));
        // In GFM a single tilde pair is not strikethrough either, so it stays as written.
        assert!(!to_html_for(source, Flavour::Gfm).contains("<sub>"));
    }

    #[test]
    fn markdown_extra_has_definition_lists_and_no_task_lists() {
        let definitions = "Term\n\n: The definition.\n";
        assert!(
            to_html_for(definitions, Flavour::MarkdownExtra).contains("<dl>"),
            "definition lists are the reason Markdown Extra exists",
        );
        assert!(!to_html_for(definitions, Flavour::Gfm).contains("<dl>"));

        let tasks = "- [x] done\n";
        assert!(to_html_for(tasks, Flavour::Gfm).contains("type=\"checkbox\""));
        assert!(
            !to_html_for(tasks, Flavour::MarkdownExtra).contains("type=\"checkbox\""),
            "task lists are GitHub's addition and predate nothing in Markdown Extra",
        );
    }

    #[test]
    fn alerts_are_a_github_extension_and_a_plain_quote_elsewhere() {
        let source = "> [!NOTE]\n> Mind the gap.\n";
        assert!(to_html_for(source, Flavour::Gfm).contains("markdown-alert"));

        let pandoc = to_html_for(source, Flavour::Pandoc);
        assert!(pandoc.contains("<blockquote>"), "still a quote: {pandoc}");
        assert!(!pandoc.contains("markdown-alert"));
    }

    #[test]
    fn every_dialect_renders_the_common_core_the_same_way() {
        // Whatever else changes, a heading, a list and emphasis mean the same thing everywhere.
        // Without this the table in `flavour.rs` could disable something load-bearing and only the
        // dialect-specific tests above would notice.
        let source = "# Title\n\n- one\n- two\n\nSome *emphasis* and `code`.\n";
        for dialect in Flavour::all() {
            let html = to_html_for(source, dialect);
            assert!(html.contains("Title</h1>"), "{dialect} lost its heading");
            assert!(html.contains("<li>one</li>"), "{dialect} lost its list");
            assert!(html.contains("<em>emphasis</em>"), "{dialect} lost emphasis");
            assert!(html.contains("<code>code</code>"), "{dialect} lost code");
        }
    }

    #[test]
    fn dialect_specific_blocks_survive_into_the_ir() {
        // A definition list has no tag of its own in the IR, so it is carried as the tight bullet
        // list it visually is. What matters is that the terms and definitions are not dropped.
        let bytes = encode_blocks("Term\n\n: The definition.\n", Flavour::Pandoc);
        let (mut decoder, _) = Decoder::new(&bytes).unwrap();
        assert_eq!(decoder.u32().unwrap(), 1, "one top-level block");
        let (tag, _, _) = read_header(&mut decoder);
        assert_eq!(tag, ir::block::UNORDERED_LIST);
    }

    #[test]
    fn maths_reaches_the_ir_as_code_rather_than_as_prose() {
        // comrak treats `$$…$$` as an inline inside its paragraph rather than as a block of its
        // own, so the IR carries it as inline code — which is the true statement about it: a run
        // of source that is not prose, and must not be re-wrapped or spell-checked.
        let bytes = encode_blocks("$$E = mc^2$$\n", Flavour::MyST);
        let (mut decoder, _) = Decoder::new(&bytes).unwrap();
        assert_eq!(decoder.u32().unwrap(), 1);
        let (tag, _, _) = read_header(&mut decoder);
        assert_eq!(tag, ir::block::PARAGRAPH);
        assert_eq!(decoder.u32().unwrap(), 1, "one inline child");
        assert_eq!(decoder.u8().unwrap(), ir::inline::CODE);
        assert_eq!(decoder.string().unwrap(), "E = mc^2");

        // Under a dialect without maths the same characters are prose.
        let bytes = encode_blocks("$$E = mc^2$$\n", Flavour::Gfm);
        let (mut decoder, _) = Decoder::new(&bytes).unwrap();
        decoder.u32().unwrap();
        read_header(&mut decoder);
        decoder.u32().unwrap();
        assert_eq!(decoder.u8().unwrap(), ir::inline::TEXT);
    }

    #[test]
    fn extracts_plain_text_for_outline_titles() {
        let arena = Arena::new();
        let root = parse_document(&arena, "## A *bold* `title`\n", &options());
        let heading = root.children().next().unwrap();
        assert_eq!(plain_text(heading), "A bold title");
    }
}
