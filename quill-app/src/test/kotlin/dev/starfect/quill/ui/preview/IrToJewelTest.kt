package dev.starfect.quill.ui.preview

import dev.starfect.quill.bridge.wire.AlertSeverity
import dev.starfect.quill.bridge.wire.ColumnAlignment
import dev.starfect.quill.bridge.wire.InlineIr
import dev.starfect.quill.bridge.wire.MarkdownBlockIr
import dev.starfect.quill.bridge.wire.TaskState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.jewel.markdown.InlineMarkdown
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.extensions.github.alerts.GitHubAlert
import org.jetbrains.jewel.markdown.extensions.github.strikethrough.GitHubStrikethroughNode

/**
 * Tests for the engine IR → Jewel model mapping.
 *
 * This is the only place where the two type hierarchies meet, so a mismatch here shows up as a
 * silently missing block in the preview rather than as a compile error.
 */
class IrToJewelTest {

    private fun text(value: String) = listOf(InlineIr.Text(value))

    @Test
    fun `heading keeps its level and content`() {
        val block = IrToJewel.mapBlock(MarkdownBlockIr.Heading(0, 0, level = 3, inlines = text("Title")))
        val heading = assertIs<MarkdownBlock.Heading>(block)
        assertEquals(3, heading.level)
        assertEquals(listOf(InlineMarkdown.Text("Title")), heading.inlineContent)
    }

    @Test
    fun `heading levels outside 1 to 6 are clamped`() {
        // comrak will not emit these, but the wire format can carry any u8 and Jewel's renderer
        // indexes its style table by level.
        assertEquals(6, assertIs<MarkdownBlock.Heading>(
            IrToJewel.mapBlock(MarkdownBlockIr.Heading(0, 0, level = 9, inlines = text("x")))
        ).level)
        assertEquals(1, assertIs<MarkdownBlock.Heading>(
            IrToJewel.mapBlock(MarkdownBlockIr.Heading(0, 0, level = 0, inlines = text("x")))
        ).level)
    }

    @Test
    fun `fenced code block carries its language`() {
        val block = IrToJewel.mapBlock(
            MarkdownBlockIr.FencedCodeBlock(0, 2, language = "rust", content = "fn main() {}")
        )
        val code = assertIs<MarkdownBlock.CodeBlock.FencedCodeBlock>(block)
        assertEquals("rust", code.language)
        assertEquals("fn main() {}", code.content)
    }

    @Test
    fun `an indented code block has no language`() {
        val block = IrToJewel.mapBlock(MarkdownBlockIr.IndentedCodeBlock(0, 1, "    literal"))
        assertEquals("    literal", assertIs<MarkdownBlock.CodeBlock.IndentedCodeBlock>(block).content)
    }

    @Test
    fun `ordered list keeps its start number and delimiter`() {
        val block = IrToJewel.mapBlock(
            MarkdownBlockIr.OrderedList(
                lineStart = 0,
                lineEnd = 1,
                tight = true,
                startFrom = 5,
                delimiter = ".",
                items = listOf(
                    MarkdownBlockIr.ListItem(0, 0, 0, TaskState.NONE, listOf(
                        MarkdownBlockIr.Paragraph(0, 0, text("five"))
                    ))
                ),
            )
        )
        val list = assertIs<MarkdownBlock.ListBlock.OrderedList>(block)
        assertEquals(5, list.startFrom)
        assertEquals(".", list.delimiter)
        assertTrue(list.isTight)
        assertEquals(1, list.children.size)
    }

    @Test
    fun `a checked task gets a ballot marker prepended to its first paragraph`() {
        val block = IrToJewel.mapBlock(
            MarkdownBlockIr.ListItem(
                lineStart = 0,
                lineEnd = 0,
                level = 0,
                task = TaskState.CHECKED,
                children = listOf(MarkdownBlockIr.Paragraph(0, 0, text("done"))),
            )
        )
        val item = assertIs<MarkdownBlock.ListItem>(block)
        val paragraph = assertIs<MarkdownBlock.Paragraph>(item.children.first())
        assertEquals(InlineMarkdown.Text("☑ "), paragraph.inlineContent.first())
        assertEquals(InlineMarkdown.Text("done"), paragraph.inlineContent[1])
    }

    @Test
    fun `an unchecked task uses the empty ballot`() {
        val block = IrToJewel.mapBlock(
            MarkdownBlockIr.ListItem(0, 0, 0, TaskState.UNCHECKED, listOf(MarkdownBlockIr.Paragraph(0, 0, text("todo"))))
        )
        val paragraph = assertIs<MarkdownBlock.Paragraph>(assertIs<MarkdownBlock.ListItem>(block).children.first())
        assertEquals(InlineMarkdown.Text("☐ "), paragraph.inlineContent.first())
    }

    @Test
    fun `a task whose first child is not a paragraph still gets a marker`() {
        val block = IrToJewel.mapBlock(
            MarkdownBlockIr.ListItem(0, 2, 0, TaskState.CHECKED, listOf(
                MarkdownBlockIr.FencedCodeBlock(0, 2, null, "code")
            ))
        )
        val item = assertIs<MarkdownBlock.ListItem>(block)
        assertIs<MarkdownBlock.Paragraph>(item.children.first())
        assertEquals(2, item.children.size)
    }

