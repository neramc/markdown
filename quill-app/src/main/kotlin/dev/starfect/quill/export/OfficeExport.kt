package dev.starfect.quill.export

import dev.starfect.quill.bridge.wire.HtmlNode
import dev.starfect.quill.bridge.wire.textOf
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream

/**
 * Word and EPUB, written directly rather than through a library.
 *
 * Both formats are a zip of XML, and both are being produced from the same tree the preview
 * renders, so the interesting part of each is not the container — it is knowing which of the
 * document's constructs the target has a real equivalent for. A heading that becomes a "Heading 1"
 * *style* can be restyled, navigated and put in a table of contents by Word; a heading that becomes
 * bold 18-point text cannot. That distinction is the whole reason these are hand-written: the
 * shortest path to a `.docx` produces the second kind, and it looks correct until somebody tries to
 * use the file.
 *
 * Neither of these embeds fonts, which is what makes them safe for any script: the reader resolves
 * the text with its own fonts, so Korean, Japanese and Arabic all render without this code knowing
 * anything about them. (The PDF exporter cannot do that, and [PdfExport] says so at length.)
 */
public object OfficeExport {

    // ------------------------------------------------------------------ DOCX

    /**
     * Writes an Office Open XML document.
     *
     * The parts are the minimum a conforming reader needs: the content types, the package
     * relationship to the document, the document itself, the styles it refers to, the numbering
     * definitions its lists refer to, and the relationships its hyperlinks refer to. Leave out the
     * numbering and every list becomes a run of unindented paragraphs; leave out the styles and the
     * headings become text that happens to be large.
     */
    public fun writeDocx(target: Path, title: String, nodes: List<HtmlNode>) {
        val body = DocxWriter().apply { blocks(nodes) }
        val relationships = body.relationships

        zip(target) {
            entry("[Content_Types].xml", CONTENT_TYPES)
            entry("_rels/.rels", PACKAGE_RELATIONSHIPS)
            entry("word/document.xml", documentXml(body.out.toString()))
            entry("word/styles.xml", stylesXml())
            entry("word/numbering.xml", NUMBERING_XML)
            entry("word/_rels/document.xml.rels", documentRelationships(relationships))
            entry("docProps/core.xml", coreProperties(title))
        }
    }

    // ------------------------------------------------------------------ EPUB

    /**
     * Writes an EPUB 3 file.
     *
     * The one thing an EPUB writer must get right before anything else is the `mimetype` entry: it
     * has to be the *first* entry in the zip and stored uncompressed, because a reader identifies
     * the file by reading those bytes at a fixed offset without unpacking anything. Every other
     * detail here is recoverable by a forgiving reader; that one is not.
     */
    public fun writeEpub(target: Path, title: String, nodes: List<HtmlNode>, language: String = "en") {
        val identifier = "urn:uuid:${UUID.randomUUID()}"
        val modified = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .format(ZonedDateTime.now(java.time.ZoneOffset.UTC))

        val chapter = XhtmlWriter().apply { blocks(nodes) }.out.toString()
        val headings = collectHeadings(nodes)

        zip(target) {
            storedEntry("mimetype", "application/epub+zip")
            entry("META-INF/container.xml", EPUB_CONTAINER)
            entry("OEBPS/content.opf", packageDocument(title, identifier, language, modified))
            entry("OEBPS/nav.xhtml", navigationDocument(title, headings))
            entry("OEBPS/styles/style.css", EPUB_STYLESHEET)
            entry("OEBPS/text/chapter.xhtml", chapterDocument(title, language, chapter))
        }
    }

    // ------------------------------------------------------------------ the DOCX body

    /**
     * Walks the HTML tree into WordprocessingML.
     *
     * Word's model is flat: a document is a sequence of paragraphs and tables, and everything a
     * Markdown document nests — a list inside a quote inside a list — has to become an indentation
     * level on an otherwise flat paragraph. That is what [depth] and [quoteDepth] carry.
     */
    private class DocxWriter {
        val out = StringBuilder()

        /** Hyperlink targets, in the order they were first seen; the index is the relationship id. */
        val relationships = mutableListOf<String>()

        private var depth = 0
        private var quoteDepth = 0

        fun blocks(nodes: List<HtmlNode>) {
            for (node in nodes) {
                when (node) {
                    is HtmlNode.Text -> if (node.text.isNotBlank()) paragraph("Body", node.text)
                    is HtmlNode.Element -> block(node)
                }
            }
        }

