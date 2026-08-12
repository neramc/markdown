package dev.starfect.quill.editing

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The editing actions, exercised as string in / string out.
 *
 * `|` marks the caret and `«...»` a selection, so a case reads as the thing the writer would see
 * rather than as a pair of integers. The caret is asserted as often as the text is: an action that
 * produces the right characters and leaves the caret somewhere arbitrary is one the writer has to
 * correct every time, which is worse than not having it.
 *
 * The guillemets are not decoration. The first version of this harness marked selections with
 * `[...]`, which collides with the brackets in Markdown's own link and task syntax — `- [x] done`
 * parsed as a selection from the first bracket, and two tests failed against correct code.
 */
class MarkdownEditsTest {

    // ------------------------------------------------------------------ fixture helpers

    /** Parses `a|b` or `a«bc»d` into a value. */
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

    /** Renders a value back into the same notation, so a failure prints something readable. */
    private fun show(value: TextFieldValue): String {
        val selection = value.selection
        return if (selection.collapsed) {
            StringBuilder(value.text).insert(selection.start, '|').toString()
        } else {
            StringBuilder(value.text).insert(selection.max, '»').insert(selection.min, '«').toString()
        }
    }

    private fun assertEdit(before: String, after: String, edit: (TextFieldValue) -> TextFieldValue) {
        assertEquals(after, show(edit(value(before))))
    }

    // ------------------------------------------------------------------ emphasis

    @Test
    fun `bold wraps the selection and keeps it selected`() {
        assertEdit("say «hello» there", "say **«hello»** there") {
            MarkdownEdits.toggleEmphasis(it, "**")
        }
    }

    @Test
    fun `bold with no selection takes the word under the caret`() {
        assertEdit("say hel|lo there", "say **«hello»** there") {
            MarkdownEdits.toggleEmphasis(it, "**")
        }
    }

    @Test
    fun `bold twice leaves the text as it started`() {
        val original = value("say «hello» there")
        val once = MarkdownEdits.toggleEmphasis(original, "**")
        val twice = MarkdownEdits.toggleEmphasis(once, "**")
        assertEquals(original.text, twice.text, "toggling twice should be a no-op")
        assertEquals(original.selection, twice.selection, "and should leave the same selection")
    }

    @Test
    fun `unwrapping works whether or not the markers are inside the selection`() {
        // Selection is the words only; the markers sit outside it.
        assertEdit("say **«hello»** there", "say «hello» there") {
            MarkdownEdits.toggleEmphasis(it, "**")
        }
        // Selection includes the markers.
        assertEdit("say «**hello**» there", "say «hello» there") {
            MarkdownEdits.toggleEmphasis(it, "**")
        }
    }

    @Test
    fun `italic and code use the same machinery`() {
        assertEdit("a «word» b", "a *«word»* b") { MarkdownEdits.toggleEmphasis(it, "*") }
        assertEdit("a «word» b", "a `«word»` b") { MarkdownEdits.toggleEmphasis(it, "`") }
        assertEdit("a «word» b", "a ~~«word»~~ b") { MarkdownEdits.toggleEmphasis(it, "~~") }
    }

    @Test
    fun `emphasis at the very start and end of the document does not run off the ends`() {
        assertEdit("«all»", "**«all»**") { MarkdownEdits.toggleEmphasis(it, "**") }
    }

    // ------------------------------------------------------------------ links

    @Test
    fun `a selected label leaves the caret in the destination`() {
        assertEdit("see «docs» here", "see [docs](|) here") { MarkdownEdits.insertLink(it) }
    }

    @Test
    fun `a selected URL becomes the destination and the caret goes to the label`() {
        assertEdit("see «https://example.com» here", "see [|](https://example.com) here") {
            MarkdownEdits.insertLink(it)
        }
    }

    @Test
    fun `a pasted URL over a label fills both halves and selects the result`() {
        assertEdit("see «docs» here", "see «[docs](https://example.com)» here") {
            MarkdownEdits.insertLink(it, "https://example.com")
        }
    }

    @Test
    fun `an empty link is inserted at the caret`() {
        assertEdit("see | here", "see [|]() here") { MarkdownEdits.insertLink(it) }
    }

    // ------------------------------------------------------------------ headings

    @Test
    fun `promoting a plain line makes it a heading and promoting again deepens it`() {
        assertEdit("ti|tle", "«# title»") { MarkdownEdits.shiftHeading(it, 1) }
        assertEdit("# ti|tle", "«## title»") { MarkdownEdits.shiftHeading(it, 1) }
    }

    @Test
    fun `demoting a level one heading removes the marker`() {
        assertEdit("# ti|tle", "«title»") { MarkdownEdits.shiftHeading(it, -1) }
    }

    @Test
    fun `heading level stops at six`() {
        assertEdit("###### ti|tle", "«###### title»") { MarkdownEdits.shiftHeading(it, 1) }
    }

    @Test
    fun `a multi-line selection is promoted line by line`() {
        assertEdit("«one\ntwo»", "«# one\n# two»") { MarkdownEdits.shiftHeading(it, 1) }
    }

    // ------------------------------------------------------------------ lists and quotes

    @Test
    fun `bullets toggle on and off across a selection`() {
        assertEdit("«one\ntwo»", "«- one\n- two»") { MarkdownEdits.toggleBullet(it) }
        assertEdit("«- one\n- two»", "«one\ntwo»") { MarkdownEdits.toggleBullet(it) }
    }

    @Test
    fun `a mixed selection becomes fully bulleted rather than toggling item by item`() {
        assertEdit("«- one\ntwo»", "«- - one\n- two»") { MarkdownEdits.toggleBullet(it) }
    }

