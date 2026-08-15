package dev.starfect.quill.ui.editor

import dev.starfect.quill.bridge.wire.OutlineEntry
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which headings enclose a line.
 *
 * The pure part of sticky scrolling, and the part worth pinning: the drawing is a row of labels,
 * but "which headings am I underneath" is a question with a wrong answer available at every step.
 */
class StickyHeadingsTest {

    private fun outline(vararg entries: Pair<Int, String>): List<OutlineEntry> =
        entries.mapIndexed { index, (level, title) ->
            OutlineEntry(level = level, line = index * 10, offset = index * 10, title = title)
        }

    @Test
    fun `the chain is the path down to the innermost heading, not the last few seen`() {
        // # A (0) / ## B (10) / ### C (20) / ## D (30)
        val outline = outline(1 to "A", 2 to "B", 3 to "C", 2 to "D")

        // Under D, B and C are finished — D replaced B at its level and closed C with it.
        assertEquals(listOf("A", "D"), headingChain(outline, 35).map { it.title })
    }

    @Test
    fun `a deeper heading nests rather than replaces`() {
        val outline = outline(1 to "A", 2 to "B", 3 to "C")
        assertEquals(listOf("A", "B", "C"), headingChain(outline, 25).map { it.title })
    }

    @Test
    fun `a heading on the first visible line needs no reminder of itself`() {
        // It is on screen. Repeating it above itself wastes the row and reads as a duplicate.
        val outline = outline(1 to "A", 2 to "B")
        assertEquals(listOf("A"), headingChain(outline, 10).map { it.title })
    }

    @Test
    fun `nothing above the top of the document`() {
        assertEquals(emptyList(), headingChain(outline(1 to "A"), 0))
        assertEquals(emptyList(), headingChain(emptyList(), 100))
    }

    @Test
    fun `a document that starts deep still produces a chain`() {
        // Plenty of real documents start at h2 because the h1 is the file name.
        val outline = outline(2 to "B", 3 to "C")
        assertEquals(listOf("B", "C"), headingChain(outline, 25).map { it.title })
    }

    @Test
    fun `a level jump does not lose the outer heading`() {
        val outline = outline(1 to "A", 4 to "D")
        assertEquals(listOf("A", "D"), headingChain(outline, 25).map { it.title })
    }
}
