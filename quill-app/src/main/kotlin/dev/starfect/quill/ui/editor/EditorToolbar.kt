package dev.starfect.quill.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.starfect.quill.QuillController
import dev.starfect.quill.model.ViewMode
import dev.starfect.quill.model.WorkspaceState
import dev.starfect.quill.ui.icons.IdeIcons
import dev.starfect.quill.ui.shell.IdeActionButton
import dev.starfect.quill.ui.shell.label
import dev.starfect.quill.ui.theme.IdeaMetrics
import dev.starfect.quill.ui.theme.LocalEditorPalette

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
    val editor = LocalEditorPalette.current

    Box(Modifier.fillMaxWidth().height(28.dp).background(editor.background)) {
        Row(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
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
