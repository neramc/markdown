package com.neramc.quill.bridge.wire

/**
 * The Markdown IR produced by the Rust parser.
 *
 * These types intentionally mirror Jewel's `MarkdownBlock` / `InlineMarkdown` hierarchies so the
 * UI-side mapper is a flat translation with no restructuring. They live in this module rather than
 * being Jewel types directly so the bridge stays free of Compose dependencies and can be tested
 * headlessly.
 */
public sealed interface MarkdownBlockIr {
    /** Zero-based inclusive source line range, used for editor/preview scroll synchronisation. */
    public val lineStart: Int
    public val lineEnd: Int

    public data class Paragraph(
        override val lineStart: Int,
        override val lineEnd: Int,
        val inlines: List<InlineIr>,
    ) : MarkdownBlockIr

    public data class Heading(
        override val lineStart: Int,
        override val lineEnd: Int,
        val level: Int,
        val inlines: List<InlineIr>,
    ) : MarkdownBlockIr

    public data class BlockQuote(
        override val lineStart: Int,
        override val lineEnd: Int,
        val children: List<MarkdownBlockIr>,
    ) : MarkdownBlockIr

    public data class OrderedList(
        override val lineStart: Int,
        override val lineEnd: Int,
        val tight: Boolean,
        val startFrom: Int,
        val delimiter: String,
        val items: List<ListItem>,
    ) : MarkdownBlockIr

    public data class UnorderedList(
        override val lineStart: Int,
        override val lineEnd: Int,
        val tight: Boolean,
        val marker: String,
        val items: List<ListItem>,
    ) : MarkdownBlockIr

    public data class ListItem(
        override val lineStart: Int,
        override val lineEnd: Int,
        val level: Int,
        val task: TaskState,
        val children: List<MarkdownBlockIr>,
    ) : MarkdownBlockIr

    public data class FencedCodeBlock(
        override val lineStart: Int,
        override val lineEnd: Int,
        val language: String?,
        val content: String,
    ) : MarkdownBlockIr

    public data class IndentedCodeBlock(
        override val lineStart: Int,
        override val lineEnd: Int,
        val content: String,
    ) : MarkdownBlockIr

    public data class HtmlBlock(
        override val lineStart: Int,
        override val lineEnd: Int,
        val content: String,
    ) : MarkdownBlockIr

    public data class ThematicBreak(override val lineStart: Int, override val lineEnd: Int) : MarkdownBlockIr

    public data class Table(
        override val lineStart: Int,
        override val lineEnd: Int,
        val columnCount: Int,
        val alignments: List<ColumnAlignment>,
        val rows: List<TableRow>,
    ) : MarkdownBlockIr

    public data class TableRow(
        override val lineStart: Int,
        override val lineEnd: Int,
        val isHeader: Boolean,
        val cells: List<TableCell>,
    ) : MarkdownBlockIr

    public data class TableCell(
        override val lineStart: Int,
        override val lineEnd: Int,
        val inlines: List<InlineIr>,
    ) : MarkdownBlockIr

    public data class FrontMatter(
        override val lineStart: Int,
        override val lineEnd: Int,
        val content: String,
    ) : MarkdownBlockIr

    public data class FootnoteDefinition(
        override val lineStart: Int,
        override val lineEnd: Int,
        val name: String,
        val children: List<MarkdownBlockIr>,
    ) : MarkdownBlockIr

    public data class Alert(
        override val lineStart: Int,
        override val lineEnd: Int,
        val severity: AlertSeverity,
        val title: String?,
        val children: List<MarkdownBlockIr>,
    ) : MarkdownBlockIr
}

/** Inline nodes. These carry no line range: scroll sync works at block granularity. */
public sealed interface InlineIr {
    public data class Text(val content: String) : InlineIr

    public data class Emphasis(val delimiter: String, val children: List<InlineIr>) : InlineIr

    public data class StrongEmphasis(val delimiter: String, val children: List<InlineIr>) : InlineIr

    public data class Strikethrough(val delimiter: String, val children: List<InlineIr>) : InlineIr

    public data class Code(val content: String) : InlineIr

    public data class Link(val destination: String, val title: String?, val children: List<InlineIr>) : InlineIr

    public data class Image(
        val source: String,
        val alt: String,
        val title: String?,
        val children: List<InlineIr>,
    ) : InlineIr

    public data class HtmlInline(val content: String) : InlineIr

    public data class FootnoteReference(val name: String) : InlineIr

    public data object SoftLineBreak : InlineIr

    public data object HardLineBreak : InlineIr
}

public enum class ColumnAlignment { NONE, LEFT, CENTER, RIGHT }

public enum class TaskState { NONE, UNCHECKED, CHECKED }

public enum class AlertSeverity { NOTE, TIP, IMPORTANT, WARNING, CAUTION }

/** A heading in the document outline. */
public data class OutlineEntry(
    val level: Int,
    val line: Int,
    /** UTF-16 offset of the heading's line, for caret navigation. */
    val offset: Int,
    val title: String,
)

/** Document statistics shown in the status bar. */
public data class DocumentStats(
    val words: Int,
    val characters: Int,
    val charactersWithoutSpaces: Int,
    val lines: Int,
    val paragraphs: Int,
    val sentences: Int,
    val readingTimeSeconds: Int,
    val codeBlocks: Int,
    val links: Int,
    val images: Int,
    val headings: Int,
) {
    public companion object {
        public val EMPTY: DocumentStats = DocumentStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    }
}

/** One search hit, in UTF-16 offsets. */
public data class SearchMatch(val start: Int, val end: Int, val line: Int, val column: Int)

/**
 * A styled run of editor source text.
 *
 * [styleId] is a semantic token identifier, not a colour: the UI resolves it against the active
 * Jewel theme so the editor recolours with the IDE theme instead of baking colours into the engine.
 */
public data class StyleSpan(val start: Int, val end: Int, val styleId: Int)

/** A coloured run inside a fenced code block. [argb] is packed 0xAARRGGBB. */
public data class ColorSpan(val start: Int, val end: Int, val argb: Int)
