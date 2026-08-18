package dev.starfect.quill.ui.dialog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.starfect.quill.QuillController
import dev.starfect.quill.model.Confirm
import dev.starfect.quill.model.ConfirmChoice
import dev.starfect.quill.model.WorkspaceState

/**
 * Renders whatever question the workspace is waiting on, or nothing.
 *
 * The mapping from a [Confirm] to the words on screen lives here rather than in the controller,
 * which is what lets the controller be tested by asking what it *decided* rather than by reading a
 * dialog. The controller says "these documents, these are unsaved"; this decides that the answer
 * buttons are Save, Discard and Cancel and in that order.
 */
@Composable
public fun ConfirmHost(
    controller: QuillController,
    workspace: WorkspaceState,
    onExit: () -> Unit = {},
) {
    val pending = workspace.confirm

    // Held across the exit animation so the dialog fades out with its text intact rather than
    // emptying a frame before it disappears.
    var last by remember { mutableStateOf<Confirm?>(null) }
    if (pending != null) last = pending

    AnimatedConfirm(visible = pending != null) {
        when (val question = pending ?: last) {
            is Confirm.CloseDocuments -> CloseDocumentsQuestion(controller, question)
            is Confirm.Exit -> ExitQuestion(controller, question, onExit)
            null -> Unit
        }
    }
}

@Composable
private fun CloseDocumentsQuestion(controller: QuillController, question: Confirm.CloseDocuments) {
    val many = question.unsavedNames.size > 1

    // Saving is the default answer, and the only one that is not destructive. A confirmation whose
    // Enter key discards work is a confirmation that has made things worse.
    ConfirmDialog(
        title = if (many) "Save ${question.unsavedNames.size} documents?" else "Save changes?",
        message = if (many) {
            "These documents have changes that are not on disk. Closing them without saving " +
                "discards those changes."
        } else {
            "${question.unsavedNames.first()} has changes that are not on disk. Closing it " +
                "without saving discards them."
        },
        detail = if (many) question.unsavedNames else emptyList(),
        onDismiss = { controller.resolveConfirm(ConfirmChoice.CANCEL) },
        actions = listOf(
            ConfirmAction(
                label = if (many) "Save All" else "Save",
                default = true,
                onClick = {
                    controller.resolveConfirm(
                        choice = ConfirmChoice.SAVE,
                        onNeedsPath = controller::promptForPath,
                    )
                },
            ),
            ConfirmAction(
                label = if (many) "Discard All" else "Discard",
                onClick = { controller.resolveConfirm(ConfirmChoice.DISCARD) },
            ),
            ConfirmAction(
                label = "Cancel",
                onClick = { controller.resolveConfirm(ConfirmChoice.CANCEL) },
            ),
        ),
    )
}

@Composable
private fun ExitQuestion(controller: QuillController, question: Confirm.Exit, onExit: () -> Unit) {
    val many = question.unsavedNames.size > 1

    ConfirmDialog(
        title = "Save before closing Quill?",
        message = if (many) {
            "${question.unsavedNames.size} documents have changes that are not on disk."
        } else {
            "${question.unsavedNames.first()} has changes that are not on disk."
        },
        detail = question.unsavedNames,
        // Escape leaves the window open. Dismissing a question about quitting must never quit.
        onDismiss = { controller.resolveConfirm(ConfirmChoice.CANCEL) },
        actions = listOf(
            ConfirmAction(
                label = if (many) "Save All and Close" else "Save and Close",
                default = true,
                onClick = {
                    controller.resolveConfirm(
                        choice = ConfirmChoice.SAVE,
                        onNeedsPath = controller::promptForPath,
                        onExit = onExit,
                    )
                },
            ),
            ConfirmAction(
                label = "Close Without Saving",
                onClick = { controller.resolveConfirm(ConfirmChoice.DISCARD, onExit = onExit) },
            ),
            ConfirmAction(
                label = "Cancel",
                onClick = { controller.resolveConfirm(ConfirmChoice.CANCEL) },
            ),
        ),
    )
}
