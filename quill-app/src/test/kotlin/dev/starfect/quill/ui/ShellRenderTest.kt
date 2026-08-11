package dev.starfect.quill.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import dev.starfect.quill.QuillController
import dev.starfect.quill.bridge.QuillEngine
import dev.starfect.quill.model.ViewMode
import dev.starfect.quill.ui.shell.QuillToolBar
import dev.starfect.quill.ui.theme.QuillTheme
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.skia.EncodedImageFormat

/**
 * Renders the whole IDE shell offscreen and checks that it actually draws.
 *
 * `ImageComposeScene` composes into a Skia raster surface rather than into a window, so this
 * exercises layout, drawing and text shaping with no display server and no GL context — the two
 * things this build environment lacks. It is also end-to-end in the strict sense: the engine being
 * driven is the real `cdylib`, the blocks on screen arrived through the FFM bridge, and the colours
 * inside the code fence were resolved by syntect.
 *
 * The assertions are structural rather than pixel-exact. A golden image would fail on any machine
 * with different fonts installed; "this pane drew hundreds of distinct colours" holds anywhere and
 * still fails loudly when a pane comes back blank.
 */
class ShellRenderTest {

    private companion object {
        const val WIDTH = 1440
        const val HEIGHT = 900

        const val FRAME_INTERVAL_NANOS = 16_000_000L

        /** Frames rendered before giving up on the scene settling; about two seconds at 60 Hz. */
        const val MAX_FRAMES = 120L

        const val DERIVE_TIMEOUT_NANOS = 20_000_000_000L

        /** Exercises every block and inline type the mapper knows about. */
        val SAMPLE = """
            ---
            title: Render check
            ---

            # Quill

            A paragraph with **bold**, *italic*, ~~struck~~ and `code`, plus a
            [link](https://example.com) and an autolink <https://starfect.dev>.

            ## Lists

            - plain item
            - [x] completed task
            - [ ] pending task

            1. first
            2. second

            > [!NOTE]
            > Alerts come from the GFM extension.

            ```rust
            fn main() {
                println!("안녕하세요 🚀");
            }
            ```

            | Column | Value |
            | :----- | ----: |
            | left   |    42 |

            ---

            Mixed scripts: 한국어 텍스트와 이모지 🎉 를 포함합니다.
        """.trimIndent()
    }

    private lateinit var scope: CoroutineScope
    private lateinit var controller: QuillController
    private lateinit var outputDirectory: Path

    @BeforeTest
    fun setUp() {
        // There is no display server here, and the scene never needs one.
        System.setProperty("java.awt.headless", "true")
        outputDirectory = Path.of(System.getProperty("quill.test.output", "build/test-renders"))
        outputDirectory.createDirectories()

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        controller = QuillController(scope, QuillEngine.create(darkTheme = true))
    }

    @AfterTest
    fun tearDown() {
        controller.close()
        scope.cancel()
    }

    @Test
    fun `the dark split view renders the editor, preview and chrome`() {
        val image = renderShell("dark-split.png", dark = true, viewMode = ViewMode.SPLIT)

        val colours = distinctColours(image)
        assertTrue(colours > 64, "the shell drew only $colours distinct colours, which means it is blank")

        // IntelliJ's dark editor background is #1E1F22. If the theme had failed to apply, the scene
        // would come back on Compose's default white.
        val luminance = averageLuminance(image)
        assertTrue(luminance < 0.35, "the dark theme did not apply: mean luminance is $luminance")
    }

    @Test
    fun `the light theme renders on a light background`() {
        val image = renderShell("light-split.png", dark = false, viewMode = ViewMode.SPLIT)

        val luminance = averageLuminance(image)
        assertTrue(luminance > 0.6, "the light theme did not apply: mean luminance is $luminance")
        assertTrue(distinctColours(image) > 64, "the light shell rendered blank")
    }

    @Test
    fun `editor-only and preview-only modes render differently`() {
        val editorOnly = renderShell("editor-only.png", dark = true, viewMode = ViewMode.EDITOR)
        val previewOnly = renderShell("preview-only.png", dark = true, viewMode = ViewMode.PREVIEW)

        assertTrue(distinctColours(editorOnly) > 64, "the editor pane rendered blank")
        assertTrue(distinctColours(previewOnly) > 64, "the preview pane rendered blank")
        // Identical renders would mean the view-mode switch never reached the layout.
        assertTrue(differingPixelRatio(editorOnly, previewOnly) > 0.1, "the two view modes look the same")
    }

    @Test
    fun `the find bar and command palette render when opened`() {
        val plain = renderShell("split-plain.png", dark = true, viewMode = ViewMode.SPLIT)
        val overlaid = renderShell("find-and-palette.png", dark = true, viewMode = ViewMode.SPLIT) {
            controller.setFindVisible(visible = true, withReplace = true)
            controller.updateFind { it.copy(query = "the") }
            controller.setCommandPaletteVisible(true)
        }

        assertTrue(distinctColours(overlaid) > 64, "the overlay render is blank")
        assertTrue(
            differingPixelRatio(plain, overlaid) > 0.02,
            "opening the find bar and command palette changed nothing on screen",
        )
    }

