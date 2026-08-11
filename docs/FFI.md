# The FFI contract

Everything between Kotlin and Rust crosses one small `extern "C"` surface. This document is the
reference for that surface: the calling convention, the ownership rules, the offset convention, and
the wire format payloads travel in.

## Ground rules

1. **Only POD crosses.** Handles are opaque pointers; payloads travel in a `#[repr(C)]` struct.
2. **Every entry point returns a status.** `0` is success; anything else means the call failed and
   detail is available from `quill_last_error`.
3. **Every entry point contains its panics.** The body runs inside `catch_unwind`. A Rust panic
   unwinding across the FFI boundary is undefined behaviour, so it is converted into a status code
   instead.
4. **All offsets are UTF-16 code units.**
5. **Rust allocates, Rust frees.** Every buffer handed out is released by exactly one
   `quill_buf_free`.

## The surface

```c
int32_t  quill_abi_version(void);

QuillEngine* quill_engine_new(int32_t dark);
void         quill_engine_free(QuillEngine*);
int32_t      quill_engine_set_dark(QuillEngine*, int32_t dark);

QuillDoc* quill_doc_open(QuillEngine*, const uint8_t* utf8, size_t len);
void      quill_doc_free(QuillDoc*);

int32_t quill_doc_replace(QuillDoc*, uint32_t start_u16, uint32_t end_u16,
                          const uint8_t* utf8, size_t len);
int32_t quill_doc_set_text(QuillDoc*, const uint8_t* utf8, size_t len);
int64_t quill_doc_version(QuillDoc*);
int64_t quill_doc_len_utf16(QuillDoc*);

int32_t quill_doc_text(QuillDoc*, QuillBuf* out);
int32_t quill_doc_blocks(QuillDoc*, QuillBuf* out);
int32_t quill_doc_outline(QuillDoc*, QuillBuf* out);
int32_t quill_doc_stats(QuillDoc*, QuillBuf* out);
int32_t quill_doc_spans(QuillDoc*, uint32_t first_line, uint32_t last_line, QuillBuf* out);

int32_t quill_doc_search(QuillDoc*, const uint8_t* query, size_t len,
                         uint32_t flags, QuillBuf* out);
int32_t quill_doc_replace_all(QuillDoc*, const uint8_t* query, size_t query_len,
                              const uint8_t* replacement, size_t replacement_len,
                              uint32_t flags);
int32_t quill_doc_export_html(QuillDoc*, const uint8_t* title, size_t len,
                              uint32_t options, QuillBuf* out);

int32_t quill_highlight_code(QuillEngine*, const uint8_t* code, size_t code_len,
                             const uint8_t* language, size_t language_len, QuillBuf* out);

int32_t quill_last_error(QuillBuf* out);
void    quill_buf_free(QuillBuf*);
```

`QuillBuf` is the only compound type that crosses:

```c
typedef struct { uint8_t* ptr; size_t len; size_t cap; } QuillBuf;
```

`cap` is present because Rust's allocator needs the original capacity to free a `Vec` correctly.
Callers must not modify it.

## UTF-16 offsets

This is the single most important rule, and the easiest to get quietly wrong.

Rust stores UTF-8 in a `ropey::Rope`. Java, Kotlin and Compose all count in UTF-16 code units. So
every offset crossing the boundary — edit ranges, span bounds, search results, outline positions —
is a UTF-16 code unit index, and the engine converts at the boundary using ropey's native
`len_utf16_cu`, `char_to_utf16_cu` and `utf16_cu_to_char`.

Why it matters concretely:

| Text | UTF-8 bytes | UTF-16 units |
|---|---|---|
| `a` | 1 | 1 |
| `한` | 3 | 1 |
| `🚀` | 4 | 2 |

A byte offset passed through unconverted displaces every span after the first non-ASCII character.
Both sides carry tests using Korean text and astral-plane emoji specifically to pin this down.

## Ownership and lifetime

- A handle returned by `quill_engine_new` or `quill_doc_open` is owned by the caller and must be
  released exactly once. The Kotlin facades are `AutoCloseable` and register a `Cleaner` as a
  backstop for a caller who forgets.
- A `QuillBuf` filled by an `_out` parameter is owned by the caller. `QuillBindings.consume` copies
  the bytes onto the JVM heap and frees the buffer in a `finally`, so a decode failure cannot leak
  native memory.
- Passing a null handle returns a null-pointer status rather than dereferencing it. This is tested.

## Why the downcall layer is Java

`MethodHandle.invokeExact` is *signature-polymorphic*: the JVM derives the call's descriptor from
the call site, and the call only links if that descriptor matches the handle's type exactly.

Kotlin does not emit polymorphic-signature call sites. It compiles `invokeExact` as an ordinary
varargs call, which fails at runtime with:

```
WrongMethodTypeException: cannot convert MethodHandle()int to (Object[])Object
```

So `QuillBindings.java` and `NativeLibraryLoader.java` are Java, and everything above them is
Kotlin. The side benefit is that a descriptor mismatch becomes a compile error rather than a
runtime one.

## QWIRE — the payload format

Structured results travel in a custom binary format rather than JSON. It keeps a serialization
framework and its ProGuard keep rules out of the dependency graph, and it is faster to produce and
consume than text.

**Header**

| Field | Type | Value |
|---|---|---|
| magic | `u32` LE | `0x31525751` (`"QWR1"`) |
| version | `u16` LE | `1` |
| kind | `u16` LE | payload kind, see below |

**Kinds**

| Kind | Value | Payload |
|---|---|---|
| Blocks | 1 | Depth-first block IR node stream |
| Outline | 2 | Heading entries |
| Stats | 3 | Word, character, line counts and reading time |
| Spans | 4 | Editor style spans |
| Search | 5 | Match ranges |
| Colors | 6 | Resolved ARGB spans for a code block |
| Text | 7 | A single UTF-8 string |

**Primitives**

- Integers are little-endian, fixed width.
- Strings are a `u32` length followed by that many UTF-8 bytes.
- Optional values are a `u8` tag (`0` absent, `1` present) followed by the value when present.
- Node streams are depth-first: a `u8` tag, the node's own fields, then a `u32` child count followed
  by that many children.

**Bounds checking.** `WireReader` validates every read against the remaining length and throws a
`QuillWireException` on overrun. A malformed payload therefore produces an exception, never an
out-of-bounds read of native memory.

## Error reporting

A non-zero status means the detail is waiting in a thread-local on the Rust side. The bridge calls
`quill_last_error`, decodes the message and throws a `QuillEngineException` carrying it. The
thread-local is cleared on read, so a later unrelated success cannot surface a stale message.

## Testing the boundary

`quill-bridge`'s test suite drives the real shared library through FFM rather than a mock. That is
what proves the ABI: the descriptors match, the offset convention holds across scripts, buffers are
freed, null handles are rejected, and a panic inside Rust becomes an exception in Kotlin rather
than a crashed JVM.
