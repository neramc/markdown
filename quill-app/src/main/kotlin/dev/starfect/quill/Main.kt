package dev.starfect.quill

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
import dev.starfect.quill.bridge.QuillEngine
import dev.starfect.quill.bridge.QuillNativeLibraryException
import dev.starfect.quill.install.Uninstall
import dev.starfect.quill.io.RecentProject
import dev.starfect.quill.io.RecentProjects
import dev.starfect.quill.io.SettingsStore
import dev.starfect.quill.model.WorkspaceState
import dev.starfect.quill.ui.QuillWindowContent
import dev.starfect.quill.ui.dialog.UninstallDialog
import dev.starfect.quill.ui.shell.QuillTitleBar
import dev.starfect.quill.ui.shell.QuillToolBar
import dev.starfect.quill.ui.shell.StartupFailureWindow
import dev.starfect.quill.ui.shell.isJetBrainsRuntime
import dev.starfect.quill.ui.theme.QuillTheme
import dev.starfect.quill.ui.welcome.WelcomeContent
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.window.DecoratedWindow

/**
 * Quill's entry point.
 *
 * **Two slow things happen here, and they happen at the same time.** Loading the native engine and
 * bringing up the window are independent — one is a shared library and a set of downcall handles,
 * the other is Skia and a Compose scene — and doing them in sequence, as this used to, costs the sum
 * of two waits where the longer of the two would do. The engine is started on another thread and
 * joined at the first point anything needs it, which is after the window has begun to exist.
 *
 * A missing or mismatched native library is still reported in a readable dialog rather than as a
 * stack trace on a blank screen — the failure a user of a two-language application is most likely to
 * hit. It arrives a moment later than it used to, which nobody will mind.
 *
 * Settings are read here, before the window, rather than in an effect after the first frame. It is a
 * small file and the alternative is visible: the window opens in the default theme and then corrects
 * itself, which reads as a bug even when it lasts one frame.
 *
 * Launched with a path, Quill opens it. Launched with nothing, it shows the welcome window, which is
 * what an IDE does: opening whatever directory the process happened to start in is a shell habit,
 * not a desktop application's.
 */
