package dev.starfect.quill.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import dev.starfect.quill.ui.theme.IdeaMetrics
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
    val transformation = remember(document.spans, palette) { MarkdownVisualTransformation(document.spans, palette) }

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

    // The advance of one character in the editor's font, which is what a column-based right margin
    // has to be measured in. Monospace is assumed because the editor font is; a proportional font
    // has no column to draw a guide at.
    val measurer = rememberTextMeasurer()
    val characterWidth = remember(textStyle) {
        measurer.measure(AnnotatedString("0"), textStyle).size.width.toFloat()
    }

    Box(modifier.background(palette.background)) {
        VerticallyScrollableContainer(scrollState = scrollState, modifier = Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth()) {
                if (settings.showLineNumbers) {
                    LineGutter(
                        layout = layout,
                        caretOffset = document.caretPosition.offset,
                        palette = palette,
                        textStyle = textStyle,
                    )
                }

                TextArea(
                    value = document.text,
                    onValueChange = { controller.onTextChanged(document.id, it) },
                    modifier = Modifier.weight(1f)
                        .caretRow(
                            layout = layout.takeIf { settings.highlightCaretRow },
                            caretOffset = document.caretPosition.offset,
                            palette = palette,
                        )
                        .rightMargin(settings.visualGuideColumn, characterWidth, palette)
                        .insertSpacesForTab(settings.tabWidth, document.text) { updated ->
                            controller.onTextChanged(document.id, updated)
                        }
                        .padding(start = 6.dp, end = 12.dp, top = EditorTopPadding, bottom = 24.dp),
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
        if (event.type != KeyEventType.KeyDown || event.key != Key.Tab) return@onPreviewKeyEvent false

        val spaces = " ".repeat(width.coerceIn(1, 16))
        val current = latestValue
        val start = current.selection.min.coerceIn(0, current.text.length)
        val end = current.selection.max.coerceIn(start, current.text.length)

        val updated = current.text.replaceRange(start, end, spaces)
        latestOnChange(
            current.copy(text = updated, selection = TextRange(start + spaces.length)),
        )
        true
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
        val x = 6.dp.toPx() + column * characterWidth
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
    palette: EditorPalette,
    textStyle: TextStyle,
) {
    val measurer = rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current

    val gutterHeight = with(density) {
        ((layout?.size?.height ?: 0) + EditorTopPadding.roundToPx()).toDp()
    }

    Canvas(
        Modifier.widthIn(min = IdeaMetrics.GutterMinWidth)
            .height(gutterHeight)
            .background(palette.gutterBackground),
    ) {
        val result = layout ?: return@Canvas
        drawLineNumbers(result, caretOffset, palette, textStyle, measurer)
    }
}

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
    val rightInset = 10.dp.toPx()

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
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        if (spans.isEmpty()) {
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
        // The transformation only adds styling, never changes character positions, so offsets map
        // one to one and the caret stays where the user put it.
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }

    override fun equals(other: Any?): Boolean =
        other is MarkdownVisualTransformation && other.spans == spans && other.palette === palette

    override fun hashCode(): Int = 31 * spans.hashCode() + palette.hashCode()
}
