//! QWIRE — the compact binary wire format shared with the JVM bridge.
//!
//! Every value the engine hands back to Kotlin is encoded here and decoded by `WireReader` in
//! `:quill-bridge`. It exists instead of JSON for three reasons: the hot paths (editor spans, block
//! trees) are re-encoded on every keystroke, the JVM side can read the payload straight off a
//! `MemorySegment` without building an intermediate document, and it keeps a serialization
//! framework — and its ProGuard keep rules — out of the dependency graph.
//!
//! Layout rules:
//! * everything is little-endian,
//! * strings are `u32` byte length followed by that many UTF-8 bytes,
//! * a payload starts with [`MAGIC`], [`WIRE_VERSION`], then a `u8` payload kind,
//! * tree payloads are a depth-first node stream; each node writes its own fields, then a `u32`
//!   child count, then its children.
//!
//! Any change here must be mirrored in `WireReader.kt` and reflected in [`WIRE_VERSION`].

/// `"QWR1"` — guards against a stale library being loaded next to a newer bridge.
pub const MAGIC: u32 = 0x3152_5751;

/// Bumped whenever the layout of any payload changes.
pub const WIRE_VERSION: u16 = 1;

/// Discriminates the payload that follows the header.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum PayloadKind {
    Blocks = 1,
    Outline = 2,
    Stats = 3,
    Search = 4,
    Spans = 5,
    CodeHighlight = 6,
    Text = 7,
}

/// Append-only binary encoder.
#[derive(Debug, Default)]
pub struct Encoder {
    buffer: Vec<u8>,
}

impl Encoder {
    /// Starts a payload, writing the magic, wire version and payload kind.
    pub fn new(kind: PayloadKind) -> Self {
        let mut encoder = Self {
            buffer: Vec::with_capacity(1024),
        };
        encoder.put_u32(MAGIC);
        encoder.put_u16(WIRE_VERSION);
        encoder.put_u8(kind as u8);
        encoder
    }

    pub fn put_u8(&mut self, value: u8) {
        self.buffer.push(value);
    }

    pub fn put_bool(&mut self, value: bool) {
        self.buffer.push(u8::from(value));
    }

    pub fn put_u16(&mut self, value: u16) {
        self.buffer.extend_from_slice(&value.to_le_bytes());
    }

    pub fn put_u32(&mut self, value: u32) {
        self.buffer.extend_from_slice(&value.to_le_bytes());
    }

    pub fn put_i64(&mut self, value: i64) {
        self.buffer.extend_from_slice(&value.to_le_bytes());
    }

    /// Writes a `usize` as `u32`, saturating rather than truncating.
    ///
    /// Saturation is deliberate: a silent wrap would turn an over-large document offset into a
    /// small valid-looking one, which is far harder to diagnose than a clamped value.
    pub fn put_len(&mut self, value: usize) {
        self.put_u32(u32::try_from(value).unwrap_or(u32::MAX));
    }

    /// Writes a length-prefixed UTF-8 string.
    pub fn put_str(&mut self, value: &str) {
        self.put_len(value.len());
        self.buffer.extend_from_slice(value.as_bytes());
    }

    /// Writes an optional string as a presence byte followed by the string when present.
    pub fn put_opt_str(&mut self, value: Option<&str>) {
        match value {
            Some(text) => {
                self.put_bool(true);
                self.put_str(text);
            }
            None => self.put_bool(false),
        }
    }

    /// Reserves a `u32` slot and returns its offset, for counts only known later.
    pub fn reserve_u32(&mut self) -> usize {
        let offset = self.buffer.len();
        self.put_u32(0);
        offset
    }

    /// Back-patches a slot previously returned by [`Encoder::reserve_u32`].
    pub fn patch_u32(&mut self, offset: usize, value: usize) {
        let encoded = u32::try_from(value).unwrap_or(u32::MAX).to_le_bytes();
        self.buffer[offset..offset + 4].copy_from_slice(&encoded);
    }

    pub fn len(&self) -> usize {
        self.buffer.len()
    }

    pub fn is_empty(&self) -> bool {
        self.buffer.is_empty()
    }

    pub fn finish(self) -> Vec<u8> {
        self.buffer
    }
}

/// Minimal decoder, used by the crate's own round-trip tests.
///
/// The production decoder lives on the Kotlin side; this one exists so the encoder is verified
/// against an independent reader rather than against itself.
#[derive(Debug)]
pub struct Decoder<'a> {
    bytes: &'a [u8],
    position: usize,
}

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum DecodeError {
    #[error("unexpected end of payload at offset {0}")]
    UnexpectedEnd(usize),
    #[error("bad magic: expected {MAGIC:#x}, found {0:#x}")]
    BadMagic(u32),
    #[error("unsupported wire version {0}")]
    BadVersion(u16),
    #[error("unknown payload kind {0}")]
    UnknownKind(u8),
    #[error("payload is not valid UTF-8")]
    InvalidUtf8,
}

