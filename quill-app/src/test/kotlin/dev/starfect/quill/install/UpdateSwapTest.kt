package dev.starfect.quill.install

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Replacing a running Quill with a newer one.
 *
 * This is the most destructive thing in the application: it moves the user's installation aside and
 * puts a downloaded directory in its place, from a script that outlives the process that wrote it.
 * The failures worth pinning are the ones that end with no editor at all — a rename that turns out
 * to be a cross-filesystem copy, a half-finished swap with nothing to fall back to, a script that
 * starts deleting before the JVM has let go of its own files.
 */
class UpdateSwapTest {

    @TempDir
    lateinit var root: Path

    private fun installation(name: String = "Quill", launcher: String = "bin/Quill"): Path {
        val image = root.resolve(name)
        image.resolve(launcher).parent.createDirectories()
        image.resolve(launcher).writeText("#!/bin/sh")
        return image
    }

    // ── Planning ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a plan retires the old image beside the new one`() {
        val current = installation()
        val replacement = root.resolve("Quill.new").also { it.createDirectories() }

        val plan = UpdateSwap.plan(current, replacement).getOrThrow()

        assertEquals(current, plan.current)
        assertEquals(root.resolve("Quill.old"), plan.retired)
        assertEquals("bin/Quill", plan.launcher)
    }

    @Test
    fun `a replacement that is not a sibling is refused`() {
        // A rename across filesystems is a copy, and a copy of a hundred megabytes that fails
        // halfway is exactly the state the retire-then-move design exists to avoid. Better to find
        // out here than at the point of no return.
        val current = installation()
        val elsewhere = root.resolve("staging/Quill.new").also { it.createDirectories() }

        val failure = UpdateSwap.plan(current, elsewhere).exceptionOrNull()
        assertNotNull(failure)
        assertContains(failure.message!!, "beside the installation")
    }

    @Test
    fun `an image with no launcher is refused rather than swapped into place`() {
        // Swapping in a directory that cannot be started leaves the user with nothing to run and
        // the old copy already deleted.
        val empty = root.resolve("Quill").also { it.createDirectories() }
        val replacement = root.resolve("Quill.new").also { it.createDirectories() }

        assertContains(UpdateSwap.plan(empty, replacement).exceptionOrNull()!!.message!!, "No launcher")
    }

    @Test
    fun `the launcher is found wherever the platform puts it`() {
        // jpackage: bin/Quill on Linux, Quill.exe at the root on Windows, Contents/MacOS inside a
        // macOS bundle. Naming one layout would fail the other two silently.
        assertEquals("bin/Quill", UpdateSwap.launcherWithin(installation("linux")))
        assertEquals("Quill.exe", UpdateSwap.launcherWithin(installation("windows", "Quill.exe")))
        assertEquals(
            "Contents/MacOS/Quill",
            UpdateSwap.launcherWithin(installation("mac", "Contents/MacOS/Quill")),
        )
        assertEquals(null, UpdateSwap.launcherWithin(root.resolve("nothing")))
    }

    // ── The script ────────────────────────────────────────────────────────────────────────────

    private fun plan(): UpdateSwap.Plan {
        val current = installation()
        val replacement = root.resolve("Quill.new").also { it.createDirectories() }
        return UpdateSwap.plan(current, replacement).getOrThrow()
    }

    @Test
    fun `nothing moves until this process has exited`() {
        // Windows will not unlink a running executable, and on Unix deleting the files under a live
        // JVM crashes it the moment it needs a class it has not loaded yet.
        for (windows in listOf(true, false)) {
            val script = UpdateSwap.script(plan(), 4242, windows)
            val wait = script.indexOfFirst { "4242" in it }
            val firstMove = script.indexOfFirst { it.startsWith("move ") || it.startsWith("mv ") }
            assertTrue(wait in 0 until firstMove, "windows=$windows: the wait must precede the move")
        }
    }

    @Test
    fun `the wait is bounded so a hung process cannot strand the script`() {
        assertTrue(UpdateSwap.script(plan(), 1, windows = true).any { "QUILL_TRIES" in it && "GEQ" in it })
        assertTrue(UpdateSwap.script(plan(), 1, windows = false).any { "-ge" in it })
    }

    @Test
    fun `the old image is retired before the new one moves in`() {
        // Retire-then-move rather than delete-then-copy. A crash between the two leaves Quill.old
        // on disk, which is visible and recoverable; the other order leaves nothing.
        for (windows in listOf(true, false)) {
            val script = UpdateSwap.script(plan(), 1, windows)
            val retire = script.indexOfFirst { "Quill.old" in it && ("move " in it || "mv " in it) }
            val install = script.indexOfFirst { "Quill.new" in it && ("move " in it || "mv " in it) }
            assertTrue(retire in 0 until install, "windows=$windows")
        }
    }

    @Test
    fun `a failed move puts the old installation back`() {
        // The user came here to get a newer editor, not to lose the one they had. Somewhere after
        // the line that moves the new image in, there has to be one that moves the retired image
        // back -- retired path first, installation path second, which is the direction that
        // distinguishes a restore from the retire that set it up.
        for (windows in listOf(true, false)) {
            val plan = plan()
            val script = UpdateSwap.script(plan, 1, windows)
            val install = script.indexOfFirst { "${plan.replacement}" in it }
            assertTrue(install >= 0, "windows=$windows: nothing moves the new image in")

            val restore = script.drop(install + 1).firstOrNull { line ->
                line.lastIndexOf("${plan.current}") > line.indexOf("${plan.retired}") &&
                    line.indexOf("${plan.retired}") >= 0
            }
            assertNotNull(restore, "windows=$windows: no path back from a failed move")
        }
    }

    @Test
    fun `the new version is started and the script deletes itself`() {
        val windowsScript = UpdateSwap.script(plan(), 1, windows = true)
        assertTrue(windowsScript.any { it.startsWith("start ") && "bin/Quill" in it.replace('\\', '/') })
        assertEquals("del /f /q \"%~f0\"", windowsScript.last())

        val unixScript = UpdateSwap.script(plan(), 1, windows = false)
        assertTrue(unixScript.any { it.endsWith("&") && "bin/Quill" in it })
        assertEquals("rm -f \"\$0\"", unixScript.last())
    }

    @Test
    fun `paths with awkward characters survive both shells`() {
        val current = root.resolve("100% Quill's").also { it.resolve("bin").createDirectories() }
        current.resolve("bin/Quill").writeText("#!/bin/sh")
        val replacement = root.resolve("Quill.new").also { it.createDirectories() }
        val plan = UpdateSwap.plan(current, replacement).getOrThrow()

        // A batch file expands %VAR% inside quotes unless the percent is doubled.
        assertTrue(UpdateSwap.script(plan, 1, windows = true).any { "100%% Quill's" in it })
        // A shell needs the apostrophe spliced out of the single-quoted string.
        assertTrue(UpdateSwap.script(plan, 1, windows = false).any { """100% Quill'\''s""" in it })
    }

    @Test
    fun `starting the swap hands the script over rather than running it here`() {
        var started: List<String>? = null
        val where = UpdateSwap.start(plan()) { lines -> started = lines; root.resolve("swap.sh") }

        assertIs<Path>(where.getOrThrow())
        assertNotNull(started)
        assertTrue(started!!.isNotEmpty())
    }
}
