package dev.starfect.quill.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.starfect.quill.QuillController
import dev.starfect.quill.model.DocumentSession
import dev.starfect.quill.model.ToolWindow
import dev.starfect.quill.model.WorkspaceState
import dev.starfect.quill.ui.icons.IdeIcons
import dev.starfect.quill.ui.theme.IdeaMetrics
import dev.starfect.quill.ui.theme.LocalShellPalette
import java.nio.file.Path
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

/**
 * A tool window stripe: the narrow icon rail down each edge of the window.
 *
 * The New UI replaced the old rotated text labels with icon buttons, and the difference is not
 * cosmetic — a 40dp rail of icons and a rail of sideways words read as two different products. The
 * name survives as the tooltip, which is where the IDE puts it.
 */
@Composable
public fun ToolWindowStripe(
    tools: List<ToolWindow>,
    active: ToolWindow?,
    onSelect: (ToolWindow) -> Unit,
    modifier: Modifier = Modifier,
    bottomTools: List<ToolWindow> = emptyList(),
    bottomActive: ToolWindow? = null,
    onSelectBottom: (ToolWindow) -> Unit = {},
) {
    val shell = LocalShellPalette.current
    Column(
        modifier = modifier.width(IdeaMetrics.StripeWidth).fillMaxHeight()
            .background(shell.toolWindowBackground)
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        tools.forEach { tool ->
            StripeButton(tool, selected = tool == active) { onSelect(tool) }
        }

        if (bottomTools.isEmpty()) return@Column

        // The New UI puts the bottom-docked tool windows at the foot of the left rail rather than
        // giving them a rail of their own, which is what keeps the window's edges to three stripes.
        Spacer(Modifier.weight(1f))
        bottomTools.forEach { tool ->
            StripeButton(tool, selected = tool == bottomActive) { onSelectBottom(tool) }
        }
    }
}

@Composable
private fun StripeButton(tool: ToolWindow, selected: Boolean, onClick: () -> Unit) {
    IdeActionButton(
        onClick = onClick,
        tooltip = tool.label,
        selected = selected,
        size = IdeaMetrics.StripeButtonSize,
    ) { tint ->
        ToolWindowIcon(tool, tint)
    }
}

/** The stripe glyph for a tool window. */
@Composable
public fun ToolWindowIcon(tool: ToolWindow, tint: Color) {
    when (tool) {
        ToolWindow.PROJECT -> IdeIcons.ProjectStripe(tint)
        ToolWindow.STRUCTURE -> IdeIcons.StructureStripe(tint)
        ToolWindow.PROBLEMS -> IdeIcons.ProblemsStripe(tint)
        ToolWindow.NOTIFICATIONS -> IdeIcons.NotificationsStripe(tint)
        ToolWindow.DATABASE -> IdeIcons.DatabaseStripe(tint)
        ToolWindow.TERMINAL -> IdeIcons.TerminalStripe(tint)
    }
}

/**
 * The header above a docked tool window.
 *
 * The title carries a chevron, because in the IDE it opens the view switcher, and it is set in mixed
 * case — the New UI stopped shouting its panel titles, and an all-caps header is one of the clearest
 * tells of a UI copied from the old look. The right end holds the overflow menu and the fold-away
 * button, in that order, as every IDE tool window does.
 */
@Composable
public fun ToolWindowHeader(
    title: String,
    onHide: (() -> Unit)? = null,
    hidesTowardsLeft: Boolean = true,
    actions: @Composable () -> Unit = {},
) {
    val shell = LocalShellPalette.current
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().height(IdeaMetrics.ToolWindowHeaderHeight)
                .padding(start = 12.dp, end = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontSize = IdeaMetrics.SmallFontSize,
                    color = shell.text,
                    maxLines = 1,
                )
                Box(Modifier.padding(start = 4.dp)) { IdeIcons.WidgetChevron(shell.mutedText, size = 9.dp) }
            }

            actions()

            IdeActionButton(onClick = {}, tooltip = "Options", size = 22.dp) { tint ->
                IdeIcons.MoreVertical(tint, size = 14.dp)
            }

            if (onHide != null) {
                IdeActionButton(onClick = onHide, tooltip = "Hide", size = 22.dp) { tint ->
                    IdeIcons.Hide(tint, towardsLeft = hidesTowardsLeft, size = 14.dp)
                }
            }
        }
        Divider(Orientation.Horizontal, color = shell.border)
    }
}

/**
 * The status bar.
 *
 * Its left half is the navigation breadcrumb — project, folders, file, enclosing heading — which is
 * where IntelliJ puts it, not floating above the editor. The right half is the run of widgets the
 * IDE keeps there: caret position, then document facts, then the line separator, encoding and
 * read-only state. Every one of them is a hover target with a tooltip, because in the IDE every one
 * of them is clickable.
 */
