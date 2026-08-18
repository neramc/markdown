package dev.starfect.quill.editing

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Undo, and specifically the grouping.
 *
 * A stack with one entry per keystroke is not undo, it is a slow rewind — undoing a sentence takes
 * forty presses — so every assertion here is about which edits merge into one step and which do
 * not. The mechanics of a stack are the easy half.
 */
class UndoHistoryTest {

    private fun value(text: String, caret: Int = text.length) = TextFieldValue(text, TextRange(caret))

    /** Types [text] one character at a time, a few milliseconds apart, as somebody would. */
    private fun UndoHistory.type(from: String, text: String, startingAt: Long = 1_000): String {
        var current = from
        text.forEachIndexed { index, character ->
            current += character
            record(value(current), now = startingAt + index * 50L)
        }
        return current
    }

    @Test
    fun `nothing to undo before anything is typed`() {
        val history = UndoHistory()
        history.reset(value(""))
        assertFalse(history.canUndo)
        assertNull(history.undo())
    }

    @Test
    fun `a word typed quickly undoes in one press`() {
        // The whole point. Forty presses to remove a sentence is what makes an editor feel
        // unfinished, and it is the first thing anybody tries.
        val history = UndoHistory()
        history.reset(value(""))
        history.type("", "hello")

        assertEquals("", history.undo()?.text)
        assertFalse(history.canUndo)
    }

    @Test
    fun `a space closes the group, so undo steps back a word at a time`() {
        val history = UndoHistory()
        history.reset(value(""))
        var text = history.type("", "hello")
        text = history.type(text, " ")
        history.type(text, "world")

        assertEquals("hello ", history.undo()?.text, "the second word should go on its own")
        assertEquals("hello", history.undo()?.text, "then the space")
        assertEquals("", history.undo()?.text, "then the first word")
    }

    @Test
    fun `a pause starts a new step`() {
        // A pause is where a writer's intent changed even when the characters did not.
        val history = UndoHistory()
        history.reset(value(""))
        history.record(value("a"), now = 1_000)
        history.record(value("ab"), now = 1_050)
        history.record(value("abc"), now = 1_050 + UndoHistory.COALESCE_MILLIS + 1)

        assertEquals("ab", history.undo()?.text)
        assertEquals("", history.undo()?.text)
    }

    @Test
    fun `typing and deleting never merge`() {
        // Backspacing over a word you just typed is an undo performed by hand. Folding the two
        // together makes one Ctrl+Z put back something the writer deliberately removed.
        val history = UndoHistory()
        history.reset(value(""))
        history.record(value("ab"), now = 1_000)
        history.record(value("abc"), now = 1_020)
        history.record(value("ab"), now = 1_040)
        history.record(value("a"), now = 1_060)

        assertEquals("abc", history.undo()?.text, "the deletions undo together, back to before them")
        assertEquals("", history.undo()?.text)
    }

    @Test
    fun `a paste is one step however large it is`() {
        val history = UndoHistory()
        history.reset(value("start"))
        history.record(value("start" + "a".repeat(500)), now = 1_000, coalesce = false)

        assertEquals("start", history.undo()?.text)
    }

    @Test
    fun `an edit larger than one character is its own step even when coalescing is allowed`() {
        // Something that arrived through the typing path but is not typing -- an input method
        // committing a syllable, a bracket pair inserted whole.
        val history = UndoHistory()
        history.reset(value(""))
        history.record(value("a"), now = 1_000)
        history.record(value("a()"), now = 1_020)

        assertEquals("a", history.undo()?.text)
    }

    @Test
    fun `moving the caret is not an edit`() {
        // Recording it would fill the history with entries that undo to the same text, which looks
        // from the outside like Ctrl+Z doing nothing.
        val history = UndoHistory()
        history.reset(value("hello"))
        history.record(TextFieldValue("hello", TextRange(0)), now = 2_000)
        history.record(TextFieldValue("hello", TextRange(3)), now = 3_000)

        assertFalse(history.canUndo)
    }

    @Test
    fun `redo puts back what undo removed`() {
        val history = UndoHistory()
        history.reset(value(""))
        history.type("", "one")
        history.record(value("one "), now = 5_000)
        history.type("one ", "two", startingAt = 6_000)

        assertEquals("one ", history.undo()?.text)
        assertTrue(history.canRedo)
        assertEquals("one two", history.redo()?.text)
        assertFalse(history.canRedo)
    }

    @Test
    fun `a new edit after an undo discards the redo branch`() {
        // The alternative is a redo that reinstates text from a future the writer has left, which
        // is how an editor loses work while appearing to restore it.
        val history = UndoHistory()
        history.reset(value(""))
        history.type("", "abc")
        history.undo()

        history.record(value("z"), now = 9_000)

        assertFalse(history.canRedo)
    }

    @Test
    fun `undo restores the caret to where the edit happened`() {
        val history = UndoHistory()
        history.reset(TextFieldValue("hello world", TextRange(5)))
        history.record(TextFieldValue("hello, world", TextRange(6)), now = 1_000, coalesce = false)

        val restored = history.undo()
        assertEquals("hello world", restored?.text)
        assertEquals(TextRange(5), restored?.selection)
    }

    @Test
    fun `the history is bounded`() {
        val history = UndoHistory()
        history.reset(value(""))
        // Each one its own step, well past the cap.
        repeat(UndoHistory.MAX_ENTRIES + 50) { history.record(value("x".repeat(it + 1)), now = it * 10_000L) }

        var steps = 0
        while (history.undo() != null) steps++
        assertEquals(UndoHistory.MAX_ENTRIES, steps)
    }

    @Test
    fun `resetting forgets both directions`() {
        val history = UndoHistory()
        history.reset(value(""))
        history.type("", "abc")
        history.undo()

        history.reset(value("a different file"))

        assertFalse(history.canUndo, "undo must not step into the document that was open before")
        assertFalse(history.canRedo)
    }
}
