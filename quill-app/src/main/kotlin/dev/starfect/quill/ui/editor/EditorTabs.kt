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
                        onClose = { controller.closeDocument(session.id) },
                    )
                }
            }

            // The IDE parks a tab-actions menu at the right end of the strip; without it the strip
            // ends in nothing and the row reads as unfinished next to a real editor window.
            IdeActionButton(
                onClick = {},
                tooltip = "Tab Actions",
                size = Tokens.SmallControlSize,
                modifier = Modifier.padding(end = Tokens.Spacing.Tiny),
            ) { tint -> IdeIcons.MoreVertical(tint, size = Tokens.SmallIconSize) }
        }
    }
}

@Composable
private fun EditorTab(
    session: DocumentSession,
    selected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
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
    val background = when {
        selected && filledSelection -> shell.hoverBackground
        selected -> shell.tabSelectedBackground
        hovered -> shell.hoverBackground
        else -> Color.Transparent
    }

    Box(
        Modifier.height(Tokens.TabHeight)
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
                if (!selected || filledSelection) return@drawBehind
                val thickness = underlineThickness.toPx()
                drawRect(
                    color = underlineColor,
                    topLeft = Offset(0f, size.height - thickness),
                    size = Size(size.width, thickness),
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

            // The close button only materialises on hover or selection. Reserving its width even
            // when hidden is what stops the strip twitching as the pointer moves across it.
            if (hovered || selected) {
                TabCloseButton(onClose)
            } else {
                Spacer(Modifier.size(Tokens.TabCloseSize))
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
