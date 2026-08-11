package dev.starfect.quill.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.starfect.quill.QuillController
import dev.starfect.quill.model.QuillSettings
import dev.starfect.quill.model.ViewMode
import dev.starfect.quill.model.WorkspaceState
import dev.starfect.quill.ui.theme.Tokens
import dev.starfect.quill.ui.theme.LocalShellPalette
import dev.starfect.quill.ui.theme.ShellDivider
import dev.starfect.quill.ui.theme.interactiveSurface
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.RadioButtonRow
import org.jetbrains.jewel.ui.component.Text

/** The pages of the Settings dialog, in the order the category list shows them. */
private enum class SettingsPage(val title: String) {
    APPEARANCE("Appearance"),
    EDITOR("Editor"),
    INSPECTIONS("Inspections"),
    SAVING("Saving"),
}

/**
 * The Settings dialog.
 *
 * Categories on the left, the selected page on the right — the shape the IDE has used for its
 * settings since long before the New UI, and the reason a user can find anything in it.
 *
 * Edits are held locally and applied on OK, so Cancel actually cancels. Applying live would be
 * simpler and would make the dialog's Cancel button a lie.
 */
@Composable
public fun SettingsDialog(controller: QuillController, workspace: WorkspaceState) {
    var draft by remember(workspace.settings) { mutableStateOf(workspace.settings) }
    var page by remember { mutableStateOf(SettingsPage.APPEARANCE) }

    IdeDialog(
        title = "Settings",
        onDismiss = controller::dismissDialog,
        onConfirm = {
            controller.applySettings(draft)
            controller.dismissDialog()
        },
        width = 760.dp,
        height = 540.dp,
    ) {
        Row(Modifier.fillMaxSize()) {
            CategoryList(page) { page = it }
            ShellDivider(Orientation.Vertical)

            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                when (page) {
                    SettingsPage.APPEARANCE -> AppearancePage(draft) { draft = it }
                    SettingsPage.EDITOR -> EditorPage(draft) { draft = it }
                    SettingsPage.INSPECTIONS -> InspectionsPage(draft) { draft = it }
                    SettingsPage.SAVING -> SavingPage(draft) { draft = it }
                }
            }
        }
    }
}

