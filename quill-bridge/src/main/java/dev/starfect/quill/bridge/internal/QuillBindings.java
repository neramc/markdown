package dev.starfect.quill.bridge.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/**
 * The raw Panama binding to {@code libquill_core}.
 *
 * <p><b>Why this layer is Java rather than Kotlin.</b> {@code MethodHandle.invokeExact} is a
 * signature-polymorphic method: the call site's descriptor is derived from the static argument and
 * return types, which is what makes a downcall a direct, allocation-free call. Kotlin compiles
 * {@code invoke}/{@code invokeExact} as an ordinary varargs call instead, boxing every argument into
 * an {@code Object[]} and failing with {@code WrongMethodTypeException} against a descriptor like
 * {@code ()int}. Writing just this layer in Java keeps the downcalls statically typed — a mismatch
 * between a {@link FunctionDescriptor} and its wrapper is a compile error here rather than a runtime
 * failure — and everything above it stays Kotlin.
 *
 * <p>This class owns marshalling only. Handle lifetime, error translation and threading policy live
 * in the Kotlin facade.
 */
public final class QuillBindings {

    /**
     * {@code size_t} on every platform Quill ships for.
     *
     * <p>Quill distributes 64-bit binaries only, which is what makes this equivalence safe; a 32-bit
     * target would need a platform-dependent layout.
     */
    private static final ValueLayout.OfLong C_SIZE_T = ValueLayout.JAVA_LONG;

    private static final ValueLayout.OfInt C_INT = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong C_LONG = ValueLayout.JAVA_LONG;
    private static final MemoryLayout PTR = ValueLayout.ADDRESS;

    /** Layout of {@code QuillBuf}: <code>{ uint8_t* ptr; size_t len; size_t cap; }</code>. */
    private static final MemoryLayout QUILL_BUF =
            MemoryLayout.structLayout(
                            ValueLayout.ADDRESS.withName("ptr"),
                            C_SIZE_T.withName("len"),
                            C_SIZE_T.withName("cap"))
                    .withName("QuillBuf");

    private static final long PTR_OFFSET = QUILL_BUF.byteOffset(MemoryLayout.PathElement.groupElement("ptr"));
    private static final long LEN_OFFSET = QUILL_BUF.byteOffset(MemoryLayout.PathElement.groupElement("len"));

    /** Success status, mirroring {@code ffi/error.rs}. */
    public static final int STATUS_OK = 0;

    private static final Arena LIBRARY_ARENA = Arena.global();
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = NativeLibraryLoader.load(LIBRARY_ARENA);

