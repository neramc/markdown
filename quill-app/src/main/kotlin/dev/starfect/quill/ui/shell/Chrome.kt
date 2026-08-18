package dev.starfect.quill.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.starfect.quill.QuillController
import dev.starfect.quill.model.Dialog
import dev.starfect.quill.model.DocumentSession
import dev.starfect.quill.model.ToolWindow
import dev.starfect.quill.model.WorkspaceState
import dev.starfect.quill.ui.icons.IdeIcons
import dev.starfect.quill.ui.theme.LocalTypeScale
import dev.starfect.quill.ui.theme.Tokens
import dev.starfect.quill.ui.theme.LocalSurfaceStyle
import dev.starfect.quill.ui.theme.LocalShellPalette
import dev.starfect.quill.ui.theme.ShellDivider
import java.nio.file.Path
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

/**
 * A tool window stripe: the narrow icon rail down each edge of the window.
 *
 * A rail of icon buttons rather than rotated text labels, at 32dp wide with 32dp square buttons, so
 * a button fills the rail edge to edge. The name survives as the tooltip.
 *
 * This is secondary navigation and is drawn like it: no enlarged icons, no filled background, and
 * only the selected state carries any weight at all.
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
    /** Counts to show on a stripe button, keyed by tool window. */
    badges: Map<ToolWindow, StripeCount> = emptyMap(),
) {
    val shell = LocalShellPalette.current
    Column(
        modifier = modifier.width(Tokens.ToolWindowBarWidth).fillMaxHeight()
            // The rail is chrome, not a region: in Islands it stays on the window ground so the
            // panels beside it read as floating above it.
            .background(if (LocalSurfaceStyle.current.separated) Color.Transparent else shell.toolWindowBackground)
            .padding(vertical = Tokens.Spacing.Tiny),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        tools.forEach { tool ->
            StripeButton(tool, selected = tool == active, badge = badges[tool]) { onSelect(tool) }
        }

        if (bottomTools.isEmpty()) return@Column

        // The New UI puts the bottom-docked tool windows at the foot of the left rail rather than
        // giving them a rail of their own, which is what keeps the window's edges to three stripes.
        Spacer(Modifier.weight(1f))
        bottomTools.forEach { tool ->
            StripeButton(tool, selected = tool == bottomActive, badge = badges[tool]) { onSelectBottom(tool) }
        }
    }
}

@Composable
private fun StripeButton(tool: ToolWindow, selected: Boolean, badge: StripeCount?, onClick: () -> Unit) {
    IdeActionButton(
        onClick = onClick,
        tooltip = tool.label,
        selected = selected,
        size = Tokens.ToolWindowBarButton,
    ) { tint ->
        Box(contentAlignment = Alignment.Center) {
            ToolWindowIcon(tool, tint)
            // A count rather than a different icon. The platform's rule for a tool window with
            // something waiting in it is a badge, because swapping the glyph costs the reader the
            // one thing they had learned to aim at.
            if (badge != null && badge.count > 0) StripeBadge(badge.count, badge.severe)
        }
    }
}

/**
 * The count on a stripe button.
 *
 * Sits on the icon's top-right corner, tucked inside the button rather than hanging off it. The
 * first attempt used the accent at 14dp with body-sized text, which on a 20dp glyph came out nearly
 * as large as the icon and read as a notification bubble rather than as a count.
 *
 * Coloured by what it is counting, not by "something is new": red when anything is an error, amber
 * otherwise. A badge that is always accent-blue tells the reader there is a number; one that is red
 * tells them why they should look.
 *
 * Above nine it becomes "9+". The badge's job is to say there is something there, and three digits
 * inside a 20dp icon say nothing legibly.
 */
@Composable
private fun StripeBadge(count: Int, severe: Boolean) {
    val shell = LocalShellPalette.current

    Box(
        modifier = Modifier.offset(x = BadgeOffset, y = -BadgeOffset)
            .defaultMinSize(minWidth = BadgeSize, minHeight = BadgeSize)
            .clip(CircleShape)
            .background(if (severe) shell.error else shell.warning)
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 9) "9+" else count.toString(),
            // Not on the UI type scale on purpose: this is a graphic label sized to a glyph, and the
            // scale's smallest step is body text that would not fit inside it.
            fontSize = BadgeFontSize,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            lineHeight = BadgeFontSize,
            maxLines = 1,
        )
    }
}

private val BadgeSize = 12.dp
private val BadgeOffset = 6.dp
private val BadgeFontSize = 9.sp

/** What a stripe button's badge should say, and how loudly. */
public data class StripeCount(val count: Int, val severe: Boolean)

