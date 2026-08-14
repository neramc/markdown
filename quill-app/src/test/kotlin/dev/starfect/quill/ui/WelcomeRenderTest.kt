package dev.starfect.quill.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import dev.starfect.quill.io.RecentProject
import dev.starfect.quill.io.RecentProjects
import dev.starfect.quill.ui.theme.QuillTheme
import dev.starfect.quill.ui.welcome.WelcomeContent
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * Renders the welcome window offscreen.
 *
 * The welcome window has two genuinely different layouts — a grid of large actions when there is
 * nothing to reopen, and a searchable list when there is — and the branch between them is decided by
 * data rather than by a flag. Rendering both is the only way to know that neither has quietly
 * stopped composing.
 */
class WelcomeRenderTest {

    private companion object {
        const val WIDTH = 1000
        const val HEIGHT = 700
        const val FRAME_INTERVAL_NANOS = 16_000_000L
        const val MAX_FRAMES = 120L
    }

    private lateinit var workspace: Path
    private lateinit var outputDirectory: Path

    @BeforeTest
    fun setUp() {
        System.setProperty("java.awt.headless", "true")
        workspace = Files.createTempDirectory("quill-welcome")
        outputDirectory = Path.of(System.getProperty("quill.test.output", "build/test-renders"))
        outputDirectory.createDirectories()
    }

    @AfterTest
    fun tearDown() {
        workspace.toFile().deleteRecursively()
    }

    @Test
    fun `the empty state renders its large actions`() {
        val image = render("welcome-empty.png", dark = true, recents = emptyList())
        assertTrue(distinctColours(image) > 32, "the empty welcome window rendered blank")
        assertTrue(averageLuminance(image) < 0.35, "the dark theme did not apply")
    }

    @Test
    fun `the recent projects list renders and differs from the empty state`() {
        val empty = render("welcome-empty-compare.png", dark = true, recents = emptyList())
        val populated = render("welcome-recents.png", dark = true, recents = sampleRecents())

        assertTrue(distinctColours(populated) > 32, "the populated welcome window rendered blank")

        // The threshold is low on purpose. Both layouts are mostly empty pane, so even a completely
        // different arrangement moves only a few percent of pixels — the two states here differ
        // across a 700x450 region and still only reach ~4%. Identical renders give exactly 0, so
        // anything above one percent is a real difference rather than noise.
        val difference = differingPixelRatio(empty, populated)
        assertTrue(difference > 0.01, "the recent-projects list looks like the empty state ($difference)")
    }

    @Test
    fun `the light theme renders on a light background`() {
        val image = render("welcome-light.png", dark = false, recents = sampleRecents())
        assertTrue(averageLuminance(image) > 0.6, "the light theme did not apply")
    }

    @Test
    fun `recent projects round-trip through the store`() {
        // The welcome window is only as good as the list behind it, and that list lives in a file
        // the application writes on every project open.
        val store = RecentProjects(workspace.resolve("recent-projects.txt"))
        assertTrue(store.load().isEmpty())

        val first = Files.createDirectory(workspace.resolve("alpha"))
        val second = Files.createDirectory(workspace.resolve("beta"))

        store.remember(first)
        store.remember(second)

        // Newest first, which is the order the window shows them in.
        assertEquals(listOf("beta", "alpha"), store.load().map { it.name })

        // Re-remembering moves an entry to the front rather than duplicating it.
        store.remember(first)
        assertEquals(listOf("alpha", "beta"), store.load().map { it.name })

        store.forget(first)
        assertEquals(listOf("beta"), store.load().map { it.name })

        // An entry whose directory has gone is dropped silently rather than offered and then failing.
        Files.delete(second)
        assertTrue(store.load().isEmpty())
    }

    private fun sampleRecents(): List<RecentProject> = listOf(
        RecentProject(Path.of(System.getProperty("user.home"), "Documents", "handbook")),
        RecentProject(Path.of(System.getProperty("user.home"), "src", "quill")),
        RecentProject(Path.of("/opt", "notes", "architecture-decisions")),
    )

    private fun render(fileName: String, dark: Boolean, recents: List<RecentProject>): BufferedImage {
        SkiaAvailability.require()

        val encoded = ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)).use { scene ->
            scene.setContent {
                QuillTheme(dark = dark) {
                    WelcomeContent(
                        version = "0.1.0",
                        recents = recents,
                        onOpenProject = {},
                        onNewDocument = {},
                        onBrowse = {},
                        onForget = {},
                        onToggleTheme = {},
                        darkTheme = dark,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            var image = scene.render(0L)
            var frame = 1L
            while (frame <= MAX_FRAMES && scene.hasInvalidations()) {
                image = scene.render(frame * FRAME_INTERVAL_NANOS)
                frame++
            }

            assertNotNull(image.encodeToData(EncodedImageFormat.PNG), "the frame could not be encoded").bytes
        }

        Files.write(outputDirectory.resolve(fileName), encoded)
        return assertNotNull(ImageIO.read(ByteArrayInputStream(encoded)), "the frame is not a readable PNG")
    }

    private fun distinctColours(image: BufferedImage): Int {
        val seen = HashSet<Int>()
        for (y in 0 until image.height step 4) {
            for (x in 0 until image.width step 4) {
                seen += image.getRGB(x, y) and 0xFFFFFF
                if (seen.size > 4_096) return seen.size
            }
        }
        return seen.size
    }

    private fun averageLuminance(image: BufferedImage): Double {
        var total = 0.0
        var count = 0L
        for (y in 0 until image.height step 4) {
            for (x in 0 until image.width step 4) {
                val rgb = image.getRGB(x, y)
                total += (
                    0.2126 * ((rgb shr 16) and 0xFF) +
                        0.7152 * ((rgb shr 8) and 0xFF) +
                        0.0722 * (rgb and 0xFF)
                    ) / 255.0
                count++
            }
        }
        return if (count == 0L) 0.0 else total / count
    }

    private fun differingPixelRatio(first: BufferedImage, second: BufferedImage): Double {
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