    @Test
    fun `tasks are added, ticked and unticked`() {
        assertEdit("«one»", "«- [ ] one»") { MarkdownEdits.toggleTask(it) }
        assertEdit("«- [ ] one»", "«- [x] one»") { MarkdownEdits.toggleTask(it) }
        assertEdit("«- [x] one»", "«- [ ] one»") { MarkdownEdits.toggleTask(it) }
    }

    @Test
    fun `quotes toggle on and off`() {
        assertEdit("«one\ntwo»", "«> one\n> two»") { MarkdownEdits.toggleQuote(it) }
        assertEdit("«> one\n> two»", "«one\ntwo»") { MarkdownEdits.toggleQuote(it) }
    }

    @Test
    fun `a blank line inside a selection is left alone`() {
        assertEdit("«one\n\ntwo»", "«- one\n\n- two»") { MarkdownEdits.toggleBullet(it) }
    }

    // ------------------------------------------------------------------ Enter

    @Test
    fun `Enter continues a bullet`() {
        assertEdit("- one|", "- one\n- |") { MarkdownEdits.continueBlock(it)!! }
    }

    @Test
    fun `Enter advances an ordered marker`() {
        assertEdit("1. one|", "1. one\n2. |") { MarkdownEdits.continueBlock(it)!! }
        assertEdit("7) seven|", "7) seven\n8) |") { MarkdownEdits.continueBlock(it)!! }
    }

    @Test
    fun `Enter continues a task as unticked`() {
        // Copying the tick would tick a task nobody has done.
        assertEdit("- [x] done|", "- [x] done\n- [ ] |") { MarkdownEdits.continueBlock(it)!! }
    }

    @Test
    fun `Enter on an empty marker clears the line instead of adding another`() {
        assertEdit("- one\n- |", "- one\n|") { MarkdownEdits.continueBlock(it)!! }
    }

    @Test
    fun `Enter continues a quote and preserves indentation`() {
        assertEdit("> quoted|", "> quoted\n> |") { MarkdownEdits.continueBlock(it)!! }
        assertEdit("  - nested|", "  - nested\n  - |") { MarkdownEdits.continueBlock(it)!! }
    }

    @Test
    fun `Enter on ordinary prose is left to the editor`() {
        assertNull(MarkdownEdits.continueBlock(value("just prose|")))
    }

    @Test
    fun `Enter with a selection is left to the editor`() {
        assertNull(MarkdownEdits.continueBlock(value("- «one»")))
    }

    // ------------------------------------------------------------------ line moves

    @Test
    fun `a line moves up and down and stays selected`() {
        assertEdit("one\n«two»\nthree", "«two»\none\nthree") { MarkdownEdits.moveLines(it, -1) }
        assertEdit("one\n«two»\nthree", "one\nthree\n«two»") { MarkdownEdits.moveLines(it, 1) }
    }

    @Test
    fun `moving past an edge does nothing`() {
        assertEdit("«one»\ntwo", "«one»\ntwo") { MarkdownEdits.moveLines(it, -1) }
        assertEdit("one\n«two»", "one\n«two»") { MarkdownEdits.moveLines(it, 1) }
    }

    @Test
    fun `duplicating copies the block below and selects the copy`() {
        assertEdit("one\n«two»\nthree", "one\ntwo\n«two»\nthree") { MarkdownEdits.duplicateLines(it) }
    }

    // ------------------------------------------------------------------ tables

    @Test
    fun `a ragged table is padded to its widest cells`() {
        val before = TextFieldValue(
            "| Name | Value |\n|---|---|\n| a | 1 |\n| longer name | 42 |\n",
            TextRange(0),
        )
        val formatted = MarkdownEdits.formatTable(before)!!
        assertEquals(
            """
            | Name        | Value |
            | ----------- | ----- |
            | a           | 1     |
            | longer name | 42    |

            """.trimIndent(),
            formatted.text,
        )
    }

    @Test
    fun `alignment markers survive formatting and drive the padding`() {
        val before = TextFieldValue(
            "| L | C | R |\n|:---|:---:|---:|\n| a | b | c |\n",
            TextRange(0),
        )
        val formatted = MarkdownEdits.formatTable(before)!!
        assertEquals(
            """
            | L   |  C  |   R |
            | :-- | :-: | --: |
            | a   |  b  |   c |

            """.trimIndent(),
            formatted.text,
        )
    }

    @Test
    fun `a wide-character cell is measured by code points, not by chars`() {
        // Korean and emoji are one code point each; measuring in UTF-16 chars would count the emoji
        // twice and pad the column short.
        val before = TextFieldValue("| a | b |\n|---|---|\n| 한국어 | 🚀 |\n", TextRange(0))
        val formatted = MarkdownEdits.formatTable(before)!!
        val rows = formatted.text.trim().split("\n")
        val widths = rows.map { row -> row.split("|").getOrNull(1)?.length }
        assertEquals(1, widths.distinct().size, "every row's first column should be the same width: $rows")
    }

    @Test
    fun `formatting outside a table reports that rather than mangling the text`() {
        assertNull(MarkdownEdits.formatTable(value("just prose|")))
    }

    @Test
    fun `a table is found from a caret anywhere inside it`() {
        val source = "intro\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\nafter\n"
        val caret = source.indexOf("| 1")
        val formatted = MarkdownEdits.formatTable(TextFieldValue(source, TextRange(caret)))!!
        assertEquals(true, formatted.text.startsWith("intro\n\n| a "), "text outside the table moved")
        assertEquals(true, formatted.text.trimEnd().endsWith("after"), "text after the table was lost")
    }
}
