package dev.starfect.quill.install

import dev.starfect.quill.io.vscode.Jsonc
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Removing Quill, from inside Quill.
 *
 * There used to be a second executable for this — a hundred and three megabytes of self-contained
 * .NET whose only job was to delete files. It shipped in every release and sat in the install
 * directory for the lifetime of the installation, and it was larger than the editor it removed.
 *
 * The work it did is small: read the manifest the installer wrote, undo the registrations Windows
 * knows about, and delete what is listed. All of that fits in the application that is already
 * installed — which also means the uninstaller can never go missing, never be the wrong version,
 * and never be the thing a user has to find. It *is* the installation.
 *
 * ## Two halves, because a process cannot delete itself
 *
 * Windows will not unlink a running executable or a loaded DLL, and an installed Quill is a JVM,
 * a Skia library and forty megabytes of class files, all of them open. So the removal splits:
 *
 * - **Now**, in this process: the registry, the shortcuts, the PATH entry. None of it is locked.
 * - **After we exit**: the files, by a small script this leaves in `%TEMP%` and starts detached.
 *
 * The script is generated from [Plan], so what gets deleted is decided by the same reviewed code
 * either way, and the script is a list of strings a test can read.
 *
 * ## Why `reg.exe`
 *
 * The JDK cannot write the registry — `java.util.prefs` reaches only a mangled corner of HKCU — so
 * the choice is a native binding or the tool Windows has shipped since 2000. `reg.exe` needs no
 * dependency, no shrinking rule and no second code path per architecture, and it turns the
 * interesting part, *which keys get touched*, into argument lists that can be asserted on.
 */
public object Uninstall {

    /** The file the installer leaves in the install root. */
    public const val MANIFEST_NAME: String = "install-manifest.json"

    /**
     * The manifest layout this understands.
     *
     * A newer manifest is refused rather than guessed at. If a future installer records something
     * this version does not know how to undo, reading it optimistically leaves that thing behind
     * while reporting success — and reporting a clean uninstall that was not one is worse than
     * asking the user to run the newer copy.
     */
    public const val SCHEMA_VERSION: Int = 1

    /** The class Explorer associates Markdown files with. Matches `ProductInfo.ProgId`. */
    internal const val PROG_ID = "Quill.Markdown"

    /** The value name the installer parks a displaced association under. */
    internal const val BACKUP_VALUE = "Quill.Backup"

    /** Where Apps & features looks. Matches `ProductInfo.RegistryKeyName` under the Uninstall hive. */
    internal const val UNINSTALL_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Quill"

    private const val CLASSES_KEY = "Software\\Classes"

    /** What the installer recorded, which is the only description of what to undo. */
    public data class Manifest(
        public val version: String,
        public val scope: Scope,
        public val installRoot: Path,
        /** Paths relative to [installRoot], separated by `/`. */
        public val files: List<String>,
        /** Paths relative to [installRoot], shallowest first as written. */
        public val directories: List<String>,
        /** Absolute paths of `.lnk` files. */
        public val shortcuts: List<String>,
        /** Extensions handed to [PROG_ID], with the leading dot. */
        public val fileAssociations: List<String>,
        public val pathEntry: String?,
        public val uninstallEntryWritten: Boolean,
    )

    /** Which half of the registry an installation lives in. */
    public enum class Scope(internal val hive: String, internal val environmentKey: String) {
        CURRENT_USER("HKCU", "Environment"),
        ALL_USERS("HKLM", "System\\CurrentControlSet\\Control\\Session Manager\\Environment"),
        ;

        internal companion object {
            fun parse(text: String?): Scope =
                if (text.equals("AllUsers", ignoreCase = true)) ALL_USERS else CURRENT_USER
        }
    }

    /** Why a manifest could not be used. */
    public sealed interface NotInstalled {
        /** No manifest beside the application: a portable unpack, or a development run. */
        public object NoManifest : NotInstalled

        /** A manifest that could not be read, or one from a version this does not understand. */
        public data class Unreadable(public val reason: String) : NotInstalled
    }

