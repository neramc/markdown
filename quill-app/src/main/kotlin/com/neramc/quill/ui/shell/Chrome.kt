package com.neramc.quill.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neramc.quill.QuillController
import com.neramc.quill.model.ToolWindow
import com.neramc.quill.model.WorkspaceState
import com.neramc.quill.ui.theme.LocalShellPalette
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

/** Vertical tool window stripe: the narrow strip of rotated labels down each edge of an IDE. */
@Composable
public fun ToolWindowStripe(tools: List<ToolWindow>, active: ToolWindow?, onSelect: (ToolWindow) -> Unit) {
    val shell = LocalShellPalette.current
    Column(
        modifier = Modifier.width(26.dp).fillMaxHeight().background(shell.toolWindowBackground),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tools.forEach { tool ->
            val selected = tool == active
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (selected) shell.selectionBackground else Color.Transparent)
                    .clickable { onSelect(tool) }
                    .padding(vertical = 6.dp, horizontal = 2.dp),
            ) {
                Text(
                    text = tool.label,
                    // Rotated so the stripe stays narrow, exactly as the IDE draws it.
                    modifier = Modifier.rotate(if (tool == ToolWindow.PROJECT) -90f else 90f),
                    fontSize = 11.sp,
                    color = if (selected) shell.accent else shell.mutedText,
                    maxLines = 1,
                )
            }
        }
    }
}

private val ToolWindow.label: String
    get() = when (this) {
        ToolWindow.PROJECT -> "Project"
        ToolWindow.STRUCTURE -> "Structure"
    }

/** Header shown at the top of every docked tool window. */
@Composable
public fun ToolWindowHeader(title: String) {
    val shell = LocalShellPalette.current
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title.uppercase(), fontSize = 11.sp, color = shell.mutedText, maxLines = 1)
        }
        Divider(Orientation.Horizontal, color = shell.border)
    }
}

/** The status bar: caret position, document statistics and the theme switch. */
@Composable
public fun StatusBar(controller: QuillController, workspace: WorkspaceState) {
    val shell = LocalShellPalette.current
    val document = workspace.activeDocument

    Row(
        modifier = Modifier.fillMaxWidth().height(24.dp).background(shell.statusBarBackground)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val notification = workspace.notification
        val error = document?.loadError
        Text(
            text = notification ?: error ?: "Ready",
            modifier = Modifier.weight(1f).clickable { controller.dismissNotification() },
            fontSize = 11.sp,
            color = when {
                error != null -> shell.error
                notification != null -> shell.accent
                else -> shell.mutedText
            },
            maxLines = 1,
        )

        if (document != null) {
            val caret = document.caretPosition
            StatusItem("${caret.line + 1}:${caret.column + 1}", "Caret line and column")
            val stats = document.stats
            StatusItem("${stats.words} words", "Words in prose, excluding code and front matter")
            StatusItem("${stats.characters} chars", "Characters, in UTF-16 code units")
            StatusItem(readingTime(stats.readingTimeSeconds), "Estimated reading time at 200 wpm")
            StatusItem("UTF-8", "File encoding")
            StatusItem("LF", "Line separator")
        }

        Box(
            Modifier.clip(RoundedCornerShape(3.dp)).clickable { controller.toggleTheme() }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                if (workspace.settings.darkTheme) "Dark" else "Light",
                fontSize = 11.sp,
                color = shell.mutedText,
            )
        }
    }
}

@Composable
private fun StatusItem(label: String, description: String) {
    Tooltip(tooltip = { Text(description) }) {
        Text(label, fontSize = 11.sp, color = LocalShellPalette.current.mutedText, maxLines = 1)
    }
}

private fun readingTime(seconds: Int): String = when {
    seconds <= 0 -> "—"
    seconds < 60 -> "${seconds}s read"
    else -> "${seconds / 60}m read"
}
