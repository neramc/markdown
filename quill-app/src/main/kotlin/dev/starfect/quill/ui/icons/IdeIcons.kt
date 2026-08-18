package dev.starfect.quill.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.starfect.quill.ui.theme.Tokens

/**
 * The icon set, drawn as vectors rather than loaded as resources.
 *
 * IntelliJ's own `expui` icons are not published to Maven Central — Jewel ships without most of
 * them — so an icon-by-resource approach would mean either bundling artwork Quill has no licence to
 * or shipping a window full of missing-icon boxes. Drawing them keeps the application self-contained
 * and lets every icon take the theme's tint, which is what the IDE does with its own SVGs.
 *
 * Every path below is expressed on the same 16x16 grid the IDE designs its icons on, then scaled, so
 * stroke weights stay consistent between a 16dp toolbar icon and a 20dp stripe icon.
 */
public object IdeIcons {

    /** Nominal stroke width on the 16-unit design grid. */
    private const val STROKE = 1.25f

    // ---------------------------------------------------------------- toolbar

    /** The New UI main menu button. */
    @Composable
    public fun Hamburger(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.IconSize) {
        IdeIcon(size, modifier) { unit ->
            for (y in intArrayOf(4, 8, 12)) {
                line(3f, y.toFloat(), 13f, y.toFloat(), tint, unit)
            }
        }
    }

    /** Search Everywhere. */
    @Composable
    public fun Search(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.IconSize) {
        IdeIcon(size, modifier) { unit ->
            circle(7f, 7f, 4.2f, tint, unit)
            line(10.2f, 10.2f, 13.5f, 13.5f, tint, unit)
        }
    }

    /** Settings. */
    @Composable
    public fun Gear(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.IconSize) {
        IdeIcon(size, modifier) { unit ->
            circle(8f, 8f, 2.4f, tint, unit)
            // Eight teeth, which is what reads as a gear at 16px without becoming mush.
            repeat(8) { index ->
                val angle = Math.toRadians(index * 45.0)
                val cos = Math.cos(angle).toFloat()
                val sin = Math.sin(angle).toFloat()
                line(8f + cos * 4.4f, 8f + sin * 4.4f, 8f + cos * 6.2f, 8f + sin * 6.2f, tint, unit)
            }
        }
    }

    /** Close, used on tabs and in the find bar. */
    @Composable
    public fun Close(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.IconSize) {
        IdeIcon(size, modifier) { unit ->
            line(4.5f, 4.5f, 11.5f, 11.5f, tint, unit)
            line(11.5f, 4.5f, 4.5f, 11.5f, tint, unit)
        }
    }

    /**
     * Collapse a tool window back to its stripe.
     *
     * The IDE distinguishes this from Close: a tool window is hidden, not destroyed, and it uses an
     * arrow pointing at the edge it will fold into rather than the X that would suggest otherwise.
     */
    @Composable
    public fun Hide(
        tint: Color,
        towardsLeft: Boolean,
        modifier: Modifier = Modifier,
        size: Dp = Tokens.IconSize,
    ) {
        IdeIcon(size, modifier) { unit ->
            if (towardsLeft) {
                line(3.5f, 3.5f, 3.5f, 12.5f, tint, unit)
                polyline(listOf(11f to 4.5f, 6.5f to 8f, 11f to 11.5f), tint, unit)
            } else {
                line(12.5f, 3.5f, 12.5f, 12.5f, tint, unit)
                polyline(listOf(5f to 4.5f, 9.5f to 8f, 5f to 11.5f), tint, unit)
            }
        }
    }

    /** A generic action, for rows in Search Everywhere that have no icon of their own. */
    @Composable
    public fun Action(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.IconSize) {
        IdeIcon(size, modifier) { unit ->
            polyline(listOf(9f to 2f, 4.5f to 8.8f, 7.6f to 8.8f, 7f to 14f, 11.5f to 7.2f, 8.4f to 7.2f), tint, unit)
        }
    }

    /** Editing actions. */
    @Composable
    public fun Pencil(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.IconSize) {
        IdeIcon(size, modifier) { unit ->
            polyline(listOf(3f to 13f, 3.8f to 10.2f, 10.6f to 3.4f, 12.6f to 5.4f, 5.8f to 12.2f, 3f to 13f), tint, unit)
            line(9.2f, 4.8f, 11.2f, 6.8f, tint, unit)
        }
    }

