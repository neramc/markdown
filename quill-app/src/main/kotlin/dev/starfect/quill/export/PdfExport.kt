package dev.starfect.quill.export

import dev.starfect.quill.bridge.wire.HtmlNode
import dev.starfect.quill.bridge.wire.textOf
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.Deflater

/**
 * PDF, laid out and written here rather than delegated.
 *
 * Of everything this editor exports, PDF is the only format with no equivalent of "let the reader
 * decide": HTML reflows, DOCX and EPUB resolve their own fonts, and a PDF is a fixed arrangement of
 * glyphs on a page. That means this file has to do two things nothing else here does — decide where
 * every line breaks, and embed a font — and it is why there is a [TrueTypeFont] parser next door.
 *
 * **The font is the whole problem.** A PDF can use fourteen fonts without embedding anything and
 * all fourteen are Latin, so the short version of this exporter produces a document in which every
 * Hangul syllable is an empty box. So: the document's own text is scanned, a font that covers it is
 * found on the machine, and that font is embedded as a CID font with Identity-H encoding, where the
 * character code written into the content stream *is* the glyph index. That is what makes
 * `# 한국어 제목` come out as `한국어 제목`.
 *
 * When no font on the machine covers the document — possible on a minimal container — the closest
 * match is used and the caller is told what is missing, rather than a file being produced that
 * silently drops half the text.
 *
 * The layout is a single column with real measurement: every line's width is computed from the
 * font's own advance widths before it is written, so wrapping is correct rather than approximate.
 * What it does not do is hyphenate, balance pages, float figures or lay out multi-column text. Those
 * are typesetting, and this is an export.
 */
public object PdfExport {

    /** What an export produced, including anything the reader ought to know about it. */
    public data class Report(
        val pages: Int,
        /** Set when the chosen font could not draw every character in the document. */
        val warning: String? = null,
    )

    // ------------------------------------------------------------------ page geometry

    /** A4 at 72 points per inch, which is what PDF measures in. */
    private const val PAGE_WIDTH = 595f
    private const val PAGE_HEIGHT = 842f
    private const val MARGIN = 56f

    private const val BODY_SIZE = 10.5f
    private const val CODE_SIZE = 9f
    private const val LINE_SPACING = 1.45f
    private const val PARAGRAPH_GAP = 6f

    /** Heading sizes, largest first, matching the six levels. */
    private val HEADING_SIZES = floatArrayOf(21f, 17f, 14.5f, 12.5f, 11.5f, 11f)

    /** How much is deflated at a time. Streams here are a font or a page, so this is one pass. */
    private const val DEFLATE_BUFFER = 64 * 1024

    /** A `bfchar` section may hold a hundred entries; past that, readers stop reading it. */
    private const val BFCHAR_LIMIT = 100

    private const val INDENT_STEP = 18f
    private const val QUOTE_RULE_INSET = 8f

    /** Ink. Body text is not pure black: a hair off it is what print has always done. */
    private val TEXT_COLOUR = Rgb(0.13f, 0.13f, 0.15f)
    private val MUTED_COLOUR = Rgb(0.42f, 0.44f, 0.48f)
    private val RULE_COLOUR = Rgb(0.80f, 0.81f, 0.84f)
    private val CODE_BACKGROUND = Rgb(0.965f, 0.967f, 0.973f)
    private val LINK_COLOUR = Rgb(0.11f, 0.36f, 0.72f)

    private data class Rgb(val red: Float, val green: Float, val blue: Float) {
        fun fill() = "%.3f %.3f %.3f rg".format(red, green, blue)
        fun stroke() = "%.3f %.3f %.3f RG".format(red, green, blue)
    }

    // ------------------------------------------------------------------ entry point

