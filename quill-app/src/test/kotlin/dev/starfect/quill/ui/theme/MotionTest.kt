package dev.starfect.quill.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import dev.starfect.quill.model.Dock
import dev.starfect.quill.ui.SkiaAvailability
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * The shell's motion, checked for the two ways animation goes wrong in a tool.
 *
 * The first is *duration*: every frame an element spends fading is a frame the reader spends
 * waiting to find out whether their click landed, so the numbers are bounded rather than left to
 * taste. The second is *termination*: an animation that never settles is an infinite repaint loop,
 * which on a desktop application is a core pinned for as long as the window is open. The render
 * tests below catch that by asserting the scene stops asking to be drawn — and then that what it
 * settled on is the end state rather than somewhere part-way.
 */
class MotionTest {

    private companion object {
        const val FRAME_INTERVAL_NANOS = 16_000_000L

        /** About a second at 60 Hz. Every transition here is under a quarter of that. */
        const val MAX_FRAMES = 60

        const val WIDTH = 320
        const val HEIGHT = 200
        const val PANEL_WIDTH = 120

        /** Nothing else in the scene is this colour, so counting it counts the panel. */
        val PANEL_COLOUR = Color(0xFF, 0x44, 0x88)
        val GROUND_COLOUR = Color(0x11, 0x11, 0x11)
    }

    @Test
    fun `durations stay inside the band a tool can afford`() {
        // Below about 80 ms a transition is not perceived as motion, only as a slightly soft edge;
        // above about 250 ms it is something the reader waits through.
        assertTrue(Motion.STATE_MILLIS in 80..200, "state change: ${Motion.STATE_MILLIS} ms")
        assertTrue(Motion.ENTER_MILLIS in 80..250, "enter: ${Motion.ENTER_MILLIS} ms")
        assertTrue(Motion.EXIT_MILLIS in 60..200, "exit: ${Motion.EXIT_MILLIS} ms")
    }

    @Test
    fun `leaving is quicker than arriving`() {
        // Something on its way out is in the way. Something arriving can afford to be followed.
        assertTrue(
            Motion.EXIT_MILLIS < Motion.ENTER_MILLIS,
            "exit (${Motion.EXIT_MILLIS} ms) must be quicker than enter (${Motion.ENTER_MILLIS} ms)",
        )
    }

    @Test
    fun `a hover is not slower than a panel opening`() {
        assertTrue(
            Motion.STATE_MILLIS <= Motion.ENTER_MILLIS,
            "a colour crossing must not take longer than a whole panel arriving",
        )
    }

    @Test
    fun `every dock has a transition in both directions`() {
        Dock.entries.forEach { dock ->
            assertTrue(Motion.dockEnter(dock) != EnterTransition.None, "$dock has no enter transition")
            assertTrue(Motion.dockExit(dock) != ExitTransition.None, "$dock has no exit transition")
        }
    }

    @Test
    fun `a panel opening settles, and is fully there when it does`() {
        SkiaAvailability.require()
        val (frames, image) = renderPanel(from = false, to = true)

        assertTrue(frames < MAX_FRAMES, "the opening animation never settled")
        assertEquals(
            PANEL_WIDTH,
            panelColumns(image),
            "the panel stopped animating before it reached its full width, after $frames frames",
        )
    }

    @Test
    fun `a panel closing settles, and is fully gone when it does`() {
        SkiaAvailability.require()
        val (frames, image) = renderPanel(from = true, to = false)

        assertTrue(frames < MAX_FRAMES, "the closing animation never settled")
        assertEquals(
            0,
            panelColumns(image),
            "the panel stopped animating part-way out, leaving a sliver behind after $frames frames",
        )
    }

