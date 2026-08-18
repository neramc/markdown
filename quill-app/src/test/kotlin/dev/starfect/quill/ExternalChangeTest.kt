package dev.starfect.quill

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.starfect.quill.bridge.QuillEngine
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * A file that changed outside Quill.
 *
 * Saving used to be an unconditional write, so a `git checkout`, a formatter, or a second editor
 * touching a file while a document sat open destroyed that work with no message. This is the test
 * for the thing that used to be silent, and the assertion that matters is the one on the *file*:
 * whatever the UI says, the bytes on disk must survive.
 */
class ExternalChangeTest {

    private lateinit var scope: CoroutineScope
    private lateinit var controller: QuillController
    private lateinit var directory: Path

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        controller = QuillController(scope, QuillEngine.create(darkTheme = true))
        directory = Files.createTempDirectory("quill-external")
    }

    @AfterTest
    fun tearDown() {
        controller.close()
        scope.cancel()
    }

    private fun open(contents: String): Pair<Path, Long> {
        val file = directory.resolve("note.md")
        file.writeText(contents)
        controller.openFile(file)
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            val session = controller.state.value.documents.firstOrNull { it.path == file }
            if (session != null && session.derivedVersion >= session.engineVersion) return file to session.id
            Thread.sleep(10)
        }
        error("the file never opened")
    }

    private fun settle() {
        // The save path is a coroutine; give it a moment and then check the disk, which is the only
        // thing that actually matters here.
        Thread.sleep(400)
    }

    @Test
    fun `an ordinary save writes the buffer`() {
        val (file, id) = open("original\n")
        controller.onTextChanged(id, TextFieldValue("edited\n", TextRange(7)))
        controller.save(id) { null }
        settle()

        assertEquals("edited\n", file.readText())
    }

    @Test
    fun `a file changed on disk is not overwritten`() {
        val (file, id) = open("original\n")
        controller.onTextChanged(id, TextFieldValue("mine\n", TextRange(5)))

        // Somebody else writes the file. The stamp is time and size, so make both differ.
        Thread.sleep(1_100)
        file.writeText("theirs, and longer than mine\n")

        controller.save(id) { null }
        settle()

        assertEquals(
            "theirs, and longer than mine\n",
            file.readText(),
            "the save overwrote work that was not Quill's to overwrite",
        )
        assertTrue(controller.state.value.documents.first { it.id == id }.conflictsWithDisk)
        assertContains(controller.state.value.notification.orEmpty(), "changed on disk")
    }

    @Test
    fun `saving again after being told overwrites deliberately`() {
        val (file, id) = open("original\n")
        controller.onTextChanged(id, TextFieldValue("mine\n", TextRange(5)))
        Thread.sleep(1_100)
        file.writeText("theirs, and longer than mine\n")

        controller.save(id) { null }
        settle()
        controller.saveOverwritingDisk(id) { null }
        settle()

        assertEquals("mine\n", file.readText())
        assertFalse(controller.state.value.documents.first { it.id == id }.conflictsWithDisk)
    }

    @Test
    fun `an unmodified buffer reloads rather than asking`() {
        // Nothing to lose and nothing to decide. An editor showing stale text while the file says
        // otherwise is the more surprising of the two behaviours.
        val (file, id) = open("original\n")
        Thread.sleep(1_100)
        file.writeText("changed elsewhere\n")

        controller.refreshFromDisk()
        settle()

        val session = controller.state.value.documents.first { it.id == id }
        assertEquals("changed elsewhere\n", session.text.text)
        assertFalse(session.isModified)
        assertFalse(session.conflictsWithDisk)
    }

    @Test
    fun `an edited buffer is flagged rather than reloaded`() {
        val (file, id) = open("original\n")
        controller.onTextChanged(id, TextFieldValue("mine\n", TextRange(5)))
        Thread.sleep(1_100)
        file.writeText("theirs\n")

        controller.refreshFromDisk()
        settle()

        val session = controller.state.value.documents.first { it.id == id }
        assertEquals("mine\n", session.text.text, "the writer's unsaved edits must not be discarded")
        assertTrue(session.conflictsWithDisk)
    }

    @Test
    fun `reloading discards the buffer and clears the conflict`() {
        val (file, id) = open("original\n")
        controller.onTextChanged(id, TextFieldValue("mine\n", TextRange(5)))
        Thread.sleep(1_100)
        file.writeText("theirs\n")
        controller.refreshFromDisk()
        settle()

        controller.reloadFromDisk(id)
        settle()

        val session = controller.state.value.documents.first { it.id == id }
        assertEquals("theirs\n", session.text.text)
        assertFalse(session.conflictsWithDisk)
        assertFalse(session.isModified)
    }

    @Test
    fun `undo after a reload cannot step back into the discarded buffer`() {
        // The history belongs to the document, and a reload replaces the document's contents
        // wholesale. Stepping back into text the writer chose to discard would be the reload
        // undoing itself.
        val (file, id) = open("original\n")
        controller.onTextChanged(id, TextFieldValue("mine\n", TextRange(5)))
        Thread.sleep(1_100)
        file.writeText("theirs\n")
        controller.reloadFromDisk(id)
        settle()

        assertFalse(controller.canUndo(id))
    }
}
