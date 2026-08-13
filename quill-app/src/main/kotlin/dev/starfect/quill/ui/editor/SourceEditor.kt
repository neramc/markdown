package dev.starfect.quill.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.starfect.quill.QuillController
import dev.starfect.quill.bridge.wire.StyleSpan
import dev.starfect.quill.model.DocumentSession
import dev.starfect.quill.model.WorkspaceState
import dev.starfect.quill.ui.theme.EditorPalette
import dev.starfect.quill.editing.MarkdownEdits
import dev.starfect.quill.editing.Vim
import dev.starfect.quill.editing.MarkdownFeatures
import dev.starfect.quill.ui.palette.SlashMenu
import dev.starfect.quill.ui.palette.SlashMenuWidth
import dev.starfect.quill.ui.palette.navigateFeatureList
import dev.starfect.quill.ui.theme.Tokens
import dev.starfect.quill.ui.theme.LocalEditorPalette
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer

/** Vertical inset shared by the text area and the gutter, so their first lines start level. */
private val EditorTopPadding = 6.dp

/**
 * The Markdown source pane.
 *
 * Text lives in Compose's `TextFieldValue`; the engine holds the authoritative rope and receives
 * edit deltas. Colouring arrives asynchronously from the engine and is applied through a
 * [VisualTransformation], so a slow parse never delays a keystroke — the worst case is a frame or
 * two of un-recoloured text.
 */
