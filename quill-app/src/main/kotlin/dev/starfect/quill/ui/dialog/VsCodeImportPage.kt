package dev.starfect.quill.ui.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.starfect.quill.io.vscode.ImportReport
import dev.starfect.quill.io.vscode.VsCodeInstallation
import dev.starfect.quill.io.vscode.VsCodeSettings
import dev.starfect.quill.model.QuillSettings
import dev.starfect.quill.ui.theme.LocalShellPalette
import dev.starfect.quill.ui.theme.LocalTypeScale
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.Text

/**
 * Bringing settings across from VS Code.
 *
 * The page shows what it found, what it would change, and — just as deliberately — what it will not.
 * An import that reports only its successes leaves somebody assuming their whole configuration came
 * across, and discovering otherwise one surprise at a time.
 *
 * Nothing is written until the dialog's own OK, like every other page here, so this is still a draft
 * the user can abandon.
 */
@Composable
public fun VsCodeImportPage(settings: QuillSettings, onChange: (QuillSettings) -> Unit) {
    val shell = LocalShellPalette.current
    val type = LocalTypeScale.current

    val installations = remember { VsCodeSettings.installations() }
    var report by remember { mutableStateOf<ImportReport?>(null) }

    GroupHeader("Import from VS Code")

    if (installations.isEmpty()) {
        Text(
            "No VS Code settings were found on this machine. Quill looks for VS Code, Insiders, " +
                "VSCodium, Cursor and Windsurf in the usual place for this platform.",
            color = shell.mutedText,
            fontSize = type.medium,
        )
        return
    }

    Text(
        "The settings that mean the same thing in both editors can be copied across. Anything " +
            "without a fair equivalent here is listed rather than guessed at.",
        color = shell.mutedText,
        fontSize = type.medium,
        modifier = Modifier.padding(bottom = 8.dp),
    )

    installations.forEach { installation ->
        InstallationRow(installation) {
            val result = VsCodeSettings.importFrom(installation, settings)
            report = result
            if (result.failure == null) onChange(result.settings)
        }
    }

    report?.let { result ->
        Spacer(Modifier.height(12.dp))
        ImportOutcome(result)
    }
}

@Composable
private fun InstallationRow(installation: VsCodeInstallation, onImport: () -> Unit) {
    val shell = LocalShellPalette.current
    val type = LocalTypeScale.current

    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(installation.name, color = shell.text, fontSize = type.default)
            Text(
                installation.settingsFile.toString(),
                color = shell.mutedText,
                fontSize = type.medium,
            )
        }
        DefaultButton(onClick = onImport) { Text("Import") }
    }
}

@Composable
private fun ImportOutcome(report: ImportReport) {
    val shell = LocalShellPalette.current
    val type = LocalTypeScale.current

    report.failure?.let { failure ->
        Text(failure, color = shell.error, fontSize = type.medium)
        return
    }

    if (report.imported.isEmpty()) {
        Text(
            "Nothing to change — every setting Quill shares with ${report.installation.name} " +
                "already matches.",
            color = shell.mutedText,
            fontSize = type.medium,
        )
    } else {
        GroupHeader("Changed ${report.imported.size}")
        report.imported.forEach { change ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    change.setting.title,
                    color = shell.text,
                    fontSize = type.medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${change.before} → ${change.after}",
                    color = shell.mutedText,
                    fontSize = type.medium,
                )
            }
        }
    }

    if (report.unreadable.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        GroupHeader("Could not be read")
        Text(report.unreadable.joinToString(", "), color = shell.mutedText, fontSize = type.medium)
    }

    if (report.unsupported.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        GroupHeader("No equivalent in Quill")
        Text(
            report.unsupported.joinToString(", "),
            color = shell.mutedText,
            fontSize = type.medium,
        )
    }
}
