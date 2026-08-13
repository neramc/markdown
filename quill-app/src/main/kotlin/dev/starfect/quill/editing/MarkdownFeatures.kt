package dev.starfect.quill.editing

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Everything Markdown can do, in one list you can search.
 *
 * Markdown's problem has never been that it is hard; it is that nobody remembers all of it. Tables,
 * footnotes, alerts, definition lists, collapsible sections, anchors, maths — each is two characters
 * once you know it, and unfindable until then. A reference card solves that for the person who
 * thinks to look for one. A searchable list *in the editor* solves it for everybody else.
 *
 * The same catalogue drives two entry points, deliberately:
 *
 * * **`/` at the start of an empty line** — the shape people know from every note-taking app. You
 *   are already typing, and the list narrows as you type.
 * * **Ctrl/Cmd+K** — reachable from anywhere, including the middle of a sentence, and it works on a
 *   selection: choosing *Bold* with three words selected wraps those three words.
 *
 * Each entry knows how to apply itself to a [TextFieldValue], which is what makes both entry points
 * a list and a function call rather than two switch statements that drift apart.
 */
public object MarkdownFeatures {

    /** What a feature belongs to, which is how the list is grouped when nothing is typed. */
    public enum class Group(public val label: String) {
        BLOCKS("Blocks"),
        TEXT("Text"),
        LISTS("Lists"),
        INSERT("Insert"),
        ADVANCED("Advanced"),
    }

    /**
     * One thing Markdown can do.
     *
     * @param name what it is called, which is what most searches match on.
     * @param syntax the source it produces, shown beside the name so the list teaches the syntax
     *   rather than hiding it behind a menu entry.
     * @param keywords extra search terms, for the names people reach for that are not the feature's
     *   own — "callout" for an alert, "tick box" for a task, "TOC" for a table of contents.
     * @param apply how it changes the document.
     */
    public data class Feature(
        val id: String,
        val name: String,
        val group: Group,
        val syntax: String,
        val description: String,
        val keywords: List<String> = emptyList(),
        val shortcut: String? = null,
        val apply: (TextFieldValue) -> TextFieldValue,
    )

    // ------------------------------------------------------------------ the catalogue

    private val ALERT_KINDS = listOf("Note", "Tip", "Important", "Warning", "Caution")

    private val TABLE_TEMPLATE = """
        | Column | Column | Column |
        | ------ | ------ | ------ |
        |        |        |        |

    """.trimIndent()

    private val DETAILS_TEMPLATE = """
        <details>
        <summary>Summary</summary>

        Hidden content.

        </details>

    """.trimIndent()