@Composable
private fun CategoryList(selected: SettingsPage, onSelect: (SettingsPage) -> Unit) {
    val shell = LocalShellPalette.current

    Column(
        Modifier.width(Tokens.DialogListWidth).fillMaxHeight()
            .background(shell.panelSecondary)
            .padding(vertical = Tokens.Spacing.Tiny)
    ) {
        SettingsPage.entries.forEach { entry ->
            Row(
                Modifier.fillMaxWidth()
                    .height(Tokens.TreeRowHeight)
                    .interactiveSurface(
                        onClick = { onSelect(entry) },
                        palette = shell,
                        selected = entry == selected,
                    )
                    .padding(horizontal = Tokens.Spacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(entry.title, color = shell.text, fontSize = Tokens.FontSize)
            }
        }
    }
}

@Composable
private fun AppearancePage(settings: QuillSettings, onChange: (QuillSettings) -> Unit) {
    GroupHeader("Theme")
    FormRow("Colour scheme") {
        val themes = remember { listOf("Dark", "Light") }
        ListComboBox(
            items = themes,
            selectedIndex = if (settings.darkTheme) 0 else 1,
            onSelectedItemChange = { index -> onChange(settings.copy(darkTheme = index == 0)) },
            modifier = Modifier.width(200.dp),
        )
    }

    Spacer(Modifier.height(10.dp))
    GroupHeader("Layout")
    FormRow("Default view") {
        Column {
            ViewMode.entries.forEach { mode ->
                RadioButtonRow(
                    text = mode.label,
                    selected = settings.viewMode == mode,
                    onClick = { onChange(settings.copy(viewMode = mode)) },
                )
            }
        }
    }
    FormIndent {
        CheckboxRow(
            text = "Scroll the preview with the caret",
            checked = settings.syncScrolling,
            onCheckedChange = { onChange(settings.copy(syncScrolling = it)) },
        )
    }
}

@Composable
private fun EditorPage(settings: QuillSettings, onChange: (QuillSettings) -> Unit) {
    GroupHeader("Text")
    FormRow("Font size") {
        val sizes = remember { (10..24).map(Int::toString) }
        ListComboBox(
            items = sizes,
            selectedIndex = sizes.indexOf(settings.editorFontSize.toString()).coerceAtLeast(0),
            onSelectedItemChange = { index ->
                onChange(settings.copy(editorFontSize = sizes[index].toInt()))
            },
            modifier = Modifier.width(110.dp),
        )
    }
    FormRow("Tab width") {
        val widths = remember { listOf("2", "4", "8") }
        ListComboBox(
            items = widths,
            selectedIndex = widths.indexOf(settings.tabWidth.toString()).coerceAtLeast(0),
            onSelectedItemChange = { index -> onChange(settings.copy(tabWidth = widths[index].toInt())) },
            modifier = Modifier.width(110.dp),
        )
    }
    FormRow("Right margin") {
        // 0 is "off", which the IDE spells as a blank field; a list keeps it to one control.
        val columns = remember { listOf("Off", "72", "80", "100", "120") }
        val current = if (settings.visualGuideColumn == 0) "Off" else settings.visualGuideColumn.toString()
        ListComboBox(
            items = columns,
            selectedIndex = columns.indexOf(current).coerceAtLeast(0),
            onSelectedItemChange = { index ->
                onChange(settings.copy(visualGuideColumn = columns[index].toIntOrNull() ?: 0))
            },
            modifier = Modifier.width(110.dp),
        )
    }

    Spacer(Modifier.height(10.dp))
    GroupHeader("Appearance")
    CheckboxRow(
        text = "Show line numbers",
        checked = settings.showLineNumbers,
        onCheckedChange = { onChange(settings.copy(showLineNumbers = it)) },
    )
    CheckboxRow(
        text = "Highlight the caret row",
        checked = settings.highlightCaretRow,
        onCheckedChange = { onChange(settings.copy(highlightCaretRow = it)) },
    )
    CheckboxRow(
        text = "Soft-wrap long lines",
        checked = settings.wordWrap,
        onCheckedChange = { onChange(settings.copy(wordWrap = it)) },
    )
}

@Composable
private fun InspectionsPage(settings: QuillSettings, onChange: (QuillSettings) -> Unit) {
    val shell = LocalShellPalette.current

    GroupHeader("Analysis")
    CheckboxRow(
        text = "Inspect documents as you type",
        checked = settings.inspectionsEnabled,
        onCheckedChange = { onChange(settings.copy(inspectionsEnabled = it)) },
    )
    CheckboxRow(
        text = "Report weak warnings",
        checked = settings.showWeakWarnings,
        onCheckedChange = { onChange(settings.copy(showWeakWarnings = it)) },
    )

    Spacer(Modifier.height(4.dp))
    Text(
        text = "Weak warnings are the style notes — trailing whitespace, hard tabs, fences without " +
            "a language. They are worth having on a document you are writing and worth turning " +
            "off on one you have imported.",
        color = shell.mutedText,
        fontSize = Tokens.SmallFontSize,
    )

    Spacer(Modifier.height(12.dp))
    GroupHeader("Enabled inspections")
    Column(Modifier.padding(top = 4.dp)) {
        INSPECTION_DESCRIPTIONS.forEach { (title, description) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(title, color = shell.text, fontSize = Tokens.SmallFontSize, modifier = Modifier.width(230.dp))
                Text(description, color = shell.mutedText, fontSize = Tokens.SmallFontSize)
            }
        }
    }
}

/**
 * What each inspection looks for.
 *
 * Listed rather than made individually toggleable: per-inspection suppression needs somewhere to
 * persist and a way to scope it to a project, and a checkbox that silently forgets on restart is
 * worse than no checkbox.
 */
private val INSPECTION_DESCRIPTIONS = listOf(
    "Structure" to "Heading level jumps, duplicate headings, more than one title",
    "Links" to "Empty destinations, undefined references, images without alt text",
    "Footnotes" to "Referenced but undefined, defined but unused",
    "Code" to "Unclosed fences, fences with no language",
    "Tables" to "Rows whose cell count does not match the header",
    "Whitespace" to "Hard tabs and trailing spaces outside code",
)

@Composable
private fun SavingPage(settings: QuillSettings, onChange: (QuillSettings) -> Unit) {
    GroupHeader("On save")
    CheckboxRow(
        text = "Remove trailing whitespace",
        checked = settings.trimTrailingWhitespaceOnSave,
        onCheckedChange = { onChange(settings.copy(trimTrailingWhitespaceOnSave = it)) },
    )
    CheckboxRow(
        text = "Ensure the file ends with a newline",
        checked = settings.ensureNewlineOnSave,
        onCheckedChange = { onChange(settings.copy(ensureNewlineOnSave = it)) },
    )

    Spacer(Modifier.height(10.dp))
    GroupHeader("Automatically")
    CheckboxRow(
        text = "Save when the window loses focus",
        checked = settings.saveOnFocusLoss,
        onCheckedChange = { onChange(settings.copy(saveOnFocusLoss = it)) },
    )
}

private val ViewMode.label: String
    get() = when (this) {
        ViewMode.EDITOR -> "Source only"
        ViewMode.SPLIT -> "Source and preview"
        ViewMode.PREVIEW -> "Preview only"
    }
