package dev.starfect.quill.ui.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import dev.starfect.quill.QuillController
import dev.starfect.quill.io.FileService
import dev.starfect.quill.model.ToolWindow
import dev.starfect.quill.model.ViewMode
import dev.starfect.quill.model.WorkspaceState
import dev.starfect.quill.ui.icons.IdeIcons
import dev.starfect.quill.ui.theme.LocalTypeScale
import dev.starfect.quill.ui.theme.Elevation.dropShadow
import dev.starfect.quill.ui.theme.floatingFill
import dev.starfect.quill.ui.theme.Tokens
import dev.starfect.quill.ui.theme.interactiveSurface
import dev.starfect.quill.ui.theme.LocalShellPalette
import dev.starfect.quill.ui.theme.ShellDivider
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/** One entry in the palette. */
internal data class Command(val title: String, val category: String, val shortcut: String?, val run: () -> Unit)

/**
 * Search Everywhere, on `Ctrl+Shift+P`.
 *
 * Modelled on IntelliJ's dialog rather than on a generic command palette: a wide floating surface
 * near the top of the window, a tab row of scopes across the top, a tall borderless query field, and
 * results grouped under muted category headers with their shortcut right-aligned. The proportions
 * are what carry the resemblance — IntelliJ's is 700pt wide with a 44pt field, and a narrow palette
 * with a boxed input reads as a different application entirely.
 *
 * Matching is subsequence-based rather than substring-based, so "epht" finds "Export to HTML" the
 * same way the IDE's does.
 */
@Composable
public fun CommandPalette(controller: QuillController, workspace: WorkspaceState) {
    val shell = LocalShellPalette.current
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var scope by remember { mutableStateOf(SearchScope.All) }

    val commands = remember(workspace) { buildCommands(controller, workspace) }
    val filtered = remember(query.text, commands, scope) {
        filter(commands.filter { scope.accepts(it) }, query.text)
    }
    val listState = rememberLazyListState()

    Box(
        modifier = Modifier.fillMaxSize()
            // A scrim that also dismisses, matching how the IDE's modal popups behave.
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { controller.setCommandPaletteVisible(false) },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier.padding(top = 100.dp).width(Tokens.SearchPopupWidth)
                .clip(RoundedCornerShape(10.dp))
                .dropShadow(RoundedCornerShape(Tokens.Radius.Popup))
                .floatingFill(shell.popupBackground)
                .border(1.dp, shell.popupBorder, RoundedCornerShape(10.dp))
                // Swallow clicks so they do not reach the dismissing scrim behind.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = false,
                ) {},
        ) {
            ScopeTabs(scope) { scope = it }
            ShellDivider(Orientation.Horizontal)

            Row(
                modifier = Modifier.fillMaxWidth().height(Tokens.SearchFieldHeight)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.Small),
            ) {
                IdeIcons.Search(shell.mutedText, size = Tokens.IconSize)
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    undecorated = true,
                    placeholder = { Text(scope.placeholder, color = shell.mutedText) },
                )
            }

            ShellDivider(Orientation.Horizontal)

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                    Text("Nothing found", color = shell.mutedText, fontSize = LocalTypeScale.current.medium)
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.heightIn(max = 380.dp).padding(vertical = 4.dp)) {
                    var lastCategory: String? = null
                    filtered.forEachIndexed { index, command ->
                        // Group headers appear whenever the category changes, which after ranking
                        // means they follow relevance rather than a fixed order.
                        if (command.category != lastCategory) {
                            lastCategory = command.category
                            item(key = "header-${command.category}-$index") {
                                CategoryHeader(command.category)
                            }
                        }
                        item(key = "command-${command.title}-$index") {
                            CommandRow(
                                command = command,
                                selected = index == 0 && query.text.isNotEmpty(),
                                onRun = {
                                    controller.setCommandPaletteVisible(false)
                                    command.run()
                                },
                            )
                        }
                    }
                }
            }

            ShellDivider(Orientation.Horizontal)
            Row(
                modifier = Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${filtered.size} result${if (filtered.size == 1) "" else "s"}",
                    fontSize = LocalTypeScale.current.medium,
                    color = shell.mutedText,
                    modifier = Modifier.weight(1f),
                )
                Text("Enter to run  ·  Esc to close", fontSize = LocalTypeScale.current.medium, color = shell.mutedText)
            }
        }
    }
}

