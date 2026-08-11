package dev.starfect.quill.ui.theme

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
import org.jetbrains.jewel.intui.window.styling.dark
import org.jetbrains.jewel.intui.window.styling.light
import org.jetbrains.jewel.window.styling.TitleBarColors
import org.jetbrains.jewel.window.styling.TitleBarStyle
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.milliseconds
import org.jetbrains.jewel.intui.standalone.styling.dark
import org.jetbrains.jewel.intui.standalone.styling.macOsDark
import org.jetbrains.jewel.intui.standalone.styling.macOsLight
import org.jetbrains.jewel.intui.standalone.styling.light
import org.jetbrains.jewel.ui.component.styling.LocalMenuStyle
import org.jetbrains.jewel.ui.component.styling.LocalScrollbarStyle
import org.jetbrains.jewel.ui.component.styling.MenuColors
import org.jetbrains.jewel.ui.component.styling.MenuMetrics
import org.jetbrains.jewel.ui.component.styling.MenuStyle
import org.jetbrains.jewel.ui.component.styling.ScrollbarMetrics
import org.jetbrains.jewel.ui.component.styling.ScrollbarStyle
import org.jetbrains.jewel.ui.component.styling.ScrollbarVisibility

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
    /** The line number on the caret's row, which the IDE draws brighter than the rest. */
    public val gutterCurrentLineForeground: Color,
    /** Caret row highlight, painted the full width of the editor behind the text. */
    public val caretRowBackground: Color,
    /** Fill behind a search match. */
    public val searchMatchBackground: Color,
    /** The right-margin guide, drawn at the configured column. */
    public val rightMargin: Color,
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
            // The New UI gutter shares the editor's background rather than sitting on a panel
            // colour; only the numbers themselves are dimmer.
            gutterBackground = Color(0xFF1E1F22),
            gutterForeground = Color(0xFF4B5059),
            gutterCurrentLineForeground = Color(0xFFA1A3AB),
            caretRowBackground = Color(0xFF26282E),
            searchMatchBackground = Color(0xFF32593D),
            rightMargin = Color(0xFF393B40),
            tokens = DARK_TOKENS,
        )

        public val Light: EditorPalette = EditorPalette(
            text = Color(0xFF000000),
            background = Color(0xFFFFFFFF),
            gutterBackground = Color(0xFFFFFFFF),
            gutterForeground = Color(0xFFADB1B8),
            gutterCurrentLineForeground = Color(0xFF5A5D63),
            caretRowBackground = Color(0xFFF2F5F9),
            searchMatchBackground = Color(0xFFC2E5C2),
            rightMargin = Color(0xFFD3D5DB),
            tokens = LIGHT_TOKENS,
        )

        public fun of(dark: Boolean): EditorPalette = if (dark) Dark else Light
    }
}

/**
 * The IDE shell's palette.
 *
 * Two things make this read as a tool rather than as a dark web app, and neither is the hue.
 *
 * **The tones are very close together.** Root, panel, hover and selection sit within a few points of
 * each other. Regions are separated by a barely-perceptible tone change rather than by a block of
 * contrasting colour, which is why the window reads as one continuous work surface.
 *
 * **Text is layered rather than uniformly bright.** Primary, secondary and muted are three distinct
 * steps. Painting every label the same white is the single fastest way to make a dense UI unreadable:
 * with nothing receding, everything competes.
 *
 * Selection in particular is a low-contrast fill, not a saturated blue. Accent is reserved for
 * saying *what is active* — the selected tab's underline, focus, links — and loses that job the
 * moment it is used for decoration.
 */