    @Test
    fun `every alert severity maps to its GitHub alert type`() {
        fun alert(severity: AlertSeverity) = IrToJewel.mapBlock(
            MarkdownBlockIr.Alert(0, 1, severity, null, listOf(MarkdownBlockIr.Paragraph(0, 0, text("body"))))
        )
        assertIs<GitHubAlert.Note>(alert(AlertSeverity.NOTE))
        assertIs<GitHubAlert.Tip>(alert(AlertSeverity.TIP))
        assertIs<GitHubAlert.Important>(alert(AlertSeverity.IMPORTANT))
        assertIs<GitHubAlert.Warning>(alert(AlertSeverity.WARNING))
        assertIs<GitHubAlert.Caution>(alert(AlertSeverity.CAUTION))
    }

    @Test
    fun `strikethrough maps to the GFM extension node`() {
        val inlines = IrToJewel.mapInlines(listOf(InlineIr.Strikethrough("~~", text("gone"))))
        val node = assertIs<GitHubStrikethroughNode>(inlines.single())
        assertEquals(listOf(InlineMarkdown.Text("gone")), node.inlineContent)
    }

    @Test
    fun `links and images keep their destinations`() {
        val inlines = IrToJewel.mapInlines(
            listOf(
                InlineIr.Link("https://example.com", "Title", text("here")),
                InlineIr.Image("logo.png", "Logo", null, emptyList()),
            )
        )
        val link = assertIs<InlineMarkdown.Link>(inlines[0])
        assertEquals("https://example.com", link.destination)
        assertEquals("Title", link.title)
        val image = assertIs<InlineMarkdown.Image>(inlines[1])
        assertEquals("logo.png", image.source)
        assertEquals("Logo", image.alt)
    }

    @Test
    fun `line breaks map to their Jewel singletons`() {
        val inlines = IrToJewel.mapInlines(listOf(InlineIr.SoftLineBreak, InlineIr.HardLineBreak))
        assertEquals(InlineMarkdown.SoftLineBreak, inlines[0])
        assertEquals(InlineMarkdown.HardLineBreak, inlines[1])
    }

    @Test
    fun `a footnote reference renders as its source form`() {
        val inlines = IrToJewel.mapInlines(listOf(InlineIr.FootnoteReference("note")))
        assertEquals(InlineMarkdown.Text("[^note]"), inlines.single())
    }

    @Test
    fun `front matter is dropped from the preview`() {
        assertNull(IrToJewel.mapBlock(MarkdownBlockIr.FrontMatter(0, 2, "title: x")))
    }

    @Test
    fun `a table becomes a Quill-rendered item rather than a Jewel block`() {
        val table = MarkdownBlockIr.Table(
            lineStart = 4,
            lineEnd = 6,
            columnCount = 2,
            alignments = listOf(ColumnAlignment.LEFT, ColumnAlignment.RIGHT),
            rows = listOf(
                MarkdownBlockIr.TableRow(4, 4, isHeader = true, cells = listOf(
                    MarkdownBlockIr.TableCell(4, 4, text("Column")),
                    MarkdownBlockIr.TableCell(4, 4, text("Value")),
                ))
            ),
        )
        val items = IrToJewel.toPreviewItems(listOf(table))
        val item = assertIs<IrToJewel.PreviewItem.Table>(items.single())
        assertEquals(4, item.lineStart)
        assertEquals(2, item.table.columnCount)
    }

    @Test
    fun `preview items carry the source line for scroll synchronisation`() {
        val items = IrToJewel.toPreviewItems(
            listOf(
                MarkdownBlockIr.Heading(0, 0, 1, text("Title")),
                MarkdownBlockIr.FrontMatter(1, 1, "dropped"),
                MarkdownBlockIr.Paragraph(7, 8, text("Body")),
            )
        )
        assertEquals(listOf(0, 7), items.map { it.lineStart })
    }

    @Test
    fun `nested block quotes and lists survive the round trip`() {
        val quote = MarkdownBlockIr.BlockQuote(0, 3, listOf(
            MarkdownBlockIr.UnorderedList(1, 3, tight = false, marker = "-", items = listOf(
                MarkdownBlockIr.ListItem(1, 2, 0, TaskState.NONE, listOf(
                    MarkdownBlockIr.Paragraph(1, 1, text("outer")),
                    MarkdownBlockIr.BlockQuote(2, 2, listOf(MarkdownBlockIr.Paragraph(2, 2, text("inner")))),
                ))
            ))
        ))
        val block = assertIs<MarkdownBlock.BlockQuote>(IrToJewel.mapBlock(quote))
        val list = assertIs<MarkdownBlock.ListBlock.UnorderedList>(block.children.single())
        val item = assertIs<MarkdownBlock.ListItem>(list.children.single())
        assertEquals(2, item.children.size)
        assertIs<MarkdownBlock.BlockQuote>(item.children[1])
    }
}
