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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.starfect.quill.install.UpdateService
import dev.starfect.quill.install.UpdateSwap
import dev.starfect.quill.install.Updates
import dev.starfect.quill.ui.theme.LocalShellPalette
import dev.starfect.quill.ui.theme.LocalTypeScale
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.ui.component.HorizontalProgressBar
import org.jetbrains.jewel.ui.component.IndeterminateHorizontalProgressBar
import org.jetbrains.jewel.ui.component.Text

/**
 * Checking for a newer Quill, and installing it.
 *
 * Four states on one panel, because the whole interaction is one decision made twice: is there
 * something newer, and do you want it. Anything more is a wizard for a button.
 *
 * The dialog never decides anything itself. [Updates] works out whether an update exists and how it
 * could be applied — replacing the installation in place, or handing the platform its own installer
 * — and this shows that answer and its consequence. A user who is told "Quill will restart" and then
 * has a `.dmg` open instead has been lied to by an interface that guessed.
 */
@Composable
public fun UpdateDialog(onDismiss: () -> Unit, onRestart: () -> Unit) {
    val shell = LocalShellPalette.current
    val type = LocalTypeScale.current
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf<UpdateState>(UpdateState.Checking) }

    LaunchedEffect(Unit) {
        state = withContext(Dispatchers.IO) { checkForUpdate() }
    }

    val available = state as? UpdateState.Available
    val ready = state as? UpdateState.Ready

    IdeDialog(
        title = "Update Quill",
        onDismiss = { if (state !is UpdateState.Downloading) onDismiss() },
        width = 480.dp,
        height = 320.dp,
        confirmLabel = when {
            ready != null -> ready.action
            available != null -> "Download"
            else -> "Close"
        },
        onConfirm = onConfirm@{
            ready?.let { finished ->
                apply(finished, onRestart, onFailure = { state = UpdateState.Failed(it) })
                return@onConfirm
            }
            val offer = available ?: return@onConfirm onDismiss()
            state = UpdateState.Downloading(offer.release.version.toString(), null)
            scope.launch {
                state = withContext(Dispatchers.IO) {
                    downloadUpdate(offer) { progress ->
                        state = UpdateState.Downloading(offer.release.version.toString(), progress.fraction)
                    }
                }
            }
        },
    ) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (val current = state) {
                is UpdateState.Checking ->
                    Text("Checking for updates…", color = shell.mutedText, fontSize = type.medium)

                is UpdateState.UpToDate -> {
                    Heading("Quill is up to date")
                    Text(
                        "You are running ${current.version}, which is the newest release.",
                        color = shell.mutedText,
                        fontSize = type.medium,
                    )
                }

                is UpdateState.Available -> {
                    Heading("Quill ${current.release.version} is available")
                    Detail("Installed", current.installed)
                    Detail("Download", "${current.asset.name} (${megabytes(current.asset.size)})")
                    Box(Modifier.height(4.dp))
                    Text(current.consequence, color = shell.mutedText, fontSize = type.medium)
                }

                is UpdateState.Downloading -> {
                    Heading("Downloading Quill ${current.version}")
                    // Indeterminate until the server has said how big the file is. A bar sitting
                    // at zero and a bar that is genuinely at zero look identical, and one of them
                    // is a download that has stalled.
                    val fraction = current.fraction
                    if (fraction == null) {
                        IndeterminateHorizontalProgressBar(Modifier.fillMaxWidth().height(4.dp))
                    } else {
                        HorizontalProgressBar(fraction, Modifier.fillMaxWidth().height(4.dp))
                    }
                    Text(
                        fraction?.let { "${(it * 100).toInt()}%" } ?: "Starting…",
                        color = shell.mutedText,
                        fontSize = type.medium,
                    )
                }

                is UpdateState.Ready -> {
                    Heading("Quill ${current.version} is ready")
                    Text(current.consequence, color = shell.mutedText, fontSize = type.medium)
                }

                is UpdateState.Failed -> {
                    Heading("Could not update")
                    Text(current.reason, color = shell.mutedText, fontSize = type.medium)
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        color = LocalShellPalette.current.text,
        fontSize = LocalTypeScale.current.h2,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun Detail(label: String, value: String) {
    val shell = LocalShellPalette.current
    val type = LocalTypeScale.current

    Row(Modifier.fillMaxWidth()) {
        Text(label, color = shell.secondaryText, fontSize = type.medium, modifier = Modifier.width(80.dp))
        Text(value, color = shell.text, fontSize = type.medium)
    }
}

private fun megabytes(bytes: Long) =
    if (bytes <= 0) "size unknown" else "${(bytes + 524_288) / 1_048_576} MB"

/** What the dialog knows. Each state is one screen. */
internal sealed interface UpdateState {
    object Checking : UpdateState

    data class UpToDate(val version: String) : UpdateState

    data class Available(
        val release: Updates.Release,
        val asset: Updates.Asset,
        val method: Updates.Method,
        val installed: String,
        val consequence: String,
    ) : UpdateState

    data class Downloading(val version: String, val fraction: Float?) : UpdateState

    /** Downloaded and verified. [action] is the button, [file] is what it acts on. */
    data class Ready(
        val version: String,
        val file: Path,
        val method: Updates.Method,
        val action: String,
        val consequence: String,
    ) : UpdateState

    data class Failed(val reason: String) : UpdateState
}

/** Asks the repository, then asks [Updates] what could be done about the answer. */
internal fun checkForUpdate(): UpdateState {
    val release = UpdateService.latestRelease().getOrElse {
        return UpdateState.Failed(
            "Could not reach the update server. ${it.message ?: "The connection failed."}",
        )
    }

    val installed = Updates.running?.toString() ?: "this build"
    return when (val check = Updates.check(release)) {
        is Updates.Check.UpToDate -> UpdateState.UpToDate(installed)
        is Updates.Check.CannotApply -> UpdateState.Failed(check.reason)
        is Updates.Check.Available -> UpdateState.Available(
            release = check.release,
            asset = check.asset,
            method = check.method,
            installed = installed,
            consequence = when (check.method) {
                Updates.Method.REPLACE ->
                    "Quill will close, replace itself and start again. Your settings and open " +
                        "documents are stored elsewhere and are not touched."
                Updates.Method.HAND_OFF ->
                    "This copy was installed by something that owns it — a package manager, or an " +
                        "administrator — so Quill will download the installer and open it rather " +
                        "than writing over files that are not its own."
            },
        )
    }
}

/**
 * Downloads and, for an in-place update, unpacks beside the installation.
 *
 * Unpacking happens here rather than after the confirmation because it is the step that can still
 * fail — a truncated archive, a full disk — and failing before the application has closed itself is
 * the difference between an error message and an editor that does not come back.
 */
internal fun downloadUpdate(
    offer: UpdateState.Available,
    onProgress: (UpdateService.Progress) -> Unit,
): UpdateState {
    val root = Updates.appImageRoot()
    val staging = when (offer.method) {
        // Beside the installation, because the swap is a rename and a rename across filesystems is
        // a copy of a hundred megabytes that can fail halfway.
        Updates.Method.REPLACE -> root?.parent ?: return UpdateState.Failed(
            "The installation has no parent directory to unpack into.",
        )
        Updates.Method.HAND_OFF -> Path.of(System.getProperty("java.io.tmpdir"))
    }

    val downloaded = UpdateService
        .download(offer.release, offer.asset, staging.resolve(offer.asset.name), onProgress)
        .getOrElse { return UpdateState.Failed(it.message ?: "The download failed.") }

    if (offer.method == Updates.Method.HAND_OFF) {
        return UpdateState.Ready(
            version = offer.release.version.toString(),
            file = downloaded,
            method = offer.method,
            action = "Open installer",
            consequence = "Quill will open ${downloaded.fileName}. Close Quill before installing over it.",
        )
    }

    val unpackInto = staging.resolve("${root!!.fileName}.new")
    runCatching { if (Files.exists(unpackInto)) unpackInto.toFile().deleteRecursively() }
    val image = UpdateService.unpack(downloaded, unpackInto)
        .getOrElse { return UpdateState.Failed(it.message ?: "The download could not be unpacked.") }
    runCatching { Files.deleteIfExists(downloaded) }

    // The archive holds `Quill/`, which unpacks one level below the staging directory. The swap
    // renames siblings, so it has to be moved up beside the installation first.
    val sibling = staging.resolve("${root.fileName}.new-image")
    runCatching { if (Files.exists(sibling)) sibling.toFile().deleteRecursively() }
    val replacement = runCatching { Files.move(image, sibling) }
        .getOrElse { return UpdateState.Failed("Could not stage the new version: ${it.message}") }
    runCatching { unpackInto.toFile().deleteRecursively() }

    return UpdateState.Ready(
        version = offer.release.version.toString(),
        file = replacement,
        method = offer.method,
        action = "Restart",
        consequence = "Quill will close and start again as ${offer.release.version}.",
    )
}

/** Hands off to the installer, or starts the swap and exits. */
internal fun apply(ready: UpdateState.Ready, onRestart: () -> Unit, onFailure: (String) -> Unit) {
    if (ready.method == Updates.Method.HAND_OFF) {
        runCatching { Desktop.getDesktop().open(ready.file.toFile()) }
            .onFailure { return onFailure("Could not open ${ready.file}: ${it.message}") }
        onRestart()
        return
    }

    val root = Updates.appImageRoot() ?: return onFailure("The installation could not be located.")
    val plan = UpdateSwap.plan(root, ready.file)
        .getOrElse { return onFailure(it.message ?: "The update could not be applied.") }

    UpdateSwap.start(plan)
        .onFailure { return onFailure(it.message ?: "The update could not be started.") }

    // Everything from here happens in the script's process, because this one is about to stop
    // existing — which is the only way a program replaces its own files on Windows.
    onRestart()
}
