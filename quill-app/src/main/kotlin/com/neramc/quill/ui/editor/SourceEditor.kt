package com.neramc.quill.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neramc.quill.QuillController
import com.neramc.quill.bridge.wire.StyleSpan
import com.neramc.quill.model.DocumentSession
import com.neramc.quill.model.WorkspaceState
import com.neramc.quill.ui.theme.EditorPalette
import com.neramc.quill.ui.theme.IdeaMetrics
import com.neramc.quill.ui.theme.LocalEditorPalette
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer

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
    val scrollState = rememberScrollState()
    val lineCount = remember(document.text.text) { document.text.text.count { it == '\n' } + 1 }
    val transformation = remember(document.spans, palette) { MarkdownVisualTransformation(document.spans, palette) }
    val caretLine = document.caretPosition.line

    Box(modifier.background(palette.background)) {
        VerticallyScrollableContainer(scrollState = scrollState, modifier = Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth()) {
                if (workspace.settings.showLineNumbers) {
                    LineGutter(lineCount, caretLine, palette, workspace.settings.editorFontSize)
                }
                TextArea(
                    value = document.text,
                    onValueChange = { controller.onTextChanged(document.id, it) },
                    modifier = Modifier.weight(1f).padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 24.dp),
                    undecorated = true,
                    visualTransformation = transformation,
                    textStyle = JewelTheme.editorTextStyle.copy(
                        fontSize = workspace.settings.editorFontSize.sp,
                        color = palette.text,
                    ),
                )
            }
        }
    }
}

/**
 * The line-number gutter.
 *
 * Numbers are right-aligned against the text, and the caret's own line is drawn in the brighter
 * colour the IDE reserves for it — which is the only cue in the gutter that tells you where you are
 * when the document is longer than the window.
 */
@Composable
private fun LineGutter(lineCount: Int, caretLine: Int, palette: EditorPalette, fontSize: Int) {
    Column(
        modifier = Modifier.fillMaxHeight().widthIn(min = IdeaMetrics.GutterMinWidth)
            .background(palette.gutterBackground)
            .padding(start = 10.dp, end = 10.dp, top = 6.dp),
        horizontalAlignment = Alignment.End,
    ) {
        for (line in 1..lineCount) {
            Text(
                text = line.toString(),
                style = JewelTheme.editorTextStyle.copy(fontSize = fontSize.sp),
                color = if (line - 1 == caretLine) {
                    palette.gutterCurrentLineForeground
                } else {
                    palette.gutterForeground
                },
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
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