public fun main(arguments: Array<String>) {
    // Apps & features runs `Quill.exe --uninstall`, and that route must not start an editor: there
    // is no document, no engine to load and nothing to be slow about. It returns before any of the
    // rest of this happens.
    if (arguments.any { it.equals("--uninstall", ignoreCase = true) }) {
        runUninstaller(silent = arguments.any { it == "/S" || it.equals("--silent", ignoreCase = true) })
        return
    }

    Startup.begin()
    val engineTask = CompletableFuture.supplyAsync { QuillEngine.create(darkTheme = true) }

    val settingsStore = SettingsStore()
    val restored = settingsStore.load()
    val requested = arguments.map(Path::of).filter { it.exists() }

    application {
        val started: Result<QuillEngine> = remember {
            try {
                Result.success(engineTask.join())
            } catch (wrapped: CompletionException) {
                // `join` wraps whatever the thread threw. The dialog wants the real failure.
                Result.failure(wrapped.cause ?: wrapped)
            }
        }

        started.exceptionOrNull()?.let { failure ->
            if (failure !is QuillNativeLibraryException) throw failure
            StartupFailureWindow(failure, ::exitApplication)
            return@application
        }
        val engine = started.getOrThrow()

        val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
        val controller = remember { QuillController(scope, engine).apply { applySettings(restored) } }
        val recentProjects = remember { RecentProjects() }

        val workspace by controller.state

        // Persist on change rather than on exit: a window closed by the OS, or a crash, should not
        // cost the reader their preferences.
        LaunchedEffect(workspace.settings) {
            if (workspace.settings != restored) {
                withContext(Dispatchers.IO) { settingsStore.save(workspace.settings) }
            }
        }

        // A project is "open" once something is on screen to edit; until then the welcome window
        // stands in for the main window, exactly as the IDE's does.
        var projectOpen by remember { mutableStateOf(requested.isNotEmpty()) }

        // The recent list is up to thirty directories, each of which has to be checked for still
        // existing. That is thirty round trips to the filesystem, and doing them during the first
        // composition means the welcome window cannot paint until they finish. It paints first and
        // fills in instead — which is invisible when the list is warm and the difference between a
        // prompt launch and a stalled one when it is not.
        var recents by remember { mutableStateOf(emptyList<RecentProject>()) }
        LaunchedEffect(Unit) { recents = withContext(Dispatchers.IO) { recentProjects.load() } }

        DisposableEffect(Unit) {
            onDispose {
                controller.close()
                scope.cancel()
            }
        }

        LaunchedEffect(Unit) {
            // A file argument opens its directory as the project, which is what the IDE does: open
            // one file and you get the folder around it in the project view. Without this, launching
            // with a path to a document left the project tool window permanently empty — the tree
            // had no root to scan, so the panel showed its "nothing here" state beside a document
            // that plainly came from somewhere.
            val project = requested.firstOrNull { it.isDirectory() }
                ?: requested.firstOrNull { it.isRegularFile() }?.parent
            project?.let { directory ->
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

        QuillTheme(
            dark = workspace.settings.darkTheme,
            uiFontSize = workspace.settings.uiFontSize,
            islands = workspace.settings.islands,
        ) {
            if (isJetBrainsRuntime()) {
                DecoratedWindow(
                    onCloseRequest = ::exitApplication,
                    state = windowState,
                    title = title,
                    icon = painterResource("icons/icon.png"),
                    onPreviewKeyEvent = { event -> handleShortcut(event, controller) },
                ) {
                    SaveOnFocusLoss(controller, workspace, window)
                    AutoSaveAfterDelay(controller, workspace)
                    AcceptDroppedFiles(controller, window)
                    Column(Modifier.fillMaxSize()) {
                        QuillTitleBar(controller, workspace, ::exitApplication)
                        QuillWindowContent(controller, workspace, Modifier.fillMaxSize(), ::exitApplication)
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
                    SaveOnFocusLoss(controller, workspace, window)
                    AutoSaveAfterDelay(controller, workspace)
                    AcceptDroppedFiles(controller, window)
                    Column(Modifier.fillMaxSize()) {
                        QuillToolBar(controller, workspace, ::exitApplication)
                        QuillWindowContent(controller, workspace, Modifier.fillMaxSize(), ::exitApplication)
                    }
                }
            }
        }
    }
}

/**
 * Accepts files and images dropped onto the window.
 *
 * An AWT `DropTarget` on the window rather than a Compose drag-and-drop modifier, for the same
 * reason focus loss is handled with an AWT listener: the drop is a property of the *window*, and
 * this way one target covers the editor, the preview and the panels — dropping a screenshot
 * anywhere in the window means the same thing.
 *
 * The drop goes through exactly the path a paste takes. An image becomes a file beside the document
 * and a link to it; a Markdown file becomes a link; a file from elsewhere is copied in first. That
 * is the same behaviour whether it arrived through the clipboard or off the desktop, which is one
 * fewer thing for anybody to learn.
 */
@Composable
private fun AcceptDroppedFiles(controller: QuillController, window: java.awt.Window) {
    androidx.compose.runtime.DisposableEffect(window) {
        val listener = object : java.awt.dnd.DropTargetAdapter() {
            override fun drop(event: java.awt.dnd.DropTargetDropEvent) {
                runCatching {
                    event.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY)
                    controller.dropTransferable(event.transferable)
                    event.dropComplete(true)
                }.onFailure { event.dropComplete(false) }
            }
        }
        val target = java.awt.dnd.DropTarget(window, java.awt.dnd.DnDConstants.ACTION_COPY, listener, true)
        onDispose { target.removeDropTargetListener(listener) }
    }
}

/**
 * Saves every modified document when the window loses focus, if the setting is on.
 *
 * An AWT listener rather than a Compose focus modifier: window activation is a property of the
 * window, and Compose's focus system only knows about focus *within* it — it cannot tell the
 * difference between the user clicking another application and clicking a tool window.
 *
 * Documents with no file are skipped rather than prompting. Losing focus is not a moment to raise a
 * modal file picker, and doing so from a listener that fires as the window deactivates puts the
 * dialog behind whatever the user just switched to.
 */
@Composable
private fun SaveOnFocusLoss(controller: QuillController, workspace: WorkspaceState, window: java.awt.Window) {
    val enabled = workspace.settings.saveOnFocusLoss

    DisposableEffect(window, enabled) {
        if (!enabled) return@DisposableEffect onDispose {}

        val listener = object : java.awt.event.WindowAdapter() {
            override fun windowLostFocus(event: java.awt.event.WindowEvent) = save()
            override fun windowDeactivated(event: java.awt.event.WindowEvent) = save()

            private fun save() {
                controller.state.value.documents
                    .filter { it.isModified && it.path != null }
                    .forEach { document -> controller.save(document.id) { null } }
            }
        }

        window.addWindowFocusListener(listener)
        window.addWindowListener(listener)
        onDispose {
            window.removeWindowFocusListener(listener)
            window.removeWindowListener(listener)
        }
    }
}

/**
 * Saves a document once typing stops, if the setting is on.
 *
 * Keyed on the text of every modified document, so each keystroke cancels the pending save and
 * starts the wait again — which is what "after a delay" means, and what stops a save firing in the
 * middle of a sentence. A document with no file is skipped rather than prompting: a file dialog that
 * appears because somebody paused to think is worse than not saving.
 */
@Composable
private fun AutoSaveAfterDelay(controller: QuillController, workspace: WorkspaceState) {
    if (!workspace.settings.autoSaveAfterDelay) return

    val pending = workspace.documents.filter { it.isModified && it.path != null }
    val signature = pending.joinToString("|") { "${it.id}:${it.text.text.length}:${it.text.text.hashCode()}" }

    LaunchedEffect(signature, workspace.settings.autoSaveDelayMillis) {
        if (pending.isEmpty()) return@LaunchedEffect
        delay(workspace.settings.autoSaveDelayMillis.toLong())
        pending.forEach { document -> controller.save(document.id) { null } }
    }
}

/**
 * `Quill.exe --uninstall`, which is what Apps & features runs.
 *
 * This is the entire uninstaller. There is no second executable, no copy of one in the install
 * folder, and nothing in the release to download — the application removes itself, so it can never
 * be missing, never be the wrong version, and never be a hundred megabytes.
 *
 * `/S` skips the confirmation, which is the convention Windows uninstallers follow and what
 * `QuietUninstallString` promises. Without it a small window asks first, because "are you sure"
 * belongs on the one action here that cannot be undone.
 *
 * Nothing but the settings file is read on this path: no engine, no project, no window until the
 * decision needs one.
 */
private fun runUninstaller(silent: Boolean) {
    if (silent) {
        val root = Uninstall.locateInstallRoot() ?: return
        val manifest = Uninstall.readManifest(root).getOrNull() ?: return
        val state = runCatching { Uninstall.readState(manifest, Uninstall.SystemRegistry) }
            .getOrDefault(Uninstall.RegistryState())
        Uninstall.execute(Uninstall.plan(manifest, state))
        return
    }

    val dark = runCatching { SettingsStore().load().darkTheme }.getOrDefault(true)

    application {
        QuillTheme(dark = dark) {
            Window(
                onCloseRequest = ::exitApplication,
                state = rememberWindowState(size = DpSize(560.dp, 380.dp)),
                title = "Uninstall Quill",
                icon = painterResource("icons/icon.png"),
            ) {
                // The same dialog the Help menu opens. One screen, one decision, one implementation
                // — an uninstall that behaved differently depending on how it was started would be
                // two uninstallers again, which is the thing this replaced.
                UninstallDialog(onDismiss = ::exitApplication, onFinished = ::exitApplication)
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
