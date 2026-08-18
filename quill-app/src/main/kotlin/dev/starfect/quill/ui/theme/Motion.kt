package dev.starfect.quill.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.Alignment
import dev.starfect.quill.model.Dock

/**
 * How the shell moves.
 *
 * A tool's animation budget is small and spends itself on *continuity*, not on delight. The rules
 * here are deliberately narrow, because the failure mode of animation in a dense UI is not ugliness
 * — it is latency. Every frame an element spends fading is a frame the reader spends waiting to know
 * whether their click landed.
 *
 * So:
 *
 * - **Nothing animates on the critical path.** Typing, caret movement, scrolling and syntax
 *   highlighting are never animated. The editor is not decorated.
 * - **State changes fade, they do not travel.** A row that becomes selected cross-fades its fill.
 *   Nothing slides across the window to get somewhere.
 * - **Durations are short enough to feel instant but long enough to be seen** — around a tenth of a
 *   second for a hover, twice that for something appearing or leaving.
 * - **Appearing is slower than disappearing.** A panel that leaves should get out of the way; one
 *   that arrives can afford to be followed.
 *
 * Keeping the numbers here rather than at each call site is what stops the shell accumulating a
 * 90ms fade next to a 300ms one, which is the thing that actually reads as unpolished.
 */
public object Motion {

    /** A state change on something already on screen: hover, selection, a toggled control. */
    public const val STATE_MILLIS: Int = 110

    /** Something appearing: a panel, a bar, a popup. */
    public const val ENTER_MILLIS: Int = 180

    /** Something leaving. Faster than arriving — it is in the way. */
    public const val EXIT_MILLIS: Int = 120

    /**
     * The shell's easing.
     *
     * Standard ease-out: quick to start, settling at the end. An ease-*in* on a UI element reads as
     * lag, because nothing appears to happen for the first few frames after the click.
     */
    public val Easing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Fade for a state change, e.g. a colour crossing from hover to selected. */
    public fun <T> state(): androidx.compose.animation.core.TweenSpec<T> =
        tween(durationMillis = STATE_MILLIS, easing = Easing)

    /**
     * A docked bar arriving and leaving: the find bar, a notification.
     *
     * Expands from its top edge rather than sliding in from off-screen, so the content below it is
     * pushed rather than covered — the same thing that happens when it is not animated at all.
     */
    public val barEnter: EnterTransition =
        expandVertically(tween(ENTER_MILLIS, easing = Easing), expandFrom = Alignment.Top) +
            fadeIn(tween(ENTER_MILLIS, easing = Easing))

    public val barExit: ExitTransition =
        shrinkVertically(tween(EXIT_MILLIS, easing = Easing), shrinkTowards = Alignment.Top) +
            fadeOut(tween(EXIT_MILLIS, easing = Easing))

    /**
     * A popup or dialog arriving.
     *
     * Barely scaled — 98% to 100%. Enough to read as "this came forward"; not enough to look like it
     * flew in. A popup that zooms is a popup you wait for.
     */
    public val popupEnter: EnterTransition =
        fadeIn(tween(ENTER_MILLIS, easing = Easing)) +
            scaleIn(tween(ENTER_MILLIS, easing = Easing), initialScale = 0.98f)

    public val popupExit: ExitTransition =
        fadeOut(tween(EXIT_MILLIS, easing = Easing)) +
            scaleOut(tween(EXIT_MILLIS, easing = Easing), targetScale = 0.98f)

    /**
     * A docked tool window opening.
     *
     * It grows from the edge it is docked to, so the editor beside it is pushed rather than
     * covered — the same thing that happens when the animation is not there, only visible. A panel
     * that slides *over* the editor and then snaps it aside is two motions where there is one
     * event.
     *
     * The fade is deliberately quicker than the expansion and finishes first. Panel contents that
     * are still translucent while the panel is still growing look like a rendering fault; text that
     * has arrived inside a container that is still settling looks like the panel opening.
     */
    public fun dockEnter(dock: Dock): EnterTransition = when (dock) {
        Dock.LEFT -> expandHorizontally(tween(ENTER_MILLIS, easing = Easing), expandFrom = Alignment.Start)
        Dock.RIGHT -> expandHorizontally(tween(ENTER_MILLIS, easing = Easing), expandFrom = Alignment.End)
        Dock.BOTTOM -> expandVertically(tween(ENTER_MILLIS, easing = Easing), expandFrom = Alignment.Bottom)
    } + fadeIn(tween(STATE_MILLIS, easing = Easing))

    /** A docked tool window closing. Collapses back into the edge it came from. */
    public fun dockExit(dock: Dock): ExitTransition = when (dock) {
        Dock.LEFT -> shrinkHorizontally(tween(EXIT_MILLIS, easing = Easing), shrinkTowards = Alignment.Start)
        Dock.RIGHT -> shrinkHorizontally(tween(EXIT_MILLIS, easing = Easing), shrinkTowards = Alignment.End)
        Dock.BOTTOM -> shrinkVertically(tween(EXIT_MILLIS, easing = Easing), shrinkTowards = Alignment.Bottom)
    } + fadeOut(tween(EXIT_MILLIS, easing = Easing))
}