@Composable
public fun SourceEditor(
    controller: QuillController,
    workspace: WorkspaceState,
    document: DocumentSession,
    modifier: Modifier = Modifier,
) {
    val palette = LocalEditorPalette.current
    val settings = workspace.settings
    val scrollState = rememberScrollState()

    // In Focus Mode everything outside the paragraph being written is dimmed rather than hidden.
    // Hiding it would make the document unnavigable; dimming it leaves the shape of the page while
    // taking the pull of it away.
    val focusRange = remember(settings.focusMode, document.text) {
        if (settings.focusMode) paragraphAround(document.text.text, document.caretPosition.offset) else null
    }
    val transformation = remember(document.spans, palette, focusRange) {
        MarkdownVisualTransformation(document.spans, palette, focusRange)
    }

    val textStyle = JewelTheme.editorTextStyle.copy(
        fontSize = settings.editorFontSize.sp,
        color = palette.text,
    )

    // The layout the text field actually produced. Everything the gutter and the caret row need —
    // where each logical line ended up, how tall a wrapped line is — comes from here rather than
    // from a parallel measurement, because a parallel measurement is a second opinion that
    // eventually disagrees. Before soft wrap, this editor numbered logical lines 1..n down the side
    // and the numbers drifted out of step with the text the moment a line wrapped.
    var layout by remember(document.id) { mutableStateOf<TextLayoutResult?>(null) }

    // How many logical lines there are, which is what the gutter has to be wide enough to number.
    val lineCount = remember(document.text.text) { document.text.text.count { it == '\n' } + 1 }

    // The advance of one character in the editor's font, which is what a column-based right margin
    // has to be measured in. Monospace is assumed because the editor font is; a proportional font
    // has no column to draw a guide at.
    val measurer = rememberTextMeasurer()
    val characterWidth = remember(textStyle) {
        measurer.measure(AnnotatedString("0"), textStyle).size.width.toFloat()
    }

    // The `/` menu. Its query is the text already in the document, which is what makes it feel like
    // typing rather than like opening a dialog: everything the writer types is Markdown until they
    // choose an entry, and pressing Escape leaves what they typed exactly where it is.
    val triggerStart = remember(document.text) { MarkdownFeatures.triggerStart(document.text) }
    var dismissedTrigger by remember(document.id) { mutableStateOf<Int?>(null) }
    val slashMatches = remember(triggerStart, document.text.text) {
        if (triggerStart == null) {
            emptyList()
        } else {
            MarkdownFeatures.search(MarkdownFeatures.triggerQuery(document.text, triggerStart))
        }
    }
    var slashSelection by remember(document.id) { mutableIntStateOf(0) }
    // A changed query means a changed list; the cursor goes back to the top rather than pointing at
    // whatever now happens to occupy row seven.
    LaunchedEffect(triggerStart, slashMatches.size) { slashSelection = 0 }

    val slashOpen = triggerStart != null &&
        slashMatches.isNotEmpty() &&
        dismissedTrigger != triggerStart &&
        // Under Vim, `/` in normal mode is the search prompt and `/` in insert mode is a character
        // somebody is typing on purpose. Neither wants a menu.
        !settings.vimMode

    // Where the caret is, in the editor Box's own coordinates, so the menu can hang off it.
    var textAreaOrigin by remember { mutableStateOf(Offset.Zero) }
    var editorOrigin by remember { mutableStateOf(Offset.Zero) }
    var editorHeight by remember { mutableIntStateOf(0) }
    var editorWidth by remember { mutableIntStateOf(0) }

    Box(
        modifier.background(palette.background)
            .onGloballyPositioned {
                editorOrigin = it.positionInRoot()
                editorHeight = it.size.height
                editorWidth = it.size.width
            },
    ) {
        VerticallyScrollableContainer(scrollState = scrollState, modifier = Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth()) {
                if (settings.showLineNumbers) {
                    LineGutter(
                        layout = layout,
                        caretOffset = document.caretPosition.offset,
                        lineCount = lineCount,
                        palette = palette,
                        textStyle = textStyle,
                    )
                }

                TextArea(
                    value = document.text,
                    onValueChange = { controller.onTextChanged(document.id, it) },
                    modifier = Modifier.weight(1f)
                        .onGloballyPositioned { textAreaOrigin = it.positionInRoot() }
                        .caretRow(
                            layout = layout.takeIf { settings.highlightCaretRow },
                            caretOffset = document.caretPosition.offset,
                            palette = palette,
                        )
                        .rightMargin(settings.visualGuideColumn, characterWidth, palette)
                        // Vim comes first when it is on: in normal mode it owns every key, and a
                        // slash menu opening on `/` would be the search prompt it expects instead.
                        .vimKeys(enabled = settings.vimMode, onKey = controller::vimKey)
                        // The slash menu's keys are read before the tab and Enter handling, so that
                        // Enter chooses an entry rather than breaking the line under the menu.
                        .slashMenuKeys(
                            open = slashOpen,
                            count = slashMatches.size,
                            selected = slashSelection,
                            onSelect = { slashSelection = it },
                            onAccept = {
                                slashMatches.getOrNull(slashSelection)?.let { feature ->
                                    controller.applyFeature(feature, triggerStart)
                                }
                            },
                            onDismiss = { dismissedTrigger = triggerStart },
                        )
                        .insertSpacesForTab(settings.tabWidth, document.text) { updated ->
                            controller.onTextChanged(document.id, updated)
                        }
                        .padding(
                            start = Tokens.Spacing.Tiny,
                            end = Tokens.Spacing.Medium,
                            top = EditorTopPadding,
                            bottom = Tokens.Spacing.XXLarge,
                        ),
                    undecorated = true,
                    visualTransformation = transformation,
                    onTextLayout = { result -> layout = result },
                    textStyle = textStyle,
                    // Soft wrap off means long lines scroll rather than fold. The gutter reads the
                    // same layout either way, so its numbers follow without knowing which is on.
                    maxLines = if (settings.wordWrap) Int.MAX_VALUE else 1,
                )
            }
        }

        if (slashOpen) {
            SlashMenu(
                matches = slashMatches,
                selected = slashSelection,
                onPick = { feature -> controller.applyFeature(feature, triggerStart) },
                modifier = Modifier.offset {
                    caretMenuOffset(
                        layout = layout,
                        caretOffset = triggerStart ?: 0,
                        textAreaOrigin = textAreaOrigin,
                        editorOrigin = editorOrigin,
                        scrollOffset = scrollState.value,
                        viewportHeight = editorHeight,
                        viewportWidth = editorWidth,
                    )
                },
            )
        }
    }
}

/** How tall the slash menu can be, mirroring the value the menu itself uses. */
private val SlashMenuHeight = 220.dp

/**
 * Where the slash menu goes: under the line the `/` is on, aligned with it.
 *
 * Computed from the text field's own layout rather than from a guess about font metrics, so it
 * lands correctly with any font, any size, and a wrapped line above it. Two adjustments matter:
 * the scroll offset, because the layout is in the scrolled content's coordinates and the menu is
 * drawn in the viewport's; and the flip upwards when there is no room below, without which the menu
 * is drawn off the bottom of the window exactly when the caret is near it.
 */