    /** A small down chevron, for widgets that open a menu. */
    @Composable
    public fun WidgetChevron(tint: Color, modifier: Modifier = Modifier, size: Dp = 10.dp) {
        IdeIcon(size, modifier) { unit ->
            polyline(listOf(4f to 6.5f, 8f to 10f, 12f to 6.5f), tint, unit)
        }
    }

    /** The VCS widget's branch glyph: two nodes joined by a fork. */
    @Composable
    public fun Branch(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.IconSize) {
        IdeIcon(size, modifier) { unit ->
            circle(4.5f, 3.6f, 1.7f, tint, unit)
            circle(4.5f, 12.4f, 1.7f, tint, unit)
            circle(11.5f, 5.6f, 1.7f, tint, unit)
            line(4.5f, 5.3f, 4.5f, 10.7f, tint, unit)
            // The fork: the branch tip curves back onto the trunk, which is what makes this read as
            // a branch rather than as three unrelated dots.
            polyline(listOf(11.5f to 7.3f, 11.5f to 9f, 4.5f to 9f), tint, unit)
        }
    }

    /** The overflow menu the IDE puts at the end of a toolbar or tab strip. */
    @Composable
    public fun MoreVertical(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.IconSize) {
        IdeIcon(size, modifier) { unit ->
            for (y in intArrayOf(4, 8, 12)) {
                drawCircle(tint, 0.85f * unit, Offset(8f * unit, y * unit))
            }
        }
    }

    /** The read-only padlock the IDE keeps at the right end of the status bar. */
    @Composable
    public fun Lock(tint: Color, locked: Boolean, modifier: Modifier = Modifier, size: Dp = 14.dp) {
        IdeIcon(size, modifier) { unit ->
            rect(4f, 7.5f, 12f, 13.5f, tint, unit)
            if (locked) {
                polyline(listOf(6f to 7.5f, 6f to 5f, 10f to 5f, 10f to 7.5f), tint, unit)
            } else {
                // Unlocked: the shackle swings clear of the body on one side.
                polyline(listOf(6f to 7.5f, 6f to 5f, 10f to 5f, 10f to 6.2f), tint, unit)
            }
        }
    }

    // ---------------------------------------------------------------- trees

    /** Collapsed disclosure triangle. */
    @Composable
    public fun ChevronRight(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.IconSize) {
        IdeIcon(size, modifier) { unit ->
            polyline(listOf(6.5f to 4.5f, 10f to 8f, 6.5f to 11.5f), tint, unit)
        }
    }

    /**
     * Back, as an arrow with a shaft.
     *
     * A bare chevron would be a smaller icon to draw, and it is what a *disclosure* control uses.
     * Navigation arrows carry a shaft in every IDE toolbar because that is the difference between
     * "expand this" and "go there", and the two sit within a few pixels of each other here.
     */
    @Composable
    public fun NavigateBack(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.IconSize) {
        IdeIcon(size, modifier) { unit ->
            line(3.5f, 8f, 12.5f, 8f, tint, unit)
            polyline(listOf(7f to 3.5f, 3f to 8f, 7f to 12.5f), tint, unit)
        }
    }

    /** Forward. The mirror of [NavigateBack]. */
    @Composable
    public fun NavigateForward(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.IconSize) {
        IdeIcon(size, modifier) { unit ->
            line(3.5f, 8f, 12.5f, 8f, tint, unit)
            polyline(listOf(9f to 3.5f, 13f to 8f, 9f to 12.5f), tint, unit)
        }
    }

    /** Expanded disclosure triangle. */
    @Composable
    public fun ChevronDown(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.IconSize) {
        IdeIcon(size, modifier) { unit ->
            polyline(listOf(4.5f to 6.5f, 8f to 10f, 11.5f to 6.5f), tint, unit)
        }
    }