    /**
     * Reads the manifest in [installRoot].
     *
     * A failure here carries a [NotInstalledException] rather than being exceptional, because "there
     * is nothing to uninstall" is an ordinary answer — most copies of Quill in the world are
     * unpacked archives — and the menu item that calls this needs to say so calmly.
     */
    public fun readManifest(installRoot: Path): Result<Manifest> {
        val file = installRoot.resolve(MANIFEST_NAME)
        if (!Files.isRegularFile(file)) return Result.failure(NotInstalledException(NotInstalled.NoManifest))

        val text = runCatching { Files.readString(file) }.getOrElse {
            return failed("The installation manifest could not be read: ${it.message}")
        }

        val parsed = Jsonc.parseOrNull(text) as? Map<*, *>
            ?: return failed("The installation manifest is not readable JSON.")

        val schema = (parsed["schemaVersion"] as? Double)?.toInt() ?: 0
        if (schema > SCHEMA_VERSION) {
            return failed(
                "This copy of Quill was installed by a newer installer (manifest version $schema, " +
                    "this understands $SCHEMA_VERSION). Uninstall from Apps & features instead.",
            )
        }

        fun strings(key: String) = (parsed[key] as? List<*>)?.filterIsInstance<String>().orEmpty()

        return Result.success(
            Manifest(
                version = parsed["version"] as? String ?: "",
                scope = Scope.parse(parsed["scope"] as? String),
                // The manifest records the root it was installed to, but the root we are standing
                // in is the one that exists. A folder somebody moved should still uninstall, and it
                // is also the only one we have just proven we can read.
                installRoot = installRoot,
                files = strings("files"),
                directories = strings("directories"),
                shortcuts = strings("shortcuts"),
                fileAssociations = strings("fileAssociations"),
                pathEntry = (parsed["pathEntry"] as? String)?.takeIf { it.isNotBlank() },
                uninstallEntryWritten = parsed["uninstallEntryWritten"] as? Boolean ?: false,
            ),
        )
    }

    /** Thrown into the [Result] of [readManifest]; carries the reason so the UI can show it. */
    public class NotInstalledException(public val reason: NotInstalled) : Exception(
        when (reason) {
            is NotInstalled.NoManifest ->
                "This copy of Quill was not installed by the Windows installer, so there is nothing " +
                    "to remove — delete the folder it lives in."
            is NotInstalled.Unreadable -> reason.reason
        },
    )

    private fun failed(reason: String): Result<Manifest> =
        Result.failure(NotInstalledException(NotInstalled.Unreadable(reason)))

    /**
     * Finds the installation this process is running from, by looking for the manifest.
     *
     * Walking up from the running code rather than assuming a layout: the app image puts jars in
     * `app/` and the launcher beside or below it, and jpackage has moved that around between
     * versions. Whichever ancestor holds the manifest is the install root by definition, and if no
     * ancestor does then this is not an installation, which is the answer we want anyway.
     */
    public fun locateInstallRoot(from: Path? = codeSourceDirectory()): Path? {
        var candidate = from?.toAbsolutePath()?.normalize()
        var levels = 0
        while (candidate != null && levels < MAX_ANCESTORS) {
            if (Files.isRegularFile(candidate.resolve(MANIFEST_NAME))) return candidate
            candidate = candidate.parent
            levels++
        }
        return null
    }

    private const val MAX_ANCESTORS = 6

    private fun codeSourceDirectory(): Path? = runCatching {
        val location = Uninstall::class.java.protectionDomain?.codeSource?.location ?: return null
        val path = Path.of(location.toURI())
        if (Files.isDirectory(path)) path else path.parent
    }.getOrNull()

    // ── Planning ──────────────────────────────────────────────────────────────────────────────

    /** Everything an uninstall will do, worked out before any of it happens. */
    public data class Plan(
        public val root: Path,
        /** `reg.exe` argument lists, in the order they must run. */
        public val registry: List<List<String>>,
        /** Absolute `.lnk` paths. */
        public val shortcuts: List<Path>,
        /** Absolute paths of payload files, plus the manifest. */
        public val files: List<Path>,
        /** Absolute paths, deepest first, ending with [root]. */
        public val directories: List<Path>,
    )

    /**
     * The registry facts a plan depends on, read before planning so planning stays pure.
     *
     * @param associationBackups extension → the handler Quill displaced, if the installer saved one
     * @param associationOwners extension → the handler currently registered
     * @param path the raw, unexpanded PATH and its type, or null when there is none
     */
    public data class RegistryState(
        public val associationBackups: Map<String, String> = emptyMap(),
        public val associationOwners: Map<String, String> = emptyMap(),
        public val path: Pair<String, String>? = null,
    )

    /** How the registry is reached, so a test can plan against a registry that is not there. */
    public fun interface Registry {
        /** Runs `reg.exe` with [arguments]; returns its output, or null if it failed. */
        public fun run(arguments: List<String>): String?
    }

