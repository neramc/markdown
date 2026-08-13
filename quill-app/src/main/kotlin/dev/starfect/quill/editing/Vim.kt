package dev.starfect.quill.editing

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * A Vim mode that is worth turning on.
 *
 * The half-hearted version of this feature is the one that maps `hjkl` and calls it Vim. What makes
 * Vim worth having is not the keys, it is the *grammar*: an operator takes a motion, a count
 * multiplies it, and `d2w` is a sentence you compose rather than a shortcut you memorise. Anything
 * that implements the keys without the grammar is a worse editor than plain arrow keys, because it
 * has taken away the familiar behaviour and given back a fraction.
 *
 * So this is a parser, not a key map. What it covers:
 *
 * * **Modes** — normal, insert, visual and visual line, with `Esc` always returning to normal.
 * * **Motions** — `h j k l w b e 0 ^ $ gg G { } f F t T ; ,`, each taking a count.
 * * **Operators** — `d c y` over any motion, doubled for the whole line (`dd`, `cc`, `yy`), and
 *   applied to the selection in visual mode.
 * * **Edits** — `x X s S D C J r p P o O a A i I >> <<`.
 * * **Registers** — one unnamed register that knows whether it holds lines or characters, which is
 *   the difference between `p` pasting into the line and pasting a line below it.
 * * **History** — `u` and `Ctrl+R`, snapshotted per change rather than per keystroke.
 * * **Command line** — `:w`, `:q`, `:wq`, `:noh`, and `/` handed to the editor's own find.
 *
 * Insert mode is deliberately *not* implemented: it returns [Outcome.consumed] as false and lets the
 * text field do what it always does, so every platform behaviour a writer relies on — dead keys, an
 * input method for Korean, the system clipboard, autocomplete — keeps working. Reimplementing text
 * entry in order to have a Vim mode would break the editor to add a feature to it.
 */
public object Vim {

    /** Which mode the editor is in. */
    public enum class Mode(public val label: String) {
        NORMAL("NORMAL"),
        INSERT("INSERT"),
        VISUAL("VISUAL"),
        VISUAL_LINE("V-LINE"),
        COMMAND("COMMAND"),
    }

    /** What the unnamed register holds, and how it was taken. */
    public data class Register(val text: String = "", val linewise: Boolean = false)

    /** A key, as the editor hands it over. */
    public data class Key(
        val character: Char? = null,
        val special: Special? = null,
        val ctrl: Boolean = false,
    ) {
        public companion object {
            public fun of(character: Char): Key = Key(character = character)
            public fun control(character: Char): Key = Key(character = character, ctrl = true)
            public fun special(special: Special): Key = Key(special = special)
        }
    }

    /** The keys that have no character. */
    public enum class Special { ESCAPE, ENTER, BACKSPACE, TAB }

    /** Everything the mode remembers between keystrokes. */
    public data class State(
        val mode: Mode = Mode.NORMAL,
        /** Keys typed so far that have not yet made a complete command. */
        val pending: String = "",
        val register: Register = Register(),
        /** Where a visual selection started. */
        val anchor: Int = 0,
        /**
         * Where the caret is in visual mode.
         *
         * Tracked separately because a Compose selection has a start and an end but no notion of
         * which one you are moving: after `vw` the range is (0, 4) whichever direction it grew in,
         * and an operator that read `start` would act on one character instead of the word.
         */
        val visualCaret: Int = 0,
        /** Snapshots for `u`, oldest first. */
        val undo: List<TextFieldValue> = emptyList(),
        val redo: List<TextFieldValue> = emptyList(),
        /** The last `f`/`t` search, so `;` and `,` can repeat it. */
        val lastFind: Pair<Char, Char>? = null,
        /** What has been typed after `:`, or null when the command line is closed. */
        val commandLine: String? = null,
        /** What to show in the status bar: the pending keys, or a message. */
        val message: String? = null,
    ) {
        /** What the status bar shows: the mode, then whatever the mode is in the middle of. */
        public val display: String
            get() = when {
                commandLine != null -> ":$commandLine"
                message != null -> "${mode.label}  $message"
                pending.isNotEmpty() -> "${mode.label}  $pending"
                else -> mode.label
            }
    }

