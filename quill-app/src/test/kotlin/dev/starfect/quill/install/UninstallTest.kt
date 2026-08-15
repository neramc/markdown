package dev.starfect.quill.install

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The uninstaller, which is the one feature in Quill that deletes things.
 *
 * Every test here is about restraint rather than function. Removing files is easy; the failure
 * modes worth pinning are all "removed something it should not have" — a path that escaped the
 * install root, a file association another editor now owns, a directory holding somebody's own
 * documents. Those are unrecoverable and silent, so they get the coverage.
 */
class UninstallTest {

    @TempDir
    lateinit var root: Path

    private fun manifest(
        scope: String = "CurrentUser",
        files: String = """["bin/Quill.exe", "app/quill.jar"]""",
        directories: String = """["bin", "app"]""",
        shortcuts: String = "[]",
        associations: String = "[]",
        pathEntry: String = "null",
        schema: Int = 1,
    ): Path {
        val file = root.resolve(Uninstall.MANIFEST_NAME)
        file.writeText(
            """
            {
              "schemaVersion": $schema,
              "product": "Quill",
              "version": "1.2.0",
              "scope": "$scope",
              "installRoot": "C:\\Users\\somebody\\AppData\\Local\\Programs\\Quill",
              "installedUtc": "2026-08-15T00:00:00+00:00",
              "files": $files,
              "directories": $directories,
              "shortcuts": $shortcuts,
              "fileAssociations": $associations,
              "pathEntry": $pathEntry,
              "uninstallEntryWritten": true
            }
            """.trimIndent(),
        )
        return file
    }

    // ── Reading ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a manifest reads back into what the installer recorded`() {
        manifest(associations = """[".md", ".markdown"]""")

        val read = Uninstall.readManifest(root).getOrThrow()

