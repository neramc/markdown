package dev.starfect.quill.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.starfect.quill.QuillController
import dev.starfect.quill.bridge.wire.Finding
import dev.starfect.quill.bridge.wire.InspectionSummary
import dev.starfect.quill.bridge.wire.Severity
import dev.starfect.quill.model.DocumentSession
import dev.starfect.quill.model.ToolWindow
import dev.starfect.quill.ui.icons.IdeIcons
import dev.starfect.quill.ui.shell.IdeActionButton
import dev.starfect.quill.ui.theme.IdeaMetrics
import dev.starfect.quill.ui.theme.LocalShellPalette
import dev.starfect.quill.ui.theme.ShellPalette
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

/**
 * The inspection widget: the severity counts and the step-through arrows above the editor.
 *
 * This is the IDE's "⚠ 1 ⌃⌄" control. It reports what the document's inspections found and moves the
 * caret between findings, which is the only thing that makes the counts useful — a number with no
 * way to reach what it counts is decoration.
 *
 * A clean document shows the all-clear tick rather than "0", because the point of the widget is a
 * glance, and a zero has to be read.
 */
@Composable
public fun InspectionWidget(
    controller: QuillController,
    document: DocumentSession,
    modifier: Modifier = Modifier,
) {
    val shell = LocalShellPalette.current
    val summary = document.inspectionSummary
    val findings = document.findings

    Row(
        modifier = modifier.height(IdeaMetrics.ActionButtonSize),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        SummaryChip(
            summary = summary,
            shell = shell,
            onClick = { controller.setBottomToolWindow(ToolWindow.PROBLEMS) },
        )

        if (findings.isNotEmpty()) {
            IdeActionButton(
                onClick = { controller.goToFinding(document, forward = false) },
                tooltip = "Previous problem",
                size = 22.dp,
            ) { tint -> IdeIcons.ArrowUp(tint, size = 13.dp) }

            IdeActionButton(
                onClick = { controller.goToFinding(document, forward = true) },
                tooltip = "Next problem",
                size = 22.dp,
            ) { tint -> IdeIcons.ArrowDown(tint, size = 13.dp) }
        }
    }
}

/**
 * The counts themselves.
 *
 * Only the severities actually present are shown. A row reading "0 errors, 0 warnings, 3 weak" is
 * three times the width for one piece of information, and the IDE does not draw it that way.
 */
@Composable
private fun SummaryChip(summary: InspectionSummary, shell: ShellPalette, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Tooltip(tooltip = { Text(summaryTooltip(summary)) }) {
        Row(
            modifier = Modifier.height(22.dp)
                .clip(RoundedCornerShape(IdeaMetrics.ActionButtonCorner))
                .background(if (hovered) shell.hoverBackground else Color.Transparent)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (summary.total == 0) {
                IdeIcons.SeverityClean(shell.success, size = 13.dp)
                return@Row
            }

            Count(summary.errors, Severity.ERROR, shell)
            Count(summary.warnings, Severity.WARNING, shell)
            Count(summary.weak, Severity.WEAK, shell)
        }
    }
}

@Composable
private fun Count(count: Int, severity: Severity, shell: ShellPalette) {
    if (count == 0) return

    Box(Modifier.padding(end = 3.dp)) { SeverityIcon(severity, shell, size = 13.dp) }
    Text(
        text = count.toString(),
        color = shell.text,
        fontSize = IdeaMetrics.TinyFontSize,
    )
    Spacer(Modifier.width(6.dp))
}

/** The glyph for a severity, in the colour the IDE gives it. */
@Composable
public fun SeverityIcon(
    severity: Severity,
    shell: ShellPalette,
    size: androidx.compose.ui.unit.Dp = 13.dp,
) {
    when (severity) {
        Severity.ERROR -> IdeIcons.SeverityError(shell.error, size = size)
        Severity.WARNING -> IdeIcons.SeverityWarning(shell.warning, size = size)
        Severity.WEAK -> IdeIcons.SeverityWeak(shell.mutedText, size = size)
    }
}

private fun summaryTooltip(summary: InspectionSummary): String {
    if (summary.total == 0) return "No problems found"

    val parts = buildList {
        if (summary.errors > 0) add(plural(summary.errors, "error"))
        if (summary.warnings > 0) add(plural(summary.warnings, "warning"))
        if (summary.weak > 0) add(plural(summary.weak, "weak warning"))
    }
    return parts.joinToString(", ") + " — click to open Problems"
}

private fun plural(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"
