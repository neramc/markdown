package dev.starfect.quill.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.starfect.quill.bridge.wire.OutlineEntry
import dev.starfect.quill.ui.theme.EditorPalette
import dev.starfect.quill.ui.theme.LocalTypeScale
import kotlinx.coroutines.launch
import org.jetbrains.jewel.ui.component.Text

/**
 * The heading you are underneath, kept on screen.
 *
 * In a document longer than the window, the single most useful thing to know is which section the
 * text under the caret belongs to — and that is exactly the line that has scrolled away. VS Code
 * calls this sticky scroll; it is the same idea as a frozen header row, and it costs one row of
 * height to answer a question that otherwise needs a scroll up and a scroll back.
 *
 * The chain is drawn, not just the innermost heading. "Options" means something different under
 * "Installing" than under "Building", and a document deep enough to need this is deep enough for
 * that to matter.
 *
 * Headings come from the outline the engine already produces, so this costs a lookup rather than a
 * parse, and it agrees with the Structure panel by construction.
 */
@Composable
public fun StickyHeadings(
    outline: List<OutlineEntry>,
    firstVisibleLine: Int,
    palette: EditorPalette,
    modifier: Modifier = Modifier,
) {
    val chain = remember(outline, firstVisibleLine) { headingChain(outline, firstVisibleLine) }
    if (chain.isEmpty()) return

    Row(
        modifier.fillMaxWidth()
            .background(palette.gutterBackground)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chain.forEachIndexed { index, entry ->
            if (index > 0) {
                Text(" › ", color = palette.gutterForeground, fontSize = LocalTypeScale.current.medium)
            }
            Text(
                entry.title,
                color = if (index == chain.lastIndex) palette.text else palette.gutterForeground,
                fontSize = LocalTypeScale.current.medium,
            )
        }
    }
}

/**
 * The headings enclosing [line], outermost first.
 *
 * A heading is only enclosing if it is *above* the line and nothing of the same or shallower level
 * has intervened — which is what makes the chain a path through the document's structure rather
 * than a list of the last few headings seen.
 */
internal fun headingChain(outline: List<OutlineEntry>, line: Int): List<OutlineEntry> {
    val chain = ArrayList<OutlineEntry>()
    for (entry in outline) {
        // A heading on the first visible line is not scrolled away, so it needs no reminder.
        if (entry.line >= line) break
        while (chain.isNotEmpty() && chain.last().level >= entry.level) {
            chain.removeAt(chain.lastIndex)
        }
        chain += entry
    }
    return chain
}

/**
 * A miniature of the whole document down the right edge.
 *
 * Not a scaled-down rendering of the text — at this width the glyphs would be noise. One bar per
 * line, as long as the line is and indented as the line is, which is enough to recognise the shape
 * of a document you have seen before: where the code blocks are, where the lists are, where the
 * long paragraph you were editing sits relative to everything else.
 *
 * The viewport is drawn as a lighter panel over it, and a click or a drag moves it, so the minimap
 * doubles as the scrollbar it sits next to.
 */
@Composable
public fun Minimap(
    text: String,
    scrollState: ScrollState,
    palette: EditorPalette,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val lines = remember(text) { summarise(text) }
    if (lines.isEmpty()) return

    Canvas(
        modifier.width(MinimapWidth).fillMaxHeight()
            .background(palette.background)
            .pointerInput(scrollState.maxValue) {
                detectTapGestures { position -> scope.launch { scrollTo(scrollState, position.y, size.height) } }
            }
            .pointerInput(scrollState.maxValue) {
                detectDragGestures { change, _ ->
                    scope.launch { scrollTo(scrollState, change.position.y, size.height) }
                }
            },
    ) {
        // Every line has to fit, however many there are, so the row height is derived rather than
        // fixed. Below a pixel the bars merge into a block, which is still the right picture.
        val rowHeight = (size.height / lines.size).coerceIn(0.4f, 3f)
        val usableWidth = size.width - MinimapPadding * 2

        lines.forEachIndexed { index, line ->
            if (line.length == 0) return@forEachIndexed
            val top = index * rowHeight
            if (top > size.height) return@forEachIndexed

            val indent = (line.indent / MaxIndentColumns.toFloat()).coerceIn(0f, 0.5f) * usableWidth
            val width = ((line.length / MaxLineColumns.toFloat()).coerceIn(0.02f, 1f)) *
                (usableWidth - indent)

            drawRect(
                color = when (line.kind) {
                    LineKind.HEADING -> palette.text
                    LineKind.CODE -> palette.gutterForeground
                    LineKind.TEXT -> palette.text.copy(alpha = 0.45f)
                },
                topLeft = Offset(MinimapPadding + indent, top),
                size = Size(width, (rowHeight * 0.7f).coerceAtLeast(0.5f)),
            )
        }

        // Where you are. Drawn over the bars rather than under them so it reads as a lens.
        val total = (scrollState.maxValue + size.height).coerceAtLeast(1f)
        val visibleFraction = (size.height / total).coerceIn(0.02f, 1f)
        val offsetFraction = if (scrollState.maxValue == 0) 0f else scrollState.value / total
        drawRect(
            color = Color.White.copy(alpha = 0.07f),
            topLeft = Offset(0f, offsetFraction * size.height),
            size = Size(size.width, visibleFraction * size.height),
        )
    }
}

private suspend fun scrollTo(scrollState: ScrollState, y: Float, height: Int) {
    if (height <= 0) return
    val fraction = (y / height).coerceIn(0f, 1f)
    scrollState.scrollTo((fraction * scrollState.maxValue).toInt())
}

private val MinimapWidth = 74.dp
private const val MinimapPadding = 6f

/** Wider than this and the bar is full length; the exact number only sets the scale. */
private const val MaxLineColumns = 90

/** Deeper than this and the indent stops growing, or one nested list eats the whole width. */
private const val MaxIndentColumns = 24

private enum class LineKind { HEADING, CODE, TEXT }

private class LineSummary(val length: Int, val indent: Int, val kind: LineKind)

/**
 * One summary per line: how long, how far in, and what sort of line it is.
 *
 * Deliberately a scan of the text rather than a walk of the parsed document. The minimap wants a
 * picture of every line including the blank ones and the half-written ones, and it wants it on the
 * frame the text changed — not one derivation later, which would leave it visibly lagging the text
 * beside it.
 */
private fun summarise(text: String): List<LineSummary> {
    var inFence = false
    return text.lineSequence().map { line ->
        val trimmed = line.trimStart()
        val indent = line.length - trimmed.length

        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            inFence = !inFence
            return@map LineSummary(trimmed.length, indent, LineKind.CODE)
        }

        val kind = when {
            inFence -> LineKind.CODE
            trimmed.startsWith("#") -> LineKind.HEADING
            else -> LineKind.TEXT
        }
        LineSummary(trimmed.length, indent, kind)
    }.toList()
}