/** The stripe glyph for a tool window, at the platform's 20dp New UI size. */
@Composable
public fun ToolWindowIcon(tool: ToolWindow, tint: Color, size: Dp = Tokens.ToolWindowIconSize) {
    when (tool) {
        ToolWindow.PROJECT -> IdeIcons.ProjectStripe(tint, size = size)
        ToolWindow.STRUCTURE -> IdeIcons.StructureStripe(tint, size = size)
        ToolWindow.PROBLEMS -> IdeIcons.ProblemsStripe(tint, size = size)
        ToolWindow.NOTIFICATIONS -> IdeIcons.NotificationsStripe(tint, size = size)
        ToolWindow.DATABASE -> IdeIcons.DatabaseStripe(tint, size = size)
        ToolWindow.TERMINAL -> IdeIcons.TerminalStripe(tint, size = size)
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
            modifier = Modifier.fillMaxWidth().height(Tokens.ToolWindowHeaderHeight)
                .padding(start = Tokens.Spacing.Medium, end = Tokens.Spacing.Tiny),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                // A tool window header is the default size in semibold, not a larger size. That is
                // what keeps it from reading as a heading in a document: it matches the rows beneath
                // it and differs only in weight.
                Text(
                    text = title,
                    fontSize = LocalTypeScale.current.default,
                    fontWeight = LocalTypeScale.current.headerWeight,
                    color = shell.text,
                    maxLines = 1,
                )
                Box(Modifier.padding(start = Tokens.Spacing.Tiny)) {
                    IdeIcons.WidgetChevron(shell.mutedIcon, size = Tokens.SmallIconSize)
                }
            }

            actions()

            if (onHide != null) {
                IdeActionButton(onClick = onHide, tooltip = "Hide", size = Tokens.SmallControlSize) { tint ->
                    IdeIcons.Hide(tint, towardsLeft = hidesTowardsLeft, size = Tokens.SmallIconSize)
                }
            }
        }
        ShellDivider(Orientation.Horizontal)
    }
}

/**
 * The status bar.
 *
 * Context on the left — project, folders, file, enclosing heading — and status on the right: caret
 * position, document facts, line separator, encoding, read-only state. Both halves are set in the
 * metadata size and the muted colour, because this is not the part of the window anyone is meant to
 * be looking at. It is information to read when it is wanted, and quiet the rest of the time.
 */
@Composable
public fun StatusBar(controller: QuillController, workspace: WorkspaceState) {
    val shell = LocalShellPalette.current
    val document = workspace.activeDocument

    val surfaces = LocalSurfaceStyle.current

    Column {
        if (!surfaces.separated) ShellDivider(Orientation.Horizontal)
        Row(
            modifier = Modifier.fillMaxWidth().height(Tokens.StatusBarHeight)
                .background(if (surfaces.separated) surfaces.windowBackground else shell.statusBarBackground)
                .padding(start = Tokens.Spacing.Small, end = Tokens.Spacing.Small),
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

                // Vim's mode is the leftmost thing in the status bar and the widest, because it
                // is the one piece of state that changes what every other key on the keyboard does.
                if (workspace.settings.vimMode) {
                    StatusItem(workspace.vim.display, "Vim mode -- Esc returns to normal")
                }

                // What Quill is doing, where every JetBrains window puts it: the left end of the
                // right-hand status group.
                TaskProgress(controller, workspace.tasks)

                // A file that changed underneath an edited buffer is the one status the writer has
                // to act on, so it sits first and offers the two ways out rather than only saying
                // that something is wrong.
                if (document?.conflictsWithDisk == true) {
                    StatusItem(
                        "Changed on disk",
                        "This file changed outside Quill and you have unsaved edits. " +
                            "Click to discard yours and reload.",
                        onClick = { controller.reloadFromDisk(document.id) },
                    )
                }

                StatusItem(
                    "${caret.line + 1}:${caret.column + 1}",
                    "Go to line and column",
                    onClick = { controller.showDialog(Dialog.GO_TO_LINE) },
                )
                StatusItem("${stats.words} words", "Words in prose, excluding code and front matter")
                StatusItem(readingTime(stats.readingTimeSeconds), "Estimated reading time at 200 wpm")
                StatusItem("LF", "Line separator")
                StatusItem("UTF-8", "File encoding")
                StatusItem("Markdown", "File type")

                // An indicator, not a control. Quill has no read-only mode to toggle into, and a
                // padlock that does nothing when pressed is worse than a padlock that plainly is
                // not a button.
                Tooltip(tooltip = { Text("The file is writable") }) {
                    IdeWidgetButton(onClick = null) {
                        IdeIcons.Lock(
                            LocalShellPalette.current.mutedIcon,
                            locked = false,
                            size = Tokens.SmallIconSize,
                        )
                    }
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
                    IdeIcons.ChevronRight(shell.mutedIcon, size = Tokens.SmallIconSize)
                }
            }

            IdeWidgetButton(onClick = { crumb.onClick(controller) }) {
                when (crumb.kind) {
                    CrumbKind.PROJECT -> IdeIcons.Module(shell.mutedIcon, size = Tokens.SmallIconSize)
                    CrumbKind.FOLDER -> IdeIcons.Folder(shell.mutedIcon, size = Tokens.SmallIconSize)
                    CrumbKind.FILE -> IdeIcons.MarkdownFile(shell.mutedIcon, shell.accent, size = Tokens.SmallIconSize)
                    CrumbKind.HEADING -> Unit
                }
                Text(
                    text = crumb.label,
                    fontSize = LocalTypeScale.current.medium,
                    color = shell.mutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(
                        start = if (crumb.kind == CrumbKind.HEADING) 0.dp else Tokens.Spacing.Tiny,
                    ),
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
        Text(message, fontSize = LocalTypeScale.current.medium, color = color, maxLines = 1)
    }
}

/**
 * One status bar widget.
 *
 * [onClick] is null for the ones that only report — the encoding, the line ending, the file type.
 * They used to be buttons with a hover fill, which is a promise the status bar was making four
 * times over and keeping none of; a reader who clicks "UTF-8" expecting an encoding menu and gets
 * nothing has been told the application is broken, and was told correctly.
 */
@Composable
private fun StatusItem(
    label: String,
    tooltip: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Tooltip(tooltip = { Text(tooltip) }) {
        IdeWidgetButton(onClick = onClick, modifier = modifier) {
            Text(
                label,
                fontSize = LocalTypeScale.current.medium,
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
