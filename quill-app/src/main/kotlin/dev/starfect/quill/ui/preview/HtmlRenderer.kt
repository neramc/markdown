package dev.starfect.quill.ui.preview

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.starfect.quill.bridge.wire.HtmlNode

/**
 * Turns the engine's HTML tree into the flat list of blocks the preview lays out.
 *
 * The tree the engine produces is a document, not a layout: `<blockquote>` holds `<p>`s, `<li>`s
 * hold more lists, and a table is four levels of nesting. Composing that shape directly would mean
 * a recursive composable per element and no way to scroll it lazily, so this pass flattens it once
 * into a list whose every entry draws in constant depth. Indentation and quote depth survive as
 * fields rather than as nesting.
 *
 * Inline content collapses in the same pass: a `<p>` becomes one styled string rather than a row of
 * composables, which is what lets a paragraph wrap as a paragraph instead of as a line of boxes.
 */
internal object HtmlRenderer {

    /** Elements that introduce a block; everything else is treated as inline. */
    private val BLOCK_TAGS = setOf(
        "address", "article", "aside", "blockquote", "details", "div", "dl", "dd", "dt",
        "fieldset", "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6",
        "header", "hr", "li", "main", "nav", "ol", "p", "pre", "section", "summary", "table",
        "tbody", "td", "tfoot", "th", "thead", "tr", "ul",
    )

    /** Flattens [nodes] into the preview's block list. */
    fun toBlocks(nodes: List<HtmlNode>): List<PreviewBlock> {
        val sink = Sink()
        sink.walk(nodes, Context())
        return sink.blocks
    }

    /**
     * The index of the block to scroll to for a source heading position.
     *
     * The rendered HTML carries no source positions — comrak's `sourcepos` is off for the preview
     * because it would put a `data-sourcepos` attribute on every element. Headings are enough: they
     * are what the outline is built from, they are where a reader navigates to, and matching on them
     * needs nothing the engine is not already producing.
     */
    fun blockForHeading(blocks: List<PreviewBlock>, headingIndex: Int): Int {
        if (headingIndex < 0) return 0
        var seen = 0
        blocks.forEachIndexed { index, block ->
            if (block is PreviewBlock.Heading) {
                if (seen == headingIndex) return index
                seen++
            }
        }
        return blocks.lastIndex.coerceAtLeast(0)
    }

    /** Where a block sits relative to the margin, and what encloses it. */
    private data class Context(
        /** List nesting depth; 0 is top level. */
        val indent: Int = 0,
        /** Blockquote nesting depth; 0 is unquoted. */
        val quote: Int = 0,
        /** The marker this block's list item carries, if any. */
        val marker: String? = null,
        /** Checkbox state for a task list item: `null` when the item is not a task. */
        val task: Boolean? = null,
    )

    private class Sink {
        val blocks = mutableListOf<PreviewBlock>()

        fun walk(nodes: List<HtmlNode>, context: Context) {
            // Consecutive inline content between blocks accumulates here and flushes as one
            // paragraph, so `text <em>and</em> more` does not become three separate blocks.
            var pending: MutableList<HtmlNode>? = null

            fun flush() {
                val inline = pending ?: return
                pending = null
                val text = InlineBuilder.build(inline)
                if (text.text.isNotBlank()) {
                    blocks += PreviewBlock.Paragraph(text, context.indent, context.quote, context.marker, context.task)
                }
            }

            for (node in nodes) {
                val element = node as? HtmlNode.Element
                if (element == null || element.tag !in BLOCK_TAGS) {
                    // Whitespace between two blocks is layout, not content; dropping it here stops
                    // the newline comrak puts between elements becoming an empty paragraph.
                    if (node is HtmlNode.Text && node.text.isBlank() && pending == null) continue
                    (pending ?: mutableListOf<HtmlNode>().also { pending = it }) += node
                    continue
                }

                flush()
                block(element, context)
            }

            flush()
        }