    @Test
    fun `the engine produced the derived views the shell is drawing`() {
        // Guards against the render assertions passing on an empty document: had derivation failed
        // silently, every pane would still draw its chrome and the colour checks would hold.
        renderShell("derived-state.png", dark = true, viewMode = ViewMode.SPLIT)

        val document = assertNotNull(controller.state.value.activeDocument, "no document was opened")
        assertTrue(document.blocks.isNotEmpty(), "the engine returned no blocks")
        assertTrue(document.outline.isNotEmpty(), "the engine returned no outline")
        assertTrue(document.spans.isNotEmpty(), "the engine returned no editor spans")
        assertTrue(document.stats.words > 0, "the engine counted no words")
        assertEquals(listOf("Quill", "Lists"), document.outline.map { it.title })
        assertEquals(null, document.loadError, "derivation reported an error")
    }

    /**
     * Composes the shell and returns the rasterised frame.
     *
     * The scene is rendered repeatedly rather than once: the controller derives on background
     * dispatchers, so the first frame shows an empty document and the interesting one arrives a few
     * milliseconds later. Rendering until composition reports no pending invalidations — with a
     * wall-clock ceiling, so a recomposition loop fails the test instead of hanging it — is what
     * makes the result deterministic.
     */
    private fun renderShell(
        fileName: String,
        dark: Boolean,
        viewMode: ViewMode,
        afterOpen: () -> Unit = {},
    ): BufferedImage {
        val source = Files.createTempDirectory("quill-render").resolve("sample.md")
        source.writeText(SAMPLE)

        controller.openProject(source.parent)
        controller.openFile(source)
        controller.updateSettings { it.copy(darkTheme = dark, viewMode = viewMode) }

        awaitDerivedDocument(source)
        afterOpen()

        val encoded = ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)).use { scene ->
            scene.setContent {
                QuillTheme(dark = dark) {
                    Column(Modifier.fillMaxSize()) {
                        // The toolbar variant rather than the decorated title bar: DecoratedWindow
                        // needs a real JBR-backed window, and everything below it is identical.
                        QuillToolBar(controller, controller.state.value, onExit = {})
                        QuillWindowContent(controller, controller.state.value, Modifier.fillMaxSize())
                    }
                }
            }

            // A bounded number of frames rather than "render until nothing is invalidated". The
            // editor's caret blinks, so once the text field has focus the scene reports pending
            // invalidations forever and a wait-for-quiescence loop never terminates. Stopping early
            // when it does settle keeps the common case fast; the cap keeps the slow case finite.
            var image = scene.render(0L)
            var frame = 1L
            while (frame <= MAX_FRAMES && scene.hasInvalidations()) {
                image = scene.render(frame * FRAME_INTERVAL_NANOS)
                frame++
            }

            assertNotNull(image.encodeToData(EncodedImageFormat.PNG), "the frame could not be encoded").bytes
        }

        Files.write(outputDirectory.resolve(fileName), encoded)
        return assertNotNull(ImageIO.read(ByteArrayInputStream(encoded)), "the encoded frame is not a readable PNG")
    }

    /**
     * Blocks until [source] is the active document and the engine has derived it.
     *
     * Matching on the path matters: a previous render in the same test leaves an already-derived
     * document behind, and a check for "some derived document" would return before the new file had
     * even been read.
     */
    private fun awaitDerivedDocument(source: Path) {
        val deadline = System.nanoTime() + DERIVE_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            val workspace = controller.state.value
            val document = workspace.activeDocument
            if (
                document?.path == source &&
                document.blocks.isNotEmpty() &&
                document.spans.isNotEmpty() &&
                workspace.projectRoot == source.parent
            ) {
                return
            }
            Thread.sleep(10)
        }
        error("the engine did not produce a derived document for $source within the timeout")
    }

    /** Counts distinct RGB values, capped. A blank pane has one or two; a drawn one has hundreds. */
    private fun distinctColours(image: BufferedImage): Int {
        val seen = HashSet<Int>()
        // Every fourth pixel in both axes is ample to tell "blank" from "drawn", and keeps the scan
        // to a few tens of thousands of samples.
        for (y in 0 until image.height step 4) {
            for (x in 0 until image.width step 4) {
                seen += image.getRGB(x, y) and 0xFFFFFF
                if (seen.size > 4_096) return seen.size
            }
        }
        return seen.size
    }

    /** Mean perceived brightness in 0..1, which is what tells the two IntelliJ schemes apart. */
    private fun averageLuminance(image: BufferedImage): Double {
        var total = 0.0
        var count = 0L
        for (y in 0 until image.height step 4) {
            for (x in 0 until image.width step 4) {
                val rgb = image.getRGB(x, y)
                val red = (rgb shr 16) and 0xFF
                val green = (rgb shr 8) and 0xFF
                val blue = rgb and 0xFF
                total += (0.2126 * red + 0.7152 * green + 0.0722 * blue) / 255.0
                count++
            }
        }
        return if (count == 0L) 0.0 else total / count
    }

    /** Fraction of sampled pixels that differ between two frames of the same size. */
    private fun differingPixelRatio(first: BufferedImage, second: BufferedImage): Double {
        assertEquals(first.width, second.width, "frames differ in width")
        assertEquals(first.height, second.height, "frames differ in height")

        var differing = 0L
        var count = 0L
        for (y in 0 until first.height step 4) {
            for (x in 0 until first.width step 4) {
                if (first.getRGB(x, y) != second.getRGB(x, y)) differing++
                count++
            }
        }
        return if (count == 0L) 0.0 else differing.toDouble() / count
    }
}