    @Test
    fun `a panel is genuinely animating rather than snapping`() {
        SkiaAvailability.require()

        // The point of the previous two tests is that the animation ends. The point of this one is
        // that there was an animation: a transition set to zero duration would pass both of them.
        val widths = renderPanelWidths(from = false, to = true)
        val partial = widths.filter { it > 0 && it < PANEL_WIDTH }

        assertTrue(
            partial.size >= 3,
            "the panel jumped straight to full width; widths seen were $widths",
        )
    }

    /**
     * Renders a docked panel through a visibility change and answers how long it took to settle.
     *
     * The scene is rendered until it stops reporting invalidations, which for an animation is
     * exactly when it has finished. Nothing here has a caret, so quiescence is reachable — which is
     * why this is its own scene rather than a corner of the shell render, where the blinking caret
     * means the scene never goes quiet.
     */
    private fun renderPanel(from: Boolean, to: Boolean): Pair<Int, BufferedImage> {
        var frames = 0
        var last: BufferedImage? = null

        scene(from) { render, flip ->
            render(0L)
            flip(to)
            var frame = 1
            var image = render(frame * FRAME_INTERVAL_NANOS)
            while (frame < MAX_FRAMES && sceneInvalidated()) {
                frame++
                image = render(frame * FRAME_INTERVAL_NANOS)
            }
            frames = frame
            last = image
        }

        return frames to assertNotNull(last, "nothing rendered")
    }

    /** Every panel width seen while the transition runs, for checking it moved through them. */
    private fun renderPanelWidths(from: Boolean, to: Boolean): List<Int> {
        val widths = mutableListOf<Int>()

        scene(from) { render, flip ->
            render(0L)
            flip(to)
            var frame = 1
            while (frame < MAX_FRAMES) {
                widths += panelColumns(render(frame * FRAME_INTERVAL_NANOS))
                if (!sceneInvalidated()) break
                frame++
            }
        }

        return widths
    }

    private var invalidated: () -> Boolean = { false }

    private fun sceneInvalidated(): Boolean = invalidated()

    /**
     * Mounts the panel in an offscreen scene and hands the body a way to render and to toggle it.
     *
     * The visibility is a `mutableStateOf` read inside the composition, so flipping it between
     * renders is exactly what happens when the controller changes the workspace.
     */
    private fun scene(
        initiallyVisible: Boolean,
        body: (render: (Long) -> BufferedImage, flip: (Boolean) -> Unit) -> Unit,
    ) {
        val visible = mutableStateOf(initiallyVisible)

        ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)).use { composeScene ->
            composeScene.setContent {
                Box(Modifier.fillMaxSize().background(GROUND_COLOUR)) {
                    Row(Modifier.fillMaxSize()) {
                        AnimatedVisibility(
                            visible = visible.value,
                            enter = Motion.dockEnter(Dock.LEFT),
                            exit = Motion.dockExit(Dock.LEFT),
                        ) {
                            Box(
                                Modifier.width(PANEL_WIDTH.dp)
                                    .fillMaxHeight()
                                    .background(PANEL_COLOUR)
                            )
                        }
                    }
                }
            }

            invalidated = { composeScene.hasInvalidations() }

            body(
                { nanos ->
                    val encoded = assertNotNull(
                        composeScene.render(nanos).encodeToData(EncodedImageFormat.PNG),
                        "the frame could not be encoded",
                    ).bytes
                    assertNotNull(ImageIO.read(ByteArrayInputStream(encoded)), "the frame is not a readable PNG")
                },
                { visible.value = it },
            )

            invalidated = { false }
        }
    }

    /**
     * How many columns of the panel's own colour are on screen.
     *
     * Counted at full opacity only. The panel fades as well as expanding, so a column part-way
     * through the fade is a blend of the panel and the ground and is not counted — which is
     * deliberate: "fully there" means both finished.
     */
    private fun panelColumns(image: BufferedImage): Int {
        val panel = PANEL_COLOUR.toArgb()
        return (0 until image.width).count { x -> image.getRGB(x, image.height / 2) == panel }
    }
}
