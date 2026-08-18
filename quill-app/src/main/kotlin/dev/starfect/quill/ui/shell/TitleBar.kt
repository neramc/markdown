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
import dev.starfect.quill.export.ExportFormat
import dev.starfect.quill.io.FileService
import dev.starfect.quill.io.GitStatus
import dev.starfect.quill.model.Dialog
import dev.starfect.quill.model.ToolWindow
import dev.starfect.quill.model.ViewMode
import dev.starfect.quill.model.WorkspaceState
import dev.starfect.quill.ui.icons.IdeIcons
import dev.starfect.quill.ui.theme.LocalTypeScale
import dev.starfect.quill.ui.theme.Elevation
import dev.starfect.quill.ui.theme.LocalSurfaceStyle
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
    // The one gradient the platform itself uses: a wash of the project's colour, strongest at the
    // left where the project widget sits and gone by the middle. It is what makes one project's
    // window recognisable in a row of them, and Jewel exposes it directly rather than needing an
    // overlay painted across the title bar.
    val ground = LocalSurfaceStyle.current.windowBackground
    val projectTint = workspace.projectRoot
        ?.fileName?.toString()
        ?.let { Elevation.projectTint(ShellPalette.badgeColor(it), ground) }
        ?: Color.Unspecified

    TitleBar(
        modifier = Modifier.fillMaxWidth().height(Tokens.ToolbarHeight),
        gradientStartColor = projectTint,
    ) {
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
            ProjectWidget(controller, workspace)
            BranchWidget(workspace)
            NavigationButtons(controller, workspace)
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
        ProjectWidget(controller, workspace)
        BranchWidget(workspace)
        NavigationButtons(controller, workspace)

        Box(Modifier.weight(1f))

        TitleBarActions(controller, workspace)
    }
    ShellDivider(Orientation.Horizontal)
}

/** Search Everywhere and Settings, the two actions the New UI keeps at the toolbar's right end. */
@Composable
private fun TitleBarActions(controller: QuillController, workspace: WorkspaceState) {
    // The run cluster, then a rule, then the window-wide actions. The rule is what stops the run
    // triangle from reading as part of the same group as Search: one acts on the document, the
    // others act on the application.
    RunWidget(controller, workspace)

    val active = workspace.activeRunConfiguration
    IdeActionButton(
        onClick = { active?.let(controller::run) ?: controller.showDialog(Dialog.RUN_CONFIGURATIONS) },
        tooltip = active?.let { "Run '${it.name}'" } ?: "Add a run configuration",
        shortcut = active?.let { "Shift+F10" },
        enabled = workspace.activeDocument != null,
    ) { tint ->
        // The run triangle is green when there is something to run and takes the ordinary icon
        // tint when there is not, which is how the IDE distinguishes an armed action from an empty one.
        IdeIcons.Run(if (active != null) LocalShellPalette.current.success else tint)
    }

    MoreActionsButton(controller, workspace)

    Box(Modifier.padding(horizontal = Tokens.Spacing.Tiny)) {
        ShellDivider(Orientation.Vertical, Modifier.height(Tokens.IconSize))
    }

    IdeActionButton(
        onClick = { controller.setCommandPaletteVisible(true) },
        tooltip = "Search Everywhere",
        shortcut = "Ctrl+Shift+P",
        selected = workspace.commandPaletteVisible,
    ) { tint -> IdeIcons.Search(tint) }

    IdeActionButton(
        onClick = { controller.showDialog(Dialog.SETTINGS) },
        tooltip = "Settings",
        shortcut = "Ctrl+Alt+S",
        selected = workspace.dialog == Dialog.SETTINGS,
    ) { tint -> IdeIcons.Gear(tint) }
}

/**
 * Back and forward, over the places the reader has been.
 *
 * Disabled rather than hidden at the ends of the history, because a pair of arrows that appears and
 * disappears moves everything beside it — and these sit next to the run controls, which must not
 * wander. The tooltip names the destination, which is the difference between an arrow you can aim
 * and one you press to find out.
 */
@Composable
private fun NavigationButtons(controller: QuillController, workspace: WorkspaceState) {
    val history = workspace.navigation

    IdeActionButton(
        onClick = controller::navigateBack,
        tooltip = history.previous?.let { "Back to ${it.label}" } ?: "Back",
        shortcut = "Ctrl+Alt+Left",
        enabled = history.canGoBack,
    ) { tint -> IdeIcons.NavigateBack(tint) }

    IdeActionButton(
        onClick = controller::navigateForward,
        tooltip = history.next?.let { "Forward to ${it.label}" } ?: "Forward",
        shortcut = "Ctrl+Alt+Right",
        enabled = history.canGoForward,
    ) { tint -> IdeIcons.NavigateForward(tint) }
}

