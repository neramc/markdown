package dev.starfect.quill.ui.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.starfect.quill.QuillController
import dev.starfect.quill.model.WorkspaceState
import dev.starfect.quill.search.ProjectSearch
import dev.starfect.quill.ui.icons.IdeIcons
import dev.starfect.quill.ui.theme.Elevation.dropShadow
import dev.starfect.quill.ui.theme.LocalShellPalette
import dev.starfect.quill.ui.theme.LocalTypeScale
import dev.starfect.quill.ui.theme.ShellDivider
import dev.starfect.quill.ui.theme.Tokens
import dev.starfect.quill.ui.theme.floatingFill
import dev.starfect.quill.ui.theme.interactiveSurface
import kotlin.io.path.name
import kotlin.io.path.relativeToOrSelf
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

private val DialogWidth = 760.dp
private val ResultsMaxHeight = 420.dp
private val ResultRowHeight = 40.dp

/**
 * Searching the whole project, five ways.
 *
 * One dialog with five scopes rather than five dialogs, because the question a writer has is
 * usually "where is that", and which of the five answers it is something they discover by trying.
 * Switching between two *searches* keeps the query, so a text search that finds nothing is one
 * click from being a file-name search that does — while switching to Recent or TODO drops it,
 * because those two mean something with an empty query and a carried-over filter would hide the
 * list behind words nobody typed for it.
 *
 * The results are whatever the controller's background search last produced. Nothing here touches
 * the disk, which is what keeps a scroll through four hundred results smooth while the search that
 * produced them is still running.
 */
@Composable
public fun ProjectSearchDialog(controller: QuillController, workspace: WorkspaceState) {
    val shell = LocalShellPalette.current
    val scale = LocalTypeScale.current
    val state = workspace.projectSearch

    // Re-created when the scope changes, so a query the controller dropped on the way into a list
    // scope actually disappears from the field rather than only from the search.
    var query by remember(state.scope) { mutableStateOf(TextFieldValue(state.query)) }
    var selected by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }

    val hits = state.results.hits

    LaunchedEffect(hits) { selected = 0 }
    LaunchedEffect(selected) { if (selected in hits.indices) listState.scrollToItem(selected) }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        modifier = Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = controller::hideProjectSearch,
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier.padding(top = 80.dp).width(DialogWidth)
                .clip(RoundedCornerShape(Tokens.Radius.Popup))
                .dropShadow(RoundedCornerShape(Tokens.Radius.Popup))
                .floatingFill(shell.popupBackground)
                .border(1.dp, shell.popupBorder, RoundedCornerShape(Tokens.Radius.Popup))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = false,
                ) {}
                .onPreviewKeyEvent { event ->
                    navigateFeatureList(event, hits.size, selected, { selected = it }) {
                        hits.getOrNull(selected)?.let(controller::openHit)
                    }
                },
        ) {
            ScopeRow(state.scope) { controller.updateProjectSearch(scope = it) }
            ShellDivider(Orientation.Horizontal)

            Row(
                modifier = Modifier.fillMaxWidth().height(Tokens.SearchFieldHeight)
                    .padding(horizontal = Tokens.Spacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.Small),
            ) {
                IdeIcons.Search(shell.mutedText, size = Tokens.IconSize)
                TextField(
                    value = query,
                    onValueChange = {
                        query = it
                        controller.updateProjectSearch(query = it.text)
                    },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    undecorated = true,
                    placeholder = { Text(state.scope.placeholder, color = shell.mutedText) },
                )
                // Case sensitivity is a property of the search, and the two scopes that are lists
                // rather than searches have nothing to apply it to.
                if (state.scope == ProjectSearch.Scope.CONTENT || state.scope == ProjectSearch.Scope.REGEX) {
                    CaseToggle(state.caseSensitive) { controller.updateProjectSearch(caseSensitive = it) }
                }
            }

            ShellDivider(Orientation.Horizontal)

            when {
                state.results.error != null -> Message(state.results.error!!, error = true)
                hits.isEmpty() && state.running -> Message("Searching…")
                hits.isEmpty() -> Message(emptyMessage(state.scope, state.query))
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = ResultsMaxHeight).padding(vertical = Tokens.Spacing.Tiny),
                ) {
                    items(hits.size, key = { "${hits[it].path}:${hits[it].offset}:$it" }) { index ->
                        ResultRow(
                            hit = hits[index],
                            root = workspace.projectRoot,
                            selected = index == selected,
                            onOpen = { controller.openHit(hits[index]) },
                        )
                    }
                }
            }

            ShellDivider(Orientation.Horizontal)
            Row(
                modifier = Modifier.fillMaxWidth().height(Tokens.StatusBarHeight)
                    .padding(horizontal = Tokens.Spacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = buildString {
                        append(hits.size)
                        append(if (hits.size == 1) " result" else " results")
                        // A truncated list has to say so, or the absence of a result reads as proof
                        // the project does not contain it.
                        if (state.results.truncated) append(" (more not shown)")
                    },
                    fontSize = scale.medium,
                    color = shell.mutedText,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "↑↓ to move  ·  Enter to open  ·  Esc to close",
                    fontSize = scale.medium,
                    color = shell.mutedText,
                )
            }
        }
    }
}

