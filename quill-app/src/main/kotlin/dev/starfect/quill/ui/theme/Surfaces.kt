package dev.starfect.quill.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How the shell's regions are shaped and separated.
 *
 * The platform ships two surface languages, and they disagree with each other on purpose.
 *
 * **Flat** is the New UI: panels and editor are one continuous work surface, separated by a small
 * tone step and nothing else. No radius, no gaps, no outlines.
 *
 * **Islands** is the 2026 default: the same regions become rounded panels with real gaps between
 * them and a visible border, floating on a recessed window background. The stated goals are clearer
 * separation between editor, tool window and panel, a stronger tool window boundary, and a more
 * recognisable active tab.
 *
 * They are not a spectrum to be split down the middle — a half-rounded panel with a half-visible gap
 * is neither. So the geometry lives in one object that a region asks for its shape, and switching
 * style switches every region at once.
 */
@Immutable
public class SurfaceStyle(
    /** Corner radius of a top-level region: the editor, a docked tool window. */
    public val regionRadius: Dp,
    /** Gap between adjacent regions, and between a region and the window edge. */
    public val regionGap: Dp,
    /** Border around a region, or [Color.Transparent] when the tone step does the work. */
    public val regionBorder: Color,
    /** What shows through the gaps. Equal to the panel tone when there are no gaps. */
    public val windowBackground: Color,
    /** Whether regions are separated by gaps rather than by shared edges. */
    public val separated: Boolean,
) {
    public companion object {
        /** The New UI: one continuous surface. */
        public fun flat(palette: ShellPalette): SurfaceStyle = SurfaceStyle(
            regionRadius = 0.dp,
            regionGap = 0.dp,
            regionBorder = Color.Transparent,
            windowBackground = palette.toolWindowBackground,
            separated = false,
        )

        /**
         * Islands: rounded, separated regions on a recessed background.
         *
         * The recess is what makes a gap read as depth rather than as a hole — the background behind
         * the islands is a step *away* from the panels, in the direction the theme is already going.
         */
        public fun islands(palette: ShellPalette, dark: Boolean): SurfaceStyle = SurfaceStyle(
            regionRadius = 10.dp,
            regionGap = 6.dp,
            regionBorder = palette.border,
            windowBackground = if (dark) Color(0xFF1A1B1E) else Color(0xFFEDEFF2),
            separated = true,
        )

        public fun of(palette: ShellPalette, dark: Boolean, islands: Boolean): SurfaceStyle =
            if (islands) islands(palette, dark) else flat(palette)
    }
}

public val LocalSurfaceStyle: ProvidableCompositionLocal<SurfaceStyle> =
    staticCompositionLocalOf { SurfaceStyle.flat(ShellPalette.Dark) }

/**
 * Shapes a top-level region — the editor, a docked tool window — according to the surface style.
 *
 * In the flat style this is a plain background fill and nothing else, which is what keeps the New UI
 * look exactly as it was. In Islands it insets the region by half a gap, rounds it, fills it and
 * draws its border, so neighbouring regions leave a full gap between them.
 */
@Composable
public fun Modifier.regionSurface(fill: Color, style: SurfaceStyle = LocalSurfaceStyle.current): Modifier {
    if (!style.separated) return this.background(fill)

    val shape = RoundedCornerShape(style.regionRadius)
    return this
        .padding(style.regionGap / 2)
        .clip(shape)
        .background(fill)
        .border(1.dp, style.regionBorder, shape)
}
