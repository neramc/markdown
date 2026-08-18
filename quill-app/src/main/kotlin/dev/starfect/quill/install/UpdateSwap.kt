package dev.starfect.quill.install

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Replacing a running application with a newer copy of itself.
 *
 * The new image is already unpacked beside the old one by the time this runs, so what is left is
 * three renames and a relaunch — and none of them can happen from inside the process being
 * replaced. Windows will not unlink a running executable or a loaded DLL, and on Unix deleting the
 * files out from under a running JVM leaves it to crash the moment it needs a class it has not
 * loaded yet. So the swap is written as a script, started detached, and the application exits.
 *
 * ```
 *   Quill.new  →  (wait for Quill to exit)  →  Quill → Quill.old → deleted
 *                                              Quill.new → Quill
 *                                              start Quill
 * ```
 *
 * Renaming the old image aside before moving the new one in is what makes this recoverable. A crash
 * between the two renames leaves `Quill.old` on disk next to nothing, which is visible and fixable;
 * deleting first and copying second leaves a user with no editor and no explanation.
 */
public object UpdateSwap {

    /** What a swap needs to know, worked out before anything moves. */
    public data class Plan(
        /** The installation being replaced. */
        public val current: Path,
        /** The unpacked new image, which must be a sibling of [current]. */
        public val replacement: Path,
        /** Where the old image is moved before it is deleted. */
        public val retired: Path,
        /** The launcher to start afterwards, relative to [current]. */
        public val launcher: String,
    )

    /**
     * Works out the swap, or explains why there is not one.
     *
     * The replacement must be a sibling of the installation. A rename across filesystems is a copy,
     * and a copy of a hundred megabytes that fails halfway is exactly the state this design exists
     * to avoid — so the staging directory is chosen next to the installation and checked here
     * rather than discovered at the point of no return.
     */
    public fun plan(current: Path, replacement: Path): Result<Plan> {
        val installation = current.toAbsolutePath().normalize()
        val incoming = replacement.toAbsolutePath().normalize()

        val parent = installation.parent
            ?: return Result.failure(IllegalStateException("The installation has no parent directory."))
        if (incoming.parent != parent) {
            return Result.failure(
                IllegalStateException(
                    "The new version was unpacked to ${incoming.parent}, which is not beside the " +
                        "installation in $parent. Moving it would be a copy rather than a rename.",
                ),
            )
        }

        val launcher = launcherWithin(installation)
            ?: return Result.failure(IllegalStateException("No launcher found in $installation."))

        return Result.success(
            Plan(
                current = installation,
                replacement = incoming,
                retired = parent.resolve("${installation.fileName}.old"),
                launcher = launcher,
            ),
        )
    }

    /**
     * The launcher inside an application image, as a path relative to its root.
     *
     * Searched for rather than named, because jpackage puts it in a different place on every
     * platform: `bin/Quill` on Linux, `Quill.exe` at the root on Windows, `Contents/MacOS/Quill`
     * inside the bundle on macOS.
     */
    internal fun launcherWithin(root: Path): String? {
        val candidates = listOf("Quill.exe", "bin/Quill", "bin/Quill.exe", "Contents/MacOS/Quill")
        return candidates.firstOrNull { Files.isRegularFile(root.resolve(it)) }
    }

    /**
     * The script that performs the swap once Quill has exited.
     *
     * Bounded wait, as in [Uninstall]: a script that spins forever because a process hung is a
     * script that never cleans itself up. If the wait times out the renames are attempted anyway —
     * on Unix that works, and on Windows it fails harmlessly and leaves the installation as it was.
     */
    internal fun script(plan: Plan, pid: Long, windows: Boolean): List<String> =
        if (windows) windowsScript(plan, pid) else unixScript(plan, pid)