    /** Every feature, in the order the list shows them when nothing has been typed. */
    public val all: List<Feature> = buildList {
        // --- blocks
        for (level in 1..6) {
            add(
                Feature(
                    id = "heading$level",
                    name = "Heading $level",
                    group = Group.BLOCKS,
                    syntax = "#".repeat(level) + " ",
                    description = "A level-$level section title",
                    keywords = listOf("h$level", "title", "section"),
                    shortcut = if (level == 1) "Ctrl+Shift+." else null,
                    apply = { setHeading(it, level) },
                )
            )
        }
        add(
            Feature(
                id = "paragraph",
                name = "Plain text",
                group = Group.BLOCKS,
                syntax = "",
                description = "Strip the block marker from this line",
                keywords = listOf("normal", "body", "clear"),
                apply = { setHeading(it, 0) },
            )
        )
        add(
            Feature(
                id = "quote",
                name = "Quote",
                group = Group.BLOCKS,
                syntax = "> ",
                description = "An indented quotation",
                keywords = listOf("blockquote", "citation"),
                shortcut = "Ctrl+Shift+Q",
                apply = { MarkdownEdits.toggleQuote(it) },
            )
        )
        add(
            Feature(
                id = "code-block",
                name = "Code block",
                group = Group.BLOCKS,
                syntax = "```lang",
                description = "A fenced block with syntax highlighting",
                keywords = listOf("fence", "snippet", "pre", "```"),
                apply = { insertBlock(it, "```\n", "\n```", caretOffset = 3) },
            )
        )
        add(
            Feature(
                id = "divider",
                name = "Divider",
                group = Group.BLOCKS,
                syntax = "---",
                description = "A horizontal rule between sections",
                keywords = listOf("rule", "hr", "separator", "line"),
                apply = { insertBlock(it, "---\n", "") },
            )
        )
        add(
            Feature(
                id = "table",
                name = "Table",
                group = Group.BLOCKS,
                syntax = "| a | b |",
                description = "A three-column table with a header row",
                keywords = listOf("grid", "columns", "rows"),
                apply = { insertBlock(it, TABLE_TEMPLATE, "") },
            )
        )

        // --- text
        add(
            Feature(
                id = "bold",
                name = "Bold",
                group = Group.TEXT,
                syntax = "**text**",
                description = "Strong emphasis",
                keywords = listOf("strong", "heavy"),
                shortcut = "Ctrl+B",
                apply = { MarkdownEdits.toggleEmphasis(it, "**") },
            )
        )
        add(
            Feature(
                id = "italic",
                name = "Italic",
                group = Group.TEXT,
                syntax = "*text*",
                description = "Emphasis",
                keywords = listOf("emphasis", "slanted"),
                shortcut = "Ctrl+I",
                apply = { MarkdownEdits.toggleEmphasis(it, "*") },
            )
        )
        add(
            Feature(
                id = "strikethrough",
                name = "Strikethrough",
                group = Group.TEXT,
                syntax = "~~text~~",
                description = "Struck-out text",
                keywords = listOf("strike", "deleted", "crossed out"),
                apply = { MarkdownEdits.toggleEmphasis(it, "~~") },
            )
        )
        add(
            Feature(
                id = "inline-code",
                name = "Inline code",
                group = Group.TEXT,
                syntax = "`code`",
                description = "A literal run inside a sentence",
                keywords = listOf("monospace", "literal", "backtick"),
                shortcut = "Ctrl+Shift+C",
                apply = { MarkdownEdits.toggleEmphasis(it, "`") },
            )
        )
        add(
            Feature(
                id = "highlight",
                name = "Highlight",
                group = Group.TEXT,
                syntax = "==text==",
                description = "Marked text — Pandoc and Obsidian; GitHub shows the equals signs",
                keywords = listOf("mark", "marker", "yellow"),
                apply = { MarkdownEdits.toggleEmphasis(it, "==") },
            )
        )
        add(
            Feature(
                id = "subscript",
                name = "Subscript",
                group = Group.TEXT,
                syntax = "H~2~O",
                description = "Lowered text — Pandoc and MultiMarkdown",
                keywords = listOf("below", "chemistry"),
                apply = { MarkdownEdits.toggleEmphasis(it, "~") },
            )
        )
        add(
            Feature(
                id = "superscript",
                name = "Superscript",
                group = Group.TEXT,
                syntax = "x^2^",
                description = "Raised text — Pandoc and MultiMarkdown",
                keywords = listOf("above", "power", "exponent"),
                apply = { MarkdownEdits.toggleEmphasis(it, "^") },
            )
        )

        // --- lists
        add(
            Feature(
                id = "bullet-list",
                name = "Bulleted list",
                group = Group.LISTS,
                syntax = "- item",
                description = "An unordered list",
                keywords = listOf("unordered", "ul", "points"),
                shortcut = "Ctrl+Shift+L",
                apply = { MarkdownEdits.toggleBullet(it) },
            )
        )
        add(
            Feature(
                id = "numbered-list",
                name = "Numbered list",
                group = Group.LISTS,
                syntax = "1. item",
                description = "An ordered list",
                keywords = listOf("ordered", "ol", "steps"),
                apply = { toggleOrdered(it) },
            )
        )
        add(
            Feature(
                id = "task-list",
                name = "Task list",
                group = Group.LISTS,
                syntax = "- [ ] task",
                description = "A checklist you can tick in the preview",
                keywords = listOf("todo", "checkbox", "tick box", "checklist"),
                shortcut = "Ctrl+Shift+T",
                apply = { MarkdownEdits.toggleTask(it) },
            )
        )
        add(
            Feature(
                id = "definition-list",
                name = "Definition list",
                group = Group.LISTS,
                syntax = "Term\\n: definition",
                description = "Terms and their definitions — Pandoc, MultiMarkdown, Markdown Extra",
                keywords = listOf("glossary", "dl", "terms"),
                apply = { insertBlock(it, "Term\n: Definition\n", "") },
            )
        )

        // --- insert
        add(
            Feature(
                id = "link",
                name = "Link",
                group = Group.INSERT,
                syntax = "[text](url)",
                description = "A hyperlink",
                keywords = listOf("url", "href", "anchor"),
                shortcut = "Ctrl+Shift+K",
                apply = { MarkdownEdits.insertLink(it) },
            )
        )
        add(
            Feature(
                id = "image",
                name = "Image",
                group = Group.INSERT,
                syntax = "![alt](path)",
                description = "An embedded image — or just drop the file into the editor",
                keywords = listOf("picture", "photo", "figure", "screenshot"),
                apply = { insertInline(it, "![", "](path)", caretOffset = 2) },
            )
        )
        add(
            Feature(
                id = "footnote",
                name = "Footnote",
                group = Group.INSERT,
                syntax = "[^1]",
                description = "A reference with its note at the foot of the document",
                keywords = listOf("note", "endnote", "citation"),
                apply = { insertFootnote(it) },
            )
        )
        add(
            Feature(
                id = "toc",
                name = "Table of contents",
                group = Group.INSERT,
                syntax = "- [Heading](#heading)",
                description = "A linked contents list built from this document's headings",
                keywords = listOf("toc", "contents", "outline", "index"),
                // Filled in by the controller, which is the only thing that has the outline.
                apply = { it },
            )
        )

        // --- advanced
        for (kind in ALERT_KINDS) {
            add(
                Feature(
                    id = "alert-${kind.lowercase()}",
                    name = "$kind callout",
                    group = Group.ADVANCED,
                    syntax = "> [!${kind.uppercase()}]",
                    description = "A GitHub alert box",
                    keywords = listOf("alert", "callout", "admonition", "banner", kind.lowercase()),
                    apply = { insertBlock(it, "> [!${kind.uppercase()}]\n> ", "", caretOffset = null) },
                )
            )
        }
        add(
            Feature(
                id = "details",
                name = "Collapsible section",
                group = Group.ADVANCED,
                syntax = "<details>",
                description = "A section the reader expands — renders on GitHub",
                keywords = listOf("details", "spoiler", "fold", "accordion", "expand"),
                apply = { insertBlock(it, DETAILS_TEMPLATE, "") },
            )
        )
        add(
            Feature(
                id = "front-matter",
                name = "Front matter",
                group = Group.ADVANCED,
                syntax = "---\\ntitle: …\\n---",
                description = "YAML metadata at the top of the document",
                keywords = listOf("yaml", "metadata", "header", "jekyll", "hugo"),
                apply = { insertFrontMatter(it) },
            )
        )
        add(
            Feature(
                id = "math-inline",
                name = "Inline maths",
                group = Group.ADVANCED,
                syntax = "\$x^2\$",
                description = "A formula inside a sentence — MyST, Pandoc, MultiMarkdown, GitHub",
                keywords = listOf("latex", "formula", "equation", "tex"),
                apply = { insertInline(it, "$", "$", caretOffset = 1) },
            )
        )
        add(
            Feature(
                id = "math-block",
                name = "Display maths",
                group = Group.ADVANCED,
                syntax = "\$\$…\$\$",
                description = "A formula on a line of its own",
                keywords = listOf("latex", "formula", "equation", "tex", "display"),
                apply = { insertBlock(it, "$$\n", "\n$$", caretOffset = 3) },
            )
        )
        add(
            Feature(
                id = "comment",
                name = "Comment",
                group = Group.ADVANCED,
                syntax = "<!-- … -->",
                description = "A note that does not appear in the rendered document",
                keywords = listOf("hidden", "invisible", "note to self", "remark"),
                apply = { insertInline(it, "<!-- ", " -->", caretOffset = 5) },
            )
        )
        add(
            Feature(
                id = "anchor",
                name = "Heading anchor",
                group = Group.ADVANCED,
                syntax = "{#custom-id}",
                description = "A custom link target on a heading — Pandoc, Markdown Extra, MyST",
                keywords = listOf("id", "slug", "target", "permalink"),
                apply = { appendToLine(it, " {#custom-id}") },
            )
        )
        add(
            Feature(
                id = "line-break",
                name = "Hard line break",
                group = Group.ADVANCED,
                syntax = "\\",
                description = "Break the line without starting a paragraph",
                keywords = listOf("br", "newline", "wrap"),
                apply = { insertInline(it, "\\\n", "") },
            )
        )
        add(
            Feature(
                id = "escape",
                name = "Escape",
                group = Group.ADVANCED,
                syntax = "\\*",
                description = "Show a Markdown character instead of using it",
                keywords = listOf("literal", "backslash", "verbatim"),
                apply = { escapeSelection(it) },
            )
        )
    }