    /**
     * A directory in the project view.
     *
     * IntelliJ fills its folder icons rather than outlining them, and tints the fill by role —
     * plain grey-blue for an ordinary directory, blue for a source root, orange for something
     * excluded from the project. The tint carries real information in the IDE, so it is worth
     * keeping rather than drawing every folder identically.
     */
    @Composable
    public fun Folder(
        tint: Color,
        modifier: Modifier = Modifier,
        size: Dp = Tokens.IconSize,
        open: Boolean = false,
    ) {
        IdeIcon(size, modifier) { unit ->
            val body = Path().apply {
                moveTo(1.8f * unit, 12.8f * unit)
                lineTo(1.8f * unit, 3.6f * unit)
                lineTo(6.4f * unit, 3.6f * unit)
                lineTo(7.9f * unit, 5.4f * unit)
                lineTo(14.2f * unit, 5.4f * unit)
                lineTo(14.2f * unit, 12.8f * unit)
                close()
            }
            drawPath(body, tint.copy(alpha = 0.9f))

            if (open) {
                // The open folder's front flap is skewed, which is how the IDE shows an expanded
                // directory without changing the icon's footprint.
                val flap = Path().apply {
                    moveTo(1.8f * unit, 12.8f * unit)
                    lineTo(4.2f * unit, 7.6f * unit)
                    lineTo(15.2f * unit, 7.6f * unit)
                    lineTo(12.8f * unit, 12.8f * unit)
                    close()
                }
                drawPath(flap, tint)
            }
        }
    }

    /** A package: a folder with the dot the IDE marks a package root with. */
    @Composable
    public fun PackageFolder(
        tint: Color,
        accent: Color,
        modifier: Modifier = Modifier,
        size: Dp = Tokens.IconSize,
    ) {
        IdeIcon(size, modifier) { unit ->
            val body = Path().apply {
                moveTo(1.8f * unit, 12.8f * unit)
                lineTo(1.8f * unit, 3.6f * unit)
                lineTo(6.4f * unit, 3.6f * unit)
                lineTo(7.9f * unit, 5.4f * unit)
                lineTo(14.2f * unit, 5.4f * unit)
                lineTo(14.2f * unit, 12.8f * unit)
                close()
            }
            drawPath(body, tint.copy(alpha = 0.9f))
            drawCircle(accent, 1.9f * unit, Offset(11.4f * unit, 10.4f * unit))
        }
    }

    /** The module root: the square the IDE puts at the top of the project tree. */
    @Composable
    public fun Module(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.IconSize) {
        IdeIcon(size, modifier) { unit ->
            rect(2.5f, 2.5f, 13.5f, 13.5f, tint, unit)
            line(2.5f, 6.2f, 13.5f, 6.2f, tint, unit)
        }
    }

    /** A file with no type of its own. */
    @Composable
    public fun PlainFile(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.IconSize) {
        IdeIcon(size, modifier) { unit ->
            val page = Path().apply {
                moveTo(3.5f * unit, 1.8f * unit)
                lineTo(9.4f * unit, 1.8f * unit)
                lineTo(12.5f * unit, 4.9f * unit)
                lineTo(12.5f * unit, 14.2f * unit)
                lineTo(3.5f * unit, 14.2f * unit)
                close()
            }
            drawPath(page, tint, style = stroke(unit))
            polyline(listOf(9.4f to 1.8f, 9.4f to 4.9f, 12.5f to 4.9f), tint, unit)
        }
    }

    // ---------------------------------------------------------------- welcome window

    /** New. */
    @Composable
    public fun Plus(tint: Color, modifier: Modifier = Modifier, size: Dp = 28.dp) {
        IdeIcon(size, modifier) { unit ->
            line(8f, 3f, 8f, 13f, tint, unit)
            line(3f, 8f, 13f, 8f, tint, unit)
        }
    }

    /** Open an existing folder. */
    @Composable
    public fun OpenFolder(tint: Color, modifier: Modifier = Modifier, size: Dp = 28.dp) {
        IdeIcon(size, modifier) { unit ->
            polyline(
                listOf(2f to 12.8f, 2f to 3.8f, 6.4f to 3.8f, 7.9f to 5.6f, 13.4f to 5.6f, 13.4f to 7.4f),
                tint,
                unit,
            )
            polyline(listOf(2f to 12.8f, 4.4f to 7.4f, 15.2f to 7.4f, 12.8f to 12.8f, 2f to 12.8f), tint, unit)
        }
    }