    private static final MethodHandle ABI_VERSION = handle("quill_abi_version", FunctionDescriptor.of(C_INT));
    private static final MethodHandle ENGINE_NEW = handle("quill_engine_new", FunctionDescriptor.of(PTR, C_INT));
    private static final MethodHandle ENGINE_FREE = handle("quill_engine_free", FunctionDescriptor.ofVoid(PTR));
    private static final MethodHandle ENGINE_SET_DARK =
            handle("quill_engine_set_dark", FunctionDescriptor.of(C_INT, PTR, C_INT));
    private static final MethodHandle DOC_OPEN =
            handle("quill_doc_open", FunctionDescriptor.of(PTR, PTR, PTR, C_SIZE_T));
    private static final MethodHandle DOC_FREE = handle("quill_doc_free", FunctionDescriptor.ofVoid(PTR));
    private static final MethodHandle DOC_REPLACE =
            handle("quill_doc_replace", FunctionDescriptor.of(C_INT, PTR, C_INT, C_INT, PTR, C_SIZE_T));
    private static final MethodHandle DOC_SET_TEXT =
            handle("quill_doc_set_text", FunctionDescriptor.of(C_INT, PTR, PTR, C_SIZE_T));
    private static final MethodHandle DOC_VERSION = handle("quill_doc_version", FunctionDescriptor.of(C_LONG, PTR));
    private static final MethodHandle DOC_LEN_UTF16 =
            handle("quill_doc_len_utf16", FunctionDescriptor.of(C_LONG, PTR));
    private static final MethodHandle DOC_TEXT = handle("quill_doc_text", FunctionDescriptor.of(C_INT, PTR, PTR));
    private static final MethodHandle DOC_BLOCKS = handle("quill_doc_blocks", FunctionDescriptor.of(C_INT, PTR, PTR));
    private static final MethodHandle DOC_HTML_DOM = handle("quill_doc_html_dom", FunctionDescriptor.of(C_INT, PTR, PTR));
    private static final MethodHandle DOC_SET_FLAVOUR = handle("quill_doc_set_flavour", FunctionDescriptor.of(C_INT, PTR, ValueLayout.JAVA_BYTE));
    private static final MethodHandle DOC_FLAVOUR = handle("quill_doc_flavour", FunctionDescriptor.of(C_INT, PTR));
    private static final MethodHandle DOC_SPANS =
            handle("quill_doc_spans", FunctionDescriptor.of(C_INT, PTR, C_INT, C_INT, PTR));
    private static final MethodHandle DOC_OUTLINE =
            handle("quill_doc_outline", FunctionDescriptor.of(C_INT, PTR, PTR));
    private static final MethodHandle DOC_INSPECTIONS =
            handle("quill_doc_inspections", FunctionDescriptor.of(C_INT, PTR, PTR));
    private static final MethodHandle DOC_STATS = handle("quill_doc_stats", FunctionDescriptor.of(C_INT, PTR, PTR));
    private static final MethodHandle DOC_SEARCH =
            handle("quill_doc_search", FunctionDescriptor.of(C_INT, PTR, PTR, C_SIZE_T, C_INT, PTR));
    private static final MethodHandle DOC_REPLACE_ALL =
            handle(
                    "quill_doc_replace_all",
                    FunctionDescriptor.of(C_INT, PTR, PTR, C_SIZE_T, PTR, C_SIZE_T, C_INT));
    private static final MethodHandle DOC_EXPORT_HTML =
            handle("quill_doc_export_html", FunctionDescriptor.of(C_INT, PTR, PTR, C_SIZE_T, C_INT, PTR));
    private static final MethodHandle HIGHLIGHT_CODE =
            handle(
                    "quill_highlight_code",
                    FunctionDescriptor.of(C_INT, PTR, PTR, C_SIZE_T, PTR, C_SIZE_T, PTR));
    private static final MethodHandle DOC_CONVERT =
            handle("quill_doc_convert", FunctionDescriptor.of(C_INT, PTR, ValueLayout.JAVA_BYTE, PTR));
    private static final MethodHandle HTML_TO_MARKDOWN =
            handle("quill_html_to_markdown", FunctionDescriptor.of(C_INT, PTR, C_SIZE_T, PTR));
    private static final MethodHandle LAST_ERROR = handle("quill_last_error", FunctionDescriptor.of(C_INT, PTR));
    private static final MethodHandle BUF_FREE = handle("quill_buf_free", FunctionDescriptor.ofVoid(PTR));

    private QuillBindings() {}

    /** A status code paired with the payload bytes an out-parameter call produced. */
    public record Payload(int status, byte[] bytes) {
        public boolean ok() {
            return status == STATUS_OK;
        }
    }

    private static MethodHandle handle(String symbol, FunctionDescriptor descriptor) {
        MemorySegment address =
                LOOKUP.find(symbol)
                        .orElseThrow(
                                () ->
                                        new UnsatisfiedLinkError(
                                                "the Quill core library does not export '"
                                                        + symbol
                                                        + "'; it is older than this bridge expects"));
        return LINKER.downcallHandle(address, descriptor);
    }

