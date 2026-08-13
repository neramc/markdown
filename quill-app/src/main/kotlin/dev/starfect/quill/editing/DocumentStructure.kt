package dev.starfect.quill.editing

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.starfect.quill.bridge.wire.OutlineEntry

/**
 * Editing a document by its structure rather than by its characters.
 *
 * Everything here is an operation somebody performs on a *document* — tick that box, move that
 * section, keep the contents list current — expressed as a change to the source text. Each is a
 * pure function of the text so it can be tested as one, which matters more here than elsewhere:
 * these are the operations that move large amounts of somebody's writing around, and the failure
 * mode of getting one wrong is losing a paragraph rather than mistyping a character.
 *
 * The recurring problem in all three is the same: relating something on screen to a place in the
 * source. A checkbox in the preview is the *n*-th checkbox; a section in the outline is a heading
 * and everything under it until the next heading of the same rank. Both are counted from the source
 * rather than tracked through the renderer, because a count cannot go stale between a keystroke and
 * a click and a tracked offset can.
 */
public object DocumentStructure {

    // ------------------------------------------------------------------ task lists

    /** A task marker found in the source. */
    private data class Task(val markerStart: Int, val checked: Boolean)

    private val TASK_PATTERN = Regex("""(?m)^(\s*(?:[-*+]|\d+[.)])\s+)\[([ xX])]""")

    /**
     * Ticks or unticks the *n*-th task in the document.
     *
     * The index is the checkbox's position in reading order, which is exactly what the preview
     * knows about the one that was clicked. Counting rather than tracking offsets is deliberate:
     * the preview is always one derivation behind the text, so an offset captured when it rendered
     * may already point somewhere else, while "the fourth checkbox" survives every edit that does
     * not add or remove one.
     *
     * Returns null when there is no such task, which happens when the preview is stale — a click
     * that does nothing is far better than one that ticks the wrong box.
     */
    public fun toggleTask(text: String, index: Int): String? {
        val tasks = TASK_PATTERN.findAll(text).toList()
        val task = tasks.getOrNull(index) ?: return null

        val bracket = task.range.last - 1
        val checked = task.groupValues[2].isNotBlank()
        return text.substring(0, bracket) + (if (checked) " " else "x") + text.substring(bracket + 1)
    }

    /** How many tasks the document has, so a caller can tell a stale preview from a real click. */
    public fun taskCount(text: String): Int = TASK_PATTERN.findAll(text).count()

    // ------------------------------------------------------------------ moving sections

    /**
     * A heading and everything beneath it, as a range of lines.
     *
     * "Beneath it" means until the next heading of the same or higher rank — so moving a `##`
     * carries its `###` subsections with it, which is what somebody dragging a section in an
     * outline means every time.
     */
    public data class Section(val level: Int, val title: String, val firstLine: Int, val lastLine: Int)

    /** The document's sections, in order, derived from the outline the engine already produced. */
    public fun sections(text: String, outline: List<OutlineEntry>): List<Section> {
        if (outline.isEmpty()) return emptyList()
        val lineCount = text.count { it == '\n' } + 1

        return outline.mapIndexed { index, entry ->
            // The section ends where the next heading of the same or higher rank begins.
            val next = outline.drop(index + 1).firstOrNull { it.level <= entry.level }
            val end = next?.let { it.line - 1 } ?: (lineCount - 1)
            Section(entry.level, entry.title, entry.line, end.coerceAtLeast(entry.line))
        }
    }

    /**
     * Moves the section at [from] so that it sits where the section at [to] currently begins.
     *
     * Returns null when the move would be a no-op or is not one that makes sense — moving a section
     * into its own body, most importantly, which would delete it.
     */
    public fun moveSection(text: String, sections: List<Section>, from: Int, to: Int): String? {
        val source = sections.getOrNull(from) ?: return null
        val destination = sections.getOrNull(to) ?: return null
        if (from == to) return null

        // Dragging a section into its own subtree would mean cutting the text out and pasting it
        // back inside the hole it left. Refusing is the only sensible answer.
        if (to > from && destination.firstLine <= source.lastLine) return null

        val lines = text.split("\n")
        val block = lines.subList(
            source.firstLine.coerceIn(0, lines.size),
            (source.lastLine + 1).coerceIn(source.firstLine, lines.size),
        ).toList()
        if (block.isEmpty()) return null

        val remaining = lines.toMutableList()
        repeat(block.size) { remaining.removeAt(source.firstLine.coerceIn(0, remaining.size - 1)) }

        // Dropping *after* the source means every line index past it has already shifted up.
        val anchor = if (destination.firstLine > source.lastLine) {
            destination.firstLine - block.size
        } else {
            destination.firstLine
        }
        remaining.addAll(anchor.coerceIn(0, remaining.size), block)

        val moved = remaining.joinToString("\n")
        return if (moved == text) null else moved
    }

