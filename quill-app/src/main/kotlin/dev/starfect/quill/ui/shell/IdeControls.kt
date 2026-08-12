package dev.starfect.quill.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
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
import dev.starfect.quill.ui.theme.LocalTypeScale
import dev.starfect.quill.ui.theme.Tokens
import dev.starfect.quill.ui.theme.LocalShellPalette
import dev.starfect.quill.ui.theme.SurfaceState
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
    /** The action's keyboard shortcut, shown beside the label in the platform's muted style. */
    shortcut: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: Dp = Tokens.ControlSize,
    content: @Composable (tint: Color) -> Unit,
) {
    val shell = LocalShellPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()

    // A toolbar button is a switch, not a cursor: `selected` here means "this tool window is open"
    // or "this view mode is on", so it takes the toggled grey rather than the selection blue.
    val state = SurfaceState.ofToggle(
        hovered = hovered,
        pressed = pressed,
        on = selected,
        enabled = enabled,
    )

    Tooltip(tooltip = { ActionTooltip(tooltip, shortcut) }) {
        Box(
            modifier = modifier.size(size)
                .clip(RoundedCornerShape(Tokens.Radius.Control))
                .background(state.background(shell))
                .hoverable(interaction, enabled = enabled)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            // A disabled action recedes rather than disappearing, so the row does not shift.
            content(state.iconTint(shell))
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

    val state = SurfaceState.ofToggle(hovered = hovered, pressed = pressed, on = selected)

    Row(
        modifier = modifier.height(Tokens.ControlSize)
            .clip(RoundedCornerShape(Tokens.Radius.Control))
            .background(state.background(shell))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Tokens.Spacing.Small),
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
            fontSize = LocalTypeScale.current.medium,
            color = if (checked) shell.text else tint,
            maxLines = 1,
        )
    }
}

/**
 * An action tooltip: what the control does, and the shortcut that does it without the pointer.
 *
 * The platform treats the tooltip as a design-system component rather than as a string — an
 * icon-only control is *required* to carry one, and where a shortcut exists it is shown. Two
 * elements rather than one concatenated string, because the shortcut is reference material and the
 * label is the answer: run them together at the same weight and the eye has to separate them every
 * time.
 */
@Composable
public fun ActionTooltip(label: String, shortcut: String? = null) {
    val shell = LocalShellPalette.current
    val scale = LocalTypeScale.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.Small),
    ) {
        Text(label, fontSize = scale.default, color = shell.text, maxLines = 1)
        if (shortcut != null) {
            Text(shortcut, fontSize = scale.medium, color = shell.mutedText, maxLines = 1)
        }
    }
}
