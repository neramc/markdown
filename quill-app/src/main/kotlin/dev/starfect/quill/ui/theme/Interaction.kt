package dev.starfect.quill.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.gestures.Orientation as DragOrientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import java.awt.Cursor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.ui.Orientation

/**
 * What a surface is doing, and what colour that makes it.
 *
 * Every hoverable thing in the shell — a toolbar button, a stripe button, a tree row, a tab, a menu
 * item — resolves its background through here. The alternative is each component inventing its own
 * hover, and the symptom of that is a UI where hovering a tree row and hovering a toolbar button
 * feel like two different products.
 *
 * Order matters: a pressed control is pressed whether or not it is also selected, and a disabled one
 * responds to nothing.
 */
@Immutable
public enum class SurfaceState {
    NORMAL,
    HOVERED,
    PRESSED,
    SELECTED,

    /** Selected, but the container holding it does not have focus. */
    SELECTED_INACTIVE,

    /** A toggle that is on: a stripe button for an open tool window, a view-mode switch, a chip. */
    TOGGLED,
    DISABLED,
    ;

    /** The fill for this state. [Color.Transparent] when the surface should show what is behind it. */
    public fun background(palette: ShellPalette): Color = when (this) {
        NORMAL, DISABLED -> Color.Transparent
        HOVERED -> palette.hoverBackground
        PRESSED -> palette.pressedBackground
        SELECTED -> palette.selectionBackground
        SELECTED_INACTIVE, TOGGLED -> palette.inactiveSelectionBackground
    }

    /** The content colour for this state. */
    public fun contentColor(palette: ShellPalette): Color = when (this) {
        DISABLED -> palette.mutedText
        else -> palette.text
    }

    /** The icon tint for this state. */
    public fun iconTint(palette: ShellPalette): Color = when (this) {
        DISABLED -> palette.mutedIcon
        else -> palette.icon
    }

    public companion object {
        /**
         * The state of a row in a list or a tree, where selection means "this is the current item".
         *
         * Such a selection is blue while its container has focus and grey once focus moves on. That
         * pair is not decoration: it is the only thing in the window saying where typing will go, and
         * a screenshot of the IDE shows both at once — a blue row in the focused Problems list and a
         * grey one in the unfocused project tree.
         */
        public fun of(
            hovered: Boolean = false,
            pressed: Boolean = false,
            selected: Boolean = false,
            focused: Boolean = true,
            enabled: Boolean = true,
        ): SurfaceState = when {
            !enabled -> DISABLED
            pressed -> PRESSED
            selected && focused -> SELECTED
            selected -> SELECTED_INACTIVE
            hovered -> HOVERED
            else -> NORMAL
        }

        /**
         * The state of a control that is switched on or off, rather than selected.
         *
         * A stripe button for an open tool window, the view-mode switch, the find bar's Aa/W/.*
         * chips. These are never "where typing will go", so they take the grey and leave the blue to
         * mean what it means — which is what stops accent leaking onto every toggled thing in the
         * window.
         */
        public fun ofToggle(
            hovered: Boolean = false,
            pressed: Boolean = false,
            on: Boolean = false,
            enabled: Boolean = true,
        ): SurfaceState = when {
            !enabled -> DISABLED
            pressed -> PRESSED
            on -> TOGGLED
            hovered -> HOVERED
            else -> NORMAL
        }
    }
}

/**
 * A clickable surface that fills itself according to [SurfaceState].
 *
 * This is the one place hover and selection are painted, so a change to how the shell responds to
 * the pointer is a change in a single file rather than in a dozen components that have to be found
 * first.
 */
@Composable
public fun Modifier.interactiveSurface(
    onClick: () -> Unit,
    palette: ShellPalette,
    selected: Boolean = false,
    enabled: Boolean = true,
    focused: Boolean = true,
    /** True when [selected] means "switched on" rather than "this is the current item". */
    toggle: Boolean = false,
    cornerRadius: Dp = Tokens.Radius.Row,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
): Modifier {
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val state = if (toggle) {
        SurfaceState.ofToggle(hovered = hovered, pressed = pressed, on = selected, enabled = enabled)
    } else {
        SurfaceState.of(
            hovered = hovered,
            pressed = pressed,
            selected = selected,
            focused = focused,
            enabled = enabled,
        )
    }

    // One animation, inherited by every hoverable thing in the shell. Animating here rather than at
    // each component is what keeps a tree row and a toolbar button from fading at different rates —
    // which is far more noticeable than either rate on its own.
    val fill by animateColorAsState(
        targetValue = state.background(palette),
        animationSpec = Motion.state(),
        label = "surfaceFill",
    )

    return this
        .clip(RoundedCornerShape(cornerRadius))
        .background(fill)
        .hoverable(interactionSource, enabled = enabled)
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}

/**
 * A one-pixel separator in the shell's border colour.
 *
 * Drawn here rather than with Jewel's `Divider`, which resolves its colour through the theme's
 * divider style and ignored the colour passed to it: the separators between the tool window rail,
 * the project panel and the editor came out near-white in the real window. A bright line between
 * every region is the single loudest thing a low-contrast shell can carry, and it was the first
 * thing visible in a screenshot.
 *
 * A [Box] with a background is not less capable here — a separator is a filled rectangle — and it is
 * the only way to be certain what colour ends up on screen.
 */
@Composable
public fun ShellDivider(
    orientation: Orientation,
    modifier: Modifier = Modifier,
    color: Color = LocalShellPalette.current.border,
) {
    Box(
        modifier
            .then(
                when (orientation) {
                    Orientation.Horizontal -> Modifier.fillMaxWidth().height(1.dp)
                    Orientation.Vertical -> Modifier.fillMaxHeight().width(1.dp)
                }
            )
            .background(color)
    )
}

/**
 * The draggable edge of a docked tool window.
 *
 * A panel whose width cannot be changed is one of the clearest signs that a window is a mock-up
 * rather than a tool: the first thing anyone does with a side panel is drag it. The handle is wider
 * than it looks — a 1px line is drawn but 5px respond to the pointer, because a splitter you have to
 * aim at is worse than no splitter.
 *
 * It draws nothing of its own until pointed at, at which point it takes the splitter colour, the
 * same way the editor's own split handle behaves.
 */
@Composable
public fun ToolWindowResizeHandle(
    onDrag: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalShellPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val dragged by interaction.collectIsDraggedAsState()
    val density = LocalDensity.current

    val line by animateColorAsState(
        targetValue = if (hovered || dragged) palette.splitter else Color.Transparent,
        animationSpec = Motion.state(),
        label = "resizeHandle",
    )

    Box(
        modifier
            .fillMaxHeight()
            .width(ResizeHandleWidth)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .hoverable(interaction)
            .draggable(
                orientation = DragOrientation.Horizontal,
                interactionSource = interaction,
                state = rememberDraggableState { delta -> onDrag(with(density) { delta.toDp().value }) },
            )
            .drawBehind {
                // Painted down the middle of the wider hit area, so the visible line does not move
                // when the pointer finds it.
                val x = size.width / 2f
                drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            }
    )
}

/** Hit width of a resize handle. Wider than the line it draws, on purpose. */
private val ResizeHandleWidth = 5.dp
