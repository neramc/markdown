package com.neramc.quill.ui.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neramc.quill.QuillController
import com.neramc.quill.io.FileService
import com.neramc.quill.model.ToolWindow
import com.neramc.quill.model.ViewMode
import com.neramc.quill.model.WorkspaceState
import com.neramc.quill.ui.theme.LocalShellPalette
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/** One entry in the command palette. */
internal data class Command(val title: String, val category: String, val shortcut: String?, val run: () -> Unit)

/**
 * The command palette, on `Ctrl+Shift+P`.
 *
 * Matching is subsequence-based rather than substring-based, so "epht" finds "Export to HTML" the
 * way the IDE's Search Everywhere does.
 */
@Composable
public fun CommandPalette(controller: QuillController, workspace: WorkspaceState) {
    val shell = LocalShellPalette.current
    var query by remember { mutableStateOf(TextFieldValue("")) }
    val commands = remember(workspace) { buildCommands(controller, workspace) }
    val filtered = remember(query.text, commands) { filter(commands, query.text) }

    Box(
        modifier = Modifier.fillMaxSize()
            // A scrim that also dismisses, matching how modal popups behave in the IDE.
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable { controller.setCommandPaletteVisible(false) },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier.padding(top = 96.dp).width(560.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(shell.toolWindowBackground)
                // Swallow clicks so they do not reach the dismissing scrim behind.
                .clickable(enabled = false) {}
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Type a command…", color = shell.mutedText) },
            )

            if (filtered.isEmpty()) {
                Text("No matching commands", color = shell.mutedText, fontSize = 12.sp)
            } else {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(filtered.size, key = { filtered[it].title }) { index ->
                        val command = filtered[index]
                        Row(
                            modifier = Modifier.fillMaxWidth().height(28.dp)
                                .clickable {
                                    controller.setCommandPaletteVisible(false)
                                    command.run()
                                }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(command.category, fontSize = 11.sp, color = shell.mutedText)
                            Text(command.title, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            command.shortcut?.let { Text(it, fontSize = 11.sp, color = shell.mutedText) }
                        }
                    }
                }
            }
        }
    }
}

private fun buildCommands(controller: QuillController, workspace: WorkspaceState): List<Command> {
    val activeId = workspace.activeDocumentId
    val document = workspace.activeDocument

    return buildList {
        add(Command("New Document", "File", "Ctrl+N", controller::newDocument))
        if (activeId != null) {
            add(Command("Save", "File", "Ctrl+S") { controller.save(activeId) { null } })
            add(Command("Close Document", "File", "Ctrl+W") { controller.closeDocument(activeId) })
            add(
                Command("Export to HTML", "File", null) {
                    controller.exportHtml(activeId, FileService().htmlExportTarget(document?.path))
                }
            )
        }
        add(Command("Find", "Edit", "Ctrl+F") { controller.setFindVisible(true) })
        add(Command("Replace", "Edit", "Ctrl+R") { controller.setFindVisible(true, withReplace = true) })

        ViewMode.entries.forEach { mode ->
            val label = mode.name.lowercase().replaceFirstChar(Char::titlecase)
            add(Command("View: $label", "View", "Ctrl+${mode.ordinal + 1}") { controller.setViewMode(mode) })
        }
        add(Command("Toggle Theme", "View", "Ctrl+Shift+T", controller::toggleTheme))
        add(
            Command("Toggle Line Numbers", "View", null) {
                controller.updateSettings { it.copy(showLineNumbers = !it.showLineNumbers) }
            }
        )
        add(Command("Toggle Project Tool Window", "View", null) { controller.setLeftToolWindow(ToolWindow.PROJECT) })
        add(
            Command("Toggle Structure Tool Window", "View", null) {
                controller.setRightToolWindow(ToolWindow.STRUCTURE)
            }
        )

        workspace.documents.forEach { session ->
            add(Command("Go to ${session.displayName}", "Navigate", null) { controller.selectDocument(session.id) })
        }
    }
}

/** Ranks commands by subsequence match, preferring earlier and tighter matches. */
internal fun filter(commands: List<Command>, query: String): List<Command> {
    if (query.isBlank()) return commands
    val needle = query.lowercase()

    return commands
        .mapNotNull { command ->
            score("${command.category} ${command.title}".lowercase(), needle)?.let { it to command }
        }
        .sortedBy { it.first }
        .map { it.second }
}

/** Returns a match cost, or `null` when [needle] is not a subsequence of [haystack]. */
private fun score(haystack: String, needle: String): Int? {
    var index = 0
    var cost = 0
    var previous = -1
    for (character in needle) {
        if (character == ' ') continue
        val found = haystack.indexOf(character, index)
        if (found < 0) return null
        // Characters adjacent in the haystack score better than scattered ones.
        cost += if (previous >= 0) found - previous else found
        previous = found
        index = found + 1
    }
    return cost
}
