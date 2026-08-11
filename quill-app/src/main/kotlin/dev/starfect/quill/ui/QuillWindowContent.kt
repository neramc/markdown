package dev.starfect.quill.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.starfect.quill.QuillController
import dev.starfect.quill.model.Dialog
import dev.starfect.quill.model.Dock
import dev.starfect.quill.model.ToolWindow
import dev.starfect.quill.model.ViewMode
import dev.starfect.quill.model.WorkspaceState
import dev.starfect.quill.ui.dialog.RunConfigurationsDialog
import dev.starfect.quill.ui.dialog.SettingsDialog
import dev.starfect.quill.ui.editor.EditorTabs
import dev.starfect.quill.ui.editor.MarkdownEditorToolbar
import dev.starfect.quill.ui.editor.SourceEditor
import dev.starfect.quill.ui.palette.CommandPalette
import dev.starfect.quill.ui.preview.PreviewPane
import dev.starfect.quill.ui.shell.StatusBar
import dev.starfect.quill.ui.shell.ToolWindowStripe
import dev.starfect.quill.ui.theme.IdeaMetrics
import dev.starfect.quill.ui.theme.LocalEditorPalette
import dev.starfect.quill.ui.theme.LocalShellPalette
import dev.starfect.quill.ui.tools.FindReplaceBar
import dev.starfect.quill.ui.tools.NotificationsPanel
import dev.starfect.quill.ui.tools.OutlinePanel
import dev.starfect.quill.ui.tools.PlaceholderPanel
import dev.starfect.quill.ui.tools.ProblemsPanel
import dev.starfect.quill.ui.tools.ProjectTree
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.HorizontalSplitLayout
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticalSplitLayout
import org.jetbrains.jewel.ui.component.rememberSplitLayoutState

/**
 * The IDE layout below the main toolbar: tool window stripes on three edges, docked tool windows,
 * the editor area with its tabs, and the status bar.
 */
@Composable
public fun QuillWindowContent(
    controller: QuillController,
    workspace: WorkspaceState,
    modifier: Modifier = Modifier,
) {
    val shell = LocalShellPalette.current

    Box(modifier) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                ToolWindowStripe(
                    tools = ToolWindow.on(Dock.LEFT),
                    active = workspace.leftToolWindow,
                    onSelect = controller::setLeftToolWindow,
                    bottomTools = ToolWindow.on(Dock.BOTTOM),
                    bottomActive = workspace.bottomToolWindow,
                    onSelectBottom = controller::setBottomToolWindow,
                )
                Divider(Orientation.Vertical, color = shell.border)

                if (workspace.leftToolWindow == ToolWindow.PROJECT) {
                    Box(Modifier.width(260.dp).fillMaxHeight().background(shell.toolWindowBackground)) {
                        ProjectTree(controller, workspace)
                    }
                    Divider(Orientation.Vertical, color = shell.border)
                }

                Column(Modifier.weight(1f).fillMaxHeight()) {
                    EditorTabs(controller, workspace)

                    // The find bar sits directly under the tabs and above the document, which is
                    // where IntelliJ docks it. A find bar at the bottom of the window is a text
                    // editor's convention, not an IDE's.
                    if (workspace.find.visible) {
                        FindReplaceBar(controller, workspace)
                    }

                    CentreArea(controller, workspace, Modifier.weight(1f).fillMaxWidth())
                }

                RightDock(controller, workspace)

                Divider(Orientation.Vertical, color = shell.border)
                ToolWindowStripe(
                    tools = ToolWindow.on(Dock.RIGHT),
                    active = workspace.rightToolWindow,
                    onSelect = controller::setRightToolWindow,
                )
            }

            StatusBar(controller, workspace)
        }

        if (workspace.commandPaletteVisible) {
            CommandPalette(controller, workspace)
        }

        when (workspace.dialog) {
            Dialog.SETTINGS -> SettingsDialog(controller, workspace)
            Dialog.RUN_CONFIGURATIONS -> RunConfigurationsDialog(controller, workspace)
            Dialog.ABOUT -> AboutDialog(controller)
            null -> Unit
        }
    }
}

/** The right dock's panel, which is whichever of its tool windows is open. */
@Composable
private fun RightDock(controller: QuillController, workspace: WorkspaceState) {
    val tool = workspace.rightToolWindow ?: return
    val shell = LocalShellPalette.current

    Divider(Orientation.Vertical, color = shell.border)
    Box(Modifier.width(280.dp).fillMaxHeight().background(shell.toolWindowBackground)) {
        when (tool) {
            ToolWindow.STRUCTURE -> OutlinePanel(controller, workspace)
            ToolWindow.NOTIFICATIONS -> NotificationsPanel(controller, workspace)
            else -> PlaceholderPanel(tool, onHide = { controller.setRightToolWindow(null) })
        }
    }
}

