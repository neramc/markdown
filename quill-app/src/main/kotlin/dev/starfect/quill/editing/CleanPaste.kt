package dev.starfect.quill.editing

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString

/**
 * Pasting from anywhere, and having it arrive as Markdown somebody would have written.
 *
 * The clipboard is not one thing. Copy a passage out of a web page and it carries plain text *and*
 * an HTML fragment *and*, sometimes, an image. Every one of those is a different document, and the
 * one an editor picks decides whether the paste keeps its headings and its table or arrives as a
 * wall of unstructured prose. The plain-text flavour is the one a naive paste takes, and it is the
 * only flavour from which the structure has already been thrown away.
 *
 * So the order below is deliberate, and it is the whole design:
 *
 * 1. **Files**, because dropping or pasting a file is unambiguous — an image is filed and linked,
 *    anything else is linked where it lies.
 * 2. **HTML**, because it is the only flavour that still knows what was a heading and what was a
 *    list. This is what makes a paste from Word, Notion, Google Docs, Confluence, a wiki or a
 *    rendered README land as source rather than as soup.
 * 3. **An image**, because a screenshot on the clipboard has no text flavour at all and is meant to
 *    become a file next to the document.
 * 4. **Plain text**, which is both the fallback and the right answer when somebody has copied code
 *    or Markdown they wrote themselves — converting that would be the editor second-guessing them.
 *
 * A note on Hangul word processors: Hancom Office puts an HTML flavour on the clipboard like every
 * other word processor, so it goes through path 2 with everything else. A `.hwp` *file* is a
 * different matter — it is a binary container this editor cannot read, so dropping one links to it
 * rather than pretending to import it.
 */
public object CleanPaste {

    /** What a paste turned into. */
    public data class PasteResult(
        /** The Markdown to insert. */
        val markdown: String,
        /** How it was read, for the status line. */
        val source: Source,
        /** Files written on the way, so the caller can report them. */
        val writtenFiles: List<Path> = emptyList(),
    )

    /** Which clipboard flavour a paste came from. */
    public enum class Source(public val description: String) {
        FILES("files"),
        HTML("formatted text"),
        IMAGE("an image"),
        PLAIN_TEXT("plain text"),
        NOTHING("nothing"),
    }

    /**
     * Converts whatever is on the clipboard into Markdown.
     *
     * @param transferable the clipboard contents.
     * @param convertHtml the HTML-to-Markdown conversion, which the engine provides.
     * @param assetDirectory where pasted images are filed, or null when the document has never been
     *   saved and so has no directory to file them beside.
     * @param linkBase the directory links are written relative to, which is the document's own.
     */
    public fun convert(
        transferable: Transferable?,
        convertHtml: (String) -> String,
        assetDirectory: Path?,
        linkBase: Path?,
        now: LocalDateTime = LocalDateTime.now(),
    ): PasteResult {
        if (transferable == null) return PasteResult("", Source.NOTHING)

        readFiles(transferable)?.let { files ->
            return fromFiles(files, assetDirectory, linkBase)
        }

        readHtml(transferable)?.let { html ->
            val markdown = runCatching { convertHtml(html) }.getOrDefault("")
            // An HTML flavour that converts to nothing is not a reason to paste nothing: a
            // clipboard whose markup is entirely styling still has its text flavour.
            if (markdown.isNotBlank()) {
                return PasteResult(markdown.trimEnd() + "\n", Source.HTML)
            }
        }

        readImage(transferable)?.let { image ->
            return fromImage(image, assetDirectory, linkBase, now)
        }

        readPlainText(transferable)?.let { text ->
            if (text.isNotEmpty()) return PasteResult(normaliseNewlines(text), Source.PLAIN_TEXT)
        }

        return PasteResult("", Source.NOTHING)
    }

    /**
     * Inserts pasted Markdown into a value, honouring the one case where the paste means something
     * other than "put these characters here".
     *
     * A URL pasted over selected text is a link. Every editor that writes prose does this, and its
     * absence is felt every time somebody pastes a URL, watches it replace the words they had
     * selected, and undoes it.
     */
    public fun apply(value: TextFieldValue, pasted: String): TextFieldValue {
        val selection = value.selection
        if (!selection.collapsed && isUrl(pasted.trim())) {
            return MarkdownEdits.insertLink(value, pasted.trim())
        }

        // A multi-line paste lands as its own block rather than in the middle of the current line,
        // which is what stops a pasted list from being swallowed by the paragraph it landed in.
        val text = value.text
        val start = selection.min
        val end = selection.max
        val separated = pasted.contains('\n') &&
            start > 0 &&
            text.getOrNull(start - 1) != '\n'
        val insert = if (separated) "\n$pasted" else pasted

        return TextFieldValue(
            text = text.substring(0, start) + insert + text.substring(end),
            selection = TextRange(start + insert.length),
        )
    }