    /** Writes [nodes] to [target] as a PDF, and reports what happened. */
    public fun write(target: Path, title: String, nodes: List<HtmlNode>): Report {
        val text = textOf(nodes) + title
        val body = FontLibrary.findCovering(text)
        val mono = FontLibrary.findCovering(codeText(nodes).ifBlank { "code" }, monospace = true) ?: body

        if (body == null) {
            // No embeddable font at all. The base-14 fallback is Latin-only, and saying so is the
            // only honest thing to do.
            val document = Writer(null, null).build(title, nodes)
            Files.createDirectories(target.toAbsolutePath().parent ?: target)
            Files.write(target, document.bytes)
            return Report(
                document.pages,
                "No embeddable font was found on this machine, so the PDF uses a built-in Latin " +
                    "font. Characters outside Latin-1 will be missing.",
            )
        }

        val missing = text.codePoints().distinct().toArray()
            .filter { !Character.isWhitespace(it) && it >= ' '.code && !body.covers(it) }

        val document = Writer(body, mono).build(title, nodes)
        target.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.write(target, document.bytes)

        return Report(
            pages = document.pages,
            warning = if (missing.isEmpty()) {
                null
            } else {
                val sample = missing.take(6).joinToString(" ") { String(Character.toChars(it)) }
                "${body.postScriptName} could not draw ${missing.size} character(s) in this " +
                    "document ($sample). They are blank in the PDF."
            },
        )
    }

    /** Every code block's text, so a monospace font can be chosen that covers it. */
    private fun codeText(nodes: List<HtmlNode>): String = buildString {
        fun walk(list: List<HtmlNode>) {
            for (node in list) {
                if (node !is HtmlNode.Element) continue
                if (node.tag == "pre" || node.tag == "code") append(textOf(node.children))
                walk(node.children)
            }
        }
        walk(nodes)
    }

    // ------------------------------------------------------------------ layout

    private class Document(val bytes: ByteArray, val pages: Int)

    /** One styled run of text, already measured. */
    private data class Run(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val monospace: Boolean = false,
        val colour: Rgb = TEXT_COLOUR,
        val strike: Boolean = false,
        val underline: Boolean = false,
    )

    /**
     * Turns the document tree into pages of positioned text.
     *
     * Written as a single pass that emits content-stream operators as it goes, rather than building
     * a box tree first. A box tree earns its cost when a layout has to be revisited — floats,
     * balanced columns, widow control — and this one never is: a line's position depends only on
     * what came before it.
     */
    private class Writer(private val body: TrueTypeFont?, private val mono: TrueTypeFont?) {

        private val pages = mutableListOf<String>()
        private val content = StringBuilder()
        private var cursor = PAGE_HEIGHT - MARGIN
        private var indent = 0f
        private var quoteDepth = 0

        /** Everything drawn, so the `ToUnicode` map covers exactly what the file contains. */
        private val drawn = mutableListOf<String>()

        fun build(title: String, nodes: List<HtmlNode>): Document {
            if (title.isNotBlank()) {
                writeLines(listOf(Run(title, bold = true)), HEADING_SIZES[0])
                cursor -= PARAGRAPH_GAP
                rule()
            }

            blocks(nodes)
            endPage()

            return assemble()
        }

        // -------------------------------------------------------------- blocks

        private fun blocks(nodes: List<HtmlNode>) {
            for (node in nodes) {
                when (node) {
                    is HtmlNode.Text -> if (node.text.isNotBlank()) {
                        paragraph(listOf(Run(node.text)), BODY_SIZE)
                    }
                    is HtmlNode.Element -> block(node)
                }
            }
        }

        private fun block(element: HtmlNode.Element) {
            when (element.tag) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    val level = element.tag[1] - '1'
                    val size = HEADING_SIZES[level.coerceIn(0, 5)]
                    // Space above a heading, and never one left stranded at the foot of a page.
                    cursor -= size * 0.6f
                    ensureRoom(size * LINE_SPACING * 3)
                    paragraph(runs(element.children, Run("", bold = true)), size)
                }

                "p" -> paragraph(runs(element.children, Run("")), BODY_SIZE)

                "blockquote" -> {
                    val top = cursor
                    quoteDepth++
                    indent += INDENT_STEP
                    blocks(element.children)
                    indent -= INDENT_STEP
                    quoteDepth--
                    // The rule is drawn after the content, because only then is its height known.
                    quoteRule(top, cursor)
                }

                "ul", "ol" -> list(element, ordered = element.tag == "ol")

                "pre" -> codeBlock(textOf(element.children))

                "table" -> table(element)

                "hr" -> {
                    cursor -= PARAGRAPH_GAP
                    rule()
                    cursor -= PARAGRAPH_GAP
                }

