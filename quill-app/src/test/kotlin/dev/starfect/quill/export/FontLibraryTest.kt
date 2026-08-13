package dev.starfect.quill.export

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.streams.toList
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Choosing a font, and reading the kind of file a system font directory actually holds.
 *
 * The ordering tests take file *names* rather than the machine's fonts, on purpose. A test that
 * asserts "the body font is not monospace" passes or fails depending on what the runner has
 * installed, which makes it a test of the image rather than of the code; the ranking is a pure
 * function of a name, so it can be pinned exactly.
 */
class FontLibraryTest {

    // ------------------------------------------------------------------ ordering

    @Test
    fun `body text does not get a monospace face`() {
        // The bug this pins: "dejavusans" is a prefix of "dejavusansmono", so a family-name match
        // alone ranked DejaVuSansMono-Bold above DejaVuSans and set every exported document in bold
        // monospace. It looked deliberate, which is why nobody would have reported it as a bug.
        assertTrue(
            FontLibrary.rank("DejaVuSans.ttf", monospace = false) <
                FontLibrary.rank("DejaVuSansMono-Bold.ttf", monospace = false),
        )
    }

    @Test
    fun `code gets a monospace face even from another family`() {
        assertTrue(
            FontLibrary.rank("JetBrainsMono-Regular.ttf", monospace = true) <
                FontLibrary.rank("DejaVuSans.ttf", monospace = true),
        )
    }

    @Test
    fun `the wrong width loses to the wrong family`() {
        // Width beats family: a proportional document set in a monospace font is unmistakably
        // wrong, while the same document in a different but reasonable family is merely not the
        // first choice.
        assertTrue(
            FontLibrary.rank("LiberationSans-Regular.ttf", monospace = false) <
                FontLibrary.rank("DejaVuSansMono.ttf", monospace = false),
        )
    }

    @Test
    fun `the regular face outranks the bold one beside it`() {
        assertTrue(
            FontLibrary.rank("LiberationSans-Regular.ttf", monospace = false) <
                FontLibrary.rank("LiberationSans-Bold.ttf", monospace = false),
        )
    }

    @Test
    fun `a text face is tried before a font that also covers CJK`() {
        // Coverage, not the name list, is what routes a Korean document to a Korean font -- so the
        // list is free to put the prose faces first, and has to. A CJK family ranked first would be
        // chosen for English too, because it covers Latin as well; its Latin glyphs are an
        // afterthought, and a reader sees that immediately.
        assertTrue(
            FontLibrary.rank("DejaVuSans.ttf", monospace = false) <
                FontLibrary.rank("wqy-zenhei.ttc", monospace = false),
        )
    }

    @Test
    fun `a Korean family is tried before a font nobody named`() {
        // What the list does earn: when no Latin font can draw the document, the search reaches a
        // Korean face before whatever else happens to be installed.
        assertTrue(
            FontLibrary.rank("malgun.ttf", monospace = false) <
                FontLibrary.rank("Loma.otf", monospace = false),
        )
    }

    // ------------------------------------------------------------------ reading

    @Test
    fun `coverage is answered without loading the font`() {
        val font = anyReadableFont() ?: return
        val ascii = "Hello".codePoints().toArray()

        // The cheap probe and the full parse have to agree, or choosing a font by the probe and
        // then embedding what the parse produced would quietly disagree about what is in the file.
        val probed = TrueTypeFont.coverage(font, ascii)
        val loaded = TrueTypeFont.load(font)
        assertNotNull(loaded)
        assertTrue(probed >= 0, "$font could not be probed")
        assertTrue(probed == ascii.count(loaded::covers), "the probe and the parse disagree about $font")
    }

    @Test
    fun `a font taken out of a collection is a standalone font file`() {
        // macOS keeps its CJK families as collections, so this is the path a Korean export on a Mac
        // takes. Embedding the collection whole produces a PDF that will not open, and the failure
        // surfaces in a reader rather than here -- which is why it is asserted on the bytes.
        val collection = anyCollection() ?: return
        val font = TrueTypeFont.load(collection)
        assertNotNull(font, "$collection is a collection this cannot read")

        val tag = String(font.program, 0, 4, StandardCharsets.ISO_8859_1)
        assertTrue(tag != "ttcf", "the embedded program is still a collection, which no reader accepts")

        // And it has to be readable as what it now claims to be.
        val reparsed = TrueTypeFont.parse(font.program)
        assertNotNull(reparsed, "the extracted font does not parse back")
        assertTrue(reparsed.glyphs == font.glyphs, "the extracted font lost glyphs")
        assertTrue(reparsed.covers('A'.code) == font.covers('A'.code), "the extracted font lost its character map")
    }

    // ------------------------------------------------------------------ fixtures

    private fun fontFiles(): List<Path> {
        val roots = listOfNotNull(
            System.getProperty("java.home")?.let { Path.of(it, "lib", "fonts") },
            Path.of("/usr/share/fonts"),
            Path.of("/System/Library/Fonts"),
            System.getenv("WINDIR")?.let { Path.of(it, "Fonts") },
        ).filter { Files.isDirectory(it) }

        return roots.flatMap { root ->
            runCatching {
                Files.walk(root, 3).use { stream ->
                    stream.filter { it.extension.lowercase() in setOf("ttf", "otf", "ttc", "otc") }.toList()
                }
            }.getOrDefault(emptyList())
        }
    }

    /** Skipped rather than failed on a machine with no fonts at all, which is a legitimate one. */
    private fun anyReadableFont(): Path? = fontFiles().firstOrNull { TrueTypeFont.load(it) != null }

    private fun anyCollection(): Path? = fontFiles()
        .filter { it.extension.lowercase() in setOf("ttc", "otc") }
        .firstOrNull { TrueTypeFont.load(it) != null }
}
