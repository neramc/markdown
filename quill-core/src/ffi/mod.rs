//! The `extern "C"` surface consumed by `:quill-bridge` through the Panama FFM API.
//!
//! Rules every entry point here follows:
//!
//! * **Nothing unwinds across the boundary.** A panic crossing an `extern "C"` frame is undefined
//!   behaviour, so each body runs inside `catch_unwind` and reports [`status::PANIC`].
//! * **Only POD crosses.** Opaque handles are raw pointers, byte payloads travel in [`QuillBuf`],
//!   results are `i32` status codes — no Rust enum, `Result`, `String` or `Vec` is laid out across
//!   the boundary.
//! * **Allocation and deallocation are paired on this side.** Every buffer Rust produces is freed by
//!   `quill_buf_free`, never by the JVM's allocator.
//! * **Offsets are UTF-16 code units**, matching `java.lang.String`.

// Every entry point here takes raw pointers by definition: the C ABI has no way to express
// `unsafe fn`, and marking the exports unsafe would not change what a C or JVM caller can do. The
// contract is documented per function and enforced by null checks plus `catch_unwind`.
#![allow(clippy::not_unsafe_ptr_arg_deref)]

pub mod buffer;
pub mod error;

use std::panic::{AssertUnwindSafe, catch_unwind};

use parking_lot::{Mutex, MutexGuard};

use crate::document::Document;
use crate::highlight::{code, editor};
use crate::wire::{Encoder, PayloadKind};
use buffer::QuillBuf;
use error::{describe_panic, set_last_error, status, take_last_error};

/// Incremented on any breaking change to this ABI. The bridge refuses to load a mismatch.
pub const ABI_VERSION: i32 = 1;

/// Engine-wide configuration shared by every document.
pub struct QuillEngine {
    dark: bool,
}

/// One open document, guarded so the JVM can call from any dispatcher thread.
pub struct QuillDoc {
    document: Mutex<Document>,
}

/// Runs `body`, converting a panic into a status code.
fn guard(body: impl FnOnce() -> i32) -> i32 {
    // AssertUnwindSafe is required because the closures capture raw pointers, which are not
    // UnwindSafe. It is sound here: a panic leaves the engine's state untouched, because every
    // mutation happens behind a parking_lot mutex (which does not poison) and the affected document
    // is simply left as it was.
    match catch_unwind(AssertUnwindSafe(body)) {
        Ok(code) => code,
        Err(payload) => {
            set_last_error(describe_panic(&*payload));
            status::PANIC
        }
    }
}

/// Runs `body`, converting a panic into a null pointer.
fn guard_ptr<T>(body: impl FnOnce() -> *mut T) -> *mut T {
    match catch_unwind(AssertUnwindSafe(body)) {
        Ok(pointer) => pointer,
        Err(payload) => {
            set_last_error(describe_panic(&*payload));
            std::ptr::null_mut()
        }
    }
}

/// Borrows a UTF-8 string from a caller-supplied pointer and length.
///
/// # Safety
///
/// `ptr` must be null or point to `len` initialised bytes that stay valid for the call.
unsafe fn borrow_str<'a>(ptr: *const u8, len: usize) -> Option<&'a str> {
    if len == 0 {
        return Some("");
    }
    if ptr.is_null() {
        return None;
    }
    // SAFETY: the caller guarantees `len` readable bytes at `ptr` for the duration of the call.
    let bytes = unsafe { std::slice::from_raw_parts(ptr, len) };
    std::str::from_utf8(bytes).ok()
}

/// Resolves the caller's output slot.
///
/// # Safety
///
/// `out` must be null or point to a writable, initialised `QuillBuf`. The JVM allocates it from a
/// zeroed `Arena` segment, and an all-zero `QuillBuf` is a valid (empty) one.
unsafe fn out_slot<'a>(out: *mut QuillBuf) -> Option<&'a mut QuillBuf> {
    // SAFETY: null is handled by `as_mut`; validity is the caller's contract.
    unsafe { out.as_mut() }
}

/// Resolves a document handle and locks it.
///
/// # Safety
///
/// `doc` must be null or a live handle returned by `quill_doc_open` and not yet freed.
unsafe fn lock_document<'a>(doc: *mut QuillDoc) -> Option<MutexGuard<'a, Document>> {
    // SAFETY: null is handled by `as_ref`; validity is the caller's contract.
    let handle = unsafe { doc.as_ref() }?;
    Some(handle.document.lock())
}

/// Hands `bytes` to the caller. `QuillBuf` has no `Drop`, so overwriting the slot cannot free
/// anything the caller still owns.
fn write_out(slot: &mut QuillBuf, bytes: Vec<u8>) -> i32 {
    *slot = QuillBuf::from_vec(bytes);
    status::OK
}

