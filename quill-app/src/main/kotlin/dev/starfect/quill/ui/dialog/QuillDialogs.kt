package dev.starfect.quill.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.starfect.quill.QuillController
import dev.starfect.quill.model.Dialog
import dev.starfect.quill.model.WorkspaceState
import dev.starfect.quill.ui.theme.LocalShellPalette
import dev.starfect.quill.ui.theme.LocalTypeScale
import org.jetbrains.jewel.ui.component.Text

/**
 * Every modal Quill can show, mounted in one place.
 *
 * Shared between the main window and the welcome window rather than duplicated, because the two had
 * drifted: the welcome window offered no way into Settings at all, since the dialog it would open
 * was only mounted on the other side. A dialog that exists but has nowhere to appear is the exact
 * shape of a button that does nothing.
 *
 * The confirmation sits above the rest: a question about losing work outranks whatever is on
 * screen, including a settings page that is itself modal.
 */
@Composable
public fun QuillDialogs(
    controller: QuillController,
    workspace: WorkspaceState,
    onExit: () -> Unit = {},
) {
    ConfirmHost(controller, workspace, onExit)

    when (workspace.dialog) {
        Dialog.SETTINGS -> SettingsDialog(controller, workspace)
        Dialog.RUN_CONFIGURATIONS -> RunConfigurationsDialog(controller, workspace)
        Dialog.ABOUT -> AboutDialog(controller)
        Dialog.GO_TO_LINE -> GoToLineDialog(controller, workspace)
        Dialog.UPDATE -> UpdateDialog(onDismiss = controller::dismissDialog, onRestart = onExit)
        Dialog.UNINSTALL -> UninstallDialog(onDismiss = controller::dismissDialog, onFinished = onExit)
        null -> Unit
    }
}

/** The About box, which is the one dialog with nothing to configure. */
@Composable
internal fun AboutDialog(controller: QuillController) {
    val shell = LocalShellPalette.current

    IdeDialog(
        title = "About Quill",
        onDismiss = controller::dismissDialog,
        width = 420.dp,
        height = 240.dp,
        confirmLabel = "Close",
    ) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Quill",
                color = shell.text,
                fontSize = LocalTypeScale.current.h1,
                fontWeight = FontWeight.SemiBold,
            )
            // The packaged launcher passes -Dquill.version; a Gradle run does not, and saying so is
            // better than showing a number that would be a guess.
            Text(
                text = System.getProperty("quill.version")?.let { "Version $it" } ?: "Development build",
                color = shell.mutedText,
                fontSize = LocalTypeScale.current.medium,
            )
            Box(Modifier.height(6.dp))
            Text(
                "A Markdown editor with a Rust engine.",
                color = shell.mutedText,
                fontSize = LocalTypeScale.current.medium,
            )
            Box(Modifier.height(8.dp))
            Text(
                "Runtime: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}",
                color = shell.mutedText,
                fontSize = LocalTypeScale.current.medium,
            )
            Text(
                "Renderer: Skia via Compose Multiplatform",
                color = shell.mutedText,
                fontSize = LocalTypeScale.current.medium,
            )
        }
    }
}