impl<'a> Decoder<'a> {
    /// Reads and validates the header, returning the payload kind.
    pub fn new(bytes: &'a [u8]) -> Result<(Self, PayloadKind), DecodeError> {
        let mut decoder = Self { bytes, position: 0 };
        let magic = decoder.u32()?;
        if magic != MAGIC {
            return Err(DecodeError::BadMagic(magic));
        }
        let version = decoder.u16()?;
        if version != WIRE_VERSION {
            return Err(DecodeError::BadVersion(version));
        }
        let kind = match decoder.u8()? {
            1 => PayloadKind::Blocks,
            2 => PayloadKind::Outline,
            3 => PayloadKind::Stats,
            4 => PayloadKind::Search,
            5 => PayloadKind::Spans,
            6 => PayloadKind::CodeHighlight,
            7 => PayloadKind::Text,
            other => return Err(DecodeError::UnknownKind(other)),
        };
        Ok((decoder, kind))
    }

    fn take(&mut self, count: usize) -> Result<&'a [u8], DecodeError> {
        let end = self
            .position
            .checked_add(count)
            .ok_or(DecodeError::UnexpectedEnd(self.position))?;
        let slice = self
            .bytes
            .get(self.position..end)
            .ok_or(DecodeError::UnexpectedEnd(self.position))?;
        self.position = end;
        Ok(slice)
    }

    pub fn u8(&mut self) -> Result<u8, DecodeError> {
        Ok(self.take(1)?[0])
    }

    pub fn bool(&mut self) -> Result<bool, DecodeError> {
        Ok(self.u8()? != 0)
    }

    pub fn u16(&mut self) -> Result<u16, DecodeError> {
        let bytes = self.take(2)?;
        Ok(u16::from_le_bytes([bytes[0], bytes[1]]))
    }

    pub fn u32(&mut self) -> Result<u32, DecodeError> {
        let bytes = self.take(4)?;
        Ok(u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
    }

    pub fn i64(&mut self) -> Result<i64, DecodeError> {
        let bytes = self.take(8)?;
        let mut array = [0u8; 8];
        array.copy_from_slice(bytes);
        Ok(i64::from_le_bytes(array))
    }

    pub fn string(&mut self) -> Result<String, DecodeError> {
        let length = self.u32()? as usize;
        let bytes = self.take(length)?;
        String::from_utf8(bytes.to_vec()).map_err(|_| DecodeError::InvalidUtf8)
    }

    pub fn opt_string(&mut self) -> Result<Option<String>, DecodeError> {
        if self.bool()? {
            Ok(Some(self.string()?))
        } else {
            Ok(None)
        }
    }

    pub fn is_exhausted(&self) -> bool {
        self.position == self.bytes.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_primitives() {
        let mut encoder = Encoder::new(PayloadKind::Stats);
        encoder.put_u8(7);
        encoder.put_bool(true);
        encoder.put_u16(513);
        encoder.put_u32(70_000);
        encoder.put_i64(-42);
        encoder.put_str("hello");
        encoder.put_opt_str(None);
        encoder.put_opt_str(Some("world"));
        let bytes = encoder.finish();

        let (mut decoder, kind) = Decoder::new(&bytes).expect("header must decode");
        assert_eq!(kind, PayloadKind::Stats);
        assert_eq!(decoder.u8().unwrap(), 7);
        assert!(decoder.bool().unwrap());
        assert_eq!(decoder.u16().unwrap(), 513);
        assert_eq!(decoder.u32().unwrap(), 70_000);
        assert_eq!(decoder.i64().unwrap(), -42);
        assert_eq!(decoder.string().unwrap(), "hello");
        assert_eq!(decoder.opt_string().unwrap(), None);
        assert_eq!(decoder.opt_string().unwrap(), Some("world".to_owned()));
        assert!(decoder.is_exhausted());
    }

    #[test]
    fn round_trips_non_ascii_strings() {
        // Korean is 3 UTF-8 bytes per character and emoji are 4; the length prefix is in bytes, so
        // this guards against anyone "helpfully" switching it to a character count.
        let mut encoder = Encoder::new(PayloadKind::Text);
        encoder.put_str("마크다운 편집기 🪶");
        let bytes = encoder.finish();

        let (mut decoder, _) = Decoder::new(&bytes).unwrap();
        assert_eq!(decoder.string().unwrap(), "마크다운 편집기 🪶");
    }

    #[test]
    fn patches_reserved_counts() {
        let mut encoder = Encoder::new(PayloadKind::Spans);
        let slot = encoder.reserve_u32();
        for value in 0..3u32 {
            encoder.put_u32(value);
        }
        encoder.patch_u32(slot, 3);
        let bytes = encoder.finish();

        let (mut decoder, _) = Decoder::new(&bytes).unwrap();
        assert_eq!(decoder.u32().unwrap(), 3);
    }

    #[test]
    fn rejects_bad_magic() {
        assert!(matches!(
            Decoder::new(&[0u8; 16]),
            Err(DecodeError::BadMagic(0))
        ));
    }

    #[test]
    fn rejects_truncated_payload() {
        let mut encoder = Encoder::new(PayloadKind::Text);
        encoder.put_str("abc");
        let mut bytes = encoder.finish();
        bytes.truncate(bytes.len() - 2);

        let (mut decoder, _) = Decoder::new(&bytes).unwrap();
        assert!(matches!(
            decoder.string(),
            Err(DecodeError::UnexpectedEnd(_))
        ));
    }

    #[test]
    fn saturates_oversized_lengths() {
        let mut encoder = Encoder::new(PayloadKind::Text);
        encoder.put_len(usize::MAX);
        let bytes = encoder.finish();

        let (mut decoder, _) = Decoder::new(&bytes).unwrap();
        assert_eq!(decoder.u32().unwrap(), u32::MAX);
    }
}
