package dev.starfect.quill.ui.preview

import dev.starfect.quill.bridge.MarkdownFlavour
import dev.starfect.quill.bridge.QuillEngine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the flattening pass that turns the engine's HTML tree into the preview's block list.
 *
 * These run against the real engine rather than against hand-written DOM fixtures. A fixture would
 * only prove the flattener agrees with what this file imagines comrak emits, and every bug worth
 * catching here lives in that gap — the wrapper element around a table, where the task checkbox
 * actually sits, whether alignment arrives as an attribute or as inline CSS.
 */
class HtmlRendererTest {

    private val engine = QuillEngine.create(darkTheme = true)

    @AfterTest
    fun tearDown() {
        engine.close()
    }

    private fun blocks(markdown: String, flavour: MarkdownFlavour = MarkdownFlavour.GFM): List<PreviewBlock> =
        engine.openDocument(markdown).use { document ->
            document.flavour = flavour
            HtmlRenderer.toBlocks(document.htmlDom())
        }

    private inline fun <reified T : PreviewBlock> only(markdown: String): T {
        val matching = blocks(markdown).filterIsInstance<T>()
        assertEquals(1, matching.size, "expected exactly one ${T::class.simpleName} in: ${blocks(markdown)}")
        return matching.single()
    }

    @Test
    fun `a heading keeps its level and text`() {
        val heading = only<PreviewBlock.Heading>("### Third level\n")

        assertEquals(3, heading.level)
        assertEquals("Third level", heading.text.text)
    }

    @Test
    fun `a paragraph becomes one block rather than one per inline`() {
        // The whole point of collapsing inlines: this is a sentence, and it has to wrap as one.
        val paragraphs = blocks("Some *emphasis* and `code` and **bold** together.\n")
            .filterIsInstance<PreviewBlock.Paragraph>()

        assertEquals(1, paragraphs.size)
        assertEquals("Some emphasis and code and bold together.", paragraphs.single().text.text)
    }

    @Test
    fun `nested emphasis produces one span carrying both styles`() {
        val paragraph = only<PreviewBlock.Paragraph>("***both***\n")
        val covering = paragraph.text.spans.filter { it.start == 0 && it.end == "both".length }

        assertTrue(covering.isNotEmpty(), "no span covers the text: ${paragraph.text.spans}")
        assertTrue(
            covering.any { it.style.fontWeight != null && it.style.fontStyle != null },
            "expected a span that is both bold and italic, got ${covering.map { it.style }}",
        )
    }

    @Test
    fun `a link records its destination as an offset range`() {
        val paragraph = only<PreviewBlock.Paragraph>("See [the docs](https://example.com) here.\n")

        assertEquals("See the docs here.", paragraph.text.text)
        val link = paragraph.text.links.single()
        assertEquals("https://example.com", link.href)
        assertEquals("the docs", paragraph.text.text.substring(link.start, link.end))
    }

    @Test
    fun `a fenced block keeps its language and its exact text`() {
        val code = only<PreviewBlock.Code>("```kotlin\nfun main() {\n    println(\"hi\")\n}\n```\n")

        assertEquals("kotlin", code.language)
        assertEquals("fun main() {\n    println(\"hi\")\n}", code.code)
    }

    @Test
    fun `an indented block is code with no language`() {
        val code = only<PreviewBlock.Code>("    indented\n")

        assertNull(code.language)
        assertEquals("indented", code.code)
    }

    @Test
    fun `list items carry their marker and nesting depth`() {
        val items = blocks("- one\n- two\n    - nested\n").filterIsInstance<PreviewBlock.Paragraph>()

        assertEquals(listOf("one", "two", "nested"), items.map { it.text.text })
        assertEquals(listOf("•", "•", "•"), items.map { it.marker })
        assertEquals(listOf(1, 1, 2), items.map { it.indent })
    }

    @Test
    fun `an ordered list numbers from its start attribute`() {
        val items = blocks("5. five\n6. six\n").filterIsInstance<PreviewBlock.Paragraph>()

        assertEquals(listOf("5.", "6."), items.map { it.marker })
    }

    @Test
    fun `a task list becomes checkboxes and the marker leaves the text`() {
        val items = blocks("- [x] done\n- [ ] todo\n").filterIsInstance<PreviewBlock.Paragraph>()

        assertEquals(listOf(true, false), items.map { it.task })
        assertEquals(listOf("done", "todo"), items.map { it.text.text })
    }

    @Test
    fun `a blockquote records its depth instead of nesting`() {
        val quoted = blocks("> outer\n>\n> > inner\n").filterIsInstance<PreviewBlock.Paragraph>()

        assertEquals("outer", quoted.first().text.text)
        assertEquals(1, quoted.first().quote)
        assertEquals(2, quoted.last().quote, "the nested quote should record depth 2")
    }

    @Test
    fun `a table keeps its header row and column alignment`() {
        val table = only<PreviewBlock.Table>(
            """
            | Left | Middle | Right |
            |:-----|:------:|------:|
            | a    | b      | c     |
            """.trimIndent() + "\n"
        )

        assertEquals(2, table.rows.size)
        assertTrue(table.rows.first().header)
        assertEquals(listOf("Left", "Middle", "Right"), table.rows.first().cells.map { it.text.text })
        assertEquals(
            listOf(CellAlignment.LEFT, CellAlignment.CENTER, CellAlignment.RIGHT),
            table.rows.first().cells.map { it.alignment },
        )
        assertEquals(listOf("a", "b", "c"), table.rows.last().cells.map { it.text.text })
    }

