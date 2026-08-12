package dev.starfect.quill.ui.tools

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.starfect.quill.QuillController
import dev.starfect.quill.model.FileNode
import dev.starfect.quill.model.ToolWindow
import dev.starfect.quill.model.WorkspaceState
import dev.starfect.quill.ui.icons.IdeIcons
import dev.starfect.quill.ui.shell.ToolWindowHeader
import dev.starfect.quill.ui.theme.LocalTypeScale
import dev.starfect.quill.ui.theme.Tokens
import dev.starfect.quill.ui.theme.LocalShellPalette
import java.nio.file.Path
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

        ToolWindowFocusScope(Modifier.fillMaxSize()) {
            VerticallyScrollableContainer(scrollState = listState, modifier = Modifier.fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    // The project root sits at the top of the tree with its location beside it,
                    // exactly as the IDE shows it. It scrolls with the rest rather than being
                    // pinned, which is also what the IDE does.
                    workspace.projectRoot?.let { root ->
                        item(key = "\u0000root") { ProjectRootRow(root) }
                    }

                    items(rows.size, key = { rows[it].path.toString() }) { index ->
                        val node = rows[index]
                        val isOpen = workspace.documents.any { it.path == node.path }
                        FileRow(
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
}

/** The project root: an expanded module icon, the project name, and its abbreviated location. */
@Composable
private fun ProjectRootRow(root: Path) {
    val shell = LocalShellPalette.current
    val home = remember { System.getProperty("user.home").orEmpty() }
    val location = remember(root, home) {
        val absolute = root.toString()
        if (home.isNotEmpty() && absolute.startsWith(home)) "~" + absolute.removePrefix(home) else absolute
    }

    TreeRow(depth = 0, onClick = {}, expandable = true, expanded = true) {
        IdeIcons.Module(shell.sourceFolderIcon, size = Tokens.IconSize)
        TreeLabel(root.fileName?.toString() ?: root.toString())
        // The location is clipped from the right so a deep path never pushes the name out.
        TreeMetadata(location, Modifier.weight(1f))
    }
}

@Composable
private fun FileRow(node: FileNode, selected: Boolean, open: Boolean, onClick: () -> Unit) {
    val shell = LocalShellPalette.current

    TreeRow(
        depth = node.depth,
        onClick = onClick,
        selected = selected,
        expandable = node.isDirectory,
        expanded = node.isExpanded,
    ) {
        // The icon says what the row is. Folders are filled and tinted by role and each file type
        // gets its own glyph; a tree where every row carries the same mark reads as a list.
        when {
            node.isDirectory -> IdeIcons.Folder(
                tint = shell.folderIcon,
                size = Tokens.IconSize,
                open = node.isExpanded,
            )

            node.name.substringAfterLast('.', "").lowercase() in MARKDOWN_EXTENSIONS ->
                IdeIcons.MarkdownFile(shell.icon, shell.accent, size = Tokens.IconSize)

            else -> IdeIcons.PlainFile(shell.mutedIcon, size = Tokens.IconSize)
        }

        // An open file is named in the accent, which is the one place in the tree it appears.
        TreeLabel(node.name, color = if (open) shell.accent else shell.text)
    }
}

/** Extensions the project view marks with the Markdown glyph rather than a plain page. */
private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdx")

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

        // Which heading the caret is under. The IDE keeps the structure view pointed at wherever you
        // are in the document, so the panel answers "where am I" without being clicked — and it is
        // what makes Structure share TreeSelection with the project tree rather than only its shape.
        val caret = document?.caretPosition?.offset ?: 0
        val current = remember(outline, caret) {
            outline.indexOfLast { it.offset <= caret }.takeIf { it >= 0 }
        }

        ToolWindowFocusScope(Modifier.fillMaxSize()) {
            VerticallyScrollableContainer(scrollState = listState, modifier = Modifier.fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(outline.size, key = { "${outline[it].line}-$it" }) { index ->
                        val entry = outline[index]
                        OutlineRow(
                            level = entry.level,
                            title = entry.title,
                            selected = index == current,
                            onClick = { documentId?.let { controller.moveCaret(it, entry.offset) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OutlineRow(level: Int, title: String, selected: Boolean, onClick: () -> Unit) {
    val shell = LocalShellPalette.current

    // A heading's level is its depth, so the outline indents exactly as the document nests.
    TreeRow(depth = (level - 1).coerceAtLeast(0), onClick = onClick, selected = selected) {
        // The structure view badges each symbol with its kind; for a document that is the heading
        // level, which is also the only thing distinguishing two identically-named rows.
        //
        // `widthIn`, not `width`. The badge sits where a file row's icon sits, so it needs the icon
        // column's width as a floor — but the UI font is proportional, and a hard 16dp box clipped
        // "H2" and "H3" to nothing the moment the shell moved to Inter, where the digit 1 is
        // narrower than the rest. Text in a box sized for an icon has to be allowed to grow.
        Box(Modifier.widthIn(min = Tokens.IconSize), contentAlignment = Alignment.Center) {
            TreeMetadata("H$level")
        }
        TreeLabel(title, color = shell.text)
    }
}

/** The centred, muted placeholder the IDE shows in an empty tool window. */
@Composable
private fun EmptyPanelMessage(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = LocalShellPalette.current.mutedText, fontSize = LocalTypeScale.current.medium)
    }
}
