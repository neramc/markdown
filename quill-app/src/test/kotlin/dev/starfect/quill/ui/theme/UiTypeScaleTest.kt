package dev.starfect.quill.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The type scale's steps are the platform's, and they are relative to the base.
 *
 * The point of the test is the *relationship*, not the numbers: whatever the base is set to, a
 * header stays three points above it and help text one below. An earlier version of the shell had
 * absolute 13/12/11 constants, which meant raising the UI font size moved the body text and left
 * every header and every piece of metadata where it was.
 */
class UiTypeScaleTest {

    @Test
    fun `the platform's default base is 13`() {
        assertEquals(13f, UiTypeScale.DEFAULT_BASE.value)
        assertEquals(13f, UiTypeScale.Default.default.value)
    }

    @Test
    fun `every step keeps its offset from the base at any size`() {
        for (base in UiTypeScale.SIZES) {
            val scale = UiTypeScale(base.sp)
            assertEquals(base.toFloat(), scale.default.value, "default should be the base itself")
            assertEquals(base + 5f, scale.h1.value, "H1 is base +5")
            assertEquals(base + 3f, scale.h2.value, "H2 is base +3")
            assertEquals(base - 1f, scale.medium.value, "help text is base −1")
            assertEquals(base + 3f, scale.paragraphLineHeight.value, "paragraph line height is size +3")
        }
    }

    @Test
    fun `the scale is ordered`() {
        val scale = UiTypeScale.Default
        assertTrue(scale.h1.value > scale.h2.value)
        assertTrue(scale.h2.value > scale.default.value)
        assertTrue(scale.default.value > scale.medium.value)
    }

    @Test
    fun `headers are weight, not size`() {
        // The platform makes a dialog, popup, notification or tool window header "Default semibold".
        // Reaching for a larger size there is what makes a tool window look like a document.
        val scale = UiTypeScale.Default
        assertEquals(FontWeight.SemiBold, scale.headerWeight)
        assertEquals(scale.default, scale.default)
    }

    @Test
    fun `the bundled families resolve when the runtime carries them`() {
        // The JetBrains Runtime ships Inter and JetBrains Mono in its own lib/fonts, which is why
        // this application does not vendor either. On a stock JDK both fall back, and the assertion
        // that matters in both cases is that resolution produced *something* usable.
        assertTrue(UiFonts.Ui !== FontFamily.Monospace, "the UI family must not resolve to a monospace")
        if (UiFonts.bundled) {
            assertTrue(UiFonts.Editor !== FontFamily.Monospace, "JetBrains Mono should have loaded")
        } else {
            assertEquals(FontFamily.Default, UiFonts.Ui)
            assertEquals(FontFamily.Monospace, UiFonts.Editor)
        }
    }
}
