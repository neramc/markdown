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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.starfect.quill.QuillController
import dev.starfect.quill.model.RunConfiguration
import dev.starfect.quill.model.RunTask
import dev.starfect.quill.model.WorkspaceState
import dev.starfect.quill.ui.icons.IdeIcons
import dev.starfect.quill.ui.theme.LocalTypeScale
import dev.starfect.quill.ui.theme.Tokens
import dev.starfect.quill.ui.theme.LocalShellPalette
import dev.starfect.quill.ui.theme.ShellDivider
import dev.starfect.quill.ui.theme.interactiveSurface
import java.nio.file.Path
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/**
 * The Run/Debug Configurations dialog.
 *
 * The shape is the IDE's: a list of configurations on the left under an add/remove toolbar, the
 * selected one's form on the right, and OK/Cancel below. Quill's "run" is a document task — export,
 * inspect, count — rather than a process, but where the dialog lives and how it is laid out is what
 * makes it findable.
 *
 * Like Settings, edits are held locally and applied on OK.
 */
@Composable
public fun RunConfigurationsDialog(controller: QuillController, workspace: WorkspaceState) {
    var draft by remember(workspace.runConfigurations) { mutableStateOf(workspace.runConfigurations) }
    var selectedId by remember(workspace.selectedRunConfigurationId) {
        mutableStateOf(workspace.selectedRunConfigurationId ?: workspace.runConfigurations.firstOrNull()?.id)
    }

    val selected = draft.firstOrNull { it.id == selectedId }
    val shell = LocalShellPalette.current

    IdeDialog(
        title = "Run/Debug Configurations",
        onDismiss = controller::dismissDialog,
        onConfirm = {
            controller.setRunConfigurations(draft, selectedId)
            controller.dismissDialog()
        },
        width = 820.dp,
        height = 560.dp,
        extraButtons = {
            if (selected != null) {
                RunButton(
                    label = "Run",
                    onClick = {
                        // Running applies the edits first — running the version on screen rather
                        // than the version last saved is the only behaviour that is not surprising.
                        val stored = controller.setRunConfigurations(draft, selectedId)
                        val target = stored.firstOrNull { it.name == selected.name } ?: return@RunButton
                        controller.run(target)
                        controller.dismissDialog()
                    },
                )
            }
        },
    ) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(Tokens.DialogListWidth).fillMaxHeight().background(shell.toolWindowBackground)) {
                ListToolbar(
                    onAdd = {
                        val added = RunConfiguration(
                            // Negative ids mark configurations that exist only in this draft; the
                            // controller assigns the real one when OK adds them.
                            id = (draft.minOfOrNull { it.id } ?: 0L).coerceAtMost(0L) - 1,
                            name = uniqueName(draft, RunTask.EXPORT_HTML.label),
                            task = RunTask.EXPORT_HTML,
                        )
                        draft = draft + added
                        selectedId = added.id
                    },
                    onRemove = selected?.let {
                        {
                            draft = draft.filterNot { entry -> entry.id == it.id }
                            selectedId = draft.firstOrNull()?.id
                        }
                    },
                    onCopy = selected?.let {
                        {
                            val copy = it.copy(
                                id = (draft.minOfOrNull { entry -> entry.id } ?: 0L).coerceAtMost(0L) - 1,
                                name = uniqueName(draft, it.name),
                            )
                            draft = draft + copy
                            selectedId = copy.id
                        }
                    },
                    addTooltip = "Add a configuration",
                )
                ShellDivider(Orientation.Horizontal)

                ConfigurationList(draft, selectedId) { selectedId = it }
            }

            ShellDivider(Orientation.Vertical)

            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (selected == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Add a configuration with +",
                            color = shell.mutedText,
                            fontSize = LocalTypeScale.current.medium,
                        )
                    }
                } else {
                    ConfigurationForm(selected) { updated ->
                        draft = draft.map { if (it.id == updated.id) updated else it }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigurationList(
    configurations: List<RunConfiguration>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
) {
    val shell = LocalShellPalette.current
    val listState = rememberLazyListState()

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(vertical = 4.dp)) {
        items(configurations.size, key = { configurations[it].id }) { index ->
            val configuration = configurations[index]

            Row(
                Modifier.fillMaxWidth()
                    .height(Tokens.TreeRowHeight)
                    .interactiveSurface(
                        onClick = { onSelect(configuration.id) },
                        palette = shell,
                        selected = configuration.id == selectedId,
                    )
                    .padding(horizontal = Tokens.Spacing.Small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.Tiny),
            ) {
                IdeIcons.Run(shell.success, size = Tokens.SmallIconSize)
                Text(
                    text = configuration.name,
                    color = shell.text,
                    fontSize = LocalTypeScale.current.default,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ConfigurationForm(configuration: RunConfiguration, onChange: (RunConfiguration) -> Unit) {
    val shell = LocalShellPalette.current
    val tasks = remember { RunTask.entries }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        FormRow("Name") {
            var name by remember(configuration.id) { mutableStateOf(TextFieldValue(configuration.name)) }
            TextField(
                value = name,
                onValueChange = { value ->
                    name = value
                    onChange(configuration.copy(name = value.text))
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        FormRow("Task") {
            ListComboBox(
                items = tasks.map { it.label },
                selectedIndex = tasks.indexOf(configuration.task),
                onSelectedItemChange = { index -> onChange(configuration.copy(task = tasks[index])) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        FormIndent {
            Text(configuration.task.description, color = shell.mutedText, fontSize = LocalTypeScale.current.medium)
        }

        Spacer(Modifier.height(10.dp))
        GroupHeader("Target")
        FormRow("Document") {
            var path by remember(configuration.id) {
                mutableStateOf(TextFieldValue(configuration.targetPath?.toString().orEmpty()))
            }
            TextField(
                value = path,
                onValueChange = { value ->
                    path = value
                    onChange(configuration.copy(targetPath = value.text.toPathOrNull()))
                },
                placeholder = {
                    Text("The focused document", color = shell.mutedText, fontSize = LocalTypeScale.current.medium)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (configuration.task == RunTask.EXPORT_HTML) {
            FormRow("Output file") {
                var output by remember(configuration.id) {
                    mutableStateOf(TextFieldValue(configuration.outputPath?.toString().orEmpty()))
                }
                TextField(
                    value = output,
                    onValueChange = { value ->
                        output = value
                        onChange(configuration.copy(outputPath = value.text.toPathOrNull()))
                    },
                    placeholder = {
                        Text(
                            "Beside the document, with an .html extension",
                            color = shell.mutedText,
                            fontSize = LocalTypeScale.current.medium,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(10.dp))
            GroupHeader("HTML options")
            CheckboxRow(
                text = "Write a complete document rather than a fragment",
                checked = configuration.standalone,
                onCheckedChange = { onChange(configuration.copy(standalone = it)) },
            )
            CheckboxRow(
                text = "Use the dark palette",
                checked = configuration.darkTheme,
                onCheckedChange = { onChange(configuration.copy(darkTheme = it)) },
            )
            CheckboxRow(
                text = "Pass raw HTML through instead of escaping it",
                checked = configuration.allowRawHtml,
                onCheckedChange = { onChange(configuration.copy(allowRawHtml = it)) },
            )
            CheckboxRow(
                text = "Open the result when the run finishes",
                checked = configuration.openAfterRun,
                onCheckedChange = { onChange(configuration.copy(openAfterRun = it)) },
            )
        }

        Spacer(Modifier.height(10.dp))
        CheckboxRow(
            text = "Store as project file",
            checked = configuration.storeAsProjectFile,
            onCheckedChange = { onChange(configuration.copy(storeAsProjectFile = it)) },
        )
    }
}

/** The dialog's own Run button, drawn green the way the IDE's is. */
@Composable
private fun RunButton(label: String, onClick: () -> Unit) {
    val shell = LocalShellPalette.current

    Row(
        Modifier.height(Tokens.ControlSize)
            .interactiveSurface(
                onClick = onClick,
                palette = shell,
                cornerRadius = Tokens.Radius.Control,
            )
            .padding(horizontal = Tokens.Spacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.Tiny),
    ) {
        IdeIcons.Run(shell.success, size = Tokens.IconSize)
        Text(label, color = shell.text, fontSize = LocalTypeScale.current.default)
    }
}

/** A blank field means "unset", not a path named "". */
private fun String.toPathOrNull(): Path? = trim().takeIf { it.isNotEmpty() }?.let(Path::of)

private fun uniqueName(configurations: List<RunConfiguration>, base: String): String {
    val taken = configurations.map { it.name }.toSet()
    if (base !in taken) return base
    var index = 2
    while ("$base ($index)" in taken) index++
    return "$base ($index)"
}