    /** Something the editor has to do that is not a text change. */
    public sealed interface Effect {
        public data object Save : Effect
        public data object Close : Effect
        public data object SaveAndClose : Effect
        public data object ClearSearch : Effect
        public data class Find(val query: String, val forward: Boolean) : Effect
        public data class StepMatch(val forward: Boolean) : Effect
        public data class Report(val text: String) : Effect
    }

    /** What one keystroke produced. */
    public data class Outcome(
        val state: State,
        val value: TextFieldValue,
        /** False means "let the text field handle this key", which is how insert mode works. */
        val consumed: Boolean = true,
        val effect: Effect? = null,
    )

    /**
     * Where the caret is, according to the mode.
     *
     * Normal mode reads it from the value, so clicking in the text moves the Vim caret too; visual
     * mode reads the state, because the value's selection cannot say which end is being moved.
     */
    private fun caretOf(state: State, value: TextFieldValue): Int =
        if (state.mode == Mode.VISUAL || state.mode == Mode.VISUAL_LINE) {
            state.visualCaret.coerceIn(0, value.text.length)
        } else {
            value.selection.start.coerceIn(0, value.text.length)
        }

    /** How many snapshots `u` can walk back through. */
    private const val HISTORY_DEPTH = 200

    // ------------------------------------------------------------------ entry point

    /** Handles one keystroke. */
    public fun handle(state: State, value: TextFieldValue, key: Key): Outcome {
        if (state.commandLine != null) return commandLine(state, value, key)

        return when (state.mode) {
            Mode.INSERT -> insert(state, value, key)
            Mode.NORMAL, Mode.VISUAL, Mode.VISUAL_LINE -> normal(state, value, key)
            Mode.COMMAND -> commandLine(state, value, key)
        }
    }

    /**
     * Insert mode: everything except Escape belongs to the text field.
     *
     * This is what keeps an input method, dead keys and the clipboard working. A Vim mode that
     * intercepted every character here would be a Vim mode you could not write Korean in.
     */
    private fun insert(state: State, value: TextFieldValue, key: Key): Outcome {
        if (key.special != Special.ESCAPE && !(key.ctrl && key.character == '[')) {
            return Outcome(state, value, consumed = false)
        }
        // Vim steps the caret back on leaving insert, so the character just typed is under it.
        val caret = (value.selection.start - 1).coerceAtLeast(lineStart(value.text, value.selection.start))
        return Outcome(
            state.copy(mode = Mode.NORMAL, pending = "", message = null),
            value.copy(selection = TextRange(caret)),
        )
    }

    // ------------------------------------------------------------------ normal and visual

    private fun normal(state: State, value: TextFieldValue, key: Key): Outcome {
        if (key.special == Special.ESCAPE || (key.ctrl && key.character == '[')) {
            return Outcome(
                state.copy(mode = Mode.NORMAL, pending = "", message = null),
                value.copy(selection = TextRange(value.selection.start)),
            )
        }

        // Ctrl+R redoes, and is the one control key the grammar uses.
        if (key.ctrl && key.character?.lowercaseChar() == 'r') {
            return redo(state, value)
        }

        val character = key.character ?: return Outcome(state, value, consumed = false)
        val keys = state.pending + character

        return parse(state, value, keys)
    }

    /**
     * Reads an accumulated key sequence as a command.
     *
     * Returning the sequence in [State.pending] when it is incomplete is what makes counts and
     * operators work: `d` is not a command, `2` is not a command, and `d2w` is — so the first two
     * are remembered and the third executes. The status bar shows the pending keys, which is how a
     * Vim user knows the editor is waiting for them rather than ignoring them.
     */
    private fun parse(state: State, value: TextFieldValue, keys: String): Outcome {
        var index = 0

        // A leading count. `0` is a motion, not a count, so it only counts after another digit.
        var count = 0
        while (index < keys.length && keys[index].isDigit() && !(keys[index] == '0' && count == 0)) {
            count = count * 10 + (keys[index] - '0')
            index++
            if (count > MAX_COUNT) count = MAX_COUNT
        }
        if (index >= keys.length) return pending(state, value, keys)

        val command = keys[index]
        val rest = keys.substring(index + 1)
        val repeat = count.coerceAtLeast(1)

        // In visual mode an operator applies to the selection rather than taking a motion.
        if (state.mode != Mode.NORMAL && command in "dcyxs<>") {
            return visualOperator(state, value, command)
        }

        return when (command) {
            in OPERATORS -> operator(state, value, command, rest, repeat, keys)
            else -> simple(state, value, command, rest, repeat, keys)
        }
    }

