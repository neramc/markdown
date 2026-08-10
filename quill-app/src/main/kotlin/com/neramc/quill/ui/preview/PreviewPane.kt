package com.neramc.quill.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neramc.quill.QuillController
import com.neramc.quill.bridge.wire.ColumnAlignment
import com.neramc.quill.bridge.wire.InlineIr
import com.neramc.quill.bridge.wire.MarkdownBlockIr
import com.neramc.quill.model.DocumentSession
import com.neramc.quill.model.WorkspaceState
import com.neramc.quill.ui.theme.LocalEditorPalette
import com.neramc.quill.ui.theme.LocalShellPalette
import java.awt.Desktop
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.jetbrains.jewel.foundation.code.MimeType
import org.jetbrains.jewel.foundation.code.highlighting.CodeHighlighter
import org.jetbrains.jewel.intui.markdown.standalone.ProvideMarkdownStyling
import org.jetbrains.jewel.intui.markdown.standalone.dark
import org.jetbrains.jewel.intui.markdown.standalone.light
import org.jetbrains.jewel.intui.markdown.standalone.styling.dark
import org.jetbrains.jewel.intui.markdown.standalone.styling.extensions.github.alerts.dark
import org.jetbrains.jewel.intui.markdown.standalone.styling.extensions.github.alerts.light
import org.jetbrains.jewel.intui.markdown.standalone.styling.light
import org.jetbrains.jewel.markdown.extensions.github.alerts.AlertStyling
import org.jetbrains.jewel.markdown.extensions.github.alerts.GitHubAlertRendererExtension
import org.jetbrains.jewel.markdown.extensions.github.strikethrough.GitHubStrikethroughRendererExtension
import org.jetbrains.jewel.markdown.rendering.InlineMarkdownRenderer
import org.jetbrains.jewel.markdown.rendering.MarkdownBlockRenderer
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling
import org.jetbrains.jewel.markdown.rendering.create
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer

/**
 * The rendered Markdown preview.
 *
 * Blocks come from the Rust engine and are mapped onto Jewel's model by [IrToJewel], so the preview
 * gets the IntelliJ Platform's own Markdown styling without Jewel ever parsing anything.
 */
@Composable
public fun PreviewPane(
    controller: QuillController,
    workspace: WorkspaceState,
    document: DocumentSession,
    modifier: Modifier = Modifier,
) {
    val dark = workspace.settings.darkTheme
    val editorPalette = LocalEditorPalette.current

    val styling = remember(dark) { if (dark) MarkdownStyling.dark() else MarkdownStyling.light() }
    val alertStyling = remember(dark) { if (dark) AlertStyling.dark() else AlertStyling.light() }

    val rendererExtensions = remember(styling, alertStyling) {
        listOf(GitHubAlertRendererExtension(alertStyling, styling), GitHubStrikethroughRendererExtension)
    }

    val blockRenderer = remember(styling, rendererExtensions, dark) {
        val inlineRenderer = InlineMarkdownRenderer.create(rendererExtensions)
        if (dark) {
            MarkdownBlockRenderer.dark(styling, rendererExtensions, inlineRenderer)
        } else {
            MarkdownBlockRenderer.light(styling, rendererExtensions, inlineRenderer)
        }
    }

    val highlighter = remember(controller, dark) { EngineCodeHighlighter(controller) }
    val items = remember(document.blocks) { IrToJewel.toPreviewItems(document.blocks) }
    val listState = rememberLazyListState()

    ProvideMarkdownStyling(
        markdownStyling = styling,
        markdownBlockRenderer = blockRenderer,
        codeHighlighter = highlighter,
    ) {
        Box(modifier.background(editorPalette.background)) {
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nothing to preview yet", color = LocalShellPalette.current.mutedText)
                }
            } else {
                VerticallyScrollableContainer(scrollState = listState, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                    ) {
                        items(items.size, key = { it }) { index ->
                            when (val item = items[index]) {
                                is IrToJewel.PreviewItem.Block ->
                                    with(blockRenderer) {
                                        RenderBlock(
                                            block = item.block,
                                            enabled = true,
                                            onUrlClick = ::openInBrowser,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                is IrToJewel.PreviewItem.Table ->
                                    MarkdownTable(item.table, Modifier.fillMaxWidth().padding(vertical = 8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renders a GFM table.
 *
 * Jewel's table extension keeps its node type internal, so rather than depending on an
 * implementation detail Quill draws the table itself using the shell palette, which keeps it
 * visually consistent with the rest of the IntelliJ styling.
 */
@Composable
private fun MarkdownTable(table: MarkdownBlockIr.Table, modifier: Modifier = Modifier) {
    val shell = LocalShellPalette.current
    val editor = LocalEditorPalette.current

    Column(modifier.border(1.dp, shell.border)) {
        table.rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth()
                    .background(if (row.isHeader) shell.toolWindowBackground else editor.background)
            ) {
                row.cells.forEachIndexed { columnIndex, cell ->
                    val alignment = table.alignments.getOrElse(columnIndex) { ColumnAlignment.NONE }
                    Box(
                        Modifier.weight(1f).widthIn(min = 48.dp)
                            .border(1.dp, shell.border)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = plainText(cell),
                            modifier = Modifier.fillMaxWidth(),
                            fontSize = 14.sp,
                            color = editor.text,
                            textAlign = when (alignment) {
                                ColumnAlignment.LEFT -> TextAlign.Start
                                ColumnAlignment.CENTER -> TextAlign.Center
                                ColumnAlignment.RIGHT -> TextAlign.End
                                ColumnAlignment.NONE -> TextAlign.Start
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Flattens a table cell's inlines to text; cell content is short enough not to need full styling. */
private fun plainText(cell: MarkdownBlockIr.TableCell): String = buildString {
    fun walk(inlines: List<InlineIr>) {
        inlines.forEach { inline ->
            when (inline) {
                is InlineIr.Text -> append(inline.content)
                is InlineIr.Code -> append(inline.content)
                is InlineIr.Emphasis -> walk(inline.children)
                is InlineIr.StrongEmphasis -> walk(inline.children)
                is InlineIr.Strikethrough -> walk(inline.children)
                is InlineIr.Link -> walk(inline.children)
                is InlineIr.Image -> append(inline.alt)
                else -> Unit
            }
        }
    }
    walk(cell.inlines)
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
 * Jewel calls this for every fenced code block in the preview; syntect does the work and returns
 * colours already resolved against the active IntelliJ scheme. Highlighting runs on
 * [Dispatchers.Default] so a large code block never blocks composition.
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
