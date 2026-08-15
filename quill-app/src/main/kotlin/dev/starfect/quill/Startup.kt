package dev.starfect.quill

/**
 * How long Quill took to get on screen.
 *
 * Startup is the one performance number a user actually experiences, and the one nobody can measure
 * from outside the process: a stopwatch on the launcher includes the shell, the loader and the
 * window manager, none of which are Quill's to fix. This reports the interval that is.
 *
 * Off unless asked for, and it prints one line to stderr — a measuring instrument, not logging. It
 * exists because packaging decisions trade against it: compressing the bundled runtime took thirty
 * megabytes off an installation and put decompression back into every JDK class load, and the way
 * to know whether that stays a good trade is to have a number on both sides of it.
 *
 * ```
 * Quill -J-Dquill.startup.trace=true
 * quill: first frame after 1284 ms
 * ```
 *
 * Note the `-J`. A jpackage launcher passes bare arguments to the application and needs `-J` to
 * forward one to the JVM, so `-Dquill.startup.trace=true` on its own is read as a file to open.
 */
public object Startup {

    internal const val PROPERTY: String = "quill.startup.trace"

    private var state: State? = null

    /** The clock, separated from the printing so a test can drive both. */
    internal class State(val startedNanos: Long) {
        var reported: Boolean = false
    }

    /**
     * Starts the clock, if tracing was asked for.
     *
     * Called first thing in `main`, and deliberately not at process start: the JVM is already up by
     * then, and counting time no change to this code could move makes the number less useful, not
     * more.
     */
    public fun begin() {
        state = if (System.getProperty(PROPERTY)?.toBoolean() == true) State(System.nanoTime()) else null
    }

    /**
     * Reports the interval, once.
     *
     * Called from the first composition of the window's content — the moment the user has something
     * to look at, rather than the moment the window object exists, which is earlier and invisible.
     * Later frames are ignored, because the second frame is not a startup.
     */
    public fun firstFrame() {
        message(System.nanoTime())?.let(System.err::println)
    }

    /** The line to print, or null when there is nothing to say. Separated so it can be tested. */
    internal fun message(nowNanos: Long): String? {
        val current = state ?: return null
        if (current.reported) return null
        current.reported = true
        return "quill: first frame after ${(nowNanos - current.startedNanos) / 1_000_000} ms"
    }

    /** Drops any running measurement. For tests, which must not leak state into each other. */
    internal fun reset() {
        state = null
    }
}