    private const val MAX_COUNT = 10_000
    private const val OPERATORS = "dcy><"

    private fun pending(state: State, value: TextFieldValue, keys: String) =
        Outcome(state.copy(pending = keys, message = null), value)

    // ------------------------------------------------------------------ operators

    /**
     * `d`, `c`, `y`, `>` and `<`, each over a motion or doubled for the whole line.
     */
    private fun operator(
        state: State,
        value: TextFieldValue,
        operator: Char,
        rest: String,
        count: Int,
        keys: String,
    ): Outcome {
        if (rest.isEmpty()) return pending(state, value, keys)

        // A second count between the operator and the motion multiplies: `2d3w` is six words.
        var index = 0
        var motionCount = 0
        while (index < rest.length && rest[index].isDigit() && !(rest[index] == '0' && motionCount == 0)) {
            motionCount = motionCount * 10 + (rest[index] - '0')
            index++
        }
        if (index >= rest.length) return pending(state, value, keys)

        val total = count * motionCount.coerceAtLeast(1)
        val motionKeys = rest.substring(index)

        // The doubled form takes whole lines: `dd`, `yy`, `cc`, `>>`.
        if (motionKeys.length == 1 && motionKeys[0] == operator) {
            return lineOperator(state, value, operator, total)
        }

        // A key that is not a motion at all ends the command rather than being remembered: `dQ`
        // means nothing, and keeping it pending would make the next keystroke part of a command
        // the writer abandoned.
        val range = motion(value, motionKeys, total, state)
            ?: return Outcome(state.copy(pending = "", message = null), value)
        if (range.needsMoreInput) return pending(state, value, keys)

        val caret = caretOf(state, value)
        val from = minOf(caret, range.target).coerceIn(0, value.text.length)
        val to = maxOf(caret, range.target).coerceIn(0, value.text.length)
        // An exclusive motion stops before its target; `e` and `f` include it.
        val end = if (range.inclusive) (to + 1).coerceAtMost(value.text.length) else to

        return applyOperator(state, value, operator, from, end, linewise = range.linewise)
    }

    /** The doubled form: whole lines, count of them. */
    private fun lineOperator(state: State, value: TextFieldValue, operator: Char, count: Int): Outcome {
        val text = value.text
        val start = lineStart(text, value.selection.start)
        var end = start
        repeat(count) {
            val lineEnd = lineEnd(text, end)
            end = if (lineEnd < text.length) lineEnd + 1 else lineEnd
        }
        return applyOperator(state, value, operator, start, end, linewise = true)
    }

    private fun applyOperator(
        state: State,
        value: TextFieldValue,
        operator: Char,
        from: Int,
        to: Int,
        linewise: Boolean,
    ): Outcome {
        val text = value.text
        val taken = text.substring(from.coerceIn(0, text.length), to.coerceIn(from, text.length))

        return when (operator) {
            'y' -> Outcome(
                state.copy(
                    pending = "",
                    mode = Mode.NORMAL,
                    register = Register(taken, linewise),
                    message = null,
                ),
                value.copy(selection = TextRange(from)),
            )

            'd', 'c' -> {
                val remaining = text.removeRange(from, to)
                val next = TextFieldValue(remaining, TextRange(from.coerceIn(0, remaining.length)))
                Outcome(
                    state.copy(
                        pending = "",
                        mode = if (operator == 'c') Mode.INSERT else Mode.NORMAL,
                        register = Register(taken, linewise),
                        message = null,
                    ).remember(value),
                    next,
                )
            }

            '>', '<' -> indent(state, value, from, to, out = operator == '<')

            else -> Outcome(state.copy(pending = ""), value)
        }
    }