/// The error reported when a handle or output pointer is null.
fn null_pointer(what: &str) -> i32 {
    set_last_error(format!("{what} pointer is null"));
    status::NULL_POINTER
}

/// Returns the ABI version this library implements.
#[unsafe(no_mangle)]
pub extern "C" fn quill_abi_version() -> i32 {
    ABI_VERSION
}

/// Creates an engine. `dark_theme` selects the palette used for code-block highlighting.
#[unsafe(no_mangle)]
pub extern "C" fn quill_engine_new(dark_theme: i32) -> *mut QuillEngine {
    guard_ptr(|| {
        Box::into_raw(Box::new(QuillEngine {
            dark: dark_theme != 0,
        }))
    })
}

/// Destroys an engine. Passing null is a no-op; passing the same pointer twice is a double free.
#[unsafe(no_mangle)]
pub extern "C" fn quill_engine_free(engine: *mut QuillEngine) {
    if engine.is_null() {
        return;
    }
    let _ = guard(|| {
        // SAFETY: by contract `engine` came from `quill_engine_new` and has not been freed.
        drop(unsafe { Box::from_raw(engine) });
        status::OK
    });
}

/// Switches the palette used for code-block highlighting.
#[unsafe(no_mangle)]
pub extern "C" fn quill_engine_set_dark(engine: *mut QuillEngine, dark_theme: i32) -> i32 {
    guard(|| {
        // SAFETY: null-checked; otherwise a live engine handle from `quill_engine_new`.
        let Some(engine) = (unsafe { engine.as_mut() }) else {
            return null_pointer("engine");
        };
        engine.dark = dark_theme != 0;
        status::OK
    })
}

/// Opens a document from UTF-8 text. Returns null on failure.
#[unsafe(no_mangle)]
pub extern "C" fn quill_doc_open(
    engine: *mut QuillEngine,
    text: *const u8,
    len: usize,
) -> *mut QuillDoc {
    guard_ptr(|| {
        if engine.is_null() {
            set_last_error("engine pointer is null");
            return std::ptr::null_mut();
        }
        // SAFETY: caller-supplied buffer, validated for UTF-8 by `borrow_str`.
        let Some(text) = (unsafe { borrow_str(text, len) }) else {
            set_last_error("document text is null or not valid UTF-8");
            return std::ptr::null_mut();
        };
        Box::into_raw(Box::new(QuillDoc {
            document: Mutex::new(Document::new(text)),
        }))
    })
}

/// Closes a document.
#[unsafe(no_mangle)]
pub extern "C" fn quill_doc_free(doc: *mut QuillDoc) {
    if doc.is_null() {
        return;
    }
    let _ = guard(|| {
        // SAFETY: by contract `doc` came from `quill_doc_open` and has not been freed.
        drop(unsafe { Box::from_raw(doc) });
        status::OK
    });
}

/// Replaces the UTF-16 range `start..end` with `text`.
#[unsafe(no_mangle)]
pub extern "C" fn quill_doc_replace(
    doc: *mut QuillDoc,
    start: u32,
    end: u32,
    text: *const u8,
    len: usize,
) -> i32 {
    guard(|| {
        // SAFETY: caller-supplied buffer, validated for UTF-8.
        let Some(text) = (unsafe { borrow_str(text, len) }) else {
            set_last_error("replacement text is null or not valid UTF-8");
            return status::INVALID_UTF8;
        };
        // SAFETY: handle validity is the caller's contract; null is handled by `lock_document`.
        let Some(mut document) = (unsafe { lock_document(doc) }) else {
            return null_pointer("document");
        };
        match document.replace(start as usize, end as usize, text) {
            Ok(()) => status::OK,
            Err(failure) => {
                set_last_error(failure.to_string());
                status::OUT_OF_RANGE
            }
        }
    })
}

/// Replaces the entire document contents.
#[unsafe(no_mangle)]
pub extern "C" fn quill_doc_set_text(doc: *mut QuillDoc, text: *const u8, len: usize) -> i32 {
    guard(|| {
        // SAFETY: caller-supplied buffer, validated for UTF-8.
        let Some(text) = (unsafe { borrow_str(text, len) }) else {
            set_last_error("document text is null or not valid UTF-8");
            return status::INVALID_UTF8;
        };
        // SAFETY: handle validity is the caller's contract.
        let Some(mut document) = (unsafe { lock_document(doc) }) else {
            return null_pointer("document");
        };
        document.set_text(text);
        status::OK
    })
}

