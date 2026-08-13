package dev.starfect.quill.ui.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.starfect.quill.QuillController
import dev.starfect.quill.editing.MarkdownFeatures
import dev.starfect.quill.ui.icons.IdeIcons
import dev.starfect.quill.ui.theme.Elevation.dropShadow
import dev.starfect.quill.ui.theme.LocalShellPalette
import dev.starfect.quill.ui.theme.LocalTypeScale
import dev.starfect.quill.ui.theme.ShellDivider
import dev.starfect.quill.ui.theme.Tokens
import dev.starfect.quill.ui.theme.floatingFill
import dev.starfect.quill.ui.theme.interactiveSurface
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/** How wide the feature list is, in both of its forms. */
private val PaletteWidth = 520.dp

/** How tall the list gets before it scrolls. */
private val PaletteMaxHeight = 320.dp

/** Height of one feature row: two lines of text, so it needs more than a command row. */
private val FeatureRowHeight = 40.dp

/**
 * Every Markdown feature, searchable, on Ctrl/Cmd+K.
 *
 * The counterpart to the `/` menu in the editor, and the same catalogue: this one is reachable from
 * the middle of a sentence and works on a selection, which is what makes it the answer to "how do I
 * make *this* a heading" as well as "what can Markdown do".
 *
 * Each row shows the name, the syntax it produces and a sentence about it. Showing the syntax is the
 * point rather than decoration — used twice, this list has taught somebody the characters and they
 * stop needing it, which is the best outcome a feature like this can have.
 */
@Composable
public fun MarkdownFeaturePalette(controller: QuillController, onDismiss: () -> Unit) {
    val shell = LocalShellPalette.current
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var selected by remember { mutableIntStateOf(0) }

    val matches = remember(query.text) { MarkdownFeatures.search(query.text) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }

    // A new query means a new list; leaving the cursor on row seven of a list that now has two is
    // how a palette ends up running the wrong thing on Enter.
    LaunchedEffect(query.text) { selected = 0 }
    LaunchedEffect(selected) {
        if (selected in matches.indices) listState.scrollToItem(selected)
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun choose(feature: MarkdownFeatures.Feature) {
        onDismiss()
        controller.applyFeature(feature)
    }

    Box(
        modifier = Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier.padding(top = 120.dp).width(PaletteWidth)
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
                    navigate(event, matches.size, selected, { selected = it }) {
                        matches.getOrNull(selected)?.let(::choose)
                    }
                },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(Tokens.SearchFieldHeight)
                    .padding(horizontal = Tokens.Spacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.Small),
            ) {
                IdeIcons.Search(shell.mutedText, size = Tokens.IconSize)
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    undecorated = true,
                    placeholder = { Text("Search Markdown features", color = shell.mutedText) },
                )
            }

            ShellDivider(Orientation.Horizontal)

            if (matches.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Nothing matches \"${query.text}\"",
                        color = shell.mutedText,
                        fontSize = LocalTypeScale.current.medium,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = PaletteMaxHeight).padding(vertical = Tokens.Spacing.Tiny),
                ) {
                    items(matches.size, key = { matches[it].id }) { index ->
                        FeatureRow(
                            feature = matches[index],
                            selected = index == selected,
                            onPick = { choose(matches[index]) },
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
                    text = "${matches.size} feature${if (matches.size == 1) "" else "s"}",
                    fontSize = LocalTypeScale.current.medium,
                    color = shell.mutedText,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "↑↓ to move  ·  Enter to insert  ·  Esc to close",
                    fontSize = LocalTypeScale.current.medium,
                    color = shell.mutedText,
                )
            }
        }
    }
}

/**
 * The `/` menu, floating at the caret.
 *
 * Deliberately not a modal: it appears *while* you are typing, the editor keeps the focus, and the
 * query is the text already in the document. That is the whole ergonomic difference from the
 * palette above, and it is why the two share a catalogue and a row but not a container.
 */
@Composable
public fun SlashMenu(
    matches: List<MarkdownFeatures.Feature>,
    selected: Int,
    onPick: (MarkdownFeatures.Feature) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (matches.isEmpty()) return
    val shell = LocalShellPalette.current
    val listState = rememberLazyListState()

    LaunchedEffect(selected) {
        if (selected in matches.indices) listState.scrollToItem(selected)
    }

    Column(
        modifier = modifier.width(PaletteWidth)
            .clip(RoundedCornerShape(Tokens.Radius.Popup))
            .dropShadow(RoundedCornerShape(Tokens.Radius.Popup))
            .floatingFill(shell.popupBackground)
            .border(1.dp, shell.popupBorder, RoundedCornerShape(Tokens.Radius.Popup)),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.heightIn(max = SlashMenuMaxHeight).padding(vertical = Tokens.Spacing.Tiny),
        ) {
            items(matches.size, key = { matches[it].id }) { index ->
                FeatureRow(
                    feature = matches[index],
                    selected = index == selected,
                    onPick = { onPick(matches[index]) },
                )
            }
        }
    }
}

/** Shorter than the palette's: it hangs off the caret and must not cover the paragraph above. */
private val SlashMenuMaxHeight = 220.dp

/**
 * Arrow-key navigation shared by both forms.
 *
 * Returns true when the event was consumed. Tab moves the cursor as well as the arrows, because in
 * a list opened by typing `/` that is the key most people reach for first.
 */
private fun navigate(
    event: androidx.compose.ui.input.key.KeyEvent,
    count: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
    onAccept: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown || count == 0) return false

    return when (event.key) {
        Key.DirectionDown, Key.Tab -> {
            onSelect((selected + 1) % count)
            true
        }

        Key.DirectionUp -> {
            onSelect((selected - 1 + count) % count)
            true
        }

        Key.Enter, Key.NumPadEnter -> {
            onAccept()
            true
        }

        else -> false
    }
}

/** Public so the editor can drive the same navigation from its own key handler. */
public fun navigateFeatureList(
    event: androidx.compose.ui.input.key.KeyEvent,
    count: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
    onAccept: () -> Unit,
): Boolean = navigate(event, count, selected, onSelect, onAccept)

/**
 * One feature: what it is called, the syntax it writes, and a sentence about it.
 *
 * The syntax is drawn in the editor's own monospace, which is what makes the row read as a piece of
 * a document rather than as a menu label.
 */
@Composable
private fun FeatureRow(
    feature: MarkdownFeatures.Feature,
    selected: Boolean,
    onPick: () -> Unit,
) {
    val shell = LocalShellPalette.current
    val scale = LocalTypeScale.current

    Row(
        modifier = Modifier.fillMaxWidth().height(FeatureRowHeight)
            .interactiveSurface(
                onClick = onPick,
                palette = shell,
                selected = selected,
                cornerRadius = Tokens.Radius.Row,
            )
            .padding(horizontal = Tokens.Spacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.Small),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = feature.name,
                fontSize = scale.default,
                color = shell.text,
                maxLines = 1,
            )
            Text(
                text = feature.description,
                fontSize = scale.medium,
                color = shell.mutedText,
                maxLines = 1,
            )
        }

        if (feature.syntax.isNotEmpty()) {
            Text(
                text = feature.syntax,
                fontSize = scale.medium,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = shell.secondaryText,
                maxLines = 1,
            )
        }

        feature.shortcut?.let { shortcut ->
            Spacer(Modifier.width(Tokens.Spacing.Small))
            Text(
                text = shortcut,
                fontSize = scale.medium,
                color = shell.mutedText,
                maxLines = 1,
            )
        }
    }
}