    /** `>>` and `<<`: shift every line in the range by one level. */
    private fun indent(state: State, value: TextFieldValue, from: Int, to: Int, out: Boolean): Outcome {
        val text = value.text
        val start = lineStart(text, from)
        val end = lineEnd(text, (to - 1).coerceAtLeast(from))
        val block = text.substring(start, end)

        val shifted = block.split("\n").joinToString("\n") { line ->
            if (out) line.removePrefix(INDENT).ifEmpty { line.trimStart() } else INDENT + line
        }

        val updated = text.replaceRange(start, end, shifted)
        return Outcome(
            state.copy(pending = "", mode = Mode.NORMAL, message = null).remember(value),
            TextFieldValue(updated, TextRange(firstNonBlank(updated, start))),
        )
    }

    private const val INDENT = "  "

    /** An operator typed in visual mode, which acts on the selection. */
    private fun visualOperator(state: State, value: TextFieldValue, operator: Char): Outcome {
        val (from, to) = visualRange(state, value)
        val linewise = state.mode == Mode.VISUAL_LINE

        return when (operator) {
            'x' -> applyOperator(state.copy(mode = Mode.NORMAL), value, 'd', from, to, linewise)
            's' -> applyOperator(state.copy(mode = Mode.NORMAL), value, 'c', from, to, linewise)
            else -> applyOperator(state.copy(mode = Mode.NORMAL), value, operator, from, to, linewise)
        }
    }

    /** The selection a visual mode describes, as offsets into the text. */
    private fun visualRange(state: State, value: TextFieldValue): Pair<Int, Int> {
        val text = value.text
        val caret = caretOf(state, value)
        val from = minOf(state.anchor, caret).coerceIn(0, text.length)
        val to = maxOf(state.anchor, caret).coerceIn(0, text.length)

        return if (state.mode == Mode.VISUAL_LINE) {
            val start = lineStart(text, from)
            val end = lineEnd(text, to).let { if (it < text.length) it + 1 else it }
            start to end
        } else {
            from to (to + 1).coerceAtMost(text.length)
        }
    }

    // ------------------------------------------------------------------ single commands

    private fun simple(
        state: State,
        value: TextFieldValue,
        command: Char,
        rest: String,
        count: Int,
        keys: String,
    ): Outcome {
        val text = value.text
        val caret = caretOf(state, value)

        // Anything that is a motion moves the caret, and extends the selection in visual mode.
        motion(value, command + rest, count, state)?.let { range ->
            if (range.needsMoreInput) return pending(state, value, keys)
            val target = range.target.coerceIn(0, text.length)
            val selection = if (state.mode == Mode.NORMAL) {
                TextRange(target)
            } else {
                // Visual selection runs from the anchor to the caret, inclusive of the character
                // under it -- which is what makes `vw` select the word rather than stop one short.
                TextRange(state.anchor, if (target >= state.anchor) (target + 1).coerceAtMost(text.length) else target)
            }
            val nextFind = if (command in "fFtT" && rest.isNotEmpty()) command to rest[0] else state.lastFind
            return Outcome(
                state.copy(pending = "", message = null, lastFind = nextFind, visualCaret = target),
                value.copy(selection = selection),
            )
        }

        return when (command) {
            // --- entering insert mode
            'i' -> enterInsert(state, value, caret)
            'a' -> enterInsert(state, value, (caret + 1).coerceAtMost(lineEnd(text, caret)))
            'I' -> enterInsert(state, value, firstNonBlank(text, lineStart(text, caret)))
            'A' -> enterInsert(state, value, lineEnd(text, caret))

            'o' -> openLine(state, value, below = true)
            'O' -> openLine(state, value, below = false)

            // --- deleting
            'x' -> {
                val end = (caret + count).coerceAtMost(lineEnd(text, caret))
                if (end <= caret) return Outcome(state.copy(pending = ""), value)
                applyOperator(state, value, 'd', caret, end, linewise = false)
            }

            'X' -> {
                val start = (caret - count).coerceAtLeast(lineStart(text, caret))
                if (start >= caret) return Outcome(state.copy(pending = ""), value)
                applyOperator(state, value, 'd', start, caret, linewise = false)
            }

            'D' -> applyOperator(state, value, 'd', caret, lineEnd(text, caret), linewise = false)
            'C' -> applyOperator(state, value, 'c', caret, lineEnd(text, caret), linewise = false)
            's' -> applyOperator(state, value, 'c', caret, (caret + count).coerceAtMost(lineEnd(text, caret)), false)
            'S' -> lineOperator(state, value, 'c', count).let { outcome ->
                // `S` clears the line but keeps it, so the writer types into an empty line rather
                // than into the line below.
                val cleared = outcome.value.text
                val at = outcome.value.selection.start
                Outcome(outcome.state, TextFieldValue(cleared.substring(0, at) + "\n" + cleared.substring(at), TextRange(at)))
            }

            // --- replacing and joining
            'r' -> {
                if (rest.isEmpty()) return pending(state, value, keys)
                val end = (caret + 1).coerceAtMost(text.length)
                if (end <= caret) return Outcome(state.copy(pending = ""), value)
                Outcome(
                    state.copy(pending = "", message = null).remember(value),
                    TextFieldValue(text.replaceRange(caret, end, rest[0].toString()), TextRange(caret)),
                )
            }

            'J' -> join(state, value, count)

            // --- pasting
            'p' -> paste(state, value, after = true, count = count)
            'P' -> paste(state, value, after = false, count = count)

            // --- history
            'u' -> undo(state, value)

            // --- modes
            'v' -> Outcome(
                state.copy(mode = Mode.VISUAL, anchor = caret, visualCaret = caret, pending = "", message = null),
                value.copy(selection = TextRange(caret, (caret + 1).coerceAtMost(text.length))),
            )

            'V' -> Outcome(
                state.copy(mode = Mode.VISUAL_LINE, anchor = caret, visualCaret = caret, pending = "", message = null),
                value.copy(selection = TextRange(lineStart(text, caret), lineEnd(text, caret))),
            )

            // --- the command line and search
            ':' -> Outcome(state.copy(commandLine = "", pending = "", message = null), value)
            '/' -> Outcome(state.copy(commandLine = "/", pending = "", message = null), value)
            'n' -> Outcome(state.copy(pending = ""), value, effect = Effect.StepMatch(forward = true))
            'N' -> Outcome(state.copy(pending = ""), value, effect = Effect.StepMatch(forward = false))

            // An unknown key clears the pending sequence rather than accumulating rubbish.
            else -> Outcome(state.copy(pending = "", message = null), value)
        }
    }

