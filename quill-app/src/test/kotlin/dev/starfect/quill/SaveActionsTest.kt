package dev.starfect.quill

import dev.starfect.quill.bridge.QuillEngine
import dev.starfect.quill.model.QuillSettings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Tests the save actions.
 *
 * These rewrite the user's file on the way to disk, so getting one wrong loses content that was
 * deliberately there. The hard-line-break case in particular: two trailing spaces are Markdown's
 * line break, and stripping them silently joins two lines the author separated on purpose.
 */
class SaveActionsTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val controller = QuillController(scope, QuillEngine.create(darkTheme = true))

    @AfterTest
    fun tearDown() {
        controller.close()
        scope.cancel()
    }

    private fun apply(
        text: String,
        trim: Boolean = false,
        newline: Boolean = false,
    ): String = controller.applySaveActions(
        text,
        QuillSettings(trimTrailingWhitespaceOnSave = trim, ensureNewlineOnSave = newline),
    )

    @Test
    fun `both actions off leave the text alone`() {
        val text = "line   \n\n\nno newline at the end"
        assertEquals(text, apply(text))
    }

    @Test
    fun `trimming removes trailing spaces and tabs`() {
        assertEquals("a\nb", apply("a   \nb\t\t", trim = true))
    }

    @Test
    fun `trimming keeps a hard line break`() {
        // Exactly two trailing spaces are a hard line break. Removing them joins the two lines.
        assertEquals("first  \nsecond", apply("first  \nsecond", trim = true))
    }

    @Test
    fun `trimming removes three spaces, which are not a hard break`() {
        assertEquals("first\nsecond", apply("first   \nsecond", trim = true))
    }

    @Test
    fun `trimming empties a whitespace-only line`() {
        assertEquals("a\n\nb", apply("a\n   \nb", trim = true))
    }

    @Test
    fun `the final newline is added when missing`() {
        assertEquals("text\n", apply("text", newline = true))
    }

    @Test
    fun `the final newline is not doubled`() {
        assertEquals("text\n", apply("text\n", newline = true))
        assertEquals("text\n", apply("text\n\n\n", newline = true))
    }

    @Test
    fun `an empty document stays empty`() {
        // "" becoming "\n" would turn every new, untouched file into a one-line one on first save.
        assertEquals("", apply("", newline = true))
    }

    @Test
    fun `both actions compose`() {
        assertEquals("a\nb\n", apply("a   \nb   \n\n\n", trim = true, newline = true))
    }

    @Test
    fun `trimming does not touch interior whitespace`() {
        assertEquals("a  b\tc", apply("a  b\tc", trim = true))
    }

    @Test
    fun `multi-byte characters survive trimming`() {
        assertEquals("한국어 텍스트\n🎉", apply("한국어 텍스트   \n🎉  \t", trim = true))
    }
}