    private fun windowsScript(plan: Plan, pid: Long): List<String> = buildList {
        add("@echo off")
        add("chcp 65001 >nul")
        add("setlocal disabledelayedexpansion")
        add("set QUILL_TRIES=0")
        add(":wait")
        add("tasklist /fi \"PID eq $pid\" /nh 2>nul | find \"$pid\" >nul")
        add("if errorlevel 1 goto swap")
        add("set /a QUILL_TRIES+=1")
        add("if %QUILL_TRIES% GEQ $WAIT_ATTEMPTS goto swap")
        add("ping 127.0.0.1 -n 2 >nul")
        add("goto wait")
        add(":swap")
        add("if exist \"${batch(plan.retired)}\" rd /s /q \"${batch(plan.retired)}\"")
        add("move \"${batch(plan.current)}\" \"${batch(plan.retired)}\" >nul")
        add("if errorlevel 1 goto failed")
        add("move \"${batch(plan.replacement)}\" \"${batch(plan.current)}\" >nul")
        add("if errorlevel 1 goto restore")
        add("rd /s /q \"${batch(plan.retired)}\"")
        add("start \"\" \"${batch(plan.current.resolve(plan.launcher))}\"")
        add("goto done")
        // The new image would not move in. Put the old one back rather than leaving nothing: the
        // user came here to get a newer editor, not to lose the one they had.
        add(":restore")
        add("move \"${batch(plan.retired)}\" \"${batch(plan.current)}\" >nul")
        add("start \"\" \"${batch(plan.current.resolve(plan.launcher))}\"")
        add(":failed")
        add(":done")
        add("del /f /q \"%~f0\"")
    }

    private fun unixScript(plan: Plan, pid: Long): List<String> = buildList {
        add("#!/bin/sh")
        add("tries=0")
        add("while kill -0 $pid 2>/dev/null; do")
        add("  tries=$((tries+1))")
        add("  [ \"\$tries\" -ge $WAIT_ATTEMPTS ] && break")
        add("  sleep 1")
        add("done")
        add("rm -rf ${shell(plan.retired)}")
        add("mv ${shell(plan.current)} ${shell(plan.retired)} || exit 1")
        add("if mv ${shell(plan.replacement)} ${shell(plan.current)}; then")
        add("  rm -rf ${shell(plan.retired)}")
        add("else")
        // Same reasoning as the Windows branch: never leave the user with nothing.
        add("  mv ${shell(plan.retired)} ${shell(plan.current)}")
        add("fi")
        add("${shell(plan.current.resolve(plan.launcher))} >/dev/null 2>&1 &")
        add("rm -f \"\$0\"")
    }

    /** Roughly a minute of one-second polls: long enough for a JVM to exit, short enough to end. */
    private const val WAIT_ATTEMPTS = 60

    /** A path as a batch file sees it. `%` starts an expansion unless it is doubled. */
    private fun batch(path: Path) = path.toString().replace("%", "%%")

    /** A path as a shell sees it: single-quoted, with any single quote spliced in. */
    private fun shell(path: Path) = "'" + path.toString().replace("'", "'\\''") + "'"

    /** How the script is started, so a test can read it instead of running it. */
    public fun interface Runner {
        /** Writes [lines] somewhere durable, starts it detached, and returns where it went. */
        public fun start(lines: List<String>): Path
    }

    /** Writes the script to the temporary directory and starts it detached. */
    public val SystemRunner: Runner = Runner { lines ->
        val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        val script = Files.createTempFile("quill-update-", if (windows) ".cmd" else ".sh")
        Files.write(script, lines.joinToString(if (windows) "\r\n" else "\n").toByteArray(StandardCharsets.UTF_8))
        script.toFile().setExecutable(true, true)

        val command = if (windows) listOf("cmd.exe", "/c", script.toString()) else listOf("/bin/sh", script.toString())
        ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        script
    }

    /**
     * Starts the swap and returns where the script went.
     *
     * The caller exits immediately afterwards. Everything from here happens in another process,
     * because this one is about to stop existing.
     */
    public fun start(plan: Plan, runner: Runner = SystemRunner): Result<Path> = runCatching {
        val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        runner.start(script(plan, ProcessHandle.current().pid(), windows))
    }
}
