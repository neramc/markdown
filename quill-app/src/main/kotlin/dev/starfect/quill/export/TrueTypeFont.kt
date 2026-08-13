package dev.starfect.quill.export

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Just enough of a TrueType font to embed one in a PDF.
 *
 * This exists because of one requirement: **the PDF exporter has to be able to write Korean.** A PDF
 * can use fourteen fonts without embedding anything, and all fourteen are Latin — so an exporter
 * built on them produces a file where every Hangul syllable is a blank box. For a Markdown editor
 * whose author writes Korean, that is not a limitation, it is a broken feature.
 *
 * Embedding a font means giving the PDF the font program and telling it, for every glyph it will
 * use, which glyph that is and how wide. So this reads exactly four things out of the file:
 *
 * * `head` — units per em, which every measurement is a fraction of.
 * * `maxp` — how many glyphs there are, which bounds everything else.
 * * `hmtx`/`hhea` — each glyph's advance width, so text can be laid out before it is drawn.
 * * `cmap` — which glyph a character maps to, which is the whole point.
 *
 * Everything else a font contains — outlines, hinting, kerning, ligatures — the PDF reader handles
 * itself once the file is embedded. This code never needs to understand a glyph, only to name it.
 *
 * Only the formats that matter are parsed: cmap subtable formats 4 (the Basic Multilingual Plane,
 * which every font has) and 12 (beyond it, which fonts with emoji have). A font offering neither is
 * rejected rather than half-read.
 */