    private fun enterInsert(state: State, value: TextFieldValue, at: Int): Outcome = Outcome(
        state.copy(mode = Mode.INSERT, pending = "", message = null).remember(value),
        value.copy(selection = TextRange(at.coerceIn(0, value.text.length))),
    )

    /** `o` and `O`: a new line below or above, with the same indentation. */
    private fun openLine(state: State, value: TextFieldValue, below: Boolean): Outcome {
        val text = value.text
        val caret = value.selection.start
        val start = lineStart(text, caret)
        val indent = text.substring(start, firstNonBlank(text, start))

        return if (below) {
            val end = lineEnd(text, caret)
            val updated = text.substring(0, end) + "\n" + indent + text.substring(end)
            Outcome(
                state.copy(mode = Mode.INSERT, pending = "", message = null).remember(value),
                TextFieldValue(updated, TextRange(end + 1 + indent.length)),
            )
        } else {
            val updated = text.substring(0, start) + indent + "\n" + text.substring(start)
            Outcome(
                state.copy(mode = Mode.INSERT, pending = "", message = null).remember(value),
                TextFieldValue(updated, TextRange(start + indent.length)),
            )
        }
    }

    /** `J`: pull the next line up, with one space between, as Vim does. */
    private fun join(state: State, value: TextFieldValue, count: Int): Outcome {
        var text = value.text
        val caret = value.selection.start
        var landing = caret

        // `J` joins two lines and `3J` joins three, so the count is lines rather than joins.
        repeat((count - 1).coerceAtLeast(1)) {
            val end = lineEnd(text, landing)
            if (end >= text.length) return@repeat
            val nextStart = firstNonBlank(text, end + 1)
            landing = end
            text = text.substring(0, end) + " " + text.substring(nextStart)
        }

        return Outcome(
            state.copy(pending = "", message = null).remember(value),
            TextFieldValue(text, TextRange(landing.coerceIn(0, text.length))),
        )
    }

