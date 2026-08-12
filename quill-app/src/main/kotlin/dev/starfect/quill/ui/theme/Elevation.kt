package dev.starfect.quill.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The shell's gradients, and the rule they answer to.
 *
 * An IDE is not a place for decorative colour. Every gradient here is doing one of two jobs, and
 * anything that is doing neither has been left out:
 *
 * 1. **Saying which way is up.** A surface that floats above another catches a little more light at
 *    its top edge. That is the whole of the effect — two or three points of lift over the first few
 *    pixels, gone by the time the eye reaches the content.
 * 2. **Carrying identity.** The title bar takes a wash of the project's own colour, which is the one
 *    place IntelliJ itself uses a gradient, and the reason a window belonging to one project is
 *    recognisable at a glance in a row of them.
 *
 * The numbers are small on purpose. A gradient you can point at in a screenshot is too strong for a
 * window somebody looks at for eight hours: the test is that removing it should be noticeable while
 * describing it should be hard.
 */
public object Elevation {

    /** How far the top-edge highlight reaches before it is gone. */
    private const val HIGHLIGHT_STOP = 0.12f

    /** How much lighter that edge is. Three points at most. */
    private const val HIGHLIGHT_ALPHA = 0.05f

    /** The wash of project colour across the title bar. */
    private const val IDENTITY_ALPHA = 0.10f

    /**
     * A surface that floats: a dialog, a popup, a menu.
     *
     * The fill plus a highlight that fades out over the top eighth. Painted as one brush rather than
     * an overlay, so it cannot be double-applied by a caller that also sets a background.
     */
    public fun floatingSurface(fill: Color): Brush = Brush.verticalGradient(
        0f to fill.lighten(HIGHLIGHT_ALPHA),
        HIGHLIGHT_STOP to fill,
        1f to fill,
    )

    /**
     * The selected editor tab in the Islands style, where the tab is a filled shape of its own.
     *
     * In the flat style the selected tab takes the editor's colour and is marked by an accent line,
     * so there is no shape to light — this brush is not used there.
     */
    public fun activeTab(fill: Color): Brush = Brush.verticalGradient(
        0f to fill.lighten(HIGHLIGHT_ALPHA * 1.4f),
        1f to fill,
    )

    /**
     * The title bar's wash of project colour.
     *
     * Horizontal and left-anchored, because that is where the project widget sits: the colour is
     * strongest under the thing it identifies and gone by the middle of the window. Returns the
     * colour Jewel's own `TitleBar` takes as its gradient start, so this rides the platform's
     * implementation rather than painting over it.
     *
     * Pre-blended against [ground] and returned **opaque**. A translucent colour was the obvious
     * thing to hand over and it came out wrong on screen: Jewel takes the value as the gradient's
     * start colour rather than as a wash to composite, so a badge hue at ten percent alpha painted
     * at full strength — measured at `#E0E8E0` over a `#1A1B1E` title bar, which is a white band
     * across the top of the window. Doing the mixing here means the colour handed over is already
     * the two or three points off the ground that it is supposed to be, whatever the consumer does
     * with it.
     */
    public fun projectTint(projectColor: Color, ground: Color): Color = Color(
        red = ground.red + (projectColor.red - ground.red) * IDENTITY_ALPHA,
        green = ground.green + (projectColor.green - ground.green) * IDENTITY_ALPHA,
        blue = ground.blue + (projectColor.blue - ground.blue) * IDENTITY_ALPHA,
        alpha = 1f,
    )

    /**
     * A soft shadow under a floating surface.
     *
     * Drawn as a stack of increasingly transparent rounded rectangles rather than with a blur,
     * because Compose Desktop has no cheap blur and a real one on every popup frame is not worth
     * what it buys. Four layers is enough to lose the banding at these alphas.
     */
    @Composable
    public fun Modifier.dropShadow(shape: Shape, radius: Dp = 12.dp): Modifier = this.drawWithContent {
        val steps = 4
        val extent = radius.toPx()
        for (step in steps downTo 1) {
            val spread = extent * step / steps
            val alpha = 0.055f * (1f - (step - 1f) / steps)
            val outline = shape.createOutline(
                size = androidx.compose.ui.geometry.Size(size.width + spread * 2, size.height + spread * 2),
                layoutDirection = layoutDirection,
                density = this,
            )
            translate(-spread, -spread + spread * 0.35f) {
                drawOutline(outline, Color.Black.copy(alpha = alpha))
            }
        }
        drawContent()
    }
}

/** Mixes [amount] of white into a colour, for a highlight that stays in the surface's own hue. */
internal fun Color.lighten(amount: Float): Color = Color(
    red = red + (1f - red) * amount,
    green = green + (1f - green) * amount,
    blue = blue + (1f - blue) * amount,
    alpha = alpha,
)

/** Fills with [brush], for a surface that floats above the shell. */
@Composable
public fun Modifier.floatingFill(fill: Color): Modifier = this.background(Elevation.floatingSurface(fill))

/**
 * A separator that fades out at both ends.
 *
 * A full-bleed line meeting a panel's edge draws attention to the corner it lands in; one that
 * arrives from nothing reads as a division rather than as a drawn object. Used where a separator
 * spans a whole region — under a tool window header, above the status bar — and not for the short
 * ones inside a toolbar, where there is no length over which to fade.
 */
@Composable
public fun FadingDivider(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.background(
            Brush.horizontalGradient(
                0f to color.copy(alpha = 0f),
                0.08f to color,
                0.92f to color,
                1f to color.copy(alpha = 0f),
            )
        ).fillMaxSize()
    )
}

private inline fun androidx.compose.ui.graphics.drawscope.DrawScope.translate(
    left: Float,
    top: Float,
    block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit,
) {
    drawContext.transform.translate(left, top)
    block()
    drawContext.transform.translate(-left, -top)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOutline(
    outline: androidx.compose.ui.graphics.Outline,
    color: Color,
) {
    when (outline) {
        is androidx.compose.ui.graphics.Outline.Rectangle -> drawRect(
            color,
            topLeft = Offset(outline.rect.left, outline.rect.top),
            size = outline.rect.size,
        )

        is androidx.compose.ui.graphics.Outline.Rounded ->
            drawPath(androidx.compose.ui.graphics.Path().apply { addRoundRect(outline.roundRect) }, color)

        is androidx.compose.ui.graphics.Outline.Generic -> drawPath(outline.path, color)
    }
}
