package com.neramc.quill.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.neramc.quill.QuillController
import com.neramc.quill.model.ToolWindow
import com.neramc.quill.model.WorkspaceState
import com.neramc.quill.ui.icons.IdeIcons
import com.neramc.quill.ui.theme.IdeaMetrics
import com.neramc.quill.ui.theme.LocalShellPalette
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

/**
 * A tool window stripe: the narrow icon rail down each edge of the window.
 *
 * The New UI replaced the old rotated text labels with icon buttons, and the difference is not
 * cosmetic — a 40dp rail of icons and a rail of sideways words read as two different products. The
 * name survives as the tooltip, which is where the IDE puts it.
 */
@Composable
public fun ToolWindowStripe(
    tools: List<ToolWindow>,
    active: ToolWindow?,
    onSelect: (ToolWindow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shell = LocalShellPalette.current
    Column(
        modifier = modifier.width(IdeaMetrics.StripeWidth).fillMaxHeight()
            .background(shell.toolWindowBackground)
            .padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        tools.forEach { tool ->
            IdeActionButton(
                onClick = { onSelect(tool) },
                tooltip = tool.label,
                selected = tool == active,
                size = IdeaMetrics.StripeButtonSize,
            ) { tint ->
                when (tool) {
                    ToolWindow.PROJECT -> IdeIcons.ProjectStripe(tint)
                    ToolWindow.STRUCTURE -> IdeIcons.StructureStripe(tint)
                }
            }
        }
    }
}

internal val ToolWindow.label: String
    get() = when (this) {
        ToolWindow.PROJECT -> "Project"
        ToolWindow.STRUCTURE -> "Structure"
    }

/**
 * The header above a docked tool window.
 *
 * Mixed case, not upper case: the New UI stopped shouting its panel titles, and an all-caps header
 * is one of the clearest tells of a UI copied from the old look.
 */
@Composable
public fun ToolWindowHeader(
    title: String,
    onHide: (() -> Unit)? = null,
    hidesTowardsLeft: Boolean = true,
    actions: @Composable () -> Unit = {},
) {
    val shell = LocalShellPalette.current
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().height(IdeaMetrics.ToolWindowHeaderHeight)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontSize = IdeaMetrics.SmallFontSize,
                color = shell.text,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )

            actions()

            if (onHide != null) {
                IdeActionButton(onClick = onHide, tooltip = "Hide", size = 22.dp) { tint ->
                    IdeIcons.Hide(tint, towardsLeft = hidesTowardsLeft, size = 14.dp)
                }
            }
        }
        Divider(Orientation.Horizontal, color = shell.border)
    }
}

/**
 * The status bar.
 *
 * The New UI keeps a message on the left and a right-aligned run of widgets — caret position, line
 * separator, encoding — each of which is a hover target. The theme switch stands in for the IDE's
 * own settings widget at the far right.
 */
@Composable
public fun StatusBar(controller: QuillController, workspace: WorkspaceState) {
    val shell = LocalShellPalette.current
    val document = workspace.activeDocument

    Column {
        Divider(Orientation.Horizontal, color = shell.border)
        Row(
            modifier = Modifier.fillMaxWidth().height(IdeaMetrics.StatusBarHeight)
                .background(shell.statusBarBackground)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val notification = workspace.notification
            val error = document?.loadError

            StatusItem(
                label = notification ?: error ?: "",
                tooltip = "Last message",
                modifier = Modifier.weight(1f),
                color = when {
                    error != null -> shell.error
                    notification != null -> shell.text
                    else -> shell.mutedText
                },
                onClick = controller::dismissNotification,
            )

            if (document != null) {
                val caret = document.caretPosition
                val stats = document.stats
                StatusItem("${caret.line + 1}:${caret.column + 1}", "Go to line and column")
                StatusItem("${stats.words} words", "Words in prose, excluding code and front matter")
                StatusItem(readingTime(stats.readingTimeSeconds), "Estimated reading time at 200 wpm")
                StatusItem("LF", "Line separator")
                StatusItem("UTF-8", "File encoding")
            }

            Spacer(Modifier.width(2.dp))
            IdeActionButton(
                onClick = controller::toggleTheme,
                tooltip = if (workspace.settings.darkTheme) "Switch to Light theme" else "Switch to Dark theme",
                size = 22.dp,
            ) { tint -> IdeIcons.Gear(tint, size = 14.dp) }
        }
    }
}

/** One status bar widget: a label with a hover fill and a tooltip, as every IDE widget is. */
@Composable
private fun StatusItem(
    label: String,
    tooltip: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = LocalShellPalette.current.mutedText,
    onClick: () -> Unit = {},
) {
    if (label.isEmpty()) {
        Box(modifier)
        return
    }

    Tooltip(tooltip = { Text(tooltip) }) {
        IdeWidgetButton(onClick = onClick, modifier = modifier) {
            Text(label, fontSize = IdeaMetrics.TinyFontSize, color = color, maxLines = 1)
        }
    }
}

private fun readingTime(seconds: Int): String = when {
    seconds <= 0 -> "—"
    seconds < 60 -> "${seconds}s read"
    else -> "${seconds / 60}m read"
}