    /** A Markdown document: a page with a folded corner and the "M" bar the IDE tints. */
    @Composable
    public fun MarkdownFile(
        tint: Color,
        accent: Color,
        modifier: Modifier = Modifier,
        size: Dp = Tokens.IconSize,
    ) {
        IdeIcon(size, modifier) { unit ->
            val page = Path().apply {
                moveTo(3.5f * unit, 1.8f * unit)
                lineTo(9.4f * unit, 1.8f * unit)
                lineTo(12.5f * unit, 4.9f * unit)
                lineTo(12.5f * unit, 14.2f * unit)
                lineTo(3.5f * unit, 14.2f * unit)
                close()
            }
            drawPath(page, tint, style = stroke(unit))
            polyline(listOf(9.4f to 1.8f, 9.4f to 4.9f, 12.5f to 4.9f), tint, unit)

            // The angle-bracket mark Markdown files carry in the IDE's file-type icon.
            polyline(listOf(6.2f to 8.6f, 6.2f to 11.6f, 8f to 9.8f, 9.8f to 11.6f, 9.8f to 8.6f), accent, unit)
        }
    }

    // ---------------------------------------------------------------- stripes

    /** The Project tool window's stripe icon. */
    @Composable
    public fun ProjectStripe(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.ToolWindowIconSize) {
        IdeIcon(size, modifier) { unit ->
            rect(2f, 3f, 14f, 13.5f, tint, unit)
            line(6.5f, 3f, 6.5f, 13.5f, tint, unit)
        }
    }

    /** The Structure tool window's stripe icon: nested, staggered rows. */
    @Composable
    public fun StructureStripe(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.ToolWindowIconSize) {
        IdeIcon(size, modifier) { unit ->
            line(3f, 4f, 13f, 4f, tint, unit)
            line(6f, 8f, 13f, 8f, tint, unit)
            line(9f, 12f, 13f, 12f, tint, unit)
            polyline(listOf(3f to 4f, 3f to 12f, 4.5f to 12f), tint, unit)
            line(6f, 8f, 6f, 8f, tint, unit)
        }
    }

    /** The Problems tool window's stripe icon: a triangle with a bang. */
    @Composable
    public fun ProblemsStripe(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.ToolWindowIconSize) {
        IdeIcon(size, modifier) { unit ->
            polyline(listOf(8f to 2.5f, 14f to 13f, 2f to 13f, 8f to 2.5f), tint, unit)
            line(8f, 6.5f, 8f, 9.5f, tint, unit)
            drawCircle(tint, 0.75f * unit, Offset(8f * unit, 11.3f * unit))
        }
    }

    /** The Notifications tool window's stripe icon: a bell. */
    @Composable
    public fun NotificationsStripe(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.ToolWindowIconSize) {
        IdeIcon(size, modifier) { unit ->
            polyline(
                listOf(
                    3.5f to 11f,
                    4.6f to 9.6f,
                    4.6f to 7f,
                    8f to 3.2f,
                    11.4f to 7f,
                    11.4f to 9.6f,
                    12.5f to 11f,
                    3.5f to 11f,
                ),
                tint,
                unit,
            )
            polyline(listOf(6.6f to 12.4f, 8f to 13.6f, 9.4f to 12.4f), tint, unit)
        }
    }

    /** The Database tool window's stripe icon: the stacked-cylinder glyph. */
    @Composable
    public fun DatabaseStripe(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.ToolWindowIconSize) {
        IdeIcon(size, modifier) { unit ->
            // Three ellipses stacked, with the sides joining the top and bottom ones.
            ellipse(8f, 4f, 5f, 2f, tint, unit)
            polyline(listOf(3f to 4f, 3f to 12f), tint, unit)
            polyline(listOf(13f to 4f, 13f to 12f), tint, unit)
            arcDown(8f, 8f, 5f, 2f, tint, unit)
            arcDown(8f, 12f, 5f, 2f, tint, unit)
        }
    }

    /** The Terminal tool window's stripe icon: a prompt caret. */
    @Composable
    public fun TerminalStripe(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.ToolWindowIconSize) {
        IdeIcon(size, modifier) { unit ->
            rect(2f, 3f, 14f, 13f, tint, unit)
            polyline(listOf(4.5f to 6.5f, 7f to 8.5f, 4.5f to 10.5f), tint, unit)
            line(8.5f, 10.5f, 11.5f, 10.5f, tint, unit)
        }
    }

    // ---------------------------------------------------------------- severity

    /** A filled severity dot, used in the inspection widget and the problems list. */
    @Composable
    public fun SeverityError(tint: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
        IdeIcon(size, modifier) { unit ->
            drawCircle(tint, 6f * unit, Offset(8f * unit, 8f * unit))
            line(8f, 4.6f, 8f, 8.6f, Color.White, unit)
            drawCircle(Color.White, 0.85f * unit, Offset(8f * unit, 11f * unit))
        }
    }

