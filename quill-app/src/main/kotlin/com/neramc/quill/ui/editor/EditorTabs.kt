package com.neramc.quill.ui.editor

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neramc.quill.QuillController
import com.neramc.quill.model.WorkspaceState
import com.neramc.quill.ui.theme.LocalShellPalette
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.editorTabStyle

/** Open documents as IDE editor tabs, with a modified marker and a close affordance. */
@Composable
public fun EditorTabs(controller: QuillController, workspace: WorkspaceState) {
    if (workspace.documents.isEmpty()) return

    val shell = LocalShellPalette.current
    val tabs = workspace.documents.map { session ->
        TabData.Default(
            selected = session.id == workspace.activeDocumentId,
            closable = true,
            onClose = { controller.closeDocument(session.id) },
            onClick = { controller.selectDocument(session.id) },
            content = { tabState ->
                Row(
                    modifier = Modifier.tabContentAlpha(tabState).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(session.displayName, fontSize = 13.sp, maxLines = 1)
                    if (session.isModified) {
                        // The asterisk is the IDE's own unsaved marker; a coloured dot would read as
                        // a different kind of status.
                        Text(" *", fontSize = 13.sp, color = shell.accent)
                    }
                }
            },
        )
    }

    TabStrip(tabs = tabs, style = JewelTheme.editorTabStyle, modifier = Modifier.fillMaxWidth())
}
