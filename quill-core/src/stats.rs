//! Document statistics for the status bar.
//!
//! Counts are taken over the *prose*, not the raw source: a reader does not read YAML front matter,
//! code fences or link URLs, so including them would make the word count and reading-time estimate
//! misleading for exactly the documents where they matter most.

use comrak::nodes::NodeValue;
use comrak::{Arena, parse_document};

use crate::document::Document;
use crate::parser::options;
use crate::wire::{Encoder, PayloadKind};

/// Average adult reading speed in words per minute, used for the reading-time estimate.
const WORDS_PER_MINUTE: u32 = 200;

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Stats {
    pub words: u32,
    pub characters: u32,
    pub characters_without_spaces: u32,
    pub lines: u32,
    pub paragraphs: u32,
    pub sentences: u32,
    pub reading_time_seconds: u32,
    pub code_blocks: u32,
    pub links: u32,
    pub images: u32,
    pub headings: u32,
}

/// Computes statistics for a document's source text.
pub fn compute(text: &str) -> Stats {
    let arena = Arena::new();
    let root = parse_document(&arena, text, &options());

    let mut prose = String::new();
    let mut stats = Stats::default();

    for node in root.descendants() {
        match &node.data.borrow().value {
            // Inline text is appended without a separator: adjacent nodes are adjacent in the
            // source too, so inserting one would split "docs." into "docs" and "." and inflate the
            // word count by one for every punctuated link or emphasis run.
            NodeValue::Text(content) => prose.push_str(content),
            NodeValue::Code(code) => prose.push_str(&code.literal),
            NodeValue::SoftBreak | NodeValue::LineBreak => prose.push(' '),
            NodeValue::Paragraph => {
                stats.paragraphs += 1;
                prose.push('\n');
            }
            NodeValue::Heading(_) => {
                stats.headings += 1;
                prose.push('\n');
            }
            NodeValue::Item(_) | NodeValue::TaskItem(_) | NodeValue::TableCell => prose.push('\n'),
            NodeValue::CodeBlock(_) => stats.code_blocks += 1,
            NodeValue::Link(_) => stats.links += 1,
            NodeValue::Image(_) => stats.images += 1,
            _ => {}
        }
    }

    stats.words = prose.split_whitespace().count() as u32;
    stats.sentences = prose
        .split(['.', '!', '?', '。', '！', '？'])
        .filter(|segment| !segment.trim().is_empty())
        .count() as u32;

    // Characters are counted on the raw source, because that is what the caret positions in the
    // editor refer to. UTF-16 keeps it consistent with every other offset crossing the bridge.
    stats.characters = text.chars().map(char::len_utf16).sum::<usize>() as u32;
    stats.characters_without_spaces = text
        .chars()
        .filter(|c| !c.is_whitespace())
        .map(|c| c.len_utf16())
        .sum::<usize>() as u32;

    stats.lines = if text.is_empty() {
        0
    } else {
        text.lines().count() as u32
    };

    // Round up, so any non-empty document reads as at least one second rather than zero.
    stats.reading_time_seconds = if stats.words == 0 {
        0
    } else {
        ((u64::from(stats.words) * 60).div_ceil(u64::from(WORDS_PER_MINUTE))) as u32
    };

    stats
}

/// Encodes statistics as a [`PayloadKind::Stats`] payload.
pub fn encode(document: &mut Document) -> Vec<u8> {
    let stats = compute(document.text());

    let mut encoder = Encoder::new(PayloadKind::Stats);
    encoder.put_u32(stats.words);
    encoder.put_u32(stats.characters);
    encoder.put_u32(stats.characters_without_spaces);
    encoder.put_u32(stats.lines);
    encoder.put_u32(stats.paragraphs);
    encoder.put_u32(stats.sentences);
    encoder.put_u32(stats.reading_time_seconds);
    encoder.put_u32(stats.code_blocks);
    encoder.put_u32(stats.links);
    encoder.put_u32(stats.images);
    encoder.put_u32(stats.headings);
    encoder.finish()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wire::Decoder;

    #[test]
    fn counts_words_and_paragraphs() {
        let stats = compute("One two three.\n\nFour five.\n");
        assert_eq!(stats.words, 5);
        assert_eq!(stats.paragraphs, 2);
        assert_eq!(stats.sentences, 2);
    }

    #[test]
    fn excludes_code_fence_contents_from_the_word_count() {
        // The fence body is not prose; counting it would inflate the reading time of any technical
        // document, which is the case this rule exists for.
        let stats = compute("Intro words here.\n\n```rust\nfn a() { let b = 1; }\n```\n");
        assert_eq!(stats.words, 3);
        assert_eq!(stats.code_blocks, 1);
    }

    #[test]
    fn excludes_front_matter_from_prose() {
        assert_eq!(
            compute("---\ntitle: Something Long\n---\n\nBody text.\n").words,
            2
        );
    }

    #[test]
    fn counts_link_text_but_not_urls() {
        // Inline runs must join without separators, or the trailing "." becomes a fourth word.
        let stats = compute("See [the docs](https://example.com/a/very/long/path).\n");
        assert_eq!(stats.words, 3, "'See', 'the', 'docs.'");
        assert_eq!(stats.links, 1);
    }

    #[test]
    fn counts_headings_and_images() {
        let stats = compute("# Title\n\n![alt](a.png)\n");
        assert_eq!(stats.headings, 1);
        assert_eq!(stats.images, 1);
    }

    #[test]
    fn characters_are_counted_in_utf16_over_the_raw_source() {
        // "한국어" is 3 UTF-16 units; a byte count would say 9 and desynchronise the status bar from
        // the caret offsets shown next to it.
        let stats = compute("한국어");
        assert_eq!(stats.characters, 3);
        assert_eq!(stats.characters_without_spaces, 3);
        assert_eq!(compute("a🪶").characters, 3, "emoji is a surrogate pair");
    }

    #[test]
    fn reading_time_rounds_up_and_is_zero_for_empty_documents() {
        assert_eq!(compute("").reading_time_seconds, 0);
        assert_eq!(compute("one").reading_time_seconds, 1);
        assert_eq!(compute(&"word ".repeat(200)).reading_time_seconds, 60);
    }

    #[test]
    fn empty_document_is_all_zero() {
        assert_eq!(compute(""), Stats::default());
    }

    #[test]
    fn encodes_every_field_in_order() {
        let mut document = Document::new("# T\n\nHello world.\n");
        let bytes = encode(&mut document);
        let (mut decoder, kind) = Decoder::new(&bytes).unwrap();
        assert_eq!(kind, PayloadKind::Stats);
        assert_eq!(decoder.u32().unwrap(), 3, "words: T, Hello, world.");
        decoder.u32().unwrap(); // characters
        decoder.u32().unwrap(); // characters without spaces
        decoder.u32().unwrap(); // lines
        assert_eq!(decoder.u32().unwrap(), 1, "paragraphs");
        decoder.u32().unwrap(); // sentences
        decoder.u32().unwrap(); // reading time
        assert_eq!(decoder.u32().unwrap(), 0, "code blocks");
        assert_eq!(decoder.u32().unwrap(), 0, "links");
        assert_eq!(decoder.u32().unwrap(), 0, "images");
        assert_eq!(decoder.u32().unwrap(), 1, "headings");
        assert!(decoder.is_exhausted());
    }
}
