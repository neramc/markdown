package com.neramc.quill.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.neramc.quill.QuillController
import com.neramc.quill.bridge.QuillNativeLibraryException
import com.neramc.quill.io.FileService
import com.neramc.quill.model.ToolWindow
import com.neramc.quill.model.ViewMode
import com.neramc.quill.model.WorkspaceState
import com.neramc.quill.ui.theme.LocalShellPalette
import com.neramc.quill.ui.theme.QuillTheme
import java.nio.file.Path
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Dropdown
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.MenuScope
import org.jetbrains.jewel.ui.component.MenuSeparator
import org.jetbrains.jewel.ui.component.SegmentedControl
import org.jetbrains.jewel.ui.component.SegmentedControlButtonData
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.window.DecoratedWindowScope
import org.jetbrains.jewel.window.TitleBar

/**
 * The IDE-style title bar: application menu on the left, project and document in the middle, view
 * controls on the right.
 *
 * Putting the menu inside the window decoration rather than in a native menu bar is what makes the
 * window read as a JetBrains IDE on every platform.
 */
@Composable
public fun DecoratedWindowScope.QuillTitleBar(
    controller: QuillController,
    workspace: WorkspaceState,
    onExit: () -> Unit,
) {
    val shell = LocalShellPalette.current
    TitleBar(Modifier.fillMaxWidth()) {
        Row(Modifier.align(Alignment.Start), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource("icons/icon.png"),
                contentDescription = "Quill",
                modifier = Modifier.padding(horizontal = 8.dp).size(18.dp),
            )
            MainMenu(controller, workspace, onExit)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            val project = workspace.projectRoot?.fileName?.toString()
            val document = workspace.activeDocument
            Text(
                text = listOfNotNull(
                    project,
                    document?.displayName?.let { if (document.isModified) "$it *" else it },
                ).joinToString("  —  ").ifEmpty { "No document" },
                color = shell.mutedText,
                maxLines = 1,
            )
        }

        Row(
            Modifier.align(Alignment.End).padding(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewModeControl(workspace.settings.viewMode, controller::setViewMode)
        }
    }
}

/**
 * The same controls as [QuillTitleBar], drawn as an ordinary toolbar row.
 *
 * Used when the platform window keeps its own decoration, so the menu, project label and view switch
 * stay available regardless of which runtime the application is launched with.
 */
@Composable
public fun QuillToolBar(controller: QuillController, workspace: WorkspaceState, onExit: () -> Unit) {
    val shell = LocalShellPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().height(36.dp).background(shell.toolWindowBackground)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource("icons/icon.png"),
            contentDescription = "Quill",
            modifier = Modifier.size(18.dp),
        )
        MainMenu(controller, workspace, onExit)

        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
            val project = workspace.projectRoot?.fileName?.toString()
            val document = workspace.activeDocument
            Text(
                text = listOfNotNull(
                    project,
                    document?.displayName?.let { if (document.isModified) "$it *" else it },
                ).joinToString("  —  "),
                color = shell.mutedText,
                maxLines = 1,
            )
        }

        ViewModeControl(workspace.settings.viewMode, controller::setViewMode)
    }
    Divider(Orientation.Horizontal, color = shell.border)
}

/** Editor / Split / Preview switch, the same control the IDE uses for its Markdown editor. */
@Composable
private fun ViewModeControl(current: ViewMode, onSelect: (ViewMode) -> Unit) {
    val buttons = ViewMode.entries.map { mode ->
        SegmentedControlButtonData(
            selected = mode == current,
            content = { Text(mode.label, fontSize = 12.sp) },
            onSelect = { onSelect(mode) },
        )
    }
    SegmentedControl(buttons = buttons, modifier = Modifier.width(220.dp))
}

private val ViewMode.label: String
    get() = when (this) {
        ViewMode.EDITOR -> "Editor"
        ViewMode.SPLIT -> "Split"
        ViewMode.PREVIEW -> "Preview"
    }

