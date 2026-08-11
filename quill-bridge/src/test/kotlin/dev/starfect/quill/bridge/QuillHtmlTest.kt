package dev.starfect.quill.bridge

import dev.starfect.quill.bridge.wire.HtmlNode
import dev.starfect.quill.bridge.wire.textOf
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Drives the HTML preview path and the flavour switch through the real shared library.
 *
 * The engine's own tests cover what each dialect does to the source. What only this suite can show
 * is that the resulting document survives the wire format and arrives on the JVM as a tree the
 * preview can walk — attributes intact, nesting intact, entities already decoded.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuillHtmlTest {

    private lateinit var engine: QuillEngine

    @BeforeAll
    fun setUp() {
        engine = QuillEngine.create(darkTheme = true)
    }

    @AfterAll
    fun tearDown() {
        engine.close()
    }

    private inline fun <T> withDocument(text: String, body: (QuillDocument) -> T): T =
        engine.openDocument(text).use(body)

    /** Every element in the tree, depth first. */
    private fun elements(nodes: List<HtmlNode>): List<HtmlNode.Element> =
        nodes.flatMap { node ->
            when (node) {
                is HtmlNode.Text -> emptyList()
                is HtmlNode.Element -> listOf(node) + elements(node.children)
            }
        }

    private fun firstElement(nodes: List<HtmlNode>, tag: String): HtmlNode.Element =
        assertNotNull(elements(nodes).firstOrNull { it.tag == tag }, "no <$tag> in the rendered tree")

    @Test
    fun `renders a heading and a paragraph`() = withDocument("# Title\n\nBody text.\n") { document ->
        val nodes = document.htmlDom()

        assertEquals("Title", textOf(firstElement(nodes, "h1").children))
        assertEquals("Body text.", textOf(firstElement(nodes, "p").children))
    }

    @Test
    fun `carries attributes through the wire format`() =
        withDocument("[Quill](https://example.com \"Home\")\n") { document ->
            val link = firstElement(document.htmlDom(), "a")

            assertEquals("https://example.com", link.attribute("href"))
            assertEquals("Home", link.attribute("title"))
            assertEquals("Quill", textOf(link.children))
        }

    @Test
    fun `decodes entities rather than passing them through`() = withDocument("A & B <not a tag>\n") { document ->
        // comrak escapes the ampersand and the angle brackets on the way out; the DOM the preview
        // receives has to hold the characters, not the escapes, or every '&' shows up as '&amp;'.
        val rendered = textOf(document.htmlDom())

        assertContains(rendered, "A & B")
        assertTrue("&amp;" !in rendered, "entities should be decoded, got: $rendered")
    }

    @Test
    fun `preserves the text of a fenced code block exactly`() =
        withDocument("```rust\nfn main() {\n    println!(\"hi\");\n}\n```\n") { document ->
            val code = firstElement(document.htmlDom(), "code")

            assertEquals("fn main() {\n    println!(\"hi\");\n}\n", textOf(code.children))
            assertContains(code.classes, "language-rust")
        }

    @Test
    fun `defaults to GFM and renders its extensions`() = withDocument("- [x] done\n- [ ] todo\n") { document ->
        assertEquals(MarkdownFlavour.GFM, document.flavour)

        val checkboxes = elements(document.htmlDom()).filter { it.tag == "input" }
        assertEquals(2, checkboxes.size)
        assertEquals("checkbox", checkboxes[0].attribute("type"))
    }

    @Test
    fun `CommonMark turns the GFM extensions off`() = withDocument("~~struck~~\n") { document ->
        assertEquals("struck", textOf(firstElement(document.htmlDom(), "del").children))

        document.flavour = MarkdownFlavour.COMMON_MARK

        val nodes = document.htmlDom()
        assertTrue(elements(nodes).none { it.tag == "del" }, "CommonMark has no strikethrough")
        assertContains(textOf(nodes), "~~struck~~")
    }

    @Test
    fun `MDX strips module statements and renders components as elements`() =
        withDocument("import Note from './Note'\n\n<Note kind=\"tip\">Careful</Note>\n") { document ->
            document.flavour = MarkdownFlavour.MDX
            val nodes = document.htmlDom()

            assertTrue("import Note" !in textOf(nodes), "the import should not reach the preview")

            val note = firstElement(nodes, "note")
            assertEquals("tip", note.attribute("kind"))
            assertEquals("Careful", textOf(note.children))
        }

    @Test
    fun `Markdoc tags become elements the preview can style`() =
        withDocument("{% callout %}\nWatch out.\n{% /callout %}\n") { document ->
            document.flavour = MarkdownFlavour.MARKDOC
            val nodes = document.htmlDom()

            val tag = assertNotNull(
                elements(nodes).firstOrNull { it.attribute("data-markdoc") == "callout" },
                "no element carrying data-markdoc in: ${textOf(nodes)}",
            )
            assertContains(tag.classes, "markdoc-tag")
            assertContains(textOf(tag.children), "Watch out.")
        }

    @Test
    fun `setting the flavour invalidates the cached render`() = withDocument("~~struck~~\n") { document ->
        val before = document.htmlDom()
        val version = document.version

        document.flavour = MarkdownFlavour.COMMON_MARK

        // The cache is keyed on the version, so a stale result here would mean the switch had no
        // visible effect until the next keystroke -- the exact bug this assertion exists to catch.
        assertTrue(document.version > version, "changing the flavour should advance the version")
        assertTrue(document.htmlDom() != before, "the render should change with the flavour")
    }

    @Test
    fun `offsets stay in UTF-16 units when the flavour changes`() = withDocument("# 제목 🎉\n") { document ->
        document.flavour = MarkdownFlavour.MDX

        assertEquals("제목 🎉", textOf(firstElement(document.htmlDom(), "h1").children))
        // "# " + 2 Korean syllables + " " + a surrogate pair + "\n"
        assertEquals(8, document.length)
    }

    @Test
    fun `a file extension picks the flavour`() {
        assertEquals(MarkdownFlavour.MDX, MarkdownFlavour.forFileName("guide.mdx"))
        assertEquals(MarkdownFlavour.MARKDOC, MarkdownFlavour.forFileName("page.mdoc"))
        assertEquals(MarkdownFlavour.GFM, MarkdownFlavour.forFileName("README.md"))
        assertEquals(MarkdownFlavour.GFM, MarkdownFlavour.forFileName("notes"))
        assertEquals(MarkdownFlavour.COMMON_MARK, MarkdownFlavour.forFileName("spec.commonmark"))
    }

    @Test
    fun `raw HTML in the source reaches the preview as markup`() =
        withDocument("Press <kbd>Ctrl</kbd> to continue.\n") { document ->
            document.flavour = MarkdownFlavour.MDX

            assertEquals("Ctrl", textOf(firstElement(document.htmlDom(), "kbd").children))
        }

    @Test
    fun `deeply nested input renders without overflowing the stack`() =
        withDocument("<div>".repeat(2_000)) { document ->
            document.flavour = MarkdownFlavour.MDX

            // The engine caps its descent, so this arrives as a bounded tree rather than as a crash
            // of the whole editor. Nobody writes this, but the preview re-renders on every
            // keystroke and a half-typed document passes through here too.
            assertTrue(elements(document.htmlDom()).isNotEmpty())
        }
}
