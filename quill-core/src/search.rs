//! Find and replace over a document.
//!
//! Results are returned as UTF-16 ranges so the editor can select them directly.

use regex::{Regex, RegexBuilder};

use crate::document::Document;
use crate::wire::{Encoder, PayloadKind};

/// Bit flags accepted by the FFI `flags` argument.
pub mod flags {
    /// Match without regard to case.
    pub const CASE_INSENSITIVE: u32 = 1 << 0;
    /// Require word boundaries around the match.
    pub const WHOLE_WORD: u32 = 1 << 1;
    /// Treat the query as a regular expression rather than a literal.
    pub const REGEX: u32 = 1 << 2;
}

#[derive(Debug, thiserror::Error)]
pub enum SearchError {
    #[error("invalid regular expression: {0}")]
    InvalidPattern(String),
}

/// One match, in UTF-16 code units.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Match {
    pub start: usize,
    pub end: usize,
    pub line: u32,
    pub column: u32,
}

/// Upper bound on results.
///
/// A pathological query such as `.*` against a large file can otherwise match at every position; the
/// UI cannot show a million hits anyway, and the cap keeps one search from allocating hundreds of
/// megabytes.
const MAX_MATCHES: usize = 10_000;

fn build_regex(query: &str, flags: u32) -> Result<Regex, SearchError> {
    let pattern = if flags & flags::REGEX != 0 { query.to_owned() } else { regex::escape(query) };
    let pattern = if flags & flags::WHOLE_WORD != 0 { format!(r"\b(?:{pattern})\b") } else { pattern };

    RegexBuilder::new(&pattern)
        .case_insensitive(flags & flags::CASE_INSENSITIVE != 0)
        .multi_line(true)
        .build()
        .map_err(|error| SearchError::InvalidPattern(error.to_string()))
}

/// Finds every match of `query` in the document.
pub fn find(document: &mut Document, query: &str, flags: u32) -> Result<Vec<Match>, SearchError> {
    if query.is_empty() {
        return Ok(Vec::new());
    }
    let regex = build_regex(query, flags)?;

    // The byte offsets the regex reports are converted to UTF-16 before they leave this function;
    // the rest of the system only ever sees UTF-16.
    let byte_ranges: Vec<(usize, usize)> = {
        let text = document.text();
        regex
            .find_iter(text)
            .take(MAX_MATCHES)
            // A zero-width match (`^`, `\b`, `a*`) cannot be selected or replaced meaningfully.
            .filter(|found| found.end() > found.start())
            .map(|found| (found.start(), found.end()))
            .collect()
    };

    Ok(byte_ranges
        .into_iter()
        .map(|(start_byte, end_byte)| {
            let start = document.byte_to_utf16(start_byte);
            let end = document.byte_to_utf16(end_byte);
            let (line, column) = document.utf16_to_line_column(start);
            Match { start, end, line: line as u32, column: column as u32 }
        })
        .collect())
}

/// Replaces every match, returning the new document text.
///
/// In regex mode `replacement` may reference capture groups (`$1`, `${name}`); in literal mode it is
/// inserted verbatim, so a replacement containing `$` does not surprise the user.
pub fn replace_all(document: &mut Document, query: &str, replacement: &str, flags: u32) -> Result<String, SearchError> {
    if query.is_empty() {
        return Ok(document.text().to_owned());
    }
    let regex = build_regex(query, flags)?;
    let text = document.text();

    Ok(if flags & flags::REGEX != 0 {
        regex.replace_all(text, replacement).into_owned()
    } else {
        regex.replace_all(text, regex::NoExpand(replacement)).into_owned()
    })
}

