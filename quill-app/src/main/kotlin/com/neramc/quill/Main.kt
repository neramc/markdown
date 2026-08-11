package com.neramc.quill

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.neramc.quill.bridge.QuillEngine
import com.neramc.quill.bridge.QuillNativeLibraryException
import com.neramc.quill.io.RecentProject
import com.neramc.quill.io.RecentProjects
import com.neramc.quill.model.WorkspaceState
import com.neramc.quill.ui.QuillWindowContent
import com.neramc.quill.ui.shell.QuillTitleBar
import com.neramc.quill.ui.shell.QuillToolBar
import com.neramc.quill.ui.shell.StartupFailureWindow
import com.neramc.quill.ui.shell.isJetBrainsRuntime
import com.neramc.quill.ui.theme.QuillTheme
import com.neramc.quill.ui.welcome.WelcomeContent
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.jewel.window.DecoratedWindow

/**
 * Quill's entry point.
 *
 * The engine is created before the window so a missing or mismatched native library is reported in a
 * readable dialog rather than as a stack trace on a blank screen — the failure a user of a
 * two-language application is most likely to hit.
 *
 * Launched with a path, Quill opens it. Launched with nothing, it shows the welcome window, which is
 * what an IDE does: opening whatever directory the process happened to start in is a shell habit,
 * not a desktop application's.
 */
public fun main(arguments: Array<String>) {
    val engine = try {
        QuillEngine.create(darkTheme = true)
    } catch (failure: QuillNativeLibraryException) {
        application { StartupFailureWindow(failure, ::exitApplication) }
        return
    }

    val requested = arguments.map(Path::of).filter { it.exists() }

    application {
        val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
        val controller = remember { QuillController(scope, engine) }
        val recentProjects = remember { RecentProjects() }
        val workspace by controller.state

        // A project is "open" once something is on screen to edit; until then the welcome window
        // stands in for the main window, exactly as the IDE's does.
        var projectOpen by remember { mutableStateOf(requested.isNotEmpty()) }
        var recents by remember { mutableStateOf(recentProjects.load()) }

        DisposableEffect(Unit) {
            onDispose {
                controller.close()
                scope.cancel()
            }
        }

        LaunchedEffect(Unit) {
            requested.firstOrNull { it.isDirectory() }?.let { directory ->
                controller.openProject(directory)
                recentProjects.remember(directory)
            }
            requested.filter { it.isRegularFile() }.forEach(controller::openFile)

            if (requested.isNotEmpty() && controller.state.value.documents.isEmpty()) {
                controller.newDocument()
            }
        }

        if (!projectOpen) {
            WelcomeWindow(
                recents = recents,
                darkTheme = workspace.settings.darkTheme,
                onExit = ::exitApplication,
                onToggleTheme = controller::toggleTheme,
                onOpenProject = { directory ->
                    controller.openProject(directory)
                    recentProjects.remember(directory)
                    recents = recentProjects.load()
                    projectOpen = true
                },
                onNewDocument = {
                    controller.newDocument()
                    projectOpen = true
                },
                onBrowse = {
                    chooseDirectory()?.let { directory ->
                        controller.openProject(directory)
                        recentProjects.remember(directory)
                        recents = recentProjects.load()
                        projectOpen = true
                    }
                },
                onForget = { path ->
                    recentProjects.forget(path)
                    recents = recentProjects.load()
                },
            )
            return@application
        }

        val windowState = rememberWindowState(size = DpSize(1440.dp, 900.dp))
        val title = windowTitle(workspace)

        QuillTheme(dark = workspace.settings.darkTheme) {
            if (isJetBrainsRuntime()) {
                DecoratedWindow(
                    onCloseRequest = ::exitApplication,
                    state = windowState,
                    title = title,
                    icon = painterResource("icons/icon.png"),
                    onPreviewKeyEvent = { event -> handleShortcut(event, controller) },
                ) {
                    Column(Modifier.fillMaxSize()) {
                        QuillTitleBar(controller, workspace, ::exitApplication)
                        QuillWindowContent(controller, workspace, Modifier.fillMaxSize())
                    }
                }
            } else {
                // Stock JDK: keep the platform's own title bar and render Quill's toolbar inside the
                // window instead. Everything below the title bar is identical.
                Window(
                    onCloseRequest = ::exitApplication,
                    state = windowState,
                    title = title,
                    icon = painterResource("icons/icon.png"),
                    onPreviewKeyEvent = { event -> handleShortcut(event, controller) },
                ) {
                    Column(Modifier.fillMaxSize()) {
                        QuillToolBar(controller, workspace, ::exitApplication)
                        QuillWindowContent(controller, workspace, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

/** The welcome window: a small, non-resizable-feeling frame carrying [WelcomeContent]. */
@androidx.compose.runtime.Composable
private fun WelcomeWindow(
    recents: List<RecentProject>,
    darkTheme: Boolean,
    onExit: () -> Unit,
    onToggleTheme: () -> Unit,
    onOpenProject: (Path) -> Unit,
    onNewDocument: () -> Unit,
    onBrowse: () -> Unit,
    onForget: (Path) -> Unit,
) {
    val state = rememberWindowState(size = DpSize(1000.dp, 700.dp))

    QuillTheme(dark = darkTheme) {
        Window(
            onCloseRequest = onExit,
            state = state,
            title = "Welcome to Quill",
            icon = painterResource("icons/icon.png"),
        ) {
            WelcomeContent(
                version = System.getProperty("quill.version", "0.1.0"),
                recents = recents,
                onOpenProject = onOpenProject,
                onNewDocument = onNewDocument,
                onBrowse = onBrowse,
                onForget = onForget,
                onToggleTheme = onToggleTheme,
                darkTheme = darkTheme,
            )
        }
    }
}

/**
 * Asks the platform for a directory.
 *
 * AWT's `FileDialog` is used rather than Swing's `JFileChooser` because it is the *native* dialog on
 * macOS and Windows, and a file picker that does not look like the platform's own is jarring in a
 * way no amount of theming fixes. The `apple.awt.fileDialogForDirectories` property is what makes it
 * select directories on macOS; elsewhere the parent of the chosen file is used.
 */
private fun chooseDirectory(): Path? {
    val previous = System.getProperty("apple.awt.fileDialogForDirectories")
    System.setProperty("apple.awt.fileDialogForDirectories", "true")

    return try {
        val dialog = FileDialog(null as Frame?, "Open Folder", FileDialog.LOAD)
        dialog.isVisible = true

        val directory = dialog.directory ?: return null
        val file = dialog.file

        val selected = if (file != null) Path.of(directory, file) else Path.of(directory)
        if (selected.isDirectory()) selected else selected.parent
    } catch (failure: Exception) {
        // A headless or otherwise unavailable dialog must not take the application down with it.
        when (failure) {
            is java.awt.HeadlessException, is SecurityException -> null
            else -> throw failure
        }
    } finally {
        if (previous == null) {
            System.clearProperty("apple.awt.fileDialogForDirectories")
        } else {
            System.setProperty("apple.awt.fileDialogForDirectories", previous)
        }
    }
}

/**
 * The window title, in IntelliJ's own format.
 *
 * `project – file` is what the IDE puts there, and it is the only place the file name appears
 * outside the tab, which is why the main toolbar does not repeat it.
 */
private fun windowTitle(workspace: WorkspaceState): String {
    val document = workspace.activeDocument ?: return "Quill"
    val project = workspace.projectRoot?.fileName?.toString()
    return listOfNotNull(project, document.displayName).joinToString(" – ")
}
