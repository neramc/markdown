package com.neramc.quill.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.intui.window.decoratedWindow
import org.jetbrains.jewel.intui.window.styling.dark
import org.jetbrains.jewel.intui.window.styling.light
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.window.styling.TitleBarStyle

/**
 * Semantic style identifiers for Markdown source, mirroring `EditorStyle` in the Rust engine.
 *
 * The engine emits the identifier and nothing else; the colour is resolved here. That split is what
 * lets the editor follow the IDE theme — switching to light mode recolours the source without the
 * engine recomputing a single span.
 */
public object EditorStyleId {
    public const val TEXT: Int = 0
    public const val HEADING: Int = 1
    public const val EMPHASIS: Int = 2
    public const val STRONG: Int = 3
    public const val INLINE_CODE: Int = 4
    public const val CODE_FENCE: Int = 5
    public const val CODE_FENCE_INFO: Int = 6
    public const val LINK_TEXT: Int = 7
    public const val LINK_URL: Int = 8
    public const val LIST_MARKER: Int = 9
    public const val BLOCK_QUOTE: Int = 10
    public const val THEMATIC_BREAK: Int = 11
    public const val HTML_TAG: Int = 12
    public const val TABLE_DELIMITER: Int = 13
    public const val TASK_MARKER: Int = 14
    public const val FRONT_MATTER: Int = 15
    public const val STRIKETHROUGH: Int = 16
    public const val IMAGE: Int = 17
    public const val FOOTNOTE_REFERENCE: Int = 18
    public const val AUTO_LINK: Int = 19
}

/** How one semantic token is drawn in the source editor. */
@Immutable
public data class EditorTokenStyle(
    val color: Color,
    val fontWeight: FontWeight? = null,
    val fontStyle: FontStyle? = null,
    val decoration: TextDecoration? = null,
)

/**
 * The editor's Markdown source palette, in the two IntelliJ schemes.
 *
 * These are IntelliJ's own Markdown editor colours rather than an invention, so the source pane
 * reads the same way it does in the IDE.
 */
@Immutable
public class EditorPalette(
    public val text: Color,
    public val background: Color,
    public val gutterBackground: Color,
    public val gutterForeground: Color,
    private val tokens: Map<Int, EditorTokenStyle>,
) {
    public fun styleFor(styleId: Int): EditorTokenStyle? = tokens[styleId]

    public companion object {
        private val DARK_TOKENS = mapOf(
            EditorStyleId.HEADING to EditorTokenStyle(Color(0xFF56A8F5), FontWeight.Bold),
            EditorStyleId.EMPHASIS to EditorTokenStyle(Color(0xFFBCBEC4), fontStyle = FontStyle.Italic),
            EditorStyleId.STRONG to EditorTokenStyle(Color(0xFFBCBEC4), FontWeight.Bold),
            EditorStyleId.INLINE_CODE to EditorTokenStyle(Color(0xFF6AAB73)),
            EditorStyleId.CODE_FENCE to EditorTokenStyle(Color(0xFF6AAB73)),
            EditorStyleId.CODE_FENCE_INFO to EditorTokenStyle(Color(0xFFCF8E6D)),
            EditorStyleId.LINK_TEXT to EditorTokenStyle(Color(0xFF548AF7)),
            EditorStyleId.LINK_URL to EditorTokenStyle(Color(0xFF7A7E85), decoration = TextDecoration.Underline),
            EditorStyleId.LIST_MARKER to EditorTokenStyle(Color(0xFFCF8E6D), FontWeight.Bold),
            EditorStyleId.BLOCK_QUOTE to EditorTokenStyle(Color(0xFF9373A5), FontWeight.Bold),
            EditorStyleId.THEMATIC_BREAK to EditorTokenStyle(Color(0xFF7A7E85)),
            EditorStyleId.HTML_TAG to EditorTokenStyle(Color(0xFFE8BF6A)),
            EditorStyleId.TABLE_DELIMITER to EditorTokenStyle(Color(0xFF7A7E85)),
            EditorStyleId.TASK_MARKER to EditorTokenStyle(Color(0xFF2AACB8), FontWeight.Bold),
            EditorStyleId.FRONT_MATTER to EditorTokenStyle(Color(0xFF7A7E85), fontStyle = FontStyle.Italic),
            EditorStyleId.STRIKETHROUGH to
                EditorTokenStyle(Color(0xFF7A7E85), decoration = TextDecoration.LineThrough),
            EditorStyleId.IMAGE to EditorTokenStyle(Color(0xFF2AACB8)),
            EditorStyleId.FOOTNOTE_REFERENCE to EditorTokenStyle(Color(0xFF9373A5)),
            EditorStyleId.AUTO_LINK to EditorTokenStyle(Color(0xFF548AF7), decoration = TextDecoration.Underline),
        )

        private val LIGHT_TOKENS = mapOf(
            EditorStyleId.HEADING to EditorTokenStyle(Color(0xFF0033B3), FontWeight.Bold),
            EditorStyleId.EMPHASIS to EditorTokenStyle(Color(0xFF000000), fontStyle = FontStyle.Italic),
            EditorStyleId.STRONG to EditorTokenStyle(Color(0xFF000000), FontWeight.Bold),
            EditorStyleId.INLINE_CODE to EditorTokenStyle(Color(0xFF067D17)),
            EditorStyleId.CODE_FENCE to EditorTokenStyle(Color(0xFF067D17)),
            EditorStyleId.CODE_FENCE_INFO to EditorTokenStyle(Color(0xFF9E880D)),
            EditorStyleId.LINK_TEXT to EditorTokenStyle(Color(0xFF2470B3)),
            EditorStyleId.LINK_URL to EditorTokenStyle(Color(0xFF8C8C8C), decoration = TextDecoration.Underline),
            EditorStyleId.LIST_MARKER to EditorTokenStyle(Color(0xFF9E880D), FontWeight.Bold),
            EditorStyleId.BLOCK_QUOTE to EditorTokenStyle(Color(0xFF871094), FontWeight.Bold),
            EditorStyleId.THEMATIC_BREAK to EditorTokenStyle(Color(0xFF8C8C8C)),
            EditorStyleId.HTML_TAG to EditorTokenStyle(Color(0xFF000080), FontWeight.Bold),
            EditorStyleId.TABLE_DELIMITER to EditorTokenStyle(Color(0xFF8C8C8C)),
            EditorStyleId.TASK_MARKER to EditorTokenStyle(Color(0xFF1750EB), FontWeight.Bold),
            EditorStyleId.FRONT_MATTER to EditorTokenStyle(Color(0xFF8C8C8C), fontStyle = FontStyle.Italic),
            EditorStyleId.STRIKETHROUGH to
                EditorTokenStyle(Color(0xFF8C8C8C), decoration = TextDecoration.LineThrough),
            EditorStyleId.IMAGE to EditorTokenStyle(Color(0xFF1750EB)),
            EditorStyleId.FOOTNOTE_REFERENCE to EditorTokenStyle(Color(0xFF871094)),
            EditorStyleId.AUTO_LINK to EditorTokenStyle(Color(0xFF2470B3), decoration = TextDecoration.Underline),
        )

        public val Dark: EditorPalette = EditorPalette(
            text = Color(0xFFBCBEC4),
            background = Color(0xFF1E1F22),
            gutterBackground = Color(0xFF1E1F22),
            gutterForeground = Color(0xFF4E5157),
            tokens = DARK_TOKENS,
        )

        public val Light: EditorPalette = EditorPalette(
            text = Color(0xFF000000),
            background = Color(0xFFFFFFFF),
            gutterBackground = Color(0xFFF7F8FA),
            gutterForeground = Color(0xFFA8ADBD),
            tokens = LIGHT_TOKENS,
        )

        public fun of(dark: Boolean): EditorPalette = if (dark) Dark else Light
    }
}