@Immutable
public class ShellPalette(
    /** Panels, tool windows, the main toolbar and the status bar. */
    public val toolWindowBackground: Color,
    /**
     * A panel nested inside another, such as a dialog's category list.
     *
     * One step away from [toolWindowBackground], not a contrasting colour.
     */
    public val panelSecondary: Color,
    /** Status bar; separate because it can be themed apart from the panels. */
    public val statusBarBackground: Color,
    /**
     * The separators between regions.
     *
     * Deliberately close to the panel it sits on. A shell whose first impression is "there are a lot
     * of lines" has borders doing work that a tone change should be doing.
     */
    public val border: Color,
    /** Primary text: file names, tool window titles, active elements. */
    public val text: Color,
    /** Secondary text: descriptions, supporting information. */
    public val secondaryText: Color,
    /** Muted text: metadata, counters, inactive states. */
    public val mutedText: Color,
    /** Default icon tint. */
    public val icon: Color,
    /** Icon tint for something disabled or inactive. */
    public val mutedIcon: Color,
    /** Hover fill, shared by every hoverable surface in the shell. */
    public val hoverBackground: Color,
    /** Pressed fill. */
    public val pressedBackground: Color,
    /** Selected row or toggled-on control. A low-contrast fill, not a saturated blue. */
    public val selectionBackground: Color,
    /** Selected row when its container does not have focus, which is weaker again. */
    public val inactiveSelectionBackground: Color,
    /** The blue the IDE uses for links, underlines and focus. */
    public val accent: Color,
    /** Editor tab strip background, behind the tabs. */
    public val tabBarBackground: Color,
    /** The selected editor tab's fill, which matches the editor itself. */
    public val tabSelectedBackground: Color,
    /** The accent bar under the selected editor tab. */
    public val tabUnderline: Color,
    /** Popup and dialog surface, a step above the panels. */
    public val popupBackground: Color,
    /** Popup border, stronger than the shell separators. */
    public val popupBorder: Color,
    /** Error text and invalid-input outlines. */
    public val error: Color,
    /** Warning severity, in inspections and the problems list. */
    public val warning: Color,
    /** The all-clear tick, and anything reporting a completed action. */
    public val success: Color,
    /** Modified-file marker in tabs and the project tree. */
    public val modified: Color,
    /** Ordinary directory icons in the project view. */
    public val folderIcon: Color,
    /** Directories the IDE treats as sources, which it tints. */
    public val sourceFolderIcon: Color,
    /** Build output and other excluded directories, which the IDE tints orange. */
    public val excludedIcon: Color,
    /** Surface behind the welcome window's content pane. */
    public val welcomeBackground: Color,
) {
    public companion object {
        public val Dark: ShellPalette = ShellPalette(
            toolWindowBackground = Color(0xFF1F2023),
            panelSecondary = Color(0xFF25262A),
            statusBarBackground = Color(0xFF1F2023),
            border = Color(0xFF2B2D30),
            text = Color(0xFFD7D9DC),
            secondaryText = Color(0xFFA6A8AD),
            mutedText = Color(0xFF777A80),
            icon = Color(0xFFA6A8AD),
            mutedIcon = Color(0xFF777A80),
            hoverBackground = Color(0xFF2B2D30),
            pressedBackground = Color(0xFF3A3D42),
            selectionBackground = Color(0xFF34373B),
            inactiveSelectionBackground = Color(0xFF2B2D30),
            accent = Color(0xFF4D8DFF),
            tabBarBackground = Color(0xFF1F2023),
            tabSelectedBackground = Color(0xFF1E1F22),
            tabUnderline = Color(0xFF4D8DFF),
            popupBackground = Color(0xFF25262A),
            popupBorder = Color(0xFF34373B),
            error = Color(0xFFDB5C5C),
            warning = Color(0xFFE0A22B),
            success = Color(0xFF5FAD65),
            modified = Color(0xFF548AF7),
            folderIcon = Color(0xFF9AA7B0),
            sourceFolderIcon = Color(0xFF5FA8F5),
            excludedIcon = Color(0xFFCC7832),
            welcomeBackground = Color(0xFF1E1F22),
        )

        // The same relationships inverted: tones a few points apart, three text steps, a selection
        // that is a soft grey rather than a saturated blue.
        public val Light: ShellPalette = ShellPalette(
            toolWindowBackground = Color(0xFFF7F8FA),
            panelSecondary = Color(0xFFF0F1F4),
            statusBarBackground = Color(0xFFF7F8FA),
            border = Color(0xFFE4E6EB),
            text = Color(0xFF25272B),
            secondaryText = Color(0xFF5A5D63),
            mutedText = Color(0xFF8C8F96),
            icon = Color(0xFF5A5D63),
            mutedIcon = Color(0xFF8C8F96),
            hoverBackground = Color(0xFFEBECF0),
            pressedBackground = Color(0xFFDCDEE3),
            selectionBackground = Color(0xFFE0E2E7),
            inactiveSelectionBackground = Color(0xFFEBECF0),
            accent = Color(0xFF3574F0),
            tabBarBackground = Color(0xFFF7F8FA),
            tabSelectedBackground = Color(0xFFFFFFFF),
            tabUnderline = Color(0xFF3574F0),
            popupBackground = Color(0xFFFFFFFF),
            popupBorder = Color(0xFFD6D9E0),
            error = Color(0xFFC94F4F),
            warning = Color(0xFFC28A18),
            success = Color(0xFF3D8B45),
            modified = Color(0xFF3574F0),
            folderIcon = Color(0xFF7A8494),
            sourceFolderIcon = Color(0xFF3574F0),
            excludedIcon = Color(0xFFA1651E),
            welcomeBackground = Color(0xFFFFFFFF),
        )

        public fun of(dark: Boolean): ShellPalette = if (dark) Dark else Light

        /**
         * The colours IntelliJ tints a project's avatar badge with.
         *
         * The IDE assigns one deterministically from the project name so the same project always
         * carries the same colour across sessions and machines — the badge is a recognition aid, and
         * a badge that changed colour on every launch would be worse than none.
         */
        private val BadgeColors: List<Color> = listOf(
            Color(0xFF8B5CF6),
            Color(0xFF3574F0),
            Color(0xFF16A394),
            Color(0xFF5FAD65),
            Color(0xFFE08855),
            Color(0xFFDB5C5C),
            Color(0xFFD96BA8),
            Color(0xFF6B7FD7),
        )

        /** Picks the badge colour for a project name. */
        public fun badgeColor(name: String): Color {
            if (name.isEmpty()) return BadgeColors[0]
            // A stable, order-independent hash: String.hashCode would work too, but folding the
            // characters keeps the choice from clustering for names sharing a prefix.
            var hash = 0
            for (character in name) {
                hash = (hash * 31 + character.code) and 0x7FFFFFFF
            }
            return BadgeColors[hash % BadgeColors.size]
        }
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

    val shell = remember(dark) { ShellPalette.of(dark) }

    IntUiTheme(
        theme = themeDefinition,
        styling = ComponentStyling.default()
            .decoratedWindow(titleBarStyle = shellTitleBarStyle(dark, shell))
            .provide {
                arrayOf(
                    LocalScrollbarStyle provides quietScrollbarStyle(dark),
                    LocalMenuStyle provides shellMenuStyle(dark, shell),
                )
            },
        swingCompatMode = false,
    ) {
        val editor = remember(dark) { EditorPalette.of(dark) }
        CompositionLocalProvider(
            LocalEditorPalette provides editor,
            LocalShellPalette provides shell,
            content = content,
        )
    }
}

/**
 * A scrollbar that stays out of the way.
 *
 * Jewel ships two shapes: an always-visible bar with a track, and an overlay thumb that fades in
 * while scrolling and fades out afterwards. The overlay is the one an IDE wants. A bright, chunky
 * scrollbar down the edge of every panel is among the loudest things a dense UI can carry, and it
 * carries no information — the thumb's position is the only part anybody reads.
 */
@Composable
private fun quietScrollbarStyle(dark: Boolean): ScrollbarStyle =
    if (dark) ScrollbarStyle.macOsDark() else ScrollbarStyle.macOsLight()

/**
 * Popup menus drawn from the same palette and type scale as the window behind them.
 *
 * A menu that does not match the shell is the fastest way to make a carefully-built window look
 * assembled from parts, and it is the part users see least often and notice most.
 */
@Composable
private fun shellMenuStyle(dark: Boolean, shell: ShellPalette): MenuStyle {
    val base = if (dark) MenuStyle.dark() else MenuStyle.light()
    return MenuStyle(
        isDark = dark,
        colors = MenuColors(
            background = shell.popupBackground,
            border = shell.popupBorder,
            shadow = base.colors.shadow,
            itemColors = base.colors.itemColors,
        ),
        metrics = MenuMetrics(
            cornerSize = CornerSize(Tokens.Radius.Popup),
            menuMargin = base.metrics.menuMargin,
            contentPadding = PaddingValues(vertical = Tokens.Spacing.Tiny),
            offset = base.metrics.offset,
            shadowSize = base.metrics.shadowSize,
            borderWidth = 1.dp,
            itemMetrics = base.metrics.itemMetrics,
            submenuMetrics = base.metrics.submenuMetrics,
        ),
        icons = base.icons,
    )
}

/**
 * The window's own title bar, painted from the shell's palette.
 *
 * Jewel's default title bar sits about twelve points lighter than the tool windows below it, which
 * puts a visible band across the top of a shell whose whole character is that its regions differ by
 * two or three points. Only the background and the border need overriding; everything else the
 * default supplies is already right.
 */
@Composable
private fun shellTitleBarStyle(dark: Boolean, shell: ShellPalette): TitleBarStyle {
    val colors = if (dark) {
        TitleBarColors.dark(
            backgroundColor = shell.toolWindowBackground,
            inactiveBackground = shell.toolWindowBackground,
            borderColor = shell.border,
        )
    } else {
        TitleBarColors.light(
            backgroundColor = shell.toolWindowBackground,
            inactiveBackground = shell.toolWindowBackground,
            borderColor = shell.border,
        )
    }
    return if (dark) TitleBarStyle.dark(colors = colors) else TitleBarStyle.light(colors = colors)
}
