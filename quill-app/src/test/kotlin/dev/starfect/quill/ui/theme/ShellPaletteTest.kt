package dev.starfect.quill.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins the relationships the shell's colours have to each other.
 *
 * Every one of these was got wrong at least once by picking a plausible-looking value, and every one
 * was settled by sampling a real IntelliJ window rather than by argument. The exact hex codes matter
 * less than the relationships between them, so that is what is asserted: a future retune can move
 * the whole palette, but not flatten the panel/editor step, not make a separator brighter than what
 * it divides, and not collapse the two selection states into one.
 */
class ShellPaletteTest {

    private fun Color.brightness(): Float = maxOf(red, green, blue)

    @Test
    fun `panels sit clearly above the editor rather than beside it`() {
        // Dark: panels are lighter than the editor by twelve points, which is enough that nothing is
        // drawn between them. Light has less room — #F7F8FA against white is eight points — so its
        // separators stay visible where dark's are dropped. Either way the step has to exist: an
        // earlier pass had the two one point apart, and every boundary then needed a line.
        val minimum = mapOf(ShellPalette.Dark to 0.04f, ShellPalette.Light to 0.015f)
        for ((palette, floor) in minimum) {
            val step = kotlin.math.abs(
                palette.toolWindowBackground.brightness() - palette.tabSelectedBackground.brightness()
            )
            assertTrue(step > floor, "panel and editor are only $step apart, below $floor")
        }
    }

    @Test
    fun `the tab strip takes the editor's tone, not the panels'`() {
        // This is what makes the strip and the document read as one surface. A strip painted in the
        // panel colour puts a band across the top of the editor.
        assertEquals(ShellPalette.Dark.tabSelectedBackground, ShellPalette.Dark.tabBarBackground)
        assertEquals(ShellPalette.Light.tabSelectedBackground, ShellPalette.Light.tabBarBackground)
        assertNotEquals(ShellPalette.Dark.toolWindowBackground, ShellPalette.Dark.tabBarBackground)
        assertNotEquals(ShellPalette.Light.toolWindowBackground, ShellPalette.Light.tabBarBackground)
    }

    @Test
    fun `a separator is darker than the panel it divides`() {
        // The IDE separates two same-toned regions with a dark line. A lighter one is the single
        // loudest thing a low-contrast shell can carry, and it was the first thing visible in the
        // first screenshot of this window.
        for (palette in listOf(ShellPalette.Dark, ShellPalette.Light)) {
            assertTrue(
                palette.border.brightness() < palette.toolWindowBackground.brightness(),
                "border ${palette.border} is not darker than panel ${palette.toolWindowBackground}",
            )
        }
    }

    @Test
    fun `the split handle is the one separator allowed to be visible`() {
        // It has to be found with a pointer, so it is the exception the rule is written around.
        assertTrue(
            ShellPalette.Dark.splitter.brightness() > ShellPalette.Dark.toolWindowBackground.brightness(),
        )
        assertTrue(
            ShellPalette.Light.splitter.brightness() < ShellPalette.Light.toolWindowBackground.brightness(),
        )
    }

    @Test
    fun `a focused selection is blue and an unfocused one is grey`() {
        // Both are low contrast, but they are not the same colour: the pair is the only thing in the
        // window saying where typing will go.
        for (palette in listOf(ShellPalette.Dark, ShellPalette.Light)) {
            val focused = palette.selectionBackground
            val inactive = palette.inactiveSelectionBackground
            assertNotEquals(focused, inactive, "the two selection states are the same colour")
            assertTrue(
                focused.blue - focused.red > 0.08f,
                "the focused selection $focused is not recognisably blue",
            )
            assertTrue(
                kotlin.math.abs(inactive.blue - inactive.red) < 0.06f,
                "the unfocused selection $inactive is tinted; it should be a neutral grey",
            )
        }
    }

    @Test
    fun `a toggled control takes the grey, not the selection blue`() {
        // Otherwise every open tool window, every view-mode button and every find-bar chip is blue,
        // and the accent stops meaning "this is where you are".
        for (palette in listOf(ShellPalette.Dark, ShellPalette.Light)) {
            assertEquals(
                palette.inactiveSelectionBackground,
                SurfaceState.TOGGLED.background(palette),
            )
            assertNotEquals(
                palette.selectionBackground,
                SurfaceState.TOGGLED.background(palette),
            )
        }
    }

    @Test
    fun `hover is weaker than press, and press weaker than selection`() {
        // A hover as strong as a selection makes a tree look permanently half-selected.
        for (palette in listOf(ShellPalette.Dark, ShellPalette.Light)) {
            val hover = palette.hoverBackground.brightness()
            val press = palette.pressedBackground.brightness()
            val selected = palette.inactiveSelectionBackground.brightness()
            val panel = palette.toolWindowBackground.brightness()
            if (palette === ShellPalette.Dark) {
                assertTrue(panel < hover && hover < press, "dark: $panel, $hover, $press")
                assertTrue(hover < selected, "hover $hover is not weaker than selection $selected")
            } else {
                assertTrue(panel > hover && hover > press, "light: $panel, $hover, $press")
                assertTrue(hover > selected, "hover $hover is not weaker than selection $selected")
            }
        }
    }

    @Test
    fun `text is layered in three distinct steps`() {
        for (palette in listOf(ShellPalette.Dark, ShellPalette.Light)) {
            val steps = listOf(palette.text, palette.secondaryText, palette.mutedText).map { it.brightness() }
            val ordered = if (palette === ShellPalette.Dark) steps else steps.map { 1f - it }
            assertTrue(
                ordered[0] > ordered[1] && ordered[1] > ordered[2],
                "text steps are not ordered primary > secondary > muted: $steps",
            )
        }
    }

    @Test
    fun `every surface state resolves a colour in both palettes`() {
        for (palette in listOf(ShellPalette.Dark, ShellPalette.Light)) {
            for (state in SurfaceState.entries) {
                state.background(palette)
                state.contentColor(palette)
                state.iconTint(palette)
            }
        }
    }
}
