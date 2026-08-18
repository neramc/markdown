package dev.starfect.quill.model

import androidx.compose.runtime.Immutable
import java.nio.file.Path

/**
 * Somewhere the reader has been.
 *
 * The path is carried alongside the document id so a place survives its tab being closed: the id
 * is gone, but "line 240 of `architecture.md`" is still a place, and Back can reopen the file to
 * get there. A history that forgets everything you closed is a history that is empty exactly when
 * you want it.
 */
@Immutable
public data class NavigationPlace(
    /** The document as it was open at the time, or null once that tab has gone. */
    public val documentId: Long?,
    /** The file, which is what makes the place reachable again after a close. */
    public val path: Path?,
    /** One-based, as a reader counts them. */
    public val line: Int,
    /** One-based. */
    public val column: Int,
    /** What the toolbar's tooltip says: `notes.md:42`, or `Untitled:1`. */
    public val label: String,
)

/**
 * Where the reader has been, and where they were going.
 *
 * The list is a single line with a cursor on it, not two stacks. That is what makes Forward
 * survive a Back — a stack-pair drops the forward side the moment anything else happens, which is
 * the behaviour people describe as "the forward arrow never works".
 *
 * Recording is deliberately coarse. A place is only kept when it is somewhere *else*: a jump inside
 * the same document that lands within [MERGE_LINES] of the current entry replaces it rather than
 * appending, so paging through search results leaves one entry per region rather than one per hit.
 * Fine-grained history is worse than none, because Back then means "up two lines" and stops being
 * a way to get anywhere.
 */
@Immutable
public data class NavigationHistory(
    public val places: List<NavigationPlace> = emptyList(),
    /** Index of where the reader is now, or -1 when nothing has been recorded. */
    public val index: Int = -1,
) {
    /** Where the reader is now, as far as the history knows. */
    public val current: NavigationPlace? get() = places.getOrNull(index)

    /** The place Back would go to, which is also what its tooltip names. */
    public val previous: NavigationPlace? get() = places.getOrNull(index - 1)

    /** The place Forward would go to. */
    public val next: NavigationPlace? get() = places.getOrNull(index + 1)

    public val canGoBack: Boolean get() = previous != null

    public val canGoForward: Boolean get() = next != null

    /**
     * Adds [place], dropping anything that was ahead of the cursor.
     *
     * Going somewhere new after stepping back abandons the branch you stepped off — the same rule a
     * browser follows, and the reason Forward is not a second Back.
     */
    public fun record(place: NavigationPlace): NavigationHistory {
        val here = current
        if (here != null && here.sameRegionAs(place)) {
            // Same region: update in place, keeping the exact line so Back returns to where the
            // reader actually was rather than to where they first arrived.
            val updated = places.toMutableList()
            updated[index] = place
            return copy(places = updated)
        }

        val kept = places.take(index + 1)
        val appended = (kept + place).takeLast(MAX_ENTRIES)
        return NavigationHistory(places = appended, index = appended.lastIndex)
    }

    /**
     * Replaces the current entry with [place] without moving the cursor.
     *
     * Called just before stepping away, so that Forward comes back to where the reader was standing
     * rather than to wherever the history last happened to record.
     */
    public fun reanchor(place: NavigationPlace): NavigationHistory {
        if (index !in places.indices) return this
        val updated = places.toMutableList()
        updated[index] = place
        return copy(places = updated)
    }

    /** Steps the cursor back one, or stays put at the beginning. */
    public fun back(): NavigationHistory = if (canGoBack) copy(index = index - 1) else this

    /** Steps the cursor forward one, or stays put at the end. */
    public fun forward(): NavigationHistory = if (canGoForward) copy(index = index + 1) else this

    /**
     * Forgets the document id of every place in [closed], keeping the places themselves.
     *
     * A closed tab does not erase where you have been; it only means getting back there involves
     * opening the file again.
     */
    public fun forget(closed: Long): NavigationHistory {
        if (places.none { it.documentId == closed }) return this
        return copy(places = places.map { if (it.documentId == closed) it.copy(documentId = null) else it })
    }

    private fun NavigationPlace.sameRegionAs(other: NavigationPlace): Boolean {
        val samePlace = (documentId != null && documentId == other.documentId) ||
            (path != null && path == other.path)
        return samePlace && kotlin.math.abs(line - other.line) <= MERGE_LINES
    }

    public companion object {
        /**
         * How far apart two places have to be to count as different places.
         *
         * Roughly a screenful. Below that, Back would land somewhere the reader can already see,
         * which does not feel like navigation at all.
         */
        public const val MERGE_LINES: Int = 25

        /** Cap on remembered places. Deep enough to be useful, bounded so it cannot grow forever. */
        public const val MAX_ENTRIES: Int = 50
    }
}
