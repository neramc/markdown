package com.neramc.quill.bridge

import com.neramc.quill.bridge.wire.PayloadKind
import com.neramc.quill.bridge.wire.QuillWireException
import com.neramc.quill.bridge.wire.WireReader
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the decoder against hand-built payloads.
 *
 * The engine tests cover the happy path with real engine output; these cover the malformed input the
 * engine will never produce, because those bytes come from native memory where an over-read is not
 * otherwise caught.
 */
class WireReaderTest {

    private fun payload(kind: Int, body: ByteBuffer.() -> Unit): MemorySegment {
        val buffer = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x31525751)
        buffer.putShort(1)
        buffer.put(kind.toByte())
        buffer.body()
        val bytes = ByteArray(buffer.position())
        buffer.flip()
        buffer.get(bytes)
        return MemorySegment.ofArray(bytes)
    }

    private fun ByteBuffer.putString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        putInt(bytes.size)
        put(bytes)
    }

    @Test
    fun `reads primitives in little-endian order`() {
        val segment = payload(PayloadKind.STATS.id) {
            put(7)
            put(1)
            putShort(513)
            putInt(70_000)
            putLong(-42L)
            putString("hello")
        }
        val reader = WireReader.of(segment)
        assertEquals(PayloadKind.STATS, reader.kind)
        assertEquals(7, reader.byte())
        assertTrue(reader.boolean())
        assertEquals(513, reader.short())
        assertEquals(70_000, reader.int())
        assertEquals(-42L, reader.long())
        assertEquals("hello", reader.string())
        assertTrue(reader.isExhausted())
    }

    @Test
    fun `decodes multi-byte utf8 correctly`() {
        assertEquals("한국어 🪶", WireReader.of(payload(7) { putString("한국어 🪶") }).string())
    }

    @Test
    fun `reads optional strings`() {
        val reader = WireReader.of(
            payload(7) {
                put(0)
                put(1)
                putString("present")
            }
        )
        assertNull(reader.optionalString())
        assertEquals("present", reader.optionalString())
    }

    @Test
    fun `rejects a payload with the wrong magic`() {
        val failure = assertFailsWith<QuillWireException> { WireReader.of(MemorySegment.ofArray(ByteArray(16))) }
        assertContains(failure.message.orEmpty(), "bad magic")
    }

    @Test
    fun `rejects an unsupported wire version`() {
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x31525751)
        buffer.putShort(99)
        buffer.put(1)
        val failure = assertFailsWith<QuillWireException> { WireReader.of(MemorySegment.ofArray(buffer.array())) }
        assertContains(failure.message.orEmpty(), "unsupported wire version")
    }

    @Test
    fun `rejects an unknown payload kind`() {
        assertFailsWith<QuillWireException> { WireReader.of(payload(99) {}) }
    }

    @Test
    fun `rejects a payload too short to hold a header`() {
        val failure = assertFailsWith<QuillWireException> { WireReader.of(MemorySegment.ofArray(ByteArray(3))) }
        assertContains(failure.message.orEmpty(), "too short")
    }

    @Test
    fun `refuses to read past the end of the payload`() {
        // A truncated string length must fail cleanly rather than over-reading native memory.
        val buffer = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x31525751)
        buffer.putShort(1)
        buffer.put(7)
        buffer.putInt(1000) // claims 1000 bytes that are not there
        val reader = WireReader.of(MemorySegment.ofArray(buffer.array()))
        assertContains(assertFailsWith<QuillWireException> { reader.string() }.message.orEmpty(), "truncated")
    }

    @Test
    fun `rejects a negative count`() {
        val failure = assertFailsWith<QuillWireException> { WireReader.of(payload(1) { putInt(-5) }).count() }
        assertContains(failure.message.orEmpty(), "negative count")
    }

    @Test
    fun `expect enforces the payload kind`() {
        val segment = payload(PayloadKind.TEXT.id) {}
        assertFailsWith<QuillWireException> { WireReader.of(segment).expect(PayloadKind.BLOCKS) }
        WireReader.of(segment).expect(PayloadKind.TEXT)
    }
}
