package dev.starfect.quill.ui.shell

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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import dev.starfect.quill.QuillController
import dev.starfect.quill.bridge.QuillNativeLibraryException
import dev.starfect.quill.io.FileService
import dev.starfect.quill.io.GitStatus
import dev.starfect.quill.model.Dialog
import dev.starfect.quill.model.ToolWindow
import dev.starfect.quill.model.ViewMode
import dev.starfect.quill.model.WorkspaceState
import dev.starfect.quill.ui.icons.IdeIcons
import dev.starfect.quill.ui.theme.Tokens
import dev.starfect.quill.ui.theme.LocalShellPalette
import dev.starfect.quill.ui.theme.ShellDivider
import dev.starfect.quill.ui.theme.ShellPalette
import dev.starfect.quill.ui.theme.QuillTheme
import java.nio.file.Path
import org.jetbrains.jewel.ui.Orientation
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
    TitleBar(Modifier.fillMaxWidth().height(Tokens.ToolbarHeight)) {
        Row(
            Modifier.align(Alignment.Start).padding(start = Tokens.Spacing.Small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.Tiny),
        ) {
            // Product icon, then the main menu, then the project widget: the order the New UI
            // uses on the platforms where the window carries its own decoration.
            Icon(
                painter = painterResource("icons/icon.png"),
                contentDescription = "Quill",
                modifier = Modifier.size(Tokens.IconSize),
            )
            MainMenuButton(controller, workspace, onExit)
            ProjectWidget(workspace)
            BranchWidget(workspace)
        }

        Row(
            Modifier.align(Alignment.End).padding(end = Tokens.Spacing.Small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp),
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
        modifier = Modifier.fillMaxWidth().height(Tokens.ToolbarHeight)
            .background(shell.toolWindowBackground)
            .padding(horizontal = Tokens.Spacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.Tiny),
    ) {
        Icon(
            painter = painterResource("icons/icon.png"),
            contentDescription = "Quill",
            modifier = Modifier.size(Tokens.IconSize),
        )
        MainMenuButton(controller, workspace, onExit)
        ProjectWidget(workspace)
        BranchWidget(workspace)

        Box(Modifier.weight(1f))

        TitleBarActions(controller, workspace)
    }
    ShellDivider(Orientation.Horizontal)
}

/** Search Everywhere and Settings, the two actions the New UI keeps at the toolbar's right end. */
@Composable
private fun TitleBarActions(controller: QuillController, workspace: WorkspaceState) {
    IdeActionButton(
        onClick = { controller.setCommandPaletteVisible(true) },
        tooltip = "Search Everywhere  Ctrl+Shift+P",
        selected = workspace.commandPaletteVisible,
    ) { tint -> IdeIcons.Search(tint) }

    val active = workspace.activeRunConfiguration
    IdeActionButton(
        onClick = { active?.let(controller::run) ?: controller.showDialog(Dialog.RUN_CONFIGURATIONS) },
        tooltip = active?.let { "Run '${it.name}'  Shift+F10" } ?: "Add a run configuration",
        enabled = workspace.activeDocument != null,
    ) { tint ->
        // The run triangle is green when there is something to run and takes the ordinary icon
        // tint when there is not, which is how the IDE distinguishes an armed action from an empty one.
        IdeIcons.Run(if (active != null) LocalShellPalette.current.success else tint)
    }

    IdeActionButton(
        onClick = { controller.showDialog(Dialog.SETTINGS) },
        tooltip = "Settings  Ctrl+Alt+S",
        selected = workspace.dialog == Dialog.SETTINGS,
    ) { tint -> IdeIcons.Gear(tint) }
}

/**
 * The project widget: a coloured avatar carrying the project's initial, its name, and a chevron.
 *
 * The badge is the detail that makes this widget recognisable at a glance — IntelliJ gives every
 * project a deterministic colour so the toolbar tells you which window you are in before you have
 * read anything.
 */
@Composable
private fun ProjectWidget(workspace: WorkspaceState) {
    val shell = LocalShellPalette.current
    val project = workspace.projectRoot?.fileName?.toString() ?: "quill"

    IdeWidgetButton(onClick = {}) {
        Box(
            modifier = Modifier.size(Tokens.ProjectBadgeSize)
                .clip(RoundedCornerShape(Tokens.ProjectBadgeCorner))
                .background(ShellPalette.badgeColor(project)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = project.firstOrNull()?.uppercase() ?: "Q",
                fontSize = Tokens.TinyFontSize,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
            )
        }

        Text(
            text = project,
            fontSize = Tokens.FontSize,
            color = shell.text,
            maxLines = 1,
            modifier = Modifier.padding(start = Tokens.Spacing.Tiny),
        )
        // The chevron is what marks this as a widget rather than a caption; the IDE draws one on
        // every toolbar widget that can be opened.
        Box(Modifier.padding(start = 4.dp)) { IdeIcons.WidgetChevron(shell.mutedText) }
    }
}

/**
 * The VCS widget, showing the checked-out branch.
 *
 * It appears only when the project really is a Git working tree, so the widget is either accurate or
 * absent — a decorative "master" next to a directory under no version control would be worse than
 * the gap it fills.
 */
@Composable
private fun BranchWidget(workspace: WorkspaceState) {
    val shell = LocalShellPalette.current
    val root = workspace.projectRoot
    val branch = remember(root) { GitStatus.currentBranch(root) } ?: return

    IdeWidgetButton(onClick = {}) {
        IdeIcons.Branch(shell.icon, size = Tokens.IconSize)
        Text(
            text = branch,
            fontSize = Tokens.FontSize,
            color = shell.text,
            maxLines = 1,
            modifier = Modifier.padding(start = 5.dp),
        )
        Box(Modifier.padding(start = 4.dp)) { IdeIcons.WidgetChevron(shell.mutedText) }
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

        // Every tool window, each reporting whether it is the one open on its dock. Listing them
        // from the enum keeps this menu and the stripes from drifting apart.
        ToolWindow.entries.forEach { tool ->
            selectableItem(
                selected = workspace.toolWindow(tool.dock) == tool,
                onClick = {
                    dismiss()
                    controller.toggleToolWindow(tool)
                },
            ) { Text(tool.label) }
        }

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

    submenu(submenu = {
        val active = workspace.activeRunConfiguration
        selectableItem(
            selected = false,
            enabled = active != null,
            keybinding = setOf("Shift", "F10"),
            onClick = {
                dismiss()
                active?.let(controller::run)
            },
        ) { Text(active?.let { "Run '${it.name}'" } ?: "Run") }

        selectableItem(
            selected = false,
            onClick = {
                dismiss()
                controller.showDialog(Dialog.RUN_CONFIGURATIONS)
            },
        ) { Text("Edit Configurations…") }
    }) { Text("Run") }

    submenu(submenu = {
        selectableItem(
            selected = false,
            keybinding = setOf("Ctrl", "Alt", "S"),
            onClick = {
                dismiss()
                controller.showDialog(Dialog.SETTINGS)
            },
        ) { Text("Settings…") }

        passiveItem { MenuSeparator() }

        selectableItem(
            selected = false,
            onClick = {
                dismiss()
                controller.showDialog(Dialog.ABOUT)
            },
        ) { Text("About Quill") }
    }) { Text("Help") }
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
                    Text(
                        text = "Quill could not start",
                        fontSize = Tokens.TitleFontSize,
                        fontWeight = FontWeight.SemiBold,
                        color = LocalShellPalette.current.text,
                    )
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
