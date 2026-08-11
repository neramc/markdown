package dev.starfect.quill.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Every size, space, radius and type step the IDE shell is built from.
 *
 * The point of putting them here rather than at each call site is that this shell's character comes
 * from density, not colour. A 40dp toolbar and a 36dp toolbar are both plausible in isolation and
 * instantly different side by side, and the difference compounds: once one component is 4dp too
 * tall the ones around it get padded to match, and the result reads as a comfortable web app rather
 * than as a tool. Changing one value here has to move everything that value describes.
 *
 * Nothing about the Markdown editor or the rendered preview is described here. Their typography and
 * spacing belong to the document, not to the shell, and they are deliberately left alone.
 */
public object Tokens {

    // ------------------------------------------------------------------ spacing
    /**
     * The only gaps the shell uses.
     *
     * A fixed scale is what stops a layout accumulating 7dp here and 13dp there until nothing lines
     * up with anything. When a gap looks wrong the fix is the next step on this scale, not a new
     * number between two of them.
     */
    public object Spacing {
        /** Between an icon and the text it labels. */
        public val Tiny: Dp = 4.dp

        /** Small internal gaps, and between adjacent controls in a row. */
        public val Small: Dp = 8.dp

        /** Inside a button or a header. */
        public val Medium: Dp = 12.dp

        /** Inside a tool window. */
        public val Large: Dp = 16.dp

        /** Between major regions. */
        public val XLarge: Dp = 20.dp

        /** The editor shell's own generous inset, and dialog padding. */
        public val XXLarge: Dp = 24.dp
    }

    // ------------------------------------------------------------------ radius
    /**
     * Corner radii, kept small on purpose.
     *
     * A panel with a large radius reads as a card floating on a page. An IDE is not a dashboard:
     * its panels and its editor are one continuous work surface, and the boundaries between them
     * are tone changes rather than outlines. Only things that genuinely float — popups, dialogs —
     * get a radius worth noticing.
     */
    public object Radius {
        /** Panels, tool windows and the editor shell. Square. */
        public val Panel: Dp = 0.dp

        /** A tree row's hover and selection fill. */
        public val Row: Dp = 2.dp

        /** A toolbar or stripe button's hover fill. */
        public val Control: Dp = 4.dp

        /** Popups and menus, which really do float above the shell. */
        public val Popup: Dp = 8.dp

        /** Dialogs. */
        public val Dialog: Dp = 10.dp
    }

    // ------------------------------------------------------------------ heights and widths
    /** The main toolbar, i.e. the custom-decorated title bar. */
    public val ToolbarHeight: Dp = 36.dp

    /** The narrow icon rail down each edge of the window. */
    public val ToolWindowBarWidth: Dp = 32.dp

    /** A button on that rail. Square, and the full width of the rail. */
    public val ToolWindowBarButton: Dp = 32.dp

    /** The header above a docked tool window. */
    public val ToolWindowHeaderHeight: Dp = 32.dp

    /** Default width of a docked tool window. */
    public val ToolWindowWidth: Dp = 248.dp

    /** An editor tab, and the strip that holds them. */
    public val TabHeight: Dp = 34.dp

    /** The accent bar under the selected tab. */
    public val TabUnderlineThickness: Dp = 2.dp

    /** The close button on a tab. */
    public val TabCloseSize: Dp = 14.dp

    /** One row in the project or structure tree. */
    public val TreeRowHeight: Dp = 23.dp

    /** Horizontal step per level of tree nesting. */
    public val TreeIndentStep: Dp = 16.dp

    /** The status bar along the bottom of the window. */
    public val StatusBarHeight: Dp = 22.dp

    /** A square button in a toolbar or a tool window header. */
    public val ControlSize: Dp = 24.dp

    /**
     * The dense button used inside tool window headers, the find bar and the editor toolbar.
     *
     * 22dp around a 16dp icon: the icon nearly fills its button, which is what makes a row of them
     * read as a strip of icons rather than as a row of buttons that happen to contain icons.
     */
    public val SmallControlSize: Dp = 22.dp

    /** The editor's own toolbar, which carries the view switch and the inspection widget. */
    public val EditorToolbarHeight: Dp = 28.dp

    /** The find bar docked under the tabs. */
    public val FindBarHeight: Dp = 30.dp

    /** Line-number gutter width, before it grows for a long document. */
    public val GutterMinWidth: Dp = 46.dp

    /** One row in a popup menu. */
    public val MenuRowHeight: Dp = 28.dp

    /** Scrollbar thickness. */
    public val ScrollbarThickness: Dp = 10.dp

    // ------------------------------------------------------------------ icons
    /** What almost every icon in the shell is drawn at. */
    public val IconSize: Dp = 16.dp

    /** Disclosure triangles, chevrons and badges. */
    public val SmallIconSize: Dp = 12.dp

    /**
     * The one icon size above 16dp: the welcome window's action tiles.
     *
     * It exists so that "bigger than the shell's icons" is a single decision rather than a number
     * invented at each tile, which is how a 30dp icon ended up next to a 16dp one.
     */
    public val LargeIconSize: Dp = 24.dp

    // ------------------------------------------------------------------ typography
    /** Primary UI text: tree rows, tabs, toolbar labels, menu items. */
    public val FontSize: TextUnit = 13.sp

    /** Secondary UI text: tool window headers, descriptions. */
    public val SmallFontSize: TextUnit = 12.sp

    /** Metadata: status bar items, counters, shortcut hints. */
    public val TinyFontSize: TextUnit = 11.sp

    /** A heading over a group of controls: the welcome window's panes, an error screen's title. */
    public val TitleFontSize: TextUnit = 15.sp

    /**
     * The only display-sized text in the product, used for the welcome greeting.
     *
     * Kept as one token rather than a spread of 18/20/22/26sp headings: a shell whose type scale has
     * six steps has no scale, and the sizes drift apart every time a screen is added.
     */
    public val DisplayFontSize: TextUnit = 22.sp

    // ------------------------------------------------------------------ dialogs and popups
    public val DialogTitleHeight: Dp = 36.dp
    public val DialogListWidth: Dp = 220.dp

    /** Search Everywhere. */
    public val SearchPopupWidth: Dp = 680.dp
    public val SearchFieldHeight: Dp = 40.dp
    public val SearchRowHeight: Dp = 26.dp

    /** A scope chip ("All", "Files", "Actions") in the search popup's filter row. */
    public val SearchScopeHeight: Dp = 26.dp

    // ------------------------------------------------------------------ welcome window
    public val WelcomeRailWidth: Dp = 240.dp
    public val WelcomeRecentRowHeight: Dp = 52.dp
    public val WelcomeActionSize: Dp = 104.dp

    /** The coloured square carrying a project's initial, in the toolbar and the welcome list. */
    public val ProjectBadgeSize: Dp = 20.dp
    public val ProjectBadgeCorner: Dp = 4.dp
}