/**
 * The run-configuration widget: the selected configuration's name, or an invitation to make one.
 *
 * Empty is the state that matters. A toolbar that shows a disabled dropdown with nothing in it
 * tells the reader they are missing something without saying what; "Add Configuration" says what to
 * press. That is what the IDE does with an unconfigured project, and it is the state Quill is in
 * the first time it opens.
 */
@Composable
private fun RunWidget(controller: QuillController, workspace: WorkspaceState) {
    val shell = LocalShellPalette.current
    val active = workspace.activeRunConfiguration
    var open by remember { mutableStateOf(false) }

    Box {
        IdeWidgetButton(onClick = { open = !open }, selected = open) {
            Text(
                text = active?.name ?: "Add Configuration",
                fontSize = LocalTypeScale.current.default,
                color = if (active != null) shell.text else shell.secondaryText,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
            Box(Modifier.padding(start = 4.dp)) { IdeIcons.WidgetChevron(shell.mutedText) }
        }

        if (open) {
            PopupMenu(
                onDismissRequest = {
                    open = false
                    true
                },
                horizontalAlignment = Alignment.Start,
                modifier = Modifier.width(280.dp),
            ) {
                workspace.runConfigurations.forEach { configuration ->
                    selectableItem(
                        selected = configuration.id == active?.id,
                        onClick = {
                            open = false
                            controller.selectRunConfiguration(configuration.id)
                        },
                    ) { Text(configuration.name) }
                }

                if (workspace.runConfigurations.isNotEmpty()) {
                    passiveItem { MenuSeparator() }
                }

                selectableItem(
                    selected = false,
                    onClick = {
                        open = false
                        controller.showDialog(Dialog.RUN_CONFIGURATIONS)
                    },
                ) { Text("Edit Configurations\u2026") }
            }
        }
    }
}

/**
 * The overflow menu at the end of the toolbar.
 *
 * Everything here has a home elsewhere — the main menu, a shortcut, the status bar. It exists
 * because the actions people reach for most often should be one press from the toolbar, and the
 * toolbar has room for about six.
 */
@Composable
private fun MoreActionsButton(controller: QuillController, workspace: WorkspaceState) {
    var open by remember { mutableStateOf(false) }

    Box {
        IdeActionButton(
            onClick = { open = !open },
            tooltip = "More Actions",
            selected = open,
        ) { tint -> IdeIcons.MoreVertical(tint) }

        if (open) {
            PopupMenu(
                onDismissRequest = {
                    open = false
                    true
                },
                horizontalAlignment = Alignment.End,
                modifier = Modifier.width(280.dp),
            ) {
                val activeId = workspace.activeDocumentId

                selectableItem(
                    selected = false,
                    keybinding = setOf("Ctrl", "G"),
                    enabled = activeId != null,
                    onClick = {
                        open = false
                        controller.showDialog(Dialog.GO_TO_LINE)
                    },
                ) { Text("Go to Line\u2026") }

                selectableItem(
                    selected = false,
                    enabled = workspace.activeDocument?.path != null,
                    onClick = {
                        open = false
                        activeId?.let(controller::reloadFromDisk)
                    },
                ) { Text("Reload from Disk") }

                passiveItem { MenuSeparator() }

                selectableItem(
                    selected = workspace.settings.focusMode,
                    keybinding = setOf("Ctrl", "Shift", "D"),
                    onClick = {
                        open = false
                        controller.toggleFocusMode()
                    },
                ) { Text("Focus Mode") }

                selectableItem(
                    selected = workspace.settings.darkTheme,
                    onClick = {
                        open = false
                        controller.toggleTheme()
                    },
                ) { Text("Dark Theme") }

                passiveItem { MenuSeparator() }

                selectableItem(
                    selected = false,
                    onClick = {
                        open = false
                        controller.showDialog(Dialog.RUN_CONFIGURATIONS)
                    },
                ) { Text("Edit Configurations\u2026") }

                selectableItem(
                    selected = false,
                    keybinding = setOf("Ctrl", "Alt", "S"),
                    onClick = {
                        open = false
                        controller.showDialog(Dialog.SETTINGS)
                    },
                ) { Text("Settings\u2026") }
            }
        }
    }
}

/**
 * The project widget: a coloured avatar carrying the project's initial, its name, and a chevron.
 *
 * The badge is the detail that makes this widget recognisable at a glance — IntelliJ gives every
 * project a deterministic colour so the toolbar tells you which window you are in before you have
 * read anything.
 */
@Composable
private fun ProjectWidget(controller: QuillController, workspace: WorkspaceState) {
    val shell = LocalShellPalette.current
    val project = workspace.projectRoot?.fileName?.toString() ?: "quill"
    var open by remember { mutableStateOf(false) }

    Box {
    IdeWidgetButton(onClick = { open = !open }, selected = open) {
        Box(
            modifier = Modifier.size(Tokens.ProjectBadgeSize)
                .clip(RoundedCornerShape(Tokens.ProjectBadgeCorner))
                .background(ShellPalette.badgeColor(project)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = project.firstOrNull()?.uppercase() ?: "Q",
                fontSize = LocalTypeScale.current.medium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
            )
        }

        Text(
            text = project,
            fontSize = LocalTypeScale.current.default,
            color = shell.text,
            maxLines = 1,
            modifier = Modifier.padding(start = Tokens.Spacing.Tiny),
        )
        // The chevron is what marks this as a widget rather than a caption; the IDE draws one on
        // every toolbar widget that can be opened. It was drawing one over a button that opened
        // nothing, which is the chevron telling a lie.
        Box(Modifier.padding(start = 4.dp)) { IdeIcons.WidgetChevron(shell.mutedText) }
    }

        if (open) {
            PopupMenu(
                onDismissRequest = {
                    open = false
                    true
                },
                horizontalAlignment = Alignment.Start,
                modifier = Modifier.width(280.dp),
            ) {
                projectMenu(controller, workspace) { open = false }
            }
        }
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

    // An indicator. Quill has no branch operations to offer, and a widget that highlights under
    // the pointer and then does nothing is how an application gets described as broken.
    IdeWidgetButton(onClick = null) {
        IdeIcons.Branch(shell.icon, size = Tokens.IconSize)
        Text(
            text = branch,
            fontSize = LocalTypeScale.current.default,
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
                activeId?.let(controller::saveWithPrompt)
            },
        ) { Text("Save") }

        selectableItem(
            selected = false,
            keybinding = setOf("Ctrl", "Shift", "S"),
            enabled = activeId != null,
            onClick = {
                dismiss()
                activeId?.let(controller::saveAs)
            },
        ) { Text("Save As\u2026") }

        passiveItem { MenuSeparator() }

        // One submenu rather than seven top-level items, and each entry says what the format is
        // for: the choice is "who is this going to", not "which extension do I want".
        submenu(
            enabled = activeId != null,
            submenu = {
                ExportFormat.entries.forEach { format ->
                    selectableItem(
                        selected = false,
                        onClick = {
                            dismiss()
                            if (activeId != null) {
                                val stem = workspace.activeDocument?.displayName
                                    ?.substringBeforeLast('.').orEmpty()
                                controller.export(
                                    activeId,
                                    format,
                                    FileService().exportTarget(
                                        workspace.activeDocument?.path,
                                        format.fileNameFor(stem.ifEmpty { "document" }),
                                    ),
                                )
                            }
                        },
                    ) { Text(format.label) }
                }
            },
        ) { Text("Export") }

        passiveItem { MenuSeparator() }

        selectableItem(
            selected = false,
            keybinding = setOf("Ctrl", "W"),
            enabled = activeId != null,
            onClick = {
                dismiss()
                activeId?.let(controller::requestCloseDocument)
            },
        ) { Text("Close Document") }

        selectableItem(
            selected = false,
            onClick = {
                dismiss()
                // Same guard as the window's X. Two ways out of the application, one rule about
                // unsaved work.
                if (controller.requestExit()) onExit()
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

        selectableItem(
            selected = false,
            onClick = {
                dismiss()
                controller.showDialog(Dialog.UPDATE)
            },
        ) { Text("Check for Updates…") }

        passiveItem { MenuSeparator() }

        // Under Help rather than hidden in Settings, and phrased as what it does. An application
        // that can be removed from inside itself is the reason there is no separate uninstaller in
        // the install folder or in the release.
        selectableItem(
            selected = false,
            onClick = {
                dismiss()
                controller.showDialog(Dialog.UNINSTALL)
            },
        ) { Text("Uninstall Quill…") }

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
                        fontSize = LocalTypeScale.current.h2,
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


/**
 * What the project widget opens.
 *
 * The three things a reader wants from the name of the project they are in: somewhere else, the
 * place it lives, and a way to see the whole of it. Recent projects are the first of those and are
 * listed by name with their path beside them, because two checkouts of the same repository have the
 * same name and the path is the only thing that tells them apart.
 */
private fun MenuScope.projectMenu(
    controller: QuillController,
    workspace: WorkspaceState,
    dismiss: () -> Unit,
) {
    val root = workspace.projectRoot

    selectableItem(
        selected = false,
        onClick = {
            dismiss()
            FileService.chooseDirectory()?.let(controller::openProject)
        },
    ) { Text("Open Folder…") }

    selectableItem(
        selected = false,
        enabled = root != null,
        onClick = {
            dismiss()
            root?.let(controller::revealInProject)
        },
    ) { Text("Show in Project View") }

    if (root != null) {
        passiveItem { MenuSeparator() }
        selectableItem(
            selected = false,
            onClick = {
                dismiss()
                java.awt.Toolkit.getDefaultToolkit().systemClipboard
                    .setContents(java.awt.datatransfer.StringSelection(root.toString()), null)
            },
        ) { Text("Copy Path") }
    }
}
