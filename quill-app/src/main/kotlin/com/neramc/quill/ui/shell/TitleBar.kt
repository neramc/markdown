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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.neramc.quill.ui.icons.IdeIcons
import com.neramc.quill.ui.theme.IdeaMetrics
import com.neramc.quill.ui.theme.LocalShellPalette
import com.neramc.quill.ui.theme.QuillTheme
import java.nio.file.Path
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.MenuScope
import org.jetbrains.jewel.ui.component.MenuSeparator
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.window.DecoratedWindowScope
import org.jetbrains.jewel.window.TitleBar

/**
 * The main toolbar, laid out the way IntelliJ IDEA's New UI lays it out.
 *
 * Left: the hamburger that holds the whole main menu, the product icon, and the project widget.
 * Centre: the open file. Right: Search Everywhere and Settings.
 *
 * The menu lives behind a hamburger rather than being spelled out as a File/Edit/View strip because
 * that is what the New UI does on every platform where the window is custom-decorated. It also
 * matters that these are flat hover-highlighted buttons and not combo boxes: a bordered control with
 * a drop-down arrow is the single detail that most gives away a JetBrains lookalike.
 */
@Composable
public fun DecoratedWindowScope.QuillTitleBar(
    controller: QuillController,
    workspace: WorkspaceState,
    onExit: () -> Unit,
) {
    TitleBar(Modifier.fillMaxWidth().height(IdeaMetrics.TitleBarHeight)) {
        Row(
            Modifier.align(Alignment.Start).padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Product icon, then the main menu, then the project widget: the order the New UI
            // uses on the platforms where the window carries its own decoration.
            Icon(
                painter = painterResource("icons/icon.png"),
                contentDescription = "Quill",
                modifier = Modifier.padding(end = 2.dp).size(20.dp),
            )
            MainMenuButton(controller, workspace, onExit)
            ProjectWidget(workspace)
        }

        CurrentFileLabel(workspace)

        Row(
            Modifier.align(Alignment.End).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TitleBarActions(controller, workspace)
        }
    }
}

/**
 * The same controls drawn as an ordinary toolbar row.
 *
 * Used when the platform keeps its own window decoration, so the shell is identical below the title
 * bar regardless of which runtime the application was launched with.
 */
@Composable
public fun QuillToolBar(controller: QuillController, workspace: WorkspaceState, onExit: () -> Unit) {
    val shell = LocalShellPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().height(IdeaMetrics.TitleBarHeight)
            .background(shell.toolWindowBackground)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            painter = painterResource("icons/icon.png"),
            contentDescription = "Quill",
            modifier = Modifier.padding(end = 2.dp).size(20.dp),
        )
        MainMenuButton(controller, workspace, onExit)
        ProjectWidget(workspace)

        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            CurrentFileLabel(workspace)
        }

        TitleBarActions(controller, workspace)
    }
    Divider(Orientation.Horizontal, color = shell.border)
}

/** Search Everywhere and Settings, the two actions the New UI keeps at the toolbar's right end. */
@Composable
private fun TitleBarActions(controller: QuillController, workspace: WorkspaceState) {
    IdeActionButton(
        onClick = { controller.setCommandPaletteVisible(true) },
        tooltip = "Search Everywhere  Ctrl+Shift+P",
        selected = workspace.commandPaletteVisible,
    ) { tint -> IdeIcons.Search(tint) }

    IdeActionButton(
        onClick = controller::toggleTheme,
        tooltip = if (workspace.settings.darkTheme) "Switch to Light theme" else "Switch to Dark theme",
    ) { tint -> IdeIcons.Gear(tint) }
}

/** The project widget: the open directory's name, as a pill that opens nothing but reads as one. */
@Composable
private fun ProjectWidget(workspace: WorkspaceState) {
    val shell = LocalShellPalette.current
    val project = workspace.projectRoot?.fileName?.toString() ?: "quill"

    IdeWidgetButton(onClick = {}) {
        Text(
            text = project,
            fontSize = IdeaMetrics.UiFontSize,
            color = shell.text,
            maxLines = 1,
        )
        // The chevron is what marks this as a widget rather than a caption; the IDE draws one on
        // every toolbar widget that can be opened.
        Box(Modifier.padding(start = 4.dp)) { IdeIcons.WidgetChevron(shell.mutedText) }
    }
}

/** The active file, centred, with the IDE's asterisk for unsaved changes. */
@Composable
private fun CurrentFileLabel(workspace: WorkspaceState) {
    val shell = LocalShellPalette.current
    val document = workspace.activeDocument ?: return

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = document.displayName,
            fontSize = IdeaMetrics.SmallFontSize,
            color = shell.mutedText,
            maxLines = 1,
        )
        if (document.isModified) {
            Text(" *", fontSize = IdeaMetrics.SmallFontSize, color = shell.modified)
        }
    }
}

