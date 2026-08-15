package dev.starfect.quill

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The startup trace.
 *
 * Small, but it is the instrument the packaging trade-offs are argued from — compressing the
 * bundled runtime buys thirty megabytes and costs class-load time — so it needs to be right about
 * the two things it claims: that it says nothing unless asked, and that it measures from the start
 * of `main` to the first frame rather than to every frame after it.
 */
class StartupTest {

    @AfterTest
    fun clear() {
        System.clearProperty(Startup.PROPERTY)
        Startup.reset()
    }

    private fun trace(on: Boolean) {
        if (on) System.setProperty(Startup.PROPERTY, "true") else System.clearProperty(Startup.PROPERTY)
        Startup.begin()
    }

    @Test
    fun `nothing is reported unless tracing was asked for`() {
        trace(on = false)
        assertNull(Startup.message(Long.MAX_VALUE))
    }

    @Test
    fun `nothing is reported when the clock was never started`() {
        // The uninstall route returns from main before Startup.begin, and a stray first frame from
        // its confirmation window must not print a duration measured from zero.
        Startup.reset()
        System.setProperty(Startup.PROPERTY, "true")
        assertNull(Startup.message(Long.MAX_VALUE))
    }

    @Test
    fun `the reported interval is the one that elapsed`() {
        trace(on = true)
        val started = System.nanoTime()

        val message = Startup.message(started + 1_500_000_000)
        assertNotNull(message)
        // Between the 1500 ms of elapsed time and that plus however long begin() was ago.
        val milliseconds = Regex("""(\d+) ms""").find(message)!!.groupValues[1].toLong()
        assertTrue(milliseconds >= 1500, "reported $milliseconds ms for a 1500 ms interval")
        assertTrue(milliseconds < 1600, "reported $milliseconds ms for a 1500 ms interval")
    }

    @Test
    fun `only the first frame is a startup`() {
        trace(on = true)

        assertNotNull(Startup.message(System.nanoTime()))
        assertNull(Startup.message(System.nanoTime()), "every later frame would be a second launch")
        assertNull(Startup.message(System.nanoTime()))
    }
}
