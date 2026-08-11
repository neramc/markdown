package com.neramc.quill.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.unit.dp
import com.neramc.quill.QuillController
import com.neramc.quill.model.DocumentSession
import com.neramc.quill.model.WorkspaceState
import com.neramc.quill.ui.icons.IdeIcons
import com.neramc.quill.ui.theme.IdeaMetrics
import com.neramc.quill.ui.shell.IdeActionButton
import com.neramc.quill.ui.theme.LocalShellPalette
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text

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

    Box(Modifier.fillMaxWidth().height(IdeaMetrics.TabHeight).background(shell.tabBarBackground)) {
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
                size = 24.dp,
                modifier = Modifier.padding(end = 6.dp),
            ) { tint -> IdeIcons.MoreVertical(tint, size = 14.dp) }
        }

        Divider(
            orientation = Orientation.Horizontal,
            color = shell.border,
            modifier = Modifier.align(Alignment.BottomStart),
        )
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
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val background = when {
        selected -> shell.tabSelectedBackground
        hovered -> shell.hoverBackground
        else -> Color.Transparent
    }

    Box(
        Modifier.height(IdeaMetrics.TabHeight)
            .background(background)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onSelect),
    ) {
        Row(
            modifier = Modifier.height(IdeaMetrics.TabHeight).padding(start = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IdeIcons.MarkdownFile(
                tint = shell.icon,
                accent = shell.accent,
                size = IdeaMetrics.IconSize,
            )

            Text(
                text = session.displayName,
                fontSize = IdeaMetrics.UiFontSize,
                // An unsaved tab is tinted rather than marked, which is what the IDE does; the
                // asterisk lives in the window title instead.
                color = if (session.isModified) shell.modified else shell.text,
                maxLines = 1,
            )

            // The close button only materialises on hover or selection. Reserving its width even
            // when hidden is what stops the strip twitching as the pointer moves across it.
            if (hovered || selected) {
                TabCloseButton(onClose)
            } else {
                Spacer(Modifier.size(IdeaMetrics.IconSize))
            }
        }

        if (selected) {
            Box(
                Modifier.align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(IdeaMetrics.TabUnderlineThickness)
                    .background(shell.tabUnderline),
            )
        }
    }
}

@Composable
private fun TabCloseButton(onClose: () -> Unit) {
    val shell = LocalShellPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Box(
        modifier = Modifier.size(IdeaMetrics.IconSize)
            .background(if (hovered) shell.pressedBackground else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        IdeIcons.Close(if (hovered) shell.text else shell.mutedText, size = 12.dp)
    }
}

/** Width reserved for a tab's close affordance, so callers can align around it. */
internal val TabCloseWidth = 16.dp
