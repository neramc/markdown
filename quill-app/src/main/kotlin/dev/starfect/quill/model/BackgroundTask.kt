package dev.starfect.quill.model

import androidx.compose.runtime.Immutable

/**
 * Something Quill is doing that takes long enough to be worth saying so.
 *
 * Work used to happen in silence. A project scan, an export, a search across a repository — each
 * ran on a coroutine and the window simply carried on looking idle until the result appeared, so
 * every long operation read as "nothing is happening" followed by "something suddenly happened".
 * The failure is not that the work is slow; it is that the window is lying about being idle.
 *
 * ## Determinate where it can be, honest where it cannot
 *
 * [fraction] is null when the work has no countable end — walking a directory tree does not know
 * how many files it will find until it has found them — and a bar that pretends otherwise by
 * crawling to 90% and waiting is worse than one that plainly says "still going". Indeterminate is
 * not a failure state; it is the truthful answer for a lot of real work.
 */
@Immutable
public data class BackgroundTask(
    public val id: Long,
    /** What is happening, in the user's terms: "Scanning project", not "walkFileTree". */
    public val title: String,
    /** What it is on right now — a file name, a step. Null when there is nothing useful to add. */
    public val detail: String? = null,
    /** 0..1, or null when the end is not knowable. */
    public val fraction: Float? = null,
    /** Whether stopping it is safe and offered. */
    public val cancellable: Boolean = true,
    /** When it started, for deciding whether it has run long enough to be worth showing. */
    public val startedAt: Long = System.currentTimeMillis(),
    /** Set when the user has asked it to stop but it has not finished unwinding. */
    public val stopping: Boolean = false,
) {
    /**
     * Whether this has run long enough to be worth putting on screen.
     *
     * Work that finishes in under a fifth of a second should never have announced itself: a status
     * bar that flickers on every keystroke-triggered background job is worse than one that stays
     * quiet, and the flicker is what people mean when they call an interface busy.
     */
    public fun visibleAt(now: Long): Boolean = now - startedAt >= SHOW_AFTER_MILLIS

    public companion object {
        /** How long work must run before it is shown at all. */
        public const val SHOW_AFTER_MILLIS: Long = 200

        /**
         * How long it stays once shown, even if it finishes sooner.
         *
         * Without this, a task that crosses the threshold and completes immediately after produces
         * a single-frame flash, which reads as a glitch rather than as progress.
         */
        public const val MINIMUM_VISIBLE_MILLIS: Long = 400
    }
}
