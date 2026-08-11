package dev.starfect.quill.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * The IntelliJ IDEA "New UI" metrics, in one place.
 *
 * These are the sizes the IDE's own layout uses, and they are what make a screenshot read as
 * IntelliJ rather than as a generic dark editor. Guessing them individually per component is how a
 * lookalike ends up almost-but-not-quite: a 28dp toolbar and a 36dp toolbar are both plausible in
 * isolation and instantly wrong side by side.
 */
public object IdeaMetrics {

    /** Main toolbar, i.e. the custom-decorated title bar. */
    public val TitleBarHeight: Dp = 40.dp

    /** Width of the tool window stripe down each edge. */
    public val StripeWidth: Dp = 40.dp

    /** The square button inside that stripe. */
    public val StripeButtonSize: Dp = 30.dp

    /** Header above a docked tool window. */
    public val ToolWindowHeaderHeight: Dp = 32.dp

    /** Editor tab height, and the height of the strip that holds them. */
    public val TabHeight: Dp = 34.dp

    /** Thickness of the accent bar under the selected editor tab. */
    public val TabUnderlineThickness: Dp = 2.dp

    /** Status bar along the bottom of the window, which also carries the breadcrumbs. */
    public val StatusBarHeight: Dp = 26.dp

    /** The coloured square in the project widget that carries the project's initial. */
    public val ProjectBadgeSize: Dp = 20.dp

    /** Corner radius on that badge. */
    public val ProjectBadgeCorner: Dp = 5.dp

    /** One row in the project or structure tree. */
    public val TreeRowHeight: Dp = 24.dp

    /** Horizontal step per tree nesting level. */
    public val TreeIndentStep: Dp = 16.dp

    /** Square toolbar button, as used in the title bar and editor toolbars. */
    public val ActionButtonSize: Dp = 26.dp

    /** Corner radius on hover and selection backgrounds. */
    public val ActionButtonCorner: Dp = 4.dp

    /** The default icon size the IDE draws actions at. */
    public val IconSize: Dp = 16.dp

    /** Icon size on the tool window stripes, which are drawn a touch larger. */
    public val StripeIconSize: Dp = 20.dp

    /** Editor gutter width for line numbers, before it grows for wide documents. */
    public val GutterMinWidth: Dp = 52.dp

    /** Search Everywhere popup width. */
    public val SearchPopupWidth: Dp = 700.dp

    /** Height of the Search Everywhere text field. */
    public val SearchFieldHeight: Dp = 44.dp

    /** One result row in Search Everywhere. */
    public val SearchRowHeight: Dp = 28.dp

    /** The find bar docked at the top of the editor. */
    public val FindBarHeight: Dp = 32.dp

    /** Left navigation rail on the welcome window. */
    /** Corner radius of a dialog panel. */
    public val DialogCorner: Dp = 10.dp

    /** Height of a dialog's own title bar. */
    public val DialogTitleHeight: Dp = 38.dp

    /** Width of the category list in the Settings and Run/Debug dialogs. */
    public val DialogListWidth: Dp = 232.dp

    /** Corner radius of the editor and preview panes, and of a preview code block. */
    public val PaneCorner: Dp = 8.dp

    /** Height of the bottom dock when it is open. */
    public val BottomDockHeight: Dp = 190.dp

    public val WelcomeRailWidth: Dp = 250.dp

    /** One entry in the welcome window's recent-projects list. */
    public val WelcomeRecentRowHeight: Dp = 56.dp

    /** The large square actions the welcome window shows when there are no recent projects. */
    public val WelcomeActionSize: Dp = 108.dp

    /** Body text in trees, tabs and toolbars. */
    public val UiFontSize: TextUnit = 13.sp

    /** Secondary text: status bar items, shortcut hints, headers. */
    public val SmallFontSize: TextUnit = 12.sp

    /** The smallest label the IDE uses, for stripe tooltips and badges. */
    public val TinyFontSize: TextUnit = 11.sp
}
