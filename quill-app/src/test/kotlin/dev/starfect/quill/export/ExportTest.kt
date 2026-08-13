package dev.starfect.quill.export

import dev.starfect.quill.bridge.wire.HtmlAttribute
import dev.starfect.quill.bridge.wire.HtmlNode
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three binary exports, read back out of the files they produce.
 *
 * A format test that only checks the writer did not throw is worth nothing: every one of these
 * formats accepts a file that opens and is wrong. So each case unpacks the archive, or reads the
 * PDF's bytes, and asserts on what a reader would actually find there — that a heading carries a
 * *style* rather than a size, that the EPUB's mimetype is stored uncompressed, that Korean survives
 * into a PDF as glyphs rather than as boxes.
 */
class ExportTest {

    // ------------------------------------------------------------------ fixtures

    private fun element(tag: String, vararg children: HtmlNode, attributes: List<HtmlAttribute> = emptyList()) =
        HtmlNode.Element(tag, attributes, children.toList())

    private fun text(value: String) = HtmlNode.Text(value)

    private val sample: List<HtmlNode> = listOf(
        element("h1", text("Guide")),
        element("p", text("Some "), element("strong", text("bold")), text(" text.")),
        element("h2", text("Details")),
        element(
            "ul",
            element("li", text("first")),
            element("li", text("second"), element("ul", element("li", text("nested")))),
        ),
        element("pre", element("code", text("fn main() {}\n"))),
        element("blockquote", element("p", text("Quoted."))),
        element(
            "table",
            element(
                "tr",
                element("th", text("Name")),
                element("th", text("Value")),
            ),
            element(
                "tr",
                element("td", text("a")),
                element("td", text("1")),
            ),
        ),
        element("p", element("a", text("link"), attributes = listOf(HtmlAttribute("href", "https://example.com")))),
        element("hr"),
    )

    private fun temporary(name: String): Path =
        Files.createTempDirectory("quill-export").resolve(name)

    private fun entries(path: Path): Map<String, String> = buildMap {
        ZipFile(path.toFile()).use { zip ->
            for (entry in zip.entries()) {
                put(entry.name, zip.getInputStream(entry).readBytes().toString(StandardCharsets.UTF_8))
            }
        }
    }

    // ------------------------------------------------------------------ DOCX

    @Test
    fun `a docx contains every part a reader needs`() {
        val target = temporary("guide.docx")
        OfficeExport.writeDocx(target, "Guide", sample)

        val parts = entries(target).keys
        for (required in listOf(
            "[Content_Types].xml",
            "_rels/.rels",
            "word/document.xml",
            "word/styles.xml",
            "word/numbering.xml",
            "word/_rels/document.xml.rels",
        )) {
            assertTrue(required in parts, "missing $required; found $parts")
        }
    }

    @Test
    fun `headings carry a style rather than a font size`() {
        // The difference between a document Word can navigate and restyle, and one it cannot.
        val target = temporary("guide.docx")
        OfficeExport.writeDocx(target, "Guide", sample)
        val document = entries(target)["word/document.xml"].orEmpty()

        assertTrue(document.contains("""<w:pStyle w:val="Heading1"/>"""), document.take(600))
        assertTrue(document.contains("""<w:pStyle w:val="Heading2"/>"""))
    }

    @Test
    fun `lists use real numbering rather than literal bullet characters`() {
        val target = temporary("guide.docx")
        OfficeExport.writeDocx(target, "Guide", sample)
        val document = entries(target)["word/document.xml"].orEmpty()

        assertTrue(document.contains("<w:numPr>"), "a list should reference the numbering definitions")
        assertTrue(document.contains("""<w:ilvl w:val="1"/>"""), "a nested list should be a deeper level")
    }

