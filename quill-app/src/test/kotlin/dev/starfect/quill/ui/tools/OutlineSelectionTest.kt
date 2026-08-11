package dev.starfect.quill.ui.tools

import dev.starfect.quill.QuillController
import dev.starfect.quill.bridge.QuillEngine
import java.nio.file.Files
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

/**
 * The Structure panel marks the heading the caret is under.
 *
 * §11 of the design brief lists `TreeSelection` among the things the Structure view must share with
 * the project tree, and for a while it shared everything *except* that: the rows had the same height,
 * indentation and hover, and no row was ever selected. Clicking the panel and watching the fill was
 * how it was found — the row went to hover and back to nothing.
 *
 * The rule the panel applies is asserted here rather than in the renderer, because "which entry is
 * current" is the part that can be wrong while everything still draws.
 */
class OutlineSelectionTest {

    private lateinit var scope: CoroutineScope
    private lateinit var controller: QuillController

    private companion object {
        val SOURCE = """
            # First

            Text under the first heading.

            ## Second

            Text under the second heading.

            ### Third

            Text under the third heading.
        """.trimIndent()

        const val DERIVE_TIMEOUT_NANOS = 20_000_000_000L
    }

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

    @Test
    fun `the current entry is the last heading at or before the caret`() {
        val file = Files.createTempDirectory("quill-outline").resolve("doc.md")
        file.writeText(SOURCE)
        controller.openProject(file.parent)
        controller.openFile(file)

        val deadline = System.nanoTime() + DERIVE_TIMEOUT_NANOS
        while (System.nanoTime() < deadline &&
            controller.state.value.activeDocument?.outline?.isEmpty() != false
        ) {
            Thread.sleep(10)
        }

        val document = assertNotNull(controller.state.value.activeDocument, "no document was opened")
        val outline = document.outline
        assertEquals(listOf("First", "Second", "Third"), outline.map { it.title })

        // The same expression the panel uses. Each case is a caret position and the heading whose
        // row should be filled.
        fun currentAt(caret: Int): Int? = outline.indexOfLast { it.offset <= caret }.takeIf { it >= 0 }

        assertEquals(0, currentAt(outline[0].offset), "the caret on a heading selects that heading")
        assertEquals(0, currentAt(outline[1].offset - 1), "text under a heading selects that heading")
        assertEquals(1, currentAt(outline[1].offset), "the caret on the second heading selects it")
        assertEquals(2, currentAt(SOURCE.length), "the caret at the end selects the last heading")
    }

    @Test
    fun `a caret above the first heading selects nothing`() {
        // Front matter, or a paragraph before the first heading. Filling the first row there would
        // claim the caret is somewhere it is not, which is worse than filling none.
        val file = Files.createTempDirectory("quill-outline").resolve("preamble.md")
        file.writeText("A sentence before any heading.\n\n# First\n\nUnder it.\n")
        controller.openProject(file.parent)
        controller.openFile(file)

        val deadline = System.nanoTime() + DERIVE_TIMEOUT_NANOS
        while (System.nanoTime() < deadline &&
            controller.state.value.activeDocument?.outline?.isEmpty() != false
        ) {
            Thread.sleep(10)
        }

        val outline = assertNotNull(controller.state.value.activeDocument).outline
        assertEquals(listOf("First"), outline.map { it.title })
        val headingOffset = outline.first().offset
        assertTrue(headingOffset > 0, "the fixture put the heading at offset 0")

        fun currentAt(caret: Int): Int? = outline.indexOfLast { it.offset <= caret }.takeIf { it >= 0 }

        assertEquals(null, currentAt(0), "a caret in the preamble selected a heading")
        assertEquals(null, currentAt(headingOffset - 1))
        assertEquals(0, currentAt(headingOffset))
    }

    @Test
    fun `a document with no headings selects nothing`() {
        val file = Files.createTempDirectory("quill-outline").resolve("plain.md")
        file.writeText("Just a paragraph, with no heading anywhere in it.\n")
        controller.openProject(file.parent)
        controller.openFile(file)

        val deadline = System.nanoTime() + DERIVE_TIMEOUT_NANOS
        while (System.nanoTime() < deadline &&
            controller.state.value.activeDocument?.blocks?.isEmpty() != false
        ) {
            Thread.sleep(10)
        }

        val outline = assertNotNull(controller.state.value.activeDocument).outline
        assertEquals(emptyList(), outline)
        assertEquals(null, outline.indexOfLast { it.offset <= 0 }.takeIf { it >= 0 })
    }
}
