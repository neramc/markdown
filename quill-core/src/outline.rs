//! Document outline: the heading tree that backs the Structure tool window.

use comrak::nodes::NodeValue;
use comrak::{Arena, parse_document};

use crate::document::Document;
use crate::parser::{options, plain_text};
use crate::wire::{Encoder, PayloadKind};

/// A single heading.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Heading {
    /// 1-6.
    pub level: u8,
    /// Zero-based source line.
    pub line: u32,
    pub title: String,
}

/// Collects every heading in document order.
pub fn collect(text: &str) -> Vec<Heading> {
    let arena = Arena::new();
    let root = parse_document(&arena, text, &options());

    let mut headings = Vec::new();
    for node in root.descendants() {
        let (level, line) = {
            let data = node.data.borrow();
            match data.value {
                NodeValue::Heading(heading) => (heading.level, data.sourcepos.start.line),
                _ => continue,
            }
        };
        let title = plain_text(node);
        headings.push(Heading {
            level,
            line: line.saturating_sub(1) as u32,
            title: if title.trim().is_empty() {
                "(untitled)".to_owned()
            } else {
                title
            },
        });
    }
    headings
}

/// Encodes the outline as a [`PayloadKind::Outline`] payload.
///
/// Each entry carries the UTF-16 offset of its line so clicking the outline can move the caret
/// without the UI having to convert anything.
pub fn encode(document: &mut Document) -> Vec<u8> {
    let headings = collect(document.text());

    let mut encoder = Encoder::new(PayloadKind::Outline);
    encoder.put_len(headings.len());
    for heading in &headings {
        encoder.put_u8(heading.level);
        encoder.put_u32(heading.line);
        encoder.put_len(document.line_start_utf16(heading.line as usize));
        encoder.put_str(&heading.title);
    }
    encoder.finish()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wire::Decoder;

    #[test]
    fn collects_headings_in_document_order() {
        let headings = collect("# One\n\ntext\n\n## Two\n\n### Three\n");
        assert_eq!(headings.len(), 3);
        assert_eq!(
            headings[0],
            Heading {
                level: 1,
                line: 0,
                title: "One".to_owned()
            }
        );
        assert_eq!(headings[1].level, 2);
        assert_eq!(headings[1].line, 4);
        assert_eq!(headings[2].title, "Three");
    }

    #[test]
    fn strips_inline_markup_from_titles() {
        assert_eq!(
            collect("# A *bold* `code` [link](x)\n")[0].title,
            "A bold code link"
        );
    }

    #[test]
    fn handles_setext_headings() {
        let headings = collect("Title\n=====\n\nOther\n-----\n");
        assert_eq!(headings.len(), 2);
        assert_eq!(headings[0].level, 1);
        assert_eq!(headings[1].level, 2);
    }

    #[test]
    fn ignores_hashes_inside_code_fences() {
        assert!(collect("```\n# not a heading\n```\n").is_empty());
    }

    #[test]
    fn labels_empty_headings() {
        let headings = collect("#\n");
        assert_eq!(headings.len(), 1);
        assert_eq!(headings[0].title, "(untitled)");
    }

    #[test]
    fn encodes_utf16_offsets_for_navigation() {
        // The heading sits after a line of Korean text, so its UTF-16 offset differs from its byte
        // offset -- exactly the bug this field exists to avoid.
        let mut document = Document::new("한국어 문장\n\n## 제목\n");
        let bytes = encode(&mut document);

        let (mut decoder, kind) = Decoder::new(&bytes).unwrap();
        assert_eq!(kind, PayloadKind::Outline);
        assert_eq!(decoder.u32().unwrap(), 1);
        assert_eq!(decoder.u8().unwrap(), 2);
        assert_eq!(decoder.u32().unwrap(), 2, "zero-based line");
        assert_eq!(decoder.u32().unwrap(), 8, "UTF-16 offset of line 2");
        assert_eq!(decoder.string().unwrap(), "제목");
        assert!(decoder.is_exhausted());
    }

    #[test]
    fn encodes_an_empty_outline() {
        let mut document = Document::new("just a paragraph\n");
        let bytes = encode(&mut document);
        let (mut decoder, _) = Decoder::new(&bytes).unwrap();
        assert_eq!(decoder.u32().unwrap(), 0);
    }
}
