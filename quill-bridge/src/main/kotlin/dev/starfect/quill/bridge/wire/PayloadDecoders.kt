package dev.starfect.quill.bridge.wire

import java.lang.foreign.MemorySegment

/**
 * Decoders that turn QWIRE payloads into the IR types.
 *
 * Tag numbers are the wire contract with `parser/ir.rs`. An unknown tag is an error rather than a
 * skipped node: the encoder never emits one, so seeing it means the native library and this bridge
 * disagree, and silently dropping content would hide that.
 */
internal object BlockTag {
    const val PARAGRAPH = 1
    const val HEADING = 2
    const val BLOCK_QUOTE = 3
    const val ORDERED_LIST = 4
    const val UNORDERED_LIST = 5
    const val LIST_ITEM = 6
    const val FENCED_CODE_BLOCK = 7
    const val INDENTED_CODE_BLOCK = 8
    const val HTML_BLOCK = 9
    const val THEMATIC_BREAK = 10
    const val TABLE = 11
    const val TABLE_ROW = 12
    const val TABLE_CELL = 13
    const val FRONT_MATTER = 14
    const val FOOTNOTE_DEFINITION = 15
    const val ALERT = 16
}

internal object InlineTag {
    const val TEXT = 1
    const val EMPHASIS = 2
    const val STRONG_EMPHASIS = 3
    const val CODE = 4
    const val LINK = 5
    const val IMAGE = 6
    const val HTML_INLINE = 7
    const val SOFT_LINE_BREAK = 8
    const val HARD_LINE_BREAK = 9
    const val STRIKETHROUGH = 10
    const val FOOTNOTE_REFERENCE = 11
}

/** Decodes a [PayloadKind.BLOCKS] payload into the block tree. */
public fun decodeBlocks(segment: MemorySegment): List<MarkdownBlockIr> {
    val reader = WireReader.of(segment).expect(PayloadKind.BLOCKS)
    return List(reader.count()) { readBlock(reader) }
}

private fun readBlock(reader: WireReader): MarkdownBlockIr {
    val tag = reader.byte()
    val lineStart = reader.int()
    val lineEnd = reader.int()

    return when (tag) {
        BlockTag.PARAGRAPH -> MarkdownBlockIr.Paragraph(lineStart, lineEnd, readInlines(reader))
        BlockTag.HEADING -> {
            val level = reader.byte()
            MarkdownBlockIr.Heading(lineStart, lineEnd, level, readInlines(reader))
        }
        BlockTag.BLOCK_QUOTE -> MarkdownBlockIr.BlockQuote(lineStart, lineEnd, readBlocks(reader))
        BlockTag.ORDERED_LIST -> {
            val tight = reader.boolean()
            val startFrom = reader.int()
            val delimiter = reader.string()
            MarkdownBlockIr.OrderedList(lineStart, lineEnd, tight, startFrom, delimiter, readListItems(reader))
        }
        BlockTag.UNORDERED_LIST -> {
            val tight = reader.boolean()
            val marker = reader.string()
            MarkdownBlockIr.UnorderedList(lineStart, lineEnd, tight, marker, readListItems(reader))
        }
        BlockTag.LIST_ITEM -> {
            val level = reader.int()
            val task = taskState(reader.byte())
            MarkdownBlockIr.ListItem(lineStart, lineEnd, level, task, readBlocks(reader))
        }
        BlockTag.FENCED_CODE_BLOCK -> {
            val language = reader.optionalString()
            val content = reader.string()
            reader.count() // always zero: code blocks have no child nodes
            MarkdownBlockIr.FencedCodeBlock(lineStart, lineEnd, language, content)
        }
        BlockTag.INDENTED_CODE_BLOCK -> {
            val content = reader.string()
            reader.count()
            MarkdownBlockIr.IndentedCodeBlock(lineStart, lineEnd, content)
        }
        BlockTag.HTML_BLOCK -> {
            val content = reader.string()
            reader.count()
            MarkdownBlockIr.HtmlBlock(lineStart, lineEnd, content)
        }
        BlockTag.THEMATIC_BREAK -> {
            reader.count()
            MarkdownBlockIr.ThematicBreak(lineStart, lineEnd)
        }
        BlockTag.TABLE -> {
            val columnCount = reader.count()
            val alignments = List(reader.count()) { columnAlignment(reader.byte()) }
            val rows = readBlocks(reader).filterIsInstance<MarkdownBlockIr.TableRow>()
            MarkdownBlockIr.Table(lineStart, lineEnd, columnCount, alignments, rows)
        }
        BlockTag.TABLE_ROW -> {
            val isHeader = reader.boolean()
            val cells = readBlocks(reader).filterIsInstance<MarkdownBlockIr.TableCell>()
            MarkdownBlockIr.TableRow(lineStart, lineEnd, isHeader, cells)
        }
        BlockTag.TABLE_CELL -> MarkdownBlockIr.TableCell(lineStart, lineEnd, readInlines(reader))
        BlockTag.FRONT_MATTER -> {
            val content = reader.string()
            reader.count()
            MarkdownBlockIr.FrontMatter(lineStart, lineEnd, content)
        }
        BlockTag.FOOTNOTE_DEFINITION -> {
            val name = reader.string()
            MarkdownBlockIr.FootnoteDefinition(lineStart, lineEnd, name, readBlocks(reader))
        }
        BlockTag.ALERT -> {
            val severity = alertSeverity(reader.byte())
            val title = reader.optionalString()
            MarkdownBlockIr.Alert(lineStart, lineEnd, severity, title, readBlocks(reader))
        }
        else -> throw QuillWireException("unknown block tag $tag")
    }
}

