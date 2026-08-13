package dev.starfect.quill.search

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The five searches, against a project on disk.
 *
 * Written against real files rather than an abstraction over them, because most of what can go
 * wrong here is about the file system: which directories are skipped, what counts as text, what
 * happens when a file is enormous or is not UTF-8 at all.
 */
class ProjectSearchTest {

    private fun project(): Path = Files.createTempDirectory("quill-search")

    private fun Path.file(relative: String, content: String): Path {
        val target = resolve(relative)
        target.parent?.createDirectories()
        target.writeText(content)
        return target
    }

    private fun names(results: ProjectSearch.Results) = results.hits.map { it.path.name }

    // ------------------------------------------------------------------ file names

    @Test
    fun `a file is found by part of its name`() {
        val root = project()
        root.file("deployment-guide.md", "x")
        root.file("readme.md", "x")

        assertEquals(listOf("deployment-guide.md"), names(ProjectSearch.run(root, ProjectSearch.Scope.FILE_NAMES, "deploy")))
    }

    @Test
    fun `a path is matched as a subsequence, so folder plus name works`() {
        val root = project()
        root.file("docs/deployment.md", "x")
        root.file("notes.md", "x")

        val hits = names(ProjectSearch.run(root, ProjectSearch.Scope.FILE_NAMES, "docsdep"))
        assertEquals(listOf("deployment.md"), hits)
    }

    @Test
    fun `an exact name outranks a partial one`() {
        val root = project()
        root.file("guide.md", "x")
        root.file("style-guide-appendix.md", "x")

        assertEquals("guide.md", names(ProjectSearch.run(root, ProjectSearch.Scope.FILE_NAMES, "guide")).first())
    }

    @Test
    fun `a Markdown file outranks a build script that matches as well`() {
        val root = project()
        root.file("release.md", "x")
        root.file("release.sh", "x")

        assertEquals("release.md", names(ProjectSearch.run(root, ProjectSearch.Scope.FILE_NAMES, "release")).first())
    }

    // ------------------------------------------------------------------ content

    @Test
    fun `content search reports the line and a readable preview`() {
        val root = project()
        root.file("notes.md", "intro\n\n    The rate limit is 100/s.\n\nend\n")

        val hit = ProjectSearch.run(root, ProjectSearch.Scope.CONTENT, "rate limit").hits.single()
        assertEquals(2, hit.line, "zero-based line")
        assertEquals("The rate limit is 100/s.", hit.preview, "indentation is not information here")

        val range = assertNotNull(hit.previewMatch)
        assertEquals("rate limit", hit.preview.substring(range), "the highlight should follow the trim")
    }

    @Test
    fun `content search is case-insensitive unless asked otherwise`() {
        val root = project()
        root.file("a.md", "Rate Limit\n")

        assertEquals(1, ProjectSearch.run(root, ProjectSearch.Scope.CONTENT, "rate limit").hits.size)
        assertEquals(
            0,
            ProjectSearch.run(root, ProjectSearch.Scope.CONTENT, "rate limit", caseSensitive = true).hits.size,
        )
    }

    @Test
    fun `every occurrence in a file is reported, not just the first`() {
        val root = project()
        root.file("a.md", "alpha\nalpha\nalpha\n")

        assertEquals(3, ProjectSearch.run(root, ProjectSearch.Scope.CONTENT, "alpha").hits.size)
    }

    @Test
    fun `Korean and emoji are found and previewed correctly`() {
        val root = project()
        root.file("ko.md", "제목\n\n한국어 문서 🪶 입니다\n")

        val hit = ProjectSearch.run(root, ProjectSearch.Scope.CONTENT, "한국어").hits.single()
        assertEquals("한국어 문서 🪶 입니다", hit.preview)
        assertEquals("한국어", hit.preview.substring(assertNotNull(hit.previewMatch)))
    }

    // ------------------------------------------------------------------ regex

    @Test
    fun `a regular expression finds what a literal search cannot`() {
        val root = project()
        root.file("links.md", "[a](https://old.example.com/x)\n[b](https://new.example.com/y)\n")

        val hits = ProjectSearch.run(root, ProjectSearch.Scope.REGEX, "https://old\\.[a-z.]+/\\w+").hits
        assertEquals(1, hits.size)
        assertEquals(0, hits.single().line)
    }

    @Test
    fun `a half-typed expression reports itself rather than clearing the results`() {
        val root = project()
        root.file("a.md", "text\n")

        val results = ProjectSearch.run(root, ProjectSearch.Scope.REGEX, "[unclosed")
        assertNotNull(results.error, "an invalid expression should say so")
        assertTrue(results.hits.isEmpty())
    }

    @Test
    fun `a pattern that matches nothing at all does not produce a result per character`() {
        // `a*` matches the empty string everywhere. Without a guard this is one hit per character
        // in the project, which is a hang rather than a search.
        val root = project()
        root.file("a.md", "bbbb\n")

        assertTrue(ProjectSearch.run(root, ProjectSearch.Scope.REGEX, "a*").hits.size < 10)
    }

    // ------------------------------------------------------------------ recent

    @Test
    fun `recently modified files come back newest first`() {
        val root = project()
        val old = root.file("old.md", "x")
        val middle = root.file("middle.md", "x")
        val fresh = root.file("fresh.md", "x")

        old.setLastModifiedTime(FileTime.fromMillis(1_000_000))
        middle.setLastModifiedTime(FileTime.fromMillis(2_000_000))
        fresh.setLastModifiedTime(FileTime.fromMillis(3_000_000))

        assertEquals(
            listOf("fresh.md", "middle.md", "old.md"),
            names(ProjectSearch.run(root, ProjectSearch.Scope.RECENT, "")),
        )
    }

