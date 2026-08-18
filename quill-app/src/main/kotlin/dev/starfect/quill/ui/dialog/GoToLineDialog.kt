package dev.starfect.quill.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import dev.starfect.quill.QuillController
import dev.starfect.quill.model.WorkspaceState
import dev.starfect.quill.ui.theme.LocalShellPalette
import dev.starfect.quill.ui.theme.LocalTypeScale
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/**
 * Go to line.
 *
 * `line` or `line:column`, which is the format every editor accepts and the same one the status bar
 * displays — so the number a reader is looking at is the number they can type back.
 *
 * It exists because the status bar claimed it did. "Go to line and column" was the tooltip on a
 * widget that did nothing when pressed, which is a worse state than not offering it at all.
 */
@Composable
public fun GoToLineDialog(controller: QuillController, workspace: WorkspaceState) {
    val shell = LocalShellPalette.current
    val type = LocalTypeScale.current
    val document = workspace.activeDocument

    val lineCount = remember(document?.text?.text) {
        document?.text?.text?.count { it == '\n' }?.plus(1) ?: 1
    }
    val caret = document?.caretPosition

    var query by remember {
        mutableStateOf(TextFieldValue(((caret?.line ?: 0) + 1).toString()).let { it.copy(selection = TextRange(0, it.text.length)) })
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val target = remember(query.text) { parse(query.text) }
    val valid = target != null && document != null

    fun go() {
        val destination = target ?: return
        val id = document?.id ?: return
        controller.goToLine(id, destination.first, destination.second)
        controller.dismissDialog()
    }

    IdeDialog(
        title = "Go to Line",
        onDismiss = controller::dismissDialog,
        width = 380.dp,
        height = 190.dp,
        confirmLabel = "Go",
        onConfirm = { if (valid) go() },
    ) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Line number", color = shell.secondaryText, fontSize = type.medium)
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Text(
                if (valid) "of $lineCount lines. Use line:column to place the caret within it."
                else "Enter a line number, or line:column.",
                color = shell.mutedText,
                fontSize = type.medium,
            )
        }
    }
}

/** Reads `line` or `line:column`. Null when the text is not one of those. */
internal fun parse(text: String): Pair<Int, Int>? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val parts = trimmed.split(':', limit = 2)
    val line = parts[0].trim().toIntOrNull()?.takeIf { it >= 1 } ?: return null
    if (parts.size == 1) return line to 1
    val column = parts[1].trim().let { if (it.isEmpty()) 1 else it.toIntOrNull() ?: return null }
    return line to column.coerceAtLeast(1)
}