/// Encodes search results as a [`PayloadKind::Search`] payload.
pub fn encode(document: &mut Document, query: &str, flags: u32) -> Result<Vec<u8>, SearchError> {
    let matches = find(document, query, flags)?;

    let mut encoder = Encoder::new(PayloadKind::Search);
    encoder.put_len(matches.len());
    for found in &matches {
        encoder.put_len(found.start);
        encoder.put_len(found.end);
        encoder.put_u32(found.line);
        encoder.put_u32(found.column);
    }
    Ok(encoder.finish())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wire::Decoder;

    #[test]
    fn finds_literal_matches() {
        let mut document = Document::new("one two one\n");
        let matches = find(&mut document, "one", 0).unwrap();
        assert_eq!(matches.len(), 2);
        assert_eq!(matches[0], Match { start: 0, end: 3, line: 0, column: 0 });
        assert_eq!(matches[1], Match { start: 8, end: 11, line: 0, column: 8 });
    }

    #[test]
    fn literal_mode_escapes_regex_metacharacters() {
        // Without escaping, "a.c" would also match "abc" -- a classic find-bar bug.
        let mut document = Document::new("abc a.c\n");
        let matches = find(&mut document, "a.c", 0).unwrap();
        assert_eq!(matches.len(), 1);
        assert_eq!(matches[0].start, 4);
    }

    #[test]
    fn honours_case_insensitivity_and_whole_word() {
        let mut document = Document::new("Alpha alpha ALPHA\n");
        assert_eq!(find(&mut document, "alpha", 0).unwrap().len(), 1);
        assert_eq!(find(&mut document, "alpha", flags::CASE_INSENSITIVE).unwrap().len(), 3);

        let mut words = Document::new("cat catalogue cat\n");
        assert_eq!(find(&mut words, "cat", 0).unwrap().len(), 3);
        assert_eq!(find(&mut words, "cat", flags::WHOLE_WORD).unwrap().len(), 2);
    }

    #[test]
    fn supports_regex_mode() {
        let mut document = Document::new("a1 b22 c333\n");
        assert_eq!(find(&mut document, r"[a-z]\d+", flags::REGEX).unwrap().len(), 3);
    }

    #[test]
    fn reports_an_invalid_pattern_instead_of_panicking() {
        let mut document = Document::new("text");
        let error = find(&mut document, "[unclosed", flags::REGEX).unwrap_err();
        assert!(matches!(error, SearchError::InvalidPattern(_)));
    }

    #[test]
    fn skips_zero_width_matches_and_empty_queries() {
        let mut document = Document::new("abc\n");
        assert!(find(&mut document, "x*", flags::REGEX).unwrap().is_empty());
        assert!(find(&mut document, "^", flags::REGEX).unwrap().is_empty());
        assert!(find(&mut document, "", 0).unwrap().is_empty());
    }

    #[test]
    fn positions_are_utf16_not_bytes() {
        // "한국어" occupies UTF-16 offsets 0..3 but bytes 0..9; the match must report 4, not 10.
        let mut document = Document::new("한국어 target\n");
        let found = find(&mut document, "target", 0).unwrap()[0];
        assert_eq!((found.start, found.end, found.column), (4, 10, 4));
    }

    #[test]
    fn reports_line_and_column() {
        let mut document = Document::new("first\nsecond needle\n");
        let found = find(&mut document, "needle", 0).unwrap()[0];
        assert_eq!((found.line, found.column), (1, 7));
    }

    #[test]
    fn replaces_literally_without_expanding_dollar_signs() {
        let mut document = Document::new("price: AMOUNT\n");
        assert_eq!(replace_all(&mut document, "AMOUNT", "$100", 0).unwrap(), "price: $100\n");
    }

    #[test]
    fn expands_capture_groups_in_regex_mode() {
        let mut document = Document::new("2024-01-31\n");
        let result = replace_all(&mut document, r"(\d{4})-(\d{2})-(\d{2})", "$3/$2/$1", flags::REGEX).unwrap();
        assert_eq!(result, "31/01/2024\n");
    }

    #[test]
    fn caps_the_number_of_matches() {
        let mut document = Document::new(&"a".repeat(MAX_MATCHES + 500));
        assert_eq!(find(&mut document, "a", 0).unwrap().len(), MAX_MATCHES);
    }

    #[test]
    fn encodes_matches() {
        let mut document = Document::new("x\nfind me\n");
        let bytes = encode(&mut document, "find", 0).unwrap();
        let (mut decoder, kind) = Decoder::new(&bytes).unwrap();
        assert_eq!(kind, PayloadKind::Search);
        assert_eq!(decoder.u32().unwrap(), 1);
        assert_eq!(decoder.u32().unwrap(), 2, "start");
        assert_eq!(decoder.u32().unwrap(), 6, "end");
        assert_eq!(decoder.u32().unwrap(), 1, "line");
        assert_eq!(decoder.u32().unwrap(), 0, "column");
        assert!(decoder.is_exhausted());
    }
}