    /**
     * Rethrows a {@link Throwable} from a downcall.
     *
     * <p>The engine never lets a panic cross the boundary, so anything arriving here is a JVM-side
     * failure (a descriptor mismatch or a closed arena) and is genuinely unrecoverable.
     */
    private static RuntimeException rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Quill native call failed", failure);
    }

    private static MemorySegment utf8(Arena arena, byte[] bytes) {
        if (bytes.length == 0) {
            // The engine short-circuits on len == 0, so the pointer is never dereferenced.
            return MemorySegment.NULL;
        }
        MemorySegment segment = arena.allocate(bytes.length);
        MemorySegment.copy(bytes, 0, segment, ValueLayout.JAVA_BYTE, 0L, bytes.length);
        return segment;
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Copies a {@code QuillBuf}'s contents onto the heap and frees the native allocation.
     *
     * <p>The copy happens before the free, so nothing the caller receives points into released
     * memory.
     */
    private static byte[] consume(MemorySegment buffer) {
        try {
            MemorySegment pointer = buffer.get(ValueLayout.ADDRESS, PTR_OFFSET);
            long length = buffer.get(C_SIZE_T, LEN_OFFSET);
            if (pointer.equals(MemorySegment.NULL) || length <= 0) {
                return new byte[0];
            }
            byte[] copy = new byte[Math.toIntExact(length)];
            // A pointer returned across the boundary has zero size until reinterpreted; this is what
            // gives the copy a bounds-checked view of the native allocation.
            MemorySegment.copy(pointer.reinterpret(length), ValueLayout.JAVA_BYTE, 0L, copy, 0, copy.length);
            return copy;
        } finally {
            try {
                BUF_FREE.invokeExact(buffer);
            } catch (Throwable failure) {
                throw rethrow(failure);
            }
        }
    }

    public static int abiVersion() {
        try {
            return (int) ABI_VERSION.invokeExact();
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    public static MemorySegment engineNew(boolean dark) {
        try {
            return (MemorySegment) ENGINE_NEW.invokeExact(dark ? 1 : 0);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    public static void engineFree(MemorySegment engine) {
        try {
            ENGINE_FREE.invokeExact(engine);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    public static int engineSetDark(MemorySegment engine, boolean dark) {
        try {
            return (int) ENGINE_SET_DARK.invokeExact(engine, dark ? 1 : 0);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    public static MemorySegment docOpen(MemorySegment engine, String text) {
        byte[] bytes = utf8(text);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pointer = utf8(arena, bytes);
            return (MemorySegment) DOC_OPEN.invokeExact(engine, pointer, (long) bytes.length);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    public static void docFree(MemorySegment doc) {
        try {
            DOC_FREE.invokeExact(doc);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    public static int docReplace(MemorySegment doc, int start, int end, String text) {
        byte[] bytes = utf8(text);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pointer = utf8(arena, bytes);
            return (int) DOC_REPLACE.invokeExact(doc, start, end, pointer, (long) bytes.length);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    public static int docSetText(MemorySegment doc, String text) {
        byte[] bytes = utf8(text);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pointer = utf8(arena, bytes);
            return (int) DOC_SET_TEXT.invokeExact(doc, pointer, (long) bytes.length);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    public static long docVersion(MemorySegment doc) {
        try {
            return (long) DOC_VERSION.invokeExact(doc);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    public static long docLenUtf16(MemorySegment doc) {
        try {
            return (long) DOC_LEN_UTF16.invokeExact(doc);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    public static Payload docText(MemorySegment doc) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(QUILL_BUF);
            int status = (int) DOC_TEXT.invokeExact(doc, buffer);
            return new Payload(status, status == STATUS_OK ? consume(buffer) : new byte[0]);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    public static Payload docBlocks(MemorySegment doc) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(QUILL_BUF);
            int status = (int) DOC_BLOCKS.invokeExact(doc, buffer);
            return new Payload(status, status == STATUS_OK ? consume(buffer) : new byte[0]);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    public static Payload docHtmlDom(MemorySegment doc) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(QUILL_BUF);
            int status = (int) DOC_HTML_DOM.invokeExact(doc, buffer);
            return new Payload(status, status == STATUS_OK ? consume(buffer) : new byte[0]);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    public static int docSetFlavour(MemorySegment doc, byte flavour) {
        try {
            return (int) DOC_SET_FLAVOUR.invokeExact(doc, flavour);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    public static int docFlavour(MemorySegment doc) {
        try {
            return (int) DOC_FLAVOUR.invokeExact(doc);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    public static Payload docSpans(MemorySegment doc, int firstLine, int lastLine) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(QUILL_BUF);
            int status = (int) DOC_SPANS.invokeExact(doc, firstLine, lastLine, buffer);
            return new Payload(status, status == STATUS_OK ? consume(buffer) : new byte[0]);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    public static Payload docOutline(MemorySegment doc) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(QUILL_BUF);
            int status = (int) DOC_OUTLINE.invokeExact(doc, buffer);
            return new Payload(status, status == STATUS_OK ? consume(buffer) : new byte[0]);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    public static Payload docInspections(MemorySegment doc) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(QUILL_BUF);
            int status = (int) DOC_INSPECTIONS.invokeExact(doc, buffer);
            return new Payload(status, status == STATUS_OK ? consume(buffer) : new byte[0]);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    public static Payload docStats(MemorySegment doc) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(QUILL_BUF);
            int status = (int) DOC_STATS.invokeExact(doc, buffer);
            return new Payload(status, status == STATUS_OK ? consume(buffer) : new byte[0]);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    public static Payload docSearch(MemorySegment doc, String query, int flags) {
        byte[] bytes = utf8(query);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(QUILL_BUF);
            MemorySegment pointer = utf8(arena, bytes);
            int status = (int) DOC_SEARCH.invokeExact(doc, pointer, (long) bytes.length, flags, buffer);
            return new Payload(status, status == STATUS_OK ? consume(buffer) : new byte[0]);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    /** Converts the document into another tool's format. */
    public static Payload docConvert(MemorySegment doc, byte target) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(QUILL_BUF);
            int status = (int) DOC_CONVERT.invokeExact(doc, target, buffer);
            return new Payload(status, status == STATUS_OK ? consume(buffer) : new byte[0]);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    /**
     * Converts an HTML fragment to Markdown.
     *
     * <p>Takes no handle: what is being converted is the clipboard, not an open document.
     */
    public static Payload htmlToMarkdown(String html) {
        byte[] bytes = utf8(html);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(QUILL_BUF);
            MemorySegment pointer = utf8(arena, bytes);
            int status = (int) HTML_TO_MARKDOWN.invokeExact(pointer, (long) bytes.length, buffer);
            return new Payload(status, status == STATUS_OK ? consume(buffer) : new byte[0]);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    public static int docReplaceAll(MemorySegment doc, String query, String replacement, int flags) {
        byte[] queryBytes = utf8(query);
        byte[] replacementBytes = utf8(replacement);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment queryPointer = utf8(arena, queryBytes);
            MemorySegment replacementPointer = utf8(arena, replacementBytes);
            return (int)
                    DOC_REPLACE_ALL.invokeExact(
                            doc,
                            queryPointer,
                            (long) queryBytes.length,
                            replacementPointer,
                            (long) replacementBytes.length,
                            flags);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    public static Payload docExportHtml(MemorySegment doc, String title, int options) {
        byte[] bytes = utf8(title);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(QUILL_BUF);
            MemorySegment pointer = utf8(arena, bytes);
            int status = (int) DOC_EXPORT_HTML.invokeExact(doc, pointer, (long) bytes.length, options, buffer);
            return new Payload(status, status == STATUS_OK ? consume(buffer) : new byte[0]);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    public static Payload highlightCode(MemorySegment engine, String code, String language) {
        byte[] codeBytes = utf8(code);
        byte[] languageBytes = utf8(language);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(QUILL_BUF);
            MemorySegment codePointer = utf8(arena, codeBytes);
            MemorySegment languagePointer = utf8(arena, languageBytes);
            int status =
                    (int)
                            HIGHLIGHT_CODE.invokeExact(
                                    engine,
                                    codePointer,
                                    (long) codeBytes.length,
                                    languagePointer,
                                    (long) languageBytes.length,
                                    buffer);
            return new Payload(status, status == STATUS_OK ? consume(buffer) : new byte[0]);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    /** Returns the calling thread's last engine error payload, or {@code null} if there was none. */
    public static byte[] lastError() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(QUILL_BUF);
            int status = (int) LAST_ERROR.invokeExact(buffer);
            return status == STATUS_OK ? consume(buffer) : null;
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }
}
