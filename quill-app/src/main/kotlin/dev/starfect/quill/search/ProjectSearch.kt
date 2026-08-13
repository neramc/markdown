package dev.starfect.quill.search

import java.io.IOException
import java.nio.charset.MalformedInputException
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.PatternSyntaxException
import kotlin.io.path.extension
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isHidden
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.relativeToOrSelf

/**
 * Searching a project, in the five ways people actually search one.
 *
 * The five are not arbitrary and they are not one feature with a checkbox. Each answers a different
 * question, and an editor that offers only the middle one makes the other four impossible:
 *
 * * **File names** — "where is the deployment guide". You know roughly what it is called.
 * * **Content** — "where did we write about rate limits". You know what it says.
 * * **Regular expressions** — "every link to the old domain". You know the shape.
 * * **Recently modified** — "what was I working on yesterday". You know *when*.
 * * **TODO** — "what did I leave unfinished". The notes a writer leaves themselves are a list they
 *   can never see, because they are scattered through every file that has one.
 *
 * Everything here is synchronous and cancellable through [Progress]. Walking a project is the one
 * operation in this editor that is genuinely unbounded — a home directory opened by accident is a
 * million files — so every path through it has a limit and reports when it hit one, rather than
 * appearing to hang.
 */
public object ProjectSearch {

    /** What is being searched for. */
    public enum class Scope(public val title: String, public val placeholder: String) {
        FILE_NAMES("Files", "Type part of a file name"),
        CONTENT("Text", "Type text to find in the project"),
        REGEX("Regex", "Type a regular expression"),
        RECENT("Recent", "Filter recently modified files"),
        TODO("TODO", "Filter the notes left in the project"),
    }

    /** One result. */
    public data class Hit(
        val path: Path,
        /** Zero-based line, or -1 for a result that is about the file rather than a place in it. */
        val line: Int = -1,
        /** UTF-16 offset of the match within the file, or -1. */
        val offset: Int = -1,
        /** The line the match is on, trimmed for display. */
        val preview: String = "",
        /** Where the match sits inside [preview], for highlighting. */
        val previewMatch: IntRange? = null,
        /** Last modified, in epoch millis, for the recent scope's "3 hours ago". */
        val modified: Long = 0,
        /** Which marker a TODO result carries. */
        val marker: String? = null,
    )

    /** What a search produced. */
    public data class Results(
        val hits: List<Hit>,
        /** True when a limit was reached and there are more results than these. */
        val truncated: Boolean = false,
        /** Set when the query itself could not be run — an invalid regular expression. */
        val error: String? = null,
    ) {
        public companion object {
            public val EMPTY: Results = Results(emptyList())
        }
    }

    /** The bounds a search runs inside. */
    public data class Limits(
        val maxResults: Int = 300,
        /** Files larger than this are skipped: a search hit inside a 40MB log helps nobody. */
        val maxFileBytes: Long = 4L * 1024 * 1024,
        val maxFilesScanned: Int = 50_000,
        /** Longest preview line, so one minified file cannot produce a result list of one row. */
        val maxPreviewLength: Int = 200,
    )

    /** Cancellation, so a search abandoned by the next keystroke stops walking the disk. */
    public fun interface Progress {
        public fun isCancelled(): Boolean
    }

    private val NEVER_CANCELLED = Progress { false }

    /**
     * Runs a search under [root].
     *
     * @param now used by the recent scope to describe how long ago a file changed; passed in so the
     *   descriptions are testable rather than dependent on the clock.
     */
    public fun run(
        root: Path,
        scope: Scope,
        query: String,
        caseSensitive: Boolean = false,
        limits: Limits = Limits(),
        now: Long = System.currentTimeMillis(),
        progress: Progress = NEVER_CANCELLED,
    ): Results {
        if (!root.isDirectory()) return Results.EMPTY
        // Every scope but these two needs something to look for; those two are lists in their own
        // right and the query only narrows them.
        if (query.isBlank() && scope != Scope.RECENT && scope != Scope.TODO) return Results.EMPTY

        val files = collectFiles(root, limits, progress)

        return when (scope) {
            Scope.FILE_NAMES -> byName(files, root, query, limits)
            Scope.CONTENT -> byContent(files, query, caseSensitive, limits, progress, regex = false)
            Scope.REGEX -> byContent(files, query, caseSensitive, limits, progress, regex = true)
            Scope.RECENT -> byRecency(files, query, limits, now)
            Scope.TODO -> byTodo(files, query, limits, progress)
        }
    }