/// Returns the document's monotonic version, or a negative status code on failure.
#[unsafe(no_mangle)]
pub extern "C" fn quill_doc_version(doc: *mut QuillDoc) -> i64 {
    let mut version = i64::from(status::NULL_POINTER);
    let _ = guard(|| {
        // SAFETY: handle validity is the caller's contract.
        let Some(document) = (unsafe { lock_document(doc) }) else {
            return null_pointer("document");
        };
        version = document.version();
        status::OK
    });
    version
}

/// Returns the document length in UTF-16 code units, or a negative status code.
#[unsafe(no_mangle)]
pub extern "C" fn quill_doc_len_utf16(doc: *mut QuillDoc) -> i64 {
    let mut length = i64::from(status::NULL_POINTER);
    let _ = guard(|| {
        // SAFETY: handle validity is the caller's contract.
        let Some(document) = (unsafe { lock_document(doc) }) else {
            return null_pointer("document");
        };
        length = document.len_utf16() as i64;
        status::OK
    });
    length
}

/// Writes the document's text into `out`.
#[unsafe(no_mangle)]
pub extern "C" fn quill_doc_text(doc: *mut QuillDoc, out: *mut QuillBuf) -> i32 {
    guard(|| {
        // SAFETY: handle and output validity are the caller's contract; nulls are handled here.
        let (Some(mut document), Some(slot)) =
            (unsafe { lock_document(doc) }, unsafe { out_slot(out) })
        else {
            return null_pointer("document or output");
        };
        let mut encoder = Encoder::new(PayloadKind::Text);
        encoder.put_str(document.text());
        write_out(slot, encoder.finish())
    })
}

/// Writes the block IR for the whole document into `out`.
#[unsafe(no_mangle)]
pub extern "C" fn quill_doc_blocks(doc: *mut QuillDoc, out: *mut QuillBuf) -> i32 {
    guard(|| {
        // SAFETY: handle and output validity are the caller's contract.
        let (Some(mut document), Some(slot)) =
            (unsafe { lock_document(doc) }, unsafe { out_slot(out) })
        else {
            return null_pointer("document or output");
        };
        if let Some(cached) = document.cached(PayloadKind::Blocks) {
            return write_out(slot, cached.to_vec());
        }
        let flavour = document.flavour();
        let bytes = crate::parser::encode_blocks(document.text(), flavour);
        document.cache(PayloadKind::Blocks, bytes.clone());
        write_out(slot, bytes)
    })
}

/// Sets the Markdown dialect the document is parsed as.
///
/// Every derived view depends on the dialect, so changing it bumps the document version and drops
/// the cache; the UI sees a new version and re-derives exactly as it would after an edit.
#[unsafe(no_mangle)]
pub extern "C" fn quill_doc_set_flavour(doc: *mut QuillDoc, flavour: u8) -> i32 {
    guard(|| {
        // SAFETY: handle validity is the caller's contract.
        let Some(mut document) = (unsafe { lock_document(doc) }) else {
            return null_pointer("document");
        };
        let Some(parsed) = crate::flavour::Flavour::from_u8(flavour) else {
            set_last_error(format!(
                "unknown Markdown flavour {flavour}; this library knows 0..={}",
                crate::flavour::Flavour::all().len() - 1
            ));
            return status::INVALID_ARGUMENT;
        };
        document.set_flavour(parsed);
        status::OK
    })
}

/// Returns the document's current dialect as its wire value, or a negative status on failure.
#[unsafe(no_mangle)]
pub extern "C" fn quill_doc_flavour(doc: *mut QuillDoc) -> i32 {
    guard(|| {
        // SAFETY: handle validity is the caller's contract.
        let Some(document) = (unsafe { lock_document(doc) }) else {
            return null_pointer("document");
        };
        document.flavour() as i32
    })
}

/// Writes the rendered document as an HTML node tree into `out`.
///
/// This is the preview's source of truth. The Markdown is converted to HTML with the dialect's own
/// rules and that HTML is parsed into a DOM, so raw HTML written in the source renders as markup
/// instead of arriving in the preview as literal text — and the preview and the exported file are
/// produced by the same conversion.
#[unsafe(no_mangle)]
pub extern "C" fn quill_doc_html_dom(doc: *mut QuillDoc, out: *mut QuillBuf) -> i32 {
    guard(|| {
        // SAFETY: handle and output validity are the caller's contract.
        let (Some(mut document), Some(slot)) =
            (unsafe { lock_document(doc) }, unsafe { out_slot(out) })
        else {
            return null_pointer("document or output");
        };
        if let Some(cached) = document.cached(PayloadKind::HtmlDom) {
            return write_out(slot, cached.to_vec());
        }

        let flavour = document.flavour();
        let html = crate::parser::to_html_for(document.text(), flavour);
        let bytes = crate::html::encode(&crate::html::parse(&html));
        document.cache(PayloadKind::HtmlDom, bytes.clone());
        write_out(slot, bytes)
    })
}

