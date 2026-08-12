package dev.starfect.quill.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.starfect.quill.QuillController
import dev.starfect.quill.model.DocumentSession
import dev.starfect.quill.model.WorkspaceState
import dev.starfect.quill.ui.theme.EditorPalette
import dev.starfect.quill.ui.theme.LocalEditorPalette
import dev.starfect.quill.ui.theme.LocalShellPalette
import dev.starfect.quill.ui.theme.ShellPalette
import java.awt.Cursor
import java.awt.Desktop
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.jetbrains.jewel.foundation.code.MimeType
import org.jetbrains.jewel.foundation.code.highlighting.CodeHighlighter
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer

/**
 * The rendered preview.
 *
 * The engine converts the document to HTML and parses it back into a DOM; [HtmlRenderer] flattens
 * that into blocks and this draws them. Going through HTML rather than rendering the Markdown AST is
 * what makes raw HTML in the source appear as markup, what makes the flavour extensions render, and
 * what keeps this pane and the exported file the same document.
 */
@Composable
public fun PreviewPane(
    controller: QuillController,
    workspace: WorkspaceState,
    document: DocumentSession,
    modifier: Modifier = Modifier,
) {
    val editor = LocalEditorPalette.current
    val shell = LocalShellPalette.current

    val blocks = remember(document.html) { HtmlRenderer.toBlocks(document.html) }
    val listState = rememberLazyListState()
    val highlighter = remember(controller) { EngineCodeHighlighter(controller) }
    val baseSize = workspace.settings.editorFontSize.sp

    // Follow the caret. The section the caret is in is the one the reader wants to see, and keeping
    // the two panes on the same part of the document is most of what makes a split view useful
    // rather than merely two views.
    if (workspace.settings.syncScrolling) {
        val caretLine = document.caretPosition.line
        val headingIndex = remember(document.outline, caretLine) {
            document.outline.indexOfLast { it.line <= caretLine }
        }

        LaunchedEffect(headingIndex, blocks) {
            if (headingIndex >= 0 && blocks.isNotEmpty()) {
                listState.animateScrollToItem(HtmlRenderer.blockForHeading(blocks, headingIndex))
            }
        }
    }

    Box(modifier.background(editor.background)) {
        if (blocks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing to preview yet", color = shell.mutedText)
            }
        } else {
            VerticallyScrollableContainer(scrollState = listState, modifier = Modifier.fillMaxSize()) {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(blocks.size, key = { it }) { index ->
                            RenderBlock(blocks[index], editor, shell, baseSize, highlighter)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Draws one block.
 *
 * A quoted block is drawn by this composable rather than by a wrapper, because the block list is
 * flat: the quote bar is painted as leading padding plus a rule, at the depth the block records.
 */
@Composable
private fun RenderBlock(
    block: PreviewBlock,
    editor: EditorPalette,
    shell: ShellPalette,
    baseSize: androidx.compose.ui.unit.TextUnit,
    highlighter: EngineCodeHighlighter,
) {
    Quoted(block.quote, shell) {
        when (block) {
            is PreviewBlock.Heading -> HeadingBlock(block, editor, shell, baseSize)
            is PreviewBlock.Paragraph -> ParagraphBlock(block, editor, shell, baseSize)
            is PreviewBlock.Code -> CodeBlock(block, editor, shell, highlighter)
            is PreviewBlock.Table -> TableBlock(block, editor, shell, baseSize)
            is PreviewBlock.ThematicBreak ->
                Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(1.dp).background(shell.border))
            is PreviewBlock.Callout -> CalloutBlock(block, editor, shell, baseSize, highlighter)
        }
    }
}

/**
 * Wraps [content] in one quote bar per level of nesting.
 *
 * `IntrinsicSize.Min` is what lets the bar match the height of the block beside it: without it a
 * `fillMaxHeight` child inside a Row whose height comes from its content has nothing to fill.
 */
@Composable
private fun Quoted(depth: Int, shell: ShellPalette, content: @Composable () -> Unit) {
    if (depth <= 0) {
        content()
        return
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        repeat(depth) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(shell.accent))
            Spacer(Modifier.width(9.dp))
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun HeadingBlock(
    block: PreviewBlock.Heading,
    editor: EditorPalette,
    shell: ShellPalette,
    baseSize: androidx.compose.ui.unit.TextUnit,
) {
    // The same ratios the IntelliJ Markdown preview uses, scaled off the editor's own font size so
    // the preview grows with it rather than staying fixed while the source changes size.
    val scale = when (block.level) {
        1 -> 1.85f
        2 -> 1.5f
        3 -> 1.28f
        4 -> 1.12f
        5 -> 1.0f
        else -> 0.94f
    }
    val top = if (block.level <= 2) 20.dp else 14.dp

    Column(Modifier.fillMaxWidth().padding(top = top, bottom = 6.dp)) {
        InlineText(
            styled = block.text,
            palette = editor,
            shell = shell,
            fontSize = baseSize * scale,
            fontWeight = FontWeight.Bold,
        )
        // Only the top two levels get a rule, matching how the platform renders them.
        if (block.level <= 2) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(shell.border))
        }
    }
}

@Composable
private fun ParagraphBlock(
    block: PreviewBlock.Paragraph,
    editor: EditorPalette,
    shell: ShellPalette,
    baseSize: androidx.compose.ui.unit.TextUnit,
) {
    val indent = (block.indent * 20).dp

    Row(
        Modifier.fillMaxWidth().padding(start = indent, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        when {
            block.task != null -> {
                TaskCheckbox(checked = block.task, shell = shell, editor = editor)
                Spacer(Modifier.width(7.dp))
            }
            block.marker != null -> {
                Text(
                    text = block.marker,
                    color = shell.mutedText,
                    fontSize = baseSize,
                    modifier = Modifier.widthIn(min = 18.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
        }

        InlineText(
            styled = block.text,
            palette = editor,
            shell = shell,
            fontSize = baseSize,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A task-list checkbox, drawn rather than composed so it matches the preview's own metrics. */
@Composable
private fun TaskCheckbox(checked: Boolean, shell: ShellPalette, editor: EditorPalette) {
    Box(
        Modifier.padding(top = 3.dp)
            .size(13.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(if (checked) shell.accent else editor.background)
            // `splitter`, not `border`. Border is the shell's *dark* separator — the line drawn
            // between two panels of the same tone — and it is the editor's own colour, so an
            // unchecked box outlined with it was invisible against the page it sat on: the task
            // list rendered as indented prose with no boxes at all. A control outline needs the
            // colour that shows against a surface.
            .border(1.dp, if (checked) shell.accent else shell.splitter, RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Text("✓", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CodeBlock(
    block: PreviewBlock.Code,
    editor: EditorPalette,
    shell: ShellPalette,
    highlighter: EngineCodeHighlighter,
) {
    var highlighted by remember(block.code, block.language) { mutableStateOf(AnnotatedString(block.code)) }

    LaunchedEffect(block.code, block.language) {
        highlighter.highlight(block.code, block.language.orEmpty()).collect { highlighted = it }
    }

    val scroll = rememberScrollState()

    Column(Modifier.fillMaxWidth().padding(start = (block.indent * 20).dp, top = 6.dp, bottom = 6.dp)) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(shell.toolWindowBackground)
                .border(1.dp, shell.border, RoundedCornerShape(8.dp))
        ) {
            if (!block.language.isNullOrBlank()) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(shell.tabBarBackground)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(block.language, color = shell.mutedText, fontSize = 11.sp)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(shell.border))
            }

            // Code must not wrap: a wrapped line changes what the code means to read. It scrolls
            // inside its own container so the preview itself never scrolls sideways.
            Box(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(12.dp)) {
                Text(
                    text = highlighted,
                    color = editor.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun TableBlock(
    block: PreviewBlock.Table,
    editor: EditorPalette,
    shell: ShellPalette,
    baseSize: androidx.compose.ui.unit.TextUnit,
) {
    if (block.rows.isEmpty()) return

    Column(
        Modifier.fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, shell.border, RoundedCornerShape(8.dp))
    ) {
        block.rows.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(shell.border))
            }
            Row(
                Modifier.fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .background(if (row.header) shell.toolWindowBackground else editor.background)
            ) {
                row.cells.forEachIndexed { columnIndex, cell ->
                    if (columnIndex > 0) {
                        Box(Modifier.width(1.dp).fillMaxHeight().background(shell.border))
                    }
                    Box(Modifier.weight(1f).padding(horizontal = 11.dp, vertical = 7.dp)) {
                        InlineText(
                            styled = cell.text,
                            palette = editor,
                            shell = shell,
                            fontSize = baseSize,
                            fontWeight = if (row.header) FontWeight.SemiBold else null,
                            textAlign = when (cell.alignment) {
                                CellAlignment.CENTER -> TextAlign.Center
                                CellAlignment.RIGHT -> TextAlign.End
                                else -> TextAlign.Start
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A GFM alert or Markdoc tag, drawn as a titled panel so the authored structure survives.
 *
 * The accent bar and title take the severity's colour; the body stays the editor's own so a long
 * alert reads as prose rather than as a coloured block.
 */
@Composable
private fun CalloutBlock(
    block: PreviewBlock.Callout,
    editor: EditorPalette,
    shell: ShellPalette,
    baseSize: androidx.compose.ui.unit.TextUnit,
    highlighter: EngineCodeHighlighter,
) {
    val accent = calloutAccent(block.severity, shell)

    Row(
        Modifier.fillMaxWidth()
            .padding(vertical = 8.dp)
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.07f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = block.name,
                color = accent,
                fontSize = baseSize * 0.92f,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            block.children.forEach { child -> RenderBlock(child, editor, shell, baseSize, highlighter) }
        }
    }
}

/** The IntelliJ alert colours, which are the same hues the platform's own Markdown preview uses. */
private fun calloutAccent(severity: CalloutSeverity, shell: ShellPalette): Color = when (severity) {
    CalloutSeverity.NOTE -> shell.accent
    CalloutSeverity.TIP -> Color(0xFF5FAD65)
    CalloutSeverity.IMPORTANT -> Color(0xFF9373E6)
    CalloutSeverity.WARNING -> Color(0xFFD6AE58)
    CalloutSeverity.CAUTION -> shell.error
}

/**
 * Draws a [StyledText], resolving its spans against the palette and making its links clickable.
 *
 * Link colour is applied here rather than in [HtmlRenderer] because the renderer runs off the
 * composition and has no palette; keeping colour out of it also means one parse serves both themes.
 */
@Composable
private fun InlineText(
    styled: StyledText,
    palette: EditorPalette,
    shell: ShellPalette,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
) {
    val annotated = remember(styled, shell) {
        buildAnnotatedString {
            append(styled.text)
            styled.spans.forEach { span ->
                addStyle(span.style, span.start.coerceIn(0, length), span.end.coerceIn(0, length))
            }
            styled.links.forEach { link ->
                addStyle(
                    SpanStyle(color = shell.accent),
                    link.start.coerceIn(0, length),
                    link.end.coerceIn(0, length),
                )
            }
        }
    }

    var layout by remember(annotated) { mutableStateOf<TextLayoutResult?>(null) }

    // The hit test runs against the laid-out text rather than against the model, because a link's
    // screen position is only known after wrapping — the same offset sits on a different line at a
    // different pane width.
    val interactive = if (styled.links.isEmpty()) {
        modifier
    } else {
        modifier
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
            .pointerInput(styled.links) {
                detectTapGestures { position ->
                    val result = layout ?: return@detectTapGestures
                    val offset = result.getOffsetForPosition(position)
                    styled.links.firstOrNull { offset >= it.start && offset < it.end }
                        ?.let { openInBrowser(it.href) }
                }
            }
    }

    Text(
        text = annotated,
        modifier = interactive,
        color = palette.text,
        fontSize = fontSize,
        fontWeight = fontWeight,
        textAlign = textAlign ?: TextAlign.Unspecified,
        onTextLayout = { layout = it },
    )
}

private fun openInBrowser(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}

/**
 * Jewel's code-highlighting seam, backed by the Rust engine.
 *
 * syntect does the work and returns colours already resolved against the active scheme.
 * Highlighting runs on [Dispatchers.Default] so a large code block never blocks composition.
 */
internal class EngineCodeHighlighter(private val controller: QuillController) : CodeHighlighter {

    @Deprecated("Superseded by the overload taking a raw language string.")
    override fun highlight(code: String, mimeType: MimeType?): Flow<AnnotatedString> =
        highlight(code, mimeType?.displayName().orEmpty())

    override fun highlight(code: String, language: String): Flow<AnnotatedString> = flow {
        // Emit the unstyled text first so the block appears immediately, then replace it with the
        // highlighted version. Jewel's contract explicitly supports this progressive pattern.
        emit(AnnotatedString(code))
        val spans = controller.highlightCode(code, language)
        if (spans.isEmpty()) return@flow

        val builder = AnnotatedString.Builder(code)
        for (span in spans) {
            val start = span.start.coerceIn(0, code.length)
            val end = span.end.coerceIn(start, code.length)
            if (start == end) continue
            builder.addStyle(SpanStyle(color = Color(span.argb)), start, end)
        }
        emit(builder.toAnnotatedString())
    }.flowOn(Dispatchers.Default)
}
