package dev.starfect.quill.bridge

import dev.starfect.quill.bridge.internal.QuillBindings
import dev.starfect.quill.bridge.wire.DocumentStats
import dev.starfect.quill.bridge.wire.Finding
import dev.starfect.quill.bridge.wire.HtmlNode
import dev.starfect.quill.bridge.wire.MarkdownBlockIr
import dev.starfect.quill.bridge.wire.OutlineEntry
import dev.starfect.quill.bridge.wire.SearchMatch
import dev.starfect.quill.bridge.wire.StyleSpan
import dev.starfect.quill.bridge.wire.decodeBlocks
import dev.starfect.quill.bridge.wire.decodeHtmlDom
import dev.starfect.quill.bridge.wire.decodeInspections
import dev.starfect.quill.bridge.wire.decodeOutline
import dev.starfect.quill.bridge.wire.decodeSearch
import dev.starfect.quill.bridge.wire.decodeSpans
import dev.starfect.quill.bridge.wire.decodeStats
import dev.starfect.quill.bridge.wire.decodeText
import java.lang.foreign.MemorySegment
import java.lang.ref.Cleaner
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

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
 *
 * That includes [close]: closing while another thread is inside a call is safe, and closing *waits*
 * for that call to return before the native document is freed.
 */
public class QuillDocument internal constructor(
    private val handle: MemorySegment,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private val cleanable = CLEANER.register(this, DocumentReleaser(handle, closed))

    /**
     * Guards the handle's lifetime: every call holds it for read, [close] takes it for write.
     *
     * A plain `closed` flag is not enough, and the difference is a crash rather than an exception.
     * Checking the flag and then making the downcall are two steps, and a `close` landing between
     * them frees the document while the other thread is inside the engine — the process aborts in
     * glibc with a corrupted heap, some distance from the code that caused it.
     *
     * The write lock is what makes closing *wait* rather than race. A worker deriving a preview has
     * no suspension point inside a native call at which to be cancelled, so waiting is the only
     * thing that can be true here.
     *
     * The one rule this imposes: never call [close] from inside one of these calls. Read locks do
     * not upgrade, so that self-deadlocks.
     */
    private val lifetime = ReentrantReadWriteLock()

    private companion object {
        val CLEANER: Cleaner = Cleaner.create { runnable ->
            Thread(runnable, "quill-document-cleaner").apply { isDaemon = true }
        }
    }

    /** Runs [block] with the native handle, holding it open for the duration. */
    private inline fun <T> withHandle(block: (MemorySegment) -> T): T = lifetime.read {
        check(!closed.get()) { "this QuillDocument has been closed" }
        block(handle)
    }

    /** Monotonic version, incremented on every mutation. */
    public val version: Long
        get() {
            val value = withHandle { QuillBindings.docVersion(it) }
            if (value < 0) throw QuillEngineException(value.toInt(), "version", lastEngineError())
            return value
        }

    /** Length in UTF-16 code units. */
    public val length: Int
        get() {
            val value = withHandle { QuillBindings.docLenUtf16(it) }
            if (value < 0) throw QuillEngineException(value.toInt(), "length", lastEngineError())
            return value.toInt()
        }

    /** The full document text. */
    public fun text(): String = decodeText(withHandle { QuillBindings.docText(it) }.require("text"))

    /**
     * Replaces the UTF-16 range `[start, end)` with [replacement].
     *
     * This is the incremental path: the engine applies the edit to its rope instead of rebuilding
     * from a fresh string, which is what keeps typing cheap in a large document.
     */
    public fun replace(start: Int, end: Int, replacement: String) {
        checkStatus(withHandle { QuillBindings.docReplace(it, start, end, replacement) }, "replace")
    }

    /** Replaces the entire contents. */
    public fun setText(text: String) {
        checkStatus(withHandle { QuillBindings.docSetText(it, text) }, "setText")
    }

    /**
     * The dialect this document is parsed as.
     *
     * Assigning re-derives everything: the cached parse, outline, statistics and preview are
     * discarded, and the version counter advances so a consumer watching it re-reads.
     */
    public var flavour: MarkdownFlavour
        get() {
            val value = withHandle { QuillBindings.docFlavour(it) }
            if (value < 0) throw QuillEngineException(value, "flavour", lastEngineError())
            return MarkdownFlavour.fromId(value)
        }
        set(value) {
            checkStatus(withHandle { QuillBindings.docSetFlavour(it, value.id) }, "setFlavour")
        }

    /** Parses the document into the block IR that drives the outline and the editor. */
    public fun blocks(): List<MarkdownBlockIr> =
        decodeBlocks(withHandle { QuillBindings.docBlocks(it) }.require("blocks"))

    /**
     * Renders the document to HTML and returns the parsed result.
     *
     * This is what the preview draws. Rendering through HTML rather than straight from the Markdown
     * AST is what keeps the preview and the exported file identical, and it is the only path on
     * which raw HTML in the source and the flavour extensions render as markup instead of as text.
     */
    public fun htmlDom(): List<HtmlNode> =
        decodeHtmlDom(withHandle { QuillBindings.docHtmlDom(it) }.require("htmlDom"))

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
        return decodeSpans(withHandle { QuillBindings.docSpans(it, firstLine, lastLine) }.require("spans"))
    }

    /** The heading outline. */
    public fun outline(): List<OutlineEntry> =
        decodeOutline(withHandle { QuillBindings.docOutline(it) }.require("outline"))

    /**
     * Problems found in the document, in source order.
     *
     * Ordered by position rather than by severity: the list is read alongside the document, and
     * jumping around it in severity order makes it useless for working through.
     */
    public fun inspections(): List<Finding> =
        decodeInspections(withHandle { QuillBindings.docInspections(it) }.require("inspections"))

    /** Word, character and reading-time statistics. */
    public fun stats(): DocumentStats = decodeStats(withHandle { QuillBindings.docStats(it) }.require("stats"))

    /**
     * Finds every occurrence of [query]. See [SearchFlags].
     *
     * @throws QuillEngineException if [SearchFlags.REGEX] is set and the pattern does not compile.
     */
    public fun search(query: String, flags: Int = SearchFlags.NONE): List<SearchMatch> {
        if (query.isEmpty()) return emptyList()
        return decodeSearch(withHandle { QuillBindings.docSearch(it, query, flags) }.require("search"))
    }

    /** Replaces every match of [query] with [replacement], mutating the document. */
    public fun replaceAll(query: String, replacement: String, flags: Int = SearchFlags.NONE) {
        if (query.isEmpty()) return
        checkStatus(withHandle { QuillBindings.docReplaceAll(it, query, replacement, flags) }, "replaceAll")
    }

    /** Renders the document to HTML. See [ExportOptions]. */
    public fun exportHtml(title: String, options: Int = ExportOptions.STANDALONE): String =
        decodeText(withHandle { QuillBindings.docExportHtml(it, title, options) }.require("exportHtml"))

    override fun close() {
        // Blocks until every in-flight call has returned. Freeing under a call is what corrupts the
        // heap, and it does not report itself where it happened.
        lifetime.write {
            if (closed.compareAndSet(false, true)) {
                cleanable.clean()
            }
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