/// Writes editor syntax spans for lines `first_line..=last_line` into `out`.
#[unsafe(no_mangle)]
pub extern "C" fn quill_doc_spans(
    doc: *mut QuillDoc,
    first_line: u32,
    last_line: u32,
    out: *mut QuillBuf,
) -> i32 {
    guard(|| {
        if last_line < first_line {
            set_last_error("last_line is before first_line");
            return status::INVALID_ARGUMENT;
        }
        // SAFETY: handle and output validity are the caller's contract.
        let (Some(mut document), Some(slot)) =
            (unsafe { lock_document(doc) }, unsafe { out_slot(out) })
        else {
            return null_pointer("document or output");
        };
        let spans = editor::highlight(document.text(), first_line as usize, last_line as usize);

        let mut encoder = Encoder::new(PayloadKind::Spans);
        encoder.put_len(spans.len());
        for span in &spans {
            encoder.put_len(span.start);
            encoder.put_len(span.end);
            encoder.put_u32(span.style.id());
        }
        write_out(slot, encoder.finish())
    })
}

/// Writes the heading outline into `out`.
#[unsafe(no_mangle)]
pub extern "C" fn quill_doc_outline(doc: *mut QuillDoc, out: *mut QuillBuf) -> i32 {
    guard(|| {
        // SAFETY: handle and output validity are the caller's contract.
        let (Some(mut document), Some(slot)) =
            (unsafe { lock_document(doc) }, unsafe { out_slot(out) })
        else {
            return null_pointer("document or output");
        };
        if let Some(cached) = document.cached(PayloadKind::Outline) {
            return write_out(slot, cached.to_vec());
        }
        let bytes = crate::outline::encode(&mut document);
        document.cache(PayloadKind::Outline, bytes.clone());
        write_out(slot, bytes)
    })
}

/// Writes the inspection findings into `out`.
#[unsafe(no_mangle)]
pub extern "C" fn quill_doc_inspections(doc: *mut QuillDoc, out: *mut QuillBuf) -> i32 {
    guard(|| {
        // SAFETY: handle and output validity are the caller's contract.
        let (Some(mut document), Some(slot)) =
            (unsafe { lock_document(doc) }, unsafe { out_slot(out) })
        else {
            return null_pointer("document or output");
        };
        if let Some(cached) = document.cached(PayloadKind::Inspections) {
            return write_out(slot, cached.to_vec());
        }
        let bytes = crate::inspect::encode(&mut document);
        document.cache(PayloadKind::Inspections, bytes.clone());
        write_out(slot, bytes)
    })
}

/// Writes document statistics into `out`.
#[unsafe(no_mangle)]
pub extern "C" fn quill_doc_stats(doc: *mut QuillDoc, out: *mut QuillBuf) -> i32 {
    guard(|| {
        // SAFETY: handle and output validity are the caller's contract.
        let (Some(mut document), Some(slot)) =
            (unsafe { lock_document(doc) }, unsafe { out_slot(out) })
        else {
            return null_pointer("document or output");
        };
        if let Some(cached) = document.cached(PayloadKind::Stats) {
            return write_out(slot, cached.to_vec());
        }
        let bytes = crate::stats::encode(&mut document);
        document.cache(PayloadKind::Stats, bytes.clone());
        write_out(slot, bytes)
    })
}

/// Converts an HTML fragment to Markdown and writes it into `out` as a text payload.
///
/// A free function rather than a document method: what is being converted is the clipboard, not the
/// open file, and the conversion has to happen before there is anywhere to put the result.
#[unsafe(no_mangle)]
pub extern "C" fn quill_html_to_markdown(
    html: *const u8,
    html_len: usize,
    out: *mut QuillBuf,
) -> i32 {
    guard(|| {
        // SAFETY: caller-supplied buffer, validated for UTF-8.
        let Some(html) = (unsafe { borrow_str(html, html_len) }) else {
            set_last_error("HTML fragment is null or not valid UTF-8");
            return status::INVALID_UTF8;
        };
        // SAFETY: output validity is the caller's contract.
        let Some(slot) = (unsafe { out_slot(out) }) else {
            return null_pointer("output");
        };
        let mut encoder = Encoder::new(PayloadKind::Text);
        encoder.put_str(&crate::import::html_to_markdown(html));
        write_out(slot, encoder.finish())
    })
}

