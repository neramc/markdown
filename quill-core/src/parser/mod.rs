//! Markdown parsing: comrak's AST walked straight into the QWIRE block stream.
//!
//! There is deliberately no intermediate Rust tree. comrak already owns an arena-allocated AST, so
//! building a second tree only to serialise it would double the allocation cost of every
//! keystroke-triggered re-parse.

pub mod ir;

use comrak::nodes::{AlertType, AstNode, ListDelimType, ListType, NodeValue, TableAlignment};
use comrak::{Arena, Options, parse_document};

use crate::wire::{Encoder, PayloadKind};

/// Parser configuration: CommonMark plus the GFM extensions Jewel can render.
pub fn options() -> Options<'static> {
    let mut options = Options::default();

    options.extension.strikethrough = true;
    options.extension.table = true;
    options.extension.autolink = true;
    options.extension.tasklist = true;
    options.extension.footnotes = true;
    options.extension.front_matter_delimiter = Some("---".to_owned());
    options.extension.alerts = true;

    options.parse.smart = false;
    // Source positions are what make editor <-> preview scroll synchronisation possible.
    options.render.sourcepos = true;
    // Raw HTML is not trusted by default; the preview renders it as literal text.
    options.render.r#unsafe = false;
    options.render.hardbreaks = false;

    options
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

/// Encodes a document's blocks as a [`PayloadKind::Blocks`] payload.
pub fn encode_blocks(text: &str) -> Vec<u8> {
    let arena = Arena::new();
    let root = parse_document(&arena, text, &options());

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
        NodeValue::BlockQuote | NodeValue::MultilineBlockQuote(_) => {
            write_header(encoder, ir::block::BLOCK_QUOTE, line_start, line_end);
            encode_block_children(node, list_level, encoder);
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
pub fn to_html_for(text: &str, flavour: crate::flavour::Flavour) -> String {
    let prepared = crate::flavour::prepare(text, flavour);

    let mut render_options = options();
    if !flavour.uses_gfm_extensions() {
        render_options.extension.strikethrough = false;
        render_options.extension.table = false;
        render_options.extension.autolink = false;
        render_options.extension.tasklist = false;
        render_options.extension.footnotes = false;
        render_options.extension.alerts = false;
    }
    // MDX and Markdoc both reach the parser as HTML wrappers around Markdown, so their output has
    // to survive rather than be escaped.
    render_options.render.r#unsafe =
        flavour.allows_raw_html() || flavour == crate::flavour::Flavour::Markdoc;
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
        let bytes = encode_blocks("# Title\n\nSome *text*.\n");
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
        let bytes = encode_blocks("```rust,no_run\nfn main() {}\n```\n");
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
        let bytes = encode_blocks("```\nplain\n```\n");
        let (mut decoder, _) = Decoder::new(&bytes).unwrap();
        decoder.u32().unwrap();
        read_header(&mut decoder);
        assert_eq!(decoder.opt_string().unwrap(), None);
    }

    #[test]
    fn encodes_task_list_state() {
        let bytes = encode_blocks("- [x] done\n- [ ] todo\n");
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
        let bytes = encode_blocks("3) first\n4) second\n");
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
        let bytes = encode_blocks("| a | b |\n|:--|--:|\n| 1 | 2 |\n");
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
        let bytes = encode_blocks("~~gone~~ and [text](https://example.com \"t\")\n");
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
        let bytes = encode_blocks("");
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

        assert!(to_html("<script>x</script>\n", true).contains("<script>"));
    }

    #[test]
    fn renders_gfm_extensions_to_html() {
        let html = to_html("| a |\n|---|\n| 1 |\n\n~~x~~\n", false);
        assert!(html.contains("<table>"));
        assert!(html.contains("<del>"));
    }

    #[test]
    fn extracts_plain_text_for_outline_titles() {
        let arena = Arena::new();
        let root = parse_document(&arena, "## A *bold* `title`\n", &options());
        let heading = root.children().next().unwrap();
        assert_eq!(plain_text(heading), "A bold title");
    }
}