    // ------------------------------------------------------------------ walking

    /**
     * Directories that are never a project's own content.
     *
     * A build directory can hold more files than everything a person wrote put together, and a
     * search that returns three copies of the same README — source, build output, and a packaged
     * artifact — is a search nobody trusts a second time.
     */
    private val SKIPPED_DIRECTORIES = setOf(
        ".git", ".hg", ".svn", ".idea", ".vscode", ".gradle", ".venv", "venv", "__pycache__",
        "node_modules", "target", "build", "out", "dist", "vendor", ".next", ".nuxt", ".cache",
    )

    /**
     * Extensions worth reading the contents of.
     *
     * Markdown first, because that is what this is, but a documentation project is not only its
     * Markdown: the configuration, the templates and the code samples beside it are part of what
     * somebody is looking for when they search the project.
     */
    private val TEXT_EXTENSIONS = setOf(
        "md", "markdown", "mdown", "mkd", "mdx", "mdoc", "myst", "mmd", "rst", "adoc", "txt", "text",
        "yml", "yaml", "toml", "json", "json5", "xml", "html", "htm", "css", "scss", "csv", "tsv",
        "kt", "kts", "java", "rs", "py", "js", "jsx", "ts", "tsx", "go", "rb", "sh", "bash", "zsh",
        "c", "h", "cpp", "hpp", "cs", "swift", "sql", "gradle", "properties", "cfg", "ini", "env",
        "gitignore", "editorconfig", "dockerfile", "makefile", "license", "tex", "bib",
    )

    /** Every file under [root] worth considering, depth-first and bounded. */
    private fun collectFiles(root: Path, limits: Limits, progress: Progress): List<Path> {
        val found = ArrayList<Path>()

        try {
            Files.walk(root, FileVisitOption.FOLLOW_LINKS).use { stream ->
                val iterator = stream.iterator()
                while (iterator.hasNext()) {
                    if (progress.isCancelled() || found.size >= limits.maxFilesScanned) break
                    val path = iterator.next()

                    if (path.isDirectory()) continue
                    if (!path.isRegularFile()) continue
                    if (isSkipped(path, root)) continue
                    found.add(path)
                }
            }
        } catch (failure: IOException) {
            // A project on a network share that goes away mid-walk should give what it found, not an
            // exception in the middle of somebody's search.
            return found
        }

        return found
    }

    private fun isSkipped(path: Path, root: Path): Boolean {
        val relative = path.relativeToOrSelf(root)
        for (segment in relative) {
            val name = segment.name
            if (name in SKIPPED_DIRECTORIES) return true
            // A hidden directory is one the project keeps to itself; a hidden file at the top level
            // (.gitignore, .editorconfig) is content somebody may well be looking for.
            if (name.startsWith('.') && segment != relative.fileName) return true
        }
        return runCatching { path.isHidden() && path.name.startsWith('.') && relative.nameCount > 1 }
            .getOrDefault(false)
    }

    private fun isTextFile(path: Path): Boolean {
        val extension = path.extension.lowercase()
        if (extension.isNotEmpty()) return extension in TEXT_EXTENSIONS
        // No extension: README, LICENSE, Makefile, Dockerfile all matter in a documentation project.
        return path.name.lowercase() in TEXT_EXTENSIONS ||
            path.name.first().isUpperCase()
    }

    // ------------------------------------------------------------------ by name