        private fun block(element: HtmlNode.Element, context: Context) {
            when (element.tag) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    val level = element.tag[1].digitToInt()
                    blocks += PreviewBlock.Heading(level, InlineBuilder.build(element.children), context.quote)
                }

                "hr" -> blocks += PreviewBlock.ThematicBreak(context.quote)

                "pre" -> {
                    // A fenced block arrives as <pre><code class="language-x">; an indented one as a
                    // bare <pre>. Both render as code, and the class is where the language lives.
                    val code = element.children.filterIsInstance<HtmlNode.Element>().firstOrNull { it.tag == "code" }
                    val language = code?.classes
                        ?.firstOrNull { it.startsWith("language-") }
                        ?.removePrefix("language-")
                    val body = textOf(code?.children ?: element.children)
                    blocks += PreviewBlock.Code(body.removeSuffix("\n"), language, context.indent, context.quote)
                }

                "blockquote" -> walk(element.children, context.copy(quote = context.quote + 1, marker = null))

                "ul" -> list(element, context, ordered = false)
                "ol" -> list(element, context, ordered = true)

                "table" -> blocks += table(element, context.quote)

                // A div carries no meaning of its own, but two kinds arrive carrying one: a GFM
                // alert and a Markdoc tag. Both are drawn as callouts so the structure the author
                // wrote stays visible instead of flattening into ordinary paragraphs.
                "div" -> {
                    val callout = calloutOf(element)
                    if (callout != null) {
                        val (name, severity, content) = callout
                        val inner = Sink().also { it.walk(content, Context()) }.blocks
                        blocks += PreviewBlock.Callout(name, severity, inner, context.quote)
                    } else {
                        walk(element.children, context)
                    }
                }

                // Footnote definitions arrive in a trailing <section class="footnotes">, which is a
                // list. Rendering it as one keeps the numbering the author sees in the source.
                "section" -> walk(element.children, context)

                // Rows and cells only reach here outside a <table>, which means the document was
                // malformed. Render the content rather than dropping it.
                else -> walk(element.children, context.copy(marker = null))
            }
        }

        private fun list(element: HtmlNode.Element, context: Context, ordered: Boolean) {
            var number = element.attribute("start")?.toIntOrNull() ?: 1

            for (item in element.children.filterIsInstance<HtmlNode.Element>()) {
                if (item.tag != "li") continue

                // GFM renders a task item as a leading <input type="checkbox">. It is consumed here
                // so it can be drawn as a checkbox rather than appearing in the item's text.
                val checkbox = item.children
                    .filterIsInstance<HtmlNode.Element>()
                    .firstOrNull { it.tag == "input" && it.attribute("type") == "checkbox" }
                val task = checkbox?.let { it.attribute("checked") != null }
                val content = if (checkbox == null) item.children else item.children.filterNot { it === checkbox }

                walk(
                    content,
                    context.copy(
                        indent = context.indent + 1,
                        marker = if (ordered) "${number++}." else "•",
                        task = task,
                    ),
                )
            }
        }

        private fun table(element: HtmlNode.Element, quote: Int): PreviewBlock.Table {
            val rows = mutableListOf<PreviewTableRow>()

            fun collect(nodes: List<HtmlNode>) {
                for (node in nodes.filterIsInstance<HtmlNode.Element>()) {
                    when (node.tag) {
                        "thead", "tbody", "tfoot" -> collect(node.children)
                        "tr" -> {
                            val cells = node.children.filterIsInstance<HtmlNode.Element>()
                                .filter { it.tag == "td" || it.tag == "th" }
                            rows += PreviewTableRow(
                                header = cells.isNotEmpty() && cells.all { it.tag == "th" },
                                cells = cells.map { cell ->
                                    PreviewTableCell(InlineBuilder.build(cell.children), alignmentOf(cell))
                                },
                            )
                        }
                        else -> Unit
                    }
                }
            }

            collect(element.children)
            return PreviewBlock.Table(rows, quote)
        }

        /**
         * Recognises the two element shapes that mean "callout", or returns `null`.
         *
         * A GFM alert is `<div class="markdown-alert markdown-alert-note">` whose first child is a
         * `<p class="markdown-alert-title">`; the title is consumed here so it becomes the
         * callout's heading rather than its first paragraph. A Markdoc tag is a div carrying
         * `data-markdoc`, and its name is the tag the author wrote.
         */
        private fun calloutOf(element: HtmlNode.Element): Triple<String, CalloutSeverity, List<HtmlNode>>? {
            element.attribute("data-markdoc")?.let { name ->
                return Triple(name, CalloutSeverity.NOTE, element.children)
            }

            val alert = element.classes.firstOrNull { it.startsWith("markdown-alert-") }
                ?.removePrefix("markdown-alert-")
                ?: return null

            val titleElement = element.children
                .filterIsInstance<HtmlNode.Element>()
                .firstOrNull { "markdown-alert-title" in it.classes }
            val title = titleElement?.let { dev.starfect.quill.bridge.wire.textOf(it.children) }
                ?: alert.replaceFirstChar { it.uppercase() }
            val body = element.children.filterNot { it === titleElement }

            return Triple(title, CalloutSeverity.of(alert), body)
        }

        /**
         * Column alignment.
         *
         * comrak writes it as `align="left"`. The `style="text-align: …"` spelling is what most
         * other renderers emit, so both are accepted — raw HTML tables in the source come from
         * wherever the author copied them from.
         */
        private fun alignmentOf(cell: HtmlNode.Element): CellAlignment {
            val value = cell.attribute("align") ?: cell.attribute("style").orEmpty()
            return when {
                "center" in value -> CellAlignment.CENTER
                "right" in value -> CellAlignment.RIGHT
                "left" in value -> CellAlignment.LEFT
                else -> CellAlignment.NONE
            }
        }
    }

    private fun textOf(nodes: List<HtmlNode>): String = dev.starfect.quill.bridge.wire.textOf(nodes)
}