    /** The warning triangle, which is the glyph the editor's inspection widget shows. */
    @Composable
    public fun SeverityWarning(tint: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
        IdeIcon(size, modifier) { unit ->
            val path = Path().apply {
                moveTo(8f * unit, 1.8f * unit)
                lineTo(15f * unit, 14f * unit)
                lineTo(1f * unit, 14f * unit)
                close()
            }
            drawPath(path, tint)
            line(8f, 6f, 8f, 9.8f, Color.Black.copy(alpha = 0.75f), unit)
            drawCircle(Color.Black.copy(alpha = 0.75f), 0.8f * unit, Offset(8f * unit, 11.9f * unit))
        }
    }

    /** The weak-warning glyph: the same triangle, outlined rather than filled. */
    @Composable
    public fun SeverityWeak(tint: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
        IdeIcon(size, modifier) { unit ->
            polyline(listOf(8f to 2.5f, 14f to 13f, 2f to 13f, 8f to 2.5f), tint, unit)
        }
    }

    /** The all-clear mark the widget shows on a document with no findings. */
    @Composable
    public fun SeverityClean(tint: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
        IdeIcon(size, modifier) { unit ->
            polyline(listOf(3.5f to 8.5f, 6.5f to 11.5f, 12.5f to 4.5f), tint, unit)
        }
    }

    /** Duplicate: two offset sheets. */
    @Composable
    public fun Copy(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.IconSize) {
        IdeIcon(size, modifier) { unit ->
            rect(5.5f, 2.5f, 13.5f, 10.5f, tint, unit)
            polyline(listOf(10.5f to 13.5f, 2.5f to 13.5f, 2.5f to 5.5f), tint, unit)
        }
    }

    // ---------------------------------------------------------------- run and debug

    /** The green run triangle. */
    @Composable
    public fun Run(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.IconSize) {
        IdeIcon(size, modifier) { unit ->
            val path = Path().apply {
                moveTo(4.5f * unit, 3f * unit)
                lineTo(13f * unit, 8f * unit)
                lineTo(4.5f * unit, 13f * unit)
                close()
            }
            drawPath(path, tint)
        }
    }

    /** The debug bug. */
    @Composable
    public fun Debug(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.IconSize) {
        IdeIcon(size, modifier) { unit ->
            circle(8f, 8.5f, 3.6f, tint, unit)
            line(3f, 6f, 4.6f, 6.8f, tint, unit)
            line(3f, 11f, 4.6f, 10.2f, tint, unit)
            line(13f, 6f, 11.4f, 6.8f, tint, unit)
            line(13f, 11f, 11.4f, 10.2f, tint, unit)
            line(8f, 3f, 8f, 4.9f, tint, unit)
        }
    }

    // ---------------------------------------------------------------- editor toolbar

    /** Show the source only: a pane filled on the left. */
    @Composable
    public fun ViewEditorOnly(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.IconSize) {
        SplitGlyph(tint, modifier, size, left = true, right = false)
    }

    /** Show source and preview side by side. */
    @Composable
    public fun ViewSplit(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.IconSize) {
        SplitGlyph(tint, modifier, size, left = true, right = true)
    }

    /** Show the rendered preview only. */
    @Composable
    public fun ViewPreviewOnly(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.IconSize) {
        SplitGlyph(tint, modifier, size, left = false, right = true)
    }

    @Composable
    private fun SplitGlyph(tint: Color, modifier: Modifier, size: Dp, left: Boolean, right: Boolean) {
        IdeIcon(size, modifier) { unit ->
            rect(2f, 3f, 14f, 13f, tint, unit)
            line(8f, 3f, 8f, 13f, tint, unit)
            if (left) {
                drawRect(tint.copy(alpha = 0.55f), Offset(2.6f * unit, 3.6f * unit), Size(4.8f * unit, 8.8f * unit))
            }
            if (right) {
                drawRect(tint.copy(alpha = 0.55f), Offset(8.6f * unit, 3.6f * unit), Size(4.8f * unit, 8.8f * unit))
            }
        }
    }

    // ---------------------------------------------------------------- find bar

    /** Step to the previous match. */
    @Composable
    public fun ArrowUp(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.IconSize) {
        IdeIcon(size, modifier) { unit ->
            polyline(listOf(4.5f to 9.5f, 8f to 6f, 11.5f to 9.5f), tint, unit)
        }
    }

