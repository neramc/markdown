package dev.starfect.quill.export

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

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
    /** The font file's bytes. */
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
    /** Where each table lives in [program]: name to offset and length. */
    private val tables: Map<String, Pair<Int, Int>>,
    /** Whether `loca` holds two-byte or four-byte offsets. */
    private val longLoca: Boolean,
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

    /**
     * The font reduced to the glyphs in [glyphs], as a font file.
     *
     * This is the difference between a usable export and one nobody would send anybody. A CJK font
     * is a large object — WenQuanYi Zen Hei is eleven megabytes, Noto Sans CJK is more — and
     * embedding it whole turns a one-page Korean letter into a file bigger than the photographs in
     * it. What the page actually needs is the few dozen shapes it draws.
     *
     * **Glyph numbering is left exactly as it was.** Every unused glyph becomes an empty entry
     * rather than being squeezed out, which is what makes this safe: the PDF addresses glyphs by
     * their id through `Identity-H` and `CIDToGIDMap /Identity`, so a subsetter that renumbered
     * would have to rewrite every string in the document to match, and would silently draw the
     * wrong letters wherever it did not. Empty entries cost four bytes each.
     *
     * Composite glyphs — an accented letter is usually a reference to a letter plus a reference to
     * an accent — drag in the glyphs they are built from, so the closure is walked before anything
     * is dropped. Missing that is how a subset ends up drawing an 'e' where 'é' should be.
     *
     * Tables the subset does not need go too: the character map (the PDF addresses glyphs directly),
     * the layout tables, the names. Returns the program unchanged for OpenType/CFF fonts, whose
     * outlines are in a format this does not read.
     */
    public fun subset(glyphs: Set<Int>): ByteArray {
        if (isCompactFontFormat) return program
        return runCatching { buildSubset(glyphs) }.getOrDefault(program)
    }

    private fun buildSubset(glyphs: Set<Int>): ByteArray {
        val reader = Reader(program)
        val (locaAt, locaLength) = tables["loca"] ?: return program
        val (glyfAt, _) = tables["glyf"] ?: return program

        // Where each glyph's outline starts and ends, from `loca`.
        val entries = if (longLoca) locaLength / 4 else locaLength / 2
        if (entries < glyphCount + 1) return program
        val bounds = IntArray(glyphCount + 1) { index ->
            if (longLoca) reader.u32(locaAt + index * 4).toInt() else reader.u16(locaAt + index * 2) * 2
        }

        // Glyph 0 is the "missing glyph" box, which a font is required to have and a reader may
        // draw at any time.
        val keep = sortedSetOf(0)
        val pending = ArrayDeque(glyphs.filter { it in 0 until glyphCount })
        while (pending.isNotEmpty()) {
            val glyph = pending.removeFirst()
            if (!keep.add(glyph)) continue
            for (component in componentsOf(reader, glyfAt, bounds, glyph)) {
                if (component in 0 until glyphCount && component !in keep) pending.addLast(component)
            }
        }

        val last = keep.last()
        val count = last + 1

        // `glyf` and a matching `loca`, in the long format so no offset can overflow.
        val outlines = ByteArrayOutputStream()
        val loca = ByteArray((count + 1) * 4)
        for (glyph in 0 until count) {
            putU32(loca, glyph * 4, outlines.size().toLong())
            if (glyph in keep) {
                val from = glyfAt + bounds[glyph]
                val length = bounds[glyph + 1] - bounds[glyph]
                if (length > 0 && from + length <= program.size) {
                    outlines.write(program, from, length)
                    while (outlines.size() % 4 != 0) outlines.write(0)
                }
            }
        }
        putU32(loca, count * 4, outlines.size().toLong())

        val head = tables["head"]?.first ?: return program
        val hhea = tables["hhea"]?.first ?: return program
        val maxp = tables["maxp"]?.first ?: return program
        val hmtx = tables["hmtx"]?.first ?: return program
        val metrics = reader.u16(hhea + 34)

        // One advance and side bearing per remaining glyph. Reading past the original's metric
        // count is not an error: a font stops repeating the advance once it is constant.
        val widths = ByteArray(count * 4)
        for (glyph in 0 until count) {
            val source = hmtx + minOf(glyph, metrics - 1).coerceAtLeast(0) * 4
            putU16(widths, glyph * 4, reader.u16(source))
            putU16(widths, glyph * 4 + 2, if (glyph < metrics) reader.u16(source + 2) else 0)
        }

        fun copy(name: String): ByteArray? = tables[name]?.let { (at, length) ->
            if (at + length > program.size) null else program.copyOfRange(at, at + length)
        }

        val newHead = copy("head") ?: return program
        putU16(newHead, 50, 1) // indexToLocFormat: long
        val newHhea = copy("hhea") ?: return program
        putU16(newHhea, 34, count)
        val newMaxp = copy("maxp") ?: return program
        putU16(newMaxp, 4, count)

        val subset = buildList {
            add("head" to newHead)
            add("hhea" to newHhea)
            add("maxp" to newMaxp)
            add("hmtx" to widths)
            add("loca" to loca)
            add("glyf" to outlines.toByteArray())
            // The hinting tables come along when the font has them: without `prep` and `fpgm` a
            // hinted font's instructions refer to programs that are no longer there.
            for (name in listOf("cvt ", "fpgm", "prep", "gasp")) copy(name)?.let { add(name to it) }
        }

        return buildSfnt(reader.u32(0), subset) ?: program
    }

    /** The glyphs a composite glyph is assembled from, or nothing for a simple one. */
    private fun componentsOf(reader: Reader, glyfAt: Int, bounds: IntArray, glyph: Int): List<Int> {
        if (glyph + 1 >= bounds.size) return emptyList()
        val start = glyfAt + bounds[glyph]
        if (bounds[glyph + 1] - bounds[glyph] < COMPOSITE_HEADER) return emptyList()
        if (reader.i16(start) >= 0) return emptyList() // a positive contour count means simple

        val components = mutableListOf<Int>()
        var at = start + COMPOSITE_HEADER
        while (true) {
            val flags = reader.u16(at)
            components += reader.u16(at + 2)
            at += 4
            at += if (flags and ARGS_ARE_WORDS != 0) 4 else 2
            at += when {
                flags and HAS_TWO_BY_TWO != 0 -> 8
                flags and HAS_X_AND_Y_SCALE != 0 -> 4
                flags and HAS_SCALE != 0 -> 2
                else -> 0
            }
            if (flags and MORE_COMPONENTS == 0) break
            if (components.size > MAX_COMPONENTS) break
        }
        return components
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

            // A .ttc collection holds several fonts in one file. Pull the first one out into a
            // standalone font before doing anything else, rather than reading its tables in place:
            // what gets embedded in the PDF is this byte array, and a reader handed a whole
            // collection rejects it.
            if (reader.u32(0) == COLLECTION_TAG) {
                val single = extractFromCollection(bytes) ?: return null
                return parse(single)
            }

            val offset = 0
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
                tables = tables,
                longLoca = indexToLocFormat == 1,
            )
        }

        /** `ttcf`, the four bytes a TrueType collection starts with. */
        private const val COLLECTION_TAG = 0x74746366L

        /**
         * Rebuilds the first font of a collection as a standalone font file.
         *
         * A `.ttc` is several fonts sharing one file and, usually, sharing tables between them —
         * which is why the format exists, and why it cannot simply be handed to something expecting
         * one font. A PDF's `FontFile2` must be a single font program: give a reader a stream whose
         * first four bytes are `ttcf` and it rejects the font, or the whole document.
         *
         * That matters well beyond the exotic case. macOS keeps most of its CJK families as
         * collections — Apple SD Gothic Neo, PingFang, Hiragino — so without this, a Korean document
         * exported on a Mac finds a font that can draw it and then produces a PDF that will not
         * open. Failing later and less clearly than not finding a font at all.
         *
         * The rebuild copies each of the font's tables out and writes a fresh table directory in
         * front of them. Table checksums come along unchanged because the bytes do; only the
         * whole-file adjustment in `head` has to be recomputed, since the file it describes is new.
         */
        private fun extractFromCollection(bytes: ByteArray): ByteArray? {
            val reader = Reader(bytes)
            if (bytes.size < 16) return null

            val fontOffset = reader.u32(12).toInt()
            if (fontOffset < 0 || fontOffset + 12 > bytes.size) return null

            val tableCount = reader.u16(fontOffset + 4)
            if (tableCount <= 0 || tableCount > MAX_TABLES) return null

            val tables = ArrayList<Pair<String, ByteArray>>(tableCount)
            for (index in 0 until tableCount) {
                val record = fontOffset + 12 + index * 16
                if (record + 16 > bytes.size) return null
                val at = reader.u32(record + 8).toInt()
                val length = reader.u32(record + 12).toInt()
                if (at < 0 || length < 0 || at.toLong() + length > bytes.size) return null
                tables += String(bytes, record, 4, Charsets.ISO_8859_1) to bytes.copyOfRange(at, at + length)
            }

            return buildSfnt(reader.u32(fontOffset), tables)
        }

        /**
         * Assembles named tables into a font file.
         *
         * Both things that produce a font here — pulling one out of a collection, and cutting one
         * down to the glyphs a document uses — end up needing the same thing: a table directory in
         * front of some table data. Checksums are computed rather than carried over, because in one
         * of those two cases the data is new.
         */
        private fun buildSfnt(version: Long, tables: List<Pair<String, ByteArray>>): ByteArray? {
            if (tables.isEmpty() || tables.size > MAX_TABLES) return null
            if (tables.any { it.first.length != 4 }) return null

            // A table directory is required to be sorted by tag, and readers do binary-search it.
            val sorted = tables.sortedBy { it.first }
            val count = sorted.size
            val out = ByteArray(SFNT_HEADER + count * 16 + sorted.sumOf { aligned(it.second.size) })

            // The binary-search hints in an sfnt header are derived from the table count. Readers
            // largely ignore them and validators do not, so they are computed rather than zeroed.
            val entrySelector = 31 - Integer.numberOfLeadingZeros(count)
            val searchRange = (1 shl entrySelector) * 16
            putU32(out, 0, version)
            putU16(out, 4, count)
            putU16(out, 6, searchRange)
            putU16(out, 8, entrySelector)
            putU16(out, 10, count * 16 - searchRange)

            var write = SFNT_HEADER + count * 16
            var head = -1
            for ((index, table) in sorted.withIndex()) {
                val (name, data) = table
                val directory = SFNT_HEADER + index * 16
                for (character in 0 until 4) out[directory + character] = name[character].code.toByte()
                putU32(out, directory + 4, checksum(data))
                putU32(out, directory + 8, write.toLong())
                putU32(out, directory + 12, data.size.toLong())
                System.arraycopy(data, 0, out, write, data.size)
                if (name == "head") head = write
                write += aligned(data.size)
            }

            // `head.checkSumAdjustment` is a checksum of the whole file, so a new file needs a new
            // one. The specification's recipe: zero the field, sum the file, subtract from a magic
            // number that exists for no reason beyond being agreed on.
            if (head >= 0 && head + 12 <= out.size) {
                putU32(out, head + 8, 0)
                putU32(out, head + 8, (0xB1B0AFBAL - checksum(out)) and 0xFFFFFFFFL)
            }

            return out
        }

        /** A table's checksum: its bytes read as big-endian words, zero-padded, added up. */
        private fun checksum(bytes: ByteArray): Long {
            val reader = Reader(bytes)
            var sum = 0L
            var at = 0
            // Reader returns zero past the end, which is exactly the padding the sum is defined over.
            while (at < bytes.size) {
                sum = (sum + reader.u32(at)) and 0xFFFFFFFFL
                at += 4
            }
            return sum
        }

        internal fun putU16(bytes: ByteArray, at: Int, value: Int) {
            bytes[at] = (value ushr 8).toByte()
            bytes[at + 1] = value.toByte()
        }

        internal fun putU32(bytes: ByteArray, at: Int, value: Long) {
            bytes[at] = (value ushr 24).toByte()
            bytes[at + 1] = (value ushr 16).toByte()
            bytes[at + 2] = (value ushr 8).toByte()
            bytes[at + 3] = value.toByte()
        }

        /** An sfnt table directory starts after twelve bytes, and every table starts on a word. */
        private const val SFNT_HEADER = 12
        private const val MAX_TABLES = 512

        /** A composite glyph's own header, before the first component. */
        private const val COMPOSITE_HEADER = 10
        private const val ARGS_ARE_WORDS = 0x0001
        private const val HAS_SCALE = 0x0008
        private const val MORE_COMPONENTS = 0x0020
        private const val HAS_X_AND_Y_SCALE = 0x0040
        private const val HAS_TWO_BY_TWO = 0x0080

        /** A guard against a corrupt glyph claiming to be built from thousands of others. */
        private const val MAX_COMPONENTS = 256

        private fun aligned(length: Int) = (length + 3) and 3.inv()

        /**
         * How many of [codePoints] the font at [path] can draw, or -1 when it is not a font this
         * can read.
         *
         * Answering this without loading the file is the point. Choosing a font means asking the
         * question of every candidate on the machine, and a system font directory is tens of
         * megabytes — reading all of it to find out which file has Hangul in it turns an export into
         * a visible pause. Only the table directory and the character map are read here, which is
         * kilobytes per font instead of megabytes.
         */
        public fun coverage(path: Path, codePoints: IntArray): Int = runCatching {
            FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                val start = read(channel, 0, 16) ?: return@use -1
                val base = if (Reader(start).u32(0) == COLLECTION_TAG) Reader(start).u32(12) else 0L

                val header = read(channel, base, SFNT_HEADER) ?: return@use -1
                val tableCount = Reader(header).u16(4)
                if (tableCount <= 0 || tableCount > MAX_TABLES) return@use -1

                val directory = read(channel, base + SFNT_HEADER, tableCount * 16) ?: return@use -1
                val reader = Reader(directory)
                var at = -1L
                var length = 0
                for (index in 0 until tableCount) {
                    val record = index * 16
                    if (String(directory, record, 4, Charsets.ISO_8859_1) != "cmap") continue
                    at = reader.u32(record + 8)
                    length = reader.u32(record + 12).toInt()
                    break
                }
                if (at < 0 || length <= 0 || length > MAX_CMAP_BYTES) return@use -1

                val cmap = read(channel, at, length) ?: return@use -1
                val map = readCharacterMap(Reader(cmap), 0, cmap.size) ?: return@use -1
                codePoints.count(map::containsKey)
            }
        }.getOrDefault(-1)

        /** A character map larger than this is corrupt rather than thorough. */
        private const val MAX_CMAP_BYTES = 8 shl 20

        private fun read(channel: FileChannel, at: Long, length: Int): ByteArray? {
            if (at < 0 || length <= 0 || at + length > channel.size()) return null
            val buffer = ByteBuffer.allocate(length)
            var position = at
            while (buffer.hasRemaining()) {
                val count = channel.read(buffer, position)
                if (count <= 0) return null
                position += count
            }
            return buffer.array()
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
     * Text faces lead. The search returns the first candidate that can draw the whole document, so
     * for an English document it stops here — and that is the point of the ordering: a CJK family
     * placed first would be chosen for English too, and the Latin glyphs in a Chinese font are an
     * afterthought in a way a reader can see immediately.
     *
     * The families that can draw Hangul follow. Nothing has to route a Korean document to them: a
     * Latin font cannot draw 한, so the search falls through on its own and arrives at the first
     * font here that can. That is a better mechanism than a name list, which is why the list only
     * has to be an ordering rather than a decision.
     */
    private val PREFERRED = listOf(
        "dejavusans", "notosans", "liberationsans", "arial", "helvetica", "roboto", "inter",
        "segoeui", "calibri", "verdana", "tahoma",
        "notosanscjk", "notoserifcjk", "notosanskr", "sourcehansans", "nanumgothic", "nanummyeongjo",
        "malgun", "applegothic", "applesdgothicneo", "gulim", "batang", "dotum",
        "notosansjp", "notosanssc", "notosanstc", "msgothic", "meiryo", "hiraginosans",
        // Named because they are what a Linux build image tends to have when it has any CJK font at
        // all. Without them the search still finds something that covers Hangul -- Unifont, whose
        // glyphs come from a 16-pixel bitmap and look it at print sizes.
        "wqyzenhei", "wqymicrohei", "arialunicode",
    )

    private val MONOSPACE_PREFERRED = listOf(
        "jetbrainsmono", "dejavusansmono", "notosansmono", "liberationmono", "consolas",
        "menlo", "monaco", "couriernew", "cousine", "sourcecodepro", "firacode", "d2coding",
    )

    private val EXTENSIONS = setOf("ttf", "otf", "ttc", "otc")

    /**
     * Names that say the file is a fixed-width font, whatever family it belongs to.
     *
     * Width is checked separately from family because the family names overlap: `dejavusans` is a
     * prefix of `dejavusansmono`, so matching on family alone offers a code font for body text.
     */
    private val MONOSPACE_MARKERS =
        listOf("mono", "code", "courier", "consol", "inconsolata", "menlo", "typewriter", "d2coding")

    /**
     * Names that say the file is a particular face rather than the plain one.
     *
     * A document set in the bold weight of the right family is the kind of wrong nothing ever
     * explains — it looks deliberate. The regular face wins whenever the family has one.
     */
    private val FACE_MARKERS = listOf(
        "bold", "italic", "oblique", "light", "thin", "black", "heavy", "medium", "semi", "extra",
        "ultra", "condensed", "narrow", "retina", "dotted", "slashed",
    )

    /**
     * How good a font file's name looks for this document, lower being better.
     *
     * Three things, in order of how much they matter. Width first: a proportional document set in a
     * monospace font is unmistakably wrong, worse than the same document in a different but
     * reasonable family. Then family, so a Korean document reaches a Korean font before anything
     * else is opened. Then face, so the regular weight wins over the bold one beside it.
     *
     * This only *orders* the search. What decides the outcome is whether the font can draw the text
     * — the first candidate in this order that covers the document is the one used, so a wrong guess
     * here costs a few kilobytes of reading, not a broken export.
     */
    internal fun rank(fileName: String, monospace: Boolean): Int {
        val name = fileName.substringBeforeLast('.')
            .lowercase()
            .replace("-", "")
            .replace("_", "")
            .replace(" ", "")
        val preferred = if (monospace) MONOSPACE_PREFERRED else PREFERRED

        val family = preferred.indexOfFirst { name.startsWith(it) }.takeIf { it >= 0 } ?: preferred.size
        val wrongWidth = MONOSPACE_MARKERS.any { it in name } != monospace
        val face = FACE_MARKERS.any { it in name }

        return (if (wrongWidth) WIDTH_PENALTY else 0) + family * FAMILY_WEIGHT + (if (face) 1 else 0)
    }

    private const val FAMILY_WEIGHT = 10
    private const val WIDTH_PENALTY = 100_000

    /**
     * The first font that can draw every character in [sample].
     *
     * @param monospace prefer a fixed-width family, for code blocks.
     * @return null when nothing on the machine covers the text, which the caller has to report
     *   rather than silently produce a document full of empty boxes.
     */
    public fun findCovering(sample: String, monospace: Boolean = false): TrueTypeFont? {
        val required = sample.codePoints().distinct()
            .filter { !Character.isWhitespace(it) && it >= ' '.code }
            .toArray()

        // Ties are broken by name so the same machine always produces the same PDF. Directory order
        // is whatever the filesystem hands back, which is stable in practice and guaranteed nowhere.
        val ordered = candidates.sortedWith(
            compareBy({ rank(it.name, monospace) }, { it.name.lowercase() }),
        )

        var bestPartial: Pair<Path, Int>? = null

        for (path in ordered.take(MAX_FONTS_EXAMINED)) {
            val covered = TrueTypeFont.coverage(path, required)
            if (covered < 0) continue
            if (covered == required.size) return TrueTypeFont.load(path) ?: continue
            if (bestPartial == null || covered > bestPartial.second) bestPartial = path to covered
        }

        // Nothing covers everything: the closest match still draws most of the document, which is
        // better than refusing to export at all. The caller reports what will be missing.
        return bestPartial?.let { TrueTypeFont.load(it.first) }
    }

    /**
     * How many font files to look at before giving up.
     *
     * Deliberately larger than a machine is likely to have. The number used to be sixty, chosen when
     * examining a font meant reading all of it; the effect was that a font directory listing ninety
     * files had its last thirty never looked at, and on this machine those thirty were where the
     * only font with Hangul in it lived. Reading a font's character map alone costs kilobytes, so
     * the budget can be the number that stops a pathological directory rather than the number that
     * keeps exports quick.
     */
    private const val MAX_FONTS_EXAMINED = 500

    /**
     * Every font file on the machine, found once.
     *
     * Walking the font directories is the only part of choosing a font that touches the whole tree,
     * and fonts do not appear during a session. An export asks for a body font and a code font, so
     * without this the walk happens twice for one document.
     */
    private val candidates: List<Path> by lazy {
        DIRECTORIES
            .filter { it.exists() && it.isDirectory() }
            .flatMap { directory ->
                runCatching {
                    Files.walk(directory, FONT_SEARCH_DEPTH).use { stream ->
                        stream.filter { it.extension.lowercase() in EXTENSIONS }.toList()
                    }
                }.getOrDefault(emptyList())
            }
            .distinctBy { it.nameWithoutExtension.lowercase() }
    }

    /** Font directories nest by family; three levels reaches every layout in common use. */
    private const val FONT_SEARCH_DEPTH = 3
}
