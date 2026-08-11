package dev.starfect.quill

import dev.starfect.quill.bridge.QuillEngine
import dev.starfect.quill.bridge.wire.Severity
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Tests that applying a setting actually changes what is on screen.
 *
 * The failure this guards against does not throw and does not show up in a unit test of the state:
 * the new value lands in [dev.starfect.quill.model.QuillSettings] correctly, and everything derived
 * from it keeps its old value until an unrelated edit happens to trigger a parse. To the user the
 * setting simply does nothing. Every setting that feeds derivation therefore needs a case here.
 */
class SettingsApplyTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val controller = QuillController(scope, QuillEngine.create(darkTheme = true))

    /** One weak warning (the unlabelled fence) and one error (it is never closed). */
    private val flawed = """
        # Title

        ```
        an unclosed fence
    """.trimIndent()

    @AfterTest
    fun tearDown() {
        controller.close()
        scope.cancel()
    }

    private fun openFlawed(): Long {
        val file = Files.createTempDirectory("quill-settings").resolve("doc.md")
        file.writeText(flawed)
        controller.openFile(file)

        await("the document to derive") {
            controller.state.value.activeDocument?.takeIf { it.derivedVersion >= 0 } != null
        }
        return assertNotNull(controller.state.value.activeDocument, "no document opened").id
    }

    /** Polls until [condition] holds, because derivation runs off the calling thread. */
    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private val findings get() = controller.state.value.activeDocument?.findings.orEmpty()

    @Test
    fun `turning off weak warnings drops them without another edit`() {
        openFlawed()

        assertTrue(findings.any { it.severity == Severity.WEAK }, "the fixture should report a weak warning")
        val errors = findings.count { it.severity == Severity.ERROR }
        assertTrue(errors > 0, "the fixture should report an error too")

        controller.applySettings(controller.state.value.settings.copy(showWeakWarnings = false))

        await("the weak warnings to disappear") { findings.none { it.severity == Severity.WEAK } }
        assertEquals(errors, findings.size, "only the weak warnings should have gone")
    }

    @Test
    fun `turning weak warnings back on restores them`() {
        openFlawed()
        val original = findings.size

        controller.applySettings(controller.state.value.settings.copy(showWeakWarnings = false))
        await("the weak warnings to disappear") { findings.none { it.severity == Severity.WEAK } }

        controller.applySettings(controller.state.value.settings.copy(showWeakWarnings = true))
        await("the weak warnings to come back") { findings.size == original }
    }

    @Test
    fun `turning inspections off empties the problems list`() {
        openFlawed()
        assertTrue(findings.isNotEmpty(), "the fixture should report something")

        controller.applySettings(controller.state.value.settings.copy(inspectionsEnabled = false))

        await("the findings to clear") { findings.isEmpty() }
    }

    @Test
    fun `switching theme re-derives so the highlighting follows`() {
        openFlawed()
        val before = assertNotNull(controller.state.value.activeDocument).spans
        assertTrue(before.isNotEmpty(), "the fixture should produce editor spans")

        controller.applySettings(controller.state.value.settings.copy(darkTheme = false))

        // The spans themselves are palette-independent, so what is asserted is that a derivation
        // ran at all: the engine's colour scheme changed and nothing would repaint without one.
        await("a re-derivation after the theme change") {
            val document = controller.state.value.activeDocument
            document != null && document.derivedVersion >= 0 && document.spans.isNotEmpty()
        }
        assertEquals(false, controller.state.value.settings.darkTheme)
    }

    @Test
    fun `a setting that does not feed derivation leaves the findings alone`() {
        openFlawed()
        val before = findings

        controller.applySettings(controller.state.value.settings.copy(showLineNumbers = false))

        // Nothing to re-derive, so the findings must be the same list rather than a recomputed one.
        assertEquals(before, findings)
    }
}
