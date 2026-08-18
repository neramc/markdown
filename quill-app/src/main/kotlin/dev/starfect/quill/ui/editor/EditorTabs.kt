package dev.starfect.quill.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.setValue
import org.jetbrains.jewel.ui.component.MenuSeparator
import org.jetbrains.jewel.ui.component.PopupMenu
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.starfect.quill.QuillController
import dev.starfect.quill.model.DocumentSession
import dev.starfect.quill.model.WorkspaceState
import dev.starfect.quill.ui.icons.IdeIcons
import dev.starfect.quill.ui.theme.LocalTypeScale
import dev.starfect.quill.ui.theme.Elevation
import dev.starfect.quill.ui.theme.Tokens
import dev.starfect.quill.ui.shell.IdeActionButton
import dev.starfect.quill.ui.theme.LocalSurfaceStyle
import dev.starfect.quill.ui.theme.LocalShellPalette
import dev.starfect.quill.ui.theme.interactiveSurface
import org.jetbrains.jewel.ui.component.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.alpha
import dev.starfect.quill.ui.theme.Motion

/**
 * The editor tab strip.
 *
 * Hand-built rather than Jewel's `TabStrip`, because the New UI's editor tab has three details that
 * matter and none of them are configurable there: the file-type icon in front of the name, the close
 * button that only appears on hover or selection, and the 2px accent bar under the selected tab. The
 * selected tab also takes the *editor's* background rather than the strip's, which is what makes it
 * read as continuous with the document below it.
 */
@Composable
public fun EditorTabs(controller: QuillController, workspace: WorkspaceState) {
    if (workspace.documents.isEmpty()) return

    val shell = LocalShellPalette.current

    Box(Modifier.fillMaxWidth().height(Tokens.TabHeight).background(shell.tabBarBackground)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.Start,
            ) {
                items(workspace.documents.size, key = { workspace.documents[it].id }) { index ->
                    val session = workspace.documents[index]
                    EditorTab(
                        session = session,
                        selected = session.id == workspace.activeDocumentId,
                        onSelect = { controller.selectDocument(session.id) },
                        onClose = { controller.requestCloseDocument(session.id) },
                        // Closing a tab in the middle slides the ones after it along rather than
                        // teleporting them, which is the difference between "that closed" and
                        // "the strip changed".
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            TabActionsButton(controller, workspace)
        }
    }
}

@Composable
private fun EditorTab(
    session: DocumentSession,
    selected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shell = LocalShellPalette.current
    val surfaces = LocalSurfaceStyle.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    // Captured outside the draw lambda: reading a composition local or a token inside it would run
    // on every frame rather than on every recomposition.
    val underlineColor = shell.tabUnderline
    val underlineThickness = Tokens.TabUnderlineThickness

    // The two surface styles mark the active tab differently, and the difference is the point.
    //
    // Flat: the tab takes the editor's own background and an accent line beneath it. A strong fill
    // there would read as a browser tab sitting on top of the editor rather than part of it.
    //
    // Islands: the editor is already a separate rounded surface, so an underline against its edge
    // has nothing to sit on. The tab becomes a filled, rounded shape instead — which is what the
    // style means by making the active tab more recognisable.
    val filledSelection = surfaces.separated
    val targetBackground = when {
        selected && filledSelection -> shell.hoverBackground
        selected -> shell.tabSelectedBackground
        hovered -> shell.hoverBackground
        else -> Color.Transparent
    }

    // The fill crosses rather than switches, at the same rate as every other hoverable surface in
    // the shell — a tab that snaps while the tree row beside it fades is the sort of mismatch that
    // reads as unfinished even when neither is wrong on its own.
    val background by animateColorAsState(targetBackground, Motion.state(), label = "tabFill")

    // The accent bar fades with the selection instead of jumping between tabs. It is two pixels of
    // colour, and it is the only thing on the strip that says which document you are in.
    val underlineAlpha by animateFloatAsState(
        targetValue = if (selected && !filledSelection) 1f else 0f,
        animationSpec = Motion.state(),
        label = "tabUnderline",
    )

    // Reserving the close button's width even while it is invisible is what stops the strip
    // twitching as the pointer crosses it; fading rather than swapping is what stops it flickering
    // when the pointer passes over a tab on the way to another.
    val closeAlpha by animateFloatAsState(
        targetValue = if (hovered || selected) 1f else 0f,
        animationSpec = Motion.state(),
        label = "tabClose",
    )

    Box(
        modifier.height(Tokens.TabHeight)
            .then(
                if (filledSelection) {
                    Modifier.padding(vertical = Tokens.Spacing.Tiny)
                        .clip(RoundedCornerShape(Tokens.Radius.Control))
                } else {
                    Modifier
                }
            )
            .then(
                // A filled tab is a surface of its own and takes the same top-edge lift as any
                // other. An underlined tab is part of the editor and gets nothing.
                if (filledSelection && selected) {
                    Modifier.background(Elevation.activeTab(background))
                } else {
                    Modifier.background(background)
                }
            )
            // The underline is drawn rather than laid out. As a child Box it used `fillMaxWidth`,
            // and inside a horizontally-scrolling LazyRow the width constraint is unbounded, so
            // "fill the available width" resolved to zero and the accent line was never on screen
            // in any build. Drawing it reads the tab's resolved width at paint time instead.
            .drawBehind {
                if (underlineAlpha <= 0f) return@drawBehind
                val thickness = underlineThickness.toPx()
                drawRect(
                    color = underlineColor,
                    topLeft = Offset(0f, size.height - thickness),
                    size = Size(size.width, thickness),
                    alpha = underlineAlpha,
                )
            }
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onSelect),
    ) {
        Row(
            modifier = Modifier.fillMaxHeight()
                .padding(start = Tokens.Spacing.Small, end = Tokens.Spacing.Tiny),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.Tiny),
        ) {
            IdeIcons.MarkdownFile(
                tint = if (selected) shell.icon else shell.mutedIcon,
                accent = shell.accent,
                size = Tokens.IconSize,
            )

            Text(
                text = session.displayName,
                fontSize = LocalTypeScale.current.default,
                // An unsaved tab is tinted rather than marked with an asterisk. An inactive tab
                // recedes to secondary, so the strip says which file you are in without an outline.
                color = when {
                    session.isModified -> shell.modified
                    selected -> shell.text
                    else -> shell.secondaryText
                },
                maxLines = 1,
            )

            // Always laid out, so the tab never changes width; only its opacity moves, and it
            // stops taking clicks once it has faded out.
            Box(Modifier.size(Tokens.TabCloseSize).alpha(closeAlpha)) {
                if (closeAlpha > 0f) TabCloseButton(onClose)
            }
        }
    }
}