/** How a table cell's text is aligned. */
internal enum class CellAlignment { NONE, LEFT, CENTER, RIGHT }

internal data class PreviewTableCell(val text: StyledText, val alignment: CellAlignment)

internal data class PreviewTableRow(val header: Boolean, val cells: List<PreviewTableCell>)

/**
 * One laid-out block of the preview.
 *
 * Every variant carries its own [quote] depth and, where it applies, list [indent], because the list
 * is flat — there is no enclosing composable left to infer either from.
 */
internal sealed interface PreviewBlock {
    val quote: Int

    data class Paragraph(
        val text: StyledText,
        val indent: Int,
        override val quote: Int,
        val marker: String?,
        val task: Boolean?,
    ) : PreviewBlock

    data class Heading(val level: Int, val text: StyledText, override val quote: Int) : PreviewBlock

    data class Code(
        val code: String,
        val language: String?,
        val indent: Int,
        override val quote: Int,
    ) : PreviewBlock

    data class Table(val rows: List<PreviewTableRow>, override val quote: Int) : PreviewBlock

    data class ThematicBreak(override val quote: Int) : PreviewBlock

    /** A GFM alert or a Markdoc tag, drawn as a titled panel. */
    data class Callout(
        val name: String,
        val severity: CalloutSeverity,
        val children: List<PreviewBlock>,
        override val quote: Int,
    ) : PreviewBlock
}

/** A callout's kind, which decides only its accent colour. */
internal enum class CalloutSeverity {
    NOTE,
    TIP,
    IMPORTANT,
    WARNING,
    CAUTION,
    ;

    companion object {
        fun of(name: String): CalloutSeverity =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NOTE
    }
}

/**
 * Inline content, resolved to text plus the styling and links that apply to ranges of it.
 *
 * Links are kept as offset ranges rather than as nested nodes because the preview needs to hit-test
 * a click against them, and by the time text has been laid out all that is left is offsets.
 */
internal data class StyledText(
    val text: String,
    val spans: List<InlineSpan> = emptyList(),
    val links: List<InlineLink> = emptyList(),
) {
    val isBlank: Boolean get() = text.isBlank()
}