        assertEquals("1.2.0", read.version)
        assertEquals(Uninstall.Scope.CURRENT_USER, read.scope)
        assertEquals(listOf("bin/Quill.exe", "app/quill.jar"), read.files)
        assertEquals(listOf(".md", ".markdown"), read.fileAssociations)
        assertTrue(read.uninstallEntryWritten)
    }

    @Test
    fun `the root we are standing in wins over the one recorded`() {
        // The manifest says C:\Users\somebody\…, which does not exist on the machine reading it.
        // Following that would either delete nothing or, worse, delete somebody else's folder.
        manifest()
        assertEquals(root, Uninstall.readManifest(root).getOrThrow().installRoot)
    }

    @Test
    fun `no manifest means this was not installed, and nothing is guessed at`() {
        val failure = Uninstall.readManifest(root).exceptionOrNull()
        assertIs<Uninstall.NotInstalledException>(failure)
        assertIs<Uninstall.NotInstalled.NoManifest>(failure.reason)
    }

    @Test
    fun `a manifest from a newer installer is refused rather than half-understood`() {
        manifest(schema = Uninstall.SCHEMA_VERSION + 1)

        val failure = Uninstall.readManifest(root).exceptionOrNull()
        assertIs<Uninstall.NotInstalledException>(failure)
        assertIs<Uninstall.NotInstalled.Unreadable>(failure.reason)
        assertContains(failure.message!!, "newer installer")
    }

    @Test
    fun `a truncated manifest is refused, not treated as an empty installation`() {
        // An empty installation plans to delete nothing but still deletes the install root, and a
        // half-written file is exactly how you get one.
        root.resolve(Uninstall.MANIFEST_NAME).writeText("""{ "files": ["bin/Quil""")

        assertIs<Uninstall.NotInstalledException>(Uninstall.readManifest(root).exceptionOrNull())
    }

    @Test
    fun `an all-users installation is recognised as one`() {
        manifest(scope = "AllUsers")
        assertEquals(Uninstall.Scope.ALL_USERS, Uninstall.readManifest(root).getOrThrow().scope)
    }

    @Test
    fun `a manifest written by the real installer reads correctly, field for field`() {
        // Verbatim output from Quill.Setup.Core's own serializer. This is the contract between two
        // languages that never call each other and are built by different toolchains, so the only
        // thing holding it together is that both sides agree on these names — and the names are
        // camelCase, which is not what the C# properties are called. `InstallManifestTests` asserts
        // the other half; if that one fails, this fixture is what needs updating.
        root.resolve(Uninstall.MANIFEST_NAME).writeText(
            """
            {
              "schemaVersion": 1,
              "product": "Quill",
              "version": "1.2.0",
              "scope": "CurrentUser",
              "installRoot": "C:\\Users\\somebody\\AppData\\Local\\Programs\\Quill",
              "installedUtc": "2026-08-15T08:11:54.5947809+00:00",
              "files": [
                "bin/Quill.exe",
                "app/quill-app.jar",
                "runtime/lib/modules"
              ],
              "directories": [
                "bin",
                "app",
                "runtime",
                "runtime/lib"
              ],
              "shortcuts": [
                "C:\\Users\\somebody\\AppData\\Roaming\\Microsoft\\Windows\\Start Menu\\Programs\\Quill.lnk"
              ],
              "fileAssociations": [
                ".md",
                ".markdown"
              ],
              "pathEntry": "C:\\Users\\somebody\\AppData\\Local\\Programs\\Quill\\bin",
              "uninstallEntryWritten": true
            }
            """.trimIndent(),
        )

        val read = Uninstall.readManifest(root).getOrThrow()

        assertEquals("1.2.0", read.version)
        assertEquals(Uninstall.Scope.CURRENT_USER, read.scope)
        assertEquals(listOf("bin/Quill.exe", "app/quill-app.jar", "runtime/lib/modules"), read.files)
        assertEquals(listOf("bin", "app", "runtime", "runtime/lib"), read.directories)
        assertEquals(1, read.shortcuts.size)
        assertEquals(listOf(".md", ".markdown"), read.fileAssociations)
        assertEquals("C:\\Users\\somebody\\AppData\\Local\\Programs\\Quill\\bin", read.pathEntry)
        assertTrue(read.uninstallEntryWritten)

        // And the whole plan comes out of it, which is what actually matters.
        val plan = Uninstall.plan(read)
        assertEquals(4, plan.files.size)
        assertContains(plan.registry, listOf("delete", "HKCU\\${Uninstall.UNINSTALL_KEY}", "/f"))
    }

    // ── Locating ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the install root is whichever ancestor holds the manifest`() {
        manifest()
        val deep = root.resolve("app/lib").createDirectories()

        assertEquals(root, Uninstall.locateInstallRoot(deep))
    }

    @Test
    fun `a directory with no manifest above it is not an installation`() {
        assertNull(Uninstall.locateInstallRoot(root.resolve("app").createDirectories()))
    }

    // ── Planning: what gets deleted ───────────────────────────────────────────────────────────

    @Test
    fun `a path that escapes the install root is not in the plan`() {
        manifest(files = """["bin/Quill.exe", "../../Documents/thesis.docx", "/etc/passwd"]""")

        val plan = Uninstall.plan(Uninstall.readManifest(root).getOrThrow())

        assertEquals(
            listOf(root.resolve("bin/Quill.exe"), root.resolve(Uninstall.MANIFEST_NAME)),
            plan.files,
        )
    }

    @Test
    fun `the install root itself is never listed as a file`() {
        // "." resolves to the root, and deleting it as a file would take the folder with everything
        // still in it before any of the emptiness checks got a say.
        manifest(files = """[".", "bin/Quill.exe"]""")

        val plan = Uninstall.plan(Uninstall.readManifest(root).getOrThrow())
        assertFalse(root in plan.files)
    }

    @Test
    fun `directories come deepest first and the root comes last`() {
        manifest(directories = """["app", "app/lib", "runtime", "runtime/lib/server"]""")

        val plan = Uninstall.plan(Uninstall.readManifest(root).getOrThrow())

        val depths = plan.directories.map { it.nameCount }
        assertEquals(depths.sortedDescending(), depths)
        assertEquals(root, plan.directories.last())
    }

    @Test
    fun `the manifest deletes itself last among the files`() {
        manifest()
        val plan = Uninstall.plan(Uninstall.readManifest(root).getOrThrow())
        assertEquals(root.resolve(Uninstall.MANIFEST_NAME), plan.files.last())
    }

    // ── Planning: the registry ────────────────────────────────────────────────────────────────

    @Test
    fun `the Apps and features entry is removed from the hive the installation used`() {
        manifest(scope = "AllUsers")

        val plan = Uninstall.plan(Uninstall.readManifest(root).getOrThrow())

        assertContains(plan.registry, listOf("delete", "HKLM\\${Uninstall.UNINSTALL_KEY}", "/f"))
    }

    @Test
    fun `an installation that wrote no uninstall entry does not try to delete one`() {
        root.resolve(Uninstall.MANIFEST_NAME).writeText("""{"schemaVersion":1,"uninstallEntryWritten":false}""")

        val plan = Uninstall.plan(Uninstall.readManifest(root).getOrThrow())
        assertTrue(plan.registry.isEmpty())
    }

    @Test
    fun `an extension Quill still owns is handed back to whoever had it before`() {
        manifest(associations = """[".md"]""")
        val state = Uninstall.RegistryState(
            associationBackups = mapOf(".md" to "VSCode.md"),
            associationOwners = mapOf(".md" to Uninstall.PROG_ID),
        )

        val plan = Uninstall.plan(Uninstall.readManifest(root).getOrThrow(), state)

        val classes = "HKCU\\Software\\Classes\\.md"
        assertContains(plan.registry, listOf("add", classes, "/ve", "/d", "VSCode.md", "/f"))
        assertContains(plan.registry, listOf("delete", classes, "/v", Uninstall.BACKUP_VALUE, "/f"))
    }

    @Test
    fun `an extension another editor has since claimed is left alone`() {
        // Taking .md away from the editor the user chose after us, on our way out, is the greater
        // sin. Our ProgId still goes; the extension's own default value does not.
        manifest(associations = """[".md"]""")
        val state = Uninstall.RegistryState(
            associationBackups = mapOf(".md" to "VSCode.md"),
            associationOwners = mapOf(".md" to "Typora.md"),
        )

        val plan = Uninstall.plan(Uninstall.readManifest(root).getOrThrow(), state)

        val classes = "HKCU\\Software\\Classes\\.md"
        assertFalse(plan.registry.any { it.take(3) == listOf("add", classes, "/ve") })
        assertFalse(plan.registry.any { it == listOf("delete", classes, "/ve", "/f") })
        // Our own class and our entry in the Open With list still go.
        assertContains(plan.registry, listOf("delete", "HKCU\\Software\\Classes\\${Uninstall.PROG_ID}", "/f"))
        assertContains(plan.registry, listOf("delete", "$classes\\OpenWithProgids", "/v", Uninstall.PROG_ID, "/f"))
    }

    @Test
    fun `an extension with no saved predecessor loses its default value rather than gaining a wrong one`() {
        manifest(associations = """[".markdown"]""")
        val state = Uninstall.RegistryState(associationOwners = mapOf(".markdown" to Uninstall.PROG_ID))

        val plan = Uninstall.plan(Uninstall.readManifest(root).getOrThrow(), state)

        assertContains(plan.registry, listOf("delete", "HKCU\\Software\\Classes\\.markdown", "/ve", "/f"))
    }

    @Test
    fun `the PATH entry is removed and the rest of PATH is written back as it was`() {
        manifest(pathEntry = "\"C:\\\\Quill\\\\bin\"")
        val state = Uninstall.RegistryState(
            path = "REG_EXPAND_SZ" to "%USERPROFILE%\\bin;C:\\Quill\\bin;C:\\Program Files\\Git\\cmd",
        )

        val plan = Uninstall.plan(Uninstall.readManifest(root).getOrThrow(), state)

        assertContains(
            plan.registry,
            listOf(
                "add", "HKCU\\Environment", "/v", "Path", "/t", "REG_EXPAND_SZ",
                "/d", "%USERPROFILE%\\bin;C:\\Program Files\\Git\\cmd", "/f",
            ),
        )
    }

    @Test
    fun `PATH is left untouched when our entry is not in it`() {
        // Rewriting PATH is the single most damaging thing here, and doing it to change nothing is
        // all risk and no benefit: the type could be wrong, the write could be partial.
        manifest(pathEntry = "\"C:\\\\Quill\\\\bin\"")
        val state = Uninstall.RegistryState(path = "REG_SZ" to "C:\\Windows;C:\\Windows\\System32")

        val plan = Uninstall.plan(Uninstall.readManifest(root).getOrThrow(), state)

        assertFalse(plan.registry.any { "Path" in it })
    }

    @Test
    fun `PATH is left untouched when it could not be read`() {
        manifest(pathEntry = "\"C:\\\\Quill\\\\bin\"")

        val plan = Uninstall.plan(Uninstall.readManifest(root).getOrThrow(), Uninstall.RegistryState())

        assertFalse(plan.registry.any { "Path" in it })
    }

    // ── Reading the registry ──────────────────────────────────────────────────────────────────

    @Test
    fun `reg query output is parsed including values with spaces in them`() {
        val output = """

            HKEY_CURRENT_USER\Environment
                Path    REG_EXPAND_SZ    C:\Program Files\Git\cmd;C:\Quill\bin
                TEMP    REG_SZ    C:\Temp

        """.trimIndent()

        assertEquals(
            "REG_EXPAND_SZ" to "C:\\Program Files\\Git\\cmd;C:\\Quill\\bin",
            Uninstall.valueOf(output, "Path"),
        )
        assertNull(Uninstall.valueOf(output, "Nonexistent"))
    }

    @Test
    fun `the default value is found under the name reg prints for it`() {
        val output = "\r\nHKEY_CURRENT_USER\\Software\\Classes\\.md\r\n" +
            "    (Default)    REG_SZ    Quill.Markdown\r\n" +
            "    Quill.Backup    REG_SZ    VSCode.md\r\n"

        assertEquals("REG_SZ" to "Quill.Markdown", Uninstall.valueOf(output, "(Default)"))
        assertEquals("REG_SZ" to "VSCode.md", Uninstall.valueOf(output, "Quill.Backup"))
    }

    @Test
    fun `reading the state asks only about the extensions the manifest claims`() {
        manifest(associations = """[".md"]""")
        val asked = mutableListOf<List<String>>()
        val registry = Uninstall.Registry { arguments ->
            asked += arguments
            "    (Default)    REG_SZ    Quill.Markdown\r\n    Quill.Backup    REG_SZ    VSCode.md"
        }

        val state = Uninstall.readState(Uninstall.readManifest(root).getOrThrow(), registry)

        assertEquals(listOf(listOf("query", "HKCU\\Software\\Classes\\.md")), asked)
        assertEquals(mapOf(".md" to "VSCode.md"), state.associationBackups)
        assertEquals(mapOf(".md" to Uninstall.PROG_ID), state.associationOwners)
    }

    // ── The deferred half ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the cleanup script waits for this process before deleting anything`() {
        manifest()
        val script = Uninstall.cleanupScript(Uninstall.plan(Uninstall.readManifest(root).getOrThrow()), 4242)

        val wait = script.indexOfFirst { "tasklist" in it && "4242" in it }
        val firstDelete = script.indexOfFirst { it.startsWith("del ") }
        assertTrue(wait in 0 until firstDelete, "the wait must come before the first deletion")
    }

    @Test
    fun `the wait is bounded so a hung process cannot leave the script spinning forever`() {
        manifest()
        val script = Uninstall.cleanupScript(Uninstall.plan(Uninstall.readManifest(root).getOrThrow()), 1)

        assertTrue(script.any { it.startsWith("if %QUILL_TRIES%") && "goto remove" in it })
    }

    @Test
    fun `directories are removed non-recursively so a user's own files survive`() {
        manifest(directories = """["app"]""")
        val script = Uninstall.cleanupScript(Uninstall.plan(Uninstall.readManifest(root).getOrThrow()), 1)

        assertTrue(script.any { it.startsWith("rd ") })
        assertFalse(script.any { "rd /s" in it }, "recursive removal would delete files we never wrote")
    }

    @Test
    fun `the script deletes itself last`() {
        manifest()
        val script = Uninstall.cleanupScript(Uninstall.plan(Uninstall.readManifest(root).getOrThrow()), 1)
        assertEquals("del /f /q \"%~f0\"", script.last())
    }

    @Test
    fun `a percent sign in the install path is not expanded as a variable`() {
        // C:\100%\Quill is a legal path, and an unescaped % in a batch file makes it C:\100 or worse.
        val plan = Uninstall.Plan(
            root = Path.of("C:\\100%\\Quill"),
            registry = emptyList(),
            shortcuts = emptyList(),
            files = listOf(Path.of("C:\\100%\\Quill\\a.jar")),
            directories = listOf(Path.of("C:\\100%\\Quill")),
        )

        val script = Uninstall.cleanupScript(plan, 1)
        assertTrue(script.any { it == "del /f /q \"C:\\100%%\\Quill\\a.jar\"" })
    }

    // ── Executing the part that runs now ──────────────────────────────────────────────────────

    @Test
    fun `shortcuts are removed and the registry commands are run in order`() {
        val shortcut = root.resolve("Quill.lnk").also { it.writeText("x") }
        val plan = Uninstall.Plan(
            root = root,
            registry = listOf(listOf("delete", "HKCU\\Something", "/f")),
            shortcuts = listOf(shortcut),
            files = emptyList(),
            directories = emptyList(),
        )
        val run = mutableListOf<List<String>>()

        val outcome = Uninstall.execute(
            plan,
            registry = { run += it; "" },
            scripts = { root.resolve("cleanup.cmd") },
        )

        assertEquals(listOf(listOf("delete", "HKCU\\Something", "/f")), run)
        assertEquals(1, outcome.removedShortcuts)
        assertFalse(Files.exists(shortcut))
        assertTrue(outcome.succeeded)
    }

    @Test
    fun `a registry command that fails is reported without stopping the rest`() {
        val shortcut = root.resolve("Quill.lnk").also { it.writeText("x") }
        val plan = Uninstall.Plan(
            root = root,
            registry = listOf(listOf("delete", "HKCU\\A", "/f"), listOf("delete", "HKCU\\B", "/f")),
            shortcuts = listOf(shortcut),
            files = emptyList(),
            directories = emptyList(),
        )
        val run = mutableListOf<List<String>>()

        val outcome = Uninstall.execute(
            plan,
            registry = { run += it; if ("HKCU\\A" in it) null else "" },
            scripts = { root.resolve("cleanup.cmd") },
        )

        assertEquals(2, run.size, "a failure must not abort the remaining registry work")
        assertEquals(1, outcome.removedShortcuts, "nor the shortcuts")
        assertFalse(outcome.succeeded)
        assertEquals(1, outcome.failures.size)
        assertContains(outcome.failures.single(), "HKCU\\A")
    }

    @Test
    fun `a shortcut that is already gone is not a failure`() {
        val plan = Uninstall.Plan(
            root = root,
            registry = emptyList(),
            shortcuts = listOf(root.resolve("never-existed.lnk")),
            files = emptyList(),
            directories = emptyList(),
        )

        val outcome = Uninstall.execute(plan, registry = { "" }, scripts = { root.resolve("c.cmd") })

        assertTrue(outcome.succeeded)
        assertEquals(0, outcome.removedShortcuts)
        assertNotNull(outcome.cleanupScript)
    }

    @Test
    fun `failing to schedule the file removal is reported rather than swallowed`() {
        // If the script cannot start, the files stay. Saying so is the difference between a user
        // deleting a folder and a user believing an uninstall happened.
        val plan = Uninstall.Plan(root, emptyList(), emptyList(), emptyList(), emptyList())

        val outcome = Uninstall.execute(
            plan,
            registry = { "" },
            scripts = { error("cmd.exe is not here") },
        )

        assertFalse(outcome.succeeded)
        assertNull(outcome.cleanupScript)
        assertContains(outcome.failures.single(), "cmd.exe is not here")
    }
}
