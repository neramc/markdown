package com.neramc.quill.bridge

import com.neramc.quill.bridge.wire.AlertSeverity
import com.neramc.quill.bridge.wire.ColumnAlignment
import com.neramc.quill.bridge.wire.InlineIr
import com.neramc.quill.bridge.wire.MarkdownBlockIr
import com.neramc.quill.bridge.wire.TaskState
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Exercises the real `libquill_core` through Panama.
 *
 * These are the tests that prove the ABI. The Rust unit tests verify the engine's logic against
 * itself; only this suite verifies that the C signatures, struct layout, offset convention and
 * buffer ownership actually agree across the boundary.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuillEngineTest {

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

    @Test
    fun `opens and reads a document`() = withDocument("# Hello\n") { document ->
        assertEquals("# Hello\n", document.text())
        assertEquals(8, document.length)
        assertEquals(1L, document.version)
    }

    @Test
    fun `applies an incremental edit`() = withDocument("hello") { document ->
        document.replace(5, 5, " world")
        assertEquals("hello world", document.text())
        assertEquals(2L, document.version)

        document.replace(0, 5, "goodbye")
        assertEquals("goodbye world", document.text())
        assertEquals(3L, document.version)
    }

    @Test
    fun `replaces the whole document`() = withDocument("old") { document ->
        document.setText("brand new content")
        assertEquals("brand new content", document.text())
    }

    @Test
    fun `rejects an out-of-range edit and reports why`() = withDocument("abc") { document ->
        val failure = assertFailsWith<QuillEngineException> { document.replace(0, 999, "x") }
        assertEquals(-3, failure.status)
        assertContains(failure.message.orEmpty(), "out of bounds")
        // The document must be untouched by a rejected edit.
        assertEquals("abc", document.text())
    }

    @Test
    fun `offsets are utf16 across the boundary`() {
        // The single most important cross-language invariant: Korean is 1 UTF-16 unit but 3 UTF-8
        // bytes, and an emoji is 2 units but 4 bytes. If either side used byte offsets these
        // assertions would land mid-character.
        withDocument("한국어") { document ->
            assertEquals(3, document.length)
            document.replace(3, 3, " 편집기")
            assertEquals("한국어 편집기", document.text())
        }
        withDocument("a🪶b") { document ->
            assertEquals(4, document.length, "the emoji is a surrogate pair")
            document.replace(1, 3, "X")
            assertEquals("aXb", document.text())
        }
    }

    @Test
    fun `parses blocks into the jewel-shaped ir`() =
        withDocument("# Title\n\nSome *emphasis* and `code`.\n") { document ->
            val blocks = document.blocks()
            assertEquals(2, blocks.size)

            val heading = blocks[0] as MarkdownBlockIr.Heading
            assertEquals(1, heading.level)
            assertEquals(0, heading.lineStart)
            assertEquals(listOf(InlineIr.Text("Title")), heading.inlines)

            val paragraph = blocks[1] as MarkdownBlockIr.Paragraph
            assertEquals(2, paragraph.lineStart, "line ranges are zero-based")
            assertTrue(paragraph.inlines.any { it is InlineIr.Emphasis })
            assertTrue(paragraph.inlines.any { it is InlineIr.Code })
        }

    @Test
    fun `parses nested structures`() = withDocument(
        "> A quote with a list:\n>\n> - [x] done\n> - [ ] pending\n"
    ) { document ->
        val quote = document.blocks().single() as MarkdownBlockIr.BlockQuote
        val list = quote.children.filterIsInstance<MarkdownBlockIr.UnorderedList>().single()
        assertEquals(2, list.items.size)
        assertEquals(TaskState.CHECKED, list.items[0].task)
        assertEquals(TaskState.UNCHECKED, list.items[1].task)
    }

    @Test
    fun `parses gfm tables with alignments`() =
        withDocument("| Left | Right |\n|:-----|------:|\n| a    | b     |\n") { document ->
            val table = document.blocks().single() as MarkdownBlockIr.Table
            assertEquals(2, table.columnCount)
            assertContentEquals(listOf(ColumnAlignment.LEFT, ColumnAlignment.RIGHT), table.alignments)
            assertEquals(2, table.rows.size)
            assertTrue(table.rows[0].isHeader)
            assertEquals(2, table.rows[0].cells.size)
        }

    @Test
    fun `parses ordered lists front matter and alerts`() {
        withDocument("3) three\n4) four\n") { document ->
            val list = document.blocks().single() as MarkdownBlockIr.OrderedList
            assertEquals(3, list.startFrom)
            assertEquals(")", list.delimiter)
            assertEquals(2, list.items.size)
        }
        withDocument("---\ntitle: x\n---\n\nbody\n") { document ->
            assertTrue(document.blocks().any { it is MarkdownBlockIr.FrontMatter })
        }
        withDocument("> [!WARNING]\n> Careful.\n") { document ->
            val alert = document.blocks().filterIsInstance<MarkdownBlockIr.Alert>().single()
            assertEquals(AlertSeverity.WARNING, alert.severity)
        }
    }

    @Test
    fun `produces the document outline with navigable offsets`() =
        withDocument("# One\n\n한국어 문단\n\n## Two\n") { document ->
            val outline = document.outline()
            assertEquals(2, outline.size)
            assertEquals("One", outline[0].title)
            assertEquals(0, outline[0].offset)

            assertEquals("Two", outline[1].title)
            assertEquals(2, outline[1].level)
            assertEquals(4, outline[1].line)
            // Lines above it occupy 6 + 1 + 7 + 1 = 15 UTF-16 units. In bytes it would be 21, so
            // this fails loudly if either side ever reverts to byte offsets.
            assertEquals(15, outline[1].offset)
            assertEquals("## Two", document.text().substring(outline[1].offset, outline[1].offset + 6))
        }

    @Test
    fun `computes statistics`() = withDocument("# Title\n\nOne two three.\n\n```rust\nfn a() {}\n```\n") { document ->
        val stats = document.stats()
        assertEquals(4, stats.words, "the fence body is not prose")
        assertEquals(1, stats.headings)
        assertEquals(1, stats.codeBlocks)
        assertEquals(1, stats.paragraphs)
        assertTrue(stats.readingTimeSeconds > 0)
    }

    @Test
    fun `highlights markdown source with viewport windowing`() =
        withDocument("# Heading\n\n**bold** text\n\n`code`\n") { document ->
            val all = document.spans(0, Int.MAX_VALUE - 1)
            assertTrue(all.size >= 3)
            assertTrue(all.all { it.start < it.end }, "spans must be non-empty")
            assertTrue(all.all { it.end <= document.length }, "spans must stay inside the document")

            // Restricting the window restricts the result.
            val firstLineOnly = document.spans(0, 0)
            assertEquals(1, firstLineOnly.size)
            assertEquals(0, firstLineOnly[0].start)
        }

    @Test
    fun `rejects an inverted span window before calling the engine`() = withDocument("a\nb\n") { document ->
        assertFailsWith<IllegalArgumentException> { document.spans(5, 1) }
        assertFailsWith<IllegalArgumentException> { document.spans(-1, 5) }
    }

    @Test
    fun `searches with flags`() = withDocument("Cat cat catalogue\n") { document ->
        // Case-sensitive: "cat" and the "cat" inside "catalogue"; "Cat" does not match.
        assertEquals(2, document.search("cat").size)
        assertEquals(3, document.search("cat", SearchFlags.CASE_INSENSITIVE).size)
        assertEquals(2, document.search("cat", SearchFlags.CASE_INSENSITIVE or SearchFlags.WHOLE_WORD).size)
        assertEquals(3, document.search("c.t", SearchFlags.CASE_INSENSITIVE or SearchFlags.REGEX).size)
        assertTrue(document.search("").isEmpty())
    }

    @Test
    fun `search reports utf16 positions`() = withDocument("한국어 target\n") { document ->
        val match = document.search("target").single()
        assertEquals(4, match.start)
        assertEquals(10, match.end)
        assertEquals(0, match.line)
        assertEquals(4, match.column)
        assertEquals("target", document.text().substring(match.start, match.end))
    }

    @Test
    fun `reports an invalid regular expression`() = withDocument("text") { document ->
        val failure = assertFailsWith<QuillEngineException> { document.search("[unclosed", SearchFlags.REGEX) }
        assertEquals(-4, failure.status)
        assertContains(failure.message.orEmpty(), "regular expression")
    }

    @Test
    fun `replaces all matches`() = withDocument("a-b-c\n") { document ->
        document.replaceAll("-", "+")
        assertEquals("a+b+c\n", document.text())

        // Literal mode must not expand `$` as a capture reference.
        document.setText("value\n")
        document.replaceAll("value", "\$100")
        assertEquals("\$100\n", document.text())
    }

    @Test
    fun `exports html`() = withDocument("# Title\n\n한국어 🪶\n") { document ->
        val fragment = document.exportHtml("Doc", ExportOptions.NONE)
        assertContains(fragment, "<h1>Title</h1>")
        assertTrue(!fragment.contains("<!doctype html>"))

        val standalone = document.exportHtml("My Document", ExportOptions.STANDALONE or ExportOptions.DARK)
        assertTrue(standalone.startsWith("<!doctype html>"))
        assertContains(standalone, "<title>My Document</title>")
        assertContains(standalone, "한국어 🪶")
    }

    @Test
    fun `highlights fenced code and follows the palette`() {
        val dark = engine.highlightCode("fn main() { let x = 1; }", "rust")
        assertTrue(dark.size > 1, "expected multiple coloured runs")
        assertTrue(dark.all { it.start < it.end })
        assertEquals(0, dark.first().start)

        engine.setDarkTheme(false)
        val light = engine.highlightCode("fn main() { let x = 1; }", "rust")
        engine.setDarkTheme(true)
        assertNotEquals(dark.first().argb, light.first().argb)
    }

    @Test
    fun `unknown code languages still return one run`() {
        val spans = engine.highlightCode("whatever", "not-a-real-language")
        assertEquals(1, spans.size)
        assertEquals(0, spans[0].start)
        assertEquals(8, spans[0].end)
        assertTrue(engine.highlightCode("", "rust").isEmpty())
    }

    @Test
    fun `code highlight offsets are utf16`() {
        val code = "let s = \"한국어\";"
        val spans = engine.highlightCode(code, "rust")
        assertEquals(code.length, spans.last().end, "the last span must end at the string's UTF-16 length")
    }

    @Test
    fun `closing a document twice is safe and using it afterwards fails cleanly`() {
        val document = engine.openDocument("x")
        document.close()
        document.close()
        assertFailsWith<IllegalStateException> { document.text() }
    }

    @Test
    fun `handles an empty document`() = withDocument("") { document ->
        assertEquals("", document.text())
        assertEquals(0, document.length)
        assertTrue(document.blocks().isEmpty())
        assertTrue(document.outline().isEmpty())
        assertTrue(document.spans(0, 100).isEmpty())
        assertEquals(0, document.stats().words)
    }

    @Test
    fun `survives many allocations without leaking handles`() {
        // Exercises the buffer alloc/free pairing: every call allocates in Rust and frees through
        // quill_buf_free. A mismatch shows up here as steadily growing RSS or a crash.
        repeat(500) { index ->
            engine.openDocument("# Document $index\n\nWith some body text.\n").use { document ->
                document.blocks()
                document.outline()
                document.stats()
                document.spans(0, 10)
            }
        }
    }

    @Test
    fun `is usable concurrently from several threads`() {
        // The UI calls the engine from coroutine dispatcher threads, so a shared document has to
        // tolerate concurrent access.
        engine.openDocument("# Shared\n\nbody\n").use { document ->
            val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
            val threads = (0 until 4).map { worker ->
                Thread {
                    runCatching {
                        repeat(50) {
                            document.stats()
                            document.outline()
                            document.spans(0, 5)
                        }
                    }.onFailure { failures += it }
                }.apply { name = "quill-test-$worker"; start() }
            }
            threads.forEach { it.join() }
            assertTrue(failures.isEmpty(), "concurrent access failed: ${failures.firstOrNull()}")
        }
    }

    @Test
    fun `reports no error when nothing has failed`() {
        // Draining first, because an earlier assertion in this class may have left one behind.
        lastEngineError()
        assertNull(lastEngineError())
    }
}
