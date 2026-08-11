package dev.starfect.quill.io

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads the current branch of a Git working tree, for the toolbar's VCS widget.
 *
 * IntelliJ always shows a branch widget next to the project widget, and leaving it out is one of the
 * more noticeable gaps in a toolbar that is otherwise arranged like the IDE's. Rather than draw a
 * decorative fake, this reads the real branch — which means the widget is either accurate or absent.
 *
 * `.git/HEAD` is parsed directly instead of shelling out to `git`: it is a single small file with a
 * stable two-case format, and spawning a process on every recomposition to learn one string would be
 * absurd. A detached HEAD reports the short commit, which is what the IDE shows too.
 */
public object GitStatus {

    /** Bytes of `.git/HEAD` worth reading; a real one is well under 100. */
    private const val MAX_HEAD_BYTES = 4096L

    /**
     * Returns the branch name for the repository containing [directory], or `null` when there is
     * none — not a repository, an unreadable `.git`, or an empty HEAD.
     */
    public fun currentBranch(directory: Path?): String? {
        val gitDirectory = findGitDirectory(directory) ?: return null

        return try {
            val head = gitDirectory.resolve("HEAD")
            if (!Files.isRegularFile(head) || Files.size(head) > MAX_HEAD_BYTES) {
                return null
            }

            val contents = Files.readString(head).trim()
            when {
                contents.startsWith("ref:") ->
                    contents.removePrefix("ref:").trim().removePrefix("refs/heads/").ifEmpty { null }

                // Detached HEAD: the file holds a raw object id, and the IDE shows it abbreviated.
                contents.length >= 7 && contents.all { it.isDigit() || it in 'a'..'f' } -> contents.take(7)

                else -> null
            }
        } catch (failure: IOException) {
            null
        }
    }

    /**
     * Walks up from [directory] looking for `.git`.
     *
     * A worktree or submodule has a `.git` *file* pointing at the real directory rather than a
     * directory of its own, so both shapes are handled; ignoring the file case would make the widget
     * vanish in exactly the checkouts most likely to be someone's daily driver.
     */
    private fun findGitDirectory(directory: Path?): Path? {
        var current = directory?.toAbsolutePath()

        while (current != null) {
            val candidate = current.resolve(".git")
            when {
                Files.isDirectory(candidate) -> return candidate

                Files.isRegularFile(candidate) -> return try {
                    val pointer = Files.readString(candidate).trim().removePrefix("gitdir:").trim()
                    if (pointer.isEmpty()) null else current.resolve(pointer).normalize()
                } catch (failure: IOException) {
                    null
                }
            }
            current = current.parent
        }

        return null
    }
}
