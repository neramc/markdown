package dev.starfect.quill.bridge

import dev.starfect.quill.bridge.internal.QuillBindings
import dev.starfect.quill.bridge.wire.DocumentStats
import dev.starfect.quill.bridge.wire.MarkdownBlockIr
import dev.starfect.quill.bridge.wire.OutlineEntry
import dev.starfect.quill.bridge.wire.SearchMatch
import dev.starfect.quill.bridge.wire.StyleSpan
import dev.starfect.quill.bridge.wire.decodeBlocks
import dev.starfect.quill.bridge.wire.decodeOutline
import dev.starfect.quill.bridge.wire.decodeSearch
import dev.starfect.quill.bridge.wire.decodeSpans
import dev.starfect.quill.bridge.wire.decodeStats
import dev.starfect.quill.bridge.wire.decodeText
import java.lang.foreign.MemorySegment
import java.lang.ref.Cleaner
import java.util.concurrent.atomic.AtomicBoolean

/** Search behaviour flags, mirroring `search::flags` in the engine. */
public object SearchFlags {
    public const val NONE: Int = 0
    public const val CASE_INSENSITIVE: Int = 1 shl 0
    public const val WHOLE_WORD: Int = 1 shl 1
    public const val REGEX: Int = 1 shl 2
}

/** HTML export flags, mirroring `export::options` in the engine. */
public object ExportOptions {
    public const val NONE: Int = 0

    /** Emit a complete HTML document with an inline stylesheet rather than a fragment. */
    public const val STANDALONE: Int = 1 shl 0

    /** Use the dark palette. */
    public const val DARK: Int = 1 shl 1

    /** Pass raw HTML in the source through instead of escaping it. */
    public const val ALLOW_RAW_HTML: Int = 1 shl 2
}

/**
 * One open document held by the engine.
 *
 * **All offsets are UTF-16 code-unit offsets**, matching `String` and Compose's text field. The
 * engine converts to and from its internal UTF-8 rope at its own boundary, so a caret position from
 * the UI passes straight through without conversion here.
 *
 * Instances are safe to use from multiple threads — the engine locks the document per call — but the
 * calls block while parsing, so they belong off the UI thread.
 */
public class QuillDocument internal constructor(
    private val handle: MemorySegment,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private val cleanable = CLEANER.register(this, DocumentReleaser(handle, closed))

    private companion object {
        val CLEANER: Cleaner = Cleaner.create { runnable ->
            Thread(runnable, "quill-document-cleaner").apply { isDaemon = true }
        }
    }

    private fun requireOpen(): MemorySegment {
        check(!closed.get()) { "this QuillDocument has been closed" }
        return handle
    }

    /** Monotonic version, incremented on every mutation. */
    public val version: Long
        get() {
            val value = QuillBindings.docVersion(requireOpen())
            if (value < 0) throw QuillEngineException(value.toInt(), "version", lastEngineError())
            return value
        }

    /** Length in UTF-16 code units. */
    public val length: Int
        get() {
            val value = QuillBindings.docLenUtf16(requireOpen())
            if (value < 0) throw QuillEngineException(value.toInt(), "length", lastEngineError())
            return value.toInt()
        }

    /** The full document text. */
    public fun text(): String = decodeText(QuillBindings.docText(requireOpen()).require("text"))

    /**
     * Replaces the UTF-16 range `[start, end)` with [replacement].
     *
     * This is the incremental path: the engine applies the edit to its rope instead of rebuilding
     * from a fresh string, which is what keeps typing cheap in a large document.
     */
    public fun replace(start: Int, end: Int, replacement: String) {
        checkStatus(QuillBindings.docReplace(requireOpen(), start, end, replacement), "replace")
    }

    /** Replaces the entire contents. */
    public fun setText(text: String) {
        checkStatus(QuillBindings.docSetText(requireOpen(), text), "setText")
    }

    /** Parses the document into the block IR that drives the preview. */
    public fun blocks(): List<MarkdownBlockIr> =
        decodeBlocks(QuillBindings.docBlocks(requireOpen()).require("blocks"))

    /**
     * Syntax spans for the Markdown source, limited to lines `[firstLine, lastLine]` (zero-based,
     * inclusive).
     *
     * Restricting the range to the visible viewport is what keeps highlighting cheap on large
     * documents. The engine still scans preceding lines for code-fence state, so a windowed result
     * is identical to highlighting everything and discarding the rest.
     */
    public fun spans(firstLine: Int, lastLine: Int): List<StyleSpan> {
        require(firstLine >= 0) { "firstLine must not be negative, was $firstLine" }
        require(lastLine >= firstLine) { "lastLine ($lastLine) must not precede firstLine ($firstLine)" }
        return decodeSpans(QuillBindings.docSpans(requireOpen(), firstLine, lastLine).require("spans"))
    }

    /** The heading outline. */
    public fun outline(): List<OutlineEntry> =
        decodeOutline(QuillBindings.docOutline(requireOpen()).require("outline"))

    /** Word, character and reading-time statistics. */
    public fun stats(): DocumentStats = decodeStats(QuillBindings.docStats(requireOpen()).require("stats"))

    /**
     * Finds every occurrence of [query]. See [SearchFlags].
     *
     * @throws QuillEngineException if [SearchFlags.REGEX] is set and the pattern does not compile.
     */
    public fun search(query: String, flags: Int = SearchFlags.NONE): List<SearchMatch> {
        if (query.isEmpty()) return emptyList()
        return decodeSearch(QuillBindings.docSearch(requireOpen(), query, flags).require("search"))
    }

    /** Replaces every match of [query] with [replacement], mutating the document. */
    public fun replaceAll(query: String, replacement: String, flags: Int = SearchFlags.NONE) {
        if (query.isEmpty()) return
        checkStatus(QuillBindings.docReplaceAll(requireOpen(), query, replacement, flags), "replaceAll")
    }

    /** Renders the document to HTML. See [ExportOptions]. */
    public fun exportHtml(title: String, options: Int = ExportOptions.STANDALONE): String =
        decodeText(QuillBindings.docExportHtml(requireOpen(), title, options).require("exportHtml"))

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            cleanable.clean()
        }
    }

    private class DocumentReleaser(
        private val handle: MemorySegment,
        private val closed: AtomicBoolean,
    ) : Runnable {
        private val released = AtomicBoolean(false)

        override fun run() {
            if (released.compareAndSet(false, true)) {
                closed.set(true)
                QuillBindings.docFree(handle)
            }
        }
    }
}
