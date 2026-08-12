package dev.starfect.quill.editing

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Text transformations for writing Markdown.
 *
 * Pure functions over `TextFieldValue`: text in, text and a caret out. Nothing here touches the
 * engine, the controller or Compose state, which is what makes every one of them testable against a
 * string rather than against a running window — and this is a body of code where the edge cases
 * (empty selection, selection spanning a marker, caret inside a word, an unbalanced marker) vastly
 * outnumber the happy path.
 *
 * The caret is as much of the result as the text is. An action that produces the right characters
 * and leaves the caret somewhere arbitrary is one the writer has to correct after every use, which
 * is worse than not having it.
 */
public object MarkdownEdits {

    /**
     * Wraps the selection in [marker], or unwraps it if it is already wrapped.
     *
     * Toggling rather than always inserting is what lets one shortcut do both jobs, and it is what
     * the writer expects: pressing bold twice should leave the text as it started.
     *
     * With no selection, the word under the caret is used. That is the common case — you notice a
     * word should be emphasised after typing it, not before — and selecting it first is a step the
     * editor can take on the writer's behalf.
     */
    public fun toggleEmphasis(value: TextFieldValue, marker: String): TextFieldValue {
        val range = value.selection.takeIf { !it.collapsed } ?: wordAt(value.text, value.selection.start)
        val start = range.min
        val end = range.max
        val text = value.text

        val markerLength = marker.length
        val wrappedOutside = start >= markerLength &&
            end + markerLength <= text.length &&
            text.regionMatches(start - markerLength, marker, 0, markerLength) &&
            text.regionMatches(end, marker, 0, markerLength)

        val wrappedInside = end - start >= markerLength * 2 &&
            text.regionMatches(start, marker, 0, markerLength) &&
            text.regionMatches(end - markerLength, marker, 0, markerLength)

        return when {
            // The markers sit just outside the selection: the writer selected the words, not the
            // asterisks. Remove them and keep the same words selected.
            wrappedOutside -> {
                val updated = text.removeRange(end, end + markerLength).removeRange(start - markerLength, start)
                TextFieldValue(updated, TextRange(start - markerLength, end - markerLength))
            }

            // The selection includes the markers.
            wrappedInside -> {
                val updated = text.removeRange(end - markerLength, end)
                    .removeRange(start, start + markerLength)
                TextFieldValue(updated, TextRange(start, end - markerLength * 2))
            }

            else -> {
                val updated = text.substring(0, start) + marker + text.substring(start, end) +
                    marker + text.substring(end)
                TextFieldValue(updated, TextRange(start + markerLength, end + markerLength))
            }
        }
    }

    /**
     * Turns the selection into a link, or inserts an empty one.
     *
     * When the selection looks like a URL it becomes the destination and the caret lands in the
     * label, because that is the half still to be written. Otherwise the selection is the label and
     * the caret lands in the destination. Getting this the wrong way round means every link needs
     * the caret moved by hand.
     */
    public fun insertLink(value: TextFieldValue, url: String? = null): TextFieldValue {
        val range = value.selection
        val selected = if (range.collapsed) "" else value.text.substring(range.min, range.max)
        val text = value.text

        val (label, destination) = when {
            url != null -> selected to url
            looksLikeUrl(selected) -> "" to selected
            else -> selected to ""
        }

        val inserted = "[$label]($destination)"
        val updated = text.substring(0, range.min) + inserted + text.substring(range.max)

        // Caret into whichever half is still empty; select the label when both are filled, so the
        // next keystroke replaces the placeholder rather than appending to it.
        val labelStart = range.min + 1
        val destinationStart = labelStart + label.length + 2
        val caret = when {
            label.isEmpty() -> TextRange(labelStart)
            destination.isEmpty() -> TextRange(destinationStart)
            else -> TextRange(range.min, range.min + inserted.length)
        }
        return TextFieldValue(updated, caret)
    }

    /**
     * Raises or lowers the heading level of every line the selection touches.
     *
     * A line that is not a heading becomes one at level 1 when promoted. Levels are clamped to 1..6,
     * and demoting a level-1 heading removes the marker entirely — which is how a writer un-headings
     * a line without reaching for backspace.
     */
    public fun shiftHeading(value: TextFieldValue, delta: Int): TextFieldValue = editLines(value) { line ->
        val match = HEADING.matchEntire(line)
        val level = match?.groupValues?.get(1)?.length ?: 0
        val body = match?.groupValues?.get(2) ?: line

        when (val target = (level + delta).coerceAtMost(6)) {
            in 1..6 -> "#".repeat(target) + " " + body
            else -> body
        }
    }