@Composable
private fun TabCloseButton(onClose: () -> Unit) {
    val shell = LocalShellPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Box(
        modifier = Modifier.size(Tokens.TabCloseSize)
            .interactiveSurface(
                onClick = onClose,
                palette = shell,
                cornerRadius = Tokens.Radius.Row,
                interactionSource = interaction,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Muted until pointed at, which keeps a row of tabs from looking like a row of close buttons.
        IdeIcons.Close(if (hovered) shell.text else shell.mutedIcon, size = Tokens.SmallIconSize)
    }
}

/** Width reserved for a tab's close affordance, so callers can align around it. */
internal val TabCloseWidth = Tokens.TabCloseSize


/**
 * The menu at the right end of the tab strip.
 *
 * It was a button that did nothing — the strip needed something at its right end and the icon was
 * put there to fill it. Every entry below is an action the controller already had or a one-line
 * addition to it, so the menu is the whole of the fix: nothing here is a placeholder.
 *
 * Disabled rather than hidden when an entry cannot apply. "Close Others" with one tab open is a
 * question with an answer, and a menu whose items move around between openings is harder to use
 * than one whose items grey out.
 */
@Composable
private fun TabActionsButton(controller: QuillController, workspace: WorkspaceState) {
    var open by remember { mutableStateOf(false) }
    val active = workspace.activeDocument
    val documents = workspace.documents
    val index = documents.indexOfFirst { it.id == active?.id }

    Box {
        IdeActionButton(
            onClick = { open = !open },
            tooltip = "Tab Actions",
            selected = open,
            size = Tokens.SmallControlSize,
            modifier = Modifier.padding(end = Tokens.Spacing.Tiny),
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
                fun act(run: () -> Unit) {
                    open = false
                    run()
                }

                selectableItem(
                    selected = false,
                    enabled = active != null,
                    onClick = { act { active?.let { controller.requestCloseDocument(it.id) } } },
                ) { Text("Close") }

                selectableItem(
                    selected = false,
                    enabled = documents.size > 1,
                    onClick = { act { active?.let { controller.requestCloseOtherDocuments(it.id) } } },
                ) { Text("Close Others") }

                selectableItem(
                    selected = false,
                    enabled = index >= 0 && index < documents.lastIndex,
                    onClick = { act { active?.let { controller.requestCloseDocumentsAfter(it.id) } } },
                ) { Text("Close to the Right") }

                selectableItem(
                    selected = false,
                    enabled = documents.isNotEmpty(),
                    onClick = { act { controller.requestCloseAllDocuments() } },
                ) { Text("Close All") }

                passiveItem { MenuSeparator() }

                selectableItem(
                    selected = false,
                    enabled = active?.path != null,
                    onClick = {
                        act {
                            active?.path?.let { path ->
                                java.awt.Toolkit.getDefaultToolkit().systemClipboard
                                    .setContents(java.awt.datatransfer.StringSelection(path.toString()), null)
                            }
                        }
                    },
                ) { Text("Copy Path") }

                selectableItem(
                    selected = false,
                    enabled = active?.path != null,
                    onClick = { act { active?.path?.parent?.let(controller::openProject) } },
                ) { Text("Open Containing Folder as Project") }
            }
        }
    }
}