/** File / Edit / View / Tools, rendered as dropdowns. */
@Composable
private fun MainMenu(controller: QuillController, workspace: WorkspaceState, onExit: () -> Unit) {
    val activeId = workspace.activeDocumentId

    Row(verticalAlignment = Alignment.CenterVertically) {
        MenuButton("File") {
            selectableItem(selected = false, keybinding = setOf("Ctrl", "N"), onClick = controller::newDocument) {
                Text("New Document")
            }
            selectableItem(
                selected = false,
                keybinding = setOf("Ctrl", "S"),
                enabled = activeId != null,
                onClick = { activeId?.let { id -> controller.save(id) { null } } },
            ) { Text("Save") }
            passiveItem { MenuSeparator() }
            selectableItem(
                selected = false,
                enabled = activeId != null,
                onClick = {
                    if (activeId != null) {
                        controller.exportHtml(activeId, FileService().htmlExportTarget(workspace.activeDocument?.path))
                    }
                },
            ) { Text("Export to HTML…") }
            passiveItem { MenuSeparator() }
            selectableItem(
                selected = false,
                keybinding = setOf("Ctrl", "W"),
                enabled = activeId != null,
                onClick = { activeId?.let(controller::closeDocument) },
            ) { Text("Close Document") }
            selectableItem(selected = false, onClick = onExit) { Text("Exit") }
        }

        MenuButton("Edit") {
            selectableItem(
                selected = workspace.find.visible && !workspace.find.replaceVisible,
                keybinding = setOf("Ctrl", "F"),
                onClick = { controller.setFindVisible(true) },
            ) { Text("Find…") }
            selectableItem(
                selected = workspace.find.replaceVisible,
                keybinding = setOf("Ctrl", "R"),
                onClick = { controller.setFindVisible(visible = true, withReplace = true) },
            ) { Text("Replace…") }
            passiveItem { MenuSeparator() }
            selectableItem(
                selected = false,
                keybinding = setOf("Ctrl", "G"),
                enabled = workspace.activeDocument?.matches?.isNotEmpty() == true,
                onClick = { controller.stepMatch(forward = true) },
            ) { Text("Find Next") }
        }

        MenuButton("View") {
            ViewMode.entries.forEach { mode ->
                selectableItem(
                    selected = workspace.settings.viewMode == mode,
                    keybinding = setOf("Ctrl", "${mode.ordinal + 1}"),
                    onClick = { controller.setViewMode(mode) },
                ) { Text(mode.label) }
            }
            passiveItem { MenuSeparator() }
            selectableItem(
                selected = workspace.leftToolWindow != null,
                onClick = { controller.setLeftToolWindow(ToolWindow.PROJECT) },
            ) { Text("Project") }
            selectableItem(
                selected = workspace.rightToolWindow != null,
                onClick = { controller.setRightToolWindow(ToolWindow.STRUCTURE) },
            ) { Text("Structure") }
            passiveItem { MenuSeparator() }
            selectableItem(
                selected = workspace.settings.showLineNumbers,
                onClick = { controller.updateSettings { it.copy(showLineNumbers = !it.showLineNumbers) } },
            ) { Text("Line Numbers") }
            selectableItem(
                selected = workspace.settings.darkTheme,
                keybinding = setOf("Ctrl", "Shift", "T"),
                onClick = controller::toggleTheme,
            ) { Text("Dark Theme") }
        }

        MenuButton("Tools") {
            selectableItem(
                selected = workspace.commandPaletteVisible,
                keybinding = setOf("Ctrl", "Shift", "P"),
                onClick = { controller.setCommandPaletteVisible(true) },
            ) { Text("Command Palette…") }
            passiveItem { MenuSeparator() }
            selectableItem(
                selected = false,
                onClick = { controller.openProject(Path.of("").toAbsolutePath()) },
            ) { Text("Reload Project") }
        }
    }
}

@Composable
private fun MenuButton(label: String, menu: MenuScope.() -> Unit) {
    Dropdown(
        modifier = Modifier.padding(horizontal = 2.dp),
        menuContent = menu,
        content = { Text(label, fontSize = 13.sp) },
    )
}

/**
 * Shown instead of the editor when the native engine cannot be loaded.
 *
 * A two-language application fails in a way a single-language one does not: the UI starts fine and
 * the engine behind it is missing. Saying so explicitly, with the remedy, beats an empty window.
 */
@Composable
public fun ApplicationScope.StartupFailureWindow(failure: QuillNativeLibraryException, onExit: () -> Unit) {
    Window(onCloseRequest = onExit, state = rememberWindowState(), title = "Quill — startup failed") {
        QuillTheme(dark = true) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Quill could not start", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(failure.message ?: "The native core engine could not be loaded.")
                    Text(
                        "Build the engine with ./gradlew :quill-bridge:cargoBuild, or point " +
                            "-Dquill.native.path at an existing library.",
                        color = LocalShellPalette.current.mutedText,
                    )
                }
            }
        }
    }
}
