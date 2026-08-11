package dev.starfect.quill.bridge.wire

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets

/** Payload discriminators, mirroring `PayloadKind` in `wire.rs`. */
public enum class PayloadKind(internal val id: Int) {
    BLOCKS(1),
    OUTLINE(2),
    STATS(3),
    SEARCH(4),
    SPANS(5),
    CODE_HIGHLIGHT(6),
    TEXT(7),
    HTML_DOM(8),
    ;

    internal companion object {
        fun fromId(id: Int): PayloadKind =
            entries.firstOrNull { it.id == id } ?: throw QuillWireException("unknown payload kind $id")
    }
}

/** Raised when a payload does not match the format this reader implements. */
public class QuillWireException(message: String) : RuntimeException(message)

/**
 * Decoder for the QWIRE binary format produced by the Rust engine.
 *
 * This is the exact counterpart of `wire.rs`: little-endian scalars, `u32`-length-prefixed UTF-8
 * strings, and depth-first node streams. It reads straight out of the [MemorySegment] the engine
 * handed back, so decoding a block tree copies string bytes and nothing else.
 *
 * Every read is bounds-checked against the segment length, so a truncated or corrupt payload
 * surfaces as a [QuillWireException] rather than as a JVM crash — which matters because these bytes
 * originate in native memory, where an over-read is not otherwise caught.
 */
public class WireReader private constructor(
    private val segment: MemorySegment,
    private val length: Long,
    public val kind: PayloadKind,
    private var position: Long,
) {

    public companion object {
        private const val MAGIC: Int = 0x31525751 // "QWR1"
        private const val WIRE_VERSION: Short = 1
        private const val HEADER_BYTES: Long = 7

        /** Validates the header and returns a reader positioned at the payload. */
        public fun of(segment: MemorySegment): WireReader {
            val length = segment.byteSize()
            if (length < HEADER_BYTES) {
                throw QuillWireException("payload of $length bytes is too short to contain a header")
            }
            val magic = segment.get(ValueLayout.JAVA_INT_UNALIGNED, 0)
            if (magic != MAGIC) {
                throw QuillWireException(
                    "bad magic 0x${magic.toUInt().toString(16)}; the native library and bridge are out of step"
                )
            }
            val version = segment.get(ValueLayout.JAVA_SHORT_UNALIGNED, 4)
            if (version != WIRE_VERSION) {
                throw QuillWireException("unsupported wire version $version, expected $WIRE_VERSION")
            }
            val kind = PayloadKind.fromId(segment.get(ValueLayout.JAVA_BYTE, 6).toInt())
            return WireReader(segment, length, kind, position = HEADER_BYTES)
        }
    }

    /** Fails unless this payload is of the expected [kind]. */
    public fun expect(expected: PayloadKind): WireReader {
        if (kind != expected) {
            throw QuillWireException("expected a $expected payload but received $kind")
        }
        return this
    }

    private fun require(bytes: Long) {
        if (position + bytes > length) {
            throw QuillWireException("payload truncated: needed $bytes bytes at $position of $length")
        }
    }

    public fun byte(): Int {
        require(1)
        return segment.get(ValueLayout.JAVA_BYTE, position).toInt().also { position += 1 }
    }

    public fun boolean(): Boolean = byte() != 0

    public fun short(): Int {
        require(2)
        return segment.get(ValueLayout.JAVA_SHORT_UNALIGNED, position).toInt().also { position += 2 }
    }

    /**
     * Reads a `u32`.
     *
     * Returned as [Int]; values above [Int.MAX_VALUE] cannot occur because the encoder saturates
     * lengths at `u32::MAX` and every real document offset is far below 2^31.
     */
    public fun int(): Int {
        require(4)
        return segment.get(ValueLayout.JAVA_INT_UNALIGNED, position).also { position += 4 }
    }

    public fun long(): Long {
        require(8)
        return segment.get(ValueLayout.JAVA_LONG_UNALIGNED, position).also { position += 8 }
    }

    /** Reads a count, rejecting negative values that could only come from a corrupt payload. */
    public fun count(): Int {
        val value = int()
        if (value < 0) {
            throw QuillWireException("negative count $value at ${position - 4}")
        }
        return value
    }

    public fun string(): String {
        val bytes = count()
        require(bytes.toLong())
        val array = ByteArray(bytes)
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, position, array, 0, bytes)
        position += bytes
        return String(array, StandardCharsets.UTF_8)
    }

    public fun optionalString(): String? = if (boolean()) string() else null

    public fun isExhausted(): Boolean = position >= length
}