/// Converts the document into another tool's format and writes it into `out` as text.
///
/// See [`crate::convert::Target`] for the values `target` takes.
#[unsafe(no_mangle)]
pub extern "C" fn quill_doc_convert(doc: *mut QuillDoc, target: u8, out: *mut QuillBuf) -> i32 {
    guard(|| {
        // SAFETY: handle and output validity are the caller's contract.
        let (Some(mut document), Some(slot)) =
            (unsafe { lock_document(doc) }, unsafe { out_slot(out) })
        else {
            return null_pointer("document or output");
        };
        let Some(parsed) = crate::convert::Target::from_u8(target) else {
            set_last_error(format!("unknown conversion target {target}"));
            return status::INVALID_ARGUMENT;
        };

        let flavour = document.flavour();
        let converted = crate::convert::convert(document.text(), flavour, parsed);
        let mut encoder = Encoder::new(PayloadKind::Text);
        encoder.put_str(&converted);
        write_out(slot, encoder.finish())
    })
}

/// Writes search results for `query` into `out`. See [`crate::search::flags`].
#[unsafe(no_mangle)]
pub extern "C" fn quill_doc_search(
    doc: *mut QuillDoc,
    query: *const u8,
    query_len: usize,
    flags: u32,
    out: *mut QuillBuf,
) -> i32 {
    guard(|| {
        // SAFETY: caller-supplied buffer, validated for UTF-8.
        let Some(query) = (unsafe { borrow_str(query, query_len) }) else {
            set_last_error("search query is null or not valid UTF-8");
            return status::INVALID_UTF8;
        };
        // SAFETY: handle and output validity are the caller's contract.
        let (Some(mut document), Some(slot)) =
            (unsafe { lock_document(doc) }, unsafe { out_slot(out) })
        else {
            return null_pointer("document or output");
        };
        match crate::search::encode(&mut document, query, flags) {
            Ok(bytes) => write_out(slot, bytes),
            Err(failure) => {
                set_last_error(failure.to_string());
                status::INVALID_ARGUMENT
            }
        }
    })
}

/// Replaces every match of `query` with `replacement`, mutating the document in place.
#[unsafe(no_mangle)]
pub extern "C" fn quill_doc_replace_all(
    doc: *mut QuillDoc,
    query: *const u8,
    query_len: usize,
    replacement: *const u8,
    replacement_len: usize,
    flags: u32,
) -> i32 {
    guard(|| {
        // SAFETY: caller-supplied buffers, validated for UTF-8.
        let (Some(query), Some(replacement)) = (unsafe { borrow_str(query, query_len) }, unsafe {
            borrow_str(replacement, replacement_len)
        }) else {
            set_last_error("query or replacement is null or not valid UTF-8");
            return status::INVALID_UTF8;
        };
        // SAFETY: handle validity is the caller's contract.
        let Some(mut document) = (unsafe { lock_document(doc) }) else {
            return null_pointer("document");
        };
        match crate::search::replace_all(&mut document, query, replacement, flags) {
            Ok(updated) => {
                document.set_text(&updated);
                status::OK
            }
            Err(failure) => {
                set_last_error(failure.to_string());
                status::INVALID_ARGUMENT
            }
        }
    })
}

/// Renders the document to HTML and writes it into `out`. See [`crate::export::options`].
#[unsafe(no_mangle)]
pub extern "C" fn quill_doc_export_html(
    doc: *mut QuillDoc,
    title: *const u8,
    title_len: usize,
    flags: u32,
    out: *mut QuillBuf,
) -> i32 {
    guard(|| {
        // SAFETY: caller-supplied buffer, validated for UTF-8.
        let Some(title) = (unsafe { borrow_str(title, title_len) }) else {
            set_last_error("export title is null or not valid UTF-8");
            return status::INVALID_UTF8;
        };
        // SAFETY: handle and output validity are the caller's contract.
        let (Some(mut document), Some(slot)) =
            (unsafe { lock_document(doc) }, unsafe { out_slot(out) })
        else {
            return null_pointer("document or output");
        };
        let bytes = crate::export::encode(&mut document, title, flags);
        write_out(slot, bytes)
    })
}