private fun androidx.compose.ui.unit.Density.caretMenuOffset(
    layout: TextLayoutResult?,
    caretOffset: Int,
    textAreaOrigin: Offset,
    editorOrigin: Offset,
    scrollOffset: Int,
    viewportHeight: Int,
    viewportWidth: Int,
): IntOffset {
    val result = layout ?: return IntOffset.Zero
    val offset = caretOffset.coerceIn(0, result.layoutInput.text.length)
    val line = runCatching { result.getLineForOffset(offset) }.getOrNull() ?: return IntOffset.Zero

    val left = textAreaOrigin.x - editorOrigin.x + runCatching {
        result.getHorizontalPosition(offset, usePrimaryDirection = true)
    }.getOrDefault(0f)

    val lineTop = result.getLineTop(line) + EditorTopPadding.toPx() - scrollOffset
    val lineBottom = result.getLineBottom(line) + EditorTopPadding.toPx() - scrollOffset

    val height = SlashMenuHeight.toPx()
    val below = lineBottom + Tokens.Spacing.Tiny.toPx()
    val top = if (below + height > viewportHeight && lineTop - height > 0f) {
        lineTop - height - Tokens.Spacing.Tiny.toPx()
    } else {
        below
    }

    // Pinned inside the pane. In a split view the editor can be a third of the window, and a menu
    // anchored at the caret runs straight off the right-hand edge -- where it is not clipped so much
    // as absent, because it is drawn inside the pane's own bounds.
    val menuWidth = SlashMenuWidth.toPx()
    val maximumLeft = (viewportWidth - menuWidth - Tokens.Spacing.Tiny.toPx()).coerceAtLeast(0f)

    return IntOffset(
        left.coerceIn(0f, maximumLeft).toInt(),
        top.coerceAtLeast(0f).toInt(),
    )
}

/**
 * Hands keystrokes to Vim.
 *
 * The character comes from `utf16CodePoint` rather than from a table mapping `Key` constants back
 * to letters, because a keyboard is not a US keyboard: on a French or Korean layout the key that
 * produces `w` is not `Key.W`, and a Vim mode built on key codes would move by words only for some
 * of the people using it.
 *
 * Insert mode returns false from [onKey], which is what lets every ordinary keystroke — including
 * everything an input method produces — reach the text field untouched.
 */
private fun Modifier.vimKeys(enabled: Boolean, onKey: (Vim.Key) -> Boolean): Modifier = if (!enabled) {
    this
} else {
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

        val special = when (event.key) {
            Key.Escape -> Vim.Special.ESCAPE
            Key.Enter, Key.NumPadEnter -> Vim.Special.ENTER
            Key.Backspace -> Vim.Special.BACKSPACE
            Key.Tab -> Vim.Special.TAB
            else -> null
        }

        val codePoint = event.utf16CodePoint
        val character = when {
            special != null -> null
            // Control characters and the private-use codes an unmapped key produces are not text.
            codePoint in 32..0xE000 -> codePoint.toChar()
            else -> null
        }

        if (special == null && character == null) {
            false
        } else {
            onKey(Vim.Key(character = character, special = special, ctrl = event.isCtrlPressed))
        }
    }
}

/**
 * The paragraph the caret is in, which is what Focus Mode leaves undimmed.
 *
 * A paragraph rather than a line, because a sentence being written usually spans several lines and
 * dimming the rest of it as the caret moves would make the text flicker as somebody types.
 */
internal fun paragraphAround(text: String, caret: Int): IntRange {
    val at = caret.coerceIn(0, text.length)

    var start = at
    while (start > 0) {
        val lineStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        // A blank line ends the paragraph above it.
        if (lineStart == start - 1 && start >= 1) break
        if (lineStart == 0) {
            start = 0
            break
        }
        start = lineStart
        if (start >= 1 && text[start - 1] == '\n' && (start < 2 || text[start - 2] == '\n')) break
    }

    var end = at
    while (end < text.length) {
        val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
        if (lineEnd >= text.length) {
            end = text.length
            break
        }
        if (lineEnd + 1 >= text.length || text[lineEnd + 1] == '\n') {
            end = lineEnd
            break
        }
        end = lineEnd + 1
    }

    return start.coerceAtMost(end) until end.coerceAtLeast(start).coerceAtMost(text.length) + 1
}

/**
 * Reads the slash menu's keys before the text field sees them.
 *
 * Only while the menu is open: with it closed, every one of these keys means what it always means,
 * and a modifier that swallowed Enter unconditionally would be a broken editor.
 */
