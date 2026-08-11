package dev.starfect.quill.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.starfect.quill.QuillController
import dev.starfect.quill.bridge.wire.Finding
import dev.starfect.quill.bridge.wire.Severity
import dev.starfect.quill.model.DocumentSession
import dev.starfect.quill.model.ToolWindow
import dev.starfect.quill.model.WorkspaceState
import dev.starfect.quill.ui.editor.SeverityIcon
import dev.starfect.quill.ui.shell.ToolWindowHeader
import dev.starfect.quill.ui.theme.IdeaMetrics
import dev.starfect.quill.ui.theme.LocalShellPalette
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer

/**
 * The Problems tool window: every inspection finding in the focused document.
 *
 * Rows read as "message — line N", in source order, and clicking one selects the offending range in
 * the editor. Selecting rather than merely scrolling is the difference between being told where a
 * problem is and being shown it.
 */
@Composable
public fun ProblemsPanel(
    controller: QuillController,
    workspace: WorkspaceState,
    modifier: Modifier = Modifier,
) {
    val shell = LocalShellPalette.current
    val document = workspace.activeDocument
    val findings = remember(document?.findings) { document?.findings?.sortedBy { it.start }.orEmpty() }
    val listState = rememberLazyListState()

    Column(modifier.fillMaxSize().background(shell.toolWindowBackground)) {
        ToolWindowHeader(
            title = problemsTitle(document, findings),
            onHide = { controller.setBottomToolWindow(null) },
            hidesTowardsLeft = false,
        )

        when {
            document == null ->
                EmptyState("No document open")

            !workspace.settings.inspectionsEnabled ->
                EmptyState("Inspections are turned off in Settings")

            findings.isEmpty() ->
                EmptyState("No problems found in ${document.displayName}")

            else -> VerticallyScrollableContainer(scrollState = listState, modifier = Modifier.fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    // Keyed by index. Two inspections legitimately fire on the same span with the
                    // same severity — an image written `![]()` has both no source and no alt text —
                    // so any key derived from a finding's contents can collide, and a duplicate key
                    // is a hard failure in LazyColumn rather than a cosmetic one.
                    items(findings.size) { index ->
                        FindingRow(
                            finding = findings[index],
                            onClick = { controller.goToFinding(document.id, findings[index]) },
                        )
                    }
                }
            }
        }
    }
}

/** The header carries the counts, which is where the IDE puts a tool window's tally. */
private fun problemsTitle(document: DocumentSession?, findings: List<Finding>): String = when {
    document == null -> "Problems"
    findings.isEmpty() -> "Problems"
    else -> "Problems  ${findings.size}"
}

@Composable
private fun FindingRow(finding: Finding, onClick: () -> Unit) {
    val shell = LocalShellPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        modifier = Modifier.fillMaxWidth()
            .height(IdeaMetrics.TreeRowHeight)
            .background(if (hovered) shell.hoverBackground else androidx.compose.ui.graphics.Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeverityIcon(finding.severity, shell)
        Spacer(Modifier.width(7.dp))

        Text(
            text = finding.message,
            color = shell.text,
            fontSize = IdeaMetrics.SmallFontSize,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )

        // The inspection's own name, dimmed, the way the IDE labels a finding's source.
        finding.inspection?.let { inspection ->
            Text(
                text = inspection.title,
                color = shell.mutedText,
                fontSize = IdeaMetrics.TinyFontSize,
                maxLines = 1,
            )
            Spacer(Modifier.width(10.dp))
        }

        Text(
            text = ":${finding.line + 1}",
            color = shell.mutedText,
            fontSize = IdeaMetrics.TinyFontSize,
        )
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = LocalShellPalette.current.mutedText, fontSize = IdeaMetrics.SmallFontSize)
    }
}

/**
 * The Notifications tool window.
 *
 * Run results land here. The IDE keeps them as a list rather than as toasts because the useful part
 * of a result — a path, a count — is what you go back to, and a toast is gone before you can.
 */
@Composable
public fun NotificationsPanel(
    controller: QuillController,
    workspace: WorkspaceState,
    modifier: Modifier = Modifier,
) {
    val shell = LocalShellPalette.current
    val listState = rememberLazyListState()

    Column(modifier.fillMaxSize().background(shell.toolWindowBackground)) {
        ToolWindowHeader(
            title = if (workspace.notifications.isEmpty()) {
                "Notifications"
            } else {
                "Notifications  ${workspace.notifications.size}"
            },
            onHide = { controller.setRightToolWindow(null) },
            hidesTowardsLeft = false,
        )

        if (workspace.notifications.isEmpty()) {
            EmptyState("Nothing yet")
            return@Column
        }

        VerticallyScrollableContainer(scrollState = listState, modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                items(workspace.notifications.size, key = { workspace.notifications[it].id }) { index ->
                    val entry = workspace.notifications[index]
                    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(
                            text = entry.title,
                            color = notificationColour(entry.severity, shell),
                            fontSize = IdeaMetrics.SmallFontSize,
                        )
                        Text(
                            text = entry.body,
                            color = shell.mutedText,
                            fontSize = IdeaMetrics.TinyFontSize,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun notificationColour(
    severity: dev.starfect.quill.model.NotificationSeverity,
    shell: dev.starfect.quill.ui.theme.ShellPalette,
) = when (severity) {
    dev.starfect.quill.model.NotificationSeverity.ERROR -> shell.error
    dev.starfect.quill.model.NotificationSeverity.WARNING -> shell.warning
    dev.starfect.quill.model.NotificationSeverity.SUCCESS -> shell.success
    dev.starfect.quill.model.NotificationSeverity.INFO -> shell.text
}

/**
 * A placeholder for a tool window Quill declares but does not implement.
 *
 * The stripe icons are part of the IDE's shape, and hiding the ones with nothing behind them would
 * be less honest than showing what they would hold. Saying so plainly is better than a panel that
 * looks functional and does nothing.
 */
@Composable
public fun PlaceholderPanel(
    tool: ToolWindow,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shell = LocalShellPalette.current

    Column(modifier.fillMaxSize().background(shell.toolWindowBackground)) {
        ToolWindowHeader(title = tool.label, onHide = onHide, hidesTowardsLeft = false)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = placeholderMessage(tool),
                color = shell.mutedText,
                fontSize = IdeaMetrics.SmallFontSize,
            )
        }
    }
}

private fun placeholderMessage(tool: ToolWindow): String = when (tool) {
    ToolWindow.TERMINAL -> "A terminal is not part of this build."
    ToolWindow.DATABASE -> "No data sources are configured."
    else -> "Nothing here yet."
}
