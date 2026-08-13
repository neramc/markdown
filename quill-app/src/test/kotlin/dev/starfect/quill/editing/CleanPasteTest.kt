package dev.starfect.quill.editing

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The paste path, driven by a clipboard rather than by the clipboard.
 *
 * Every case here is one an editor gets wrong by default, and the reason for a fake [Transferable]
 * is that each of them is about the *choice between flavours* — which cannot be exercised at all
 * through a real system clipboard in a headless test, and which is where all the interesting
 * behaviour lives.
 */
class CleanPasteTest {

    // ------------------------------------------------------------------ fixtures

    /** A clipboard offering exactly the flavours it is given, in the order they are given. */
    private class FakeClipboard(private val entries: List<Pair<DataFlavor, Any>>) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> =
            entries.map { it.first }.toTypedArray()

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
            entries.any { it.first.equals(flavor) }

        override fun getTransferData(flavor: DataFlavor): Any =
            entries.first { it.first.equals(flavor) }.second
    }

    private val htmlStringFlavor = DataFlavor("text/html;class=java.lang.String")
    private val htmlStreamFlavor = DataFlavor("text/html;class=java.io.InputStream;charset=UTF-8")

    private fun clipboard(vararg entries: Pair<DataFlavor, Any>) = FakeClipboard(entries.toList())

    /** Stands in for the engine: enough to tell whether the HTML flavour was chosen. */
    private val convertHtml: (String) -> String = { html ->
        "converted<" + html.trim().replace("\n", " ") + ">"
    }

    private fun temporaryDirectory(): Path = Files.createTempDirectory("quill-paste")

    // ------------------------------------------------------------------ flavour choice

    @Test
    fun `formatted text beats the plain text beside it`() {
        // This is the whole point. Both flavours are always present; taking the plain one is what
        // makes a pasted article arrive as a wall of prose.
        val result = CleanPaste.convert(
            clipboard(
                DataFlavor.stringFlavor to "Title\nA paragraph.",
                htmlStringFlavor to "<h1>Title</h1><p>A paragraph.</p>",
            ),
            convertHtml,
            assetDirectory = null,
            linkBase = null,
        )

        assertEquals(CleanPaste.Source.HTML, result.source)
        assertTrue(result.markdown.startsWith("converted<"), result.markdown)
    }

    @Test
    fun `plain text is used when there is no formatted flavour`() {
        val result = CleanPaste.convert(
            clipboard(DataFlavor.stringFlavor to "just text"),
            convertHtml,
            assetDirectory = null,
            linkBase = null,
        )

        assertEquals(CleanPaste.Source.PLAIN_TEXT, result.source)
        assertEquals("just text", result.markdown)
    }

    @Test
    fun `an HTML flavour that converts to nothing falls back to the text`() {
        // A clipboard whose markup is entirely styling is not a reason to paste nothing.
        val result = CleanPaste.convert(
            clipboard(
                DataFlavor.stringFlavor to "fallback",
                htmlStringFlavor to "<span style=\"color:red\"></span>",
            ),
            { "   " },
            assetDirectory = null,
            linkBase = null,
        )

        assertEquals(CleanPaste.Source.PLAIN_TEXT, result.source)
        assertEquals("fallback", result.markdown)
    }

    @Test
    fun `an empty clipboard produces nothing rather than failing`() {
        assertEquals(CleanPaste.Source.NOTHING, CleanPaste.convert(clipboard(), convertHtml, null, null).source)
        assertEquals(CleanPaste.Source.NOTHING, CleanPaste.convert(null, convertHtml, null, null).source)
    }

    @Test
    fun `a flavour that throws is skipped rather than losing the paste`() {
        val hostile = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(htmlStringFlavor, DataFlavor.stringFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = true
            override fun getTransferData(flavor: DataFlavor): Any {
                if (flavor.mimeType.startsWith("text/html")) error("this flavour is a trap")
                return "survived"
            }
        }

        val result = CleanPaste.convert(hostile, convertHtml, null, null)
        assertEquals("survived", result.markdown)
    }

    @Test
    fun `HTML arriving as a byte stream is decoded in its declared charset`() {
        // X11 hands the HTML flavour over as bytes, not as a String.
        val bytes = "<p>한국어</p>".toByteArray(StandardCharsets.UTF_8)
        val result = CleanPaste.convert(
            clipboard(htmlStreamFlavor to ByteArrayInputStream(bytes)),
            convertHtml,
            assetDirectory = null,
            linkBase = null,
        )

        assertEquals(CleanPaste.Source.HTML, result.source)
        assertTrue(result.markdown.contains("한국어"), result.markdown)
    }

    // ------------------------------------------------------------------ fragment unwrapping

    @Test
    fun `the copied region is taken out of the surrounding page`() {
        val clipboardHtml = """
            Version:0.9
            StartHTML:00000097
            EndHTML:00000200
            <html><body><!--StartFragment--><p>only this</p><!--EndFragment--></body></html>
        """.trimIndent()

        assertEquals("<p>only this</p>", CleanPaste.unwrapFragment(clipboardHtml))
    }

    @Test
    fun `a CF_HTML header without fragment markers is still stripped`() {
        val clipboardHtml = "Version:0.9\r\nStartHTML:000000\r\n<p>body</p>"
        assertEquals("<p>body</p>", CleanPaste.unwrapFragment(clipboardHtml))
    }

    @Test
    fun `plain HTML with no wrapper is left alone`() {
        assertEquals("<p>x</p>", CleanPaste.unwrapFragment("<p>x</p>"))
    }

    // ------------------------------------------------------------------ images

    @Test
    fun `a pasted image is filed beside the document and linked relatively`() {
        val directory = temporaryDirectory()
        val assets = directory.resolve("assets")

        val result = CleanPaste.convert(
            clipboard(DataFlavor.imageFlavor to BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)),
            convertHtml,
            assetDirectory = assets,
            linkBase = directory,
            now = LocalDateTime.of(2026, 8, 13, 9, 30, 15),
        )

        assertEquals(CleanPaste.Source.IMAGE, result.source)
        assertEquals("![](assets/pasted-20260813-093015.png)\n", result.markdown)
        assertEquals(1, result.writtenFiles.size)
        assertTrue(result.writtenFiles.single().exists(), "the file the link points at should exist")
    }

    @Test
    fun `a second image pasted in the same second does not overwrite the first`() {
        val directory = temporaryDirectory()
        val assets = directory.resolve("assets")
        val moment = LocalDateTime.of(2026, 8, 13, 9, 30, 15)
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)

        repeat(2) {
            CleanPaste.convert(
                clipboard(DataFlavor.imageFlavor to image),
                convertHtml,
                assetDirectory = assets,
                linkBase = directory,
                now = moment,
            )
        }

        assertEquals(2, assets.listDirectoryEntries().size, "the second paste overwrote the first")
    }

    @Test
    fun `an image pasted into an unsaved document reports that it has nowhere to go`() {
        // Better an empty paste with a message than a link into a directory that does not exist.
        val result = CleanPaste.convert(
            clipboard(DataFlavor.imageFlavor to BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)),
            convertHtml,
            assetDirectory = null,
            linkBase = null,
        )

        assertEquals(CleanPaste.Source.IMAGE, result.source)
        assertEquals("", result.markdown)
    }

    // ------------------------------------------------------------------ files

    @Test
    fun `a dropped image from elsewhere is copied in and linked`() {
        val directory = temporaryDirectory()
        val elsewhere = temporaryDirectory().resolve("photo.png")
        elsewhere.parent.createDirectories()
        ImageIOWrite(elsewhere)

        val result = CleanPaste.convert(
            clipboard(DataFlavor.javaFileListFlavor to listOf(elsewhere.toFile())),
            convertHtml,
            assetDirectory = directory.resolve("assets"),
            linkBase = directory,
        )

        assertEquals(CleanPaste.Source.FILES, result.source)
        assertEquals("![photo.png](assets/photo.png)\n", result.markdown)
        assertTrue(directory.resolve("assets/photo.png").exists(), "the file should have been copied in")
    }

    @Test
    fun `a file already inside the project is linked where it lies`() {
        val directory = temporaryDirectory()
        val inside = directory.resolve("notes.md")
        inside.writeText("# notes\n")

        val result = CleanPaste.convert(
            clipboard(DataFlavor.javaFileListFlavor to listOf(inside.toFile())),
            convertHtml,
            assetDirectory = directory.resolve("assets"),
            linkBase = directory,
        )

        assertEquals("[notes.md](notes.md)\n", result.markdown)
        assertTrue(result.writtenFiles.isEmpty(), "nothing should have been copied")
    }

    @Test
    fun `a non-image file is linked rather than embedded`() {
        val directory = temporaryDirectory()
        val document = directory.resolve("report.hwp")
        document.writeText("binary-ish")

        val result = CleanPaste.convert(
            clipboard(DataFlavor.javaFileListFlavor to listOf(document.toFile())),
            convertHtml,
            assetDirectory = directory.resolve("assets"),
            linkBase = directory,
        )

        assertEquals("[report.hwp](report.hwp)\n", result.markdown)
    }

    @Test
    fun `a link destination containing spaces is wrapped`() {
        val directory = temporaryDirectory()
        val spaced = directory.resolve("my file.png")
        ImageIOWrite(spaced)

        assertEquals("<my file.png>", CleanPaste.relativeLink(spaced, directory))
    }

    // ------------------------------------------------------------------ insertion

    @Test
    fun `a URL pasted over a selection becomes a link around it`() {
        val value = TextFieldValue("see the docs here", TextRange(8, 12))
        val result = CleanPaste.apply(value, "https://example.com")
        assertEquals("see the [docs](https://example.com) here", result.text)
    }

    @Test
    fun `a URL pasted with no selection is inserted as itself`() {
        val value = TextFieldValue("see ", TextRange(4))
        assertEquals("see https://example.com", CleanPaste.apply(value, "https://example.com").text)
    }

    @Test
    fun `a multi-line paste starts on a line of its own`() {
        // Pasting a list into the middle of a sentence otherwise makes the first item part of it.
        val value = TextFieldValue("intro: ", TextRange(7))
        val result = CleanPaste.apply(value, "- one\n- two\n")
        assertEquals("intro: \n- one\n- two\n", result.text)
    }

    @Test
    fun `a multi-line paste at the start of a line is not given another newline`() {
        val value = TextFieldValue("intro\n", TextRange(6))
        assertEquals("intro\n- one\n", CleanPaste.apply(value, "- one\n").text)
    }

    @Test
    fun `pasting over a selection replaces it and leaves the caret after the paste`() {
        val value = TextFieldValue("keep REPLACE keep", TextRange(5, 12))
        val result = CleanPaste.apply(value, "new")
        assertEquals("keep new keep", result.text)
        assertEquals(TextRange(8), result.selection)
    }

    /** Writes a tiny real PNG, so the file has the extension's content as well as its name. */
    private fun ImageIOWrite(target: Path) {
        target.parent?.createDirectories()
        javax.imageio.ImageIO.write(
            BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
            "png",
            target.toFile(),
        )
    }
}
