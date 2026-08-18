package dev.starfect.quill.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import dev.starfect.quill.ui.dialog.ConfirmAction
import dev.starfect.quill.ui.dialog.ConfirmDialog
import dev.starfect.quill.ui.theme.QuillTheme
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * The confirmation, drawn.
 *
 * It is the one surface in the application that appears at the moment somebody is about to lose
 * work, and the one they will read fastest and least carefully. A render check is worth having for
 * exactly that: a dialog that lays out wrongly here is a dialog nobody reads before pressing
 * something.
 */
class ConfirmRenderTest {

    private companion object {
        const val WIDTH = 900
        const val HEIGHT = 560
        const val FRAME_INTERVAL_NANOS = 16_000_000L
        const val MAX_FRAMES = 60L
    }

    private lateinit var outputDirectory: Path

    @BeforeTest
    fun setUp() {
        System.setProperty("java.awt.headless", "true")
        outputDirectory = Path.of(System.getProperty("quill.test.output", "build/test-renders"))
        outputDirectory.createDirectories()
    }

    @Test
    fun `one unsaved document names it in the sentence`() {
        val image = render(
            "confirm-one.png",
            title = "Save changes?",
            message = "architecture.md has changes that are not on disk. Closing it without saving " +
                "discards them.",
            detail = emptyList(),
            labels = listOf("Save", "Discard", "Cancel"),
        )

        assertTrue(distinctColours(image) > 24, "the confirmation rendered blank")

        // The scrim is what makes it modal. Rendered on its own there is nothing behind it, so
        // what can be checked is the scrim itself rather than the result of compositing it: black,
        // and *partly* transparent. Opaque would hide the window the dialog is asking about, and
        // fully transparent would leave a panel floating over an undimmed editor — which is a
        // panel, and a panel is something you can ignore.
        val corner = image.getRGB(4, 4)
        val alpha = corner ushr 24 and 0xFF
        assertTrue(alpha in 40..200, "the scrim's opacity is $alpha/255, which is not a dim")
        assertEquals(0, corner and 0xFFFFFF, "the scrim is not black")
    }

    @Test
    fun `several unsaved documents are listed rather than counted`() {
        val names = listOf("architecture.md", "release-notes.md", "deployment.md", "onboarding.md")
        val listed = render(
            "confirm-many.png",
            title = "Save ${names.size} documents?",
            message = "These documents have changes that are not on disk. Closing them without " +
                "saving discards those changes.",
            detail = names,
            labels = listOf("Save All", "Discard All", "Cancel"),
        )
        val single = render(
            "confirm-one-compare.png",
            title = "Save changes?",
            message = "architecture.md has changes that are not on disk. Closing it without saving " +
                "discards them.",
            detail = emptyList(),
            labels = listOf("Save", "Discard", "Cancel"),
        )

        // The list is the whole difference between the two states, so the taller dialog is the
        // observable proof the names went in rather than being folded into a number.
        assertTrue(
            dialogHeight(listed) > dialogHeight(single),
            "listing four names did not make the dialog taller, so they are not on screen",
        )
    }

    private fun render(
        fileName: String,
        title: String,
        message: String,
        detail: List<String>,
        labels: List<String>,
    ): BufferedImage {
        SkiaAvailability.require()

        val encoded = ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)).use { scene ->
            scene.setContent {
                QuillTheme(dark = true) {
                    ConfirmDialog(
                        title = title,
                        message = message,
                        detail = detail,
                        onDismiss = {},
                        actions = labels.mapIndexed { index, label ->
                            ConfirmAction(label = label, onClick = {}, default = index == 0)
                        },
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

    /**
     * How many rows down the middle of the window belong to the dialog rather than the scrim.
     *
     * The scrim is one flat colour and the dialog's surface is another, so counting rows that
     * differ from the very top row measures the panel without needing to know where it starts.
     */
    private fun dialogHeight(image: BufferedImage): Int {
        val scrim = image.getRGB(4, 4)
        val middle = image.width / 2
        return (0 until image.height).count { y -> image.getRGB(middle, y) != scrim }
    }

    private fun distinctColours(image: BufferedImage): Int {
        val seen = HashSet<Int>()
        for (y in 0 until image.height step 2) {
            for (x in 0 until image.width step 2) {
                seen += image.getRGB(x, y)
                if (seen.size > 512) return seen.size
            }
        }
        return seen.size
    }

}
