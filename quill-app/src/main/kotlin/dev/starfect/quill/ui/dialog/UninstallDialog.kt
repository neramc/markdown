package dev.starfect.quill.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.starfect.quill.install.Uninstall
import dev.starfect.quill.ui.theme.LocalShellPalette
import dev.starfect.quill.ui.theme.LocalTypeScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.ui.component.Text

/**
 * Removing Quill from inside Quill.
 *
 * The whole dialog is one screen with one decision, and the decision is stated in the terms a user
 * cares about: which folder is going, and what else gets undone. There is no wizard, because there
 * is nothing to configure — an uninstall that offers choices is an uninstall that can be got wrong.
 *
 * The interesting case is the one where there is nothing to uninstall. Most copies of Quill are
 * unpacked archives, and this says so plainly and offers no button, rather than pretending to
 * remove something and leaving the folder exactly where it was.
 */
@Composable
public fun UninstallDialog(onDismiss: () -> Unit, onFinished: () -> Unit) {
    val shell = LocalShellPalette.current
    val type = LocalTypeScale.current

    // Reading the manifest and the registry both touch the disk, so the dialog opens first and
    // fills in. It is a handful of milliseconds on a warm machine and a visible stall on a cold one.
    var state by remember { mutableStateOf<UninstallState>(UninstallState.Loading) }
    var removing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        state = withContext(Dispatchers.IO) { loadUninstallState() }
    }

    val ready = state as? UninstallState.Ready

    IdeDialog(
        title = "Uninstall Quill",
        onDismiss = { if (!removing) onDismiss() },
        width = 480.dp,
        height = 300.dp,
        confirmLabel = if (removing) "Removing…" else "Remove Quill",
        onConfirm = onConfirm@{
            val plan = ready?.plan ?: return@onConfirm onDismiss()
            if (removing) return@onConfirm
            removing = true
            // Deliberately on this thread. The registry work and the shortcuts take milliseconds,
            // and the alternative is a window that can still be interacted with while the thing
            // underneath it is being deleted.
            Uninstall.execute(plan)
            onFinished()
        },
    ) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (val current = state) {
                is UninstallState.Loading -> Text(
                    "Looking for the installation…",
                    color = shell.mutedText,
                    fontSize = type.medium,
                )

                is UninstallState.NotInstalled -> {
                    Text(
                        "Nothing to uninstall",
                        color = shell.text,
                        fontSize = type.h2,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(current.reason, color = shell.mutedText, fontSize = type.medium)
                }

                is UninstallState.Ready -> {
                    Text(
                        "Remove Quill ${current.version} from this computer?",
                        color = shell.text,
                        fontSize = type.h2,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Box(Modifier.height(2.dp))
                    Detail("Folder", current.plan.root.toString())
                    Detail("Files", "${current.plan.files.size}")
                    if (current.plan.shortcuts.isNotEmpty()) {
                        Detail("Shortcuts", "${current.plan.shortcuts.size}")
                    }
                    if (current.associations.isNotEmpty()) {
                        Detail("File types", current.associations.joinToString(", "))
                    }
                    Box(Modifier.height(6.dp))
                    Text(
                        "Your documents and settings are stored elsewhere and are not touched. " +
                            "Quill closes, and the last of the folder goes with it.",
                        color = shell.mutedText,
                        fontSize = type.medium,
                    )
                }
            }
        }
    }
}

/** One labelled fact. The label column is fixed so the values line up and can be scanned down. */
@Composable
private fun Detail(label: String, value: String) {
    val shell = LocalShellPalette.current
    val type = LocalTypeScale.current

    Row(Modifier.fillMaxWidth()) {
        Text(label, color = shell.secondaryText, fontSize = type.medium, modifier = Modifier.width(90.dp))
        Text(value, color = shell.text, fontSize = type.medium)
    }
}

/** What the dialog knows, which is a disk read away from what it shows. */
internal sealed interface UninstallState {
    object Loading : UninstallState

    data class NotInstalled(val reason: String) : UninstallState

    data class Ready(
        val version: String,
        val plan: Uninstall.Plan,
        val associations: List<String>,
    ) : UninstallState
}

/** Manifest, then registry, then plan — the whole decision, made before anything is shown. */
internal fun loadUninstallState(): UninstallState {
    val root = Uninstall.locateInstallRoot()
        ?: return UninstallState.NotInstalled(
            "This copy of Quill was not installed by the Windows installer. To remove it, delete " +
                "the folder it was unpacked into.",
        )

    val manifest = Uninstall.readManifest(root).getOrElse { failure ->
        return UninstallState.NotInstalled(failure.message ?: "The installation could not be read.")
    }

    val state = runCatching { Uninstall.readState(manifest, Uninstall.SystemRegistry) }
        .getOrDefault(Uninstall.RegistryState())

    return UninstallState.Ready(
        version = manifest.version.ifBlank { "this version" },
        plan = Uninstall.plan(manifest, state),
        associations = manifest.fileAssociations,
    )
}