/** The hamburger, and the main menu it opens. */
@Composable
private fun MainMenuButton(controller: QuillController, workspace: WorkspaceState, onExit: () -> Unit) {
    var open by remember { mutableStateOf(false) }

    Box {
        IdeActionButton(
            onClick = { open = !open },
            tooltip = "Main Menu",
            selected = open,
        ) { tint -> IdeIcons.Hamburger(tint) }

        if (open) {
            PopupMenu(
                onDismissRequest = {
                    open = false
                    true
                },
                horizontalAlignment = Alignment.Start,
                modifier = Modifier.width(260.dp),
            ) {
                mainMenu(controller, workspace, onExit) { open = false }
            }
        }
    }
}

/**
 * The whole main menu as one flat list of submenus.
 *
 * Every action closes the popup before running, because an action that opens a dialog or changes the
 * layout behind a menu that is still on screen is the kind of detail that feels broken without being
 * easy to name.
 */
private fun MenuScope.mainMenu(
    controller: QuillController,
    workspace: WorkspaceState,
    onExit: () -> Unit,
    dismiss: () -> Unit,
) {
    val activeId = workspace.activeDocumentId

    submenu(submenu = {
        selectableItem(
            selected = false,
            keybinding = setOf("Ctrl", "N"),
            onClick = {
                dismiss()
                controller.newDocument()
            },
        ) { Text("New Document") }

        selectableItem(
            selected = false,
            keybinding = setOf("Ctrl", "S"),
            enabled = activeId != null,
            onClick = {
                dismiss()
                activeId?.let { id -> controller.save(id) { null } }
            },
        ) { Text("Save") }

        passiveItem { MenuSeparator() }

        selectableItem(
            selected = false,
            enabled = activeId != null,
            onClick = {
                dismiss()
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
            onClick = {
                dismiss()
                activeId?.let(controller::closeDocument)
            },
        ) { Text("Close Document") }

        selectableItem(
            selected = false,
            onClick = {
                dismiss()
                onExit()
            },
        ) { Text("Exit") }
    }) { Text("File") }

    submenu(submenu = {
        selectableItem(
            selected = workspace.find.visible && !workspace.find.replaceVisible,
            keybinding = setOf("Ctrl", "F"),
            onClick = {
                dismiss()
                controller.setFindVisible(true)
            },
        ) { Text("Find…") }

        selectableItem(
            selected = workspace.find.replaceVisible,
            keybinding = setOf("Ctrl", "R"),
            onClick = {
                dismiss()
                controller.setFindVisible(visible = true, withReplace = true)
            },
        ) { Text("Replace…") }

        passiveItem { MenuSeparator() }

        selectableItem(
            selected = false,
            keybinding = setOf("Ctrl", "G"),
            enabled = workspace.activeDocument?.matches?.isNotEmpty() == true,
            onClick = {
                dismiss()
                controller.stepMatch(forward = true)
            },
        ) { Text("Find Next") }
    }) { Text("Edit") }

    submenu(submenu = {
        ViewMode.entries.forEach { mode ->
            selectableItem(
                selected = workspace.settings.viewMode == mode,
                keybinding = setOf("Ctrl", "${mode.ordinal + 1}"),
                onClick = {
                    dismiss()
                    controller.setViewMode(mode)
                },
            ) { Text(mode.label) }
        }

        passiveItem { MenuSeparator() }

        selectableItem(
            selected = workspace.leftToolWindow != null,
            onClick = {
                dismiss()
                controller.setLeftToolWindow(ToolWindow.PROJECT)
            },
        ) { Text("Project") }

        selectableItem(
            selected = workspace.rightToolWindow != null,
            onClick = {
                dismiss()
                controller.setRightToolWindow(ToolWindow.STRUCTURE)
            },
        ) { Text("Structure") }

        passiveItem { MenuSeparator() }

        selectableItem(
            selected = workspace.settings.showLineNumbers,
            onClick = {
                dismiss()
                controller.updateSettings { it.copy(showLineNumbers = !it.showLineNumbers) }
            },
        ) { Text("Line Numbers") }

        selectableItem(
            selected = workspace.settings.darkTheme,
            keybinding = setOf("Ctrl", "Shift", "T"),
            onClick = {
                dismiss()
                controller.toggleTheme()
            },
        ) { Text("Dark Theme") }
    }) { Text("View") }

    submenu(submenu = {
        selectableItem(
            selected = workspace.commandPaletteVisible,
            keybinding = setOf("Ctrl", "Shift", "P"),
            onClick = {
                dismiss()
                controller.setCommandPaletteVisible(true)
            },
        ) { Text("Search Everywhere…") }

        passiveItem { MenuSeparator() }

        selectableItem(
            selected = false,
            onClick = {
                dismiss()
                controller.openProject(Path.of("").toAbsolutePath())
            },
        ) { Text("Reload Project") }
    }) { Text("Tools") }
}

/** Display name for a view mode, shared by the menu and the editor toolbar's tooltips. */
internal val ViewMode.label: String
    get() = when (this) {
        ViewMode.EDITOR -> "Editor Only"
        ViewMode.SPLIT -> "Editor and Preview"
        ViewMode.PREVIEW -> "Preview Only"
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