private fun readBlocks(reader: WireReader): List<MarkdownBlockIr> = List(reader.count()) { readBlock(reader) }

private fun readListItems(reader: WireReader): List<MarkdownBlockIr.ListItem> =
    readBlocks(reader).filterIsInstance<MarkdownBlockIr.ListItem>()

private fun readInlines(reader: WireReader): List<InlineIr> = List(reader.count()) { readInline(reader) }

private fun readInline(reader: WireReader): InlineIr = when (val tag = reader.byte()) {
    InlineTag.TEXT -> {
        val content = reader.string()
        reader.count()
        InlineIr.Text(content)
    }
    InlineTag.EMPHASIS -> {
        val delimiter = reader.string()
        InlineIr.Emphasis(delimiter, readInlines(reader))
    }
    InlineTag.STRONG_EMPHASIS -> {
        val delimiter = reader.string()
        InlineIr.StrongEmphasis(delimiter, readInlines(reader))
    }
    InlineTag.STRIKETHROUGH -> {
        val delimiter = reader.string()
        InlineIr.Strikethrough(delimiter, readInlines(reader))
    }
    InlineTag.CODE -> {
        val content = reader.string()
        reader.count()
        InlineIr.Code(content)
    }
    InlineTag.LINK -> {
        val destination = reader.string()
        val title = reader.optionalString()
        InlineIr.Link(destination, title, readInlines(reader))
    }
    InlineTag.IMAGE -> {
        val source = reader.string()
        val alt = reader.string()
        val title = reader.optionalString()
        InlineIr.Image(source, alt, title, readInlines(reader))
    }
    InlineTag.HTML_INLINE -> {
        val content = reader.string()
        reader.count()
        InlineIr.HtmlInline(content)
    }
    InlineTag.FOOTNOTE_REFERENCE -> {
        val name = reader.string()
        reader.count()
        InlineIr.FootnoteReference(name)
    }
    InlineTag.SOFT_LINE_BREAK -> {
        reader.count()
        InlineIr.SoftLineBreak
    }
    InlineTag.HARD_LINE_BREAK -> {
        reader.count()
        InlineIr.HardLineBreak
    }
    else -> throw QuillWireException("unknown inline tag $tag")
}

private fun taskState(value: Int): TaskState = when (value) {
    1 -> TaskState.UNCHECKED
    2 -> TaskState.CHECKED
    else -> TaskState.NONE
}

private fun columnAlignment(value: Int): ColumnAlignment = when (value) {
    1 -> ColumnAlignment.LEFT
    2 -> ColumnAlignment.CENTER
    3 -> ColumnAlignment.RIGHT
    else -> ColumnAlignment.NONE
}

private fun alertSeverity(value: Int): AlertSeverity = when (value) {
    1 -> AlertSeverity.TIP
    2 -> AlertSeverity.IMPORTANT
    3 -> AlertSeverity.WARNING
    4 -> AlertSeverity.CAUTION
    else -> AlertSeverity.NOTE
}