/** The scopes Search Everywhere offers, mapped onto what a Markdown editor actually has. */
internal enum class SearchScope(val title: String, val placeholder: String) {
    All("All", "Type to search everywhere"),
    Actions("Actions", "Type an action name"),
    Files("Files", "Type a file name"),
    ;

    fun accepts(command: Command): Boolean = when (this) {
        All -> true
        Files -> command.category == "Files"
        Actions -> command.category != "Files"
    }
}

@Composable
private fun ScopeTabs(selected: SearchScope, onSelect: (SearchScope) -> Unit) {
    val shell = LocalShellPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().height(Tokens.TabHeight).padding(horizontal = Tokens.Spacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchScope.entries.forEach { scope ->
            val isSelected = scope == selected

            Box(
                modifier = Modifier.height(Tokens.SearchScopeHeight)
                    // A scope chip switches the search's filter; it is not the row the caret is on,
                    // so it takes the toggled grey and leaves the blue to the result list.
                    .interactiveSurface(
                        onClick = { onSelect(scope) },
                        palette = shell,
                        selected = isSelected,
                        toggle = true,
                        cornerRadius = Tokens.Radius.Control,
                    )
                    .padding(horizontal = Tokens.Spacing.Small),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = scope.title,
                    fontSize = LocalTypeScale.current.medium,
                    color = if (isSelected) shell.text else shell.mutedText,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun CategoryHeader(category: String) {
    val shell = LocalShellPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().height(Tokens.StatusBarHeight).padding(start = Tokens.Spacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(category, fontSize = LocalTypeScale.current.medium, color = shell.mutedText, maxLines = 1)
    }
}

@Composable
private fun CommandRow(command: Command, selected: Boolean, onRun: () -> Unit) {
    val shell = LocalShellPalette.current

    Row(
        modifier = Modifier.fillMaxWidth().height(Tokens.SearchRowHeight)
            // Keyboard selection and pointer hover are the same state here: the palette is driven by
            // the arrow keys, and a row the caret is on should look the way a row under the pointer
            // does rather than inventing a third fill.
            .interactiveSurface(
                onClick = onRun,
                palette = shell,
                selected = selected,
                cornerRadius = Tokens.Radius.Panel,
            )
            .padding(horizontal = Tokens.Spacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
        // The same icon-to-label gap as a tree row and a tab, so a glyph sits the same distance from
        // its text everywhere in the shell.
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.Tiny),
    ) {
        // Each category gets its own glyph. A column of identical icons carries no information and
        // is one of the things that makes a copied Search Everywhere feel like a plain list.
        Box(Modifier.size(Tokens.IconSize), contentAlignment = Alignment.Center) {
            when (command.category) {
                "Files" -> IdeIcons.MarkdownFile(shell.icon, shell.accent, size = Tokens.IconSize)
                "File" -> IdeIcons.MarkdownFile(shell.icon, shell.mutedText, size = Tokens.IconSize)
                "Edit" -> IdeIcons.Pencil(shell.icon, size = Tokens.IconSize)
                "View" -> IdeIcons.ViewSplit(shell.icon, size = Tokens.IconSize)
                else -> IdeIcons.Action(shell.icon, size = Tokens.IconSize)
            }
        }

        Text(
            text = command.title,
            fontSize = LocalTypeScale.current.default,
            color = shell.text,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )

        command.shortcut?.let {
            Text(it, fontSize = LocalTypeScale.current.medium, color = shell.mutedText, maxLines = 1)
        }
    }
}

private fun buildCommands(controller: QuillController, workspace: WorkspaceState): List<Command> {
    val activeId = workspace.activeDocumentId
    val document = workspace.activeDocument

    return buildList {
        add(Command("New Document", "File", "Ctrl+N", controller::newDocument))
        if (activeId != null) {
            add(Command("Save", "File", "Ctrl+S") { controller.saveWithPrompt(activeId) })
            add(Command("Save As\u2026", "File", "Ctrl+Shift+S") { controller.saveAs(activeId) })
            add(Command("Close Document", "File", "Ctrl+W") { controller.requestCloseDocument(activeId) })
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
            add(Command(session.displayName, "Files", null) { controller.selectDocument(session.id) })
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