public class TrueTypeFont private constructor(
    /** The font file's bytes, embedded in the PDF as-is. */
    public val program: ByteArray,
    public val postScriptName: String,
    private val unitsPerEm: Int,
    private val glyphCount: Int,
    /** Advance width per glyph, in font units. */
    private val advances: IntArray,
    /** Unicode code point to glyph id. */
    private val characterMap: Map<Int, Int>,
    public val ascender: Int,
    public val descender: Int,
    /** Whether the file is OpenType with PostScript outlines, which PDF names differently. */
    public val isCompactFontFormat: Boolean,
) {

    /** The glyph for a code point, or 0 — the "missing glyph" box every font has at index 0. */
    public fun glyph(codePoint: Int): Int = characterMap[codePoint] ?: 0

    /** Whether the font can draw a code point at all. */
    public fun covers(codePoint: Int): Boolean = characterMap.containsKey(codePoint)

    /** How much of the font's own coordinate system one em is. */
    public val unitsPerEmValue: Int get() = unitsPerEm

    public val glyphs: Int get() = glyphCount

    /** A glyph's advance, in thousandths of an em, which is the unit PDF widths are in. */
    public fun advanceMilli(glyph: Int): Int {
        if (glyph < 0 || glyph >= advances.size) return DEFAULT_ADVANCE_MILLI
        return advances[glyph] * 1000 / unitsPerEm
    }

    /** The width of a string at a point size, in points. */
    public fun width(text: String, size: Float): Float {
        var total = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            total += advanceMilli(glyph(codePoint))
            index += Character.charCount(codePoint)
        }
        return total * size / 1000f
    }

    /**
     * Encodes a string as the two-byte glyph indices an Identity-H PDF font expects.
     *
     * Identity-H means "the character code *is* the glyph id", which is what makes this possible
     * without also writing a `ToUnicode` mapping for legibility — and why [toUnicodeMap] exists, so
     * copying text out of the exported PDF gives back the words rather than glyph numbers.
     */
    public fun encode(text: String): ByteArray {
        val out = ByteArray(text.codePointCount(0, text.length) * 2)
        var position = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val glyph = glyph(codePoint)
            out[position++] = (glyph ushr 8).toByte()
            out[position++] = (glyph and 0xFF).toByte()
            index += Character.charCount(codePoint)
        }
        return out.copyOf(position)
    }

    /** Every (glyph, code point) pair actually used by [texts], for the PDF's `ToUnicode` map. */
    public fun toUnicodeMap(texts: Iterable<String>): List<Pair<Int, Int>> {
        val used = sortedMapOf<Int, Int>()
        for (text in texts) {
            var index = 0
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                val glyph = glyph(codePoint)
                if (glyph != 0) used.putIfAbsent(glyph, codePoint)
                index += Character.charCount(codePoint)
            }
        }
        return used.entries.map { it.key to it.value }
    }

    public companion object {

        /** What an advance is assumed to be when a glyph has none: half an em. */
        private const val DEFAULT_ADVANCE_MILLI = 500

        /**
         * Reads a font file, or returns null when it is not one this can embed.
         *
         * Returning null rather than throwing is deliberate: this is called while walking a font
         * directory, where most of what it finds will be a format it does not read, and that is
         * ordinary rather than exceptional.
         */
        public fun load(path: Path): TrueTypeFont? = runCatching { parse(Files.readAllBytes(path)) }.getOrNull()

        public fun parse(bytes: ByteArray): TrueTypeFont? {
            if (bytes.size < 12) return null
            val reader = Reader(bytes)

            var offset = 0
            val tag = reader.u32(0)
            // A .ttc collection begins with a header pointing at the first real font.
            if (tag == 0x74746366L) {
                if (bytes.size < 16) return null
                offset = reader.u32(12).toInt()
            }

            val version = reader.u32(offset)
            // 0x00010000 is TrueType outlines, 'OTTO' is PostScript outlines, 'true' is Apple's.
            val compact = version == 0x4F54544FL
            if (version != 0x00010000L && !compact && version != 0x74727565L) return null

            val tableCount = reader.u16(offset + 4)
            if (tableCount <= 0 || tableCount > 512) return null

            val tables = HashMap<String, Pair<Int, Int>>(tableCount)
            for (index in 0 until tableCount) {
                val record = offset + 12 + index * 16
                if (record + 16 > bytes.size) return null
                val name = String(bytes, record, 4, Charsets.ISO_8859_1)
                tables[name] = reader.u32(record + 8).toInt() to reader.u32(record + 12).toInt()
            }

            val head = tables["head"]?.first ?: return null
            val unitsPerEm = reader.u16(head + 18)
            if (unitsPerEm == 0) return null
            val indexToLocFormat = reader.i16(head + 50)
            if (indexToLocFormat != 0 && indexToLocFormat != 1) return null

            val maxp = tables["maxp"]?.first ?: return null
            val glyphCount = reader.u16(maxp + 4)
            if (glyphCount == 0) return null

            val hhea = tables["hhea"]?.first ?: return null
            val ascender = reader.i16(hhea + 4)
            val descender = reader.i16(hhea + 6)
            val metricCount = reader.u16(hhea + 34)

            val hmtx = tables["hmtx"]?.first ?: return null
            val advances = IntArray(glyphCount)
            var last = unitsPerEm / 2
            for (glyph in 0 until glyphCount) {
                if (glyph < metricCount) {
                    val at = hmtx + glyph * 4
                    if (at + 2 > bytes.size) break
                    last = reader.u16(at)
                }
                advances[glyph] = last
            }

            val cmap = tables["cmap"]?.first ?: return null
            val characters = readCharacterMap(reader, cmap, bytes.size) ?: return null
            if (characters.isEmpty()) return null

            val name = tables["name"]?.let { readPostScriptName(reader, it.first, bytes) }
                ?: "EmbeddedFont"

            return TrueTypeFont(
                program = bytes,
                postScriptName = name,
                unitsPerEm = unitsPerEm,
                glyphCount = glyphCount,
                advances = advances,
                characterMap = characters,
                ascender = ascender * 1000 / unitsPerEm,
                descender = descender * 1000 / unitsPerEm,
                isCompactFontFormat = compact,
            )
        }

        /**
         * Reads the best available character map.
         *
         * "Best" is a real judgement: a font can carry several, and the Windows Unicode ones are
         * the only ones guaranteed to be Unicode rather than a legacy encoding. Format 12 is
         * preferred over format 4 because it reaches past the Basic Multilingual Plane, which is
         * where the emoji are.
         */
        private fun readCharacterMap(reader: Reader, cmap: Int, size: Int): Map<Int, Int>? {
            val subtableCount = reader.u16(cmap + 2)
            if (subtableCount <= 0 || subtableCount > 64) return null

            var best: Pair<Int, Int>? = null // score to offset
            for (index in 0 until subtableCount) {
                val record = cmap + 4 + index * 8
                if (record + 8 > size) return null
                val platform = reader.u16(record)
                val encoding = reader.u16(record + 2)
                val offset = cmap + reader.u32(record + 4).toInt()
                if (offset + 4 > size) continue

                val format = reader.u16(offset)
                val score = when {
                    platform == 3 && encoding == 10 && format == 12 -> 100
                    platform == 0 && format == 12 -> 95
                    platform == 3 && encoding == 1 && format == 4 -> 90
                    platform == 0 && format == 4 -> 85
                    format == 4 || format == 12 -> 50
                    else -> 0
                }
                if (score > 0 && (best == null || score > best.first)) best = score to offset
            }

            val offset = best?.second ?: return null
            return when (reader.u16(offset)) {
                4 -> readFormat4(reader, offset, size)
                12 -> readFormat12(reader, offset, size)
                else -> null
            }
        }

        /** Format 4: segmented ranges over the Basic Multilingual Plane. */
        private fun readFormat4(reader: Reader, offset: Int, size: Int): Map<Int, Int> {
            val segments = reader.u16(offset + 6) / 2
            val endCodes = offset + 14
            val startCodes = endCodes + segments * 2 + 2
            val deltas = startCodes + segments * 2
            val rangeOffsets = deltas + segments * 2

            val map = HashMap<Int, Int>(segments * 8)
            for (segment in 0 until segments) {
                val end = reader.u16(endCodes + segment * 2)
                val start = reader.u16(startCodes + segment * 2)
                val delta = reader.i16(deltas + segment * 2)
                val rangeOffset = reader.u16(rangeOffsets + segment * 2)
                if (start > end || start == 0xFFFF) continue

                for (character in start..end) {
                    val glyph = if (rangeOffset == 0) {
                        (character + delta) and 0xFFFF
                    } else {
                        val at = rangeOffsets + segment * 2 + rangeOffset + (character - start) * 2
                        if (at + 2 > size) continue
                        val raw = reader.u16(at)
                        if (raw == 0) continue else (raw + delta) and 0xFFFF
                    }
                    if (glyph != 0) map[character] = glyph
                }
            }
            return map
        }

        /** Format 12: grouped ranges over the whole of Unicode. */
        private fun readFormat12(reader: Reader, offset: Int, size: Int): Map<Int, Int> {
            val groups = reader.u32(offset + 12).toInt()
            if (groups <= 0) return emptyMap()

            val map = HashMap<Int, Int>(groups * 8)
            for (group in 0 until groups) {
                val at = offset + 16 + group * 12
                if (at + 12 > size) break
                val start = reader.u32(at).toInt()
                val end = reader.u32(at + 4).toInt()
                val startGlyph = reader.u32(at + 8).toInt()
                if (start > end || end - start > MAX_GROUP_SPAN) continue
                for (character in start..end) {
                    map[character] = startGlyph + (character - start)
                }
            }
            return map
        }

        /** A guard against a corrupt group claiming to span the whole code space. */
        private const val MAX_GROUP_SPAN = 0x20000

        /** The font's PostScript name — name id 6 — which is what the PDF refers to it by. */
        private fun readPostScriptName(reader: Reader, name: Int, bytes: ByteArray): String? {
            val count = reader.u16(name + 2)
            val storage = name + reader.u16(name + 4)

            for (index in 0 until count) {
                val record = name + 6 + index * 12
                if (record + 12 > bytes.size) return null
                if (reader.u16(record + 6) != 6) continue

                val length = reader.u16(record + 8)
                val offset = storage + reader.u16(record + 10)
                if (offset + length > bytes.size) continue

                val platform = reader.u16(record)
                val text = if (platform == 3) {
                    String(bytes, offset, length, Charsets.UTF_16BE)
                } else {
                    String(bytes, offset, length, Charsets.ISO_8859_1)
                }
                // A PostScript name may not contain spaces or the characters PDF uses as delimiters.
                val cleaned = text.filter { it.isLetterOrDigit() || it == '-' || it == '+' }
                if (cleaned.isNotEmpty()) return cleaned
            }
            return null
        }
    }

    /** Big-endian reads, bounds-checked, because a font file is untrusted input. */
    private class Reader(private val bytes: ByteArray) {
        fun u16(at: Int): Int {
            if (at < 0 || at + 2 > bytes.size) return 0
            return ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
        }

        fun i16(at: Int): Int = u16(at).let { if (it >= 0x8000) it - 0x10000 else it }

        fun u32(at: Int): Long {
            if (at < 0 || at + 4 > bytes.size) return 0
            return ((bytes[at].toLong() and 0xFF) shl 24) or
                ((bytes[at + 1].toLong() and 0xFF) shl 16) or
                ((bytes[at + 2].toLong() and 0xFF) shl 8) or
                (bytes[at + 3].toLong() and 0xFF)
        }
    }
}