/// Highlights a fenced code block, writing coloured runs into `out`.
#[unsafe(no_mangle)]
pub extern "C" fn quill_highlight_code(
    engine: *mut QuillEngine,
    code_ptr: *const u8,
    code_len: usize,
    language: *const u8,
    language_len: usize,
    out: *mut QuillBuf,
) -> i32 {
    guard(|| {
        // SAFETY: null-checked; otherwise a live handle from `quill_engine_new`.
        let Some(engine) = (unsafe { engine.as_ref() }) else {
            return null_pointer("engine");
        };
        // SAFETY: caller-supplied buffers, validated for UTF-8.
        let (Some(source), Some(language)) = (unsafe { borrow_str(code_ptr, code_len) }, unsafe {
            borrow_str(language, language_len)
        }) else {
            set_last_error("code or language is null or not valid UTF-8");
            return status::INVALID_UTF8;
        };
        // SAFETY: output validity is the caller's contract.
        let Some(slot) = (unsafe { out_slot(out) }) else {
            return null_pointer("output");
        };

        let spans = code::highlight(source, language, engine.dark);
        let mut encoder = Encoder::new(PayloadKind::CodeHighlight);
        encoder.put_len(spans.len());
        for span in &spans {
            encoder.put_len(span.start);
            encoder.put_len(span.end);
            encoder.put_u32(span.argb);
        }
        write_out(slot, encoder.finish())
    })
}

/// Writes the calling thread's last error message into `out` and clears it.
///
/// Returns [`status::OK`] when a message was written, or [`status::INVALID_ARGUMENT`] when there was
/// no error to report.
#[unsafe(no_mangle)]
pub extern "C" fn quill_last_error(out: *mut QuillBuf) -> i32 {
    guard(|| {
        let Some(message) = take_last_error() else {
            return status::INVALID_ARGUMENT;
        };
        // SAFETY: output validity is the caller's contract.
        let Some(slot) = (unsafe { out_slot(out) }) else {
            return status::NULL_POINTER;
        };
        let mut encoder = Encoder::new(PayloadKind::Text);
        encoder.put_str(&message);
        write_out(slot, encoder.finish())
    })
}