private fun Modifier.slashMenuKeys(
    open: Boolean,
    count: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
): Modifier = if (!open) {
    this
} else {
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) {
            false
        } else if (event.key == Key.Escape) {
            onDismiss()
            true
        } else {
            navigateFeatureList(event, count, selected, onSelect, onAccept)
        }
    }
}

/**
 * Replaces the Tab key with [width] spaces.
 *
 * A literal tab in Markdown is genuinely ambiguous — four spaces of indentation start a code block,
 * and a tab may or may not reach four columns depending on who opens the file. That is also why the
 * engine reports one as a weak warning; inserting spaces means the editor does not create the
 * problem the inspection then reports.
 *
 * The event is consumed either way, so Tab never moves focus out of the editor.
 */
@Composable
private fun Modifier.insertSpacesForTab(
    width: Int,
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
): Modifier {
    // The handler outlives any one composition, so it reads the latest value and callback rather
    // than the ones captured when the modifier was built — otherwise every Tab after the first
    // would apply to the text as it was when the editor last recomposed.
    val latestValue by rememberUpdatedState(value)
    val latestOnChange by rememberUpdatedState(onChange)

    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

        when (event.key) {
            Key.Tab -> {
                val spaces = " ".repeat(width.coerceIn(1, 16))
                val current = latestValue
                val start = current.selection.min.coerceIn(0, current.text.length)
                val end = current.selection.max.coerceIn(start, current.text.length)

                val updated = current.text.replaceRange(start, end, spaces)
                latestOnChange(current.copy(text = updated, selection = TextRange(start + spaces.length)))
                true
            }

            // Enter continues a list, task or quote — and clears the marker when its line is
            // otherwise empty, which is how a writer ends a list by pressing Enter twice. A plain
            // paragraph produces no continuation, and the event falls through to the text field
            // rather than this reimplementing what a newline already does.
            Key.Enter -> {
                if (event.isShiftPressed || event.isCtrlPressed || event.isAltPressed) {
                    false
                } else {
                    MarkdownEdits.continueBlock(latestValue)
                        ?.also(latestOnChange) != null
                }
            }

            else -> false
        }
    }
}

/**
 * Draws the right-margin guide, or nothing when [column] is zero.
 *
 * The line marks where text would pass the configured column, measured in character advances from
 * the text's own left edge — which is why this modifier sits on the text area rather than on the
 * editor, where the gutter's width would shift it.
 */