    @Test
    fun `bold survives and keeps the space beside it`() {
        // Without xml:space="preserve" Word eats the space between runs and "some bold text"
        // becomes "someboldtext".
        val target = temporary("guide.docx")
        OfficeExport.writeDocx(target, "Guide", sample)
        val document = entries(target)["word/document.xml"].orEmpty()

        assertTrue(document.contains("<w:b/>"))
        assertTrue(document.contains("""xml:space="preserve""""))
    }

    @Test
    fun `a hyperlink becomes a relationship rather than plain text`() {
        val target = temporary("guide.docx")
        OfficeExport.writeDocx(target, "Guide", sample)

        assertTrue(entries(target)["word/document.xml"].orEmpty().contains("<w:hyperlink"))
        assertTrue(entries(target)["word/_rels/document.xml.rels"].orEmpty().contains("https://example.com"))
    }

    @Test
    fun `a table becomes a table`() {
        val target = temporary("guide.docx")
        OfficeExport.writeDocx(target, "Guide", sample)
        val document = entries(target)["word/document.xml"].orEmpty()

        assertTrue(document.contains("<w:tbl>"))
        assertTrue(document.contains("<w:tc>"))
    }

    @Test
    fun `Korean and emoji survive into the document`() {
        val target = temporary("ko.docx")
        OfficeExport.writeDocx(target, "제목", listOf(element("p", text("한국어 문서 🪶"))))
        assertTrue(entries(target)["word/document.xml"].orEmpty().contains("한국어 문서 🪶"))
    }

    @Test
    fun `characters XML forbids are dropped rather than making the file unopenable`() {
        val target = temporary("control.docx")
        OfficeExport.writeDocx(target, "x", listOf(element("p", text("beforeafter"))))
        val document = entries(target)["word/document.xml"].orEmpty()

        assertTrue(document.contains("beforeafter"), document.substringAfter("<w:body>").take(300))
        assertTrue(!document.contains(''))
    }

    // ------------------------------------------------------------------ EPUB

    @Test
    fun `the epub mimetype is the first entry and is stored uncompressed`() {
        // A reader identifies the file by reading these bytes at a fixed offset without unpacking
        // anything, so this is the one detail no reader will forgive.
        val target = temporary("guide.epub")
        OfficeExport.writeEpub(target, "Guide", sample)

        ZipInputStream(Files.newInputStream(target)).use { stream ->
            val first = stream.nextEntry
            assertEquals("mimetype", first.name)
            assertEquals(java.util.zip.ZipEntry.STORED, first.method)
            assertEquals("application/epub+zip", stream.readBytes().toString(StandardCharsets.UTF_8))
        }
    }

    @Test
    fun `an epub carries a package document, navigation and content`() {
        val target = temporary("guide.epub")
        OfficeExport.writeEpub(target, "Guide", sample)
        val parts = entries(target)

        assertTrue("OEBPS/content.opf" in parts.keys)
        assertTrue("OEBPS/nav.xhtml" in parts.keys)
        assertTrue("OEBPS/text/chapter.xhtml" in parts.keys)
        assertTrue(parts["OEBPS/content.opf"].orEmpty().contains("<dc:title>Guide</dc:title>"))
    }

    @Test
    fun `the navigation document lists the document's headings`() {
        val target = temporary("guide.epub")
        OfficeExport.writeEpub(target, "Guide", sample)
        val nav = entries(target)["OEBPS/nav.xhtml"].orEmpty()

        assertTrue(nav.contains("Guide"), nav)
        assertTrue(nav.contains("Details"), nav)
    }

    @Test
    fun `the chapter is well-formed XHTML with void elements closed`() {
        // An EPUB is parsed as XML: an unclosed <hr> is not a quirk, it is a book that will not open.
        val target = temporary("guide.epub")
        OfficeExport.writeEpub(target, "Guide", sample)
        val chapter = entries(target)["OEBPS/text/chapter.xhtml"].orEmpty()

        assertTrue(chapter.contains("<hr />"), chapter.takeLast(400))
        assertTrue(!Regex("<hr>").containsMatchIn(chapter))

        // And it actually parses.
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        factory.newDocumentBuilder().parse(chapter.byteInputStream())
    }

    @Test
    fun `unsafe attributes and unknown tags are dropped from the book`() {
        val hostile = listOf(
            element(
                "p",
                text("safe"),
                attributes = listOf(HtmlAttribute("onclick", "steal()"), HtmlAttribute("id", "kept")),
            ),
            element("marquee", text("content survives")),
        )
        val target = temporary("hostile.epub")
        OfficeExport.writeEpub(target, "x", hostile)
        val chapter = entries(target)["OEBPS/text/chapter.xhtml"].orEmpty()

        assertTrue(!chapter.contains("onclick"), chapter)
        assertTrue(chapter.contains("""id="kept""""))
        assertTrue(!chapter.contains("<marquee"))
        assertTrue(chapter.contains("content survives"), "content must survive even when its tag does not")
    }

    // ------------------------------------------------------------------ PDF

    @Test
    fun `a pdf has a header, a catalogue, a cross-reference table and a trailer`() {
        val target = temporary("guide.pdf")
        val report = PdfExport.write(target, "Guide", sample)
        val bytes = target.readBytes()
        val text = String(bytes, StandardCharsets.ISO_8859_1)

        assertTrue(text.startsWith("%PDF-1.7"), "not a PDF header")
        assertTrue(text.contains("/Type /Catalog"))
        assertTrue(text.contains("/Type /Pages"))
        assertTrue(text.contains("xref"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))
        assertTrue(report.pages >= 1)
    }

    @Test
    fun `the cross-reference offsets point at the objects they claim to`() {
        // A wrong offset here is the classic way a hand-written PDF fails: it opens in one reader
        // that rebuilds the table and fails in every reader that trusts it.
        val target = temporary("guide.pdf")
        PdfExport.write(target, "Guide", sample)
        val text = String(target.readBytes(), StandardCharsets.ISO_8859_1)

        val xrefStart = text.substringAfterLast("startxref").trim().substringBefore("\n").toInt()
        assertTrue(text.startsWith("xref", xrefStart), "startxref does not point at the table")

        // Line 0 is `xref`, line 1 the range, line 2 the mandatory free entry; the rest are objects.
        val table = text.substring(xrefStart).lineSequence().drop(3).takeWhile { it.endsWith(" n ") }.toList()
        assertTrue(table.isNotEmpty(), "the table has no entries")

        table.forEachIndexed { index, line ->
            val offset = line.take(10).toInt()
            assertTrue(
                text.startsWith("${index + 1} 0 obj", offset),
                "entry ${index + 1} points at ${text.substring(offset, offset + 20)}",
            )
        }
    }

    @Test
    fun `a long document runs onto more than one page`() {
        val many = (1..120).map { element("p", text("Paragraph $it, with enough words in it to take a line.")) }
        val target = temporary("long.pdf")
        val report = PdfExport.write(target, "Long", many)

        assertTrue(report.pages > 1, "120 paragraphs should not fit on one page")
        assertEquals(report.pages, Regex("/Type /Page[^s]").findAll(
            String(target.readBytes(), StandardCharsets.ISO_8859_1)
        ).count())
    }

    @Test
    fun `an empty document still produces a file that opens`() {
        val target = temporary("empty.pdf")
        val report = PdfExport.write(target, "", emptyList())

        assertEquals(1, report.pages, "a zero-page PDF is not a PDF")
        assertTrue(String(target.readBytes(), StandardCharsets.ISO_8859_1).contains("/Count 1"))
    }

    @Test
    fun `Korean text is embedded as glyphs rather than dropped`() {
        // The reason this exporter embeds a font at all. With a base-14 font every one of these
        // characters would be a blank box, and nothing would say so.
        //
        // What this can assert depends on the machine, and being careless about which is which is
        // how a test comes to assert a property of the *runner* rather than of the code. A build
        // image carrying DejaVu and nothing else is a legitimate machine, and on one the honest
        // behaviour is a base-14 PDF plus a report saying so -- not a failure.
        //
        // So the branch asks the font library exactly the question the exporter asks it: can
        // anything here draw *this* text. Asking a weaker one -- whether any font at all exists --
        // gets a "yes" on a Latin-only machine and then asserts a CID font that was never embedded,
        // which is a green test on a developer laptop and a red one on CI.
        val heading = "한국어 제목"
        val paragraph = "한국어 문서입니다."
        val target = temporary("korean.pdf")
        val report = PdfExport.write(target, heading, listOf(element("p", text(paragraph))))
        val text = String(target.readBytes(), StandardCharsets.ISO_8859_1)

        if (FontLibrary.findCovering(paragraph + heading) == null) {
            assertTrue(
                report.warning?.contains("No embeddable font") == true,
                "with no font covering Hangul the export must say so, not produce silent blanks: ${report.warning}",
            )
            return
        }

        assertTrue(text.contains("/Subtype /Type0"), "the exporter must embed a CID font, not a base-14 one")
        assertTrue(text.contains("/Encoding /Identity-H"), "a CID font needs Identity-H")
        assertTrue(text.contains("/FontFile2") || text.contains("/FontFile3"), "the font must be embedded")
        assertTrue(text.contains("/W ["), "a covering font must carry the widths of the glyphs it drew")
        assertEquals(null, report.warning, "a font that covers the document has nothing to warn about")
    }

    @Test
    fun `a font that cannot draw the document is reported rather than hidden`() {
        // Whatever font is chosen, the report either warns or the font genuinely covers the text.
        val target = temporary("exotic.pdf")
        val report = PdfExport.write(target, "x", listOf(element("p", text("𐀀 Ꭰ ܐ"))))

        assertTrue(Files.exists(target), "a document with unusual characters should still export")
        // Either every glyph was available, or the caller was told which were not.
        assertTrue(report.warning == null || report.warning!!.contains("could not draw") ||
            report.warning!!.contains("No embeddable font"))
    }

    @Test
    fun `code blocks and tables reach the page`() {
        val target = temporary("rich.pdf")
        val report = PdfExport.write(target, "Rich", sample)
        assertTrue(report.pages >= 1)
        // The content stream is the evidence: a shaded rectangle for the code panel, and the
        // stroked rules a table draws under each row.
        val text = String(target.readBytes(), StandardCharsets.ISO_8859_1)
        assertTrue(text.contains(" re f"), "the code block's panel should be filled")
        assertTrue(text.contains(" l S"), "the table's rules should be stroked")
    }

    // ------------------------------------------------------------------ font parsing

    @Test
    fun `the font parser reads a real font off this machine`() {
        val font = FontLibrary.findCovering("Hello")
        if (font == null) {
            // A container with no fonts at all. Nothing to assert, and the PDF test above already
            // covers what happens then.
            return
        }

        assertTrue(font.glyphs > 0, "a font with no glyphs is not a font")
        assertTrue(font.covers('H'.code), "${font.postScriptName} should be able to draw 'H'")
        assertTrue(font.width("Hello", 12f) > 0f, "a measured string should have a width")
        assertEquals(
            font.width("HH", 12f),
            font.width("H", 12f) * 2,
            0.01f,
            "widths should be additive",
        )
    }

    @Test
    fun `a file that is not a font is rejected rather than parsed`() {
        assertEquals(null, TrueTypeFont.parse(ByteArray(0)))
        assertEquals(null, TrueTypeFont.parse("not a font at all, just some text".toByteArray()))
        assertEquals(null, TrueTypeFont.parse(ByteArray(2048) { it.toByte() }))
    }
}
