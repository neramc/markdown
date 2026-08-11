//! The document model: a rope buffer, a monotonic version counter and a per-version result cache.
//!
//! ## Offsets
//!
//! Every offset that crosses the FFI boundary is a **UTF-16 code-unit** offset, because that is what
//! `java.lang.String` and Compose's text field use. Internally the rope stores UTF-8 and works in
//! characters, so this module is the single place where the three indexing schemes are reconciled.
//! Getting it wrong is not theoretical: Korean text is one UTF-16 unit but three UTF-8 bytes per
//! character, and astral-plane emoji are two UTF-16 units but one Rust `char`, so a byte offset
//! passed through unconverted misplaces every span after the first non-ASCII character.

use ropey::Rope;

use crate::wire::PayloadKind;

/// Errors that can arise when mutating or querying a document.
#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum DocumentError {
    #[error("range {start}..{end} is out of bounds for a document of {length} UTF-16 code units")]
    RangeOutOfBounds {
        start: usize,
        end: usize,
        length: usize,
    },
    #[error("range start {start} is greater than end {end}")]
    InvertedRange { start: usize, end: usize },
}

/// Cached, version-stamped encoder output.
///
/// The preview, outline and statistics are all recomputed from the same parse; memoising the
/// encoded bytes per version means switching tool windows or re-rendering after a no-op keystroke
/// costs nothing.
#[derive(Debug, Default)]
struct ResultCache {
    entries: Vec<(PayloadKind, i64, Vec<u8>)>,
}

impl ResultCache {
    fn get(&self, kind: PayloadKind, version: i64) -> Option<&[u8]> {
        self.entries
            .iter()
            .find(|(cached_kind, cached_version, _)| {
                *cached_kind == kind && *cached_version == version
            })
            .map(|(_, _, bytes)| bytes.as_slice())
    }

    /// Drops everything, for a change that invalidates results without touching the text.
    fn clear(&mut self) {
        self.entries.clear();
    }

    fn put(&mut self, kind: PayloadKind, version: i64, bytes: Vec<u8>) {
        self.entries
            .retain(|(cached_kind, _, _)| *cached_kind != kind);
        self.entries.push((kind, version, bytes));
    }
}

/// A single open Markdown document.
#[derive(Debug)]
pub struct Document {
    rope: Rope,
    version: i64,
    /// Lazily materialised flat copy of the rope. Parsing needs a contiguous `&str`, and nearly
    /// every operation parses, so this is rebuilt at most once per edit.
    flat: Option<String>,
    cache: ResultCache,
    flavour: crate::flavour::Flavour,
}

impl Document {
    pub fn new(text: &str) -> Self {
        Self {
            rope: Rope::from_str(text),
            version: 1,
            flat: Some(text.to_owned()),
            cache: ResultCache::default(),
            flavour: crate::flavour::Flavour::default(),
        }
    }

    /// The dialect this document is parsed as.
    pub fn flavour(&self) -> crate::flavour::Flavour {
        self.flavour
    }

    /// Changes the dialect, discarding every derived result.
    ///
    /// The cache is keyed by document version, and a flavour change alters every derived view
    /// without touching the text — so the version is bumped too, or the UI would keep showing
    /// results parsed under the previous dialect.
    pub fn set_flavour(&mut self, flavour: crate::flavour::Flavour) {
        if self.flavour != flavour {
            self.flavour = flavour;
            self.version += 1;
            self.cache.clear();
        }
    }

    pub fn version(&self) -> i64 {
        self.version
    }

    /// Length in UTF-16 code units.
    pub fn len_utf16(&self) -> usize {
        self.rope.len_utf16_cu()
    }

    pub fn len_lines(&self) -> usize {
        self.rope.len_lines()
    }

    /// The whole document as a contiguous string.
    pub fn text(&mut self) -> &str {
        // Destructuring splits the borrow so the closure can read `rope` while `flat` is borrowed
        // mutably; `self.flat.get_or_insert_with(|| self.rope.to_string())` would not compile.
        let Self { rope, flat, .. } = self;
        flat.get_or_insert_with(|| rope.to_string())
    }

    /// Replaces the UTF-16 range `start..end` with `text`, bumping the version.
    pub fn replace(&mut self, start: usize, end: usize, text: &str) -> Result<(), DocumentError> {
        if start > end {
            return Err(DocumentError::InvertedRange { start, end });
        }
        let length = self.len_utf16();
        if end > length {
            return Err(DocumentError::RangeOutOfBounds { start, end, length });
        }

        let start_char = self.rope.utf16_cu_to_char(start);
        let end_char = self.rope.utf16_cu_to_char(end);
        if end_char > start_char {
            self.rope.remove(start_char..end_char);
        }
        if !text.is_empty() {
            self.rope.insert(start_char, text);
        }
        self.invalidate();
        Ok(())
    }

    /// Replaces the entire contents.
    pub fn set_text(&mut self, text: &str) {
        self.rope = Rope::from_str(text);
        self.invalidate();
        self.flat = Some(text.to_owned());
    }

    fn invalidate(&mut self) {
        self.version = self.version.wrapping_add(1);
        self.flat = None;
    }

    /// Converts a UTF-8 byte offset (what the parser reports) to a UTF-16 offset (what the JVM
    /// expects).
    pub fn byte_to_utf16(&self, byte: usize) -> usize {
        let clamped = byte.min(self.rope.len_bytes());
        self.rope.char_to_utf16_cu(self.rope.byte_to_char(clamped))
    }