        private fun block(element: HtmlNode.Element) {
            when (element.tag) {
                "h1", "h2", "h3", "h4", "h5", "h6" ->
                    styledParagraph("Heading${element.tag[1]}", element.children)

                "p" -> styledParagraph("Body", element.children)

                "blockquote" -> {
                    quoteDepth++
                    blocks(element.children)
                    quoteDepth--
                }

                "ul", "ol" -> list(element, ordered = element.tag == "ol")

                "pre" -> code(textOf(element.children))

                "table" -> table(element)

                "hr" -> {
                    // Word has no horizontal rule element; a bottom border on an empty paragraph is
                    // how every tool that writes .docx produces one.
                    out.append(
                        "<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" " +
                            "w:color=\"AAAAAA\"/></w:pBdr></w:pPr></w:p>"
                    )
                }

                "div", "section", "article", "main", "figure", "details" -> blocks(element.children)

                "dl" -> blocks(element.children)
                "dt" -> styledParagraph("Strong", element.children)
                "dd" -> {
                    depth++
                    styledParagraph("Body", element.children)
                    depth--
                }

                // Anything else still has content, and the content is what matters.
                else -> {
                    val text = textOf(listOf(element))
                    if (text.isNotBlank()) styledParagraph("Body", element.children)
                }
            }
        }

        private fun list(element: HtmlNode.Element, ordered: Boolean) {
            depth++
            for (child in element.children) {
                if (child !is HtmlNode.Element || child.tag != "li") continue

                // An item's own text comes first, then anything nested under it — which is how a
                // list inside a list becomes a deeper indent rather than a sibling.
                val inline = child.children.filter { !isBlock(it) }
                val nested = child.children.filter { isBlock(it) }

                val checkbox = child.children
                    .filterIsInstance<HtmlNode.Element>()
                    .firstOrNull { it.tag == "input" }
                val prefix = when {
                    checkbox == null -> ""
                    checkbox.attribute("checked") != null -> "☑ "
                    else -> "☐ "
                }

                if (inline.isNotEmpty() || prefix.isNotEmpty()) {
                    out.append("<w:p><w:pPr><w:pStyle w:val=\"ListParagraph\"/>")
                    out.append(numbering(ordered))
                    out.append("</w:pPr>")
                    if (prefix.isNotEmpty()) run(prefix, RunStyle())
                    runs(inline, RunStyle())
                    out.append("</w:p>")
                }
                blocks(nested)
            }
            depth--
        }

        private fun numbering(ordered: Boolean): String {
            val level = (depth - 1).coerceIn(0, 8)
            val id = if (ordered) 2 else 1
            return "<w:numPr><w:ilvl w:val=\"$level\"/><w:numId w:val=\"$id\"/></w:numPr>"
        }

        private fun code(source: String) {
            // One paragraph per line: Word treats a paragraph as the unit of layout, and a single
            // paragraph holding newlines wraps as prose rather than keeping the code's own breaks.
            for (line in source.trimEnd('\n').split("\n")) {
                out.append("<w:p><w:pPr><w:pStyle w:val=\"Code\"/>")
                out.append(indentation())
                out.append("</w:pPr>")
                run(line, RunStyle(monospace = true))
                out.append("</w:p>")
            }
        }

        private fun table(element: HtmlNode.Element) {
            out.append(
                "<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/>" +
                    "<w:tblW w:w=\"0\" w:type=\"auto\"/>" +
                    "<w:tblBorders>" +
                    listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") {
                        "<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"BFBFBF\"/>"
                    } +
                    "</w:tblBorders></w:tblPr>"
            )
            for (row in rowsOf(element)) {
                out.append("<w:tr>")
                for (cell in row.children.filterIsInstance<HtmlNode.Element>()) {
                    if (cell.tag != "td" && cell.tag != "th") continue
                    out.append("<w:tc><w:tcPr><w:tcW w:w=\"0\" w:type=\"auto\"/></w:tcPr>")
                    out.append("<w:p><w:pPr><w:pStyle w:val=\"Body\"/></w:pPr>")
                    runs(cell.children, RunStyle(bold = cell.tag == "th"))
                    out.append("</w:p></w:tc>")
                }
                out.append("</w:tr>")
            }
            out.append("</w:tbl>")
            // A table must be followed by a paragraph, or Word merges it with whatever comes next.
            out.append("<w:p/>")
        }

