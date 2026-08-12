package dev.starfect.quill.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.starfect.quill.QuillController
import dev.starfect.quill.model.WorkspaceState
import dev.starfect.quill.ui.icons.IdeIcons
import dev.starfect.quill.ui.shell.IdeActionButton
import dev.starfect.quill.ui.shell.IdeToggleChip
import dev.starfect.quill.ui.theme.LocalTypeScale
import dev.starfect.quill.ui.theme.Tokens
import dev.starfect.quill.ui.theme.LocalShellPalette
import dev.starfect.quill.ui.theme.ShellDivider
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/**
 * The find and replace bar, docked at the *top* of the editor as IntelliJ docks it.
 *
 * The layout is the IDE's: a leading chevron that expands find into find-and-replace, the query
 * field, the match counter inside the field's trailing edge, step buttons, and the three option
 * toggles as short glyph chips rather than labelled checkboxes. Chips are what keep this one row
 * tall; a row of "Match case / Words / Regex" checkboxes is nearly twice as wide and immediately
 * looks like a different program.
 *
 * An invalid regular expression is shown inline rather than as a dialog: while the user is typing
 * `[a-z` the pattern is *expected* to be invalid, and interrupting them for it would be hostile.
 */
@Composable
public fun FindReplaceBar(controller: QuillController, workspace: WorkspaceState) {
    val shell = LocalShellPalette.current
    val find = workspace.find
    val document = workspace.activeDocument
    val matchCount = document?.matches?.size ?: 0
    val currentMatch = document?.currentMatch ?: -1

    Column(Modifier.fillMaxWidth().background(shell.toolWindowBackground)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(Tokens.FindBarHeight).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IdeActionButton(
                onClick = { controller.setFindVisible(visible = true, withReplace = !find.replaceVisible) },
                tooltip = if (find.replaceVisible) "Collapse to Find" else "Expand to Replace",
                size = Tokens.SmallControlSize,
            ) { tint -> IdeIcons.ExpandChevron(tint, find.replaceVisible, size = Tokens.IconSize) }

            TextField(
                value = TextFieldValue(find.query),
                onValueChange = { value -> controller.updateFind { it.copy(query = value.text) } },
                modifier = Modifier.width(280.dp),
                placeholder = { Text("Find", color = shell.mutedText) },
                outline = if (find.error != null) Outline.Error else Outline.None,
                trailingIcon = {
                    // The IDE puts the counter inside the field, right-aligned, where it does not
                    // move as the query grows.
                    Text(
                        text = when {
                            find.query.isEmpty() -> ""
                            matchCount == 0 -> "No results"
                            else -> "${currentMatch + 1}/$matchCount"
                        },
                        fontSize = LocalTypeScale.current.medium,
                        color = if (matchCount == 0 && find.query.isNotEmpty()) shell.error else shell.mutedText,
                        modifier = Modifier.padding(end = 6.dp),
                        maxLines = 1,
                    )
                },
            )

            IdeActionButton(
                onClick = { controller.stepMatch(forward = false) },
                tooltip = "Previous Occurrence",
                shortcut = "Shift+F3",
                enabled = matchCount > 0,
                size = Tokens.SmallControlSize,
            ) { tint -> IdeIcons.ArrowUp(tint, size = Tokens.IconSize) }

            IdeActionButton(
                onClick = { controller.stepMatch(forward = true) },
                tooltip = "Next Occurrence",
                shortcut = "F3",
                enabled = matchCount > 0,
                size = Tokens.SmallControlSize,
            ) { tint -> IdeIcons.ArrowDown(tint, size = Tokens.IconSize) }

            ShellDivider(Orientation.Vertical, Modifier.height(Tokens.IconSize))

            IdeToggleChip(
                label = "Aa",
                checked = find.caseSensitive,
                onCheckedChange = { checked -> controller.updateFind { it.copy(caseSensitive = checked) } },
                tooltip = "Match Case",
            )
            IdeToggleChip(
                label = "W",
                checked = find.wholeWord,
                onCheckedChange = { checked -> controller.updateFind { it.copy(wholeWord = checked) } },
                tooltip = "Words",
            )
            IdeToggleChip(
                label = ".*",
                checked = find.regex,
                onCheckedChange = { checked -> controller.updateFind { it.copy(regex = checked) } },
                tooltip = "Regex",
            )

            find.error?.let { message ->
                Text(
                    text = message,
                    fontSize = LocalTypeScale.current.medium,
                    color = shell.error,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            Box(Modifier.weight(1f))

            IdeActionButton(
                onClick = { controller.setFindVisible(false) },
                tooltip = "Close",
                shortcut = "Escape",
                size = Tokens.SmallControlSize,
            ) { tint -> IdeIcons.Close(tint, size = Tokens.IconSize) }
        }

        if (find.replaceVisible) {
            Row(
                modifier = Modifier.fillMaxWidth().height(Tokens.FindBarHeight)
                    .padding(start = 32.dp, end = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TextField(
                    value = TextFieldValue(find.replacement),
                    onValueChange = { value -> controller.updateFind { it.copy(replacement = value.text) } },
                    modifier = Modifier.width(280.dp),
                    placeholder = { Text("Replace", color = shell.mutedText) },
                )
                ReplaceAction("Replace All", enabled = matchCount > 0, onClick = controller::replaceAll)
            }
        }

        ShellDivider(Orientation.Horizontal)
    }
}

/** A flat text action, the shape the IDE's find bar uses instead of a raised button. */
@Composable
private fun ReplaceAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    val shell = LocalShellPalette.current
    IdeActionButton(
        onClick = onClick,
        tooltip = label,
        enabled = enabled,
        size = Tokens.SmallControlSize,
        modifier = Modifier.width(88.dp),
    ) { tint ->
        Text(
            text = label,
            fontSize = LocalTypeScale.current.medium,
            color = if (enabled) shell.text else tint,
            maxLines = 1,
        )
    }
}