                "img" -> {
                    // Images are named rather than embedded: reading PNG and JPEG, colour spaces and
                    // transparency is a second project, and a caption is more use than nothing.
                    val alt = element.attribute("alt").orEmpty()
                    val source = element.attribute("src").orEmpty()
                    val label = if (alt.isBlank()) source else "$alt — $source"
                    if (label.isNotBlank()) {
                        paragraph(listOf(Run("[image: $label]", italic = true, colour = MUTED_COLOUR)), BODY_SIZE)
                    }
                }

                "div", "section", "article", "main", "figure", "details", "dl" ->
                    blocks(element.children)

                "dt" -> paragraph(runs(element.children, Run("", bold = true)), BODY_SIZE)
                "dd" -> {
                    indent += INDENT_STEP
                    paragraph(runs(element.children, Run("")), BODY_SIZE)
                    indent -= INDENT_STEP
                }

                else -> {
                    val inline = runs(listOf(element), Run(""))
                    if (inline.any { it.text.isNotBlank() }) paragraph(inline, BODY_SIZE)
                }
            }
        }

        private fun list(element: HtmlNode.Element, ordered: Boolean) {
            var number = element.attribute("start")?.toIntOrNull() ?: 1

            for (child in element.children) {
                if (child !is HtmlNode.Element || child.tag != "li") continue

                val checkbox = child.children.filterIsInstance<HtmlNode.Element>()
                    .firstOrNull { it.tag == "input" }
                val marker = when {
                    checkbox != null && checkbox.attribute("checked") != null -> "☑"
                    checkbox != null -> "☐"
                    ordered -> "${number++}."
                    else -> "•"
                }

                val inline = child.children.filter { !isBlock(it) }
                val nested = child.children.filter { isBlock(it) }

                val markerWidth = measure(marker, BODY_SIZE, mono = false, bold = false) + 6f
                val itemRuns = runs(inline, Run(""))

                if (itemRuns.any { it.text.isNotBlank() } || checkbox != null) {
                    ensureRoom(BODY_SIZE * LINE_SPACING)
                    // The marker sits in the hanging indent, so wrapped lines line up under the text.
                    drawText(marker, MARGIN + indent, cursor, BODY_SIZE, mono = false, colour = TEXT_COLOUR)
                    indent += markerWidth
                    writeLines(itemRuns, BODY_SIZE, alreadyPositioned = true)
                    indent -= markerWidth
                }

                if (nested.isNotEmpty()) {
                    indent += INDENT_STEP
                    blocks(nested)
                    indent -= INDENT_STEP
                }
            }
            cursor -= PARAGRAPH_GAP
        }

        private fun codeBlock(source: String) {
            val lines = source.trimEnd('\n').split("\n")
            val height = lines.size * CODE_SIZE * 1.35f + 10f

            ensureRoom(minOf(height, PAGE_HEIGHT - MARGIN * 2))
            val top = cursor + CODE_SIZE * 0.9f

            // The shaded panel is drawn first so the text sits on top of it.
            val panelHeight = minOf(height, top - MARGIN)
            content.append(CODE_BACKGROUND.fill()).append('\n')
            content.append(
                "%.2f %.2f %.2f %.2f re f\n".format(
                    MARGIN + indent - 4f,
                    top - panelHeight,
                    PAGE_WIDTH - MARGIN * 2 - indent + 8f,
                    panelHeight,
                )
            )

            cursor -= 5f
            for (line in lines) {
                ensureRoom(CODE_SIZE * 1.35f)
                // A code line is not wrapped: it is clipped to the panel, because a wrapped line of
                // code says something the source did not.
                drawText(clip(line, CODE_SIZE), MARGIN + indent, cursor, CODE_SIZE, mono = true, colour = TEXT_COLOUR)
                cursor -= CODE_SIZE * 1.35f
            }
            cursor -= PARAGRAPH_GAP
        }

        /** Trims a code line to the panel width, marking that it was cut. */
        private fun clip(line: String, size: Float): String {
            val available = PAGE_WIDTH - MARGIN * 2 - indent
            if (measure(line, size, mono = true, bold = false) <= available) return line

            var text = line
            while (text.isNotEmpty() && measure("$text…", size, mono = true, bold = false) > available) {
                text = text.dropLast(1)
            }
            return "$text…"
        }

        private fun table(element: HtmlNode.Element) {
            val rows = rowsOf(element)
            if (rows.isEmpty()) return

            val columns = rows.maxOf { row ->
                row.children.count { it is HtmlNode.Element && (it.tag == "td" || it.tag == "th") }
            }
            if (columns == 0) return

            val available = PAGE_WIDTH - MARGIN * 2 - indent
            val columnWidth = available / columns

            for (row in rows) {
                val cells = row.children.filterIsInstance<HtmlNode.Element>()
                    .filter { it.tag == "td" || it.tag == "th" }
                val header = cells.any { it.tag == "th" }

                ensureRoom(BODY_SIZE * LINE_SPACING + 4f)
                val top = cursor

                var deepest = cursor
                cells.forEachIndexed { index, cell ->
                    val x = MARGIN + indent + index * columnWidth
                    val saved = cursor
                    val lines = wrap(runs(cell.children, Run("", bold = header)), BODY_SIZE, columnWidth - 8f)
                    for (line in lines) {
                        drawRuns(line, x + 3f, cursor, BODY_SIZE)
                        cursor -= BODY_SIZE * LINE_SPACING
                    }
                    deepest = minOf(deepest, cursor)
                    cursor = saved
                }

                cursor = deepest - 3f
                content.append(RULE_COLOUR.stroke()).append('\n')
                content.append("0.5 w\n")
                content.append("%.2f %.2f m %.2f %.2f l S\n".format(
                    MARGIN + indent, cursor + 2f, PAGE_WIDTH - MARGIN, cursor + 2f,
                ))
                if (top == cursor) break
            }
            cursor -= PARAGRAPH_GAP
        }

        // -------------------------------------------------------------- inline

        /** Flattens an element's children into styled runs. */
        private fun runs(nodes: List<HtmlNode>, inherited: Run): List<Run> {
            val out = mutableListOf<Run>()

            fun walk(list: List<HtmlNode>, style: Run) {
                for (node in list) {
                    when (node) {
                        is HtmlNode.Text -> if (node.text.isNotEmpty()) {
                            out.add(style.copy(text = node.text))
                        }
                        is HtmlNode.Element -> when (node.tag) {
                            "strong", "b" -> walk(node.children, style.copy(bold = true))
                            "em", "i", "cite", "var" -> walk(node.children, style.copy(italic = true))
                            "del", "s" -> walk(node.children, style.copy(strike = true))
                            "u", "ins" -> walk(node.children, style.copy(underline = true))
                            "code", "kbd", "samp" -> walk(node.children, style.copy(monospace = true))
                            "a" -> walk(node.children, style.copy(colour = LINK_COLOUR, underline = true))
                            "br" -> out.add(style.copy(text = "\n"))
                            "input" -> Unit
                            "img" -> node.attribute("alt")?.takeIf { it.isNotBlank() }?.let {
                                out.add(style.copy(text = "[$it]", italic = true, colour = MUTED_COLOUR))
                            }
                            else -> walk(node.children, style)
                        }
                    }
                }
            }

            walk(nodes, inherited)
            return out
        }

        private fun paragraph(runs: List<Run>, size: Float) {
            if (runs.none { it.text.isNotBlank() }) return
            writeLines(runs, size)
            cursor -= PARAGRAPH_GAP
        }

        /**
         * Wraps runs into lines and draws them.
         *
         * @param alreadyPositioned when the first line shares its row with something already drawn,
         *   which is how a list marker and its first line of text end up level.
         */
        private fun writeLines(runs: List<Run>, size: Float, alreadyPositioned: Boolean = false) {
            val available = PAGE_WIDTH - MARGIN * 2 - indent
            val lines = wrap(runs, size, available)

            lines.forEachIndexed { index, line ->
                if (index > 0 || !alreadyPositioned) ensureRoom(size * LINE_SPACING)
                drawRuns(line, MARGIN + indent, cursor, size)
                cursor -= size * LINE_SPACING
            }
        }

        /**
         * Breaks runs into lines that fit [available] points.
         *
         * Words are the break opportunity for Latin; for CJK every character is one, because Korean
         * and Japanese are written without spaces and a wrapper that only breaks on spaces produces
         * one enormous line running off the page. Getting this wrong is the second way a PDF
         * exporter fails a Korean document, after the font.
         */
        private fun wrap(runs: List<Run>, size: Float, available: Float): List<List<Run>> {
            val lines = mutableListOf<List<Run>>()
            var current = mutableListOf<Run>()
            var width = 0f

            fun flush() {
                lines.add(current)
                current = mutableListOf()
                width = 0f
            }

            for (run in runs) {
                if (run.text == "\n") {
                    flush()
                    continue
                }

                for (piece in segments(run.text)) {
                    if (piece == "\n") {
                        flush()
                        continue
                    }
                    val pieceWidth = measure(piece, size, run.monospace, run.bold)

                    // A leading space on a fresh line is the space that ended the previous one.
                    if (current.isEmpty() && piece.isBlank()) continue

                    if (width + pieceWidth > available && current.isNotEmpty()) flush()

                    val last = current.lastOrNull()
                    if (last != null && sameStyle(last, run)) {
                        current[current.size - 1] = last.copy(text = last.text + piece)
                    } else {
                        current.add(run.copy(text = piece))
                    }
                    width += pieceWidth
                }
            }

            if (current.isNotEmpty()) lines.add(current)
            return lines.ifEmpty { listOf(emptyList()) }
        }

        private fun sameStyle(a: Run, b: Run) =
            a.bold == b.bold && a.italic == b.italic && a.monospace == b.monospace &&
                a.colour == b.colour && a.strike == b.strike && a.underline == b.underline

        /**
         * Splits text into the smallest units a line may break between.
         *
         * A Latin word plus its trailing space is one unit; a CJK ideograph or syllable is one unit
         * on its own.
         */
        private fun segments(text: String): List<String> {
            val out = mutableListOf<String>()
            val builder = StringBuilder()

            var index = 0
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                val characters = Character.charCount(codePoint)
                val slice = text.substring(index, index + characters)

                when {
                    codePoint == '\n'.code -> {
                        if (builder.isNotEmpty()) out.add(builder.toString().also { builder.clear() })
                        out.add("\n")
                    }
                    isBreakable(codePoint) -> {
                        if (builder.isNotEmpty()) out.add(builder.toString().also { builder.clear() })
                        out.add(slice)
                    }
                    Character.isWhitespace(codePoint) -> {
                        builder.append(slice)
                        out.add(builder.toString())
                        builder.clear()
                    }
                    else -> builder.append(slice)
                }
                index += characters
            }
            if (builder.isNotEmpty()) out.add(builder.toString())
            return out
        }

        /** Whether a character may start a line on its own: the scripts written without spaces. */
        private fun isBreakable(codePoint: Int): Boolean = when (Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL,
            -> true
            else -> false
        }

        // -------------------------------------------------------------- drawing

        private fun drawRuns(runs: List<Run>, startX: Float, y: Float, size: Float) {
            var x = startX
            for (run in runs) {
                if (run.text.isEmpty()) continue
                drawText(run.text, x, y, size, run.monospace, run.colour, run.bold, run.italic)
                val width = measure(run.text, size, run.monospace, run.bold)

                if (run.strike || run.underline) {
                    val offset = if (run.strike) size * 0.28f else -size * 0.12f
                    content.append(run.colour.stroke()).append('\n')
                    content.append("0.6 w\n")
                    content.append("%.2f %.2f m %.2f %.2f l S\n".format(x, y + offset, x + width, y + offset))
                }
                x += width
            }
        }

        private fun drawText(
            text: String,
            x: Float,
            y: Float,
            size: Float,
            mono: Boolean,
            colour: Rgb,
            bold: Boolean = false,
            italic: Boolean = false,
        ) {
            if (text.isEmpty()) return
            drawn.add(text)

            val font = if (mono) "F2" else "F1"
            content.append("BT\n")
            content.append(colour.fill()).append('\n')
            // Synthetic bold and italic. The alternative is embedding four faces of two families,
            // which triples the file for an effect a reader cannot distinguish at body size.
            if (bold) content.append("2 Tr %.2f w\n".format(size * 0.035f)).append(colour.stroke()).append('\n')
            if (italic) content.append("1 0 0.21256 1 0 0 Tm\n")
            content.append("/$font %.2f Tf\n".format(size))
            content.append("%.2f %.2f Td\n".format(if (italic) x - y * 0.21256f else x, y))
            content.append(show(text)).append(" Tj\n")
            if (bold) content.append("0 Tr\n")
            content.append("ET\n")
        }

        /** A string, in whichever encoding the embedded font uses. */
        private fun show(text: String): String {
            val font = body
            if (font == null) {
                // Base-14 fallback: a literal string in WinAnsi, with the delimiters escaped.
                val escaped = text.map { character ->
                    when (character) {
                        '\\' -> "\\\\"
                        '(' -> "\\("
                        ')' -> "\\)"
                        in ' '..'ÿ' -> character.toString()
                        else -> "?"
                    }
                }.joinToString("")
                return "($escaped)"
            }

            // Identity-H: the code is the glyph, written as hex.
            val encoded = font.encode(text)
            return buildString(encoded.size * 2 + 2) {
                append('<')
                for (byte in encoded) append("%02X".format(byte))
                append('>')
            }
        }

        private fun measure(text: String, size: Float, mono: Boolean, bold: Boolean): Float {
            val font = (if (mono) this.mono else body) ?: return text.length * size * 0.5f
            val width = font.width(text, size)
            // Synthetic bold strokes outside the glyph, so it is a shade wider than it measures.
            return if (bold) width * 1.02f else width
        }

        private fun rule() {
            ensureRoom(4f)
            content.append(RULE_COLOUR.stroke()).append('\n')
            content.append("0.7 w\n")
            content.append("%.2f %.2f m %.2f %.2f l S\n".format(
                MARGIN + indent, cursor, PAGE_WIDTH - MARGIN, cursor,
            ))
            cursor -= 4f
        }

        private fun quoteRule(top: Float, bottom: Float) {
            if (top <= bottom) return
            content.append(RULE_COLOUR.stroke()).append('\n')
            content.append("2 w\n")
            val x = MARGIN + indent + QUOTE_RULE_INSET
            content.append("%.2f %.2f m %.2f %.2f l S\n".format(x, top + 3f, x, bottom + 8f))
        }

        // -------------------------------------------------------------- pages

        private fun ensureRoom(height: Float) {
            if (cursor - height < MARGIN) endPage()
        }

        private fun endPage() {
            if (content.isEmpty() && pages.isEmpty()) {
                // An empty document still gets one page: a zero-page PDF does not open.
                pages.add("")
                return
            }
            if (content.isEmpty()) return
            pages.add(content.toString())
            content.clear()
            cursor = PAGE_HEIGHT - MARGIN
        }

        // -------------------------------------------------------------- the file

        /**
         * Writes the PDF file itself: a header, numbered objects, a cross-reference table and a
         * trailer pointing at it.
         *
         * The cross-reference table is byte offsets into this very file, which is why the objects
         * are serialised into a byte stream and their positions recorded as they go rather than
         * being assembled from strings at the end.
         */
        private fun assemble(): Document {
            val out = ByteArrayOutputStream()
            val offsets = mutableListOf<Int>()

            fun write(text: String) = out.write(text.toByteArray(StandardCharsets.ISO_8859_1))

            fun startObject(number: Int) {
                while (offsets.size < number) offsets.add(0)
                offsets[number - 1] = out.size()
                write("$number 0 obj\n")
            }

            write("%PDF-1.7\n")
            // A comment of high bytes, which tells every tool that reads this the file is binary.
            out.write(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))

            val pageCount = pages.size.coerceAtLeast(1)
            // Object numbering: 1 catalogue, 2 page tree, then a page and a content stream each,
            // then the fonts.
            val firstPage = 3
            val firstContent = firstPage + pageCount
            val fontBase = firstContent + pageCount

            startObject(1)
            write("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

            startObject(2)
            val kids = (0 until pageCount).joinToString(" ") { "${firstPage + it} 0 R" }
            write("<< /Type /Pages /Count $pageCount /Kids [$kids] >>\nendobj\n")

            val fontResources = buildString {
                append("<< /F1 $fontBase 0 R /F2 ${fontBase + fontObjectCount()} 0 R >>")
            }

            for (index in 0 until pageCount) {
                startObject(firstPage + index)
                write(
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $PAGE_WIDTH $PAGE_HEIGHT] " +
                        "/Resources << /Font $fontResources >> " +
                        "/Contents ${firstContent + index} 0 R >>\nendobj\n"
                )
            }

            for (index in 0 until pageCount) {
                val stream = pages.getOrElse(index) { "" }
                startObject(firstContent + index)
                writeStream(out, "", stream.toByteArray(StandardCharsets.ISO_8859_1))
            }

            writeFont(out, offsets, fontBase, body, "F1")
            writeFont(out, offsets, fontBase + fontObjectCount(), mono ?: body, "F2")

            val xref = out.size()
            write("xref\n0 ${offsets.size + 1}\n")
            write("0000000000 65535 f \n")
            for (offset in offsets) write("%010d 00000 n \n".format(offset))
            write("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")

            return Document(out.toByteArray(), pageCount)
        }

        /**
         * How many objects one font takes: the type-0, the descendant, the descriptor, the font
         * file and the map back to Unicode.
         */
        private fun fontObjectCount() = if (body == null) 1 else 5

        /**
         * Writes a stream object's dictionary and body, compressed.
         *
         * Every stream in the file goes through here, which is why the whole file is compressed
         * rather than the part somebody remembered. It matters most for the one stream that is
         * large: an embedded font. [extras] carries whatever the object needs beyond the length and
         * the filter — `/Length1` for a font program, which the specification defines as the
         * *uncompressed* size and is therefore taken before packing.
         */
        private fun writeStream(out: ByteArrayOutputStream, extras: String, bytes: ByteArray) {
            val packed = deflate(bytes)
            out.write("<< /Length ${packed.size} /Filter /FlateDecode$extras >>\nstream\n".latin1())
            out.write(packed)
            out.write("\nendstream\nendobj\n".latin1())
        }

        private fun deflate(bytes: ByteArray): ByteArray {
            val deflater = Deflater(Deflater.BEST_COMPRESSION)
            return try {
                deflater.setInput(bytes)
                deflater.finish()
                val out = ByteArrayOutputStream(bytes.size / 4 + 64)
                val buffer = ByteArray(DEFLATE_BUFFER)
                while (!deflater.finished()) {
                    val count = deflater.deflate(buffer)
                    if (count <= 0) break
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            } finally {
                deflater.end()
            }
        }

        private fun String.latin1() = toByteArray(StandardCharsets.ISO_8859_1)

        /**
         * Writes a font, either as a base-14 reference or as an embedded CID font.
         *
         * The CID path is four objects because that is what the specification requires: a Type0
         * font naming the encoding, a CIDFontType2 carrying the widths, a descriptor carrying the
         * metrics, and the font program itself as a stream.
         */
        private fun writeFont(
            out: ByteArrayOutputStream,
            offsets: MutableList<Int>,
            base: Int,
            font: TrueTypeFont?,
            @Suppress("UNUSED_PARAMETER") resourceName: String,
        ) {
            fun write(text: String) = out.write(text.toByteArray(StandardCharsets.ISO_8859_1))
            fun startObject(number: Int) {
                while (offsets.size < number) offsets.add(0)
                offsets[number - 1] = out.size()
                write("$number 0 obj\n")
            }

            if (font == null) {
                startObject(base)
                write("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>\nendobj\n")
                return
            }

            val name = font.postScriptName.take(60).ifEmpty { "Embedded" }

            startObject(base)
            write(
                "<< /Type /Font /Subtype /Type0 /BaseFont /$name /Encoding /Identity-H " +
                    "/DescendantFonts [${base + 1} 0 R] /ToUnicode ${base + 4} 0 R >>\nendobj\n"
            )

            startObject(base + 1)
            write(
                "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /$name " +
                    "/CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> " +
                    "/FontDescriptor ${base + 2} 0 R /DW 1000 /W [${widths(font)}] " +
                    "/CIDToGIDMap /Identity >>\nendobj\n"
            )

            startObject(base + 2)
            val fileKey = if (font.isCompactFontFormat) "FontFile3" else "FontFile2"
            write(
                "<< /Type /FontDescriptor /FontName /$name /Flags 4 " +
                    "/FontBBox [-1000 ${font.descender} 2000 ${font.ascender}] /ItalicAngle 0 " +
                    "/Ascent ${font.ascender} /Descent ${font.descender} /CapHeight ${font.ascender} " +
                    "/StemV 80 /$fileKey ${base + 3} 0 R >>\nendobj\n"
            )

            // Only the glyphs the document draws. A CJK font is eleven megabytes and a page of
            // Korean uses perhaps forty shapes out of it, so embedding the file whole produces a
            // one-page letter larger than most people's mail will accept.
            startObject(base + 3)
            val program = font.subset(usedGlyphs(font))
            val extras = if (font.isCompactFontFormat) " /Subtype /OpenType" else " /Length1 ${program.size}"
            writeStream(out, extras, program)

            // Identity-H means the bytes in the content stream are glyph numbers, which is what
            // makes an arbitrary font usable without inventing an encoding for it -- and what makes
            // the text unreadable to anything that did not draw it. Without this map, selecting a
            // paragraph in a reader and copying it yields nonsense, and searching the document finds
            // nothing. The map says which character each glyph came from.
            startObject(base + 4)
            writeStream(out, "", toUnicodeCMap(font).latin1())
        }

        /** Every glyph the document draws in this font. */
        private fun usedGlyphs(font: TrueTypeFont): Set<Int> = buildSet {
            add(0)
            for (text in drawn) {
                var index = 0
                while (index < text.length) {
                    val codePoint = text.codePointAt(index)
                    add(font.glyph(codePoint))
                    index += Character.charCount(codePoint)
                }
            }
        }

        /**
         * The `ToUnicode` CMap: for each glyph the document uses, the character it stands for.
         *
         * A PostScript program, which is what the format is, in the shape every producer writes it.
         */
        private fun toUnicodeCMap(font: TrueTypeFont): String {
            val pairs = font.toUnicodeMap(drawn)

            return buildString {
                append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
                append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
                append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n")
                append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n")

                // A `bfchar` section may hold a hundred entries; more than that and readers stop.
                for (chunk in pairs.chunked(BFCHAR_LIMIT)) {
                    append(chunk.size).append(" beginbfchar\n")
                    for ((glyph, codePoint) in chunk) {
                        append("<%04X> <".format(glyph))
                        // Beyond the Basic Multilingual Plane a character is two UTF-16 units, and
                        // the map is defined in UTF-16 rather than in code points.
                        for (unit in Character.toChars(codePoint)) append("%04X".format(unit.code))
                        append(">\n")
                    }
                    append("endbfchar\n")
                }

                append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
            }
        }

        /**
         * The `/W` array: the advance of every glyph the document uses.
         *
         * Only the glyphs used, because a CJK font has tens of thousands and writing them all would
         * add more to the file than the text does.
         */
        private fun widths(font: TrueTypeFont): String {
            val used = sortedSetOf<Int>()
            for (text in drawn) {
                var index = 0
                while (index < text.length) {
                    val codePoint = text.codePointAt(index)
                    used.add(font.glyph(codePoint))
                    index += Character.charCount(codePoint)
                }
            }
            if (used.isEmpty()) return ""

            // Consecutive glyphs share one `first last width`-style run, which is the compact form.
            return buildString {
                for (glyph in used) {
                    append(glyph).append(" [").append(font.advanceMilli(glyph)).append("] ")
                }
            }.trim()
        }
    }

    // ------------------------------------------------------------------ helpers

    private val BLOCK_TAGS = setOf(
        "h1", "h2", "h3", "h4", "h5", "h6", "p", "div", "pre", "blockquote", "ul", "ol",
        "table", "hr", "section", "article", "dl", "figure",
    )

    private fun isBlock(node: HtmlNode): Boolean =
        node is HtmlNode.Element && node.tag in BLOCK_TAGS

    private fun rowsOf(table: HtmlNode.Element): List<HtmlNode.Element> = buildList {
        fun walk(nodes: List<HtmlNode>) {
            for (node in nodes) {
                if (node !is HtmlNode.Element) continue
                if (node.tag == "tr") add(node) else walk(node.children)
            }
        }
        walk(table.children)
    }
}