/// Frees a buffer previously produced by this library.
#[unsafe(no_mangle)]
pub extern "C" fn quill_buf_free(buffer: *mut QuillBuf) {
    if buffer.is_null() {
        return;
    }
    let _ = guard(|| {
        // SAFETY: non-null and, by contract, produced by this library and not yet freed.
        unsafe { (*buffer).release() };
        status::OK
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wire::Decoder;

    /// Calls an out-parameter entry point and returns the payload bytes.
    fn capture(call: impl FnOnce(*mut QuillBuf) -> i32) -> Vec<u8> {
        let mut buffer = QuillBuf::empty();
        assert_eq!(call(&mut buffer), status::OK);
        // SAFETY: the call succeeded, so the buffer holds `len` readable bytes.
        let bytes = unsafe { std::slice::from_raw_parts(buffer.ptr, buffer.len) }.to_vec();
        quill_buf_free(&mut buffer);
        bytes
    }

    fn open(text: &str) -> (*mut QuillEngine, *mut QuillDoc) {
        let engine = quill_engine_new(1);
        let doc = quill_doc_open(engine, text.as_ptr(), text.len());
        assert!(!doc.is_null());
        (engine, doc)
    }

    #[test]
    fn reports_the_abi_version() {
        assert_eq!(quill_abi_version(), ABI_VERSION);
    }

    #[test]
    fn every_known_flavour_round_trips_across_the_boundary() {
        let (engine, doc) = open("# Title\n");

        for value in 0u8..=3 {
            assert_eq!(quill_doc_set_flavour(doc, value), status::OK);
            assert_eq!(quill_doc_flavour(doc), i32::from(value));
        }

        quill_doc_free(doc);
        quill_engine_free(engine);
    }

    #[test]
    fn an_unknown_flavour_is_rejected_rather_than_falling_back() {
        let (engine, doc) = open("~~struck~~\n");

        assert_eq!(quill_doc_set_flavour(doc, 0), status::OK);
        let before = quill_doc_flavour(doc);

        // A value this build does not know means the bridge and the library disagree. Serving GFM
        // instead would render the document in the wrong dialect with nothing reporting why.
        assert_eq!(quill_doc_set_flavour(doc, 200), status::INVALID_ARGUMENT);
        assert_eq!(
            quill_doc_flavour(doc),
            before,
            "a rejected call must not change the document"
        );

        let message = String::from_utf8(capture(|out| quill_last_error(out))).expect("utf-8");
        assert!(message.contains("200"), "unhelpful error: {message}");

        quill_doc_free(doc);
        quill_engine_free(engine);
    }

    #[test]
    fn opens_edits_and_closes_a_document() {
        let (engine, doc) = open("hello");
        assert_eq!(quill_doc_version(doc), 1);
        assert_eq!(quill_doc_len_utf16(doc), 5);

        let suffix = " world";
        assert_eq!(
            quill_doc_replace(doc, 5, 5, suffix.as_ptr(), suffix.len()),
            status::OK
        );
        assert_eq!(quill_doc_version(doc), 2);

        let payload = capture(|out| quill_doc_text(doc, out));
        let (mut decoder, kind) = Decoder::new(&payload).unwrap();
        assert_eq!(kind, PayloadKind::Text);
        assert_eq!(decoder.string().unwrap(), "hello world");

        quill_doc_free(doc);
        quill_engine_free(engine);
    }

    #[test]
    fn null_handles_are_rejected_not_dereferenced() {
        assert_eq!(
            quill_doc_replace(std::ptr::null_mut(), 0, 0, c"x".as_ptr().cast(), 1),
            status::NULL_POINTER
        );
        assert_eq!(
            quill_doc_version(std::ptr::null_mut()),
            i64::from(status::NULL_POINTER)
        );
        assert_eq!(
            quill_doc_len_utf16(std::ptr::null_mut()),
            i64::from(status::NULL_POINTER)
        );

        let mut buffer = QuillBuf::empty();
        assert_eq!(
            quill_doc_blocks(std::ptr::null_mut(), &mut buffer),
            status::NULL_POINTER
        );
        assert_eq!(
            quill_highlight_code(
                std::ptr::null_mut(),
                std::ptr::null(),
                0,
                std::ptr::null(),
                0,
                &mut buffer
            ),
            status::NULL_POINTER
        );

        // Freeing null must be a no-op rather than a crash.
        quill_doc_free(std::ptr::null_mut());
        quill_engine_free(std::ptr::null_mut());
        quill_buf_free(std::ptr::null_mut());
    }

    #[test]
    fn rejects_a_null_output_buffer_and_invalid_utf8() {
        let (engine, doc) = open("x");
        assert_eq!(
            quill_doc_blocks(doc, std::ptr::null_mut()),
            status::NULL_POINTER
        );

        // 0xFF is never valid UTF-8.
        let invalid = [0xFFu8, 0xFE];
        assert_eq!(
            quill_doc_replace(doc, 0, 0, invalid.as_ptr(), invalid.len()),
            status::INVALID_UTF8
        );

        quill_doc_free(doc);
        quill_engine_free(engine);
    }

    #[test]
    fn reports_out_of_range_edits() {
        let (engine, doc) = open("abc");
        let text = "z";
        assert_eq!(
            quill_doc_replace(doc, 0, 99, text.as_ptr(), text.len()),
            status::OUT_OF_RANGE
        );

        // The failure detail is retrievable and then cleared.
        let payload = capture(|out| quill_last_error(out));
        let (mut decoder, _) = Decoder::new(&payload).unwrap();
        assert!(decoder.string().unwrap().contains("out of bounds"));
        assert_eq!(
            quill_last_error(&mut QuillBuf::empty()),
            status::INVALID_ARGUMENT
        );

        quill_doc_free(doc);
        quill_engine_free(engine);
    }

    #[test]
    fn produces_blocks_outline_stats_and_spans() {
        let (engine, doc) = open("# Title\n\nSome text with `code`.\n");

        let payload = capture(|out| quill_doc_blocks(doc, out));
        let (mut decoder, kind) = Decoder::new(&payload).unwrap();
        assert_eq!(kind, PayloadKind::Blocks);
        assert_eq!(decoder.u32().unwrap(), 2);

        let payload = capture(|out| quill_doc_outline(doc, out));
        let (mut decoder, kind) = Decoder::new(&payload).unwrap();
        assert_eq!(kind, PayloadKind::Outline);
        assert_eq!(decoder.u32().unwrap(), 1);

        let payload = capture(|out| quill_doc_stats(doc, out));
        let (_, kind) = Decoder::new(&payload).unwrap();
        assert_eq!(kind, PayloadKind::Stats);

        let payload = capture(|out| quill_doc_spans(doc, 0, 10, out));
        let (mut decoder, kind) = Decoder::new(&payload).unwrap();
        assert_eq!(kind, PayloadKind::Spans);
        assert!(decoder.u32().unwrap() >= 2, "heading and inline code");

        quill_doc_free(doc);
        quill_engine_free(engine);
    }

    #[test]
    fn rejects_an_inverted_line_window() {
        let (engine, doc) = open("a\nb\n");
        assert_eq!(
            quill_doc_spans(doc, 5, 1, &mut QuillBuf::empty()),
            status::INVALID_ARGUMENT
        );
        quill_doc_free(doc);
        quill_engine_free(engine);
    }

    #[test]
    fn searches_and_replaces() {
        let (engine, doc) = open("one two one\n");
        let query = "one";
        let results = capture(|out| quill_doc_search(doc, query.as_ptr(), query.len(), 0, out));
        let (mut decoder, kind) = Decoder::new(&results).unwrap();
        assert_eq!(kind, PayloadKind::Search);
        assert_eq!(decoder.u32().unwrap(), 2);

        let replacement = "1";
        assert_eq!(
            quill_doc_replace_all(
                doc,
                query.as_ptr(),
                query.len(),
                replacement.as_ptr(),
                replacement.len(),
                0
            ),
            status::OK
        );
        let payload = capture(|out| quill_doc_text(doc, out));
        let (mut decoder, _) = Decoder::new(&payload).unwrap();
        assert_eq!(decoder.string().unwrap(), "1 two 1\n");

        quill_doc_free(doc);
        quill_engine_free(engine);
    }

    #[test]
    fn reports_an_invalid_search_pattern() {
        let (engine, doc) = open("text");
        let query = "[unclosed";
        assert_eq!(
            quill_doc_search(
                doc,
                query.as_ptr(),
                query.len(),
                crate::search::flags::REGEX,
                &mut QuillBuf::empty()
            ),
            status::INVALID_ARGUMENT
        );
        quill_doc_free(doc);
        quill_engine_free(engine);
    }

    #[test]
    fn exports_html() {
        let (engine, doc) = open("# Title\n");
        let title = "Doc";
        let bytes = capture(|out| {
            quill_doc_export_html(
                doc,
                title.as_ptr(),
                title.len(),
                crate::export::options::STANDALONE,
                out,
            )
        });
        let (mut decoder, kind) = Decoder::new(&bytes).unwrap();
        assert_eq!(kind, PayloadKind::Text);
        let html = decoder.string().unwrap();
        assert!(html.contains("<!doctype html>"));
        assert!(html.contains("<h1>Title</h1>"));

        quill_doc_free(doc);
        quill_engine_free(engine);
    }

    #[test]
    fn highlights_code_and_follows_the_engine_palette() {
        let engine = quill_engine_new(1);
        let source = "fn main() {}";
        let language = "rust";

        let dark = capture(|out| {
            quill_highlight_code(
                engine,
                source.as_ptr(),
                source.len(),
                language.as_ptr(),
                language.len(),
                out,
            )
        });
        assert_eq!(quill_engine_set_dark(engine, 0), status::OK);
        let light = capture(|out| {
            quill_highlight_code(
                engine,
                source.as_ptr(),
                source.len(),
                language.as_ptr(),
                language.len(),
                out,
            )
        });
        assert_ne!(dark, light, "the palette switch must change the output");

        let (mut decoder, kind) = Decoder::new(&dark).unwrap();
        assert_eq!(kind, PayloadKind::CodeHighlight);
        assert!(decoder.u32().unwrap() > 1);

        quill_engine_free(engine);
    }

    #[test]
    fn set_text_replaces_contents() {
        let (engine, doc) = open("old");
        let replacement = "완전히 새로운 내용";
        assert_eq!(
            quill_doc_set_text(doc, replacement.as_ptr(), replacement.len()),
            status::OK
        );
        assert_eq!(quill_doc_len_utf16(doc), 10);

        quill_doc_free(doc);
        quill_engine_free(engine);
    }

    #[test]
    fn repeated_queries_are_served_from_the_cache() {
        let (engine, doc) = open("# A\n\n## B\n");
        let first = capture(|out| quill_doc_outline(doc, out));
        assert_eq!(first, capture(|out| quill_doc_outline(doc, out)));

        // After an edit the cache must not serve the stale answer.
        let addition = "\n### C\n";
        let length = quill_doc_len_utf16(doc) as u32;
        assert_eq!(
            quill_doc_replace(doc, length, length, addition.as_ptr(), addition.len()),
            status::OK
        );
        assert_ne!(first, capture(|out| quill_doc_outline(doc, out)));

        quill_doc_free(doc);
        quill_engine_free(engine);
    }

    #[test]
    fn documents_are_usable_from_multiple_threads() {
        // The JVM calls the engine from whichever coroutine dispatcher thread is free, so a document
        // handle has to tolerate concurrent access.
        let (engine, doc) = open("# Title\n\nbody text\n");
        let address = doc as usize;

        let handles: Vec<_> = (0..4)
            .map(|_| {
                std::thread::spawn(move || {
                    let doc = address as *mut QuillDoc;
                    for _ in 0..25 {
                        let mut buffer = QuillBuf::empty();
                        assert_eq!(quill_doc_stats(doc, &mut buffer), status::OK);
                        quill_buf_free(&mut buffer);
                    }
                })
            })
            .collect();
        for handle in handles {
            handle.join().expect("worker thread must not panic");
        }

        quill_doc_free(doc);
        quill_engine_free(engine);
    }
}