/** Decodes a [PayloadKind.OUTLINE] payload. */
public fun decodeOutline(segment: MemorySegment): List<OutlineEntry> {
    val reader = WireReader.of(segment).expect(PayloadKind.OUTLINE)
    return List(reader.count()) {
        OutlineEntry(level = reader.byte(), line = reader.int(), offset = reader.int(), title = reader.string())
    }
}

/** Decodes a [PayloadKind.STATS] payload. */
public fun decodeStats(segment: MemorySegment): DocumentStats {
    val reader = WireReader.of(segment).expect(PayloadKind.STATS)
    return DocumentStats(
        words = reader.int(),
        characters = reader.int(),
        charactersWithoutSpaces = reader.int(),
        lines = reader.int(),
        paragraphs = reader.int(),
        sentences = reader.int(),
        readingTimeSeconds = reader.int(),
        codeBlocks = reader.int(),
        links = reader.int(),
        images = reader.int(),
        headings = reader.int(),
    )
}

/** Decodes a [PayloadKind.SEARCH] payload. */
public fun decodeSearch(segment: MemorySegment): List<SearchMatch> {
    val reader = WireReader.of(segment).expect(PayloadKind.SEARCH)
    return List(reader.count()) {
        SearchMatch(start = reader.int(), end = reader.int(), line = reader.int(), column = reader.int())
    }
}

/** Decodes a [PayloadKind.SPANS] payload. */
public fun decodeSpans(segment: MemorySegment): List<StyleSpan> {
    val reader = WireReader.of(segment).expect(PayloadKind.SPANS)
    return List(reader.count()) { StyleSpan(start = reader.int(), end = reader.int(), styleId = reader.int()) }
}

/** Decodes a [PayloadKind.CODE_HIGHLIGHT] payload. */
public fun decodeColorSpans(segment: MemorySegment): List<ColorSpan> {
    val reader = WireReader.of(segment).expect(PayloadKind.CODE_HIGHLIGHT)
    return List(reader.count()) { ColorSpan(start = reader.int(), end = reader.int(), argb = reader.int()) }
}

/** Decodes a [PayloadKind.TEXT] payload. */
public fun decodeText(segment: MemorySegment): String =
    WireReader.of(segment).expect(PayloadKind.TEXT).string()

/** Decodes a [PayloadKind.INSPECTIONS] payload. */
public fun decodeInspections(segment: MemorySegment): List<Finding> {
    val reader = WireReader.of(segment).expect(PayloadKind.INSPECTIONS)
    return List(reader.count()) {
        Finding(
            inspection = Inspection.fromId(reader.byte()),
            severity = Severity.fromId(reader.byte()),
            line = reader.int(),
            start = reader.int(),
            end = reader.int(),
            message = reader.string(),
        )
    }
}

/** Node tags in an [PayloadKind.HTML_DOM] payload, mirroring `html.rs`. */
private const val NODE_TEXT = 0
private const val NODE_ELEMENT = 1

/**
 * Nesting depth this decoder will descend to.
 *
 * The engine caps its own parser at 256, so a payload deeper than this cannot come from a matching
 * native library. Refusing it keeps a corrupt or mismatched payload from overflowing the JVM stack.
 */
private const val MAX_HTML_DEPTH = 512

/** Decodes a [PayloadKind.HTML_DOM] payload into the rendered document tree. */
public fun decodeHtmlDom(segment: MemorySegment): List<HtmlNode> {
    val reader = WireReader.of(segment).expect(PayloadKind.HTML_DOM)
    return readHtmlNodes(reader, depth = 0)
}

private fun readHtmlNodes(reader: WireReader, depth: Int): List<HtmlNode> =
    List(reader.count()) { readHtmlNode(reader, depth) }

private fun readHtmlNode(reader: WireReader, depth: Int): HtmlNode = when (val tag = reader.byte()) {
    NODE_TEXT -> HtmlNode.Text(reader.string())
    NODE_ELEMENT -> {
        if (depth >= MAX_HTML_DEPTH) {
            throw QuillWireException("HTML payload nests deeper than $MAX_HTML_DEPTH levels")
        }
        val name = reader.string()
        val attributes = List(reader.count()) { HtmlAttribute(reader.string(), reader.string()) }
        HtmlNode.Element(name, attributes, readHtmlNodes(reader, depth + 1))
    }
    else -> throw QuillWireException("unknown HTML node tag $tag")
}
