package dev.starfect.quill.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.starfect.quill.ui.icons.IdeIcons
import dev.starfect.quill.ui.theme.LocalShellPalette
import dev.starfect.quill.ui.theme.LocalTypeScale
import dev.starfect.quill.ui.theme.Tokens
import dev.starfect.quill.ui.theme.interactiveSurface
import org.jetbrains.jewel.ui.component.Text

/**
 * Whether the tool window a row belongs to currently holds keyboard focus.
 *
 * The IDE draws a selected row two ways: solidly while its panel is focused, and in a weaker grey
 * once focus moves to the editor. That is the whole of §25's "focus" for a tree — no ring, no
 * outline, just the selection stepping back so the window says where typing will go.
 *
 * A composition local rather than a parameter because the answer is a property of the panel, and
 * threading it through every row and every row's caller is how it ends up wrong in one of them.
 */
public val LocalToolWindowFocused: ProvidableCompositionLocal<Boolean> = compositionLocalOf { true }

/**
 * Marks its content as one focus region and publishes whether focus is inside it.
 *
 * `hasFocus` is true when the focused node is this one *or any descendant*, which is exactly the
 * question a tool window needs answered: rows are individually focusable, and the panel should not
 * dim its selection just because focus moved from one of its own rows to another.
 */
@Composable
public fun ToolWindowFocusScope(
    modifier: Modifier = Modifier,
    /** Moves the selection by one row. Wired to the arrow keys. */
    onMove: (Int) -> Unit = {},
    /** Activates the selected row. Wired to Enter and Space. */
    onActivate: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }

    Box(
        modifier
            .onFocusChanged { focused = it.hasFocus }
            .focusRequester(requester)
            // Focusable itself, not only through its rows: a panel the user has tabbed to has to be
            // able to receive an arrow key before anything inside it has been clicked.
            .focusable()
            // The platform's keyboard contract for a list: arrows move, Space and Enter activate,
            // and Escape hands focus back. Without it a tool window is reachable only by pointer,
            // which fails the accessibility guideline outright.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> { onMove(1); true }
                    Key.DirectionUp -> { onMove(-1); true }
                    Key.Enter, Key.Spacebar -> { onActivate(); true }
                    else -> false
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { requester.requestFocus() }
    ) {
        CompositionLocalProvider(LocalToolWindowFocused provides focused, content = content)
    }
}

/**
 * One row of a tree, wherever that tree appears.
 *
 * The Project view and the Structure view are the same control showing different content, and they
 * have to look it. When each owns its own row the two drift apart one plausible decision at a time —
 * six pixels of padding here, a slightly different hover there — until the panel on the left and the
 * panel on the right read as parts of two different products.
 *
 * Everything that defines the row lives here: height, indentation, indent guides, disclosure column,
 * hover and selection, and the label's typography. Callers supply an icon and a label and nothing
 * else.
 */
@Composable
public fun TreeRow(
    depth: Int,
    /**
     * What clicking the row does, or null when the row only reports.
     *
     * Null is not "nothing wired yet": it removes the hover fill and the click target entirely.
     * The project root is the case — it is always open, there is nothing to expand, and a row that
     * lights up under the pointer and then does nothing is the same broken promise the toolbar
     * widgets were making.
     */
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    expandable: Boolean = false,
    expanded: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val shell = LocalShellPalette.current
    val interaction = remember { MutableInteractionSource() }

    Row(
        modifier = modifier.fillMaxWidth()
            .height(Tokens.TreeRowHeight)
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier.interactiveSurface(
                        onClick = onClick,
                        palette = shell,
                        selected = selected,
                        focused = LocalToolWindowFocused.current,
                        cornerRadius = Tokens.Radius.Row,
                        interactionSource = interaction,
                    )
                }
            )
            .indentGuides(depth, shell.border)
            .padding(
                start = TreeEdgeInset + (Tokens.TreeIndentStep * depth),
                end = Tokens.Spacing.Small,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.Tiny),
    ) {
        TreeDisclosure(expandable = expandable, expanded = expanded)
        content()
    }
}

/**
 * The disclosure column.
 *
 * Drawn even when there is nothing to disclose, so a file row's icon lands at the same x as the icon
 * of the folder above it. Omitting it for leaves shifts every one of them left by the width of a
 * missing chevron, and a tree whose icons do not form a column reads as ragged however carefully
 * everything else is aligned.
 */
@Composable
public fun TreeDisclosure(expandable: Boolean, expanded: Boolean) {
    val shell = LocalShellPalette.current

    Box(Modifier.size(DisclosureColumnWidth), contentAlignment = Alignment.Center) {
        when {
            expandable && expanded -> IdeIcons.ChevronDown(shell.mutedIcon, size = Tokens.SmallIconSize)
            expandable -> IdeIcons.ChevronRight(shell.mutedIcon, size = Tokens.SmallIconSize)
            else -> Spacer(Modifier.size(Tokens.SmallIconSize))
        }
    }
}

/** A tree row's label, in the shell's primary UI type. */
@Composable
public fun TreeLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalShellPalette.current.text,
    weight: FontWeight? = null,
) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = LocalTypeScale.current.default,
        fontWeight = weight,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Secondary text on a tree row: a path, a count, a heading level. */
@Composable
public fun TreeMetadata(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = LocalTypeScale.current.medium,
        color = LocalShellPalette.current.mutedText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Inset from the panel edge to the disclosure column. */
private val TreeEdgeInset = Tokens.Spacing.Small

/** Width of the disclosure column, which is what fixes where every icon in the tree begins. */
private val DisclosureColumnWidth = Tokens.IconSize

/**
 * The vertical guides connecting a nested row back to its ancestors.
 *
 * One line per level, at that level's indent. They let the eye follow a deep tree without counting
 * indentation — but only if they are nearly invisible. Drawn at any real contrast they become the
 * loudest thing in the panel, which is the opposite of their job.
 */
private fun Modifier.indentGuides(depth: Int, color: Color): Modifier = drawBehind {
    if (depth <= 0) return@drawBehind

    val step = Tokens.TreeIndentStep.toPx()
    val origin = TreeEdgeInset.toPx() + (DisclosureColumnWidth.toPx() / 2f)
    for (level in 0 until depth) {
        val x = origin + (level * step)
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
    }
}
