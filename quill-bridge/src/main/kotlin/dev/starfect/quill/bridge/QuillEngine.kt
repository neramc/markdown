package dev.starfect.quill.bridge

import dev.starfect.quill.bridge.internal.QuillBindings
import dev.starfect.quill.bridge.wire.ColorSpan
import dev.starfect.quill.bridge.wire.decodeColorSpans
import java.lang.foreign.MemorySegment
import java.lang.ref.Cleaner
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * The Quill core engine.
 *
 * One engine is enough for a whole application: it holds shared configuration and the syntax
 * definitions, and documents opened from it are independently locked, so calls may come from any
 * thread. The calls themselves block while the engine parses, so callers should keep them off the
 * UI thread.
 *
 * The handle is released by [close]. A [Cleaner] is registered as a backstop so a forgotten engine
 * is eventually reclaimed rather than leaking for the process lifetime — that is a safety net, not
 * the intended lifecycle, since GC timing is not a resource-management strategy.
 */
public class QuillEngine private constructor(
    private val handle: MemorySegment,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private val cleanable = CLEANER.register(this, EngineReleaser(handle, closed))

    public companion object {
        private val CLEANER: Cleaner = Cleaner.create { runnable ->
            Thread(runnable, "quill-native-cleaner").apply { isDaemon = true }
        }

        /** The ABI version this bridge implements. */
        public const val EXPECTED_ABI_VERSION: Int = 1

        /**
         * Creates an engine.
         *
         * @param darkTheme selects the palette used for fenced code-block highlighting.
         * @throws QuillNativeLibraryException if the library is missing or its ABI does not match.
         */
        public fun create(darkTheme: Boolean = true): QuillEngine {
            val actualAbi = try {
                QuillBindings.abiVersion()
            } catch (failure: UnsatisfiedLinkError) {
                throw QuillNativeLibraryException(
                    failure.message ?: "the Quill core library could not be loaded",
                    failure,
                )
            }
            if (actualAbi != EXPECTED_ABI_VERSION) {
                throw QuillNativeLibraryException(
                    "Quill core ABI $actualAbi does not match the $EXPECTED_ABI_VERSION this build expects; " +
                        "the native library and the application are from different versions"
                )
            }

            val handle = QuillBindings.engineNew(darkTheme)
            if (handle == MemorySegment.NULL) {
                throw QuillNativeLibraryException(
                    "the engine could not be created: ${lastEngineError() ?: "no detail reported"}"
                )
            }
            return QuillEngine(handle)
        }
    }

    /**
     * Guards the handle's lifetime. See the same field on [QuillDocument] for why a flag alone is
     * not enough: the flag check and the downcall are two steps, and a close between them frees the
     * engine out from under a thread that is inside it.
     */
    private val lifetime = ReentrantReadWriteLock()

    /** Runs [block] with the native handle, holding it open for the duration. */
    private inline fun <T> withHandle(block: (MemorySegment) -> T): T = lifetime.read {
        check(!closed.get()) { "this QuillEngine has been closed" }
        block(handle)
    }

    /** Switches the palette used for fenced code-block highlighting. */
    public fun setDarkTheme(dark: Boolean) {
        checkStatus(withHandle { QuillBindings.engineSetDark(it, dark) }, "setDarkTheme")
    }

    /** Opens a document. The caller owns the result and must close it. */
    public fun openDocument(text: String = ""): QuillDocument {
        val handle = withHandle { QuillBindings.docOpen(it, text) }
        if (handle == MemorySegment.NULL) {
            throw QuillEngineException(QuillStatus.INVALID_ARGUMENT, "openDocument", lastEngineError())
        }
        return QuillDocument(handle)
    }

    /**
     * Highlights a fenced code block.
     *
     * An unknown language is not an error: it comes back as a single default-coloured run, so a
     * fence tagged with something exotic still renders as code.
     */
    public fun highlightCode(code: String, language: String): List<ColorSpan> {
        if (code.isEmpty()) return emptyList()
        return decodeColorSpans(withHandle { QuillBindings.highlightCode(it, code, language) }.require("highlightCode"))
    }

    override fun close() {
        // Waits for in-flight calls, so the engine is never freed under one.
        lifetime.write {
            if (closed.compareAndSet(false, true)) {
                cleanable.clean()
            }
        }
    }

    /**
     * Releases the native handle.
     *
     * Deliberately a static class holding no reference to the [QuillEngine]: capturing the owner
     * would keep it reachable and the cleaner would never run.
     */
    private class EngineReleaser(
        private val handle: MemorySegment,
        private val closed: AtomicBoolean,
    ) : Runnable {
        private val released = AtomicBoolean(false)

        override fun run() {
            if (released.compareAndSet(false, true)) {
                closed.set(true)
                QuillBindings.engineFree(handle)
            }
        }
    }
}
