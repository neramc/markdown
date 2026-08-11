package dev.starfect.quill.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.starfect.quill.ui.theme.IdeaMetrics
import dev.starfect.quill.ui.theme.LocalShellPalette
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

/**
 * The IDE's square action button: an icon on a rounded fill that appears on hover.
 *
 * This is the single most repeated control in the New UI — the title bar, the tool window headers,
 * the editor toolbar and the find bar are all rows of it — so it is worth having exactly one
 * implementation with exactly one set of hover and toggle colours.
 */
@Composable
public fun IdeActionButton(
    onClick: () -> Unit,
    tooltip: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: Dp = IdeaMetrics.ActionButtonSize,
    content: @Composable (tint: Color) -> Unit,
) {
    val shell = LocalShellPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()

    val background = when {
        !enabled -> Color.Transparent
        pressed || selected -> shell.pressedBackground
        hovered -> shell.hoverBackground
        else -> Color.Transparent
    }
    // A disabled action stays visible but recedes, rather than disappearing and shifting the row.
    val tint = if (enabled) shell.icon else shell.icon.copy(alpha = 0.4f)

    Tooltip(tooltip = { Text(tooltip) }) {
        Box(
            modifier = modifier.size(size)
                .clip(RoundedCornerShape(IdeaMetrics.ActionButtonCorner))
                .background(background)
                .hoverable(interaction, enabled = enabled)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            content(tint)
        }
    }
}

/**
 * A text button drawn with the same hover treatment, for the widgets in the title bar.
 *
 * The IDE's project and branch widgets are pill-shaped labels that light up on hover; they are not
 * combo boxes, and drawing them as combo boxes is the single change that most makes a window stop
 * looking like IntelliJ.
 */
@Composable
public fun IdeWidgetButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable () -> Unit,
) {
    val shell = LocalShellPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()

    val background = when {
        pressed || selected -> shell.pressedBackground
        hovered -> shell.hoverBackground
        else -> Color.Transparent
    }

    Row(
        modifier = modifier.height(IdeaMetrics.ActionButtonSize)
            .clip(RoundedCornerShape(IdeaMetrics.ActionButtonCorner))
            .background(background)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/**
 * A toggle chip, as used for the find bar's Match case / Words / Regex switches.
 *
 * The IDE renders these as short glyphs rather than checkboxes, which is what keeps its find bar one
 * row tall instead of two.
 */
@Composable
public fun IdeToggleChip(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tooltip: String,
    modifier: Modifier = Modifier,
) {
    val shell = LocalShellPalette.current
    IdeActionButton(
        onClick = { onCheckedChange(!checked) },
        tooltip = tooltip,
        modifier = modifier,
        selected = checked,
    ) { tint ->
        Text(
            text = label,
            fontSize = IdeaMetrics.TinyFontSize,
            color = if (checked) shell.text else tint,
            maxLines = 1,
        )
    }
}