    /** Step to the next match. */
    @Composable
    public fun ArrowDown(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.IconSize) {
        IdeIcon(size, modifier) { unit ->
            polyline(listOf(4.5f to 6.5f, 8f to 10f, 11.5f to 6.5f), tint, unit)
        }
    }

    /** Expand the find bar into find-and-replace, as the IDE's leading chevron does. */
    @Composable
    public fun ExpandChevron(
        tint: Color,
        expanded: Boolean,
        modifier: Modifier = Modifier,
        size: Dp = Tokens.IconSize,
    ) {
        if (expanded) ChevronDown(tint, modifier, size) else ChevronRight(tint, modifier, size)
    }

    // ---------------------------------------------------------------- primitives

    /**
     * Draws one icon on the 16-unit grid.
     *
     * The lambda receives the pixels-per-grid-unit factor, so paths are written in design units and
     * scale exactly with the requested size.
     */
    // ---------------------------------------------------------------- writing actions

    // Drawn as marks rather than as letters. A "B" and an "I" are what a word processor uses, and
    // they only work in a Latin alphabet; the shapes below say bold and italic to a reader of any
    // script, and hold together at 16px where a glyph would not.

    /** Bold: a stroke of doubled weight. */
    @Composable
    public fun Bold(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.IconSize) {
        IdeIcon(size, modifier) { unit ->
            drawLine(
                color = tint,
                start = Offset(4.5f * unit, 4f * unit),
                end = Offset(4.5f * unit, 12f * unit),
                strokeWidth = STROKE * 2.2f * unit,
                cap = StrokeCap.Round,
            )
            line(4.5f, 4f, 9.5f, 4f, tint, unit)
            line(4.5f, 8f, 9.5f, 8f, tint, unit)
            line(4.5f, 12f, 9.5f, 12f, tint, unit)
            line(9.5f, 4f, 11.5f, 6f, tint, unit)
            line(11.5f, 6f, 9.5f, 8f, tint, unit)
            line(9.5f, 8f, 11.8f, 10f, tint, unit)
            line(11.8f, 10f, 9.5f, 12f, tint, unit)
        }
    }

    /** Italic: a slanted stroke between two serifs. */
    @Composable
    public fun Italic(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.IconSize) {
        IdeIcon(size, modifier) { unit ->
            line(6f, 4f, 11.5f, 4f, tint, unit)
            line(4.5f, 12f, 10f, 12f, tint, unit)
            line(9f, 4f, 7f, 12f, tint, unit)
        }
    }

    /** Inline code: the angle brackets, tighter than the fenced-block glyph. */
    @Composable
    public fun InlineCode(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.IconSize) {
        IdeIcon(size, modifier) { unit ->
            line(6f, 5f, 3f, 8f, tint, unit)
            line(3f, 8f, 6f, 11f, tint, unit)
            line(10f, 5f, 13f, 8f, tint, unit)
            line(13f, 8f, 10f, 11f, tint, unit)
        }
    }

    /** Link: two chain segments meeting on the diagonal. */
    @Composable
    public fun Link(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.IconSize) {
        IdeIcon(size, modifier) { unit ->
            val path = Path().apply {
                moveTo(7f * unit, 9.5f * unit)
                lineTo(5f * unit, 11.5f * unit)
                moveTo(9f * unit, 6.5f * unit)
                lineTo(11f * unit, 4.5f * unit)
            }
            drawPath(path, tint, style = stroke(unit))
            line(6.2f, 6.2f, 4.2f, 8.2f, tint, unit)
            line(4.2f, 8.2f, 5.6f, 9.6f, tint, unit)
            line(9.8f, 9.8f, 11.8f, 7.8f, tint, unit)
            line(11.8f, 7.8f, 10.4f, 6.4f, tint, unit)
            line(6.5f, 9.5f, 9.5f, 6.5f, tint, unit)
        }
    }

    /** A bulleted list: three dots and three rules. */
    @Composable
    public fun BulletList(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.IconSize) {
        IdeIcon(size, modifier) { unit ->
            for (y in intArrayOf(4, 8, 12)) {
                drawCircle(tint, 0.9f * unit, Offset(3.5f * unit, y * unit))
                line(6.5f, y.toFloat(), 13f, y.toFloat(), tint, unit)
            }
        }
    }

