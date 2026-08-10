//! Quill's core engine.
//!
//! Everything that is not drawing pixels lives here: the document buffer, Markdown parsing, syntax
//! highlighting, outline extraction, statistics, search and HTML export. The engine is consumed by
//! the Kotlin UI through the `extern "C"` surface in [`ffi`], reached from the JVM with the Panama
//! FFM API.
//!
//! ## Layering
//!
//! ```text
//!   ffi        C ABI, panic containment, handle lifetime
//!   wire       QWIRE binary encoding shared with the JVM
//!   document   rope buffer, versioning, UTF-8 <-> UTF-16 offsets
//!   parser     comrak AST -> Jewel-shaped block IR
//!   highlight  editor source lexer + code-fence highlighting
//!   outline / stats / search / export
//! ```
//!
//! ## The one invariant to remember
//!
//! Every offset that crosses the FFI boundary is a **UTF-16 code-unit** offset, because that is what
//! the JVM's strings and Compose's text field use. Internally the rope works in UTF-8 bytes and
//! characters. [`document::Document`] is the only place those are converted, and every public entry
//! point in [`ffi`] speaks UTF-16.

#![deny(unsafe_op_in_unsafe_fn)]

pub mod document;
pub mod export;
pub mod ffi;
pub mod highlight;
pub mod outline;
pub mod parser;
pub mod search;
pub mod stats;
pub mod theme;
pub mod wire;

/// The crate version, surfaced in the UI's About dialog.
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

#[cfg(test)]
mod integration_tests {
    use crate::document::Document;
    use crate::wire::{Decoder, PayloadKind};

    /// A document exercising most of the supported Markdown surface.
    const SAMPLE: &str = "---\ntitle: Sample\n---\n\n\
# Quill\n\n\
A paragraph with *emphasis*, **strong**, `code` and a [link](https://example.com).\n\n\
## Lists\n\n\
- [x] finished\n- [ ] pending\n\n\
1. first\n2. second\n\n\
> A quote.\n\n\
```rust\nfn main() { println!(\"hi\"); }\n```\n\n\
| Column | Value |\n|:-------|------:|\n| a      |     1 |\n\n\
---\n\n\
Final ~~struck~~ paragraph with 한국어 and 🪶.\n";

    #[test]
    fn parses_the_full_sample_without_losing_blocks() {
        let bytes = crate::parser::encode_blocks(SAMPLE);
        let (mut decoder, kind) = Decoder::new(&bytes).unwrap();
        assert_eq!(kind, PayloadKind::Blocks);
        let count = decoder.u32().unwrap();
        assert!(count >= 10, "expected a rich block tree, got {count} top-level blocks");
    }

    #[test]
    fn every_stage_agrees_on_the_same_document() {
        let mut document = Document::new(SAMPLE);

        let outline = crate::outline::collect(document.text());
        assert_eq!(outline.len(), 2);
        assert_eq!(outline[0].title, "Quill");
        assert_eq!(outline[1].title, "Lists");

        let stats = crate::stats::compute(document.text());
        assert!(stats.words > 10);
        assert_eq!(stats.code_blocks, 1);
        assert_eq!(stats.links, 1);

        let spans = crate::highlight::editor::highlight(document.text(), 0, usize::MAX);
        assert!(!spans.is_empty());
        // Spans must never point outside the document, or the JVM throws when it builds the
        // AnnotatedString.
        let length = document.len_utf16();
        for span in &spans {
            assert!(span.end <= length, "span {span:?} exceeds document length {length}");
            assert!(span.start < span.end);
        }
    }

    #[test]
    fn incremental_edits_match_a_full_reparse() {
        let mut incremental = Document::new("# Title\n");
        let end = incremental.len_utf16();
        incremental.replace(end, end, "\nSome added text.\n").unwrap();

        let mut whole = Document::new("# Title\n\nSome added text.\n");
        assert_eq!(incremental.text(), whole.text());
        assert_eq!(
            crate::parser::encode_blocks(incremental.text()),
            crate::parser::encode_blocks(whole.text())
        );
    }

    #[test]
    fn round_trips_through_search_and_export() {
        let mut document = Document::new(SAMPLE);
        assert_eq!(crate::search::find(&mut document, "paragraph", 0).unwrap().len(), 2);

        let html = crate::export::to_html_document(
            document.text(),
            "Sample",
            crate::export::options::STANDALONE | crate::export::options::DARK,
        );
        assert!(html.contains("<h1>Quill</h1>"));
        assert!(html.contains("한국어"));
        assert!(html.contains("<table>"));
    }
}
