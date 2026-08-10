package com.neramc.quill

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.neramc.quill.bridge.QuillEngine
import com.neramc.quill.bridge.QuillNativeLibraryException
import com.neramc.quill.model.WorkspaceState
import com.neramc.quill.ui.QuillWindowContent
import com.neramc.quill.ui.shell.QuillTitleBar
import com.neramc.quill.ui.shell.QuillToolBar
import com.neramc.quill.ui.shell.StartupFailureWindow
import com.neramc.quill.ui.shell.isJetBrainsRuntime
import com.neramc.quill.ui.theme.QuillTheme
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
 */
public fun main(arguments: Array<String>) {
    val engine = try {
        QuillEngine.create(darkTheme = true)
    } catch (failure: QuillNativeLibraryException) {
        application { StartupFailureWindow(failure, ::exitApplication) }
        return
    }

    application {
        val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
        val controller = remember { QuillController(scope, engine) }
        val workspace by controller.state

        DisposableEffect(Unit) {
            onDispose {
                controller.close()
                scope.cancel()
            }
        }

        LaunchedEffect(Unit) {
            val paths = arguments.map(Path::of).filter { it.exists() }
            paths.firstOrNull { it.isDirectory() }?.let(controller::openProject)
            paths.filter { it.isRegularFile() }.forEach(controller::openFile)
            if (controller.state.value.documents.isEmpty()) {
                controller.newDocument()
            }
            if (controller.state.value.projectRoot == null) {
                controller.openProject(Path.of("").toAbsolutePath())
            }
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

private fun windowTitle(workspace: WorkspaceState): String {
    val document = workspace.activeDocument ?: return "Quill"
    val modified = if (document.isModified) "*" else ""
    val project = workspace.projectRoot?.fileName?.toString()
    return listOfNotNull(project, "$modified${document.displayName}").joinToString(" — ") + " – Quill"
}
