package com.neramc.quill.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.neramc.quill.QuillController
import com.neramc.quill.model.FileNode
import com.neramc.quill.model.ToolWindow
import com.neramc.quill.model.WorkspaceState
import com.neramc.quill.ui.icons.IdeIcons
import com.neramc.quill.ui.shell.ToolWindowHeader
import com.neramc.quill.ui.theme.IdeaMetrics
import com.neramc.quill.ui.theme.LocalShellPalette
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer

/**
 * The Project tool window: a lazily expanded file tree rooted at the open directory.
 *
 * Three details make a tree look like IntelliJ's rather than like a list: a file-type icon on every
 * row, vertical indent guides connecting a folder to its children, and a selection that spans the
 * full width of the panel rather than just the label.
 */
@Composable
public fun ProjectTree(controller: QuillController, workspace: WorkspaceState) {
    val shell = LocalShellPalette.current
    val rows = remember(workspace.projectTree) { flatten(workspace.projectTree) }
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize()) {
        ToolWindowHeader(
            title = "Project",
            onHide = { controller.setLeftToolWindow(ToolWindow.PROJECT) },
        )

        if (rows.isEmpty()) {
            EmptyPanelMessage("Nothing to show")
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
                        open = isOpen,
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
private fun TreeRow(node: FileNode, selected: Boolean, open: Boolean, onClick: () -> Unit) {
    val shell = LocalShellPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val background = when {
        selected -> shell.selectionBackground
        hovered -> shell.hoverBackground
        else -> Color.Transparent
    }
    val indent = IdeaMetrics.TreeIndentStep * node.depth

    Row(
        modifier = Modifier.fillMaxWidth().height(IdeaMetrics.TreeRowHeight)
            .background(background)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .indentGuides(node.depth, shell.border)
            .padding(start = 6.dp + indent, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // The disclosure column is always present, so file rows line up with folder rows instead of
        // shifting left by the width of a missing chevron.
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            when {
                node.isDirectory && node.isExpanded -> IdeIcons.ChevronDown(shell.icon, size = 12.dp)
                node.isDirectory -> IdeIcons.ChevronRight(shell.icon, size = 12.dp)
                else -> Spacer(Modifier.size(12.dp))
            }
        }

        if (node.isDirectory) {
            IdeIcons.Folder(shell.icon, size = IdeaMetrics.IconSize)
        } else {
            IdeIcons.MarkdownFile(shell.icon, shell.accent, size = IdeaMetrics.IconSize)
        }

        Text(
            text = node.name,
            fontSize = IdeaMetrics.UiFontSize,
            color = if (open) shell.accent else shell.text,
            maxLines = 1,
        )
    }
}

/**
 * Draws the vertical guide lines that connect a nested row back to its ancestors.
 *
 * IntelliJ draws one line per level at the level's indent position. They are what let the eye follow
 * a deep tree without counting indentation, and their absence is why a plain indented list reads as
 * flat even when it is not.
 */
private fun Modifier.indentGuides(depth: Int, color: Color): Modifier = drawBehind {
    if (depth == 0) return@drawBehind

    val step = IdeaMetrics.TreeIndentStep.toPx()
    val origin = 13.dp.toPx()
    for (level in 0 until depth) {
        val x = origin + (level * step)
        drawLine(
            color = color,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 1f,
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
        ToolWindowHeader(
            title = "Structure",
            onHide = { controller.setRightToolWindow(ToolWindow.STRUCTURE) },
            hidesTowardsLeft = false,
        )

        if (outline.isEmpty()) {
            EmptyPanelMessage(if (document == null) "No document" else "No headings")
            return@Column
        }

        VerticallyScrollableContainer(scrollState = listState, modifier = Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(outline.size, key = { "${outline[it].line}-$it" }) { index ->
                    val entry = outline[index]
                    OutlineRow(
                        level = entry.level,
                        title = entry.title,
                        onClick = { documentId?.let { controller.moveCaret(it, entry.offset) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun OutlineRow(level: Int, title: String, onClick: () -> Unit) {
    val shell = LocalShellPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val depth = (level - 1).coerceAtLeast(0)

    Row(
        modifier = Modifier.fillMaxWidth().height(IdeaMetrics.TreeRowHeight)
            .background(if (hovered) shell.hoverBackground else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .indentGuides(depth, shell.border)
            .padding(start = 8.dp + (IdeaMetrics.TreeIndentStep * depth), end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // The IDE's structure view badges each symbol with its kind; for a document that is the
        // heading level, which is also the only thing that distinguishes two identically-named rows.
        Box(
            modifier = Modifier.width(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("H$level", fontSize = IdeaMetrics.TinyFontSize, color = shell.mutedText, maxLines = 1)
        }
        Text(title, fontSize = IdeaMetrics.UiFontSize, color = shell.text, maxLines = 1)
    }
}

/** The centred, muted placeholder the IDE shows in an empty tool window. */
@Composable
private fun EmptyPanelMessage(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = LocalShellPalette.current.mutedText, fontSize = IdeaMetrics.SmallFontSize)
    }
}