    /** Toggles a bullet marker on every line the selection touches. */
    public fun toggleBullet(value: TextFieldValue): TextFieldValue {
        val lines = selectedLines(value)
        val allBulleted = lines.isNotEmpty() && lines.all { BULLET.containsMatchIn(it) || it.isBlank() }
        return editLines(value) { line ->
            when {
                line.isBlank() -> line
                allBulleted -> BULLET.replace(line, "$1")
                else -> {
                    val indent = line.takeWhile { it == ' ' || it == '\t' }
                    "$indent- " + line.removePrefix(indent)
                }
            }
        }
    }

    /** Toggles a task checkbox on every line the selection touches, adding a bullet if needed. */
    public fun toggleTask(value: TextFieldValue): TextFieldValue {
        val lines = selectedLines(value).filter { it.isNotBlank() }
        val allChecked = lines.isNotEmpty() && lines.all { TASK_CHECKED.containsMatchIn(it) }
        val anyTask = lines.any { TASK.containsMatchIn(it) }

        return editLines(value) { line ->
            when {
                line.isBlank() -> line
                // Every one is ticked, so untick them; some are unticked, so tick those.
                allChecked -> TASK_CHECKED.replace(line) { "${it.groupValues[1]}[ ] " }
                anyTask -> TASK.replace(line) { "${it.groupValues[1]}[x] " }
                else -> {
                    val indent = line.takeWhile { it == ' ' || it == '\t' }
                    val body = line.removePrefix(indent).removePrefix("- ")
                    "$indent- [ ] $body"
                }
            }
        }
    }

    /** Toggles a block quote marker on every line the selection touches. */
    public fun toggleQuote(value: TextFieldValue): TextFieldValue {
        val lines = selectedLines(value)
        val allQuoted = lines.isNotEmpty() && lines.all { it.trimStart().startsWith(">") || it.isBlank() }
        return editLines(value) { line ->
            when {
                allQuoted -> line.replaceFirst(Regex("^(\\s*)>\\s?"), "$1")
                line.isBlank() -> line
                else -> {
                    val indent = line.takeWhile { it == ' ' || it == '\t' }
                    "$indent> " + line.removePrefix(indent)
                }
            }
        }
    }

    /**
     * What a newline should insert, given the line the caret is on.
     *
     * Continues a list, task list or block quote; and when the marker's line is otherwise empty,
     * removes it instead — which is how a writer ends a list by pressing Enter twice, without
     * leaving a dangling bullet behind.
     *
     * Returns null when there is nothing clever to do, so the caller can let the ordinary newline
     * through rather than reimplementing it.
     */
    public fun continueBlock(value: TextFieldValue): TextFieldValue? {
        if (!value.selection.collapsed) return null

        val caret = value.selection.start
        val lineStart = value.text.lastIndexOf('\n', (caret - 1).coerceAtLeast(0))
            .let { if (it < 0) 0 else it + 1 }
        val line = value.text.substring(lineStart, caret)

        val match = CONTINUABLE.matchEntire(line) ?: return null
        val prefix = match.groupValues[1]
        val body = match.groupValues[2]

        // An empty marker line means "I am done with this list": clear the line rather than adding
        // another empty item to it.
        if (body.isBlank()) {
            val updated = value.text.removeRange(lineStart, caret)
            return TextFieldValue(updated, TextRange(lineStart))
        }

        // An ordered marker advances; everything else repeats.
        val next = ORDERED.matchEntire(prefix)?.let { ordered ->
            val indent = ordered.groupValues[1]
            val number = ordered.groupValues[2].toLongOrNull()?.plus(1) ?: 1L
            "$indent$number${ordered.groupValues[3]} "
        } ?: prefix

        // A task marker continues as an *unticked* one. Copying the tick would tick a task nobody
        // has done yet.
        val continued = next.replace("[x] ", "[ ] ").replace("[X] ", "[ ] ")
        val updated = value.text.substring(0, caret) + "\n" + continued + value.text.substring(caret)
        return TextFieldValue(updated, TextRange(caret + 1 + continued.length))
    }

