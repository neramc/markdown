package com.neramc.quill.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.neramc.quill.QuillController
import com.neramc.quill.model.ToolWindow
import com.neramc.quill.model.ViewMode
import com.neramc.quill.model.WorkspaceState
import com.neramc.quill.ui.editor.EditorTabs
import com.neramc.quill.ui.editor.MarkdownEditorToolbar
import com.neramc.quill.ui.editor.SourceEditor
import com.neramc.quill.ui.palette.CommandPalette
import com.neramc.quill.ui.preview.PreviewPane
import com.neramc.quill.ui.shell.StatusBar
import com.neramc.quill.ui.shell.ToolWindowStripe
import com.neramc.quill.ui.theme.LocalEditorPalette
import com.neramc.quill.ui.theme.LocalShellPalette
import com.neramc.quill.ui.tools.FindReplaceBar
import com.neramc.quill.ui.tools.OutlinePanel
import com.neramc.quill.ui.tools.ProjectTree
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.HorizontalSplitLayout
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.rememberSplitLayoutState

/**
 * The IDE layout below the main toolbar: tool window stripes on both edges, docked tool windows, the
 * editor area with its tabs, and the status bar.
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
                    tools = listOf(ToolWindow.PROJECT),
                    active = workspace.leftToolWindow,
                    onSelect = controller::setLeftToolWindow,
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

                    EditorArea(controller, workspace, Modifier.weight(1f).fillMaxWidth())
                }

                if (workspace.rightToolWindow == ToolWindow.STRUCTURE) {
                    Divider(Orientation.Vertical, color = shell.border)
                    Box(Modifier.width(260.dp).fillMaxHeight().background(shell.toolWindowBackground)) {
                        OutlinePanel(controller, workspace)
                    }
                }
                Divider(Orientation.Vertical, color = shell.border)
                ToolWindowStripe(
                    tools = listOf(ToolWindow.STRUCTURE),
                    active = workspace.rightToolWindow,
                    onSelect = controller::setRightToolWindow,
                )
            }

            StatusBar(controller, workspace)
        }

        if (workspace.commandPaletteVisible) {
            CommandPalette(controller, workspace)
        }
    }
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

    Column(modifier) {
        MarkdownEditorToolbar(controller, workspace)

        when (workspace.settings.viewMode) {
            ViewMode.EDITOR -> SourceEditor(controller, workspace, document, Modifier.weight(1f).fillMaxWidth())
            ViewMode.PREVIEW -> PreviewPane(controller, workspace, document, Modifier.weight(1f).fillMaxWidth())
            ViewMode.SPLIT -> {
                val splitState = rememberSplitLayoutState(0.5f)
                HorizontalSplitLayout(
                    first = { SourceEditor(controller, workspace, document, Modifier.fillMaxSize()) },
                    second = { PreviewPane(controller, workspace, document, Modifier.fillMaxSize()) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = splitState,
                    firstPaneMinWidth = 220.dp,
                    secondPaneMinWidth = 220.dp,
                )
            }
        }
    }
}
