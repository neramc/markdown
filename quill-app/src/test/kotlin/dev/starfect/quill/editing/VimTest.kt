package dev.starfect.quill.editing

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Vim, exercised the way it is used: as sequences of keys.
 *
 * Each case types a string of keys at a document and checks what came out, which is the only level
 * at which this is worth testing — the grammar is the feature, and testing `wordForward` in
 * isolation would say nothing about whether `d2w` deletes two words.
 *
 * `|` marks the caret in both the input and the expected output.
 */
class VimTest {

    // ------------------------------------------------------------------ harness

    private fun value(marked: String): TextFieldValue {
        val caret = marked.indexOf('|')
        return TextFieldValue(marked.removeRange(caret, caret + 1), TextRange(caret))
    }

    private fun show(value: TextFieldValue): String =
        StringBuilder(value.text).insert(value.selection.start.coerceIn(0, value.text.length), '|').toString()

    /** Types [keys] one at a time and returns everything that came out of the last one. */
    private fun type(
        marked: String,
        keys: String,
        state: Vim.State = Vim.State(),
    ): Vim.Outcome {
        var outcome = Vim.Outcome(state, value(marked))
        for (character in keys) {
            val key = when (character) {
                '' -> Vim.Key.special(Vim.Special.ESCAPE)
                '\n' -> Vim.Key.special(Vim.Special.ENTER)
                else -> Vim.Key.of(character)
            }
            outcome = Vim.handle(outcome.state, outcome.value, key)
        }
        return outcome
    }

    private fun after(marked: String, keys: String): String = show(type(marked, keys).value)

    private val ESC = "\u001b"

    // ------------------------------------------------------------------ motions

    @Test
    fun `hjkl move by one and stay inside the line`() {
        assertEquals("ab|cd", after("abc|d", "h"))
        assertEquals("abc|d", after("ab|cd", "l"))
        // `l` does not run past the end of the line onto the next one.
        assertEquals("abcd|\nefgh", after("abc|d\nefgh", "lll"))
        assertEquals("abcd\nef|gh", after("ab|cd\nefgh", "j"))
        assertEquals("ab|cd\nefgh", after("abcd\nef|gh", "k"))
    }

    @Test
    fun `a count multiplies a motion`() {
        assertEquals("abcde|fgh", after("|abcdefgh", "5l"))
        assertEquals("one two |three", after("|one two three", "2w"))
    }

    @Test
    fun `w b and e step by words`() {
        assertEquals("one |two three", after("|one two three", "w"))
        assertEquals("one two |three", after("|one two three", "ww"))
        assertEquals("one |two three", after("one two |three", "b"))
        assertEquals("on|e two", after("|one two", "e"))
    }

    @Test
    fun `line motions land where Vim puts them`() {
        assertEquals("|  indented text", after("  indented te|xt", "0"))
        assertEquals("  |indented text", after("  indented te|xt", "^"))
        assertEquals("  indented text|", after("  |indented text", "$"))
    }

    @Test
    fun `gg and G go to the first and last line`() {
        assertEquals("|one\ntwo\nthree", after("one\ntwo\nth|ree", "gg"))
        assertEquals("one\ntwo\n|three", after("o|ne\ntwo\nthree", "G"))
        assertEquals("one\n|two\nthree", after("|one\ntwo\nthree", "2G"))
    }

    @Test
    fun `f and t find a character on the line, and semicolon repeats`() {
        assertEquals("hello, |world", after("|hello, world", "fw"))
        assertEquals("hello,| world", after("|hello, world", "tw"))
        // Each `f.` lands *on* the next full stop, so two of them reach the second one.
        assertEquals("a.b|.c.d", after("|a.b.c.d", "f.f."))
        assertEquals("a.b|.c.d", after("|a.b.c.d", "f.;"))
    }

    @Test
    fun `a find that fails leaves the caret alone`() {
        assertEquals("hel|lo", after("hel|lo", "fz"))
    }

    @Test
    fun `braces move between paragraphs`() {
        val document = "|one\ntwo\n\nthree\nfour\n\nfive"
        assertEquals("one\ntwo\n|\nthree\nfour\n\nfive", after(document, "}"))
        assertEquals("one\ntwo\n\nthree\nfour\n|\nfive", after(document, "}}"))
    }

    // ------------------------------------------------------------------ operators

    @Test
    fun `dw deletes a word and d2w deletes two`() {
        assertEquals("|two three", after("|one two three", "dw"))
        assertEquals("|three", after("|one two three", "d2w"))
        // A count on either side of the operator multiplies.
        assertEquals("|three", after("|one two three", "2dw"))
    }

    @Test
    fun `dd deletes the line and takes it into the register`() {
        assertEquals("one\n|three", after("one\n|two\nthree", "dd"))
        assertEquals("|three", after("|one\ntwo\nthree", "2dd"))
    }

