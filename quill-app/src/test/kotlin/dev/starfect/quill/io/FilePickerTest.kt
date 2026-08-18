package dev.starfect.quill.io

import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two places Quill opens a native file dialog.
 *
 * This exists because of what happened without it. `KeymapTest` presses every binding the reference
 * lists, and one of them is Save As — which raises a picker. On Linux there is no display, AWT
 * throws, and the picker returns null; on the Windows and macOS runners there *is* a display, so
 * the dialog opened and waited for somebody to click it. Three platform jobs sat at that prompt for
 * hours while the release they were building never finished.
 *
 * The build now sets `java.awt.headless=true` for the test JVM, which is the actual fix — a test
 * must never open a window. This checks the property is in force and that both pickers answer
 * "nowhere" rather than throwing, since every caller treats null as "the writer cancelled".
 */
class FilePickerTest {

    @Test
    fun `the test JVM is headless`() {
        assertTrue(
            GraphicsEnvironment.isHeadless(),
            "the test JVM can open windows; a modal dialog in a test run is a hang, not a failure",
        )
    }

    @Test
    fun `asking where to save answers nowhere instead of throwing`() {
        assertNull(FileService.chooseSaveFile(suggestedName = "untitled.md"))
    }

    @Test
    fun `asking for a folder answers nowhere instead of throwing`() {
        assertNull(FileService.chooseDirectory())
    }
}