    @Test
    fun `a thematic break survives`() {
        assertEquals(1, blocks("above\n\n---\n\nbelow\n").filterIsInstance<PreviewBlock.ThematicBreak>().size)
    }

    @Test
    fun `an image falls back to its alt text`() {
        val paragraph = only<PreviewBlock.Paragraph>("![a diagram](diagram.png)\n")

        assertContains(paragraph.text.text, "a diagram")
    }

    @Test
    fun `a Markdoc tag becomes a callout holding its own blocks`() {
        val callout = blocks("{% warning %}\nBe careful.\n{% /warning %}\n", MarkdownFlavour.MARKDOC)
            .filterIsInstance<PreviewBlock.Callout>()
            .single()

        assertEquals("warning", callout.name)
        assertContains(
            callout.children.filterIsInstance<PreviewBlock.Paragraph>().map { it.text.text },
            "Be careful.",
        )
    }

    @Test
    fun `a GFM alert becomes a callout with its title lifted out of the body`() {
        val callout = blocks("> [!WARNING]\n> Mind the gap.\n")
            .filterIsInstance<PreviewBlock.Callout>()
            .single()

        assertEquals("Warning", callout.name)
        assertEquals(CalloutSeverity.WARNING, callout.severity)

        // The title must not also appear as the first paragraph, which is where it lives in the HTML.
        assertEquals(
            listOf("Mind the gap."),
            callout.children.filterIsInstance<PreviewBlock.Paragraph>().map { it.text.text },
        )
    }

    @Test
    fun `every alert severity maps to its own kind`() {
        val severities = listOf("NOTE", "TIP", "IMPORTANT", "WARNING", "CAUTION").map { keyword ->
            blocks("> [!$keyword]\n> body\n").filterIsInstance<PreviewBlock.Callout>().single().severity
        }

        assertEquals(
            listOf(
                CalloutSeverity.NOTE,
                CalloutSeverity.TIP,
                CalloutSeverity.IMPORTANT,
                CalloutSeverity.WARNING,
                CalloutSeverity.CAUTION,
            ),
            severities,
        )
    }

    @Test
    fun `a footnote definition survives as a numbered item`() {
        val rendered = blocks("Text[^1]\n\n[^1]: A footnote.\n")

        assertTrue(
            rendered.filterIsInstance<PreviewBlock.Paragraph>().any { "A footnote." in it.text.text },
            "the footnote body disappeared: $rendered",
        )
    }

    @Test
    fun `raw HTML renders as markup once the flavour allows it`() {
        val paragraph = blocks("Press <kbd>Esc</kbd>.\n", MarkdownFlavour.MDX)
            .filterIsInstance<PreviewBlock.Paragraph>()
            .single()

        // The tag is gone and its content survives; in GFM the same source would show the literal
        // "<kbd>" because comrak escapes it.
        assertEquals("Press Esc.", paragraph.text.text)
    }

    @Test
    fun `whitespace between blocks does not become empty paragraphs`() {
        // comrak separates every block with a newline. Treated as content, each one becomes a blank
        // paragraph and the preview grows a gap after every element.
        val paragraphs = blocks("# Title\n\nOne\n\nTwo\n").filterIsInstance<PreviewBlock.Paragraph>()

        assertEquals(listOf("One", "Two"), paragraphs.map { it.text.text })
    }

    @Test
    fun `an empty document produces no blocks`() {
        assertTrue(blocks("").isEmpty())
    }

    @Test
    fun `a document of only whitespace produces no blocks`() {
        assertTrue(blocks("   \n\n  \n").isEmpty())
    }

    @Test
    fun `spans stay inside the text they annotate`() {
        // Every span offset is used to slice the string in the composable; one past the end is a
        // crash in the preview rather than a wrong colour.
        val everything = blocks(
            "# H *i*\n\n**b** and `c` and [l](x) and ~~s~~\n\n> q\n\n- [x] t\n"
        )

        for (block in everything) {
            val text = when (block) {
                is PreviewBlock.Paragraph -> block.text
                is PreviewBlock.Heading -> block.text
                else -> continue
            }
            for (span in text.spans) {
                assertTrue(span.start in 0..text.text.length, "span start ${span.start} outside '${text.text}'")
                assertTrue(span.end in span.start..text.text.length, "span end ${span.end} outside '${text.text}'")
            }
            for (link in text.links) {
                assertTrue(link.start in 0..text.text.length, "link start outside '${text.text}'")
                assertTrue(link.end in link.start..text.text.length, "link end outside '${text.text}'")
            }
        }
    }

    @Test
    fun `a heading inside a list item still renders`() {
        // Malformed-ish but legal, and a shape that flattening can lose if block handling only
        // recurses through the cases it expects.
        val blocks = blocks("- # heading in a list\n")

        assertNotNull(
            blocks.filterIsInstance<PreviewBlock.Heading>().firstOrNull()
                ?: blocks.filterIsInstance<PreviewBlock.Paragraph>().firstOrNull { "heading" in it.text.text },
            "the heading disappeared: $blocks",
        )
    }
}
