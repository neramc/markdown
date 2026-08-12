package dev.starfect.quill.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.starfect.quill.ui.icons.IdeIcons
import dev.starfect.quill.ui.shell.IdeActionButton
import dev.starfect.quill.ui.theme.LocalTypeScale
import dev.starfect.quill.ui.theme.Tokens
import dev.starfect.quill.ui.theme.LocalShellPalette
import dev.starfect.quill.ui.theme.ShellDivider
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/**
 * The frame every Quill dialog is drawn in.
 *
 * It is an in-window overlay rather than a separate OS window. A real IDE dialog is its own window,
 * but a window cannot be composed offscreen, and every dialog here would then be untestable and
 * unscreenshottable. The scrim, the rounded panel and the button row are what make it read as a
 * dialog; being a separate window is not.
 *
 * Escape closes and Enter confirms, because a dialog that has to be dismissed with the mouse is the
 * one thing an IDE user will notice immediately.
 */
@Composable
public fun IdeDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 720.dp,
    height: Dp = 520.dp,
    confirmLabel: String = "OK",
    onConfirm: (() -> Unit)? = null,
    extraButtons: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val shell = LocalShellPalette.current
    val scrimInteraction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier.fillMaxSize()
            // The scrim absorbs clicks, which is what makes the dialog modal. Without it the editor
            // underneath still takes the caret and the dialog is a floating panel, not a dialog.
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(interactionSource = scrimInteraction, indication = null, onClick = onDismiss)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> {
                        onDismiss()
                        true
                    }
                    Key.Enter, Key.NumPadEnter -> {
                        onConfirm?.invoke() ?: onDismiss()
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val panelInteraction = remember { MutableInteractionSource() }

        Column(
            Modifier.width(width).height(height)
                .clip(RoundedCornerShape(Tokens.Radius.Popup))
                .background(shell.popupBackground)
                .border(1.dp, shell.popupBorder, RoundedCornerShape(Tokens.Radius.Popup))
                // Swallow clicks so they do not reach the scrim and dismiss the dialog.
                .clickable(interactionSource = panelInteraction, indication = null) {}
        ) {
            DialogTitleBar(title, onDismiss)
            ShellDivider(Orientation.Horizontal)

            Box(Modifier.weight(1f).fillMaxWidth()) { content() }

            ShellDivider(Orientation.Horizontal)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.Medium, vertical = Tokens.Spacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                extraButtons()
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(Tokens.Spacing.Small))
                DefaultButton(onClick = { onConfirm?.invoke() ?: onDismiss() }) { Text(confirmLabel) }
            }
        }
    }
}

@Composable
private fun DialogTitleBar(title: String, onDismiss: () -> Unit) {
    val shell = LocalShellPalette.current

    Row(
        Modifier.fillMaxWidth().height(Tokens.DialogTitleHeight).padding(start = Tokens.Spacing.Medium, end = Tokens.Spacing.Tiny),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = shell.text,
            fontSize = LocalTypeScale.current.default,
            fontWeight = LocalTypeScale.current.headerWeight,
        )
        Spacer(Modifier.weight(1f))
        IdeActionButton(onClick = onDismiss, tooltip = "Close", size = Tokens.ControlSize) { tint ->
            IdeIcons.Close(tint, size = Tokens.IconSize)
        }
    }
}

/**
 * A labelled row in a dialog form.
 *
 * The label column is fixed so every control in a form starts at the same x, which is what makes a
 * settings page scan as a form rather than as a stack of unrelated controls.
 */
@Composable
public fun FormRow(
    label: String,
    modifier: Modifier = Modifier,
    labelWidth: Dp = 150.dp,
    content: @Composable () -> Unit,
) {
    val shell = LocalShellPalette.current

    Row(
        modifier.fillMaxWidth().padding(vertical = Tokens.Spacing.Tiny),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = shell.secondaryText,
            fontSize = LocalTypeScale.current.default,
            modifier = Modifier.width(labelWidth),
        )
        Box(Modifier.weight(1f)) { content() }
    }
}

/** A form row with no label, indented to line up with the controls above it. */
@Composable
public fun FormIndent(labelWidth: Dp = 150.dp, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = Tokens.Spacing.Tiny), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(labelWidth))
        Box(Modifier.weight(1f)) { content() }
    }
}

/** The toolbar above a dialog's list pane: add, remove, copy. */
@Composable
public fun ListToolbar(
    onAdd: () -> Unit,
    onRemove: (() -> Unit)?,
    onCopy: (() -> Unit)? = null,
    addTooltip: String = "Add",
) {
    val shell = LocalShellPalette.current

    Row(
        Modifier.fillMaxWidth().height(Tokens.ToolWindowHeaderHeight).padding(horizontal = Tokens.Spacing.Tiny),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        IdeActionButton(onClick = onAdd, tooltip = addTooltip, size = Tokens.ControlSize) { tint ->
            IdeIcons.Plus(tint, size = Tokens.IconSize)
        }
        IdeActionButton(
            onClick = { onRemove?.invoke() },
            tooltip = "Remove",
            enabled = onRemove != null,
            size = Tokens.ControlSize,
        ) { tint ->
            Box(Modifier.size(Tokens.IconSize), contentAlignment = Alignment.Center) {
                Box(Modifier.width(9.dp).height(1.5.dp).background(tint))
            }
        }
        if (onCopy != null) {
            IdeActionButton(onClick = onCopy, tooltip = "Copy", size = Tokens.ControlSize) { tint ->
                IdeIcons.Copy(tint, size = Tokens.IconSize)
            }
        }
    }
}
