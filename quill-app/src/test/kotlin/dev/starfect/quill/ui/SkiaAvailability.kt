package dev.starfect.quill.ui

import org.junit.jupiter.api.Assumptions

/**
 * Whether this machine can draw at all.
 *
 * The render tests compose the real interface onto a real canvas and assert on the pixels, which
 * needs Skia's native library — and that library needs a graphics stack underneath it. A machine
 * without one cannot run these tests, and that is a fact about the machine rather than a defect in
 * the code: GitHub's arm64 Linux image, for one, ships without the libraries `libskiko` links
 * against, and the load fails before a single pixel is drawn.
 *
 * So the tests are *skipped* there rather than failed. The distinction matters in both directions.
 * Failing would mean a release could never be cut from a runner that happens to be headless, for a
 * reason that has nothing to do with the release. Passing silently would mean nobody ever notices
 * that a platform's interface is going untested. A skip says exactly what happened, in the place
 * where somebody reading the results will see it.
 *
 * Only a failure to *load* is treated this way. Once Skia is loaded, anything it does wrong is the
 * code's problem and fails normally.
 */
internal object SkiaAvailability {

    private val failure: Throwable? by lazy {
        runCatching {
            // Touching the class runs its static initialiser, which is what loads the library.
            Class.forName("org.jetbrains.skia.Surface", true, SkiaAvailability::class.java.classLoader)
        }.exceptionOrNull()
    }

    /** Skips the calling test, with the reason, when Skia's native library will not load. */
    fun require() {
        val cause = failure ?: return
        Assumptions.assumeTrue(
            false,
            "Skia's native library will not load on this machine, so nothing can be rendered to " +
                "assert on. This is the machine, not the code: ${describe(cause)}",
        )
    }

    private fun describe(error: Throwable): String {
        val chain = generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }
        return chain.joinToString(" <- ") { "${it::class.simpleName}: ${it.message?.take(200)}" }
    }
}
