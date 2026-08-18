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
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Closing a tab.
 *
 * It used to wait for the document's derivation on the calling thread — the UI thread — so closing
 * a tab froze the window for as long as the parse took. On a large file that is hundreds of
 * milliseconds, and "Close All" froze it once per tab.
 *
 * The assertion is a wall-clock bound, which is a shape of test to be careful with: it is loose
 * enough that only the structural fault trips it. Waiting for a derivation of this document takes
 * far longer than the ceiling here, and not waiting takes microseconds.
 */
class CloseDocumentTest {

    private lateinit var scope: CoroutineScope
    private lateinit var controller: QuillController
    private lateinit var directory: Path

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        controller = QuillController(scope, QuillEngine.create(darkTheme = true))
        directory = Files.createTempDirectory("quill-close")
    }

    @AfterTest
    fun tearDown() {
        controller.close()
        scope.cancel()
    }

    /** Big enough that deriving it is clearly measurable. */
    private fun document(lines: Int) = buildString {
        for (i in 0 until lines) {
            if (i % 20 == 0) append("## Section ").append(i).append("\n\n")
            append("Prose with **bold**, `code`, a [link](https://example.com) and 한국어 on ").append(i).append(".\n")
        }
    }

    private fun openDerived(name: String, lines: Int): Long {
        val file = directory.resolve(name)
        file.writeText(document(lines))
        controller.openFile(file)
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            val session = controller.state.value.documents.firstOrNull { it.path == file }
            if (session != null && session.derivedVersion >= session.engineVersion) return session.id
            Thread.sleep(10)
        }
        error("never derived")
    }

    @Test
    fun `closing a tab does not wait for the parse it interrupts`() {
        val id = openDerived("big.md", 2_000)

        // Start a derivation and close while it is in flight, which is the case that used to block.
        val text = controller.state.value.documents.first { it.id == id }.text.text
        controller.onTextChanged(id, TextFieldValue(text + "\nmore", TextRange(text.length + 5)))

        val started = System.nanoTime()
        controller.closeDocument(id)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertTrue(
            elapsedMillis < 150,
            "closing a tab blocked the caller for $elapsedMillis ms; it must not wait for the parse",
        )
        assertTrue(controller.state.value.documents.none { it.id == id })
    }

    @Test
    fun `closing every tab is quick, however many there are`() {
        repeat(6) { openDerived("doc$it.md", 800) }
        assertEquals(6, controller.state.value.documents.size)

        val started = System.nanoTime()
        controller.closeAllDocuments()
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertTrue(
            elapsedMillis < 500,
            "Close All blocked the caller for $elapsedMillis ms; it used to wait once per tab",
        )
        assertTrue(controller.state.value.documents.isEmpty())
    }

    @Test
    fun `the engine survives closing documents while they are being derived`() {
        // The reason the wait existed: freeing a document out from under a worker reading it is a
        // crash in native code. The wait moved off the UI thread, it did not go away, and this is
        // the test that it still happens somewhere.
        repeat(8) {
            val id = openDerived("churn$it.md", 400)
            val text = controller.state.value.documents.first { it.id == id }.text.text
            controller.onTextChanged(id, TextFieldValue(text + "x", TextRange(text.length + 1)))
            controller.closeDocument(id)
        }
        Thread.sleep(500)

        // Still usable afterwards, which it would not be if a handle had been freed early.
        val id = openDerived("after.md", 100)
        assertTrue(controller.state.value.documents.any { it.id == id })
    }
}
