package dev.starfect.quill.ui.dialog

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import dev.starfect.quill.ui.icons.IdeIcons
import dev.starfect.quill.ui.theme.Elevation.dropShadow
import dev.starfect.quill.ui.theme.LocalShellPalette
import dev.starfect.quill.ui.theme.LocalTypeScale
import dev.starfect.quill.ui.theme.Motion
import dev.starfect.quill.ui.theme.Tokens
import dev.starfect.quill.ui.theme.floatingFill
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/**
 * A button in a confirmation's footer.
 *
 * @param label what it says.
 * @param onClick what it does. Every button in a confirmation does something; there is no disabled
 *   state here, because a question you cannot answer is not a question.
 * @param default whether it is the one Enter presses and the one drawn filled.
 */
public data class ConfirmAction(
    public val label: String,
    public val onClick: () -> Unit,
    public val default: Boolean = false,
)

/**
 * The small modal Quill asks a question in before doing something irreversible.
 *
 * Separate from [IdeDialog] rather than a mode of it, for two reasons that both come down to the
 * same thing: this is not a form. It has no fixed size — it is as tall as its sentence — and its
 * footer is a list of *answers*, not the OK/Cancel pair a form ends with. Closing four modified
 * documents offers Save All, Discard All and Cancel; forcing that through a two-button footer is
 * how a dialog ends up asking "are you sure?" and meaning something else.
 *
 * Escape always answers with [onDismiss], which is always the answer that changes nothing. That is
 * the rule that makes it safe to dismiss a dialog you did not read.
 */
@Composable
public fun ConfirmDialog(
    title: String,
    message: String,
    actions: List<ConfirmAction>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    detail: List<String> = emptyList(),
) {
    val shell = LocalShellPalette.current
    val type = LocalTypeScale.current
    val scrimInteraction = remember { MutableInteractionSource() }
    val focus = remember { FocusRequester() }

    // The dialog takes focus when it appears, so Enter and Escape reach it rather than the editor
    // underneath. Without this the keyboard still drives the text field the question is about.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        modifier = modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(interactionSource = scrimInteraction, indication = null, onClick = onDismiss)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> {
                        onDismiss()
                        true
                    }
                    Key.Enter, Key.NumPadEnter -> {
                        actions.firstOrNull { it.default }?.onClick?.invoke() ?: onDismiss()
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val panelInteraction = remember { MutableInteractionSource() }

        Column(
            Modifier.widthIn(min = 380.dp, max = 520.dp)
                .clip(RoundedCornerShape(Tokens.Radius.Dialog))
                .dropShadow(RoundedCornerShape(Tokens.Radius.Dialog))
                .floatingFill(shell.popupBackground)
                .border(1.dp, shell.popupBorder, RoundedCornerShape(Tokens.Radius.Dialog))
                .clickable(interactionSource = panelInteraction, indication = null) {}
                .padding(Tokens.Spacing.XLarge),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(Tokens.LargeIconSize), contentAlignment = Alignment.Center) {
                    IdeIcons.SeverityWarning(shell.warning, size = Tokens.LargeIconSize)
                }
                Spacer(Modifier.width(Tokens.Spacing.Medium))

                Column {
                    Text(
                        text = title,
                        color = shell.text,
                        fontSize = type.default,
                        fontWeight = type.headerWeight,
                    )
                    Spacer(Modifier.height(Tokens.Spacing.Small))
                    Text(text = message, color = shell.secondaryText, fontSize = type.default)

                    // The names are listed rather than counted. "3 documents have unsaved changes"
                    // is a number you have to trust; the names are the thing you actually check
                    // before pressing Discard.
                    if (detail.isNotEmpty()) {
                        Spacer(Modifier.height(Tokens.Spacing.Small))
                        Column {
                            detail.take(MaxNamesListed).forEach { line ->
                                Text(text = "•  $line", color = shell.text, fontSize = type.default)
                            }
                            if (detail.size > MaxNamesListed) {
                                Text(
                                    text = "and ${detail.size - MaxNamesListed} more",
                                    color = shell.mutedText,
                                    fontSize = type.medium,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(Tokens.Spacing.XLarge))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.Small, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions.forEach { action ->
                    if (action.default) {
                        DefaultButton(onClick = action.onClick) { Text(action.label) }
                    } else {
                        OutlinedButton(onClick = action.onClick) { Text(action.label) }
                    }
                }
            }
        }
    }
}

/**
 * Wraps [ConfirmDialog] in the shell's arrival animation.
 *
 * Kept as its own composable so the caller passes a nullable question rather than an `if`: the exit
 * transition needs the content to survive the state going null, which an `if` around the dialog
 * cannot do.
 */
@Composable
public fun AnimatedConfirm(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = Motion.popupEnter,
        exit = Motion.popupExit,
    ) {
        content()
    }
}

/** How many names a confirmation lists before it starts counting instead. */
private const val MaxNamesListed = 6
