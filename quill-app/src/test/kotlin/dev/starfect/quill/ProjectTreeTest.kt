package dev.starfect.quill

import dev.starfect.quill.bridge.QuillEngine
import dev.starfect.quill.model.FileNode
import dev.starfect.quill.model.ToolWindow
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The project tool window's own actions.
 *
 * All three exist because something in the window promised them and did not keep the promise:
 * "Show in Project View" only opened the panel, leaving the file as many collapsed directories deep
 * as it was; the breadcrumbs lit up under the pointer and did nothing; and the tree was read once
 * when the project opened, so a file written by anything else was invisible until a restart.
 */
class ProjectTreeTest {

    private lateinit var scope: CoroutineScope
    private lateinit var controller: QuillController
    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        controller = QuillController(scope, QuillEngine.create(darkTheme = true))
        root = Files.createTempDirectory("quill-tree")
        root.resolve("docs/guide/deep").createDirectories()
        root.resolve("docs/guide/deep/buried.md").writeText("# Buried\n")
        root.resolve("top.md").writeText("# Top\n")
    }

    @AfterTest
    fun tearDown() {
        controller.close()
        scope.cancel()
        root.toFile().deleteRecursively()
    }

    private fun openProject() {
        controller.openProject(root)
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            if (controller.state.value.projectTree.isNotEmpty()) return
            Thread.sleep(5)
        }
        error("the project never scanned")
    }

    private fun expandedPaths(nodes: List<FileNode> = controller.state.value.projectTree): List<Path> =
        buildList {
            fun walk(current: List<FileNode>) {
                current.forEach { node ->
                    if (node.isDirectory && node.isExpanded) {
                        add(node.path)
                        walk(node.children)
                    }
                }
            }
            walk(nodes)
        }

    private fun visiblePaths(nodes: List<FileNode> = controller.state.value.projectTree): List<Path> =
        buildList {
            fun walk(current: List<FileNode>) {
                current.forEach { node ->
                    add(node.path)
                    if (node.isDirectory && node.isExpanded) walk(node.children)
                }
            }
            walk(nodes)
        }

    @Test
    fun `revealing a buried file expands every directory above it`() {
        openProject()
        val buried = root.resolve("docs/guide/deep/buried.md")
        assertTrue(expandedPaths().isEmpty(), "nothing is expanded when a project opens")

        controller.revealInProject(buried)

        assertEquals(
            listOf(root.resolve("docs"), root.resolve("docs/guide"), root.resolve("docs/guide/deep")),
            expandedPaths(),
            "every directory between the root and the file has to be open for it to be visible",
        )
        assertTrue(buried in visiblePaths(), "the file itself is now a row in the tree")
    }

    @Test
    fun `revealing opens the panel rather than toggling it`() {
        openProject()
        // The panel starts open. A "show me" that closes it because it was already open is the bug
        // a plain toggle would have.
        assertEquals(ToolWindow.PROJECT, controller.state.value.leftToolWindow)

        controller.revealInProject(root.resolve("top.md"))

        assertEquals(ToolWindow.PROJECT, controller.state.value.leftToolWindow)
    }

    @Test
    fun `revealing something outside the project changes nothing`() {
        openProject()
        controller.revealInProject(Path.of("/somewhere/else/entirely.md"))
        assertTrue(expandedPaths().isEmpty())
    }

    @Test
    fun `collapse all folds the whole tree`() {
        openProject()
        controller.revealInProject(root.resolve("docs/guide/deep/buried.md"))
        assertEquals(3, expandedPaths().size)

        controller.collapseAllDirectories()

        assertTrue(expandedPaths().isEmpty())
    }

    @Test
    fun `refresh picks up a file written outside Quill`() {
        openProject()
        assertFalse(visiblePaths().any { it.fileName.toString() == "later.md" })

        root.resolve("later.md").writeText("# Written by something else\n")
        controller.refreshProject()

        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            if (visiblePaths().any { it.fileName.toString() == "later.md" }) return
            Thread.sleep(10)
        }
        error("refresh never showed the new file")
    }

    @Test
    fun `refresh keeps what was expanded expanded`() {
        openProject()
        val buried = root.resolve("docs/guide/deep/buried.md")
        controller.revealInProject(buried)
        val before = expandedPaths()

        root.resolve("another.md").writeText("# Another\n")
        controller.refreshProject()

        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            if (visiblePaths().any { it.fileName.toString() == "another.md" }) break
            Thread.sleep(10)
        }

        assertEquals(
            before,
            expandedPaths(),
            "a refresh that collapses the tree is indistinguishable from reopening the project",
        )
    }
}
