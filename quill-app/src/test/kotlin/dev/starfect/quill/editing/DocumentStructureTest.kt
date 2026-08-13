package dev.starfect.quill.editing

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.starfect.quill.bridge.wire.OutlineEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The structural edits, which are the ones that move somebody's paragraphs around.
 *
 * Each case is written against the source text, because that is what gets saved. The failure these
 * are guarding against is not a cosmetic one: a section move that miscounts a line deletes it, and
 * a checkbox that resolves to the wrong index ticks the wrong task in somebody's checklist.
 */
class DocumentStructureTest {

    /** Builds an outline the way the engine would, from the document's own heading lines. */
    private fun outlineOf(text: String): List<OutlineEntry> {
        var offset = 0
        return text.split("\n").mapIndexedNotNull { line, content ->
            val start = offset
            offset += content.length + 1
            val hashes = content.takeWhile { it == '#' }.length
            if (hashes in 1..6 && content.getOrNull(hashes) == ' ') {
                OutlineEntry(hashes, line, start, content.drop(hashes + 1).trim())
            } else {
                null
            }
        }
    }

    // ------------------------------------------------------------------ tasks

    @Test
    fun `ticking a task changes exactly that task`() {
        val text = "- [ ] one\n- [ ] two\n- [ ] three\n"
        assertEquals("- [ ] one\n- [x] two\n- [ ] three\n", DocumentStructure.toggleTask(text, 1))
    }

    @Test
    fun `unticking a task puts the space back`() {
        assertEquals("- [ ] done\n", DocumentStructure.toggleTask("- [x] done\n", 0))
        assertEquals("- [ ] done\n", DocumentStructure.toggleTask("- [X] done\n", 0))
    }

    @Test
    fun `tasks are counted in reading order across nesting and list kinds`() {
        val text = """
            - [ ] first
              - [x] nested
            1. [ ] ordered
            * [ ] star
        """.trimIndent()

        assertEquals(4, DocumentStructure.taskCount(text))
        assertTrue(DocumentStructure.toggleTask(text, 1)!!.contains("- [ ] nested"))
        assertTrue(DocumentStructure.toggleTask(text, 2)!!.contains("1. [x] ordered"))
    }

    @Test
    fun `a click on a checkbox that is no longer there does nothing`() {
        // The preview is always a derivation behind. A click that misses is far better than one
        // that ticks a different task.
        assertNull(DocumentStructure.toggleTask("- [ ] only\n", 3))
        assertNull(DocumentStructure.toggleTask("no tasks here\n", 0))
    }

    @Test
    fun `bracket text that is not a task is left alone`() {
        val text = "See [x] in the appendix, and a [link](url).\n\n- [ ] real task\n"
        assertEquals(1, DocumentStructure.taskCount(text))
        assertTrue(DocumentStructure.toggleTask(text, 0)!!.contains("- [x] real task"))
        assertTrue(DocumentStructure.toggleTask(text, 0)!!.contains("See [x] in the appendix"))
    }

    // ------------------------------------------------------------------ sections

    private val document = """
        # Title

        Intro paragraph.

        ## Alpha

        Alpha body.

        ### Alpha detail

        Detail body.

        ## Beta

        Beta body.

        ## Gamma

        Gamma body.
    """.trimIndent()

    @Test
    fun `a section runs to the next heading of the same or higher rank`() {
        val sections = DocumentStructure.sections(document, outlineOf(document))
        val alpha = sections.first { it.title == "Alpha" }

        // Alpha owns its subsection, which is what makes dragging it move the whole thing.
        assertTrue(alpha.lastLine > sections.first { it.title == "Alpha detail" }.firstLine)
        assertEquals(sections.first { it.title == "Beta" }.firstLine - 1, alpha.lastLine)
    }

    @Test
    fun `moving a section carries its subsections with it`() {
        val sections = DocumentStructure.sections(document, outlineOf(document))
        val alpha = sections.indexOfFirst { it.title == "Alpha" }
        val gamma = sections.indexOfFirst { it.title == "Gamma" }

        val moved = DocumentStructure.moveSection(document, sections, alpha, gamma)!!
        val order = moved.lines().filter { it.startsWith("#") }

        assertEquals(listOf("# Title", "## Beta", "## Alpha", "### Alpha detail", "## Gamma"), order)
        assertTrue(moved.contains("Detail body."), "the subsection's content moved with it")
    }

    @Test
    fun `moving a section upwards puts it before its destination`() {
        val sections = DocumentStructure.sections(document, outlineOf(document))
        val gamma = sections.indexOfFirst { it.title == "Gamma" }
        val alpha = sections.indexOfFirst { it.title == "Alpha" }

        val moved = DocumentStructure.moveSection(document, sections, gamma, alpha)!!
        val order = moved.lines().filter { it.startsWith("#") }

        assertEquals(listOf("# Title", "## Gamma", "## Alpha", "### Alpha detail", "## Beta"), order)
    }

