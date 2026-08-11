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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import dev.starfect.quill.ui.theme.Tokens
import dev.starfect.quill.ui.theme.LocalEditorPalette
import dev.starfect.quill.ui.theme.LocalShellPalette
import dev.starfect.quill.ui.theme.ShellDivider
import dev.starfect.quill.ui.tools.FindReplaceBar
import dev.starfect.quill.ui.tools.NotificationsPanel
import dev.starfect.quill.ui.tools.OutlinePanel
import dev.starfect.quill.ui.tools.PlaceholderPanel
import dev.starfect.quill.ui.tools.ProblemsPanel
import dev.starfect.quill.ui.tools.ProjectTree
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.HorizontalSplitLayout
import org.jetbrains.jewel.ui.component.styling.DividerMetrics
import org.jetbrains.jewel.ui.component.styling.DividerStyle
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
                ShellDivider(Orientation.Vertical)

                if (workspace.leftToolWindow == ToolWindow.PROJECT) {
                    Box(Modifier.width(260.dp).fillMaxHeight().background(shell.toolWindowBackground)) {
                        ProjectTree(controller, workspace)
                    }
                    ShellDivider(Orientation.Vertical)
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

                ShellDivider(Orientation.Vertical)
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

    ShellDivider(Orientation.Vertical)
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
                ShellDivider(Orientation.Horizontal)
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
    val shell = LocalShellPalette.current

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

    Column(modifier.background(editor.background)) {
        MarkdownEditorToolbar(controller, workspace)

        // The source and the page it becomes sit directly against each other, divided by the
        // splitter and nothing else. They were floating panes with rounded corners, a border and a
        // gutter; that reads as two cards on a dashboard. A work surface is continuous, and the
        // boundary between two halves of it is a line you can drag, not a frame around each half.
        when (workspace.settings.viewMode) {
            ViewMode.EDITOR ->
                SourceEditor(controller, workspace, document, Modifier.weight(1f).fillMaxWidth())

            ViewMode.PREVIEW ->
                PreviewPane(controller, workspace, document, Modifier.weight(1f).fillMaxWidth())

            ViewMode.SPLIT -> {
                val splitState = rememberSplitLayoutState(0.5f)
                HorizontalSplitLayout(
                    first = { SourceEditor(controller, workspace, document, Modifier.fillMaxSize()) },
                    second = { PreviewPane(controller, workspace, document, Modifier.fillMaxSize()) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    // The splitter is the only thing dividing the two halves, so it takes the same
                    // near-invisible border as every other separator in the shell.
                    dividerStyle = DividerStyle(shell.border, DividerMetrics.defaults()),
                    state = splitState,
                    firstPaneMinWidth = 220.dp,
                    secondPaneMinWidth = 220.dp,
                )
            }
        }
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
            Text(
                text = "Quill",
                color = shell.text,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            // The packaged launcher passes -Dquill.version; a Gradle run does not, and saying so is
            // better than showing a number that would be a guess.
            Text(
                text = System.getProperty("quill.version")?.let { "Version $it" } ?: "Development build",
                color = shell.mutedText,
                fontSize = Tokens.SmallFontSize,
            )
            Box(Modifier.height(6.dp))
            Text(
                "A Markdown editor with a Rust engine.",
                color = shell.mutedText,
                fontSize = Tokens.SmallFontSize,
            )
            Box(Modifier.height(8.dp))
            Text(
                "Runtime: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}",
                color = shell.mutedText,
                fontSize = Tokens.TinyFontSize,
            )
            Text(
                "Renderer: Skia via Compose Multiplatform",
                color = shell.mutedText,
                fontSize = Tokens.TinyFontSize,
            )
        }
    }
}