    /** Reads everything [plan] will need to decide. */
    public fun readState(manifest: Manifest, registry: Registry): RegistryState {
        val backups = mutableMapOf<String, String>()
        val owners = mutableMapOf<String, String>()

        manifest.fileAssociations.forEach { extension ->
            val key = "${manifest.scope.hive}\\$CLASSES_KEY\\$extension"
            val output = registry.run(listOf("query", key)) ?: return@forEach
            valueOf(output, BACKUP_VALUE)?.let { backups[extension] = it.second }
            // The default value prints as "(Default)" in reg.exe's output.
            valueOf(output, "(Default)")?.let { owners[extension] = it.second }
        }

        val path = manifest.pathEntry?.let {
            val output = registry.run(listOf("query", "${manifest.scope.hive}\\${manifest.scope.environmentKey}", "/v", "Path"))
            output?.let { text -> valueOf(text, "Path") }
        }

        return RegistryState(backups, owners, path)
    }

    /**
     * Pulls a value out of `reg query` output.
     *
     * The format is `    Name    REG_TYPE    data`, separated by runs of whitespace, and the data
     * may itself contain spaces — a PATH is nothing but paths with spaces in them — so only the
     * first two gaps are separators and the rest of the line is the value.
     */
    internal fun valueOf(output: String, name: String): Pair<String, String>? =
        output.lineSequence()
            .mapNotNull { VALUE_LINE.matchEntire(it.trimEnd()) }
            .firstOrNull { it.groupValues[1] == name }
            ?.let { it.groupValues[2] to it.groupValues[3] }

    private val VALUE_LINE = Regex("""\s+(\(Default\)|\S+)\s+(REG_[A-Z_]+)\s{2,}(.*)""")

    /**
     * Works out what to remove.
     *
     * Nothing outside the install root is ever listed. The manifest is a file on disk that a user
     * can edit and a partial write can truncate, and an uninstaller that follows one off its own
     * territory is a program that deletes a home directory because an entry had `..` in it.
     * Shortcuts are the deliberate exception — they live in the Start menu by definition — so they
     * are held separately and only ever removed one named file at a time.
     */
    public fun plan(manifest: Manifest, state: RegistryState = RegistryState()): Plan {
        val root = manifest.installRoot.toAbsolutePath().normalize()

        fun inside(relative: String): Path? {
            val resolved = runCatching { root.resolve(relative.replace('/', java.io.File.separatorChar)) }
                .getOrNull()?.toAbsolutePath()?.normalize() ?: return null
            return if (resolved.startsWith(root) && resolved != root) resolved else null
        }

        val hive = manifest.scope.hive
        val registry = buildList {
            if (manifest.uninstallEntryWritten) {
                add(listOf("delete", "$hive\\$UNINSTALL_KEY", "/f"))
            }

            if (manifest.fileAssociations.isNotEmpty()) {
                add(listOf("delete", "$hive\\$CLASSES_KEY\\$PROG_ID", "/f"))
            }

            manifest.fileAssociations.forEach { extension ->
                val key = "$hive\\$CLASSES_KEY\\$extension"
                add(listOf("delete", "$key\\OpenWithProgids", "/v", PROG_ID, "/f"))

                // Only give the extension back if it is still ours. Another editor may have claimed
                // it since installation, and taking it from them on our way out is the greater sin.
                if (state.associationOwners[extension]?.equals(PROG_ID, ignoreCase = true) != false) {
                    val previous = state.associationBackups[extension]
                    if (previous != null) {
                        add(listOf("add", key, "/ve", "/d", previous, "/f"))
                    } else {
                        add(listOf("delete", key, "/ve", "/f"))
                    }
                }
                if (state.associationBackups.containsKey(extension)) {
                    add(listOf("delete", key, "/v", BACKUP_VALUE, "/f"))
                }
            }

            val entry = manifest.pathEntry
            val (type, value) = state.path ?: (null to null)
            if (entry != null && type != null && value != null) {
                val remaining = value.split(';')
                    .filter { it.isNotBlank() && !it.trim().equals(entry, ignoreCase = true) }
                if (remaining.size != value.split(';').count { it.isNotBlank() }) {
                    add(
                        listOf(
                            "add", "$hive\\${manifest.scope.environmentKey}",
                            "/v", "Path", "/t", type, "/d", remaining.joinToString(";"), "/f",
                        ),
                    )
                }
            }
        }

        return Plan(
            root = root,
            registry = registry,
            shortcuts = manifest.shortcuts.mapNotNull { runCatching { Path.of(it) }.getOrNull() },
            // Built rather than appended to with `+`. A `Path` is an `Iterable<Path>` over its own
            // name elements, so `listOfPaths + aPath` silently splices `C:\Quill\app` into three
            // entries named `C:`, `Quill` and `app` — which here would be three deletions of
            // whatever those relative names happen to hit.
            files = buildList {
                manifest.files.mapNotNullTo(this, ::inside)
                // Last, because until it is gone this is still a readable installation.
                add(root.resolve(MANIFEST_NAME))
            },
            directories = buildList {
                // Deepest first: a parent cannot go until its children have.
                addAll(manifest.directories.mapNotNull(::inside).sortedByDescending { it.nameCount })
                add(root)
            },
        )
    }