    /// UTF-16 offset of the first character of a zero-based line.
    ///
    /// Lines past the end clamp to the document length, so a stale viewport range from the UI can
    /// never produce an out-of-range query.
    pub fn line_start_utf16(&self, line: usize) -> usize {
        if line >= self.rope.len_lines() {
            return self.len_utf16();
        }
        self.rope.char_to_utf16_cu(self.rope.line_to_char(line))
    }

    /// Zero-based line containing a UTF-16 offset.
    pub fn utf16_to_line(&self, offset: usize) -> usize {
        let clamped = offset.min(self.len_utf16());
        self.rope.char_to_line(self.rope.utf16_cu_to_char(clamped))
    }

    /// Zero-based `(line, column)` for a UTF-16 offset, with the column also in UTF-16 units.
    pub fn utf16_to_line_column(&self, offset: usize) -> (usize, usize) {
        let line = self.utf16_to_line(offset);
        (line, offset.saturating_sub(self.line_start_utf16(line)))
    }

    pub(crate) fn cached(&self, kind: PayloadKind) -> Option<&[u8]> {
        self.cache.get(kind, self.version)
    }

    pub(crate) fn cache(&mut self, kind: PayloadKind, bytes: Vec<u8>) {
        let version = self.version;
        self.cache.put(kind, version, bytes);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tracks_version_across_edits() {
        let mut document = Document::new("hello");
        assert_eq!(document.version(), 1);
        document.replace(5, 5, " world").unwrap();
        assert_eq!(document.version(), 2);
        assert_eq!(document.text(), "hello world");
    }

    #[test]
    fn replaces_a_range() {
        let mut document = Document::new("the quick fox");
        document.replace(4, 9, "slow").unwrap();
        assert_eq!(document.text(), "the slow fox");
    }

    #[test]
    fn deletes_when_replacement_is_empty() {
        let mut document = Document::new("abcdef");
        document.replace(1, 4, "").unwrap();
        assert_eq!(document.text(), "aef");
    }

    #[test]
    fn rejects_out_of_bounds_and_inverted_ranges() {
        let mut document = Document::new("abc");
        assert_eq!(
            document.replace(0, 99, "x"),
            Err(DocumentError::RangeOutOfBounds {
                start: 0,
                end: 99,
                length: 3
            })
        );
        assert_eq!(
            document.replace(2, 1, "x"),
            Err(DocumentError::InvertedRange { start: 2, end: 1 })
        );
        // A rejected edit must not have mutated anything.
        assert_eq!(document.text(), "abc");
        assert_eq!(document.version(), 1);
    }

    #[test]
    fn korean_text_uses_utf16_units_not_bytes() {
        // "한국어" is 3 UTF-16 units but 9 UTF-8 bytes. Editing at UTF-16 offset 3 must land after
        // the third Hangul syllable, not in the middle of one.
        let mut document = Document::new("한국어");
        assert_eq!(document.len_utf16(), 3);
        document.replace(3, 3, " 마크다운").unwrap();
        assert_eq!(document.text(), "한국어 마크다운");
    }

    #[test]
    fn astral_emoji_counts_as_two_utf16_units() {
        let document = Document::new("🪶");
        assert_eq!(document.len_utf16(), 2);
        assert_eq!(document.byte_to_utf16(4), 2);

        let mut mixed = Document::new("a🪶b");
        assert_eq!(mixed.len_utf16(), 4);
        // Offset 3 is just past the emoji, i.e. immediately before "b".
        mixed.replace(3, 4, "Z").unwrap();
        assert_eq!(mixed.text(), "a🪶Z");
    }

    #[test]
    fn converts_byte_offsets_to_utf16() {
        let document = Document::new("한a🪶");
        assert_eq!(document.byte_to_utf16(0), 0);
        assert_eq!(document.byte_to_utf16(3), 1); // past "한"
        assert_eq!(document.byte_to_utf16(4), 2); // past "a"
        assert_eq!(document.byte_to_utf16(8), 4); // past the emoji (surrogate pair)
    }

    #[test]
    fn maps_lines_to_utf16_offsets() {
        let document = Document::new("one\n한국어\nthree");
        assert_eq!(document.line_start_utf16(0), 0);
        assert_eq!(document.line_start_utf16(1), 4);
        assert_eq!(document.line_start_utf16(2), 8);
        assert_eq!(document.utf16_to_line(9), 2);
        assert_eq!(document.utf16_to_line_column(9), (2, 1));
    }

    #[test]
    fn clamps_queries_past_the_end() {
        let document = Document::new("a\nb");
        let length = document.len_utf16();
        assert_eq!(document.line_start_utf16(999), length);
        assert_eq!(document.utf16_to_line(999), document.utf16_to_line(length));
    }

    #[test]
    fn set_text_replaces_everything_and_bumps_version() {
        let mut document = Document::new("old");
        document.set_text("brand new");
        assert_eq!(document.text(), "brand new");
        assert_eq!(document.version(), 2);
    }

    #[test]
    fn cache_is_invalidated_by_edits() {
        let mut document = Document::new("a");
        document.cache(PayloadKind::Outline, vec![1, 2, 3]);
        assert_eq!(
            document.cached(PayloadKind::Outline),
            Some([1, 2, 3].as_slice())
        );
        document.replace(1, 1, "b").unwrap();
        assert_eq!(document.cached(PayloadKind::Outline), None);
    }
}