    /** A task list: a ticked box over two rules. */
    @Composable
    public fun TaskList(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.IconSize) {
        IdeIcon(size, modifier) { unit ->
            rect(2.5f, 3f, 6.5f, 7f, tint, unit)
            line(3.4f, 5.1f, 4.4f, 6.1f, tint, unit)
            line(4.4f, 6.1f, 6f, 3.8f, tint, unit)
            line(8.5f, 5f, 13.5f, 5f, tint, unit)
            line(2.5f, 10.5f, 13.5f, 10.5f, tint, unit)
            line(2.5f, 13.5f, 10f, 13.5f, tint, unit)
        }
    }

    /** A table: a grid with a heavier header row. */
    @Composable
    public fun Table(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.IconSize) {
        IdeIcon(size, modifier) { unit ->
            rect(2.5f, 3.5f, 13.5f, 12.5f, tint, unit)
            line(2.5f, 6.5f, 13.5f, 6.5f, tint, unit)
            line(2.5f, 9.5f, 13.5f, 9.5f, tint, unit)
            line(8f, 3.5f, 8f, 12.5f, tint, unit)
        }
    }

    /** A table of contents: indented rules, the shape of an outline. */
    @Composable
    public fun Contents(tint: Color, modifier: Modifier = Modifier, size: Dp = Tokens.IconSize) {
        IdeIcon(size, modifier) { unit ->
            line(2.5f, 3.5f, 13.5f, 3.5f, tint, unit)
            line(5f, 6.5f, 13.5f, 6.5f, tint, unit)
            line(5f, 9.5f, 13.5f, 9.5f, tint, unit)
            line(7.5f, 12.5f, 13.5f, 12.5f, tint, unit)
        }
    }

    @Composable
    private fun IdeIcon(size: Dp, modifier: Modifier, draw: DrawScope.(Float) -> Unit) {
        Canvas(modifier.size(size)) { draw(this.size.minDimension / 16f) }
    }

    private fun DrawScope.stroke(unit: Float) = Stroke(
        width = STROKE * unit,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
        pathEffect = PathEffect.cornerPathEffect(0.5f * unit),
    )

    private fun DrawScope.line(x1: Float, y1: Float, x2: Float, y2: Float, tint: Color, unit: Float) {
        drawLine(
            color = tint,
            start = Offset(x1 * unit, y1 * unit),
            end = Offset(x2 * unit, y2 * unit),
            strokeWidth = STROKE * unit,
            cap = StrokeCap.Round,
        )
    }

    private fun DrawScope.polyline(points: List<Pair<Float, Float>>, tint: Color, unit: Float) {
        val path = Path()
        points.forEachIndexed { index, (x, y) ->
            if (index == 0) path.moveTo(x * unit, y * unit) else path.lineTo(x * unit, y * unit)
        }
        drawPath(path, tint, style = stroke(unit))
    }

    private fun DrawScope.rect(left: Float, top: Float, right: Float, bottom: Float, tint: Color, unit: Float) {
        val path = Path().apply {
            addRect(Rect(left * unit, top * unit, right * unit, bottom * unit))
        }
        drawPath(path, tint, style = stroke(unit))
    }

    private fun DrawScope.circle(x: Float, y: Float, radius: Float, tint: Color, unit: Float) {
        drawCircle(tint, radius * unit, Offset(x * unit, y * unit), style = stroke(unit))
    }

    /** A full ellipse, for the database cylinder's top face. */
    private fun DrawScope.ellipse(
        x: Float,
        y: Float,
        radiusX: Float,
        radiusY: Float,
        tint: Color,
        unit: Float,
    ) {
        val path = Path().apply {
            addOval(
                Rect(
                    (x - radiusX) * unit,
                    (y - radiusY) * unit,
                    (x + radiusX) * unit,
                    (y + radiusY) * unit,
                )
            )
        }
        drawPath(path, tint, style = stroke(unit))
    }

    /**
     * The lower half of an ellipse.
     *
     * The cylinder's middle and bottom faces show only their front edge — drawing the full ellipse
     * would put a line through the body where the hidden back edge is.
     */
    private fun DrawScope.arcDown(
        x: Float,
        y: Float,
        radiusX: Float,
        radiusY: Float,
        tint: Color,
        unit: Float,
    ) {
        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset((x - radiusX) * unit, (y - radiusY) * unit),
            size = Size(2 * radiusX * unit, 2 * radiusY * unit),
            style = stroke(unit),
        )
    }
}
