package com.neramc.quill.io

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory

/** A project the welcome window offers to reopen. */
public data class RecentProject(val path: Path) {
    /** The directory's own name, which is what the IDE shows as the project name. */
    public val name: String get() = path.fileName?.toString() ?: path.toString()

    /** The location, with the home directory abbreviated the way the IDE abbreviates it. */
    public val displayPath: String
        get() {
            val home = System.getProperty("user.home").orEmpty()
            val absolute = path.toString()
            return if (home.isNotEmpty() && absolute.startsWith(home)) {
                "~" + absolute.removePrefix(home)
            } else {
                absolute
            }
        }
}

/**
 * The recent-projects list behind the welcome window.
 *
 * A plain newline-separated file rather than JSON or preferences: the data is a list of paths, it is
 * written once per project open, and adding a serialization framework — plus its ProGuard keep rules
 * — to store one list of strings would cost more than it carries.
 *
 * Every read filters out directories that no longer exist, so a list that has gone stale on disk
 * quietly shrinks instead of offering entries that fail when clicked.
 */
public class RecentProjects(private val storePath: Path = defaultStorePath()) {

    private companion object {
        /** How many entries survive; the IDE's own list is of a similar order. */
        const val MAX_ENTRIES = 30

        fun defaultStorePath(): Path {
            val configuredHome = System.getProperty("quill.config.dir")
            if (!configuredHome.isNullOrBlank()) {
                return Path.of(configuredHome).resolve("recent-projects.txt")
            }

            val home = Path.of(System.getProperty("user.home", "."))
            // XDG on Linux, Application Support on macOS, AppData on Windows: the three places a
            // user would expect to find — and be able to delete — this file.
            val osName = System.getProperty("os.name").orEmpty().lowercase()
            val base = when {
                osName.contains("win") ->
                    System.getenv("APPDATA")?.let(Path::of) ?: home.resolve("AppData/Roaming")

                osName.contains("mac") -> home.resolve("Library/Application Support")

                else -> System.getenv("XDG_CONFIG_HOME")?.let(Path::of) ?: home.resolve(".config")
            }
            return base.resolve("Quill").resolve("recent-projects.txt")
        }
    }

    /** Reads the list, newest first, dropping anything that is no longer a directory. */
    public fun load(): List<RecentProject> = try {
        if (!Files.isRegularFile(storePath)) {
            emptyList()
        } else {
            Files.readAllLines(storePath, StandardCharsets.UTF_8)
                .asSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() }
                .map(Path::of)
                .filter { it.isDirectory() }
                .distinct()
                .take(MAX_ENTRIES)
                .map(::RecentProject)
                .toList()
        }
    } catch (failure: Exception) {
        // A corrupt or unreadable list is not worth failing a launch over; an empty welcome window
        // still works.
        when (failure) {
            is IOException, is java.nio.file.InvalidPathException -> emptyList()
            else -> throw failure
        }
    }

    /** Moves [project] to the front of the list and persists it. */
    public fun remember(project: Path) {
        if (!project.isDirectory()) return

        val absolute = project.toAbsolutePath().normalize()
        val updated = (listOf(absolute) + load().map { it.path })
            .distinct()
            .take(MAX_ENTRIES)

        try {
            storePath.parent?.let { Files.createDirectories(it) }
            Files.write(
                storePath,
                updated.map(Path::toString),
                StandardCharsets.UTF_8,
            )
        } catch (failure: IOException) {
            // Losing the recent list is a cosmetic failure; it must not surface as an error.
        }
    }

    /** Removes one entry, for the welcome window's context action. */
    public fun forget(project: Path) {
        val remaining = load().map { it.path }.filterNot { it == project }
        try {
            storePath.parent?.let { Files.createDirectories(it) }
            Files.write(storePath, remaining.map(Path::toString), StandardCharsets.UTF_8)
        } catch (failure: IOException) {
            // As above.
        }
    }
}