/** Chrome colours the IDE shell needs that Jewel does not expose directly. */
@Immutable
public class ShellPalette(
    public val toolWindowBackground: Color,
    public val statusBarBackground: Color,
    public val border: Color,
    public val mutedText: Color,
    public val selectionBackground: Color,
    public val accent: Color,
    public val error: Color,
) {
    public companion object {
        public val Dark: ShellPalette = ShellPalette(
            toolWindowBackground = Color(0xFF2B2D30),
            statusBarBackground = Color(0xFF2B2D30),
            border = Color(0xFF393B40),
            mutedText = Color(0xFF7A7E85),
            selectionBackground = Color(0xFF2E436E),
            accent = Color(0xFF548AF7),
            error = Color(0xFFDB5C5C),
        )

        public val Light: ShellPalette = ShellPalette(
            toolWindowBackground = Color(0xFFF7F8FA),
            statusBarBackground = Color(0xFFF7F8FA),
            border = Color(0xFFEBECF0),
            mutedText = Color(0xFF6C707E),
            selectionBackground = Color(0xFFD4E2FF),
            accent = Color(0xFF3574F0),
            error = Color(0xFFC94F4F),
        )

        public fun of(dark: Boolean): ShellPalette = if (dark) Dark else Light
    }
}

public val LocalEditorPalette: ProvidableCompositionLocal<EditorPalette> =
    staticCompositionLocalOf { EditorPalette.Dark }

public val LocalShellPalette: ProvidableCompositionLocal<ShellPalette> =
    staticCompositionLocalOf { ShellPalette.Dark }

/**
 * Applies the IntelliJ theme and Quill's derived palettes.
 *
 * `decoratedWindow()` is what supplies the window and title-bar styling. Without it `IntUiTheme`
 * alone leaves those composition locals empty and `DecoratedWindow` fails at runtime with "No
 * DecoratedWindowStyle provided" — the styling for the custom window decoration lives in a separate
 * module from the base theme.
 */
@Composable
public fun QuillTheme(dark: Boolean, content: @Composable () -> Unit) {
    val themeDefinition = if (dark) JewelTheme.darkThemeDefinition() else JewelTheme.lightThemeDefinition()

    IntUiTheme(
        theme = themeDefinition,
        styling = ComponentStyling.default().decoratedWindow(
            titleBarStyle = if (dark) TitleBarStyle.dark() else TitleBarStyle.light(),
        ),
        swingCompatMode = false,
    ) {
        val editor = remember(dark) { EditorPalette.of(dark) }
        val shell = remember(dark) { ShellPalette.of(dark) }
        CompositionLocalProvider(
            LocalEditorPalette provides editor,
            LocalShellPalette provides shell,
            content = content,
        )
    }
}