private fun emptyMessage(scope: ProjectSearch.Scope, query: String): String = when {
    query.isBlank() && scope == ProjectSearch.Scope.TODO -> "No TODO, FIXME or XXX notes in this project"
    query.isBlank() -> scope.placeholder
    else -> "Nothing found for \"$query\""
}

@Composable
private fun Message(text: String, error: Boolean = false) {
    val shell = LocalShellPalette.current
    Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = if (error) shell.modified else shell.mutedText,
            fontSize = LocalTypeScale.current.medium,
        )
    }
}

@Composable
private fun ScopeRow(selected: ProjectSearch.Scope, onSelect: (ProjectSearch.Scope) -> Unit) {
    val shell = LocalShellPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().height(Tokens.TabHeight).padding(horizontal = Tokens.Spacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.Tiny),
    ) {
        ProjectSearch.Scope.entries.forEach { scope ->
            val isSelected = scope == selected
            Box(
                modifier = Modifier.height(Tokens.SearchScopeHeight)
                    .interactiveSurface(
                        onClick = { onSelect(scope) },
                        palette = shell,
                        selected = isSelected,
                        toggle = true,
                        cornerRadius = Tokens.Radius.Control,
                    )
                    .padding(horizontal = Tokens.Spacing.Small),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = scope.title,
                    fontSize = LocalTypeScale.current.medium,
                    color = if (isSelected) shell.text else shell.mutedText,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun CaseToggle(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val shell = LocalShellPalette.current
    Box(
        modifier = Modifier.height(Tokens.SmallControlSize)
            .interactiveSurface(
                onClick = { onToggle(!enabled) },
                palette = shell,
                selected = enabled,
                toggle = true,
                cornerRadius = Tokens.Radius.Control,
            )
            .padding(horizontal = Tokens.Spacing.Small),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Aa",
            fontSize = LocalTypeScale.current.medium,
            color = if (enabled) shell.text else shell.mutedText,
        )
    }
}

/**
 * One result: what file, and what in it.
 *
 * The second line carries whichever of the three things the scope makes meaningful — the matched
 * line for a text search, the age for a recent one, the marker for a TODO — with the match itself
 * drawn in the accent so the eye finds it without reading the line.
 */
@Composable
private fun ResultRow(
    hit: ProjectSearch.Hit,
    root: java.nio.file.Path?,
    selected: Boolean,
    onOpen: () -> Unit,
) {
    val shell = LocalShellPalette.current
    val scale = LocalTypeScale.current

    // The *directory*, not the path: the file name is already the first thing on the row, and
    // repeating it on the right for every file at the project root reads as a bug.
    val relative = remember(hit.path, root) {
        val directory = hit.path.parent ?: return@remember ""
        root?.let { directory.relativeToOrSelf(it).toString().replace('\\', '/') }
            ?: directory.toString()
    }

    Row(
        modifier = Modifier.fillMaxWidth().height(ResultRowHeight)
            .interactiveSurface(
                onClick = onOpen,
                palette = shell,
                selected = selected,
                cornerRadius = Tokens.Radius.Row,
            )
            .padding(horizontal = Tokens.Spacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.Small),
    ) {
        IdeIcons.MarkdownFile(tint = shell.icon, accent = shell.accent, size = Tokens.IconSize)

        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.Tiny),
            ) {
                Text(hit.path.name, fontSize = scale.default, color = shell.text, maxLines = 1)
                if (hit.line >= 0) {
                    Text(":${hit.line + 1}", fontSize = scale.medium, color = shell.mutedText, maxLines = 1)
                }
                hit.marker?.let {
                    Text(it, fontSize = scale.medium, color = shell.accent, maxLines = 1)
                }
            }

            if (hit.preview.isNotEmpty()) {
                Text(
                    text = highlighted(hit, shell.accent),
                    fontSize = scale.medium,
                    fontFamily = if (hit.line >= 0) FontFamily.Monospace else FontFamily.Default,
                    color = shell.secondaryText,
                    maxLines = 1,
                )
            } else if (relative.isNotEmpty()) {
                Text(relative, fontSize = scale.medium, color = shell.mutedText, maxLines = 1)
            }
        }

        if (hit.preview.isNotEmpty() && relative.isNotEmpty()) {
            Text(relative, fontSize = scale.medium, color = shell.mutedText, maxLines = 1)
        }
    }
}

/** The preview with the matched run drawn in the accent colour. */
private fun highlighted(hit: ProjectSearch.Hit, accent: Color): AnnotatedString {
    val range = hit.previewMatch ?: return AnnotatedString(hit.preview)
    return buildAnnotatedStringSafely(hit.preview, range.first, range.last + 1, accent)
}

private fun buildAnnotatedStringSafely(
    text: String,
    start: Int,
    end: Int,
    accent: Color,
): AnnotatedString {
    val from = start.coerceIn(0, text.length)
    val to = end.coerceIn(from, text.length)
    return AnnotatedString.Builder(text).apply {
        addStyle(SpanStyle(color = accent, fontWeight = FontWeight.Medium), from, to)
    }.toAnnotatedString()
}
