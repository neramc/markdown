package dev.starfect.quill.editing

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Closing what you open, and wrapping what you select.
 *
 * Two behaviours every editor has and whose absence is felt constantly: typing `(` gives you `()`
 * with the caret between them, and selecting a word before typing `` ` `` gives you `` `word` ``
 * rather than a backtick where the word used to be.
 *
 * Written as a function of the text before and after the keystroke rather than as a key handler.
 * Compose hands over a finished [TextFieldValue] — the selection has already been replaced, the
 * character has already been inserted — so the only honest way to know what happened is to compare.
 * That also means this works identically for a keystroke, a paste of one character, and an input
 * method's commit, without knowing which it was.
 *
 * **`*` and `_` wrap but do not auto-close, and that asymmetry is the whole Markdown-specific part.**
 * Selecting a word and typing `*` to get `*word*` is one of the most useful things in a Markdown
 * editor. Auto-closing `*` is one of the most annoying: `* ` at the start of a line is a bullet, and
 * an editor that turns it into `**` has broken the most common thing anybody types.
 */
public object AutoPairs {

    /** Pairs that close themselves as you type. */
    private val CLOSING: Map<Char, Char> = mapOf(
        '(' to ')',
        '[' to ']',
        '{' to '}',
        '`' to '`',
        '"' to '"',
    )

    /** Pairs that wrap a selection. A superset: see the note on `*` above. */
    private val SURROUNDING: Map<Char, Char> = CLOSING + mapOf(
        '*' to '*',
        '_' to '_',
        '~' to '~',
        '\'' to '\'',
    )

    /** Characters a new pair may be opened in front of. Anything else means "you are mid-word". */
    private fun opensBefore(next: Char?): Boolean =
        next == null || next.isWhitespace() || next in ")]}>,.;:!?\"'`"

    /**
     * The edit to apply, given what the field held before and what it holds now.
     *
     * Returns [after] unchanged when nothing here applies, which is the overwhelmingly common case.
     */
    public fun apply(
        before: TextFieldValue,
        after: TextFieldValue,
        closeBrackets: Boolean,
        surroundSelection: Boolean,
    ): TextFieldValue {
        if (surroundSelection && !before.selection.collapsed) {
            surround(before, after)?.let { return it }
        }
        if (!closeBrackets) return after
        if (!before.selection.collapsed) return after

        typeOver(before, after)?.let { return it }
        close(before, after)?.let { return it }
        deletePair(before, after)?.let { return it }
        return after
    }

    /** Selecting text and typing an opener wraps the selection instead of replacing it. */
    private fun surround(before: TextFieldValue, after: TextFieldValue): TextFieldValue? {
        val typed = insertedCharacter(before, after) ?: return null
        val close = SURROUNDING[typed] ?: return null

        val start = before.selection.min
        val end = before.selection.max
        val selected = before.text.substring(start, end)
        val text = before.text.replaceRange(start, end, "$typed$selected$close")

        // The selection survives, still around the same words, so the wrap can be repeated: a
        // second `*` over `*word*` gives `**word**`, which is how bold gets typed.
        return TextFieldValue(text, TextRange(start + 1, start + 1 + selected.length))
    }

    /** Typing the closing character when it is already there moves over it rather than doubling it. */
    private fun typeOver(before: TextFieldValue, after: TextFieldValue): TextFieldValue? {
        val typed = insertedCharacter(before, after) ?: return null
        if (typed !in CLOSING.values) return null

        val caret = before.selection.start
        if (before.text.getOrNull(caret) != typed) return null

        return TextFieldValue(before.text, TextRange(caret + 1))
    }

    /** Typing an opener inserts the pair, with the caret between them. */
    private fun close(before: TextFieldValue, after: TextFieldValue): TextFieldValue? {
        val typed = insertedCharacter(before, after) ?: return null
        val close = CLOSING[typed] ?: return null

        val caret = before.selection.start
        if (!opensBefore(before.text.getOrNull(caret))) return null

        // A quote right after a word is an apostrophe or the end of a quotation, not the start of
        // one: `don't` must not become `don''t`.
        if (typed == close && before.text.getOrNull(caret - 1)?.isLetterOrDigit() == true) return null

        val text = before.text.replaceRange(caret, caret, "$typed$close")
        return TextFieldValue(text, TextRange(caret + 1))
    }

    /** Deleting an opener takes its empty partner with it. */
    private fun deletePair(before: TextFieldValue, after: TextFieldValue): TextFieldValue? {
        val deleted = deletedIndex(before, after) ?: return null
        val opener = before.text.getOrNull(deleted) ?: return null
        val close = CLOSING[opener] ?: return null
        if (before.text.getOrNull(deleted + 1) != close) return null

        val text = before.text.removeRange(deleted, deleted + 2)
        return TextFieldValue(text, TextRange(deleted))
    }

    /**
     * The one character this edit inserted, or null when it was anything else.
     *
     * "Anything else" covers a paste, a multi-character input method commit, a deletion, and a
     * selection change — all of which must pass through untouched.
     */
    private fun insertedCharacter(before: TextFieldValue, after: TextFieldValue): Char? {
        val start = before.selection.min
        val end = before.selection.max
        if (after.text.length != before.text.length - (end - start) + 1) return null
        if (!after.selection.collapsed || after.selection.start != start + 1) return null

        val head = before.text.take(start)
        val tail = before.text.substring(end)
        if (!after.text.startsWith(head) || !after.text.endsWith(tail)) return null

        return after.text.getOrNull(start)
    }

    /** The index of the single character this edit removed, or null. */
    private fun deletedIndex(before: TextFieldValue, after: TextFieldValue): Int? {
        if (!before.selection.collapsed) return null
        if (after.text.length != before.text.length - 1) return null

        val caret = before.selection.start
        if (caret == 0) return null
        if (!after.selection.collapsed || after.selection.start != caret - 1) return null
        if (after.text != before.text.removeRange(caret - 1, caret)) return null

        return caret - 1
    }
}