    /**
     * File names, ranked.
     *
     * Subsequence matching over the *relative path*, so `docsdep` finds `docs/deployment.md` — the
     * behaviour that makes a go-to-file box worth using instead of the tree.
     */
    private fun byName(files: List<Path>, root: Path, query: String, limits: Limits): Results {
        val lowered = query.trim().lowercase()

        val scored = files.mapNotNull { path ->
            val relative = path.relativeToOrSelf(root).toString().replace('\\', '/')
            val name = path.name

            val score = when {
                name.equals(query, ignoreCase = true) -> 1000
                name.lowercase().startsWith(lowered) -> 900 - name.length
                name.lowercase().contains(lowered) -> 700 - name.length
                else -> subsequenceScore(relative.lowercase(), lowered) ?: return@mapNotNull null
            }
            // A Markdown file outranks a build script of equal textual merit: this is a Markdown
            // editor, and the file somebody wants is almost always the document.
            val bonus = if (path.extension.lowercase() in MARKDOWN_EXTENSIONS) 50 else 0
            Hit(path = path, modified = lastModified(path)) to (score + bonus)
        }.sortedByDescending { it.second }

        return Results(
            hits = scored.take(limits.maxResults).map { it.first },
            truncated = scored.size > limits.maxResults,
        )
    }

    private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdown", "mkd", "mdx", "mdoc")

    // ------------------------------------------------------------------ by content