    @Test
    fun `the recent list says how long ago in the largest unit that is true`() {
        assertEquals("just now", ProjectSearch.describeAge(30_000))
        assertEquals("5 minutes ago", ProjectSearch.describeAge(5 * 60_000L))
        assertEquals("1 hour ago", ProjectSearch.describeAge(60 * 60_000L))
        assertEquals("3 days ago", ProjectSearch.describeAge(3 * 24 * 60 * 60_000L))
        assertEquals("2 weeks ago", ProjectSearch.describeAge(15 * 24 * 60 * 60_000L))
    }

    @Test
    fun `an empty query is a list rather than nothing, for recent and TODO only`() {
        val root = project()
        root.file("a.md", "TODO: something\n")

        assertTrue(ProjectSearch.run(root, ProjectSearch.Scope.RECENT, "").hits.isNotEmpty())
        assertTrue(ProjectSearch.run(root, ProjectSearch.Scope.TODO, "").hits.isNotEmpty())
        assertTrue(ProjectSearch.run(root, ProjectSearch.Scope.CONTENT, "").hits.isEmpty())
        assertTrue(ProjectSearch.run(root, ProjectSearch.Scope.FILE_NAMES, "").hits.isEmpty())
    }

    // ------------------------------------------------------------------ TODO

    @Test
    fun `TODO search collects every marker convention`() {
        val root = project()
        root.file(
            "notes.md",
            """
            TODO: write the introduction
            FIXME the numbers in the table
            <!-- XXX this section is guesswork -->
            A sentence about a todo list.
            """.trimIndent(),
        )

        val hits = ProjectSearch.run(root, ProjectSearch.Scope.TODO, "").hits
        assertEquals(listOf("TODO", "FIXME", "XXX"), hits.map { it.marker })
        assertTrue(
            hits.none { it.preview.contains("todo list") },
            "a lowercase 'todo' in prose is a word, not a marker",
        )
    }

    @Test
    fun `a TODO in the middle of a line keeps the whole line as its preview`() {
        val root = project()
        root.file("a.md", "The table is wrong. FIXME: recount the rows.\n")

        val hit = ProjectSearch.run(root, ProjectSearch.Scope.TODO, "").hits.single()
        assertEquals("The table is wrong. FIXME: recount the rows.", hit.preview)
    }

    @Test
    fun `the TODO list can be narrowed by what the note says`() {
        val root = project()
        root.file("a.md", "TODO: rewrite the intro\nTODO: check the numbers\n")

        val hits = ProjectSearch.run(root, ProjectSearch.Scope.TODO, "numbers").hits
        assertEquals(1, hits.size)
        assertTrue(hits.single().preview.contains("numbers"))
    }

    // ------------------------------------------------------------------ what gets walked

    @Test
    fun `build output and version control are not searched`() {
        val root = project()
        root.file("src.md", "needle")
        root.file("node_modules/pkg/readme.md", "needle")
        root.file("build/copy.md", "needle")
        root.file(".git/COMMIT_EDITMSG", "needle")
        root.file("target/doc.md", "needle")

        assertEquals(listOf("src.md"), names(ProjectSearch.run(root, ProjectSearch.Scope.CONTENT, "needle")))
    }

    @Test
    fun `a binary-ish file is not searched but a dotfile at the top level is`() {
        val root = project()
        root.file("image.png", "needle")
        root.file(".editorconfig", "needle")

        assertEquals(listOf(".editorconfig"), names(ProjectSearch.run(root, ProjectSearch.Scope.CONTENT, "needle")))
    }

    @Test
    fun `an enormous file is skipped rather than read`() {
        val root = project()
        root.file("huge.md", "needle\n" + "x".repeat(2000))
        root.file("small.md", "needle")

        val results = ProjectSearch.run(
            root,
            ProjectSearch.Scope.CONTENT,
            "needle",
            limits = ProjectSearch.Limits(maxFileBytes = 100),
        )
        assertEquals(listOf("small.md"), names(results))
    }

    // ------------------------------------------------------------------ bounds

    @Test
    fun `hitting the result limit is reported rather than hidden`() {
        val root = project()
        root.file("many.md", "needle\n".repeat(50))

        val results = ProjectSearch.run(
            root,
            ProjectSearch.Scope.CONTENT,
            "needle",
            limits = ProjectSearch.Limits(maxResults = 10),
        )
        assertEquals(10, results.hits.size)
        assertTrue(results.truncated, "a truncated result set has to say so")
    }

    @Test
    fun `a cancelled search stops instead of finishing`() {
        val root = project()
        repeat(20) { root.file("file$it.md", "needle\n") }

        val results = ProjectSearch.run(
            root,
            ProjectSearch.Scope.CONTENT,
            "needle",
            progress = { true },
        )
        assertTrue(results.hits.isEmpty(), "a search cancelled before it began should return nothing")
    }

    @Test
    fun `a very long line is truncated in the preview`() {
        val root = project()
        root.file("wide.md", "needle " + "x".repeat(1000))

        val hit = ProjectSearch.run(
            root,
            ProjectSearch.Scope.CONTENT,
            "needle",
            limits = ProjectSearch.Limits(maxPreviewLength = 40),
        ).hits.single()

        assertTrue(hit.preview.length <= 41, "got ${hit.preview.length} characters")
        assertTrue(hit.preview.endsWith("…"))
    }

    @Test
    fun `searching a path that is not a directory gives nothing rather than failing`() {
        val root = project()
        val file = root.file("a.md", "x")
        assertTrue(ProjectSearch.run(file, ProjectSearch.Scope.CONTENT, "x").hits.isEmpty())
    }
}