        private fun styledParagraph(style: String, children: List<HtmlNode>) {
            out.append("<w:p><w:pPr><w:pStyle w:val=\"$style\"/>")
            out.append(indentation())
            out.append("</w:pPr>")
            runs(children, RunStyle())
            out.append("</w:p>")
        }

        private fun paragraph(style: String, text: String) {
            out.append("<w:p><w:pPr><w:pStyle w:val=\"$style\"/>${indentation()}</w:pPr>")
            run(text, RunStyle())
            out.append("</w:p>")
        }

        private fun indentation(): String {
            // 360 twentieths of a point is a quarter inch, Word's own default indent step.
            val left = (depth + quoteDepth) * 360
            if (left == 0) return ""
            return "<w:ind w:left=\"$left\"/>"
        }

        private fun runs(nodes: List<HtmlNode>, style: RunStyle) {
            for (node in nodes) {
                when (node) {
                    is HtmlNode.Text -> run(node.text, style)
                    is HtmlNode.Element -> when (node.tag) {
                        "strong", "b" -> runs(node.children, style.copy(bold = true))
                        "em", "i", "cite" -> runs(node.children, style.copy(italic = true))
                        "del", "s" -> runs(node.children, style.copy(strike = true))
                        "code", "kbd", "samp" -> runs(node.children, style.copy(monospace = true))
                        "sup" -> runs(node.children, style.copy(vertical = "superscript"))
                        "sub" -> runs(node.children, style.copy(vertical = "subscript"))
                        "br" -> out.append("<w:r><w:br/></w:r>")
                        "a" -> {
                            val href = node.attribute("href")
                            if (href.isNullOrEmpty()) {
                                runs(node.children, style)
                            } else {
                                val id = relationships.indexOf(href).takeIf { it >= 0 }
                                    ?: relationships.size.also { relationships.add(href) }
                                out.append("<w:hyperlink r:id=\"rId${id + RELATIONSHIP_BASE}\">")
                                runs(node.children, style.copy(link = true))
                                out.append("</w:hyperlink>")
                            }
                        }
                        // A checkbox has already been turned into a glyph by the list writer.
                        "input" -> Unit
                        else -> runs(node.children, style)
                    }
                }
            }
        }

