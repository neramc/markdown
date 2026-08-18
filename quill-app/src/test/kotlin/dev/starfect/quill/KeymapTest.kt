package dev.starfect.quill

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import dev.starfect.quill.bridge.QuillEngine
import dev.starfect.quill.model.KeyBinding
import dev.starfect.quill.model.Keymap
import androidx.compose.ui.input.key.KeyEventType
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
 * The keyboard reference against the keyboard.
 *
 * [Keymap] is a hand-written table and `handleShortcut` is a `when` over key events; neither can be
 * generated from the other without inventing a language for modifier combinations. So instead this
 * presses every binding the reference claims and asserts the handler took it. That catches the two
 * failures that actually happen: a binding documented but never wired, and a binding shadowed by an
 * earlier branch — Ctrl+Shift+T was both the task-list toggle and the theme toggle, and with a
 * document open only the first could ever fire.
 */
class KeymapTest {

    private lateinit var scope: CoroutineScope
    private lateinit var controller: QuillController
    private lateinit var directory: Path

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        controller = QuillController(scope, QuillEngine.create(darkTheme = true))
        directory = Files.createTempDirectory("quill-keymap")
    }

    @AfterTest
    fun tearDown() {
        controller.close()
        scope.cancel()
    }

    /** A document is open for every case, because that is the state the editor is normally in. */
    private fun openDocument() {
        val file = directory.resolve("keys.md")
        file.writeText("# Keys\n\nsome text\n")
        controller.openFile(file)
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            if (controller.state.value.documents.isNotEmpty()) return
            Thread.sleep(5)
        }
        error("never opened")
    }

    /** The [Key] a name in the reference stands for. */
    private fun keyOf(name: String): Key? = when (name.uppercase()) {
        "UP" -> Key.DirectionUp
        "DOWN" -> Key.DirectionDown
        "LEFT" -> Key.DirectionLeft
        "RIGHT" -> Key.DirectionRight
        "." -> Key.Period
        "," -> Key.Comma
        "1" -> Key.One
        "2" -> Key.Two
        "3" -> Key.Three
        "6" -> Key.Six
        "F2" -> Key.F2
        "F3" -> Key.F3
        "F10" -> Key.F10
        else -> name.singleOrNull()?.takeIf { it.isLetter() }?.let { LETTERS[it.uppercaseChar()] }
    }

    /**
     * Turns a chord such as `Ctrl+Shift+K` into the event the handler sees.
     *
     * Compose's own desktop factory, not a hand-built AWT event: the handler reads
     * `isCtrlPressed` and friends, and building the AWT event underneath means reproducing its
     * modifier mask and finding a component to source it from, neither of which works headless.
     *
     * The factory is marked internal to compose-ui. Opting in is acceptable here and nowhere else:
     * this is a test, it is pinned to the same Compose version the application compiles against,
     * and the alternative is not testing the keymap at all.
     */
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    private fun press(chord: String): KeyEvent? {
        val parts = chord.split('+').map { it.trim() }.filter { it.isNotEmpty() }
        val key = keyOf(parts.last()) ?: return null

        return KeyEvent(
            key = key,
            type = KeyEventType.KeyDown,
            isCtrlPressed = parts.any { it.equals("Ctrl", true) },
            isShiftPressed = parts.any { it.equals("Shift", true) },
            isAltPressed = parts.any { it.equals("Alt", true) },
        )
    }

    private companion object {
        /** A to Z, since [Key] has no lookup by character. */
        val LETTERS: Map<Char, Key> = mapOf(
            'A' to Key.A, 'B' to Key.B, 'C' to Key.C, 'D' to Key.D, 'E' to Key.E, 'F' to Key.F,
            'G' to Key.G, 'H' to Key.H, 'I' to Key.I, 'J' to Key.J, 'K' to Key.K, 'L' to Key.L,
            'M' to Key.M, 'N' to Key.N, 'O' to Key.O, 'P' to Key.P, 'Q' to Key.Q, 'R' to Key.R,
            'S' to Key.S, 'T' to Key.T, 'U' to Key.U, 'V' to Key.V, 'W' to Key.W, 'X' to Key.X,
            'Y' to Key.Y, 'Z' to Key.Z,
        )
    }

    /** The chords the table lists, one per binding, with alternatives split apart. */
    private fun chords(binding: KeyBinding): List<String> =
        binding.keys.split("  or  ").map { it.trim() }

    /** Back to one clean open document, no dialog, no pending question. */
    private fun reset() {
        controller.dismissDialog()
        controller.dismissConfirm()
        if (controller.state.value.documents.isEmpty()) openDocument()
    }

    @Test
    fun `every documented binding is handled`() {
        openDocument()

        val unhandled = mutableListOf<String>()
        Keymap.all.forEach { binding ->
            chords(binding).forEach { chord ->
                // Bindings act. Ctrl+W closes the document the next binding needs, Ctrl+S may
                // raise a question, and a dialog left open changes what Escape and Enter mean —
                // so the state is put back between presses rather than left to accumulate.
                reset()
                val event = press(chord) ?: error("the test cannot express '$chord' (${binding.action})")
                if (!handleShortcut(event, controller)) {
                    unhandled += "${binding.action}: $chord"
                }
            }
        }

        assertTrue(
            unhandled.isEmpty(),
            "the reference lists bindings the editor does not handle:\n" + unhandled.joinToString("\n"),
        )
    }

    @Test
    fun `Ctrl+G goes to a line rather than to the next match`() {
        openDocument()
        assertTrue(handleShortcut(press("Ctrl+G")!!, controller))
        assertEquals(
            dev.starfect.quill.model.Dialog.GO_TO_LINE,
            controller.state.value.dialog,
            "Ctrl+G is Go to Line in the IDE, and the menu entry claims it",
        )
    }

    @Test
    fun `F3 steps the find matches`() {
        openDocument()
        // No assertion on where it lands — with no query there is nowhere to go. What matters is
        // that the key is claimed, because it is the binding the reference now advertises.
        assertTrue(handleShortcut(press("F3")!!, controller))
        assertTrue(handleShortcut(press("Shift+F3")!!, controller))
    }

    @Test
    fun `no two documented bindings collide`() {
        val seen = mutableMapOf<String, String>()
        val collisions = mutableListOf<String>()

        Keymap.all.forEach { binding ->
            chords(binding).forEach { chord ->
                val normalised = chord.split('+').map { it.trim().lowercase() }.sorted().joinToString("+")
                val owner = seen.put(normalised, binding.action)
                if (owner != null) collisions += "$chord: '$owner' and '${binding.action}'"
            }
        }

        assertTrue(
            collisions.isEmpty(),
            "two actions claim the same keys, so one of them cannot fire:\n" + collisions.joinToString("\n"),
        )
    }
}