    private fun byContent(
        files: List<Path>,
        query: String,
        caseSensitive: Boolean,
        limits: Limits,
        progress: Progress,
        regex: Boolean,
    ): Results {
        val pattern = try {
            if (regex) {
                Regex(query, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
            } else {
                Regex(Regex.escape(query), if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
            }
        } catch (failure: PatternSyntaxException) {
            // An invalid expression is a half-typed one: it is reported in the dialog rather than
            // clearing the results, because the writer is still typing it.
            return Results(emptyList(), error = failure.description ?: "Invalid regular expression")
        } catch (failure: IllegalArgumentException) {
            return Results(emptyList(), error = failure.message ?: "Invalid regular expression")
        }

        val hits = ArrayList<Hit>()
        var truncated = false

        for (path in files) {
            if (progress.isCancelled()) break
            if (hits.size >= limits.maxResults) {
                truncated = true
                break
            }
            val text = readTextFile(path, limits) ?: continue

            for (match in pattern.findAll(text)) {
                if (hits.size >= limits.maxResults) {
                    truncated = true
                    break
                }
                // A pattern that matches the empty string would otherwise produce one result per
                // character in the project.
                if (match.value.isEmpty()) break
                hits.add(hitAt(path, text, match.range.first, match.value.length, limits))
            }
        }

        return Results(hits, truncated)
    }

    // ------------------------------------------------------------------ recent

    /**
     * The files most recently changed, newest first.
     *
     * The answer to "what was I working on", which is a question no other kind of search can
     * answer — the writer does not remember the name or the words, only that it was yesterday.
     */
    private fun byRecency(files: List<Path>, query: String, limits: Limits, now: Long): Results {
        val lowered = query.trim().lowercase()

        val hits = files.asSequence()
            .filter { lowered.isEmpty() || it.name.lowercase().contains(lowered) }
            .map { it to lastModified(it) }
            .sortedByDescending { it.second }
            .take(limits.maxResults)
            .map { (path, modified) ->
                Hit(path = path, modified = modified, preview = describeAge(now - modified))
            }
            .toList()

        return Results(hits)
    }

    /** "3 minutes ago", in the largest unit that is still true. */
    internal fun describeAge(millis: Long): String {
        if (millis < 0) return "just now"
        val seconds = millis / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            seconds < 60 -> "just now"
            minutes < 60 -> "$minutes minute${plural(minutes)} ago"
            hours < 24 -> "$hours hour${plural(hours)} ago"
            days < 7 -> "$days day${plural(days)} ago"
            days < 30 -> "${days / 7} week${plural(days / 7)} ago"
            days < 365 -> "${days / 30} month${plural(days / 30)} ago"
            else -> "${days / 365} year${plural(days / 365)} ago"
        }
    }

    private fun plural(count: Long) = if (count == 1L) "" else "s"

    // ------------------------------------------------------------------ TODO

    /**
     * The notes a writer left themselves.
     *
     * Uppercase and word-bounded on purpose. Matching `todo` case-insensitively finds every
     * sentence containing the word "todo", which in a document about task management is all of
     * them; the convention that a marker is shouted is what makes it findable at all.
     */
    private val TODO_PATTERN = Regex("\\b(TODO|FIXME|XXX|HACK|BUG|NOTE|REVIEW|OPTIMIZE|DEPRECATED)\\b[:\\s]")

    private fun byTodo(files: List<Path>, query: String, limits: Limits, progress: Progress): Results {
        val filter = query.trim().lowercase()
        val hits = ArrayList<Hit>()
        var truncated = false

        for (path in files) {
            if (progress.isCancelled()) break
            if (hits.size >= limits.maxResults) {
                truncated = true
                break
            }
            val text = readTextFile(path, limits) ?: continue

            for (match in TODO_PATTERN.findAll(text)) {
                if (hits.size >= limits.maxResults) {
                    truncated = true
                    break
                }
                val hit = hitAt(path, text, match.range.first, match.value.trimEnd().length, limits)
                if (filter.isNotEmpty() && !hit.preview.lowercase().contains(filter)) continue
                hits.add(hit.copy(marker = match.groupValues[1]))
            }
        }

        return Results(hits, truncated)
    }

    // ------------------------------------------------------------------ shared

    /** Builds a hit from an offset, working out its line and a readable preview. */
    private fun hitAt(path: Path, text: String, offset: Int, length: Int, limits: Limits): Hit {
        val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0))
            .let { if (it < 0 || offset == 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        val line = text.substring(0, offset).count { it == '\n' }

        val raw = text.substring(lineStart, lineEnd)
        // Indentation is not information in a result list; the match's position inside the preview
        // has to move with it, or the highlight lands on the wrong characters.
        val leading = raw.length - raw.trimStart().length
        val trimmed = raw.trim()

        val matchStart = (offset - lineStart - leading).coerceIn(0, trimmed.length)
        val matchEnd = (matchStart + length).coerceIn(matchStart, trimmed.length)

        val preview = if (trimmed.length > limits.maxPreviewLength) {
            trimmed.take(limits.maxPreviewLength) + "…"
        } else {
            trimmed
        }

        return Hit(
            path = path,
            line = line,
            offset = offset,
            preview = preview,
            previewMatch = if (matchEnd > matchStart && matchStart < preview.length) {
                matchStart until matchEnd.coerceAtMost(preview.length)
            } else {
                null
            },
            modified = lastModified(path),
        )
    }

    /** Reads a file, or null when it is too big, not text, or unreadable. */
    private fun readTextFile(path: Path, limits: Limits): String? {
        if (!isTextFile(path)) return null
        return try {
            if (Files.size(path) > limits.maxFileBytes) return null
            path.readText()
        } catch (failure: IOException) {
            null
        } catch (failure: MalformedInputException) {
            // Not UTF-8, so not something this editor can show a preview line from.
            null
        }
    }

    private fun lastModified(path: Path): Long =
        runCatching { path.getLastModifiedTime().toMillis() }.getOrDefault(0L)

    /** Every query character in order; closer together scores higher. */
    private fun subsequenceScore(candidate: String, query: String): Int? {
        if (query.isEmpty()) return 0
        var index = 0
        var score = 400
        var previous = -1
        for (character in query) {
            val found = candidate.indexOf(character, index)
            if (found < 0) return null
            if (previous >= 0) score -= (found - previous - 1).coerceAtMost(8)
            previous = found
            index = found + 1
        }
        return score
    }
}
