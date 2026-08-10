package com.neramc.quill.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neramc.quill.QuillController
import com.neramc.quill.model.FileNode
import com.neramc.quill.model.WorkspaceState
import com.neramc.quill.ui.shell.ToolWindowHeader
import com.neramc.quill.ui.theme.LocalShellPalette
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer

/** The Project tool window: a lazily expanded file tree rooted at the open directory. */
@Composable
public fun ProjectTree(controller: QuillController, workspace: WorkspaceState) {
    val shell = LocalShellPalette.current
    val rows = remember(workspace.projectTree) { flatten(workspace.projectTree) }
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize()) {
        ToolWindowHeader(workspace.projectRoot?.fileName?.toString() ?: "Project")

        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No files", color = shell.mutedText, fontSize = 12.sp)
            }
            return@Column
        }

        VerticallyScrollableContainer(scrollState = listState, modifier = Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(rows.size, key = { rows[it].path.toString() }) { index ->
                    val node = rows[index]
                    val isOpen = workspace.documents.any { it.path == node.path }
                    TreeRow(
                        node = node,
                        selected = isOpen && workspace.activeDocument?.path == node.path,
                        highlighted = isOpen,
                        onClick = {
                            if (node.isDirectory) controller.toggleDirectory(node.path)
                            else controller.openFile(node.path)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TreeRow(node: FileNode, selected: Boolean, highlighted: Boolean, onClick: () -> Unit) {
    val shell = LocalShellPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().height(22.dp)
            .background(if (selected) shell.selectionBackground else Color.Transparent)
            .clickable(onClick = onClick)
            // Indentation carries the hierarchy, the same way the IDE's project view does it.
            .padding(start = (8 + node.depth * 14).dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when {
                node.isDirectory && node.isExpanded -> "▾"
                node.isDirectory -> "▸"
                else -> " "
            },
            modifier = Modifier.width(14.dp),
            fontSize = 10.sp,
            color = shell.mutedText,
        )
        Text(
            text = node.name,
            fontSize = 12.sp,
            color = if (highlighted) shell.accent else Color.Unspecified,
            fontWeight = if (highlighted) FontWeight.Medium else null,
            maxLines = 1,
        )
    }
}

/** Flattens the expanded parts of the tree into the rows the list renders. */
private fun flatten(nodes: List<FileNode>): List<FileNode> = buildList {
    fun walk(current: List<FileNode>) {
        current.forEach { node ->
            add(node)
            if (node.isDirectory && node.isExpanded) walk(node.children)
        }
    }
    walk(nodes)
}

/** The Structure tool window: the document's heading outline. */
@Composable
public fun OutlinePanel(controller: QuillController, workspace: WorkspaceState) {
    val shell = LocalShellPalette.current
    val document = workspace.activeDocument
    val outline = document?.outline.orEmpty()
    val documentId = document?.id
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize()) {
        ToolWindowHeader("Structure")

        if (outline.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (document == null) "No document" else "No headings",
                    color = shell.mutedText,
                    fontSize = 12.sp,
                )
            }
            return@Column
        }

        VerticallyScrollableContainer(scrollState = listState, modifier = Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(outline.size, key = { "${outline[it].line}-$it" }) { index ->
                    val entry = outline[index]
                    Row(
                        modifier = Modifier.fillMaxWidth().height(22.dp)
                            .clickable { documentId?.let { controller.moveCaret(it, entry.offset) } }
                            .padding(start = (8 + (entry.level - 1) * 12).dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("H${entry.level}", fontSize = 10.sp, color = shell.mutedText)
                        Text(entry.title, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}