@Composable
public fun StatusBar(controller: QuillController, workspace: WorkspaceState) {
    val shell = LocalShellPalette.current
    val document = workspace.activeDocument

    Column {
        Divider(Orientation.Horizontal, color = shell.border)
        Row(
            modifier = Modifier.fillMaxWidth().height(IdeaMetrics.StatusBarHeight)
                .background(shell.statusBarBackground)
                .padding(start = 6.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                val notification = workspace.notification
                val error = document?.loadError

                when {
                    // A message displaces the breadcrumbs while it is showing, the same way the
                    // IDE's status text does, and clicking it dismisses it.
                    error != null -> StatusMessage(error, shell.error, controller::dismissNotification)
                    notification != null -> StatusMessage(notification, shell.text, controller::dismissNotification)
                    else -> Breadcrumbs(controller, workspace)
                }
            }

            if (document != null) {
                val caret = document.caretPosition
                val stats = document.stats

                StatusItem("${caret.line + 1}:${caret.column + 1}", "Go to line and column")
                StatusItem("${stats.words} words", "Words in prose, excluding code and front matter")
                StatusItem(readingTime(stats.readingTimeSeconds), "Estimated reading time at 200 wpm")
                StatusItem("LF", "Line separator")
                StatusItem("UTF-8", "File encoding")
                StatusItem("Markdown", "File type")

                Spacer(Modifier.width(2.dp))
                IdeActionButton(onClick = {}, tooltip = "The file is writable", size = 22.dp) { tint ->
                    IdeIcons.Lock(tint, locked = false)
                }
            }
        }
    }
}

/**
 * The breadcrumb trail along the bottom of the window.
 *
 * The last crumb is the heading the caret currently sits under, which is the Markdown equivalent of
 * the enclosing class and method the IDE shows for code. It is the one part of the trail that
 * changes as you move around a file, and the reason the bar is worth having at all.
 */
@Composable
private fun Breadcrumbs(controller: QuillController, workspace: WorkspaceState) {
    val shell = LocalShellPalette.current
    val document = workspace.activeDocument ?: return
    val crumbs = remember(workspace.projectRoot, document.path, document.outline, document.caretPosition) {
        buildCrumbs(workspace.projectRoot, document)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                Box(Modifier.padding(horizontal = 1.dp)) {
                    IdeIcons.ChevronRight(shell.mutedText, size = 10.dp)
                }
            }

            IdeWidgetButton(onClick = { crumb.onClick(controller) }) {
                when (crumb.kind) {
                    CrumbKind.PROJECT -> IdeIcons.Module(shell.icon, size = 13.dp)
                    CrumbKind.FOLDER -> IdeIcons.Folder(shell.folderIcon, size = 13.dp)
                    CrumbKind.FILE -> IdeIcons.MarkdownFile(shell.icon, shell.accent, size = 13.dp)
                    CrumbKind.HEADING -> Unit
                }
                Text(
                    text = crumb.label,
                    fontSize = IdeaMetrics.TinyFontSize,
                    color = shell.mutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = if (crumb.kind == CrumbKind.HEADING) 0.dp else 4.dp),
                )
            }
        }
    }
}

private enum class CrumbKind { PROJECT, FOLDER, FILE, HEADING }

private class Crumb(val label: String, val kind: CrumbKind, val onClick: (QuillController) -> Unit = {})

/** Builds the trail: project, the folders between it and the file, the file, then the heading. */
private fun buildCrumbs(projectRoot: Path?, document: DocumentSession): List<Crumb> = buildList {
    val path = document.path

    if (projectRoot != null) {
        add(Crumb(projectRoot.fileName?.toString() ?: projectRoot.toString(), CrumbKind.PROJECT))
    }

    if (path != null) {
        val relative = runCatching { projectRoot?.relativize(path) }.getOrNull()
        val parts = (relative ?: path.fileName)?.toList().orEmpty()
        // Everything except the last element is a directory on the way to the file.
        parts.dropLast(1).forEach { part -> add(Crumb(part.toString(), CrumbKind.FOLDER)) }
    }

    add(Crumb(document.displayName, CrumbKind.FILE))

    // The heading the caret is inside: the last one that begins at or before the caret.
    val caretOffset = document.caretPosition.offset
    document.outline.lastOrNull { it.offset <= caretOffset }?.let { entry ->
        add(
            Crumb(entry.title, CrumbKind.HEADING) { controller ->
                controller.moveCaret(document.id, entry.offset)
            }
        )
    }
}

/** A transient message, shown in place of the breadcrumbs. */
@Composable
private fun StatusMessage(message: String, color: Color, onDismiss: () -> Unit) {
    IdeWidgetButton(onClick = onDismiss) {
        Text(message, fontSize = IdeaMetrics.TinyFontSize, color = color, maxLines = 1)
    }
}

/** One status bar widget: a label with a hover fill and a tooltip, as every IDE widget is. */
@Composable
private fun StatusItem(label: String, tooltip: String, modifier: Modifier = Modifier) {
    Tooltip(tooltip = { Text(tooltip) }) {
        IdeWidgetButton(onClick = {}, modifier = modifier) {
            Text(
                label,
                fontSize = IdeaMetrics.TinyFontSize,
                color = LocalShellPalette.current.mutedText,
                maxLines = 1,
            )
        }
    }
}

private fun readingTime(seconds: Int): String = when {
    seconds <= 0 -> "—"
    seconds < 60 -> "${seconds}s read"
    else -> "${seconds / 60}m read"
}
