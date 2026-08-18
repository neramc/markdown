package dev.starfect.quill

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.starfect.quill.bridge.QuillEngine
import dev.starfect.quill.model.Confirm
import dev.starfect.quill.model.ConfirmChoice
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Closing a document that has unsaved edits.
 *
 * Quill used to discard them without a word: the tab's close button called straight through to the
 * close, and an hour of writing left the window between one click and the next frame. Everything
 * here is about the difference between *asking* and *doing*, which is why the assertions are on
 * what the controller decided rather than on any dialog — the question is state, and the dialog is
 * a view of it.
 */
class ConfirmCloseTest {

    private lateinit var scope: CoroutineScope
    private lateinit var controller: QuillController
    private lateinit var directory: Path

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        controller = QuillController(scope, QuillEngine.create(darkTheme = true))
        directory = Files.createTempDirectory("quill-confirm")
    }

    @AfterTest
    fun tearDown() {
        controller.close()
        scope.cancel()
    }

    private fun open(name: String, text: String = "# $name\n"): Long {
        val file = directory.resolve(name)
        file.writeText(text)
        controller.openFile(file)
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            controller.state.value.documents.firstOrNull { it.path == file }?.let { return it.id }
            Thread.sleep(5)
        }
        error("never opened $name")
    }

    private fun edit(id: Long, appended: String) {
        val current = controller.state.value.documents.first { it.id == id }.text.text
        val next = current + appended
        controller.onTextChanged(id, TextFieldValue(next, TextRange(next.length)))
    }

    private fun awaitClosed(id: Long) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            if (controller.state.value.documents.none { it.id == id }) return
            Thread.sleep(5)
        }
        error("document $id never closed")
    }

    @Test
    fun `an unmodified document closes without asking`() {
        val id = open("clean.md")

        controller.requestCloseDocument(id)

        assertNull(controller.state.value.confirm, "a saved document must not raise a question")
        assertTrue(controller.state.value.documents.none { it.id == id })
    }

    @Test
    fun `a modified document is not closed until the question is answered`() {
        val id = open("dirty.md")
        edit(id, "unsaved work\n")

        controller.requestCloseDocument(id)

        val pending = controller.state.value.confirm
        assertTrue(pending is Confirm.CloseDocuments)
        assertEquals(listOf(id), pending.ids)
        assertEquals(listOf("dirty.md"), pending.unsavedNames)
        assertTrue(
            controller.state.value.documents.any { it.id == id },
            "the document must still be open while the question is on screen",
        )
    }

    @Test
    fun `cancel keeps the document and its edits`() {
        val id = open("cancel.md")
        edit(id, "still here\n")
        controller.requestCloseDocument(id)

        controller.resolveConfirm(ConfirmChoice.CANCEL)

        assertNull(controller.state.value.confirm)
        val session = controller.state.value.documents.first { it.id == id }
        assertTrue(session.text.text.endsWith("still here\n"))
        assertTrue(session.isModified)
    }

    @Test
    fun `discard closes without writing anything`() {
        val id = open("discard.md")
        val file = directory.resolve("discard.md")
        edit(id, "never written\n")
        controller.requestCloseDocument(id)

        controller.resolveConfirm(ConfirmChoice.DISCARD)

        assertNull(controller.state.value.confirm)
        awaitClosed(id)
        assertTrue(
            "never written" !in file.readText(),
            "Discard must not write; the file still holds what it held",
        )
    }

    @Test
    fun `save writes before the tab goes`() {
        val id = open("save.md")
        val file = directory.resolve("save.md")
        edit(id, "kept\n")
        controller.requestCloseDocument(id)

        controller.resolveConfirm(ConfirmChoice.SAVE)

        awaitClosed(id)
        // The write has to have landed by the time the tab is gone, not merely to have been
        // started: the tab disappearing is the only signal the writer gets that it is safe to quit.
        assertTrue("kept" in file.readText(), "Save must have written the buffer before closing")
    }

    @Test
    fun `one question covers a whole batch`() {
        val clean = open("a.md")
        val first = open("b.md")
        val second = open("c.md")
        edit(first, "one\n")
        edit(second, "two\n")

        controller.requestCloseAllDocuments()

        val pending = controller.state.value.confirm
        assertTrue(pending is Confirm.CloseDocuments)
        assertEquals(listOf(clean, first, second), pending.ids, "every document being closed is listed")
        assertEquals(
            listOf("b.md", "c.md"),
            pending.unsavedNames,
            "only the modified ones are what the question is about",
        )
    }

    @Test
    fun `closing others leaves the kept document alone`() {
        val kept = open("kept.md")
        val other = open("other.md")
        edit(other, "edited\n")

        controller.requestCloseOtherDocuments(kept)
        controller.resolveConfirm(ConfirmChoice.DISCARD)

        awaitClosed(other)
        assertTrue(controller.state.value.documents.any { it.id == kept })
    }

    @Test
    fun `closing to the right ignores the tabs to the left`() {
        val left = open("1.md")
        val middle = open("2.md")
        val right = open("3.md")
        edit(left, "left edit\n")

        controller.requestCloseDocumentsAfter(middle)

        assertNull(
            controller.state.value.confirm,
            "the modified document is to the left; nothing being closed is modified",
        )
        awaitClosed(right)
        assertTrue(controller.state.value.documents.map { it.id } == listOf(left, middle))
    }

    @Test
    fun `save on a never-saved document cancels the whole batch when no path is given`() {
        val saved = open("on-disk.md")
        edit(saved, "edited\n")
        controller.newDocument()
        val untitled = controller.state.value.documents.last().id
        controller.onTextChanged(untitled, TextFieldValue("scratch", TextRange(7)))

        controller.requestCloseAllDocuments()
        // The dialog's picker returning null is a cancelled Save As. Closing the documents it did
        // get to would leave no record of which ones were saved.
        controller.resolveConfirm(ConfirmChoice.SAVE) { null }

        Thread.sleep(300)
        assertTrue(
            controller.state.value.documents.any { it.id == untitled },
            "a cancelled picker must abandon the close, not close what it reached first",
        )
        assertTrue(controller.state.value.documents.any { it.id == saved })
    }

    @Test
    fun `answering twice does nothing the second time`() {
        val id = open("once.md")
        edit(id, "edit\n")
        controller.requestCloseDocument(id)

        controller.resolveConfirm(ConfirmChoice.CANCEL)
        controller.resolveConfirm(ConfirmChoice.DISCARD)

        assertTrue(
            controller.state.value.documents.any { it.id == id },
            "the second answer had no question to answer and must be ignored",
        )
    }
}

