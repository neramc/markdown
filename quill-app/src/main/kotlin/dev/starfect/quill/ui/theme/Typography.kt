package dev.starfect.quill.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * The shell's type: two families, one base size, and a scale expressed relative to it.
 *
 * The platform's typography rule is stated as *relative* steps — H1 is "default +5", help text is
 * "default −1" — and the base is a user setting that everything else follows. Absolute sizes cannot
 * express that: raising the base from 13 to 15 has to move the whole scale, and a token file full of
 * 13/12/11 moves none of it. So [UiTypeScale] is built from a base and the offsets the platform
 * documents, and the shell reads its sizes from the scale rather than from constants.
 *
 * Both families are the ones the platform specifies — Inter for the UI, JetBrains Mono for the
 * editor — and both are already on disk: the JetBrains Runtime this application is built and
 * packaged against bundles them in its own `lib/fonts`, so they travel with the jlink image at no
 * extra size. On a stock JDK they are absent and the scale falls back to the platform default, which
 * is the right outcome: a missing font should change how the window looks, not stop it opening.
 */
public object UiFonts {

    /** Where the runtime keeps its bundled fonts. Present on the JetBrains Runtime, absent elsewhere. */
    private val fontDirectory: File? =
        System.getProperty("java.home")
            ?.let { File(it, "lib/fonts") }
            ?.takeIf { it.isDirectory }

    private fun family(vararg faces: Triple<String, FontWeight, FontStyle>): FontFamily? {
        val directory = fontDirectory ?: return null
        val loaded = faces.mapNotNull { (name, weight, style) ->
            directory.resolve(name).takeIf { it.isFile }?.let { Font(it, weight, style) }
        }
        return loaded.takeIf { it.isNotEmpty() }?.let { FontFamily(it) }
    }

    /**
     * The UI family: Inter.
     *
     * Only regular and semibold are loaded, because those are the only two weights the type scale
     * uses. Compose synthesises anything else, and a synthesised bold is better than shipping a
     * weight nothing asks for.
     */
    public val Ui: FontFamily = family(
        Triple("Inter-Regular.otf", FontWeight.Normal, FontStyle.Normal),
        Triple("Inter-Italic.otf", FontWeight.Normal, FontStyle.Italic),
        Triple("Inter-SemiBold.otf", FontWeight.SemiBold, FontStyle.Normal),
        Triple("Inter-SemiBoldItalic.otf", FontWeight.SemiBold, FontStyle.Italic),
    ) ?: FontFamily.Default

    /** The editor family: JetBrains Mono. */
    public val Editor: FontFamily = family(
        Triple("JetBrainsMono-Regular.ttf", FontWeight.Normal, FontStyle.Normal),
        Triple("JetBrainsMono-Italic.ttf", FontWeight.Normal, FontStyle.Italic),
        Triple("JetBrainsMono-Bold.ttf", FontWeight.Bold, FontStyle.Normal),
        Triple("JetBrainsMono-BoldItalic.ttf", FontWeight.Bold, FontStyle.Italic),
        Triple("JetBrainsMono-Medium.ttf", FontWeight.Medium, FontStyle.Normal),
    ) ?: FontFamily.Monospace

    /** Whether the bundled families were found, which the About dialog reports. */
    public val bundled: Boolean = Ui !== FontFamily.Default
}

/**
 * The type scale, derived from a base size.
 *
 * Every step is the platform's own: H1 is base+5, H2 is base+3, help text is base−1, and a
 * paragraph's line height is its size +3. Naming them after their role rather than their size is
 * what lets the base move without every call site needing to be found again.
 */
@Immutable
public class UiTypeScale(
    /** The user's UI font size. The platform's default is 13. */
    public val base: TextUnit,
) {
    /** Main page header. */
    public val h1: TextUnit = (base.value + 5).sp

    /** Small page header. */
    public val h2: TextUnit = (base.value + 3).sp

    /** Labels, inputs, links, tree rows, table rows, tabs, menu items. */
    public val default: TextUnit = base

    /** Help text, and the metadata that sits beside a label. */
    public val medium: TextUnit = (base.value - 1).sp

    /** Line height for a paragraph of description text. */
    public val paragraphLineHeight: TextUnit = (base.value + 3).sp

    /**
     * The weight for a dialog, popup, notification or tool window header.
     *
     * The platform makes headers semibold at the *default* size rather than larger. That is what
     * keeps a tool window header from reading as a heading in a document: it is the same size as the
     * rows beneath it and differs only in weight.
     */
    public val headerWeight: FontWeight = FontWeight.SemiBold

    public companion object {
        /** The platform's default UI size. */
        public val DEFAULT_BASE: TextUnit = 13.sp

        public val Default: UiTypeScale = UiTypeScale(DEFAULT_BASE)

        /** The range the settings dialog offers, matching what the IDE accepts. */
        public val SIZES: List<Int> = listOf(11, 12, 13, 14, 15, 16, 18, 20)
    }
}
