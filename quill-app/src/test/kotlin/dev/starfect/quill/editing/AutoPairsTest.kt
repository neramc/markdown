package dev.starfect.quill.editing

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Closing pairs and wrapping selections.
 *
 * Each case is written the way the editor sees it: the value before the keystroke, and the value
 * Compose hands back after it. That is the real interface — there is no key event to test against.
 */
class AutoPairsTest {

    private fun value(text: String, cursor: Int) = TextFieldValue(text, TextRange(cursor))
    private fun selected(text: String, from: Int, to: Int) = TextFieldValue(text, TextRange(from, to))

    /** What the field would hold if the character were simply inserted, which is what Compose sends. */
    private fun typed(before: TextFieldValue, character: Char): TextFieldValue {
        val start = before.selection.min
        val end = before.selection.max
        return TextFieldValue(before.text.replaceRange(start, end, character.toString()), TextRange(start + 1))
    }

    private fun apply(
        before: TextFieldValue,
        after: TextFieldValue,
        close: Boolean = true,
        surround: Boolean = true,
    ) = AutoPairs.apply(before, after, close, surround)

    private fun type(
        text: String,
        cursor: Int,
        character: Char,
        close: Boolean = true,
        surround: Boolean = true,
    ): TextFieldValue {
        val before = value(text, cursor)
        return apply(before, typed(before, character), close, surround)
    }

    // ------------------------------------------------------------------ closing

    @Test
    fun `an opening bracket brings its partner and leaves the caret between them`() {
        val result = type("", 0, '(')
        assertEquals("()", result.text)
        assertEquals(1, result.selection.start)
    }

    @Test
    fun `a backtick closes itself, because inline code is typed constantly`() {
        assertEquals("``", type("", 0, '`').text)
    }

    @Test
    fun `nothing is closed in the middle of a word`() {
        // Typing `(` before "word" should give "(word", not "()word" -- the bracket is being put
        // around what is already there.
        assertEquals("(word", type("word", 0, '(').text)
    }

    @Test
    fun `a pair does open before whitespace and before a closing bracket`() {
        // The pair goes in at the caret; the space that was already there stays after it.
        assertEquals("() ", type(" ", 0, '(').text)
        assertEquals("(())", type("()", 1, '(').text)
    }

    @Test
    fun `an apostrophe after a word is not the start of a quotation`() {
        // The failure this prevents is "don't" becoming "don''t", which would make the feature
        // intolerable within about four words of prose.
        assertEquals("don\"", type("don", 3, '"').text)
    }

    @Test
    fun `a quote at the start of a word does open`() {
        assertEquals("\"\"", type("", 0, '"').text)
    }

    @Test
    fun `typing the closing character steps over the one already there`() {
        val result = type("()", 1, ')')
        assertEquals("()", result.text, "the bracket must not be doubled")
        assertEquals(2, result.selection.start, "the caret should have moved past it")
    }

    @Test
    fun `deleting an opener takes its empty partner`() {
        val before = value("()", 1)
        val after = value(")", 0)
        assertEquals("", apply(before, after).text)
    }

    @Test
    fun `deleting an opener with content between the pair leaves the closer alone`() {
        val before = value("(a)", 1)
        val after = value("a)", 0)
        assertEquals("a)", apply(before, after).text)
    }

    // ------------------------------------------------------------------ surrounding

    @Test
    fun `typing an asterisk over a selection wraps it`() {
        val before = selected("make this bold", 5, 9)
        val result = apply(before, typed(before, '*'))

        assertEquals("make *this* bold", result.text)
        assertEquals(6, result.selection.start, "the selection should still be around the word")
        assertEquals(10, result.selection.end)
    }

    @Test
    fun `wrapping twice gives bold, which is how bold is typed`() {
        val once = apply(selected("this", 0, 4), typed(selected("this", 0, 4), '*'))
        val twice = apply(once, typed(once, '*'))
        assertEquals("**this**", twice.text)
    }

    @Test
    fun `brackets and backticks wrap too`() {
        val before = selected("a link", 2, 6)
        assertEquals("a [link]", apply(before, typed(before, '[')).text)
        assertEquals("a `link`", apply(before, typed(before, '`')).text)
    }

    @Test
    fun `an asterisk wraps a selection but never closes itself`() {
        // The Markdown-specific asymmetry, and the reason for it: `* ` at the start of a line is a
        // bullet, and an editor that turns it into `**` has broken the commonest thing anybody types.
        assertEquals("*", type("", 0, '*').text)
        assertEquals("* ", type(" ", 0, '*').text)

        val before = selected("word", 0, 4)
        assertEquals("*word*", apply(before, typed(before, '*')).text)
    }

    // ------------------------------------------------------------------ leaving things alone

    @Test
    fun `both behaviours can be turned off`() {
        assertEquals("(", type("", 0, '(', close = false).text)

        val before = selected("word", 0, 4)
        assertEquals("*", apply(before, typed(before, '*'), close = true, surround = false).text)
    }

    @Test
    fun `a paste of several characters passes through untouched`() {
        val before = value("", 0)
        val after = value("(pasted)", 8)
        assertEquals(after, apply(before, after))
    }

    @Test
    fun `an ordinary character passes through untouched`() {
        assertEquals("a", type("", 0, 'a').text)
        assertEquals("한", type("", 0, '한').text)
    }

    @Test
    fun `a selection change passes through untouched`() {
        val before = value("some text", 0)
        val after = selected("some text", 0, 4)
        assertEquals(after, apply(before, after))
    }

    @Test
    fun `an ordinary deletion passes through untouched`() {
        val before = value("word", 4)
        val after = value("wor", 3)
        assertEquals(after, apply(before, after))
    }
}
