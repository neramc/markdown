package dev.starfect.quill.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.starfect.quill.QuillController
import dev.starfect.quill.bridge.MarkdownFlavour
import dev.starfect.quill.model.DocumentSession
import dev.starfect.quill.model.ViewMode
import dev.starfect.quill.model.WorkspaceState
import dev.starfect.quill.ui.icons.IdeIcons
import dev.starfect.quill.ui.shell.IdeActionButton
import dev.starfect.quill.ui.shell.label
import dev.starfect.quill.ui.theme.IdeaMetrics
import dev.starfect.quill.ui.theme.LocalEditorPalette
import dev.starfect.quill.ui.theme.LocalShellPalette
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text

/**
 * The Markdown editor's own toolbar: the three view-mode toggles, right-aligned.
 *
 * This is where IntelliJ puts them — floating at the top-right corner of the Markdown editor itself,
 * not in the window's title bar and not as a segmented control. Getting the location right matters
 * more than getting the icons right: a view switch in the title bar reads as an application-level
 * mode, while one in the editor reads as a property of the file you are looking at, which is what it
 * actually is.
 */
@Composable
public fun MarkdownEditorToolbar(controller: QuillController, workspace: WorkspaceState) {
    val shell = LocalShellPalette.current
    val document = workspace.activeDocument

    Box(Modifier.fillMaxWidth().height(30.dp).background(shell.toolWindowBackground)) {
        // The flavour sits at the left, where the IDE puts a file's language: it is a property of
        // the document, and it belongs next to the document rather than beside the view controls.
        if (document != null) {
            Row(
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlavourPicker(controller, document)
            }
        }

        Row(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            if (document != null) {
                InspectionWidget(controller, document)
                Box(Modifier.padding(horizontal = 5.dp).width(1.dp).height(14.dp).background(shell.border))
            }

            val current = workspace.settings.viewMode

            IdeActionButton(
                onClick = { controller.setViewMode(ViewMode.EDITOR) },
                tooltip = "${ViewMode.EDITOR.label}  Ctrl+1",
                selected = current == ViewMode.EDITOR,
                size = 24.dp,
            ) { tint -> IdeIcons.ViewEditorOnly(tint, size = IdeaMetrics.IconSize) }

            IdeActionButton(
                onClick = { controller.setViewMode(ViewMode.SPLIT) },
                tooltip = "${ViewMode.SPLIT.label}  Ctrl+2",
                selected = current == ViewMode.SPLIT,
                size = 24.dp,
            ) { tint -> IdeIcons.ViewSplit(tint, size = IdeaMetrics.IconSize) }

            IdeActionButton(
                onClick = { controller.setViewMode(ViewMode.PREVIEW) },
                tooltip = "${ViewMode.PREVIEW.label}  Ctrl+3",
                selected = current == ViewMode.PREVIEW,
                size = 24.dp,
            ) { tint -> IdeIcons.ViewPreviewOnly(tint, size = IdeaMetrics.IconSize) }
        }
    }
}

/**
 * The Markdown dialect this document is parsed as.
 *
 * A picker rather than a global setting because a project routinely mixes them — a README beside an
 * MDX guide beside Markdoc content — and each has to render on its own terms. The extension picks
 * the initial value; this is for the files whose extension does not say.
 */
@Composable
private fun FlavourPicker(controller: QuillController, document: DocumentSession) {
    val shell = LocalShellPalette.current
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Box {
        Row(
            Modifier.height(22.dp)
                .clip(RoundedCornerShape(IdeaMetrics.ActionButtonCorner))
                .background(if (hovered || open) shell.hoverBackground else Color.Transparent)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null) { open = true }
                .padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(document.flavour.displayName, color = shell.mutedText, fontSize = IdeaMetrics.TinyFontSize)
            Box(Modifier.padding(start = 4.dp)) { IdeIcons.WidgetChevron(shell.mutedText, size = 9.dp) }
        }

        if (open) {
            PopupMenu(onDismissRequest = { open = false; true }, horizontalAlignment = Alignment.Start) {
                MarkdownFlavour.entries.forEach { flavour ->
                    selectableItem(
                        selected = flavour == document.flavour,
                        onClick = {
                            controller.setFlavour(document.id, flavour)
                            open = false
                        },
                    ) {
                        Text(flavour.displayName)
                    }
                }
            }
        }
    }
}