/**
 * Finding a font on this machine that can draw a particular document.
 *
 * There is no portable way to ask the JVM for the *file* behind a font — `java.awt.Font` will tell
 * you whether it can display a character and will not tell you where it came from — so this walks
 * the places every operating system keeps fonts and reads their character maps directly. That is
 * more work than asking, and it is the only way to get a file that can be embedded.
 *
 * The search is ordered rather than exhaustive: the fonts most likely to cover a given script are
 * tried first, so a Korean document finds a Korean font in a handful of reads instead of parsing
 * every font on the system.
 */
public object FontLibrary {

    /** Where each platform keeps its fonts, plus the runtime's own bundled ones. */
    private val DIRECTORIES: List<Path> = buildList {
        System.getProperty("java.home")?.let { add(Path.of(it, "lib", "fonts")) }
        add(Path.of("/usr/share/fonts"))
        add(Path.of("/usr/local/share/fonts"))
        System.getProperty("user.home")?.let {
            add(Path.of(it, ".fonts"))
            add(Path.of(it, ".local", "share", "fonts"))
            add(Path.of(it, "Library", "Fonts"))
        }
        add(Path.of("/System/Library/Fonts"))
        add(Path.of("/Library/Fonts"))
        System.getenv("WINDIR")?.let { add(Path.of(it, "Fonts")) }
        add(Path.of("C:\\Windows\\Fonts"))
    }

