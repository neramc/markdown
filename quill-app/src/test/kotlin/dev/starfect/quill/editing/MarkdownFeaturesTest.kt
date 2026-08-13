package dev.starfect.quill.editing

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The feature catalogue: the searching, the trigger detection, and the edits themselves.
 *
 * The searching cases are written as "somebody who does not know the name" — `tbl`, `callout`,
 * `tick box` — because that is the whole reason the list exists. A search that only matches the
 * exact name helps nobody who needed the list in the first place.
 */
class MarkdownFeaturesTest {

    private fun feature(id: String) = MarkdownFeatures.all.first { it.id == id }

    private fun value(marked: String): TextFieldValue {
        val open = marked.indexOf('«')
        if (open >= 0) {
            val close = marked.indexOf('»')
            val text = marked.removeRange(close, close + 1).removeRange(open, open + 1)
            return TextFieldValue(text, TextRange(open, close - 1))
        }
        val caret = marked.indexOf('|')
        return TextFieldValue(marked.removeRange(caret, caret + 1), TextRange(caret))
    }

    private fun show(value: TextFieldValue): String {
        val selection = value.selection
        return if (selection.collapsed) {
            StringBuilder(value.text).insert(selection.start, '|').toString()
        } else {
            StringBuilder(value.text).insert(selection.max, '»').insert(selection.min, '«').toString()
        }
    }

    // ------------------------------------------------------------------ the catalogue itself