    /**
     * `p` and `P`.
     *
     * The linewise flag is the whole difference: a register taken with `yy` pastes as a new line
     * below the caret, and one taken with `yw` pastes into the middle of the line. Getting this
     * wrong makes `yyp` — the most common thing anybody does in Vim — insert a word.
     */
    private fun paste(state: State, value: TextFieldValue, after: Boolean, count: Int): Outcome {
        val register = state.register
        if (register.text.isEmpty()) return Outcome(state.copy(pending = ""), value)

        val text = value.text
        val caret = value.selection.start.coerceIn(0, text.length)
        val payload = register.text.repeat(count.coerceAtLeast(1))

        return if (register.linewise) {
            val at = if (after) {
                lineEnd(text, caret).let { if (it < text.length) it + 1 else it }
            } else {
                lineStart(text, caret)
            }
            val block = if (payload.endsWith("\n")) payload else payload + "\n"
            // Pasting below the last line, which has no newline of its own, needs one adding first.
            val prefix = if (at == text.length && text.isNotEmpty() && !text.endsWith("\n")) "\n" else ""
            val updated = text.substring(0, at) + prefix + block + text.substring(at)
            Outcome(
                state.copy(pending = "", message = null).remember(value),
                TextFieldValue(updated, TextRange(at + prefix.length)),
            )
        } else {
            val at = if (after) (caret + 1).coerceAtMost(text.length) else caret
            val updated = text.substring(0, at) + payload + text.substring(at)
            Outcome(
                state.copy(pending = "", message = null).remember(value),
                TextFieldValue(updated, TextRange(at + payload.length - 1)),
            )
        }
    }

    // ------------------------------------------------------------------ history

    /** Snapshots the value before a change, so `u` has something to go back to. */
    private fun State.remember(before: TextFieldValue): State = copy(
        undo = (undo + before).takeLast(HISTORY_DEPTH),
        redo = emptyList(),
    )

    private fun undo(state: State, value: TextFieldValue): Outcome {
        val previous = state.undo.lastOrNull()
            ?: return Outcome(state.copy(pending = "", message = "Already at oldest change"), value)

        return Outcome(
            state.copy(
                pending = "",
                message = null,
                undo = state.undo.dropLast(1),
                redo = (state.redo + value).takeLast(HISTORY_DEPTH),
            ),
            previous,
        )
    }

    private fun redo(state: State, value: TextFieldValue): Outcome {
        val next = state.redo.lastOrNull()
            ?: return Outcome(state.copy(pending = "", message = "Already at newest change"), value)

        return Outcome(
            state.copy(
                pending = "",
                message = null,
                redo = state.redo.dropLast(1),
                undo = (state.undo + value).takeLast(HISTORY_DEPTH),
            ),
            next,
        )
    }

    // ------------------------------------------------------------------ command line

    private fun commandLine(state: State, value: TextFieldValue, key: Key): Outcome {
        val buffer = state.commandLine.orEmpty()

        return when {
            key.special == Special.ESCAPE ->
                Outcome(state.copy(commandLine = null, message = null), value)

            key.special == Special.BACKSPACE -> {
                if (buffer.isEmpty()) {
                    Outcome(state.copy(commandLine = null), value)
                } else {
                    Outcome(state.copy(commandLine = buffer.dropLast(1)), value)
                }
            }

            key.special == Special.ENTER -> runCommand(state.copy(commandLine = null), value, buffer)

            key.character != null -> Outcome(state.copy(commandLine = buffer + key.character), value)

            else -> Outcome(state, value)
        }
    }

    /** The handful of commands that mean something in an editor with no buffers or windows. */
    private fun runCommand(state: State, value: TextFieldValue, command: String): Outcome {
        // `/pattern` arrives here through the same buffer, marked by its leading slash.
        if (command.startsWith("/")) {
            val query = command.drop(1)
            return Outcome(
                state,
                value,
                effect = if (query.isEmpty()) null else Effect.Find(query, forward = true),
            )
        }

        return when (val trimmed = command.trim()) {
            "w" -> Outcome(state, value, effect = Effect.Save)
            "q" -> Outcome(state, value, effect = Effect.Close)
            "wq", "x" -> Outcome(state, value, effect = Effect.SaveAndClose)
            "noh", "nohl", "nohlsearch" -> Outcome(state, value, effect = Effect.ClearSearch)
            "" -> Outcome(state, value)
            else -> {
                // `:42` goes to a line, which is the other thing people type into a colon prompt.
                val line = trimmed.toIntOrNull()
                if (line != null) {
                    Outcome(state, value.copy(selection = TextRange(offsetOfLine(value.text, line - 1))))
                } else {
                    Outcome(state, value, effect = Effect.Report("Not an editor command: $trimmed"))
                }
            }
        }
    }

