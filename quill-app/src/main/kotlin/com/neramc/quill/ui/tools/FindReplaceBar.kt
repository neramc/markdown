package com.neramc.quill.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neramc.quill.QuillController
import com.neramc.quill.model.WorkspaceState
import com.neramc.quill.ui.theme.LocalShellPalette
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.ActionButton
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/**
 * The find and replace bar, docked below the editor as it is in the IDE.
 *
 * An invalid regular expression is shown inline rather than as an error dialog: while the user is
 * typing `[a-z` the pattern is *expected* to be invalid, and interrupting them for it would be
 * hostile.
 */
@Composable
public fun FindReplaceBar(controller: QuillController, workspace: WorkspaceState) {
    val shell = LocalShellPalette.current
    val find = workspace.find
    val document = workspace.activeDocument
    val matchCount = document?.matches?.size ?: 0
    val currentMatch = document?.currentMatch ?: -1

    Column(
        modifier = Modifier.fillMaxWidth().background(shell.toolWindowBackground)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = TextFieldValue(find.query),
                onValueChange = { value -> controller.updateFind { it.copy(query = value.text) } },
                modifier = Modifier.width(260.dp),
                placeholder = { Text("Find", color = shell.mutedText) },
                outline = if (find.error != null) Outline.Error else Outline.None,
            )

            Text(
                text = when {
                    find.query.isEmpty() -> ""
                    matchCount == 0 -> "No matches"
                    else -> "${currentMatch + 1} of $matchCount"
                },
                fontSize = 12.sp,
                color = if (matchCount == 0 && find.query.isNotEmpty()) shell.error else shell.mutedText,
                modifier = Modifier.width(96.dp),
                maxLines = 1,
            )

            ActionButton(onClick = { controller.stepMatch(forward = false) }) { Text("▲") }
            ActionButton(onClick = { controller.stepMatch(forward = true) }) { Text("▼") }

            CheckboxRow(
                text = "Match case",
                checked = find.caseSensitive,
                onCheckedChange = { checked -> controller.updateFind { it.copy(caseSensitive = checked) } },
            )
            CheckboxRow(
                text = "Words",
                checked = find.wholeWord,
                onCheckedChange = { checked -> controller.updateFind { it.copy(wholeWord = checked) } },
            )
            CheckboxRow(
                text = "Regex",
                checked = find.regex,
                onCheckedChange = { checked -> controller.updateFind { it.copy(regex = checked) } },
            )

            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                ActionButton(onClick = { controller.setFindVisible(false) }) { Text("✕") }
            }
        }

        if (find.replaceVisible) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = TextFieldValue(find.replacement),
                    onValueChange = { value -> controller.updateFind { it.copy(replacement = value.text) } },
                    modifier = Modifier.width(260.dp),
                    placeholder = { Text("Replace with", color = shell.mutedText) },
                )
                ActionButton(onClick = controller::replaceAll) { Text("Replace all") }
            }
        }

        find.error?.let { Text(it, fontSize = 11.sp, color = shell.error, maxLines = 1) }
    }
}