    @Test
    fun `d dollar deletes to the end of the line`() {
        assertEquals("one |", after("one |two three", "d$"))
    }

    @Test
    fun `cw deletes a word and enters insert mode`() {
        val outcome = type("|one two", "cw")
        assertEquals(Vim.Mode.INSERT, outcome.state.mode)
        assertEquals("|two", show(outcome.value))
    }

    @Test
    fun `yy then p duplicates the line, which is the thing everybody does`() {
        // The single most common Vim sequence there is. If the register's linewise flag is wrong,
        // this pastes the text into the middle of the next line instead.
        assertEquals("one\n|one\ntwo", after("|one\ntwo", "yyp"))
    }

    @Test
    fun `P pastes a yanked line above the caret`() {
        assertEquals("|one\none\ntwo", after("|one\ntwo", "yyP"))
    }

    @Test
    fun `a yanked word pastes into the line rather than onto a new one`() {
        assertEquals("one one| two", after("|one two", "ywwP"))
    }

    @Test
    fun `pasting a line below the last line adds the newline it needs`() {
        assertEquals("only\n|only\n", after("|only", "yyp"))
    }

    @Test
    fun `angle brackets indent and outdent whole lines`() {
        assertEquals("  |one\ntwo", after("|one\ntwo", ">>"))
        assertEquals("|one\ntwo", after("  |one\ntwo", "<<"))
    }

    // ------------------------------------------------------------------ single-key edits

    @Test
    fun `x deletes forwards and X backwards, within the line`() {
        assertEquals("|bcd", after("|abcd", "x"))
        assertEquals("|d", after("|abcd", "3x"))
        assertEquals("ab|d", after("abc|d", "X"))
        // Neither runs past the line it is on.
        assertEquals("abc|\ndef", after("abc|\ndef", "x"))
    }

    @Test
    fun `D and C act to the end of the line`() {
        assertEquals("one |", after("one |two", "D"))
        assertEquals(Vim.Mode.INSERT, type("one |two", "C").state.mode)
    }

    @Test
    fun `r replaces exactly one character and stays in normal mode`() {
        val outcome = type("c|at", "rx")
        assertEquals("c|xt", show(outcome.value))
        assertEquals(Vim.Mode.NORMAL, outcome.state.mode)
    }

    @Test
    fun `r waits for its argument rather than acting on nothing`() {
        val outcome = type("c|at", "r")
        assertEquals("r", outcome.state.pending, "the status bar should show that a key is expected")
        assertEquals("c|at", show(outcome.value))
    }

    @Test
    fun `J joins the next line with a single space`() {
        assertEquals("one| two", after("|one\ntwo", "J"))
        // The count is lines, not joins: `2J` joins two lines and `3J` joins three.
        assertEquals("one| two\nthree", after("|one\ntwo\nthree", "2J"))
        assertEquals("one two| three", after("|one\ntwo\nthree", "3J"))
        // Leading whitespace on the joined line is dropped, as Vim does.
        assertEquals("one| two", after("|one\n    two", "J"))
    }

    @Test
    fun `o and O open a line and keep the indentation`() {
        val below = type("  |one", "o")
        assertEquals("  one\n  |", show(below.value))
        assertEquals(Vim.Mode.INSERT, below.state.mode)

        assertEquals("  |\n  one", show(type("  |one", "O").value))
    }

    // ------------------------------------------------------------------ modes

    @Test
    fun `insert mode hands every ordinary key back to the text field`() {
        val entered = type("|abc", "i")
        assertEquals(Vim.Mode.INSERT, entered.state.mode)

        val typed = Vim.handle(entered.state, entered.value, Vim.Key.of('x'))
        assertFalse(typed.consumed, "insert mode must not swallow characters -- that breaks input methods")
    }

    @Test
    fun `Escape returns to normal mode and steps the caret back`() {
        val outcome = type("abc|", "i" + ESC)
        assertEquals(Vim.Mode.NORMAL, outcome.state.mode)
        assertEquals("ab|c", show(outcome.value))
    }

    @Test
    fun `a A I position the caret where Vim does`() {
        assertEquals("a|bc", show(type("|abc", "a").value))
        assertEquals("abc|", show(type("|abc", "A").value))
        assertEquals("  |one", show(type("  on|e", "I").value))
    }

    @Test
    fun `visual mode selects and an operator applies to the selection`() {
        val visual = type("|one two", "v")
        assertEquals(Vim.Mode.VISUAL, visual.state.mode)

        // `vw` includes the character the caret lands on, so `vwd` on "one two" leaves "wo".
        // That is Vim's own behaviour and the reason `ve` exists.
        assertEquals("|wo", after("|one two", "vwd"))
        assertEquals("| two", after("|one two", "ved"))
        assertEquals("|", after("|one\ntwo", "Vjd"))
    }