    @Test
    fun `every feature has a unique id and something to show`() {
        val ids = MarkdownFeatures.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size, "duplicate ids: ${ids.groupBy { it }.filter { it.value.size > 1 }.keys}")
        MarkdownFeatures.all.forEach {
            assertTrue(it.name.isNotBlank(), "${it.id} has no name")
            assertTrue(it.description.isNotBlank(), "${it.id} has no description")
        }
    }

    @Test
    fun `the catalogue covers the things people actually look up`() {
        // Not an exhaustive list — a spot check that the catalogue is a reference rather than a
        // toolbar in a different shape.
        val names = MarkdownFeatures.all.map { it.name.lowercase() }
        for (expected in listOf("table", "task list", "footnote", "collapsible section", "front matter")) {
            assertTrue(names.any { it == expected }, "the list should offer '$expected'")
        }
    }

    // ------------------------------------------------------------------ searching

    @Test
    fun `an empty query offers everything`() {
        assertEquals(MarkdownFeatures.all.size, MarkdownFeatures.search("").size)
        assertEquals(MarkdownFeatures.all.size, MarkdownFeatures.search("   ").size)
    }

    @Test
    fun `a name is found by its prefix and ranks first`() {
        assertEquals("table", MarkdownFeatures.search("tab").first().id)
        assertEquals("bold", MarkdownFeatures.search("bol").first().id)
    }

    @Test
    fun `a feature is found by a word that is not its name`() {
        // Nobody looking for `> [!NOTE]` searches for "note callout"; they search for "callout" or
        // "admonition" or "banner".
        assertTrue(MarkdownFeatures.search("callout").any { it.id.startsWith("alert-") })
        assertTrue(MarkdownFeatures.search("admonition").any { it.id.startsWith("alert-") })
        assertEquals("task-list", MarkdownFeatures.search("tick box").first().id)
        assertEquals("task-list", MarkdownFeatures.search("todo").first().id)
        assertEquals("divider", MarkdownFeatures.search("separator").first().id)
    }

    @Test
    fun `the syntax itself is searchable`() {
        // Somebody who half-remembers the characters can type them.
        assertTrue(MarkdownFeatures.search("```").any { it.id == "code-block" })
        assertTrue(MarkdownFeatures.search("[^").any { it.id == "footnote" })
    }

    @Test
    fun `a subsequence finds a name nobody typed in full`() {
        assertEquals("heading3", MarkdownFeatures.search("hd3").first().id)
    }

    @Test
    fun `a query matching nothing returns nothing rather than everything`() {
        assertTrue(MarkdownFeatures.search("zzzzqqq").isEmpty())
    }

    // ------------------------------------------------------------------ the slash trigger

    @Test
    fun `a slash on an empty line opens the list`() {
        assertEquals(0, MarkdownFeatures.triggerStart(value("/|")))
        assertEquals(6, MarkdownFeatures.triggerStart(value("intro\n/|")))
    }

    @Test
    fun `a slash inside a sentence is a slash`() {
        // `and/or`, `2026/08/13`, `src/main` — a menu appearing over any of these makes prose
        // unwritable, and this is the single most important thing about the trigger.
        assertNull(MarkdownFeatures.triggerStart(value("and/or|")))
        assertNull(MarkdownFeatures.triggerStart(value("see src/main|")))
        assertNull(MarkdownFeatures.triggerStart(value("2026/08|")))
    }

    @Test
    fun `a slash after a space in the query closes the list`() {
        // The writer went back to writing.
        assertNull(MarkdownFeatures.triggerStart(value("/table and|")))
    }

    @Test
    fun `a slash inside an indented list item still opens the list`() {
        assertEquals(4, MarkdownFeatures.triggerStart(value("a\n  /|")))
    }

    @Test
    fun `the trigger reads the query typed after the slash`() {
        val typed = value("/tab|")
        val start = assertNotNull(MarkdownFeatures.triggerStart(typed))
        assertEquals("tab", MarkdownFeatures.triggerQuery(typed, start))
    }

    @Test
    fun `choosing a feature removes the text that opened the list`() {
        val typed = value("intro\n/tab|")
        val start = assertNotNull(MarkdownFeatures.triggerStart(typed))
        assertEquals("intro\n|", show(MarkdownFeatures.removeTrigger(typed, start)))
    }

    @Test
    fun `a selection means the caret is not in a trigger`() {
        assertNull(MarkdownFeatures.triggerStart(value("«/tab»")))
    }

    // ------------------------------------------------------------------ the edits

    @Test
    fun `a heading replaces whatever level the line already had`() {
        assertEquals("## Title|", show(feature("heading2").apply(value("Ti|tle"))))
        assertEquals("### Title|", show(feature("heading3").apply(value("# Ti|tle"))))
        assertEquals("Title|", show(feature("paragraph").apply(value("#### Ti|tle"))))
    }

    @Test
    fun `a table is inserted with a header row and a place to type`() {
        val result = feature("table").apply(value("|"))
        assertTrue(result.text.startsWith("| Column | Column | Column |\n| ------"), result.text)
    }

    @Test
    fun `a block dropped into the middle of a paragraph gets its own line`() {
        val result = feature("divider").apply(value("some text|"))
        assertEquals("some text\n---\n", result.text)
    }

    @Test
    fun `an ordered list numbers its lines and toggles back off`() {
        val numbered = feature("numbered-list").apply(value("«one\ntwo»"))
        assertEquals("1. one\n2. two", numbered.text)
        assertEquals("one\ntwo", feature("numbered-list").apply(numbered).text)
    }

    @Test
    fun `a footnote numbers itself past the ones already there`() {
        val existing = TextFieldValue("A[^1] and B[^2].|".replace("|", ""), TextRange(15))
        val result = feature("footnote").apply(existing)
        assertTrue(result.text.contains("[^3]"), result.text)
        assertTrue(result.text.trimEnd().endsWith("[^3]:"), result.text)
    }

    @Test
    fun `a footnote in an empty document starts at one`() {
        val result = feature("footnote").apply(value("text|"))
        assertTrue(result.text.startsWith("text[^1]"), result.text)
    }

    @Test
    fun `front matter goes to the very top and only once`() {
        val once = feature("front-matter").apply(value("# Title\n|"))
        assertTrue(once.text.startsWith("---\ntitle: \ndate: \n---\n\n# Title"), once.text)
        assertEquals(once.text, feature("front-matter").apply(once).text, "a second insert should be a no-op")
    }

    @Test
    fun `inline features wrap the selection when there is one`() {
        assertEquals("a `«word»` b", show(feature("inline-code").apply(value("a «word» b"))))
        assertEquals("a ==«word»== b", show(feature("highlight").apply(value("a «word» b"))))
    }

    @Test
    fun `escaping a selection escapes every character that is syntax`() {
        val result = feature("escape").apply(value("«a*b_c[d]»"))
        assertEquals("a\\*b\\_c\\[d\\]", result.text)
    }

    @Test
    fun `a heading anchor is appended to the end of the line, not the caret`() {
        assertEquals("# Ti|tle", show(value("# Ti|tle")), "fixture sanity")
        assertEquals("# Title {#custom-id}|", show(feature("anchor").apply(value("# Ti|tle"))))
    }

    @Test
    fun `every feature leaves the document parseable and the caret inside it`() {
        // A blanket check: whatever each edit does, it may not produce a caret outside the text.
        // A caret past the end throws inside the text field, which is a crash on a menu click.
        val original = value("intro\n\n- one\n- two\n\nend|")
        MarkdownFeatures.all.forEach { candidate ->
            val result = candidate.apply(original)
            assertTrue(
                result.selection.start in 0..result.text.length &&
                    result.selection.end in 0..result.text.length,
                "${candidate.id} put the caret at ${result.selection} in ${result.text.length} characters",
            )
        }
    }
}
