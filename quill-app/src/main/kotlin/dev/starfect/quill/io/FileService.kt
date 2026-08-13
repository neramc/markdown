package dev.starfect.quill.io

import dev.starfect.quill.model.FileNode
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isHidden
import kotlin.io.path.name
import kotlin.streams.asSequence

/** File-system access for the workspace: reading, writing and scanning a project tree. */
public class FileService {

    private companion object {
        /** Extensions the project tool window shows alongside directories. */
        val TEXT_EXTENSIONS = setOf("md", "markdown", "mdx", "txt", "adoc", "rst")

        /** Directories never worth walking into for a Markdown project. */
        val IGNORED_DIRECTORIES = setOf(
            ".git", ".idea", ".gradle", "node_modules", "target", "build", "out", "dist",
            ".venv", "__pycache__", ".svn", ".hg",
        )

        /** Cap on entries per directory, so a pathological folder cannot stall the UI. */
        const val MAX_ENTRIES_PER_DIRECTORY = 2_000
    }

    /** Reads a UTF-8 file. */
    public fun read(path: Path): String = Files.readString(path, StandardCharsets.UTF_8)

    /**
     * Writes [content] as UTF-8.
     *
     * The write goes to a sibling temporary file that is then moved into place, so a crash or full
     * disk mid-write leaves the previous version intact rather than a truncated file. That matters
     * more than usual for an editor, where the file being written is the user's only copy.
     */
    public fun write(path: Path, content: String) {
        path.parent?.let { Files.createDirectories(it) }
        val temporary = Files.createTempFile(path.parent ?: Path.of("."), ".${path.name}.", ".tmp")
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8)
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (unsupported: AtomicMoveNotSupportedException) {
                // Some filesystems (and Windows across volumes) cannot move atomically; a plain
                // replace is still better than writing in place.
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: IOException) {
            Files.deleteIfExists(temporary)
            throw failure
        }
    }

    /** Lists the top level of a project directory. */
    public fun scan(root: Path): List<FileNode> = children(root, depth = 0)

    /**
     * Lists one directory's visible entries.
     *
     * The tree is expanded lazily rather than walked up front: a deep project would otherwise cost a
     * full recursive stat before the window even appears.
     */
    public fun children(directory: Path, depth: Int): List<FileNode> {
        if (!directory.isDirectory()) return emptyList()

        return runCatching {
            Files.list(directory).use { stream ->
                stream.asSequence()
                    .filterNot { runCatching { it.isHidden() }.getOrDefault(true) }
                    .filterNot { it.isDirectory() && it.name in IGNORED_DIRECTORIES }
                    .filter { it.isDirectory() || it.extension.lowercase() in TEXT_EXTENSIONS }
                    .take(MAX_ENTRIES_PER_DIRECTORY)
                    .map { path ->
                        FileNode(path = path, name = path.name, isDirectory = path.isDirectory(), depth = depth)
                    }
                    // Directories first, then case-insensitive by name -- the ordering an IDE
                    // project view uses.
                    .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    /** Suggests an HTML export path next to the source file. */
    public fun htmlExportTarget(source: Path?): Path = exportTarget(source, "html")

    /**
     * Where an export of [source] should be written, given a file name.
     *
     * Beside the document, not in a downloads folder: an export is nearly always about *this*
     * document, and having it appear next to the source is what makes it findable without a dialog.
     */
    public fun exportTarget(source: Path?, fileName: String): Path {
        val base = source ?: Path.of(System.getProperty("user.home"), "untitled.md")
        val directory = base.parent ?: Path.of(".")
        return if (fileName.contains('.') && !fileName.startsWith('.')) {
            directory.resolve(fileName)
        } else {
            directory.resolve(base.name.substringBeforeLast('.', base.name) + "." + fileName)
        }
    }
}