internal data class InlineSpan(val start: Int, val end: Int, val style: SpanStyle)

internal data class InlineLink(val start: Int, val end: Int, val href: String)

/**
 * Collapses an inline subtree into a [StyledText].
 *
 * Styles compose as the walk descends, so `<strong><em>x</em></strong>` produces one span that is
 * both bold and italic rather than two that fight over the same range.
 */
private object InlineBuilder {

    private val CODE_FONT = FontFamily.Monospace

    fun build(nodes: List<HtmlNode>): StyledText {
        val text = StringBuilder()
        val spans = mutableListOf<InlineSpan>()
        val links = mutableListOf<InlineLink>()
        walk(nodes, text, spans, links, SpanStyle())
        // Markdown puts a newline after every block-level element; inside one paragraph that is a
        // space, not a line break, and leaving it in adds a blank line to every rendered paragraph.
        return StyledText(text.toString().trim(), spans, links)
    }

    private fun walk(
        nodes: List<HtmlNode>,
        text: StringBuilder,
        spans: MutableList<InlineSpan>,
        links: MutableList<InlineLink>,
        inherited: SpanStyle,
    ) {
        for (node in nodes) {
            when (node) {
                is HtmlNode.Text -> {
                    val start = text.length
                    text.append(node.text)
                    if (inherited != SpanStyle() && text.length > start) {
                        spans += InlineSpan(start, text.length, inherited)
                    }
                }

                is HtmlNode.Element -> {
                    val start = text.length
                    when (node.tag) {
                        "br" -> {
                            text.append('\n')
                            continue
                        }

                        "img" -> {
                            // No image decoding in the preview yet; the alt text is what the author
                            // wrote for exactly this case, so it stands in rather than nothing.
                            val alt = node.attribute("alt").orEmpty().ifEmpty { "image" }
                            text.append("🖼 ").append(alt)
                            spans += InlineSpan(start, text.length, inherited + IMAGE_STYLE)
                            continue
                        }

                        "input" -> continue // Task checkboxes are drawn by the list, not the text.
                    }

                    val style = inherited + styleFor(node)
                    walk(node.children, text, spans, links, style)

                    if (node.tag == "a") {
                        val href = node.attribute("href")
                        if (!href.isNullOrEmpty() && text.length > start) {
                            links += InlineLink(start, text.length, href)
                        }
                    }
                }
            }
        }
    }

    private val IMAGE_STYLE = SpanStyle(fontStyle = FontStyle.Italic)

    private fun styleFor(element: HtmlNode.Element): SpanStyle = when (element.tag) {
        "em", "i", "cite", "var" -> SpanStyle(fontStyle = FontStyle.Italic)
        "strong", "b" -> SpanStyle(fontWeight = FontWeight.Bold)
        "del", "s", "strike" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        "u", "ins" -> SpanStyle(textDecoration = TextDecoration.Underline)
        "code", "kbd", "samp", "tt" -> SpanStyle(fontFamily = CODE_FONT, fontSize = CODE_SIZE)
        "a" -> SpanStyle(textDecoration = TextDecoration.Underline)
        "sup" -> SpanStyle(fontSize = SMALL_SIZE, baselineShift = SUPERSCRIPT)
        "sub" -> SpanStyle(fontSize = SMALL_SIZE, baselineShift = SUBSCRIPT)
        "mark" -> SpanStyle(background = MARK_BACKGROUND)
        else -> SpanStyle()
    }

    private val CODE_SIZE: TextUnit = 13.sp
    private val SMALL_SIZE: TextUnit = 11.sp
    private val SUPERSCRIPT = androidx.compose.ui.text.style.BaselineShift.Superscript
    private val SUBSCRIPT = androidx.compose.ui.text.style.BaselineShift.Subscript

    /** IntelliJ's search-match yellow, which is what `<mark>` means in a rendered document. */
    private val MARK_BACKGROUND = Color(0x66C4A000)
}
