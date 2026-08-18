package dev.starfect.quill.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import dev.starfect.quill.QuillController
import dev.starfect.quill.bridge.QuillEngine
import dev.starfect.quill.ui.shell.QuillToolBar
import dev.starfect.quill.ui.theme.QuillTheme
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * What one keystroke costs the window.
 *
 * The number a user actually feels. Everything else about performance here — how long the engine
 * takes to parse, how many composables read which state — is a means to this end, and optimising
 * against those directly is how you end up with a faster parser and an editor that still stutters.
 *
 * `ImageComposeScene` composes and rasterises through Skia with no display server, so a frame here
 * is a real frame: layout, text shaping and drawing all happen. It is not a windowed frame — there
 * is no compositor and no vsync — so treat the absolute numbers as a floor and the *ratios* between
 * document sizes as the finding.
 *
 * What is asserted is therefore the *growth* between document sizes, not the milliseconds. This runs
 * on shared CI hardware where a four-fold swing happens, and a ceiling within a small multiple of
 * the local number fails on the machine rather than on the change — which teaches everyone to re-run
 * it, at which point it has stopped being a guard. The ratios are stable because the harness cost
 * appears in both terms, and they are what actually distinguishes viewport-scoped highlighting from
 * whole-document highlighting.
 */
class EditorLatencyTest {

    private companion object {
        const val WIDTH = 1440
        const val HEIGHT = 900
        const val FRAME_NANOS = 16_000_000L
        const val SETTLE_FRAMES = 60L
        const val DERIVE_TIMEOUT_NANOS = 30_000_000_000L

        /** Keystrokes measured per document size, after warm-up. */
        const val RUNS = 25
    }

    private lateinit var scope: CoroutineScope
    private lateinit var controller: QuillController

    @BeforeTest
    fun setUp() {
        System.setProperty("java.awt.headless", "true")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        controller = QuillController(scope, QuillEngine.create(darkTheme = true))
    }

    @AfterTest
    fun tearDown() {
        controller.close()
        scope.cancel()
    }

    /** A document of roughly [lines] lines, shaped like something somebody would actually write. */
    private fun document(lines: Int): String = buildString {
        for (i in 0 until lines) {
            if (i % 20 == 0) append("## Section ").append(i).append("\n\n")
            append("Some **prose** with `code`, a [link](https://example.com), and 한국어 on line ")
                .append(i).append(".\n")
            if (i % 50 == 0) append("\n```kotlin\nfun f() = ").append(i).append("\n```\n\n")
        }
    }

    @Test
    fun `a keystroke costs a frame, and says how much of one`() {
        SkiaAvailability.require()

        val measurements = linkedMapOf<Int, Double>()

        for (lines in listOf(100, 500, 2000)) {
            val file = Files.createTempDirectory("quill-latency").resolve("doc.md")
            file.writeText(document(lines))

            controller.openProject(file.parent)
            controller.openFile(file)
            awaitDerived(file)

            val id = controller.state.value.activeDocument!!.id

            ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)).use { scene ->
                scene.setContent {
                    QuillTheme(dark = true) {
                        Column(Modifier.fillMaxSize()) {
                            QuillToolBar(controller, controller.state.value, onExit = {})
                            QuillWindowContent(controller, controller.state.value, Modifier.fillMaxSize())
                        }
                    }
                }

                var frame = 0L
                fun settle() {
                    var spent = 0L
                    while (spent < SETTLE_FRAMES && scene.hasInvalidations()) {
                        scene.render(frame++ * FRAME_NANOS)
                        spent++
                    }
                }

                scene.render(frame++ * FRAME_NANOS)
                settle()

                // Warm up the text layout and JIT before measuring.
                repeat(8) { type(id, it); settle() }

                // Median, not mean. This runs on shared hardware where one sample in twenty is a
                // scheduling artefact several times the size of the rest, and a mean reports that
                // artefact as the user's experience.
                val samples = ArrayList<Long>(RUNS)
                repeat(RUNS) { run ->
                    type(id, run + 100)
                    val started = System.nanoTime()
                    scene.render(frame++ * FRAME_NANOS)
                    samples += System.nanoTime() - started
                    settle()
                }
                samples.sort()

                measurements[lines] = samples[samples.size / 2] / 1e6
            }
        }

        println("=== frame cost of one keystroke ===")
        measurements.forEach { (lines, millis) -> println("  %5d lines : %7.2f ms".format(lines, millis)) }

        // Measured here at 37 / 114 / 527 ms after scoping highlighting to the viewport, against
        // 54 / 211 / 1579 ms before it. About 40 ms of every number is the offscreen scene
        // rasterising 1440x900 in software, which a real window does not pay the same way.
        //
        // **The finding is the growth, not the milliseconds.** Ceilings within a small multiple of
        // the local numbers do not survive shared CI hardware: this suite ran four times in one
        // afternoon on identical editor code and failed once, at 492 ms against a 400 ms ceiling —
        // four times the local 114 ms, on a runner that was simply busy. A test that fails on the
        // machine rather than on the change teaches everyone to re-run it, and then it is not a
        // guard at all.
        //
        // Ratios do not care how fast the machine is, because the harness cost is in both terms.
        // They also catch the regression this exists for: four times the lines cost 4.0-4.6x here
        // and 7.5x before viewport scoping, so the ceiling sits between those.
        val smallToMedium = measurements.getValue(500) / measurements.getValue(100)
        val mediumToLarge = measurements.getValue(2000) / measurements.getValue(500)
        println("  growth 100 -> 500 : %.2fx".format(smallToMedium))
        println("  growth 500 -> 2000: %.2fx".format(mediumToLarge))

        // Only the second ratio is asserted. The first barely discriminates — 3.0-3.6x here against
        // 3.9x before the fix — so a ceiling tight enough to catch anything would be tight enough
        // to fail on noise, and one loose enough to be stable would catch nothing. It is printed
        // because it is worth reading, not because it is worth failing on.
        assertTrue(
            mediumToLarge < 6.0,
            "four times the lines cost ${"%.2f".format(mediumToLarge)}x the frame; " +
                "highlighting is meant to follow the viewport, not the document",
        )

        // And a floor under all of it, an order of magnitude above the local numbers rather than a
        // small multiple. This is not policing milliseconds — it is the assertion that catches a
        // keystroke becoming unusable in a way the ratios happen to preserve.
        assertTrue(measurements.getValue(100) < 600.0, "a keystroke in a 100-line document cost ${measurements[100]} ms")
        assertTrue(measurements.getValue(500) < 2_000.0, "a keystroke in a 500-line document cost ${measurements[500]} ms")
        assertTrue(measurements.getValue(2000) < 6_000.0, "a keystroke in a 2000-line document cost ${measurements[2000]} ms")
    }

    /** Types one character into the middle of the document, the way the text field would. */
    private fun type(id: Long, seed: Int) {
        val session = controller.state.value.documents.first { it.id == id }
        val text = session.text.text
        val at = text.length / 2
        val edited = text.substring(0, at) + ('a' + (seed % 26)) + text.substring(at)
        controller.onTextChanged(id, TextFieldValue(edited, TextRange(at + 1)))
    }

    private fun awaitDerived(source: Path) {
        val deadline = System.nanoTime() + DERIVE_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            val active = controller.state.value.activeDocument
            if (active?.path == source && active.derivedVersion >= active.engineVersion) return
            Thread.sleep(10)
        }
        error("the document was never derived")
    }
}