    // ------------------------------------------------------------------ table of contents

    /** The comment pair that marks a generated contents list. */
    public const val TOC_OPEN: String = "<!-- toc -->"
    public const val TOC_CLOSE: String = "<!-- /toc -->"

    /**
     * Rewrites the document's marked table of contents from its headings.
     *
     * A *marked* region rather than "the list after the first heading", because a generated block
     * has to be recognisable to be regenerated: without the markers the only way to update a
     * contents list is to guess which list it is, and guessing wrong rewrites somebody's content.
     * The markers are HTML comments, so they are invisible everywhere the document is rendered.
     *
     * Returns null when the document has no markers — which is how this stays opt-in per document
     * even with the setting on. Somebody who has not asked for a contents list does not get one
     * appearing in their file.
     */
    public fun updateTableOfContents(text: String, outline: List<OutlineEntry>): String? {
        val open = text.indexOf(TOC_OPEN)
        if (open < 0) return null
        val close = text.indexOf(TOC_CLOSE, open + TOC_OPEN.length)
        if (close < 0) return null

        val contents = renderContents(outline)
        val replacement = buildString {
            append(TOC_OPEN)
            append('\n')
            if (contents.isNotEmpty()) {
                append(contents)
                append('\n')
            }
            append(TOC_CLOSE)
        }

        val updated = text.substring(0, open) + replacement + text.substring(close + TOC_CLOSE.length)
        return if (updated == text) null else updated
    }

    /**
     * The contents list itself: one line per heading, indented by depth, linked by anchor.
     *
     * The shallowest heading becomes the top level, so a document whose headings all start at `##`
     * does not produce a list indented under nothing. The document's own title — a lone top-level
     * heading above everything else — is left out, because a contents list that begins with the
     * name of the page you are on is noise.
     */
    public fun renderContents(outline: List<OutlineEntry>): String {
        val entries = outline.filter { it.title.isNotBlank() }
        if (entries.isEmpty()) return ""

        val body = if (entries.size > 1 && entries.first().level < entries[1].level &&
            entries.count { it.level == entries.first().level } == 1
        ) {
            entries.drop(1)
        } else {
            entries
        }
        if (body.isEmpty()) return ""

        val base = body.minOf { it.level }
        // Anchors have to be unique: two sections called "Notes" get `#notes` and `#notes-1`, which
        // is the rule GitHub uses and therefore the one the links have to follow.
        val seen = mutableMapOf<String, Int>()

        return body.joinToString("\n") { entry ->
            val indent = "  ".repeat((entry.level - base).coerceAtLeast(0))
            val slug = slug(entry.title)
            val count = seen.merge(slug, 0) { existing, _ -> existing + 1 } ?: 0
            val anchor = if (count == 0) slug else "$slug-$count"
            "$indent- [${escapeLabel(entry.title)}](#$anchor)"
        }
    }

    /** Inserts an empty, marked contents region at the caret, ready to be filled. */
    public fun insertTableOfContentsRegion(value: TextFieldValue, outline: List<OutlineEntry>): TextFieldValue {
        val text = value.text
        val caret = value.selection.max.coerceIn(0, text.length)
        val lead = if (caret > 0 && text.getOrNull(caret - 1) != '\n') "\n" else ""

        val contents = renderContents(outline)
        val block = buildString {
            append(lead)
            append(TOC_OPEN).append('\n')
            if (contents.isNotEmpty()) append(contents).append('\n')
            append(TOC_CLOSE).append('\n')
        }

        return TextFieldValue(
            text = text.substring(0, caret) + block + text.substring(caret),
            selection = TextRange(caret + block.length),
        )
    }

    /** Whether the document carries a generated contents region. */
    public fun hasTableOfContents(text: String): Boolean =
        text.contains(TOC_OPEN) && text.contains(TOC_CLOSE)

    /**
     * GitHub's heading anchor rules, which is where these documents are usually read.
     *
     * Lower-cased, punctuation dropped, spaces to hyphens — and letters from every script kept,
     * because GitHub keeps them: `## 한국어` anchors to `#한국어`, and a slug function that only
     * knew ASCII would produce `#` and link to nothing.
     */
    public fun slug(title: String): String = title.trim().lowercase()
        .replace(Regex("[^\\p{L}\\p{N}\\s-]"), "")
        .replace(Regex("\\s+"), "-")

    /** Escapes the characters that would end a link label early. */
    private fun escapeLabel(title: String): String =
        title.replace("[", "\\[").replace("]", "\\]")
}