/**
 * The editor area, with the bottom dock below it when one is open.
 *
 * The bottom dock is a split rather than a fixed strip so the user can trade editor height for
 * problem-list height, which is the whole reason the IDE's is draggable.
 */
@Composable
private fun CentreArea(controller: QuillController, workspace: WorkspaceState, modifier: Modifier) {
    val bottom = workspace.bottomToolWindow
    val shell = LocalShellPalette.current

    if (bottom == null) {
        EditorArea(controller, workspace, modifier)
        return
    }

    val splitState = rememberSplitLayoutState(0.72f)
    VerticalSplitLayout(
        first = { EditorArea(controller, workspace, Modifier.fillMaxSize()) },
        second = {
            Column(Modifier.fillMaxSize()) {
                Divider(Orientation.Horizontal, color = shell.border)
                when (bottom) {
                    ToolWindow.PROBLEMS -> ProblemsPanel(controller, workspace)
                    else -> PlaceholderPanel(bottom, onHide = { controller.setBottomToolWindow(null) })
                }
            }
        },
        modifier = modifier,
        state = splitState,
        firstPaneMinWidth = 120.dp,
        secondPaneMinWidth = 90.dp,
    )
}

/** Source, preview, or both, under the Markdown editor's own toolbar. */
@Composable
private fun EditorArea(controller: QuillController, workspace: WorkspaceState, modifier: Modifier) {
    val document = workspace.activeDocument
    val editor = LocalEditorPalette.current

    if (document == null) {
        Box(modifier.background(editor.background), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("No document open", color = LocalShellPalette.current.mutedText)
                Text("Ctrl+N creates one", color = LocalShellPalette.current.mutedText)
            }
        }
        return
    }

    Column(modifier.background(LocalShellPalette.current.toolWindowBackground)) {
        MarkdownEditorToolbar(controller, workspace)

        // The panes float on the panel colour with a gutter between them, rather than butting
        // against a shared divider. It is what makes the split read as two documents — the source
        // you are writing and the page it becomes — instead of one region with a line through it.
        Row(
            Modifier.weight(1f).fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (workspace.settings.viewMode) {
                ViewMode.EDITOR -> Pane(Modifier.weight(1f)) {
                    SourceEditor(controller, workspace, document, Modifier.fillMaxSize())
                }

                ViewMode.PREVIEW -> Pane(Modifier.weight(1f)) {
                    PreviewPane(controller, workspace, document, Modifier.fillMaxSize())
                }

                ViewMode.SPLIT -> {
                    val splitState = rememberSplitLayoutState(0.5f)
                    HorizontalSplitLayout(
                        first = {
                            Pane(Modifier.fillMaxSize().padding(end = 3.dp)) {
                                SourceEditor(controller, workspace, document, Modifier.fillMaxSize())
                            }
                        },
                        second = {
                            Pane(Modifier.fillMaxSize().padding(start = 3.dp)) {
                                PreviewPane(controller, workspace, document, Modifier.fillMaxSize())
                            }
                        },
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        state = splitState,
                        firstPaneMinWidth = 220.dp,
                        secondPaneMinWidth = 220.dp,
                    )
                }
            }
        }
    }
}

/** One rounded, bordered pane of the editor area. */
@Composable
private fun Pane(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shell = LocalShellPalette.current

    Box(
        modifier
            .clip(RoundedCornerShape(IdeaMetrics.PaneCorner))
            .border(1.dp, shell.border, RoundedCornerShape(IdeaMetrics.PaneCorner))
    ) {
        content()
    }
}

/** The About box, which is the one dialog with nothing to configure. */
@Composable
private fun AboutDialog(controller: QuillController) {
    val shell = LocalShellPalette.current

    dev.starfect.quill.ui.dialog.IdeDialog(
        title = "About Quill",
        onDismiss = controller::dismissDialog,
        width = 420.dp,
        height = 240.dp,
        confirmLabel = "Close",
    ) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Quill", color = shell.text, fontSize = androidx.compose.ui.unit.TextUnit.Unspecified)
            Text(
                "A Markdown editor with a Rust engine.",
                color = shell.mutedText,
                fontSize = IdeaMetrics.SmallFontSize,
            )
            Box(Modifier.height(8.dp))
            Text(
                "Runtime: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}",
                color = shell.mutedText,
                fontSize = IdeaMetrics.TinyFontSize,
            )
            Text(
                "Renderer: Skia via Compose Multiplatform",
                color = shell.mutedText,
                fontSize = IdeaMetrics.TinyFontSize,
            )
        }
    }
}
