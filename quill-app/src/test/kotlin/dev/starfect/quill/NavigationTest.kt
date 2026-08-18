package dev.starfect.quill

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.starfect.quill.bridge.QuillEngine
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The toolbar's back and forward arrows, driven through the controller.
 *
 * What is worth testing here is not the list — [dev.starfect.quill.model.NavigationHistoryTest]
 * covers that — but which of Quill's actions count as navigation. Typing must not, jumping must,
 * and Back must reach a file whose tab has since been closed.
 */
class NavigationTest {

    private lateinit var scope: CoroutineScope
    private lateinit var controller: QuillController
    private lateinit var directory: Path

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        controller = QuillController(scope, QuillEngine.create(darkTheme = true))
        directory = Files.createTempDirectory("quill-nav")
    }

    @AfterTest
    fun tearDown() {
        controller.close()
        scope.cancel()
    }

    /** 400 numbered lines, so a line number is visible in the text at that line. */
    private fun numbered() = (1..400).joinToString("\n") { "line $it" } + "\n"

    private fun open(name: String): Long {
        val file = directory.resolve(name)
        file.writeText(numbered())
        controller.openFile(file)
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            controller.state.value.documents.firstOrNull { it.path == file }?.let { return it.id }
            Thread.sleep(5)
        }
        error("never opened $name")
    }

    private fun caretLine(id: Long): Int {
        val session = controller.state.value.documents.first { it.id == id }
        return session.caretPosition.line + 1
    }

    @Test
    fun `typing is not navigation`() {
        val id = open("typing.md")
        val before = controller.state.value.navigation.places.size

        repeat(20) {
            val text = controller.state.value.documents.first { it.id == id }.text.text
            controller.onTextChanged(id, TextFieldValue(text + "x", TextRange(text.length + 1)))
        }

        assertEquals(
            before,
            controller.state.value.navigation.places.size,
            "a history that records keystrokes is a history where Back means 'undo'",
        )
    }

    @Test
    fun `a jump records where it came from and where it went`() {
        val id = open("jump.md")
        controller.goToLine(id, 5)
        controller.goToLine(id, 300)

        val history = controller.state.value.navigation
        assertTrue(history.canGoBack)
        assertEquals(300, history.current?.line)

        controller.navigateBack()
        assertEquals(5, caretLine(id), "Back returns the caret, not merely the history cursor")
    }

    @Test
    fun `forward returns to where the reader was standing`() {
        val id = open("forward.md")
        controller.goToLine(id, 20)
        controller.goToLine(id, 350)

        controller.navigateBack()
        assertEquals(20, caretLine(id))
        assertTrue(controller.state.value.navigation.canGoForward)

        controller.navigateForward()
        assertEquals(350, caretLine(id))
    }

    @Test
    fun `a round trip that changes nothing leaves the caret where it was`() {
        val id = open("roundtrip.md")
        controller.goToLine(id, 40)
        controller.goToLine(id, 240)
        // Move on from the destination without navigating, the way scrolling and clicking do.
        controller.moveCaret(id, controller.state.value.documents.first { it.id == id }.text.text.length)
        val settled = caretLine(id)

        controller.navigateBack()
        controller.navigateForward()

        assertEquals(settled, caretLine(id), "Forward must not quietly move the caret")
    }

    @Test
    fun `switching tabs is navigation`() {
        val first = open("one.md")
        val second = open("two.md")
        controller.selectDocument(first)

        assertTrue(controller.state.value.navigation.canGoBack)
        controller.navigateBack()
        assertEquals(second, controller.state.value.activeDocumentId)
    }

    @Test
    fun `re-selecting the tab you are on is not navigation`() {
        val id = open("same.md")
        val before = controller.state.value.navigation.places.size

        repeat(5) { controller.selectDocument(id) }

        assertEquals(before, controller.state.value.navigation.places.size)
    }

    @Test
    fun `back reopens a file whose tab was closed`() {
        val first = open("kept.md")
        val second = open("closed.md")
        controller.goToLine(second, 120)
        controller.selectDocument(first)
        controller.closeDocument(second)

        assertTrue(
            controller.state.value.navigation.places.any { it.path?.fileName.toString() == "closed.md" },
            "closing a tab must not erase where you have been",
        )

        controller.navigateBack()

        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            val reopened = controller.state.value.documents.firstOrNull {
                it.path == directory.resolve("closed.md")
            }
            if (reopened != null && reopened.caretPosition.line + 1 == 120) return
            Thread.sleep(10)
        }
        error("Back never reopened closed.md at line 120")
    }

    @Test
    fun `nothing to go back to leaves everything alone`() {
        val id = open("alone.md")
        controller.goToLine(id, 7)
        val before = controller.state.value

        controller.navigateForward()

        assertEquals(before.navigation, controller.state.value.navigation)
        assertFalse(controller.state.value.navigation.canGoForward)
    }
}