    private fun isUrl(text: String): Boolean =
        text.none { it.isWhitespace() } &&
            (text.startsWith("https://") || text.startsWith("http://")) &&
            text.length > "https://".length

    // ------------------------------------------------------------------ flavours

    /**
     * Reads the clipboard's HTML flavour, whatever shape this platform offers it in.
     *
     * There is no single HTML flavour to ask for. X11 hands over `text/html` as bytes in a charset
     * named on the flavour; Windows offers a String but wraps it in the CF_HTML header; macOS
     * offers both. Rather than guess, every flavour whose MIME type is `text/html` is tried in
     * order of how little decoding it needs.
     */
    internal fun readHtml(transferable: Transferable): String? {
        val flavours = runCatching { transferable.transferDataFlavors.toList() }.getOrDefault(emptyList())
        val htmlFlavours = flavours.filter { it.mimeType.startsWith("text/html") }
            // A String needs no charset guesswork, so it is tried first; a Reader next; bytes last.
            .sortedBy {
                when {
                    it.representationClass == String::class.java -> 0
                    java.io.Reader::class.java.isAssignableFrom(it.representationClass) -> 1
                    else -> 2
                }
            }

        for (flavour in htmlFlavours) {
            val html = runCatching { readAsText(transferable, flavour) }.getOrNull()
            if (!html.isNullOrBlank()) return unwrapFragment(html)
        }
        return null
    }

    private fun readAsText(transferable: Transferable, flavour: DataFlavor): String? =
        when (val data = transferable.getTransferData(flavour)) {
            is String -> data
            is java.io.Reader -> data.use { it.readText() }
            is InputStream -> data.use { stream ->
                InputStreamReader(stream, charsetOf(flavour)).readText()
            }
            is ByteArray -> String(data, charsetOf(flavour))
            else -> null
        }

    private fun charsetOf(flavour: DataFlavor): Charset {
        val named = flavour.getParameter("charset")
        return runCatching { Charset.forName(named) }.getOrDefault(StandardCharsets.UTF_8)
    }

    /**
     * Strips the wrappers a clipboard puts around an HTML fragment.
     *
     * Windows prepends a CF_HTML header of `Key:Value` lines, and every application that writes one
     * also marks the copied region with `<!--StartFragment-->`. Taking the region between the
     * markers is what keeps a paste from carrying the surrounding page's chrome — and, for Word,
     * what keeps a hundred lines of `<style>` out of the document.
     */
    internal fun unwrapFragment(html: String): String {
        val start = html.indexOf(FRAGMENT_START)
        val end = html.lastIndexOf(FRAGMENT_END)
        if (start >= 0 && end > start) {
            return html.substring(start + FRAGMENT_START.length, end)
        }

        // No markers: drop a CF_HTML header if there is one, recognised by its first line.
        if (html.startsWith("Version:")) {
            val body = html.indexOf('<')
            if (body > 0) return html.substring(body)
        }
        return html
    }

    private const val FRAGMENT_START = "<!--StartFragment-->"
    private const val FRAGMENT_END = "<!--EndFragment-->"

    internal fun readPlainText(transferable: Transferable): String? =
        runCatching {
            if (!transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) return@runCatching null
            transferable.getTransferData(DataFlavor.stringFlavor) as? String
        }.getOrNull()

    internal fun readImage(transferable: Transferable): BufferedImage? =
        runCatching {
            if (!transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) return@runCatching null
            (transferable.getTransferData(DataFlavor.imageFlavor) as? Image)?.let(::toBufferedImage)
        }.getOrNull()