        private fun run(text: String, style: RunStyle) {
            if (text.isEmpty()) return
            out.append("<w:r><w:rPr>")
            if (style.bold) out.append("<w:b/>")
            if (style.italic) out.append("<w:i/>")
            if (style.strike) out.append("<w:strike/>")
            if (style.monospace) out.append("<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\"/>")
            if (style.link) out.append("<w:color w:val=\"1A73E8\"/><w:u w:val=\"single\"/>")
            style.vertical?.let { out.append("<w:vertAlign w:val=\"$it\"/>") }
            out.append("</w:rPr>")
            // xml:space is not optional: without it Word strips the space between two styled runs,
            // and "**bold** text" comes out as "boldtext".
            out.append("<w:t xml:space=\"preserve\">${escape(text)}</w:t></w:r>")
        }
    }

    private data class RunStyle(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val monospace: Boolean = false,
        val link: Boolean = false,
        val vertical: String? = null,
    )

    /** Relationship ids 1 and 2 are taken by the styles and numbering parts. */
    private const val RELATIONSHIP_BASE = 3

    // ------------------------------------------------------------------ the EPUB body

    /** Re-serialises the tree as XHTML, which is what EPUB requires and HTML is not. */
    private class XhtmlWriter {
        val out = StringBuilder()

        fun blocks(nodes: List<HtmlNode>) {
            for (node in nodes) write(node)
        }

        private fun write(node: HtmlNode) {
            when (node) {
                is HtmlNode.Text -> out.append(escape(node.text))
                is HtmlNode.Element -> {
                    // A reader parses EPUB content as XML, so an unknown tag is not ignored the way
                    // a browser ignores it -- the book fails to open. Anything outside XHTML is
                    // reduced to its content.
                    if (node.tag !in XHTML_TAGS) {
                        blocks(node.children)
                        return
                    }

                    out.append('<').append(node.tag)
                    for (attribute in node.attributes) {
                        if (attribute.name !in SAFE_ATTRIBUTES) continue
                        out.append(' ').append(attribute.name)
                            .append("=\"").append(escape(attribute.value)).append('"')
                    }

                    if (node.tag in VOID_TAGS) {
                        out.append(" />")
                        return
                    }
                    out.append('>')
                    blocks(node.children)
                    out.append("</").append(node.tag).append('>')
                    if (node.tag in BLOCK_TAGS) out.append('\n')
                }
            }
        }
    }

    private val XHTML_TAGS = setOf(
        "h1", "h2", "h3", "h4", "h5", "h6", "p", "div", "span", "a", "em", "strong", "b", "i",
        "code", "pre", "blockquote", "ul", "ol", "li", "table", "thead", "tbody", "tr", "td", "th",
        "img", "br", "hr", "sup", "sub", "del", "s", "u", "mark", "dl", "dt", "dd", "figure",
        "figcaption", "small", "abbr", "cite", "kbd", "samp", "var", "section", "article", "aside",
    )

    private val VOID_TAGS = setOf("br", "hr", "img")

    private val BLOCK_TAGS = setOf(
        "h1", "h2", "h3", "h4", "h5", "h6", "p", "div", "pre", "blockquote", "ul", "ol", "li",
        "table", "tr", "hr", "section", "article",
    )

    /**
     * The attributes worth carrying into a book.
     *
     * Deliberately short. Event handlers and inline styles from a source document are exactly the
     * things that make an EPUB fail validation, and none of them are what the writer meant.
     */
    private val SAFE_ATTRIBUTES = setOf("href", "src", "alt", "title", "id", "class", "colspan", "rowspan")

    /** Headings, for the navigation document, which is what makes a book navigable. */
    private fun collectHeadings(nodes: List<HtmlNode>): List<Pair<Int, String>> = buildList {
        fun walk(list: List<HtmlNode>) {
            for (node in list) {
                if (node !is HtmlNode.Element) continue
                if (node.tag.length == 2 && node.tag[0] == 'h' && node.tag[1] in '1'..'6') {
                    add((node.tag[1] - '0') to textOf(node.children).trim())
                }
                walk(node.children)
            }
        }
        walk(nodes)
    }

    // ------------------------------------------------------------------ the parts

    /**
     * Assembles a part from lines.
     *
     * Deliberately not a raw string with `trimIndent`: `trimIndent` computes the common indentation
     * of the *interpolated* result, so a multi-line value pasted into an indented template removes
     * the template's own indentation — and, for an XML declaration, moves it off column zero, which
     * makes the file unparseable. Every part here interpolates something multi-line.
     */
    private fun part(vararg lines: String) = lines.joinToString("\n")

    private fun documentXml(body: String) = part(
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""",
        """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"""" +
            """ xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""",
        "<w:body>" + body +
            """<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>""" +
            """<w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134"/></w:sectPr></w:body>""",
        "</w:document>",
    )

    private fun chapterDocument(title: String, language: String, chapter: String) = part(
        """<?xml version="1.0" encoding="UTF-8"?>""",
        "<!DOCTYPE html>",
        """<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="$language" lang="$language">""",
        "<head>",
        """<meta charset="utf-8" />""",
        "<title>${escape(title)}</title>",
        """<link rel="stylesheet" type="text/css" href="../styles/style.css" />""",
        "</head>",
        "<body>",
        chapter,
        "</body>",
        "</html>",
    )

    private fun stylesXml(): String {
        val headings = (1..6).joinToString("") { level ->
            val size = listOf(36, 30, 26, 24, 22, 20)[level - 1]
            """
            <w:style w:type="paragraph" w:styleId="Heading$level">
              <w:name w:val="heading $level"/><w:basedOn w:val="Body"/>
              <w:pPr><w:outlineLvl w:val="${level - 1}"/>
                <w:spacing w:before="${280 - level * 20}" w:after="120"/><w:keepNext/></w:pPr>
              <w:rPr><w:b/><w:sz w:val="$size"/></w:rPr>
            </w:style>
            """.trimIndent()
        }

        return part(
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""",
            """<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""" + """
              <w:docDefaults><w:rPrDefault><w:rPr>
                <w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Malgun Gothic"/>
                <w:sz w:val="22"/></w:rPr></w:rPrDefault></w:docDefaults>
              <w:style w:type="paragraph" w:default="1" w:styleId="Body">
                <w:name w:val="Normal"/><w:pPr><w:spacing w:after="120" w:line="276" w:lineRule="auto"/></w:pPr>
              </w:style>
              $headings
              <w:style w:type="paragraph" w:styleId="ListParagraph">
                <w:name w:val="List Paragraph"/><w:basedOn w:val="Body"/>
                <w:pPr><w:spacing w:after="40"/><w:contextualSpacing/></w:pPr>
              </w:style>
              <w:style w:type="paragraph" w:styleId="Code">
                <w:name w:val="HTML Preformatted"/><w:basedOn w:val="Body"/>
                <w:pPr><w:spacing w:after="0" w:line="240" w:lineRule="auto"/>
                  <w:shd w:val="clear" w:fill="F5F5F5"/></w:pPr>
                <w:rPr><w:rFonts w:ascii="Consolas" w:hAnsi="Consolas"/><w:sz w:val="20"/></w:rPr>
              </w:style>
              <w:style w:type="paragraph" w:styleId="Strong">
                <w:name w:val="Strong Paragraph"/><w:basedOn w:val="Body"/><w:rPr><w:b/></w:rPr>
              </w:style>
              <w:style w:type="table" w:styleId="TableGrid"><w:name w:val="Table Grid"/></w:style>
            </w:styles>""",
        )
    }

    private fun documentRelationships(hyperlinks: List<String>): String {
        val links = hyperlinks.mapIndexed { index, target ->
            """<Relationship Id="rId${index + RELATIONSHIP_BASE}"
                 Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink"
                 Target="${escape(target)}" TargetMode="External"/>"""
        }.joinToString("")

        return part(
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""",
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""",
            """<Relationship Id="rId1" Target="styles.xml"""" +
                """ Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles"/>""",
            """<Relationship Id="rId2" Target="numbering.xml"""" +
                """ Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering"/>""",
            links,
            "</Relationships>",
        )
    }

    private fun coreProperties(title: String) = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
                           xmlns:dc="http://purl.org/dc/elements/1.1/">
          <dc:title>${escape(title)}</dc:title>
          <cp:lastModifiedBy>Quill</cp:lastModifiedBy>
        </cp:coreProperties>
    """.trimIndent()

    private val CONTENT_TYPES = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
          <Default Extension="xml" ContentType="application/xml"/>
          <Override PartName="/word/document.xml"
            ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
          <Override PartName="/word/styles.xml"
            ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
          <Override PartName="/word/numbering.xml"
            ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>
          <Override PartName="/docProps/core.xml"
            ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
        </Types>
    """.trimIndent()

    private val PACKAGE_RELATIONSHIPS = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1"
            Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
            Target="word/document.xml"/>
          <Relationship Id="rId2"
            Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties"
            Target="docProps/core.xml"/>
        </Relationships>
    """.trimIndent()

    /** Nine levels each of bullets and numbers, which is as deep as Word itself goes. */
    private val NUMBERING_XML: String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<w:numbering xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">")

        for ((abstractId, ordered) in listOf(0 to false, 1 to true)) {
            append("<w:abstractNum w:abstractNumId=\"$abstractId\">")
            for (level in 0..8) {
                val format = if (ordered) {
                    listOf("decimal", "lowerLetter", "lowerRoman")[level % 3]
                } else {
                    "bullet"
                }
                val text = if (ordered) "%${level + 1}." else listOf("•", "◦", "▪")[level % 3]
                append(
                    "<w:lvl w:ilvl=\"$level\"><w:start w:val=\"1\"/>" +
                        "<w:numFmt w:val=\"$format\"/><w:lvlText w:val=\"$text\"/>" +
                        "<w:lvlJc w:val=\"left\"/>" +
                        "<w:pPr><w:ind w:left=\"${(level + 1) * 360}\" w:hanging=\"360\"/></w:pPr></w:lvl>"
                )
            }
            append("</w:abstractNum>")
        }
        append("<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>")
        append("<w:num w:numId=\"2\"><w:abstractNumId w:val=\"1\"/></w:num>")
        append("</w:numbering>")
    }

    private val EPUB_CONTAINER = """
        <?xml version="1.0" encoding="UTF-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
    """.trimIndent()

    private fun packageDocument(title: String, identifier: String, language: String, modified: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:identifier id="book-id">$identifier</dc:identifier>
            <dc:title>${escape(title)}</dc:title>
            <dc:language>$language</dc:language>
            <meta property="dcterms:modified">$modified</meta>
          </metadata>
          <manifest>
            <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            <item id="chapter" href="text/chapter.xhtml" media-type="application/xhtml+xml"/>
            <item id="style" href="styles/style.css" media-type="text/css"/>
          </manifest>
          <spine>
            <itemref idref="chapter"/>
          </spine>
        </package>
    """.trimIndent()

    private fun navigationDocument(title: String, headings: List<Pair<Int, String>>): String {
        val items = headings.ifEmpty { listOf(1 to title) }.joinToString("\n") { (_, text) ->
            "<li><a href=\"text/chapter.xhtml\">${escape(text)}</a></li>"
        }

        return part(
            """<?xml version="1.0" encoding="UTF-8"?>""",
            "<!DOCTYPE html>",
            """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">""",
            """<head><meta charset="utf-8" /><title>${escape(title)}</title></head>""",
            "<body>",
            """<nav epub:type="toc" id="toc">""",
            "<h1>Contents</h1>",
            "<ol>",
            items,
            "</ol>",
            "</nav>",
            "</body>",
            "</html>",
        )
    }

    private val EPUB_STYLESHEET = """
        body { font-family: serif; line-height: 1.6; margin: 1em; }
        h1, h2, h3, h4, h5, h6 { font-family: sans-serif; line-height: 1.25; }
        pre { background: #f5f5f5; padding: 0.6em; overflow-x: auto; font-size: 0.9em; }
        code { font-family: monospace; }
        blockquote { margin-left: 1em; padding-left: 0.8em; border-left: 3px solid #ccc; color: #444; }
        table { border-collapse: collapse; }
        td, th { border: 1px solid #bbb; padding: 0.3em 0.6em; }
        img { max-width: 100%; }
    """.trimIndent()

    // ------------------------------------------------------------------ helpers

    private fun isBlock(node: HtmlNode): Boolean =
        node is HtmlNode.Element && node.tag in BLOCK_TAGS

    /** A table's rows, wherever `thead`/`tbody` put them. */
    private fun rowsOf(table: HtmlNode.Element): List<HtmlNode.Element> = buildList {
        fun walk(nodes: List<HtmlNode>) {
            for (node in nodes) {
                if (node !is HtmlNode.Element) continue
                if (node.tag == "tr") add(node) else walk(node.children)
            }
        }
        walk(table.children)
    }

    private fun escape(text: String): String = buildString(text.length) {
        for (character in text) {
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                // XML 1.0 forbids most control characters outright; a stray one makes the whole
                // file unopenable, which is a high price for a byte nobody can see.
                '\t', '\n', '\r' -> append(character)
                in '\u0000'..'\u001F', in '\u007F'..'\u009F' -> Unit
                else -> append(character)
            }
        }
    }

    /** A tiny zip builder, so the two writers above read as a list of parts. */
    private class ZipBuilder(private val stream: ZipOutputStream) {
        fun entry(name: String, content: String) {
            stream.putNextEntry(ZipEntry(name))
            stream.write(content.toByteArray(StandardCharsets.UTF_8))
            stream.closeEntry()
        }

        /**
         * Writes an entry with no compression.
         *
         * Only EPUB's `mimetype` needs this, and it needs it absolutely: a reader identifies the
         * file by reading those bytes straight out of the archive at a fixed offset.
         */
        fun storedEntry(name: String, content: String) {
            val bytes = content.toByteArray(StandardCharsets.UTF_8)
            val entry = ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                crc = CRC32().apply { update(bytes) }.value
            }
            stream.putNextEntry(entry)
            stream.write(bytes)
            stream.closeEntry()
        }
    }

    private fun zip(target: Path, build: ZipBuilder.() -> Unit) {
        target.parent?.let { java.nio.file.Files.createDirectories(it) }
        ZipOutputStream(target.outputStream()).use { stream ->
            ZipBuilder(stream).build()
        }
    }

    /** The same builders, into memory, which is what the tests read. */
    internal fun docxBytes(title: String, nodes: List<HtmlNode>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { stream ->
            val builder = ZipBuilder(stream)
            val body = DocxWriter().apply { blocks(nodes) }
            builder.entry("[Content_Types].xml", CONTENT_TYPES)
            builder.entry("_rels/.rels", PACKAGE_RELATIONSHIPS)
            builder.entry("word/document.xml", documentXml(body.out.toString()))
            builder.entry("word/styles.xml", stylesXml())
            builder.entry("word/numbering.xml", NUMBERING_XML)
            builder.entry("word/_rels/document.xml.rels", documentRelationships(body.relationships))
            builder.entry("docProps/core.xml", coreProperties(title))
        }
        return buffer.toByteArray()
    }
}