    /** Moves the lines the selection touches up or down by one, keeping them selected. */
    public fun moveLines(value: TextFieldValue, delta: Int): TextFieldValue {
        val lines = value.text.split("\n")
        val (first, last) = selectedLineRange(value)

        val target = if (delta < 0) first - 1 else last + 1
        if (target !in lines.indices) return value

        val block = lines.subList(first, last + 1).toList()
        val rest = lines.toMutableList()
        repeat(block.size) { rest.removeAt(first) }
        val insertAt = if (delta < 0) first - 1 else first + 1
        rest.addAll(insertAt, block)

        val updated = rest.joinToString("\n")
        val newStart = offsetOfLine(updated, insertAt)
        val newEnd = newStart + block.sumOf { it.length + 1 } - 1
        return TextFieldValue(updated, TextRange(newStart, newEnd.coerceAtMost(updated.length)))
    }

    /** Duplicates the lines the selection touches, selecting the copy. */
    public fun duplicateLines(value: TextFieldValue): TextFieldValue {
        val lines = value.text.split("\n")
        val (first, last) = selectedLineRange(value)
        val block = lines.subList(first, last + 1)

        val rest = lines.toMutableList()
        rest.addAll(last + 1, block)
        val updated = rest.joinToString("\n")

        val start = offsetOfLine(updated, last + 1)
        val end = start + block.sumOf { it.length + 1 } - 1
        return TextFieldValue(updated, TextRange(start, end.coerceAtMost(updated.length)))
    }

    /**
     * Reflows a GFM table so its pipes line up.
     *
     * Purely cosmetic to a renderer and worth a great deal to whoever has to read the source: a
     * table edited by hand drifts out of alignment after the first cell whose text changes length.
     * Cells are padded to the widest in their column, measured in code points rather than chars so a
     * CJK or emoji cell does not throw the column out.
     *
     * Returns null when the caret is not in something that looks like a table, so the caller can
     * report that rather than silently doing nothing.
     */
    public fun formatTable(value: TextFieldValue): TextFieldValue? {
        val lines = value.text.split("\n")
        val (caretLine, _) = selectedLineRange(value)
        if (caretLine !in lines.indices) return null

        var first = caretLine
        while (first > 0 && isTableRow(lines[first - 1])) first--
        var last = caretLine
        while (last < lines.lastIndex && isTableRow(lines[last + 1])) last++
        if (!isTableRow(lines[caretLine]) || last - first < 1) return null

        val rows = lines.subList(first, last + 1).map(::splitRow)
        val delimiterIndex = rows.indexOfFirst { row -> row.isNotEmpty() && row.all(::isDelimiterCell) }
        val columns = rows.maxOf { it.size }
        val alignments = delimiterIndex.takeIf { it >= 0 }?.let { rows[it].map(::alignmentOf) }.orEmpty()

        val widths = IntArray(columns) { column ->
            rows.withIndex()
                .filter { (index, _) -> index != delimiterIndex }
                .maxOf { (_, row) -> row.getOrNull(column)?.codePointCount().orZero() }
                .coerceAtLeast(3)
        }

        val formatted = rows.mapIndexed { index, row ->
            val cells = (0 until columns).map { column ->
                val width = widths[column]
                if (index == delimiterIndex) {
                    delimiterCell(alignments.getOrNull(column) ?: Alignment.NONE, width)
                } else {
                    pad(row.getOrNull(column).orEmpty(), width, alignments.getOrNull(column) ?: Alignment.NONE)
                }
            }
            cells.joinToString(" | ", prefix = "| ", postfix = " |")
        }

        val updated = (lines.subList(0, first) + formatted + lines.subList(last + 1, lines.size))
            .joinToString("\n")
        val caret = offsetOfLine(updated, caretLine).coerceAtMost(updated.length)
        return TextFieldValue(updated, TextRange(caret))
    }

    /** Column alignment as a GFM delimiter row expresses it. */
    private enum class Alignment { NONE, LEFT, CENTRE, RIGHT }

