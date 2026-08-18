package dev.starfect.quill.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import dev.starfect.quill.QuillController
import dev.starfect.quill.model.FileNode
import dev.starfect.quill.model.Dock
import dev.starfect.quill.model.ToolWindow
import dev.starfect.quill.model.WorkspaceState
import dev.starfect.quill.ui.icons.IdeIcons
import dev.starfect.quill.ui.shell.ToolWindowHeader
import dev.starfect.quill.ui.theme.LocalTypeScale
import dev.starfect.quill.ui.theme.Tokens
import dev.starfect.quill.ui.theme.LocalShellPalette
import java.nio.file.Path
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import androidx.compose.ui.unit.dp
import dev.starfect.quill.io.FileService
import dev.starfect.quill.ui.shell.IdeActionButton
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.MenuSeparator

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
            alternatives = ToolWindow.on(Dock.LEFT),
            onSelect = controller::setLeftToolWindow,
            actions = { ProjectActions(controller, workspace) },
        )

        if (rows.isEmpty()) {
            EmptyPanelMessage(
                message = if (workspace.projectRoot == null) {
                    "No project is open."
                } else {
                    "This folder has no documents Quill can open."
                },
                actionLabel = "New document",
                onAction = controller::newDocument,
            )
            return@Column
        }

        // Where the arrow keys are, which is not the same thing as which document is open. A tree
        // you can only reach with the pointer is the accessibility guideline's headline failure.
        var cursor by remember(rows) { mutableStateOf(-1) }
        val activate: (FileNode) -> Unit = { node ->
            if (node.isDirectory) controller.toggleDirectory(node.path) else controller.openFile(node.path)
        }

        LaunchedEffect(cursor) {
            if (cursor in rows.indices) listState.animateScrollToItem(cursor)
        }

        ToolWindowFocusScope(
            modifier = Modifier.fillMaxSize(),
            onMove = { step -> cursor = (cursor + step).coerceIn(0, rows.lastIndex.coerceAtLeast(0)) },
            onActivate = { rows.getOrNull(cursor)?.let(activate) },
        ) {
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
                            // The keyboard cursor wins when it is somewhere: it is what Enter will
                            // act on, and a row that says "selected" without being the one Enter
                            // opens is worse than no selection at all.
                            selected = if (cursor >= 0) {
                                index == cursor
                            } else {
                                isOpen && workspace.activeDocument?.path == node.path
                            },
                            open = isOpen,
                            onClick = {
                                cursor = index
                                activate(node)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The Project panel's own actions.
 *
 * Refresh is the one that has to be here: the tree is read when the project opens and never again,
 * so a file created in a terminal is invisible until then. Collapse All earns its place on any
 * project deep enough to need it, which is the same projects where scrolling to find anything is
 * the problem.
 */
@Composable
private fun ProjectActions(controller: QuillController, workspace: WorkspaceState) {
    var open by remember { mutableStateOf(false) }

    IdeActionButton(
        onClick = controller::collapseAllDirectories,
        tooltip = "Collapse All",
        enabled = workspace.projectRoot != null,
        size = Tokens.SmallControlSize,
    ) { tint -> IdeIcons.CollapseAll(tint, size = Tokens.SmallIconSize) }

    Box {
        IdeActionButton(
            onClick = { open = !open },
            tooltip = "Options",
            selected = open,
            size = Tokens.SmallControlSize,
        ) { tint -> IdeIcons.MoreVertical(tint, size = Tokens.SmallIconSize) }

        if (open) {
            PopupMenu(
                onDismissRequest = {
                    open = false
                    true
                },
                horizontalAlignment = Alignment.End,
                modifier = Modifier.width(240.dp),
            ) {
                selectableItem(
                    selected = false,
                    enabled = workspace.projectRoot != null,
                    onClick = {
                        open = false
                        controller.refreshProject()
                    },
                ) { Text("Refresh") }

                selectableItem(
                    selected = false,
                    enabled = workspace.projectRoot != null,
                    onClick = {
                        open = false
                        controller.collapseAllDirectories()
                    },
                ) { Text("Collapse All") }

                passiveItem { MenuSeparator() }

                selectableItem(
                    selected = false,
                    onClick = {
                        open = false
                        FileService.chooseDirectory()?.let(controller::openProject)
                    },
                ) { Text("Open Folder\u2026") }
            }
        }
    }
}

/** The project root: an expanded module icon, the project name, and its abbreviated location. */
@Composable
private fun ProjectRootRow(root: Path) {
    val shell = LocalShellPalette.current
    val home = remember { System.getProperty("user.home").orEmpty() }
    // The *containing* folder, not the project's own path. Showing the full path put the project's
    // name on the row twice — "demo  demo" — because the name is the last segment of it.
    val location = remember(root, home) {
        val parent = root.toAbsolutePath().normalize().parent?.toString().orEmpty()
        when {
            parent.isEmpty() -> ""
            home.isNotEmpty() && parent == home -> "~"
            home.isNotEmpty() && parent.startsWith("$home/") -> "~" + parent.removePrefix(home)
            else -> parent
        }
    }

    // Neither expandable nor clickable: the root is always open, and there is nothing a click on
    // it could do that the header's actions do not already do. A null onClick takes the hover fill
    // away with the action, rather than leaving a row that lights up and then does nothing.
    TreeRow(depth = 0, onClick = null, expandable = false, expanded = true) {
        IdeIcons.Module(shell.sourceFolderIcon, size = Tokens.IconSize)
        TreeLabel(root.fileName?.toString() ?: root.toString())
        // Clipped from the right so a deep path never pushes the name out, and omitted entirely
        // when there is nothing useful to say.
        if (location.isNotEmpty()) TreeMetadata(location, Modifier.weight(1f))
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
            alternatives = ToolWindow.on(Dock.RIGHT),
            onSelect = controller::setRightToolWindow,
        )

        if (outline.isEmpty()) {
            EmptyPanelMessage(
                message = if (document == null) {
                    "No document is open."
                } else {
                    "This document has no headings yet. Structure lists them as you add them."
                },
            )
            return@Column
        }

        // Which heading the caret is under. The IDE keeps the structure view pointed at wherever you
        // are in the document, so the panel answers "where am I" without being clicked — and it is
        // what makes Structure share TreeSelection with the project tree rather than only its shape.
        val caret = document?.caretPosition?.offset ?: 0
        val current = remember(outline, caret) {
            outline.indexOfLast { it.offset <= caret }.takeIf { it >= 0 }
        }

        // Arrow keys walk the outline from wherever the caret currently is, so the first press
        // continues from the reader's position rather than jumping to the top.
        var cursor by remember(outline) { mutableStateOf<Int?>(null) }
        val active = cursor ?: current

        ToolWindowFocusScope(
            modifier = Modifier.fillMaxSize(),
            onMove = { step ->
                cursor = ((active ?: 0) + step).coerceIn(0, outline.lastIndex.coerceAtLeast(0))
            },
            onActivate = {
                outline.getOrNull(active ?: -1)?.let { entry ->
                    documentId?.let { controller.moveCaret(it, entry.offset) }
                }
            },
        ) {
            VerticallyScrollableContainer(scrollState = listState, modifier = Modifier.fillMaxSize()) {
                // Dragging a row moves the whole section -- the heading and everything under it,
                // subsections included. Reordering a long document by its outline is the one
                // rearrangement that is genuinely awkward by hand: cutting exactly the right lines
                // out of the middle of a file and putting them back somewhere else is where
                // documents lose paragraphs.
                // The drag is tracked as a distance rather than by hit-testing the row under the
                // pointer: a LazyColumn recycles its rows, so "which row am I over" is a question
                // with no stable answer, while "how many row-heights have I moved" is exact.
                var dragging by remember(outline) { mutableStateOf<Int?>(null) }
                var dragOffset by remember(outline) { mutableFloatStateOf(0f) }
                val rowHeight = with(LocalDensity.current) { Tokens.TreeRowHeight.toPx() }
                val dropTarget = dragging?.let { from ->
                    (from + Math.round(dragOffset / rowHeight)).coerceIn(0, outline.lastIndex)
                }

                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(outline.size, key = { "${outline[it].line}-$it" }) { index ->
                        val entry = outline[index]
                        OutlineRow(
                            level = entry.level,
                            title = entry.title,
                            selected = index == active,
                            dragging = dragging == index,
                            dropBefore = dropTarget == index && dragging != null && dragging != index,
                            onClick = {
                                cursor = index
                                documentId?.let { controller.moveCaret(it, entry.offset) }
                            },
                            onDragStart = {
                                dragging = index
                                dragOffset = 0f
                            },
                            onDrag = { delta -> dragOffset += delta },
                            onDrop = {
                                val from = dragging
                                val to = dropTarget
                                dragging = null
                                dragOffset = 0f
                                if (from != null && to != null && from != to) controller.moveSection(from, to)
                            },
                            onDragCancel = {
                                dragging = null
                                dragOffset = 0f
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OutlineRow(
    level: Int,
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    dragging: Boolean = false,
    dropBefore: Boolean = false,
    onDragStart: () -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDrop: () -> Unit = {},
    onDragCancel: () -> Unit = {},
) {
    val shell = LocalShellPalette.current
    val accent = shell.accent

    // A heading's level is its depth, so the outline indents exactly as the document nests.
    TreeRow(
        depth = (level - 1).coerceAtLeast(0),
        onClick = onClick,
        selected = selected,
        modifier = Modifier
            // The insertion line is drawn where the section would land, which is the only feedback
            // that says *where* rather than merely that something is being dragged.
            .drawBehind {
                if (dropBefore) {
                    drawRect(
                        color = accent,
                        topLeft = Offset(0f, 0f),
                        size = Size(size.width, 2f),
                    )
                }
            }
            .alpha(if (dragging) 0.5f else 1f)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDrag = { change, delta ->
                        change.consume()
                        onDrag(delta.y)
                    },
                    onDragEnd = { onDrop() },
                    onDragCancel = { onDragCancel() },
                )
            },
    ) {
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

/**
 * The empty state of a tool window.
 *
 * The platform's rule is that an empty tool window gets an empty *state*, not an empty panel — and
 * the difference is whether there is a way out of it. A centred "Nothing to show" tells the reader
 * what they can already see; what they need is the action that would put something there.
 *
 * So: one line saying what is missing, and — where there is something sensible to do about it — a
 * link that does it. Set in the muted step, because an empty panel should not compete with the
 * document.
 */
@Composable
private fun EmptyPanelMessage(
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val shell = LocalShellPalette.current
    val scale = LocalTypeScale.current

    Box(Modifier.fillMaxSize().padding(Tokens.Spacing.XLarge), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.Small),
        ) {
            Text(
                text = message,
                color = shell.mutedText,
                fontSize = scale.medium,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                Link(text = actionLabel, onClick = onAction)
            }
        }
    }
}
