package dev.starfect.quill.editing

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Undo and redo for one document.
 *
 * Quill had neither outside Vim mode. Compose's text field carries a small undo stack of its own,
 * but it belongs to the composable rather than to the document — switching tabs disposes it, so the
 * history of what you were writing was thrown away by looking at something else and coming back.
 * There was no redo at all.
 *
 * ## Grouping is the whole feature
 *
 * A stack with one entry per keystroke is not undo, it is a slow rewind: undoing a sentence takes
 * forty presses. Every editor worth using coalesces, and they all coalesce on roughly the same
 * three rules, which are the ones below:
 *
 *  - **Time.** Edits more than [COALESCE_MILLIS] apart are separate, because a pause is where a
 *    writer's intent changed even when the characters did not.
 *  - **Direction.** Typing and deleting never merge. Backspacing over a word you just typed is an
 *    undo the writer is performing by hand, and folding the two together makes one press of Ctrl+Z
 *    put back something they deliberately removed.
 *  - **Word boundaries.** Whitespace ends a group, so undo steps back a word at a time rather than
 *    a paragraph at a time. This is the rule that makes the feature feel like other editors.
 *
 * Anything that is not typing — a paste, a format, a replace-all — is its own entry regardless, by
 * passing `coalesce = false`. Those are single intentions and undoing one should take one press.
 *
 * ## Why snapshots
 *
 * Each entry holds the whole text rather than a diff. A Markdown document is measured in kilobytes
 * and the bound below is two hundred entries, so the worst case is a few megabytes of strings that
 * are mostly shared structure — against a diff-and-patch implementation whose bugs corrupt the
 * user's document rather than merely costing memory. It is the wrong trade to make cleverly.
 */
public class UndoHistory {

    /** One point the document can be returned to. */
    public data class Entry(val text: String, val selection: TextRange)

    private val past = ArrayDeque<Entry>()
    private val future = ArrayDeque<Entry>()

    /** What the document holds right now; the thing undo moves away from and redo moves back to. */
    private var present: Entry? = null

    /** When [present] was last written, for the time rule. */
    private var presentAt = 0L

    /** Whether [present] was produced by deleting, for the direction rule. */
    private var presentWasDeletion = false

    public val canUndo: Boolean get() = past.isNotEmpty()
    public val canRedo: Boolean get() = future.isNotEmpty()

    /**
     * Records that the document now reads [value].
     *
     * @param coalesce false for edits that are one intention regardless of timing — a paste, a
     *   reformat, a replace-all — so that undoing one takes one press.
     */
    public fun record(value: TextFieldValue, now: Long = System.currentTimeMillis(), coalesce: Boolean = true) {
        val previous = present
        if (previous == null) {
            present = Entry(value.text, value.selection)
            presentAt = now
            return
        }
        if (previous.text == value.text) {
            // Moving the caret is not an edit. Recording it would fill the history with entries
            // that undo to the same text and look, from the outside, like Ctrl+Z doing nothing.
            present = previous.copy(selection = value.selection)
            return
        }

        val deletion = value.text.length < previous.text.length
        if (coalesce && shouldMerge(previous.text, value.text, deletion, now)) {
            present = Entry(value.text, value.selection)
            presentAt = now
            presentWasDeletion = deletion
            future.clear()
            return
        }

        past.addLast(previous)
        while (past.size > MAX_ENTRIES) past.removeFirst()
        present = Entry(value.text, value.selection)
        // Whitespace *closes* a group rather than opening one. Left as an ordinary step it would
        // start a new group and then absorb the word that follows, so "hello world" undid to
        // "hello" in one press instead of stopping at "hello " — the boundary landing one word
        // late, which is exactly where nobody expects it.
        presentAt = if (endsGroup(previous.text, value.text, deletion)) 0L else now
        presentWasDeletion = deletion
        future.clear()
    }

    /** Whether this edit is a boundary the next one must not merge across. */
    private fun endsGroup(before: String, after: String, deletion: Boolean): Boolean {
        val changed = if (deletion) charRemoved(before, after) else charAdded(before, after)
        return changed != null && changed.isWhitespace()
    }

    /** The value to restore, or null when there is nothing to undo. */
    public fun undo(): TextFieldValue? {
        val current = present ?: return null
        val restored = past.removeLastOrNull() ?: return null
        future.addLast(current)
        present = restored
        // The next edit after an undo starts a new group: the writer has just changed direction.
        presentAt = 0L
        return TextFieldValue(restored.text, restored.selection)
    }

    /** The value to restore, or null when there is nothing to redo. */
    public fun redo(): TextFieldValue? {
        val current = present ?: return null
        val restored = future.removeLastOrNull() ?: return null
        past.addLast(current)
        present = restored
        presentAt = 0L
        return TextFieldValue(restored.text, restored.selection)
    }

    /** Forgets everything, for a document being replaced wholesale rather than edited. */
    public fun reset(value: TextFieldValue) {
        past.clear()
        future.clear()
        present = Entry(value.text, value.selection)
        presentAt = 0L
        presentWasDeletion = false
    }

    private fun shouldMerge(before: String, after: String, deletion: Boolean, now: Long): Boolean {
        if (presentAt == 0L) return false
        if (now - presentAt > COALESCE_MILLIS) return false
        if (deletion != presentWasDeletion) return false

        // One character at a time is typing; anything larger arrived some other way and is its own
        // step even when it came through the same path.
        val difference = kotlin.math.abs(after.length - before.length)
        if (difference != 1) return false

        // Whitespace closes a group, so undo steps back a word rather than a paragraph.
        val changed = if (deletion) charRemoved(before, after) else charAdded(before, after)
        return changed != null && !changed.isWhitespace()
    }

    /** The single character [after] has that [before] does not, or null if it is not a single insert. */
    private fun charAdded(before: String, after: String): Char? {
        var index = 0
        while (index < before.length && before[index] == after[index]) index++
        return after.getOrNull(index)
    }

    /** The single character [before] has that [after] does not. */
    private fun charRemoved(before: String, after: String): Char? {
        var index = 0
        while (index < after.length && after[index] == before[index]) index++
        return before.getOrNull(index)
    }

    public companion object {
        /** A pause longer than this starts a new undo step. */
        public const val COALESCE_MILLIS: Long = 700

        /**
         * How far back undo reaches.
         *
         * Two hundred grouped steps is many thousands of keystrokes — far past the point where a
         * writer would reach for the file's history instead — and bounds the memory a long session
         * can accumulate.
         */
        public const val MAX_ENTRIES: Int = 200
    }
}