    @Suppress("UNCHECKED_CAST")
    internal fun readFiles(transferable: Transferable): List<Path>? =
        runCatching {
            if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return@runCatching null
            (transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>)
                ?.map(File::toPath)
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()

    // ------------------------------------------------------------------ payloads

    /**
     * Links dropped or pasted files.
     *
     * An image is embedded, because that is always what was meant. Anything else is linked: a `.md`
     * next to this one, a PDF, a spreadsheet. A file outside the document's own directory is copied
     * in first — a link to somewhere else on the author's disk is a link that works exactly once,
     * on their machine.
     */
    private fun fromFiles(files: List<Path>, assetDirectory: Path?, linkBase: Path?): PasteResult {
        val written = mutableListOf<Path>()
        val links = files.map { source ->
            val isImage = source.extension.lowercase() in IMAGE_EXTENSIONS
            val target = if (assetDirectory != null && !isInside(source, linkBase)) {
                copyInto(assetDirectory, source)?.also(written::add) ?: source
            } else {
                source
            }
            val reference = relativeLink(target, linkBase)
            val label = source.fileName?.toString().orEmpty()
            if (isImage) "![$label]($reference)" else "[$label]($reference)"
        }
        return PasteResult(links.joinToString("\n") + "\n", Source.FILES, written)
    }

    /**
     * Files a pasted image beside the document and links it.
     *
     * A screenshot has no name, so it gets one from the moment it was pasted: sortable, unique
     * without a counter, and meaningful when the folder is opened a year later.
     */
    private fun fromImage(
        image: BufferedImage,
        assetDirectory: Path?,
        linkBase: Path?,
        now: LocalDateTime,
    ): PasteResult {
        if (assetDirectory == null) {
            return PasteResult("", Source.IMAGE)
        }
        val name = "pasted-${TIMESTAMP.format(now)}.png"
        val target = uniqueName(assetDirectory, name)
        return try {
            assetDirectory.createDirectories()
            ImageIO.write(image, "png", target.toFile())
            PasteResult("![](${relativeLink(target, linkBase)})\n", Source.IMAGE, listOf(target))
        } catch (failure: java.io.IOException) {
            // The paste is lost either way; saying so beats inserting a link to a file that is not
            // there.
            PasteResult("", Source.IMAGE)
        }
    }

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "avif")

    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** Where images pasted into a document are filed. */
    public fun assetDirectoryFor(documentPath: Path?): Path? = documentPath?.parent?.resolve("assets")

    private fun isInside(candidate: Path, root: Path?): Boolean {
        if (root == null) return false
        return runCatching { candidate.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()) }
            .getOrDefault(false)
    }

    private fun copyInto(directory: Path, source: Path): Path? = runCatching {
        directory.createDirectories()
        val target = uniqueName(directory, source.fileName.toString())
        Files.copy(source, target)
        target
    }.getOrNull()

    /** A name that is not already taken, by suffixing `-2`, `-3` and so on. */
    private fun uniqueName(directory: Path, name: String): Path {
        val candidate = directory.resolve(name)
        if (!candidate.exists()) return candidate

        val stem = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        for (index in 2..MAX_NAME_ATTEMPTS) {
            val suffixed = if (extension.isEmpty()) "$stem-$index" else "$stem-$index.$extension"
            val next = directory.resolve(suffixed)
            if (!next.exists()) return next
        }
        return candidate
    }

    private const val MAX_NAME_ATTEMPTS = 1000

    /**
     * A link from the document to a file.
     *
     * Relative where possible, because a document and its images move together and an absolute path
     * survives neither the move nor being read by anybody else.
     */
    internal fun relativeLink(target: Path, base: Path?): String {
        val text = if (base == null) {
            target.toAbsolutePath().invariantSeparatorsPathString
        } else {
            runCatching {
                base.toAbsolutePath().normalize()
                    .relativize(target.toAbsolutePath().normalize())
                    .invariantSeparatorsPathString
            }.getOrElse { target.toAbsolutePath().invariantSeparatorsPathString }
        }
        // A destination with spaces has to be wrapped, or it ends at the first one.
        return if (text.any { it.isWhitespace() }) "<$text>" else text
    }

    private fun normaliseNewlines(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')

    private fun toBufferedImage(image: Image): BufferedImage {
        if (image is BufferedImage) return image
        val width = image.getWidth(null).coerceAtLeast(1)
        val height = image.getHeight(null).coerceAtLeast(1)
        val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = buffered.createGraphics()
        try {
            graphics.drawImage(image, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return buffered
    }
}
