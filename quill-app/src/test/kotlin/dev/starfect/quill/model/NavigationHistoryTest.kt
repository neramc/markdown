package dev.starfect.quill.model

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The list-with-a-cursor that Back and Forward walk.
 *
 * Tested apart from the controller because the interesting rules are all in here: when a place is
 * new, when it merely updates the one you are standing on, and what happens to the forward branch.
 */
class NavigationHistoryTest {

    private fun place(document: Long?, line: Int, name: String = "a.md") = NavigationPlace(
        documentId = document,
        path = Path.of("/p/$name"),
        line = line,
        column = 1,
        label = "$name:$line",
    )

    @Test
    fun `an empty history goes nowhere`() {
        val history = NavigationHistory()
        assertFalse(history.canGoBack)
        assertFalse(history.canGoForward)
        assertNull(history.current)
        assertEquals(history, history.back())
        assertEquals(history, history.forward())
    }

    @Test
    fun `one place is not somewhere to go back to`() {
        val history = NavigationHistory().record(place(1, 10))
        assertFalse(history.canGoBack, "there is nowhere behind the only place you have been")
        assertEquals(10, history.current?.line)
    }

    @Test
    fun `two distant places make a step`() {
        val history = NavigationHistory().record(place(1, 10)).record(place(1, 200))

        assertTrue(history.canGoBack)
        assertEquals(200, history.current?.line)
        assertEquals(10, history.previous?.line)

        val back = history.back()
        assertEquals(10, back.current?.line)
        assertTrue(back.canGoForward)
        assertEquals(200, back.forward().current?.line)
    }

    @Test
    fun `a nearby place replaces rather than appends`() {
        // Paging through search results inside one section must not fill the history with a
        // separate entry per hit; Back would then mean "up two lines".
        val history = NavigationHistory()
            .record(place(1, 100))
            .record(place(1, 105))
            .record(place(1, 110))

        assertEquals(1, history.places.size)
        assertEquals(110, history.current?.line, "the entry tracks where the reader actually is")
        assertFalse(history.canGoBack)
    }

    @Test
    fun `the merge window is exactly MERGE_LINES`() {
        val edge = NavigationHistory()
            .record(place(1, 100))
            .record(place(1, 100 + NavigationHistory.MERGE_LINES))
        assertEquals(1, edge.places.size, "exactly the window is still the same region")

        val beyond = NavigationHistory()
            .record(place(1, 100))
            .record(place(1, 101 + NavigationHistory.MERGE_LINES))
        assertEquals(2, beyond.places.size, "one line past it is somewhere else")
    }

    @Test
    fun `nearby lines in different documents are different places`() {
        val history = NavigationHistory()
            .record(place(1, 10, "a.md"))
            .record(place(2, 12, "b.md"))

        assertEquals(2, history.places.size)
        assertTrue(history.canGoBack)
    }

    @Test
    fun `going somewhere new abandons the forward branch`() {
        val history = NavigationHistory()
            .record(place(1, 10))
            .record(place(1, 200))
            .record(place(1, 400))
            .back()

        assertTrue(history.canGoForward)

        val branched = history.record(place(2, 5, "other.md"))
        assertFalse(branched.canGoForward, "stepping off the branch abandons it, as a browser does")
        assertEquals(listOf(10, 200, 5), branched.places.map { it.line })
    }

    @Test
    fun `reanchor moves the current entry without moving the cursor`() {
        val history = NavigationHistory().record(place(1, 10)).record(place(2, 300, "b.md"))
        val anchored = history.reanchor(place(2, 340, "b.md"))

        assertEquals(1, anchored.index, "two places, so the cursor sits on index 1")
        assertEquals(340, anchored.current?.line, "Forward must return to where the reader stood")
        assertEquals(10, anchored.previous?.line, "and Back must be untouched")
    }

    @Test
    fun `reanchor on an empty history does nothing`() {
        assertEquals(NavigationHistory(), NavigationHistory().reanchor(place(1, 4)))
    }

    @Test
    fun `closing a document keeps its places and drops its id`() {
        val history = NavigationHistory()
            .record(place(1, 10, "a.md"))
            .record(place(2, 10, "b.md"))
            .forget(1)

        assertEquals(2, history.places.size, "where you have been does not depend on what is open")
        assertNull(history.places.first().documentId)
        assertEquals(Path.of("/p/a.md"), history.places.first().path, "the file is how Back gets back")
        assertEquals(2L, history.places.last().documentId)
    }

    @Test
    fun `forgetting an id nothing holds is a no-op`() {
        val history = NavigationHistory().record(place(1, 10))
        assertTrue(history === history.forget(99))
    }

    @Test
    fun `the history is bounded`() {
        var history = NavigationHistory()
        repeat(NavigationHistory.MAX_ENTRIES * 2) { step ->
            history = history.record(place(1, 1 + step * (NavigationHistory.MERGE_LINES + 1)))
        }

        assertEquals(NavigationHistory.MAX_ENTRIES, history.places.size)
        assertEquals(history.places.lastIndex, history.index, "the cursor stays on the newest place")
        // The oldest places went, not the newest: the cap must not silently make Forward stale.
        assertEquals(
            1 + NavigationHistory.MAX_ENTRIES * (NavigationHistory.MERGE_LINES + 1),
            history.places.first().line,
        )
    }

    @Test
    fun `a place with no id still matches by path`() {
        // After a close-and-reopen the id is gone but the file is the same, and recording nearby
        // must still count as the same region rather than stacking a duplicate.
        val closed = NavigationHistory().record(place(1, 100)).forget(1)
        val again = closed.record(place(7, 108))

        assertEquals(1, again.places.size)
        assertEquals(7L, again.current?.documentId)
    }
}
