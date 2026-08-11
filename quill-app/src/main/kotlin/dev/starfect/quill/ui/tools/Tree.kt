package dev.starfect.quill.ui.tools

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.starfect.quill.ui.icons.IdeIcons
import dev.starfect.quill.ui.theme.LocalShellPalette
import dev.starfect.quill.ui.theme.Tokens
import dev.starfect.quill.ui.theme.interactiveSurface
import org.jetbrains.jewel.ui.component.Text

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
    onClick: () -> Unit,
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
            .interactiveSurface(
                onClick = onClick,
                palette = shell,
                selected = selected,
                cornerRadius = Tokens.Radius.Row,
                interactionSource = interaction,
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
        fontSize = Tokens.FontSize,
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
        fontSize = Tokens.TinyFontSize,
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