    // ------------------------------------------------------------------ motions

    /** Where a motion lands, and how an operator should treat it. */
    private data class Motion(
        val target: Int,
        /** True when the operator should include the character under the target. */
        val inclusive: Boolean = false,
        /** True when the motion is about whole lines. */
        val linewise: Boolean = false,
        /** True when the motion needs another key — `f` waiting for its target. */
        val needsMoreInput: Boolean = false,
    )

    /** Resolves a motion, or null when [keys] is not one. */
    private fun motion(value: TextFieldValue, keys: String, count: Int, state: State): Motion? {
        if (keys.isEmpty()) return null
        val text = value.text
        val caret = value.selection.start.coerceIn(0, text.length)
        val command = keys[0]
        val argument = keys.getOrNull(1)

        return when (command) {
            'h' -> Motion((caret - count).coerceAtLeast(lineStart(text, caret)))
            'l' -> Motion((caret + count).coerceAtMost(lineEnd(text, caret)))
            'j' -> Motion(verticalMove(text, caret, count), linewise = true)
            'k' -> Motion(verticalMove(text, caret, -count), linewise = true)

            '0' -> Motion(lineStart(text, caret))
            '^' -> Motion(firstNonBlank(text, lineStart(text, caret)))
            '$' -> Motion(lineEnd(text, verticalMove(text, caret, count - 1)))

            'w' -> Motion(wordForward(text, caret, count))
            'b' -> Motion(wordBackward(text, caret, count))
            'e' -> Motion(wordEnd(text, caret, count), inclusive = true)

            '{' -> Motion(paragraph(text, caret, count, forward = false), linewise = true)
            '}' -> Motion(paragraph(text, caret, count, forward = true), linewise = true)

            'G' -> Motion(
                if (count > 1 || keys.length > 1) offsetOfLine(text, count - 1) else lineStart(text, text.length),
                linewise = true,
            )

            'g' -> when (argument) {
                null -> Motion(caret, needsMoreInput = true)
                'g' -> Motion(offsetOfLine(text, count - 1), linewise = true)
                else -> null
            }

            in "fFtT" -> when (argument) {
                null -> Motion(caret, needsMoreInput = true)
                else -> findChar(text, caret, command, argument, count)?.let {
                    Motion(it, inclusive = command == 'f' || command == 't')
                } ?: Motion(caret)
            }

            ';', ',' -> {
                val (last, target) = state.lastFind ?: return Motion(caret)
                val direction = if (command == ';') last else reverse(last)
                findChar(text, caret, direction, target, count)?.let {
                    Motion(it, inclusive = direction == 'f' || direction == 't')
                } ?: Motion(caret)
            }

            else -> null
        }
    }

    private fun reverse(command: Char): Char = when (command) {
        'f' -> 'F'
        'F' -> 'f'
        't' -> 'T'
        else -> 't'
    }

    /** `f`, `F`, `t` and `T`, within the caret's own line as Vim does. */
    private fun findChar(text: String, caret: Int, command: Char, target: Char, count: Int): Int? {
        val start = lineStart(text, caret)
        val end = lineEnd(text, caret)
        var position = caret

        repeat(count.coerceAtLeast(1)) {
            position = when (command) {
                'f', 't' -> {
                    // `t` stops before its target, so a repeat has to step past the one it is on.
                    val from = if (command == 't') position + 2 else position + 1
                    text.indexOf(target, from.coerceAtMost(end)).takeIf { it in 0..<end } ?: return null
                }
                else -> {
                    val from = if (command == 'T') position - 2 else position - 1
                    text.lastIndexOf(target, from.coerceAtLeast(-1)).takeIf { it >= start } ?: return null
                }
            }
        }

        return when (command) {
            't' -> position - 1
            'T' -> position + 1
            else -> position
        }
    }

    // ------------------------------------------------------------------ text utilities