    // ------------------------------------------------------------------ searching

    /**
     * The features matching [query], best first.
     *
     * Subsequence matching, the same rule the command palette uses: `tbl` finds *Table*, `hd3` finds
     * *Heading 3*. It is what makes a search box usable before you know what the thing is called,
     * which is exactly the situation this list exists for.
     */
    public fun search(query: String): List<Feature> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return all

        return all.mapNotNull { feature ->
            score(feature, trimmed)?.let { feature to it }
        }.sortedWith(
            compareByDescending<Pair<Feature, Int>> { it.second }
                .thenBy { it.first.name.length }
        ).map { it.first }
    }

    /** How well a feature matches, or null when it does not. Higher is better. */
    private fun score(feature: Feature, query: String): Int? {
        val name = feature.name.lowercase()
        val lowered = query.lowercase()

        // An exact prefix of the name is what somebody who knows the name typed.
        if (name.startsWith(lowered)) return 1000 - name.length
        if (name.contains(lowered)) return 700 - name.length

        for (keyword in feature.keywords) {
            if (keyword.startsWith(lowered)) return 600
            if (keyword.contains(lowered)) return 500
        }
        // The syntax itself is searchable, so typing ``` finds the code block.
        if (feature.syntax.lowercase().contains(lowered)) return 400
        if (feature.description.lowercase().contains(lowered)) return 300

        return subsequenceScore(name, lowered)
    }

    /** Scores `hd3` against `heading 3`: every query character in order, closer together is better. */
    private fun subsequenceScore(candidate: String, query: String): Int? {
        var index = 0
        var score = 200
        var previous = -1
        for (character in query) {
            val found = candidate.indexOf(character, index)
            if (found < 0) return null
            if (previous >= 0) score -= (found - previous - 1).coerceAtMost(10)
            previous = found
            index = found + 1
        }
        return score
    }

    // ------------------------------------------------------------------ the edits

    /**
     * Removes the `/query` the writer typed to open the list.
     *
     * The slash and everything after it were the search box, not the document. Leaving them behind
     * would mean every use of this feature needed a manual cleanup, which is the kind of small
     * betrayal that stops people using a feature at all.
     */
    public fun removeTrigger(value: TextFieldValue, triggerStart: Int): TextFieldValue {
        val caret = value.selection.max.coerceIn(0, value.text.length)
        val start = triggerStart.coerceIn(0, caret)
        return TextFieldValue(
            text = value.text.removeRange(start, caret),
            selection = TextRange(start),
        )
    }

    /**
     * Where a `/` trigger starts, or null when the caret is not in one.
     *
     * A slash only opens the list at the start of an otherwise empty line. Anywhere else it is a
     * path separator, a date, or a fraction — and an editor that popped up a menu over `and/or`
     * would be unusable for prose.
     */
    public fun triggerStart(value: TextFieldValue): Int? {
        if (!value.selection.collapsed) return null
        val caret = value.selection.start.coerceIn(0, value.text.length)
        val lineStart = value.text.lastIndexOf('\n', (caret - 1).coerceAtLeast(0))
            .let { if (it < 0 || caret == 0) 0 else it + 1 }

        val line = value.text.substring(lineStart, caret)
        // Leading whitespace is allowed, so `/` works inside an indented list item.
        val content = line.trimStart()
        if (!content.startsWith('/')) return null
        // The query is one word: a space means the writer went back to writing prose.
        if (content.drop(1).any { it.isWhitespace() }) return null
        return lineStart + (line.length - content.length)
    }

    /** The query typed after the `/`. */
    public fun triggerQuery(value: TextFieldValue, triggerStart: Int): String {
        val caret = value.selection.max.coerceIn(0, value.text.length)
        if (triggerStart >= caret) return ""
        return value.text.substring(triggerStart + 1, caret)
    }

    // ------------------------------------------------------------------ edit helpers

    /** Sets the current line's heading level, where 0 means no heading. */
    private fun setHeading(value: TextFieldValue, level: Int): TextFieldValue {
        val text = value.text
        val caret = value.selection.max.coerceIn(0, text.length)
        val start = lineStart(text, caret)
        val end = lineEnd(text, caret)
        val line = text.substring(start, end)
        val body = line.trimStart('#').trimStart()
        val replacement = if (level == 0) body else "#".repeat(level) + " " + body

        return TextFieldValue(
            text = text.substring(0, start) + replacement + text.substring(end),
            selection = TextRange(start + replacement.length),
        )
    }

    /** Toggles an ordered list across the selected lines. */
    private fun toggleOrdered(value: TextFieldValue): TextFieldValue {
        val text = value.text
        val start = lineStart(text, value.selection.min)
        val end = lineEnd(text, value.selection.max)
        val lines = text.substring(start, end).split("\n")

        val numbered = Regex("^\\s*\\d+[.)]\\s")
        val allNumbered = lines.filter { it.isNotBlank() }.all { numbered.containsMatchIn(it) }

        var counter = 1
        val replaced = lines.joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                allNumbered -> line.replaceFirst(numbered, "")
                else -> "${counter++}. $line"
            }
        }

        return TextFieldValue(
            text = text.substring(0, start) + replaced + text.substring(end),
            selection = TextRange(start, start + replaced.length),
        )
    }

    /**
     * Inserts a block, on a line of its own.
     *
     * @param caretOffset where the caret lands inside the inserted text, counted from its start; the
     *   end of the whole insertion when null.
     */
    private fun insertBlock(
        value: TextFieldValue,
        before: String,
        after: String,
        caretOffset: Int? = null,
    ): TextFieldValue {
        val text = value.text
        val from = value.selection.min.coerceIn(0, text.length)
        val to = value.selection.max.coerceIn(0, text.length)

        // A block dropped into the middle of a paragraph is not a block. If there is anything
        // before it on its line, it needs a line of its own first.
        val lead = if (from > lineStart(text, from)) "\n" else ""
        val selected = text.substring(from, to)
        val insertion = lead + before + selected + after

        return TextFieldValue(
            text = text.substring(0, from) + insertion + text.substring(to),
            selection = TextRange(from + (caretOffset?.plus(lead.length) ?: insertion.length)),
        )
    }

    /** Inserts inline text around the selection, if any. */
    private fun insertInline(
        value: TextFieldValue,
        before: String,
        after: String,
        caretOffset: Int? = null,
    ): TextFieldValue {
        val text = value.text
        val from = value.selection.min
        val to = value.selection.max
        val selected = text.substring(from, to)
        val insertion = before + selected + after

        return TextFieldValue(
            text = text.substring(0, from) + insertion + text.substring(to),
            selection = TextRange(
                when {
                    caretOffset != null && selected.isEmpty() -> from + caretOffset
                    else -> from + before.length + selected.length
                }
            ),
        )
    }

    /** Appends to the end of the caret's line. */
    private fun appendToLine(value: TextFieldValue, suffix: String): TextFieldValue {
        val text = value.text
        val end = lineEnd(text, value.selection.max)
        return TextFieldValue(
            text = text.substring(0, end) + suffix + text.substring(end),
            selection = TextRange(end + suffix.length),
        )
    }

    /**
     * Inserts a footnote reference at the caret and its definition at the foot of the document.
     *
     * Numbered from the highest reference already present, so adding one to a document that has
     * three does not produce a second `[^1]`.
     */
    private fun insertFootnote(value: TextFieldValue): TextFieldValue {
        val existing = Regex("\\[\\^(\\d+)]").findAll(value.text)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull() ?: 0
        val number = existing + 1

        val caret = value.selection.max.coerceIn(0, value.text.length)
        val reference = "[^$number]"
        val withReference = value.text.substring(0, caret) + reference + value.text.substring(caret)

        val separator = if (withReference.endsWith("\n\n")) "" else if (withReference.endsWith("\n")) "\n" else "\n\n"
        val definition = "$separator[^$number]: "

        return TextFieldValue(
            text = withReference + definition,
            selection = TextRange(withReference.length + definition.length),
        )
    }

    /** Inserts a YAML front-matter block at the very top of the document. */
    private fun insertFrontMatter(value: TextFieldValue): TextFieldValue {
        if (value.text.startsWith("---\n")) return value
        val block = "---\ntitle: \ndate: \n---\n\n"
        return TextFieldValue(
            text = block + value.text,
            // Caret after `title: `, which is the field anybody is here to fill in.
            selection = TextRange("---\ntitle: ".length),
        )
    }

    /** Backslash-escapes the Markdown characters in the selection. */
    private fun escapeSelection(value: TextFieldValue): TextFieldValue {
        if (value.selection.collapsed) return insertInline(value, "\\", "")
        val from = value.selection.min
        val to = value.selection.max
        val escaped = value.text.substring(from, to).map { character ->
            if (character in "\\`*_{}[]()#+-.!|<>~") "\\$character" else "$character"
        }.joinToString("")

        return TextFieldValue(
            text = value.text.substring(0, from) + escaped + value.text.substring(to),
            selection = TextRange(from, from + escaped.length),
        )
    }

    private fun lineStart(text: String, offset: Int): Int {
        if (offset <= 0) return 0
        val found = text.lastIndexOf('\n', offset - 1)
        return if (found < 0) 0 else found + 1
    }

    private fun lineEnd(text: String, offset: Int): Int {
        val found = text.indexOf('\n', offset.coerceIn(0, text.length))
        return if (found < 0) text.length else found
    }
}
