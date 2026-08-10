package com.neramc.quill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the edit-delta computation that turns an editor change into an engine rope patch.
 *
 * The invariant every case checks is the same one the editor depends on: applying the delta to the
 * previous text must reproduce the new text exactly. If that ever stops holding, the engine's rope
 * silently diverges from the buffer on screen and every derived view is wrong from that point on.
 */
class TextDeltaTest {

    private fun apply(previous: String, delta: TextDelta): String =
        previous.substring(0, delta.start) + delta.replacement + previous.substring(delta.end)

    private fun roundTrip(previous: String, current: String): TextDelta {
        val delta = TextDelta.between(previous, current)
        assertEquals(current, apply(previous, delta), "delta did not reproduce the new text")
        return delta
    }

    @Test
    fun `insertion in the middle touches only the inserted range`() {
        val delta = roundTrip("hello world", "hello brave world")
        assertEquals(6, delta.start)
        assertEquals(6, delta.end)
        assertEquals("brave ", delta.replacement)
    }

    @Test
    fun `deletion produces an empty replacement`() {
        val delta = roundTrip("hello brave world", "hello world")
        assertEquals("", delta.replacement)
        assertEquals(6, delta.start)
        assertEquals(12, delta.end)
    }

    @Test
    fun `appending at the end leaves the prefix untouched`() {
        val delta = roundTrip("# Title", "# Title\n\nBody")
        assertEquals(7, delta.start)
        assertEquals(7, delta.end)
    }

    @Test
    fun `typing at the start shifts nothing after it`() {
        val delta = roundTrip("world", "hello world")
        assertEquals(0, delta.start)
        assertEquals(0, delta.end)
        assertEquals("hello ", delta.replacement)
    }

    @Test
    fun `identical strings produce an empty delta`() {
        val delta = roundTrip("unchanged", "unchanged")
        assertEquals("", delta.replacement)
        assertEquals(delta.start, delta.end)
    }

    @Test
    fun `clearing the document replaces the whole range`() {
        val delta = roundTrip("some text", "")
        assertEquals(0, delta.start)
        assertEquals(9, delta.end)
        assertEquals("", delta.replacement)
    }

    @Test
    fun `korean text uses UTF-16 offsets, not byte offsets`() {
        // Every Hangul syllable is three UTF-8 bytes but one UTF-16 unit. A byte offset leaking
        // through here would land the engine three times too far into the rope.
        val delta = roundTrip("안녕하세요 세계", "안녕하세요 아름다운 세계")
        assertEquals(6, delta.start)
        assertEquals(6, delta.end)
        assertEquals("아름다운 ", delta.replacement)
    }

    @Test
    fun `an inserted astral emoji is treated as two units`() {
        val delta = roundTrip("ab", "a🚀b")
        assertEquals(1, delta.start)
        assertEquals(1, delta.end)
        assertEquals(2, delta.replacement.length)
    }

    @Test
    fun `a prefix boundary never splits a surrogate pair`() {
        // "🚀" and "🚁" share their high surrogate, so a naive common-prefix scan stops between the
        // two halves of the pair and hands the engine an offset that is not a character boundary.
        val delta = roundTrip("🚀", "🚁")
        assertEquals(0, delta.start)
        assertTrue(!delta.replacement.isEmpty() && Character.isHighSurrogate(delta.replacement[0]))
    }

    @Test
    fun `a suffix boundary never splits a surrogate pair`() {
        val delta = roundTrip("x🚀", "y🚀")
        assertEquals(0, delta.start)
        assertEquals("y", delta.replacement)
    }

    @Test
    fun `deleting one of two identical emoji stays on a boundary`() {
        val delta = roundTrip("🚀🚀", "🚀")
        assertEquals(2, delta.replacement.length + (delta.end - delta.start))
    }

    @Test
    fun `a repeated character sequence still round-trips`() {
        // Common prefix and common suffix overlap here, which is where an unclamped scan produces a
        // negative-length range.
        roundTrip("aaaa", "aa")
        roundTrip("aa", "aaaa")
        roundTrip("abab", "ab")
    }

    @Test
    fun `replacing the middle of mixed-script text round-trips`() {
        roundTrip("한국어 🚀 text here", "한국어 🎉 text here")
        roundTrip("prefix 🚀🚀🚀 suffix", "prefix 🚀 suffix")
    }
}