    private fun lineStart(text: String, offset: Int): Int {
        val at = offset.coerceIn(0, text.length)
        if (at == 0) return 0
        val found = text.lastIndexOf('\n', at - 1)
        return if (found < 0) 0 else found + 1
    }

    private fun lineEnd(text: String, offset: Int): Int {
        val found = text.indexOf('\n', offset.coerceIn(0, text.length))
        return if (found < 0) text.length else found
    }

    private fun firstNonBlank(text: String, from: Int): Int {
        var index = from.coerceIn(0, text.length)
        while (index < text.length && text[index] != '\n' && text[index].isWhitespace()) index++
        return index
    }

    /** The offset at the start of a zero-based line, clamped to the document. */
    private fun offsetOfLine(text: String, line: Int): Int {
        if (line <= 0) return 0
        var index = 0
        var seen = 0
        while (seen < line) {
            val next = text.indexOf('\n', index)
            if (next < 0) return lineStart(text, text.length)
            index = next + 1
            seen++
        }
        return index
    }

    /** `j` and `k`: keep the column where possible, as Vim does. */
    private fun verticalMove(text: String, caret: Int, lines: Int): Int {
        if (lines == 0) return caret
        val column = caret - lineStart(text, caret)
        var start = lineStart(text, caret)

        repeat(kotlin.math.abs(lines)) {
            start = if (lines > 0) {
                val end = lineEnd(text, start)
                if (end >= text.length) return@repeat
                end + 1
            } else {
                if (start == 0) return@repeat
                lineStart(text, start - 1)
            }
        }

        return (start + column).coerceAtMost(lineEnd(text, start))
    }

    private fun isWordCharacter(character: Char) = character.isLetterOrDigit() || character == '_'

    private fun wordForward(text: String, caret: Int, count: Int): Int {
        var index = caret.coerceIn(0, text.length)
        repeat(count.coerceAtLeast(1)) {
            if (index >= text.length) return text.length
            val startedOnWord = isWordCharacter(text[index])
            // Step off whatever kind of run the caret is in...
            while (index < text.length && !text[index].isWhitespace() &&
                isWordCharacter(text[index]) == startedOnWord
            ) {
                index++
            }
            // ...then over the whitespace that follows it.
            while (index < text.length && text[index].isWhitespace()) index++
        }
        return index
    }

    private fun wordBackward(text: String, caret: Int, count: Int): Int {
        var index = caret.coerceIn(0, text.length)
        repeat(count.coerceAtLeast(1)) {
            if (index == 0) return 0
            index--
            while (index > 0 && text[index].isWhitespace()) index--
            if (index == 0) return 0
            val kind = isWordCharacter(text[index])
            while (index > 0 && !text[index - 1].isWhitespace() && isWordCharacter(text[index - 1]) == kind) {
                index--
            }
        }
        return index
    }

    private fun wordEnd(text: String, caret: Int, count: Int): Int {
        var index = caret.coerceIn(0, text.length)
        repeat(count.coerceAtLeast(1)) {
            if (index >= text.length - 1) return (text.length - 1).coerceAtLeast(0)
            index++
            while (index < text.length && text[index].isWhitespace()) index++
            if (index >= text.length) return text.length - 1
            val kind = isWordCharacter(text[index])
            while (index + 1 < text.length && !text[index + 1].isWhitespace() &&
                isWordCharacter(text[index + 1]) == kind
            ) {
                index++
            }
        }
        return index
    }

    /** `{` and `}`: to the blank line that separates one paragraph from the next. */
    private fun paragraph(text: String, caret: Int, count: Int, forward: Boolean): Int {
        var index = caret.coerceIn(0, text.length)

        repeat(count.coerceAtLeast(1)) {
            if (forward) {
                var line = lineEnd(text, index)
                while (line < text.length) {
                    val nextStart = line + 1
                    val nextEnd = lineEnd(text, nextStart)
                    if (nextStart >= nextEnd) return@repeat run { index = nextStart }
                    line = nextEnd
                }
                index = text.length
            } else {
                var start = lineStart(text, index)
                while (start > 0) {
                    val previousStart = lineStart(text, start - 1)
                    if (previousStart == start - 1) return@repeat run { index = previousStart }
                    start = previousStart
                }
                index = 0
            }
        }

        return index.coerceIn(0, text.length)
    }
}