    /**
     * Names worth trying first, in order.
     *
     * The CJK fonts lead because they are the case that fails without this whole mechanism: a Latin
     * font is nearly always found anyway, and a Korean one has to be looked for.
     */
    private val PREFERRED = listOf(
        "notosanscjk", "notoserifcjk", "notosanskr", "sourcehansans", "nanumgothic", "nanummyeongjo",
        "malgun", "applegothic", "applesdgothicneo", "gulim", "batang", "dotum",
        "notosansjp", "notosanssc", "notosanstc", "msgothic", "meiryo", "hiraginosans",
        "dejavusans", "notosans", "liberationsans", "arial", "helvetica", "roboto", "inter",
        "segoeui", "calibri", "verdana", "tahoma",
    )

    private val MONOSPACE_PREFERRED = listOf(
        "jetbrainsmono", "dejavusansmono", "notosansmono", "liberationmono", "consolas",
        "menlo", "monaco", "couriernew", "cousine", "sourcecodepro", "firacode", "d2coding",
    )

    private val EXTENSIONS = setOf("ttf", "otf", "ttc", "otc")

    /**
     * The first font that can draw every character in [sample].
     *
     * @param monospace prefer a fixed-width family, for code blocks.
     * @return null when nothing on the machine covers the text, which the caller has to report
     *   rather than silently produce a document full of empty boxes.
     */
    public fun findCovering(sample: String, monospace: Boolean = false): TrueTypeFont? {
        val required = sample.codePoints().distinct().toArray()
            .filter { !Character.isWhitespace(it) && it >= ' '.code }
        val candidates = candidates()
        val preferred = if (monospace) MONOSPACE_PREFERRED else PREFERRED

        // Preferred names first, then whatever else is on the machine.
        val ordered = candidates.sortedBy { path ->
            val name = path.name.lowercase().replace("-", "").replace("_", "").replace(" ", "")
            preferred.indexOfFirst { name.startsWith(it) }.takeIf { it >= 0 } ?: preferred.size
        }

        var bestPartial: Pair<TrueTypeFont, Int>? = null

        for (path in ordered.take(MAX_FONTS_EXAMINED)) {
            val font = TrueTypeFont.load(path) ?: continue
            val covered = required.count(font::covers)
            if (covered == required.size) return font
            if (bestPartial == null || covered > bestPartial.second) bestPartial = font to covered
        }

        // Nothing covers everything: the closest match still draws most of the document, which is
        // better than refusing to export at all. The caller reports what will be missing.
        return bestPartial?.first
    }

    /** How many font files to open before giving up. Parsing one is milliseconds; a system may have thousands. */
    private const val MAX_FONTS_EXAMINED = 60

    private fun candidates(): List<Path> = DIRECTORIES
        .filter { it.exists() && it.isDirectory() }
        .flatMap { directory ->
            runCatching {
                Files.walk(directory, FONT_SEARCH_DEPTH).use { stream ->
                    stream.filter { it.extension.lowercase() in EXTENSIONS }.toList()
                }
            }.getOrDefault(emptyList())
        }
        .distinctBy { it.name.lowercase() }

    /** Font directories nest by family; three levels reaches every layout in common use. */
    private const val FONT_SEARCH_DEPTH = 3
}