private fun Modifier.rightMargin(
    column: Int,
    characterWidth: Float,
    palette: EditorPalette,
): Modifier {
    if (column <= 0 || characterWidth <= 0f) return this
    return drawBehind {
        val x = Tokens.Spacing.Tiny.toPx() + column * characterWidth
        if (x < size.width) {
            drawLine(palette.rightMargin, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        }
    }
}

/**
 * Paints the caret row behind the text, the full width of the editor.
 *
 * Drawn from the text field's own layout, so it lands on the visual row the caret is on even when
 * the logical line above it wrapped across three.
 */
private fun Modifier.caretRow(
    layout: TextLayoutResult?,
    caretOffset: Int,
    palette: EditorPalette,
): Modifier = drawBehind {
    val result = layout ?: return@drawBehind
    val offset = caretOffset.coerceIn(0, result.layoutInput.text.length)
    val line = runCatching { result.getLineForOffset(offset) }.getOrNull() ?: return@drawBehind

    val top = result.getLineTop(line) + EditorTopPadding.toPx()
    val height = result.getLineBottom(line) - result.getLineTop(line)
    drawRect(palette.caretRowBackground, Offset(0f, top), Size(size.width, height))
}

/**
 * The line-number gutter.
 *
 * Numbers are placed at the top of each logical line's *first visual row*, taken from the text
 * field's layout, so a wrapped line shows one number and the rows below it stay blank — which is
 * exactly what the IDE does. The caret's own line is drawn in the brighter colour the IDE reserves
 * for it; in a document longer than the window that is the only cue in the gutter telling you where
 * you are.
 */
@Composable
private fun LineGutter(
    layout: TextLayoutResult?,
    caretOffset: Int,
    lineCount: Int,
    palette: EditorPalette,
    textStyle: TextStyle,
) {
    val measurer = rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current

    val gutterHeight = with(density) {
        ((layout?.size?.height ?: 0) + EditorTopPadding.roundToPx()).toDp()
    }

    // Sized from the widest number it will actually draw, not from a fixed guess. A fixed width is
    // right until a document passes the digit count it was chosen for, and then the leading digit of
    // every line number is quietly clipped — which is how this was wrong before.
    val gutterWidth = with(density) {
        val widest = measurer.measure(AnnotatedString("9".repeat(lineCount.toString().length)), textStyle)
        (widest.size.width.toDp() + GutterLeftInset + GutterRightInset).coerceAtLeast(Tokens.GutterMinWidth)
    }

    Canvas(
        Modifier.width(gutterWidth)
            .height(gutterHeight)
            .background(palette.gutterBackground),
    ) {
        val result = layout ?: return@Canvas
        drawLineNumbers(result, caretOffset, palette, textStyle, measurer)
    }
}

/** Space between the panel edge and the leftmost digit a line number can reach. */
private val GutterLeftInset = Tokens.Spacing.Small

/** Space between the last digit and the text it numbers. */
private val GutterRightInset = Tokens.Spacing.Small

/** Draws one right-aligned number per logical line, at that line's first visual row. */
private fun DrawScope.drawLineNumbers(
    layout: TextLayoutResult,
    caretOffset: Int,
    palette: EditorPalette,
    textStyle: TextStyle,
    measurer: androidx.compose.ui.text.TextMeasurer,
) {
    val text = layout.layoutInput.text.text
    val caretLine = runCatching {
        layout.getLineForOffset(caretOffset.coerceIn(0, text.length))
    }.getOrNull()

    val topInset = EditorTopPadding.toPx()
    val rightInset = GutterRightInset.toPx()

    var lineNumber = 1
    var offset = 0

    while (offset <= text.length) {
        val visualLine = runCatching { layout.getLineForOffset(offset) }.getOrNull() ?: break
        val label = lineNumber.toString()

        val measured = measurer.measure(
            text = AnnotatedString(label),
            style = textStyle.copy(
                color = if (visualLine == caretLine) {
                    palette.gutterCurrentLineForeground
                } else {
                    palette.gutterForeground
                },
            ),
        )

        drawText(
            textLayoutResult = measured,
            topLeft = Offset(
                x = size.width - rightInset - measured.size.width,
                y = topInset + layout.getLineTop(visualLine),
            ),
        )

        val newline = text.indexOf('\n', offset)
        if (newline < 0) break
        offset = newline + 1
        lineNumber++
    }
}

/**
 * Applies the engine's semantic spans to the editor text.
 *
 * The spans are always one derivation behind the text they describe, because they are computed
 * asynchronously. Clamping every range to the current length is what makes that safe: a span left
 * over from a previous edit paints slightly wrong for a frame instead of throwing an
 * IndexOutOfBoundsException deep inside text layout.
 */
internal class MarkdownVisualTransformation(
    private val spans: List<StyleSpan>,
    private val palette: EditorPalette,
    /** In Focus Mode, the paragraph to leave at full strength; everything else is dimmed. */
    private val focusRange: IntRange? = null,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        if (spans.isEmpty() && focusRange == null) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        val builder = AnnotatedString.Builder(text)
        val length = text.length
        for (span in spans) {
            val start = span.start.coerceIn(0, length)
            val end = span.end.coerceIn(start, length)
            if (start == end) continue
            val style = palette.styleFor(span.styleId) ?: continue
            builder.addStyle(
                SpanStyle(
                    color = style.color,
                    fontWeight = style.fontWeight,
                    fontStyle = style.fontStyle,
                    textDecoration = style.decoration,
                ),
                start,
                end,
            )
        }
        // Focus dimming is applied last, over the syntax colouring, so a heading outside the
        // current paragraph recedes with everything else rather than staying bright.
        focusRange?.let { range ->
            val before = range.first.coerceIn(0, length)
            val after = (range.last + 1).coerceIn(before, length)
            if (before > 0) builder.addStyle(SpanStyle(color = palette.dimmed), 0, before)
            if (after < length) builder.addStyle(SpanStyle(color = palette.dimmed), after, length)
        }

        // The transformation only adds styling, never changes character positions, so offsets map
        // one to one and the caret stays where the user put it.
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }

    override fun equals(other: Any?): Boolean =
        other is MarkdownVisualTransformation &&
            other.spans == spans &&
            other.palette === palette &&
            other.focusRange == focusRange

    override fun hashCode(): Int = 31 * (31 * spans.hashCode() + palette.hashCode()) + focusRange.hashCode()
}