    @Test
    fun `visual line mode yanks whole lines`() {
        val outcome = type("|one\ntwo", "Vy")
        assertTrue(outcome.state.register.linewise, "V yanks lines, so p must paste a line")
        assertEquals(Vim.Mode.NORMAL, outcome.state.mode)
    }

    @Test
    fun `Escape leaves visual mode without changing the text`() {
        val outcome = type("|one two", "vw" + ESC)
        assertEquals(Vim.Mode.NORMAL, outcome.state.mode)
        assertEquals("one two", outcome.value.text)
    }

    // ------------------------------------------------------------------ history

    @Test
    fun `u undoes a change and Ctrl-R redoes it`() {
        val deleted = type("|one two", "dw")
        assertEquals("|two", show(deleted.value))

        val undone = Vim.handle(deleted.state, deleted.value, Vim.Key.of('u'))
        assertEquals("|one two", show(undone.value))

        val redone = Vim.handle(undone.state, undone.value, Vim.Key.control('r'))
        assertEquals("|two", show(redone.value))
    }

    @Test
    fun `undo at the beginning of history says so rather than doing nothing silently`() {
        val outcome = type("|text", "u")
        assertEquals("Already at oldest change", outcome.state.message)
    }

    @Test
    fun `a new change clears the redo stack`() {
        val deleted = type("|one two three", "dw")
        val undone = Vim.handle(deleted.state, deleted.value, Vim.Key.of('u'))
        val changed = Vim.handle(undone.state, undone.value, Vim.Key.of('x'))
        assertTrue(changed.state.redo.isEmpty(), "redoing past a new change would rewrite history")
    }

    // ------------------------------------------------------------------ pending state

    @Test
    fun `an incomplete command is remembered and shown`() {
        assertEquals("d", type("|one two", "d").state.pending)
        assertEquals("2d", type("|one two", "2d").state.pending)
        assertEquals("", type("|one two", "dw").state.pending, "a completed command clears it")
    }

    @Test
    fun `a key that means nothing clears the pending sequence`() {
        val outcome = type("|one", "dQ")
        assertEquals("", outcome.state.pending)
        assertEquals("one", outcome.value.text, "an unknown command must not change the document")
    }

    // ------------------------------------------------------------------ the command line

    @Test
    fun `colon w saves and colon q closes`() {
        assertEquals(Vim.Effect.Save, type("|x", ":w\n").effect)
        assertEquals(Vim.Effect.Close, type("|x", ":q\n").effect)
        assertEquals(Vim.Effect.SaveAndClose, type("|x", ":wq\n").effect)
    }

    @Test
    fun `a colon command is shown while it is being typed`() {
        assertEquals(":wq", type("|x", ":wq").state.display)
    }

    @Test
    fun `backspacing out of an empty command line closes it`() {
        var outcome = type("|x", ":")
        outcome = Vim.handle(outcome.state, outcome.value, Vim.Key.special(Vim.Special.BACKSPACE))
        assertNull(outcome.state.commandLine)
    }

    @Test
    fun `a colon number goes to that line`() {
        assertEquals("one\ntwo\n|three", after("|one\ntwo\nthree", ":3\n"))
    }

    @Test
    fun `slash hands the query to the editor's own search`() {
        val outcome = type("|text", "/limit\n")
        assertEquals(Vim.Effect.Find("limit", forward = true), outcome.effect)
    }

    @Test
    fun `an unknown colon command reports itself`() {
        val effect = type("|x", ":frobnicate\n").effect
        assertTrue(effect is Vim.Effect.Report && effect.text.contains("frobnicate"))
    }

    @Test
    fun `n and N step through the matches the editor found`() {
        assertEquals(Vim.Effect.StepMatch(forward = true), type("|x", "n").effect)
        assertEquals(Vim.Effect.StepMatch(forward = false), type("|x", "N").effect)
    }

    // ------------------------------------------------------------------ edges

    @Test
    fun `every command survives an empty document`() {
        val commands = listOf("x", "dd", "dw", "yy", "p", "J", "o", "O", "A", "$", "G", "gg", "w", "b", "e")
        for (keys in commands) {
            val outcome = type("|", keys)
            assertTrue(
                outcome.value.selection.start in 0..outcome.value.text.length,
                "'$keys' put the caret at ${outcome.value.selection.start} in an empty document",
            )
        }
    }

    @Test
    fun `Korean text moves by words like any other`() {
        // Hangul syllables are letters, so word motions treat them as words rather than as
        // punctuation -- which is what would happen with an ASCII-only word test.
        assertEquals("한국어 |문서 입니다", after("|한국어 문서 입니다", "w"))
        assertEquals("|문서 입니다", after("|한국어 문서 입니다", "dw"))
    }

    @Test
    fun `a count is capped rather than looping forever`() {
        val outcome = type("|abc", "99999999999999l")
        assertEquals("abc|", show(outcome.value))
    }
}