/**
 * Leaving Quill with unsaved edits.
 *
 * Closing the window called `exitApplication` directly, so the X in the corner was a discard button
 * that did not say so.
 */
class ConfirmExitTest {

    private lateinit var scope: CoroutineScope
    private lateinit var controller: QuillController
    private lateinit var directory: Path

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        controller = QuillController(scope, QuillEngine.create(darkTheme = true))
        directory = Files.createTempDirectory("quill-exit")
    }

    @AfterTest
    fun tearDown() {
        controller.close()
        scope.cancel()
    }

    private fun open(name: String): Long {
        val file = directory.resolve(name)
        file.writeText("# $name\n")
        controller.openFile(file)
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            controller.state.value.documents.firstOrNull { it.path == file }?.let { return it.id }
            Thread.sleep(5)
        }
        error("never opened $name")
    }

    private fun edit(id: Long, appended: String) {
        val current = controller.state.value.documents.first { it.id == id }.text.text
        val next = current + appended
        controller.onTextChanged(id, TextFieldValue(next, TextRange(next.length)))
    }

    @Test
    fun `with nothing unsaved the window closes straight away`() {
        open("clean.md")
        assertTrue(controller.requestExit(), "nothing to lose means nothing to ask")
        assertNull(controller.state.value.confirm)
    }

    @Test
    fun `with unsaved work the window does not close yet`() {
        val id = open("dirty.md")
        edit(id, "unsaved\n")

        assertTrue(!controller.requestExit(), "the caller must not exit while the question is up")
        val pending = controller.state.value.confirm
        assertTrue(pending is Confirm.Exit)
        assertEquals(listOf("dirty.md"), pending.unsavedNames)
    }

    @Test
    fun `cancel does not exit`() {
        val id = open("cancel.md")
        edit(id, "unsaved\n")
        controller.requestExit()

        var exited = false
        controller.resolveConfirm(ConfirmChoice.CANCEL, onExit = { exited = true })

        assertTrue(!exited, "Cancel on a question about quitting must never quit")
        assertNull(controller.state.value.confirm)
    }

    @Test
    fun `discard exits immediately`() {
        val id = open("discard.md")
        edit(id, "unsaved\n")
        controller.requestExit()

        var exited = false
        controller.resolveConfirm(ConfirmChoice.DISCARD, onExit = { exited = true })

        assertTrue(exited)
    }

    @Test
    fun `save exits only after the last write lands`() {
        val first = open("one.md")
        val second = open("two.md")
        edit(first, "alpha\n")
        edit(second, "beta\n")
        controller.requestExit()

        // An AtomicBoolean rather than a captured `var`: the exit fires on a worker and is read
        // here, and a plain field gives no guarantee this thread ever sees the write.
        val exited = java.util.concurrent.atomic.AtomicBoolean(false)
        controller.resolveConfirm(ConfirmChoice.SAVE, onExit = { exited.set(true) })

        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline && !exited.get()) Thread.sleep(5)

        assertTrue(exited.get(), "the exit never happened")
        // Read after the exit fired: if the exit can outrun the writes, this is where it shows.
        assertTrue("alpha" in directory.resolve("one.md").readText())
        assertTrue("beta" in directory.resolve("two.md").readText())
    }

    @Test
    fun `a cancelled save-as leaves the window open`() {
        controller.newDocument()
        val untitled = controller.state.value.documents.last().id
        controller.onTextChanged(untitled, TextFieldValue("scratch", TextRange(7)))
        controller.requestExit()

        var exited = false
        controller.resolveConfirm(ConfirmChoice.SAVE, onNeedsPath = { null }, onExit = { exited = true })

        Thread.sleep(300)
        assertTrue(!exited, "with nowhere to write it, quitting would be the discard it refused")
    }
}
