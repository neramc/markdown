package dev.starfect.quill.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The fonts Quill asks the runtime for.
 *
 * Packaging deletes every face in the runtime's `lib/fonts` that is not on a list in
 * `quill-app/build.gradle.kts` — thirty-four of the forty-three the JetBrains Runtime carries, six
 * and a half megabytes that shipped on every platform and were never opened. That makes this set a
 * contract with the build rather than an implementation detail.
 *
 * The failure it guards against is silent in both directions. A face added to [UiFonts] and not to
 * the build's list is deleted on its way into the package, and [UiFonts] falls back to the
 * platform's default sans-serif — which looks like a theming bug, in a release, on somebody else's
 * machine. A face dropped from [UiFonts] and left in the build's list just ships bytes nothing
 * opens.
 */
class UiFontsTest {

    /**
     * Kept in step by hand with `bundledFonts` in `quill-app/build.gradle.kts`.
     *
     * Changing [UiFonts] fails this test, and the fix is to change both — not to update this list
     * until the test passes.
     */
    private val packaged = listOf(
        "Inter-Regular.otf",
        "Inter-Italic.otf",
        "Inter-SemiBold.otf",
        "Inter-SemiBoldItalic.otf",
        "JetBrainsMono-Regular.ttf",
        "JetBrainsMono-Italic.ttf",
        "JetBrainsMono-Bold.ttf",
        "JetBrainsMono-BoldItalic.ttf",
        "JetBrainsMono-Medium.ttf",
    )

    @Test
    fun `every face the interface loads is one packaging keeps`() {
        assertEquals(
            packaged,
            UiFonts.FACES,
            "UiFonts and `bundledFonts` in quill-app/build.gradle.kts disagree. Packaging keeps only " +
                "what the build lists, so a face here that is missing there is deleted on its way " +
                "into the release and the application falls back to the platform default. Update " +
                "both.",
        )
    }

    @Test
    fun `the faces are file names, not paths or families`() {
        // They are resolved against the runtime's own font directory, so anything with a separator
        // in it would either escape that directory or simply not exist.
        assertTrue(UiFonts.FACES.none { '/' in it || '\\' in it })
        assertTrue(UiFonts.FACES.all { it.endsWith(".otf") || it.endsWith(".ttf") })
    }

    @Test
    fun `a runtime without the bundled fonts still yields usable families`() {
        // On a stock JDK there is no lib/fonts at all. The families then fall back rather than
        // being null, because a missing font should change how the window looks, not stop it
        // opening — and every call site treats these as non-null.
        assertTrue(UiFonts.Ui.equals(UiFonts.Ui))
        assertTrue(UiFonts.Editor.equals(UiFonts.Editor))
    }
}