    @Test
    fun `nothing is lost when a section moves`() {
        val sections = DocumentStructure.sections(document, outlineOf(document))
        for (from in sections.indices) {
            for (to in sections.indices) {
                val moved = DocumentStructure.moveSection(document, sections, from, to) ?: continue
                assertEquals(
                    document.split("\n").sorted(),
                    moved.split("\n").sorted(),
                    "moving $from to $to changed the document's content, not just its order",
                )
            }
        }
    }

    @Test
    fun `a section cannot be dragged into its own body`() {
        // It would mean cutting the text out and pasting it back into the hole it left.
        val sections = DocumentStructure.sections(document, outlineOf(document))
        val alpha = sections.indexOfFirst { it.title == "Alpha" }
        val detail = sections.indexOfFirst { it.title == "Alpha detail" }

        assertNull(DocumentStructure.moveSection(document, sections, alpha, detail))
        assertNull(DocumentStructure.moveSection(document, sections, alpha, alpha))
    }

    @Test
    fun `a document with no headings has no sections`() {
        assertTrue(DocumentStructure.sections("Just prose.\n", emptyList()).isEmpty())
    }

    // ------------------------------------------------------------------ contents

    @Test
    fun `the contents list is indented from the shallowest heading present`() {
        val text = "## Alpha\n\n### Detail\n\n## Beta\n"
        val contents = DocumentStructure.renderContents(outlineOf(text))

        assertEquals(
            "- [Alpha](#alpha)\n  - [Detail](#detail)\n- [Beta](#beta)",
            contents,
        )
    }

    @Test
    fun `the document's own title is left out of its contents`() {
        // A contents list beginning with the name of the page you are on is noise.
        val contents = DocumentStructure.renderContents(outlineOf(document))
        assertTrue(!contents.contains("Title"), contents)
        assertTrue(contents.startsWith("- [Alpha]"), contents)
    }

    @Test
    fun `repeated headings get the numbered anchors GitHub gives them`() {
        val text = "## Notes\n\n## Notes\n\n## Notes\n"
        val contents = DocumentStructure.renderContents(outlineOf(text))

        assertEquals(
            "- [Notes](#notes)\n- [Notes](#notes-1)\n- [Notes](#notes-2)",
            contents,
        )
    }

    @Test
    fun `Korean headings keep their characters in the anchor`() {
        // GitHub anchors `## 한국어` to `#한국어`. A slug that only knew ASCII would link to nothing.
        assertEquals("한국어-제목", DocumentStructure.slug("한국어 제목!"))
        assertTrue(DocumentStructure.renderContents(outlineOf("## 한국어 제목\n")).contains("(#한국어-제목)"))
    }

    @Test
    fun `a marked contents region is rewritten in place`() {
        val text = """
            # Title

            <!-- toc -->
            - [Stale](#stale)
            <!-- /toc -->

            ## Alpha

            ## Beta
        """.trimIndent()

        val updated = DocumentStructure.updateTableOfContents(text, outlineOf(text))!!
        assertTrue(updated.contains("- [Alpha](#alpha)"), updated)
        assertTrue(!updated.contains("Stale"), "the old list should be replaced, not appended to")
        assertTrue(updated.contains("# Title"), "nothing outside the markers should move")
        assertTrue(updated.contains("## Beta"))
    }

    @Test
    fun `a document without markers is left completely alone`() {
        // This is what keeps the feature opt-in per document even with the setting on.
        assertNull(DocumentStructure.updateTableOfContents("# Title\n\n## Alpha\n", outlineOf("# Title\n\n## Alpha\n")))
    }

    @Test
    fun `an unchanged contents list reports no change rather than a rewrite`() {
        val text = "<!-- toc -->\n- [Alpha](#alpha)\n<!-- /toc -->\n\n## Alpha\n"
        assertNull(
            DocumentStructure.updateTableOfContents(text, outlineOf(text)),
            "an update that changes nothing must not mark the document modified",
        )
    }

    @Test
    fun `a document with no headings gets an empty region rather than a broken one`() {
        val text = "<!-- toc -->\n- [Gone](#gone)\n<!-- /toc -->\n\nJust prose.\n"
        val updated = DocumentStructure.updateTableOfContents(text, emptyList())!!

        assertEquals("<!-- toc -->\n<!-- /toc -->\n\nJust prose.\n", updated)
    }

    @Test
    fun `inserting a region writes the markers and the current headings`() {
        val text = "# Title\n\n## Alpha\n"
        val value = TextFieldValue(text, TextRange(8))
        val result = DocumentStructure.insertTableOfContentsRegion(value, outlineOf(text))

        assertTrue(DocumentStructure.hasTableOfContents(result.text))
        assertTrue(result.text.contains("- [Alpha](#alpha)"), result.text)
        assertTrue(result.selection.start <= result.text.length)
    }

    @Test
    fun `a heading containing brackets does not break its own link`() {
        val contents = DocumentStructure.renderContents(outlineOf("## The [draft] section\n"))
        assertTrue(contents.contains("\\[draft\\]"), contents)
    }
}