    // ── Doing it ──────────────────────────────────────────────────────────────────────────────

    /** What the in-process half of an uninstall managed. */
    public data class Outcome(
        public val removedShortcuts: Int,
        public val failures: List<String>,
        /** The script that will remove the files once this process exits, if one was started. */
        public val cleanupScript: Path?,
    ) {
        public val succeeded: Boolean get() = failures.isEmpty()
    }

    /** Runs `reg.exe` for real. */
    public val SystemRegistry: Registry = Registry { arguments ->
        runCatching {
            val process = ProcessBuilder(listOf("reg") + arguments)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (process.waitFor() == 0) output else null
        }.getOrNull()
    }

    /**
     * Carries out the part of [plan] that can happen while Quill is running, and leaves the rest
     * ready to run once it is not.
     *
     * Every step is attempted even after one fails. A half-removed installation is the worst
     * outcome available — files gone but still listed in Apps & features, or the reverse — so the
     * goal is to undo as much as possible and then say precisely what is left.
     */
    public fun execute(
        plan: Plan,
        registry: Registry = SystemRegistry,
        scripts: ScriptRunner = SystemScripts,
    ): Outcome {
        val failures = mutableListOf<String>()
        var removedShortcuts = 0

        // Registrations first. A shortcut that outlives its target for a few seconds is a broken
        // icon in the Start menu; a target that outlives its shortcut is nothing at all.
        plan.registry.forEach { arguments ->
            if (registry.run(arguments) == null) failures += "reg ${arguments.joinToString(" ")}"
        }

        plan.shortcuts.forEach { shortcut ->
            runCatching { if (Files.deleteIfExists(shortcut)) removedShortcuts++ }
                .onFailure { failures += "$shortcut: ${it.message}" }
        }

        val script = runCatching { scripts.start(cleanupScript(plan, ProcessHandle.current().pid())) }
            .onFailure { failures += "scheduling the file removal: ${it.message}" }
            .getOrNull()

        return Outcome(removedShortcuts, failures, script)
    }

    /** Starts the deferred half. Separated so a test can read the script instead of running it. */
    public fun interface ScriptRunner {
        /** Writes [lines] somewhere durable, starts it detached, and returns where it went. */
        public fun start(lines: List<String>): Path
    }

    /** Writes the script to `%TEMP%` and launches a detached, window-less `cmd`. */
    public val SystemScripts: ScriptRunner = ScriptRunner { lines ->
        val script = Files.createTempFile("quill-uninstall-", ".cmd")
        // cmd reads a batch file in the console codepage, and `chcp 65001` in the script itself
        // arrives too late for the lines already buffered — but the paths are the only non-ASCII
        // content and they are read line by line, so UTF-8 with the chcp first works.
        Files.write(script, lines.joinToString("\r\n").toByteArray(StandardCharsets.UTF_8))
        ProcessBuilder("cmd.exe", "/c", script.toString())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        script
    }

    /**
     * The script that finishes the job after Quill exits.
     *
     * It waits for [pid] to disappear before touching anything, because until then the JVM, Skia
     * and every jar are open files that Windows will not unlink. The wait is bounded: a script that
     * spins forever because a process hung is a script that never deletes itself.
     *
     * Directories go with plain `rd`, never `rd /s`. Somebody who installed into a folder that also
     * held their own files keeps those files; recursive removal here deletes data the installer
     * never created, and the install root is included in that rule.
     */
    internal fun cleanupScript(plan: Plan, pid: Long): List<String> = buildList {
        add("@echo off")
        add("chcp 65001 >nul")
        add("setlocal disabledelayedexpansion")
        add("set QUILL_TRIES=0")
        add(":wait")
        add("tasklist /fi \"PID eq $pid\" /nh 2>nul | find \"$pid\" >nul")
        add("if errorlevel 1 goto remove")
        add("set /a QUILL_TRIES+=1")
        add("if %QUILL_TRIES% GEQ $WAIT_ATTEMPTS goto remove")
        add("ping 127.0.0.1 -n 2 >nul")
        add("goto wait")
        add(":remove")
        plan.files.forEach { add("del /f /q \"${batch(it)}\"") }
        plan.directories.forEach { add("rd \"${batch(it)}\" 2>nul") }
        add("del /f /q \"%~f0\"")
    }

    /** Roughly a minute of one-second polls: long enough for a JVM to exit, short enough to end. */
    private const val WAIT_ATTEMPTS = 60

    /** A path as a batch file sees it. `%` starts an expansion unless it is doubled. */
    private fun batch(path: Path) = path.toString().replace("%", "%%")
}
