package dev.starfect.quill.io.vscode

import dev.starfect.quill.model.QuillSettings
import dev.starfect.quill.model.SettingsRegistry
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading a real VS Code settings file.
 *
 * The fixtures here are written the way people actually write that file — with comments, a trailing
 * comma, a language block, and a pile of extension settings that mean nothing here — because a
 * parser that only handles the tidy case would work on nobody's machine.
 */
class VsCodeImportTest {

    private val temporary: Path = Files.createTempDirectory("quill-vscode-test")

    @AfterTest
    fun cleanUp() {
        System.clearProperty("quill.vscode.root")
        temporary.toFile().deleteRecursively()
    }

    private fun install(flavour: String = "Code", contents: String): VsCodeInstallation {
        val file = temporary.resolve("$flavour/User/settings.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, contents)
        System.setProperty("quill.vscode.root", temporary.toString())
        return VsCodeSettings.installations().first { it.settingsFile == file }
    }

    // ------------------------------------------------------------------ the parser

    @Test
    fun `comments and a trailing comma do not stop the file being read`() {
        val parsed = Jsonc.parse(
            """
            {
                // How big the text is
                "editor.fontSize": 16,
                /* and whether it wraps */
                "editor.wordWrap": "on",
            }
            """.trimIndent(),
        ) as Map<*, *>

        assertEquals(16.0, parsed["editor.fontSize"])
        assertEquals("on", parsed["editor.wordWrap"])
    }

    @Test
    fun `escapes and nesting survive`() {
        val parsed = Jsonc.parse(
            """{ "a": "line\nbreak é \"quoted\"", "b": [1, 2, {"c": true}], "d": null }""",
        ) as Map<*, *>

        assertEquals("line\nbreak é \"quoted\"", parsed["a"])
        assertEquals(listOf(1.0, 2.0, mapOf("c" to true)), parsed["b"])
        assertNull(parsed["d"])
    }

    @Test
    fun `a malformed file says where it went wrong rather than throwing something unreadable`() {
        val failure = runCatching { Jsonc.parse("""{ "a": 1,, }""") }.exceptionOrNull()
        assertTrue(failure is Jsonc.MalformedException, "expected a malformed-file error, got $failure")
        assertTrue(failure.message!!.contains("line"), "the message should locate the problem: ${failure.message}")
    }

    // ------------------------------------------------------------------ the import

    @Test
    fun `the settings that mean the same thing are carried across`() {
        val installation = install(
            contents = """
            {
                "editor.fontSize": 17,
                "editor.tabSize": 2,
                "editor.wordWrap": "off",
                "editor.lineNumbers": "off",
                "files.insertFinalNewline": false,
                "files.trimTrailingWhitespace": true,
                "editor.rulers": [100],
                "editor.renderLineHighlight": "none"
            }
            """.trimIndent(),
        )

        val report = VsCodeSettings.importFrom(installation, QuillSettings())
        val settings = report.settings

        assertNull(report.failure)
        assertEquals(17, settings.editorFontSize)
        assertEquals(2, settings.tabWidth)
        assertEquals(false, settings.wordWrap, "\"off\" is a boolean spelled as a word")
        assertEquals(false, settings.showLineNumbers)
        assertEquals(false, settings.ensureNewlineOnSave)
        assertEquals(true, settings.trimTrailingWhitespaceOnSave)
        assertEquals(100, settings.visualGuideColumn, "the first ruler is the guide")
        assertEquals(false, settings.highlightCaretRow)

        assertTrue(report.imported.any { it.setting == SettingsRegistry.EditorFontSize })
        assertTrue(report.imported.all { it.before != it.after }, "nothing unchanged should be reported as imported")
    }

    @Test
    fun `a Markdown language block wins over the global setting`() {
        // Somebody who wrote this has said something specific about editing Markdown, which is the
        // only thing Quill does. Taking the global value would import the opposite of their intent.
        val installation = install(
            contents = """
            {
                "editor.wordWrap": "off",
                "editor.fontSize": 12,
                "[markdown]": {
                    "editor.wordWrap": "on",
                    "editor.fontSize": 18
                }
            }
            """.trimIndent(),
        )

        val settings = VsCodeSettings.importFrom(installation, QuillSettings()).settings
        assertEquals(true, settings.wordWrap)
        assertEquals(18, settings.editorFontSize)
    }

    @Test
    fun `a combined language block still applies`() {
        val installation = install(
            contents = """{ "[markdown][plaintext]": { "editor.tabSize": 8 } }""",
        )
        assertEquals(8, VsCodeSettings.importFrom(installation, QuillSettings()).settings.tabWidth)
    }

    @Test
    fun `settings with no equivalent are reported rather than guessed at`() {
        val installation = install(
            contents = """
            {
                "editor.fontSize": 15,
                "editor.fontLigatures": true,
                "editor.cursorSmoothCaretAnimation": "on",
                "python.analysis.typeCheckingMode": "strict",
                "gitlens.hovers.enabled": false
            }
            """.trimIndent(),
        )

        val report = VsCodeSettings.importFrom(installation, QuillSettings())

        assertTrue("editor.fontLigatures" in report.unsupported, "an editor setting Quill lacks should be listed")
        assertTrue(
            report.unsupported.none { it.startsWith("python.") || it.startsWith("gitlens.") },
            "extension settings would bury the ones that matter: ${report.unsupported}",
        )
    }

    @Test
    fun `a value of the wrong shape is reported rather than silently dropped`() {
        val installation = install(contents = """{ "editor.fontSize": "enormous" }""")
        val report = VsCodeSettings.importFrom(installation, QuillSettings())

        assertEquals(QuillSettings().editorFontSize, report.settings.editorFontSize)
        assertTrue("editor.fontSize" in report.unreadable)
    }

    @Test
    fun `a font size far outside Quill's range is clamped rather than ignored`() {
        // Unlike a corrupt settings file, this is somebody's real preference in another editor. The
        // nearest thing Quill can offer beats pretending they never said it.
        val installation = install(contents = """{ "editor.fontSize": 400 }""")
        val settings = VsCodeSettings.importFrom(installation, QuillSettings()).settings
        assertEquals(SettingsRegistry.EditorFontSize.range.last, settings.editorFontSize)
    }

    @Test
    fun `a broken settings file is reported, not thrown`() {
        val installation = install(contents = """{ "editor.fontSize": }""")
        val report = VsCodeSettings.importFrom(installation, QuillSettings())

        assertTrue(report.failure != null, "a broken file should produce a message")
        assertEquals(QuillSettings(), report.settings, "nothing should change when nothing could be read")
    }

    @Test
    fun `every flavour of VS Code is found`() {
        for (flavour in listOf("Code", "Code - Insiders", "VSCodium", "Cursor")) {
            val file = temporary.resolve("$flavour/User/settings.json")
            Files.createDirectories(file.parent)
            Files.writeString(file, "{}")
        }
        System.setProperty("quill.vscode.root", temporary.toString())

        val found = VsCodeSettings.installations().map { it.name }
        assertEquals(listOf("VS Code", "VS Code Insiders", "VSCodium", "Cursor"), found)
    }

    @Test
    fun `no VS Code at all is not an error`() {
        System.setProperty("quill.vscode.root", temporary.resolve("nothing-here").toString())
        assertTrue(VsCodeSettings.installations().isEmpty())
    }
}