    // ------------------------------------------------------------------ helpers

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+")
    private val TASK = Regex("^(\\s*(?:[-*+]|\\d+[.)])\\s+)\\[[ xX]]\\s+")
    private val TASK_CHECKED = Regex("^(\\s*(?:[-*+]|\\d+[.)])\\s+)\\[[xX]]\\s+")
    private val ORDERED = Regex("^(\\s*)(\\d+)([.)])\\s+$")

    /** A line that pressing Enter should continue: bullet, ordered item, task or quote. */
    private val CONTINUABLE =
        Regex("^(\\s*(?:[-*+]|\\d+[.)])\\s+(?:\\[[ xX]]\\s+)?|\\s*>\\s?)(.*)$")

    private fun Int?.orZero() = this ?: 0

    private fun String.codePointCount() = codePointCount(0, length)

    private fun looksLikeUrl(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.isNotEmpty() &&
            !trimmed.contains(' ') &&
            (trimmed.startsWith("http://") || trimmed.startsWith("https://") ||
                trimmed.startsWith("mailto:") || trimmed.startsWith("/") || trimmed.startsWith("./"))
    }

    /** The word under [offset], or an empty range when the caret is not in one. */
    private fun wordAt(text: String, offset: Int): TextRange {
        if (text.isEmpty()) return TextRange(offset)
        fun isWord(index: Int) = index in text.indices && (text[index].isLetterOrDigit() || text[index] == '_')

        var start = offset
        while (isWord(start - 1)) start--
        var end = offset
        while (isWord(end)) end++
        return TextRange(start, end)
    }

    private fun selectedLineRange(value: TextFieldValue): Pair<Int, Int> {
        val text = value.text
        val first = text.take(value.selection.min).count { it == '\n' }
        val last = text.take(value.selection.max).count { it == '\n' }
        return first to last
    }

    private fun selectedLines(value: TextFieldValue): List<String> {
        val (first, last) = selectedLineRange(value)
        return value.text.split("\n").subList(first, last + 1)
    }

    private fun offsetOfLine(text: String, line: Int): Int {
        if (line <= 0) return 0
        var offset = 0
        var seen = 0
        while (seen < line) {
            val next = text.indexOf('\n', offset)
            if (next < 0) return text.length
            offset = next + 1
            seen++
        }
        return offset
    }

    /** Applies [transform] to every line the selection touches, keeping those lines selected. */
    private fun editLines(value: TextFieldValue, transform: (String) -> String): TextFieldValue {
        val lines = value.text.split("\n").toMutableList()
        val (first, last) = selectedLineRange(value)
        for (index in first..last) lines[index] = transform(lines[index])

        val updated = lines.joinToString("\n")
        val start = offsetOfLine(updated, first)
        val end = offsetOfLine(updated, last) + lines[last].length
        return TextFieldValue(updated, TextRange(start, end.coerceAtMost(updated.length)))
    }

    private fun isTableRow(line: String): Boolean = line.trim().startsWith("|") && line.trim().length > 1

    private fun splitRow(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split("|").map(String::trim)

    private fun isDelimiterCell(cell: String): Boolean =
        cell.isNotEmpty() && cell.all { it == '-' || it == ':' || it == ' ' } && cell.contains('-')

    private fun alignmentOf(cell: String): Alignment {
        val trimmed = cell.trim()
        val left = trimmed.startsWith(":")
        val right = trimmed.endsWith(":")
        return when {
            left && right -> Alignment.CENTRE
            right -> Alignment.RIGHT
            left -> Alignment.LEFT
            else -> Alignment.NONE
        }
    }

    private fun delimiterCell(alignment: Alignment, width: Int): String = when (alignment) {
        Alignment.LEFT -> ":" + "-".repeat(width - 1)
        Alignment.RIGHT -> "-".repeat(width - 1) + ":"
        Alignment.CENTRE -> ":" + "-".repeat(width - 2) + ":"
        Alignment.NONE -> "-".repeat(width)
    }

    private fun pad(cell: String, width: Int, alignment: Alignment): String {
        val slack = (width - cell.codePointCount()).coerceAtLeast(0)
        return when (alignment) {
            Alignment.RIGHT -> " ".repeat(slack) + cell
            Alignment.CENTRE -> " ".repeat(slack / 2) + cell + " ".repeat(slack - slack / 2)
            else -> cell + " ".repeat(slack)
        }
    }
}
