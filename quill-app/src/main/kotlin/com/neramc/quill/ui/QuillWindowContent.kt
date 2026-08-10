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
import com.neramc.quill.ui.editor.SourceEditor
import com.neramc.quill.ui.palette.CommandPalette
import com.neramc.quill.ui.preview.PreviewPane
import com.neramc.quill.ui.shell.StatusBar
import com.neramc.quill.ui.shell.ToolWindowStripe
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
 * The IDE layout below the title bar: tool window stripes on both edges, docked tool windows, the
 * editor area with its tabs, the find bar, and the status bar.
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
                    Divider(Orientation.Horizontal, color = shell.border)
                    EditorArea(controller, workspace, Modifier.weight(1f).fillMaxWidth())
                    if (workspace.find.visible) {
                        Divider(Orientation.Horizontal, color = shell.border)
                        FindReplaceBar(controller, workspace)
                    }
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

            Divider(Orientation.Horizontal, color = shell.border)
            StatusBar(controller, workspace)
        }

        if (workspace.commandPaletteVisible) {
            CommandPalette(controller, workspace)
        }
    }
}

/** Source, preview, or both, depending on the current view mode. */
@Composable
private fun EditorArea(controller: QuillController, workspace: WorkspaceState, modifier: Modifier) {
    val document = workspace.activeDocument
    if (document == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
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

    when (workspace.settings.viewMode) {
        ViewMode.EDITOR -> SourceEditor(controller, workspace, document, modifier)
        ViewMode.PREVIEW -> PreviewPane(controller, workspace, document, modifier)
        ViewMode.SPLIT -> {
            val splitState = rememberSplitLayoutState(0.5f)
            HorizontalSplitLayout(
                first = { SourceEditor(controller, workspace, document, Modifier.fillMaxSize()) },
                second = { PreviewPane(controller, workspace, document, Modifier.fillMaxSize()) },
                modifier = modifier,
                state = splitState,
                firstPaneMinWidth = 220.dp,
                secondPaneMinWidth = 220.dp,
            )
        }
    }
}
