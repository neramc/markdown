package dev.starfect.quill

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.starfect.quill.bridge.ExportOptions
import dev.starfect.quill.bridge.MarkdownFlavour
import dev.starfect.quill.bridge.QuillDocument
import dev.starfect.quill.bridge.QuillEngine
import dev.starfect.quill.bridge.QuillEngineException
import dev.starfect.quill.bridge.SearchFlags
import dev.starfect.quill.bridge.wire.ColorSpan
import dev.starfect.quill.bridge.wire.DocumentStats
import dev.starfect.quill.bridge.wire.Finding
import dev.starfect.quill.bridge.wire.HtmlNode
import dev.starfect.quill.bridge.wire.InspectionSummary
import dev.starfect.quill.bridge.wire.MarkdownBlockIr
import dev.starfect.quill.bridge.wire.OutlineEntry
import dev.starfect.quill.bridge.wire.Severity
import dev.starfect.quill.bridge.wire.StyleSpan
import dev.starfect.quill.io.FileService
import dev.starfect.quill.model.BackgroundTask
import dev.starfect.quill.model.Confirm
import dev.starfect.quill.model.ConfirmChoice
import dev.starfect.quill.model.Dialog
import dev.starfect.quill.model.Dock
import dev.starfect.quill.model.DocumentSession
import dev.starfect.quill.model.FileNode
import dev.starfect.quill.model.FindState
import dev.starfect.quill.model.NavigationHistory
import dev.starfect.quill.model.NavigationPlace
import dev.starfect.quill.model.Notification
import dev.starfect.quill.model.NotificationSeverity
import dev.starfect.quill.editing.AutoPairs
import dev.starfect.quill.editing.UndoHistory
import dev.starfect.quill.editing.CleanPaste
import dev.starfect.quill.editing.DocumentStructure
import dev.starfect.quill.editing.MarkdownEdits
import dev.starfect.quill.editing.MarkdownFeatures
import dev.starfect.quill.editing.Vim
import dev.starfect.quill.export.ExportFormat
import dev.starfect.quill.export.OfficeExport
import dev.starfect.quill.export.PdfExport
import dev.starfect.quill.model.QuillSettings
import dev.starfect.quill.model.RunConfiguration
import dev.starfect.quill.model.RunTask
import dev.starfect.quill.model.ToolWindow
import dev.starfect.quill.model.ViewMode
import dev.starfect.quill.model.WorkspaceState
import dev.starfect.quill.search.ProjectSearch
import java.awt.Toolkit
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Owns the engine, the open documents and the application state.
 *
 * ## Threading
 *
 * Every engine call blocks while Rust parses, so all of them run on [Dispatchers.Default] and only
 * the resulting state update returns to the UI thread. The editor never waits on the engine: typing
 * updates [WorkspaceState] immediately and the derived data (preview blocks, outline, statistics,
 * syntax spans) arrives afterwards.
 *
 * ## Staleness
 *
 * Derivation is debounced and therefore racy by construction — a fast typist can produce several
 * generations of results in flight. Each result carries the engine version it came from and is
 * discarded unless that still matches, so a slow parse can never overwrite a newer one.
 */
public class QuillController(
    private val scope: CoroutineScope,
    private val engine: QuillEngine,
    private val fileService: FileService = FileService(),
) : AutoCloseable {

    private val handles = ConcurrentHashMap<Long, QuillDocument>()
    private val nextId = AtomicLong(1)
    private val derivationJobs = ConcurrentHashMap<Long, Job>()

    /**
     * The lines each editor can currently see.
     *
     * Deliberately *not* in [WorkspaceState]. It changes on every scroll frame, and the workspace is
     * one immutable value that every pane in the window is derived from — putting a scroll position
     * in it would repaint the project tree, the outline and the status bar sixty times a second to
     * describe something none of them show.
     */
    private val visibleLines = ConcurrentHashMap<Long, IntRange>()

    /** Which lines the spans currently in [DocumentSession.spans] cover. */
    private val highlightedLines = ConcurrentHashMap<Long, IntRange>()

    /** The range to ask the engine for: what the editor can see, plus a margin either side. */
    private fun highlightWindow(id: Long): IntRange {
        val visible = visibleLines[id] ?: return 0..INITIAL_HIGHLIGHT_LINES
        val screenful = visible.last - visible.first + 1
        val margin = (screenful * HIGHLIGHT_MARGIN_SCREENS).coerceAtLeast(HIGHLIGHT_MIN_MARGIN_LINES)
        return (visible.first - margin).coerceAtLeast(0)..(visible.last + margin)
    }

    /**
     * Tells the controller which lines of [id] are on screen.
     *
     * Called by the editor as it scrolls. Cheap and idempotent: when the lines are already inside
     * the highlighted window this returns without touching anything, which is the case for every
     * scroll that stays within a few screenfuls of where it started.
     */
    public fun onVisibleLinesChanged(id: Long, first: Int, last: Int) {
        val requested = first.coerceAtLeast(0)..last.coerceAtLeast(first)
        val previous = visibleLines.put(id, requested)
        if (previous == requested) return

        val covered = highlightedLines[id]
        if (covered != null && requested.first >= covered.first && requested.last <= covered.last) return

        refreshHighlighting(id)
    }

    /**
     * Re-highlights [id] for its current viewport, without re-deriving anything else.
     *
     * Scrolling changes nothing about the document, so the blocks, the outline, the statistics and
     * the problems are all still correct; asking for them again would be several times the work of
     * the one thing that did change.
     */
    private fun refreshHighlighting(id: Long) {
        highlightJobs.remove(id)?.cancel()
        highlightJobs[id] = scope.launch {
            delay(HIGHLIGHT_DEBOUNCE_MILLIS)
            val handle = handles[id] ?: return@launch
            val window = highlightWindow(id)
            val spans = withContext(Dispatchers.Default) {
                runCatching { handle.version to handle.spans(window.first, window.last) }.getOrNull()
            } ?: return@launch

            highlightedLines[id] = window
            updateDocument(id) { session ->
                // A newer edit is already re-deriving; its spans will be the right ones.
                if (session.engineVersion > spans.first) session else session.copy(spans = spans.second)
            }
        }
    }

    private val highlightJobs = ConcurrentHashMap<Long, Job>()

    /**
     * Undo and redo, per document.
     *
     * Here rather than in the editor composable because a history that belongs to a text field is
     * discarded when the field is — which meant switching tabs and coming back threw away
     * everything you had written into that tab.
     */
    private val histories = ConcurrentHashMap<Long, UndoHistory>()

    private fun history(id: Long): UndoHistory = histories.getOrPut(id) { UndoHistory() }

    /** Whether [session]'s file has changed since Quill last agreed with it. */
    private fun changedOnDisk(session: DocumentSession): Boolean {
        val path = session.path ?: return false
        val recorded = session.diskStamp ?: return false
        val now = fileService.stamp(path) ?: return false
        return now != recorded
    }

    /**
     * Checks every open document against its file, and acts on what changed.
     *
     * Called when the window regains focus, which is when the user has just been somewhere else —
     * a terminal, another editor, a merge — and is the moment a change is most likely to have
     * happened and most useful to hear about.
     *
     * A buffer with no unsaved edits is reloaded silently. There is nothing to lose and nothing to
     * decide, and an editor showing stale text while the file says otherwise is the more surprising
     * of the two behaviours. A buffer *with* unsaved edits is only flagged: the two versions cannot
     * be merged and Quill will not pick one.
     */
    public fun refreshFromDisk() {
        scope.launch {
            _state.value.documents.forEach { session ->
                val path = session.path ?: return@forEach
                if (!changedOnDisk(session)) return@forEach

                if (session.isModified) {
                    updateDocument(session.id) { it.copy(conflictsWithDisk = true) }
                    update {
                        it.copy(
                            notification = "${path.fileName} changed on disk, and you have unsaved " +
                                "edits. Saving will overwrite the file.",
                        )
                    }
                } else {
                    reloadFromDisk(session.id)
                }
            }
        }
    }

    /** Discards the buffer and reads the file again. */
    public fun reloadFromDisk(id: Long) {
        val session = _state.value.documents.firstOrNull { it.id == id } ?: return
        val path = session.path ?: return

        scope.launch {
            withContext(Dispatchers.IO) { runCatching { fileService.read(path) } }
                .onSuccess { text ->
                    val stamp = fileService.stamp(path)
                    handles[id]?.let { handle -> runCatching { handle.setText(text) } }
                    history(id).reset(TextFieldValue(text))
                    updateDocument(id) { current ->
                        current.copy(
                            text = TextFieldValue(text, TextRange(current.text.selection.start.coerceIn(0, text.length))),
                            savedText = text,
                            diskStamp = stamp,
                            conflictsWithDisk = false,
                        )
                    }
                    derive(id, immediate = true)
                }
                .onFailure { failure ->
                    update { it.copy(notification = "Could not reload ${path.fileName}: ${failure.message}") }
                }
        }
    }

    /**
     * Saves over a file that changed underneath, because the writer said so.
     *
     * The conflict flag is what [save] refuses on, so clearing it first is the whole of "yes, I
     * mean it" — and it is cleared for this one save rather than for the document, so a later
     * change on disk stops the next one again.
     */
    public fun saveOverwritingDisk(id: Long, onNeedsPath: () -> Path?) {
        updateDocument(id) { it.copy(diskStamp = null, conflictsWithDisk = false) }
        save(id, onNeedsPath)
    }

    // ------------------------------------------------------------------ background work

    private val nextTaskId = AtomicLong(1)

    /**
     * A running task's handle, for reporting what it is doing.
     *
     * Passed to the block so progress is reported from inside the work rather than guessed at from
     * outside it. Every method is safe to call from any thread and cheap enough to call in a loop.
     */
    public inner class TaskHandle internal constructor(private val id: Long) {

        /** Sets the fraction complete, or clears it back to indeterminate with null. */
        public fun progress(fraction: Float?) {
            mutateTask(id) { it.copy(fraction = fraction?.coerceIn(0f, 1f)) }
        }

        /** Sets the line under the title: the file being read, the step being run. */
        public fun detail(text: String?) {
            mutateTask(id) { it.copy(detail = text) }
        }

        /** Both at once, which is what a loop over a known number of items wants. */
        public fun progress(done: Int, total: Int, detail: String? = null) {
            mutateTask(id) {
                it.copy(
                    fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null,
                    detail = detail ?: it.detail,
                )
            }
        }
    }

    private fun mutateTask(id: Long, transform: (BackgroundTask) -> BackgroundTask) {
        update { workspace ->
            val index = workspace.tasks.indexOfFirst { it.id == id }
            if (index < 0) {
                workspace
            } else {
                workspace.copy(tasks = workspace.tasks.toMutableList().also { it[index] = transform(it[index]) })
            }
        }
    }

    /**
     * Runs [block] as a named background task, so the window can say it is happening.
     *
     * The task appears in [WorkspaceState.tasks] for its whole life and is removed when the block
     * returns, however it returns — the `finally` is the point, because a task that survives its
     * own failure leaves the status bar claiming work that stopped minutes ago.
     *
     * Cancelling the returned job cancels the block, which is what the status bar's stop button
     * does. Work that cannot be safely interrupted passes `cancellable = false` and is shown
     * without one rather than being shown with a button that does nothing.
     */
    public fun launchTask(
        title: String,
        cancellable: Boolean = true,
        block: suspend TaskHandle.() -> Unit,
    ): Job {
        val id = nextTaskId.getAndIncrement()
        update { it.copy(tasks = it.tasks + BackgroundTask(id = id, title = title, cancellable = cancellable)) }

        return scope.launch {
            try {
                TaskHandle(id).block()
            } finally {
                update { workspace -> workspace.copy(tasks = workspace.tasks.filterNot { it.id == id }) }
            }
        }.also { job -> taskJobs[id] = job; job.invokeOnCompletion { taskJobs.remove(id) } }
    }

    private val taskJobs = ConcurrentHashMap<Long, Job>()

    /**
     * Asks a task to stop.
     *
     * It is marked as stopping straight away rather than removed, because cancellation is a request
     * that takes as long as the block's next suspension point — and an indicator that vanishes on
     * the click while the work carries on is the same lie the silence was.
     */
    public fun cancelTask(id: Long) {
        mutateTask(id) { it.copy(stopping = true) }
        taskJobs[id]?.cancel()
    }

    /** The document a keyboard action applies to, or null when nothing is open. */
    public fun activeDocumentId(): Long? = _state.value.activeDocumentId

    /** Whether [id] has anything to step back to. */
    public fun canUndo(id: Long): Boolean = histories[id]?.canUndo == true

    /** Whether [id] has anything to step forward to. */
    public fun canRedo(id: Long): Boolean = histories[id]?.canRedo == true

    /** Steps [id] back one edit. */
    public fun undo(id: Long) {
        applyHistory(id) { it.undo() }
    }

    /** Steps [id] forward one edit. */
    public fun redo(id: Long) {
        applyHistory(id) { it.redo() }
    }

    /**
     * Puts a remembered value back.
     *
     * It goes through the same path a typed edit does, so the engine's rope, the derived views and
     * the modified marker all move with it — and then the history is told the document reads this
     * way *without* recording a new step, or undo would push what it just undid back onto the stack
     * and Ctrl+Z would alternate between two states for ever.
     */
    private fun applyHistory(id: Long, step: (UndoHistory) -> TextFieldValue?) {
        val history = histories[id] ?: return
        val restored = step(history) ?: return
        applyEdit(id, restored, record = false)
    }

    private val stateLock = Any()
    private val _state = mutableStateOf(WorkspaceState())
    public val state: State<WorkspaceState> = _state

    private companion object {
        /** Narrowest a docked tool window can be dragged before it stops being useful. */
        private const val MIN_TOOL_WINDOW_WIDTH = 150f

        /** Widest, so a side panel cannot take the window. */
        private const val MAX_TOOL_WINDOW_WIDTH = 640f

        /**
         * Debounce before re-deriving. Long enough that a burst of keystrokes coalesces into one
         * parse, short enough that the preview still feels live.
         */
        const val DERIVE_DEBOUNCE_MILLIS = 120L

        /**
         * Lines highlighted above and below the ones on screen.
         *
         * Highlighting is scoped to the viewport because the cost of it is paid by the *text field*,
         * not by the engine: every style range the editor is handed becomes a run Compose has to
         * shape separately, and a 500-line document produces around two thousand of them. Measured
         * on the real window, styling the whole document cost ~240 ms of every keystroke and styling
         * the visible part costs a fraction of that.
         *
         * The margin is what stops a small scroll from needing a new request, and it is measured in
         * screenfuls rather than lines so that it means the same thing at any font size or window
         * height. Two either side means ordinary reading never leaves the window; a jump that does
         * refreshes in the background while the text stays legible — still the right characters in
         * the right places, just briefly unstyled.
         */
        const val HIGHLIGHT_MARGIN_SCREENS = 2

        /** A floor for the margin, so a one-line viewport still gets a workable window. */
        const val HIGHLIGHT_MIN_MARGIN_LINES = 40

        /** What to highlight before the editor has said what it can see. */
        const val INITIAL_HIGHLIGHT_LINES = 150

        /**
         * Settle time before re-highlighting after a scroll.
         *
         * Shorter than the parse debounce because this is one cheap call rather than six, and
         * because unstyled text on screen is the thing the user is waiting to stop looking at.
         */
        const val HIGHLIGHT_DEBOUNCE_MILLIS = 40L

        /** How many notifications to keep. Older ones are noise nobody scrolls back to. */
        const val MAX_NOTIFICATIONS = 50

        /** Debounce before a project search touches the disk. Longer than the parse debounce: this
         *  one reads every file under the root, so a keystroke's worth of work is much larger. */
        const val PROJECT_SEARCH_DEBOUNCE_MILLIS = 180L

        /** How long to wait for a file opened from a search result before giving up on the jump. */
        const val OPEN_POLL_ATTEMPTS = 40
        const val OPEN_POLL_INTERVAL_MILLIS = 25L
    }

    /**
     * Applies [transform] to the workspace state atomically.
     *
     * The lock is not optional. Updates arrive from the UI thread and from every background
     * coroutine the controller launches — a file read, a project scan, a search, a derivation — and
     * each one is a read-modify-write of a single immutable value. Without serialising them, two
     * updates that overlap silently discard the earlier one: opening a project and a file together
     * loses whichever landed first, and an async result arriving after the user toggles a tool
     * window reverts the toggle. The transform itself only rebuilds a small data class, so holding
     * the lock costs nothing measurable.
     */
    private fun update(transform: (WorkspaceState) -> WorkspaceState) {
        synchronized(stateLock) { _state.value = transform(_state.value) }
    }

    private fun updateDocument(id: Long, transform: (DocumentSession) -> DocumentSession) {
        update { workspace ->
            workspace.copy(documents = workspace.documents.map { if (it.id == id) transform(it) else it })
        }
    }

    // ---------------------------------------------------------------- documents

    /** Opens a new empty document and focuses it. */
    public fun newDocument() {
        val id = nextId.getAndIncrement()
        handles[id] = engine.openDocument("")
        history(id).reset(TextFieldValue(""))
        update { workspace ->
            workspace.copy(
                documents = workspace.documents + DocumentSession(id = id, path = null),
                activeDocumentId = id,
            )
        }
        derive(id, immediate = true)
    }

    /**
     * Opens [path], focusing it if it is already open.
     *
     * [onOpened] runs once the document exists and is in the workspace, which is the only moment
     * anything can be done to it. Reading a file is asynchronous, so a caller that wants to land on
     * a particular line cannot simply call this and then jump — the document is not there yet.
     */
    public fun openFile(path: Path, onOpened: ((Long) -> Unit)? = null) {
        val existing = _state.value.documents.firstOrNull { it.path == path }
        if (existing != null) {
            update { it.copy(activeDocumentId = existing.id) }
            // A caller that supplies [onOpened] is placing the caret itself, and recording the
            // arrival before it moves would leave a history entry at line 1 and — worse, when the
            // caller is Back — truncate the forward branch it is walking.
            if (onOpened == null) recordVisit(existing.id) else onOpened(existing.id)
            return
        }

        scope.launch {
            withContext(Dispatchers.IO) { runCatching { fileService.read(path) } }
                .onSuccess { text ->
                    val id = nextId.getAndIncrement()
                    val handle = engine.openDocument(text)
                    handles[id] = handle
                    history(id).reset(TextFieldValue(text))
                    val stamp = fileService.stamp(path)

                    // The extension decides the dialect: a .mdx file has to parse as MDX from the
                    // first render, not after the user finds the setting.
                    val flavour = MarkdownFlavour.forFileName(path.fileName.toString())
                    runCatching { handle.flavour = flavour }

                    update { workspace ->
                        workspace.copy(
                            documents = workspace.documents + DocumentSession(
                                id = id,
                                path = path,
                                text = TextFieldValue(text),
                                savedText = text,
                                flavour = flavour,
                                diskStamp = stamp,
                            ),
                            activeDocumentId = id,
                        )
                    }
                    derive(id, immediate = true)
                    if (onOpened == null) recordVisit(id) else onOpened(id)
                }
                .onFailure { failure ->
                    update { it.copy(notification = "Could not open ${path.fileName}: ${failure.message}") }
                }
        }
    }

    // ---------------------------------------------------------------- writing actions

    /**
     * Applies a Markdown editing action to the focused document.
     *
     * Every action is a pure function of the text field's value — see [MarkdownEdits] — so this is
     * only plumbing: fetch the value, transform it, and push it back through the same path a
     * keystroke takes. Going through [onTextChanged] rather than writing the text directly is what
     * keeps the engine's rope, the undo history and the derived views in step; an action that wrote
     * straight to the session would leave the engine holding the text from before it.
     */
    public fun edit(transform: (TextFieldValue) -> TextFieldValue?) {
        val id = _state.value.activeDocumentId ?: return
        val session = _state.value.documents.firstOrNull { it.id == id } ?: return
        val updated = transform(session.text) ?: return
        onTextChanged(id, updated)
    }

    /**
     * Applies one entry from the Markdown feature catalogue.
     *
     * @param triggerStart where the `/query` that opened the list began, so it can be removed first.
     *   Null when the feature was chosen from the palette, where nothing was typed into the document.
     */
    public fun applyFeature(feature: MarkdownFeatures.Feature, triggerStart: Int? = null) {
        // The contents list is the one entry that cannot be a pure function of the text: it is built
        // from the outline, which only the controller has. Everything else is self-contained, which
        // is what keeps the catalogue testable without a controller at all.
        if (feature.id == "toc") {
            triggerStart?.let { start -> edit { MarkdownFeatures.removeTrigger(it, start) } }
            insertTableOfContents()
            return
        }

        edit { value ->
            val base = triggerStart?.let { MarkdownFeatures.removeTrigger(value, it) } ?: value
            feature.apply(base)
        }
    }

    /**
     * Pastes the clipboard as Markdown.
     *
     * The conversion runs off the UI thread because it goes through the engine, and a paste of a
     * long article is a parse of a long article. Everything after it — inserting the text, moving
     * the caret — is back on the main dispatcher, because it touches the editor's own state.
     */
    public fun pasteClean() {
        val id = _state.value.activeDocumentId ?: return
        val session = _state.value.documents.firstOrNull { it.id == id } ?: return
        val transferable = runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
        }.getOrNull()

        scope.launch {
            val result = withContext(Dispatchers.Default) {
                CleanPaste.convert(
                    transferable = transferable,
                    convertHtml = engine::htmlToMarkdown,
                    assetDirectory = CleanPaste.assetDirectoryFor(session.path),
                    linkBase = session.path?.parent,
                )
            }

            if (result.markdown.isEmpty()) {
                if (result.source == CleanPaste.Source.IMAGE) {
                    update { it.copy(notification = "Save the document first, so the image has somewhere to go") }
                }
                return@launch
            }

            // Re-read the session: the conversion was suspended, and the writer may have typed.
            val current = _state.value.documents.firstOrNull { it.id == id } ?: return@launch
            onTextChanged(id, CleanPaste.apply(current.text, result.markdown))

            if (result.writtenFiles.isNotEmpty()) {
                val names = result.writtenFiles.joinToString(", ") { it.fileName.toString() }
                update { it.copy(notification = "Saved $names beside the document") }
            }
        }
    }

    /**
     * Ticks or unticks the task a preview checkbox stands for.
     *
     * The caret is left where it was: ticking a box in the preview is not a reason for the source
     * pane to jump, and in a split view the writer can see both.
     */
    public fun toggleTask(index: Int) {
        val id = _state.value.activeDocumentId ?: return
        val session = _state.value.documents.firstOrNull { it.id == id } ?: return
        val updated = DocumentStructure.toggleTask(session.text.text, index) ?: return

        onTextChanged(id, session.text.copy(text = updated))
    }

    /**
     * Moves a whole section — a heading and everything under it — in front of another.
     *
     * What dragging a row in the Structure panel does. Reordering a document by its outline is the
     * one rearrangement that is genuinely hard to do by hand: the text is right there, but cutting
     * exactly the right lines out of the middle of a long file and putting them back somewhere else
     * is fiddly, error-prone, and where documents lose paragraphs.
     */
    public fun moveSection(from: Int, to: Int) {
        val id = _state.value.activeDocumentId ?: return
        val session = _state.value.documents.firstOrNull { it.id == id } ?: return

        val sections = DocumentStructure.sections(session.text.text, session.outline)
        val moved = DocumentStructure.moveSection(session.text.text, sections, from, to) ?: return

        onTextChanged(id, TextFieldValue(moved, TextRange(0)))
        update { it.copy(notification = "Moved \"${sections[from].title}\"") }
    }

    /**
     * Drops files or an image into the document at the caret.
     *
     * The same path a paste takes, because it is the same operation: something arrives from outside
     * the editor and has to become Markdown. An image is filed beside the document and linked; a
     * Markdown file is linked where it lies.
     */
    public fun dropTransferable(transferable: java.awt.datatransfer.Transferable?) {
        val id = _state.value.activeDocumentId ?: return
        val session = _state.value.documents.firstOrNull { it.id == id } ?: return

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                CleanPaste.convert(
                    transferable = transferable,
                    convertHtml = engine::htmlToMarkdown,
                    assetDirectory = CleanPaste.assetDirectoryFor(session.path),
                    linkBase = session.path?.parent,
                )
            }
            if (result.markdown.isEmpty()) {
                if (result.source == CleanPaste.Source.IMAGE) {
                    update { it.copy(notification = "Save the document first, so the image has somewhere to go") }
                }
                return@launch
            }

            val current = _state.value.documents.firstOrNull { it.id == id } ?: return@launch
            onTextChanged(id, CleanPaste.apply(current.text, result.markdown))

            if (result.writtenFiles.isNotEmpty()) {
                val names = result.writtenFiles.joinToString(", ") { it.fileName.toString() }
                update { it.copy(notification = "Added $names") }
            }
        }
    }

    /**
     * Inserts a table of contents at the caret, built from the document's own outline.
     *
     * The outline is already derived and on screen in the Structure panel, so this is a formatting
     * of something the reader can see rather than a second parse that might disagree with it.
     *
     * Anchors follow GitHub's slug rules — lowercased, spaces to hyphens, punctuation dropped —
     * because that is where these documents are usually read.
     */
    public fun insertTableOfContents() {
        val id = _state.value.activeDocumentId ?: return
        val session = _state.value.documents.firstOrNull { it.id == id } ?: return

        if (session.outline.isEmpty()) {
            update { it.copy(notification = "This document has no headings to list") }
            return
        }

        // A region rather than a one-off list. The markers are HTML comments, so they show up
        // nowhere the document is rendered, and they are what lets the list be kept up to date
        // afterwards instead of going stale the moment a heading is added.
        edit { DocumentStructure.insertTableOfContentsRegion(it, session.outline) }
    }

    /**
     * Rewrites the marked contents region, when the document has one and the setting is on.
     *
     * Called after each derivation, because that is when a fresh outline exists. Returning without
     * an edit when nothing changed is what keeps this from marking every document modified on every
     * keystroke.
     */
    private fun refreshTableOfContents(id: Long) {
        if (!_state.value.settings.autoTableOfContents) return
        val session = _state.value.documents.firstOrNull { it.id == id } ?: return
        if (session.outline.isEmpty()) return

        val updated = DocumentStructure.updateTableOfContents(session.text.text, session.outline) ?: return
        onTextChanged(id, session.text.copy(text = updated))
    }

    /** GitHub's heading anchor rules, which is where these documents are usually read. */
    private fun slug(title: String): String = title.trim().lowercase()
        .replace(Regex("[^\\p{L}\\p{N}\\s-]"), "")
        .replace(Regex("\\s+"), "-")

    /**
     * Closes a document, releasing its engine handle.
     *
     * The tab goes from the window immediately and the native handle is released afterwards, once
     * the derivation that might be reading it has stopped. This used to wait for that derivation on
     * the calling thread — which is the UI thread — so closing a tab froze the window for as long as
     * the parse took, up to half a second on a large file, and closing ten tabs froze it ten times
     * in a row.
     *
     * The ordering still matters for the same reason it always did: freeing the document out from
     * under a worker reading it is a crash in native code. What changed is who waits.
     */
    public fun closeDocument(id: Long) {
        val running = derivationJobs.remove(id)
        running?.cancel()
        highlightJobs.remove(id)?.cancel()
        val handle = handles.remove(id)
        histories.remove(id)
        visibleLines.remove(id)
        highlightedLines.remove(id)

        if (handle != null) {
            scope.launch {
                running?.join()
                handle.close()
            }
        }

        update { workspace ->
            val remaining = workspace.documents.filterNot { it.id == id }
            workspace.copy(
                // The places stay; only the dead id goes. Back still reaches a closed file, by
                // reopening it.
                navigation = workspace.navigation.forget(id),
                documents = remaining,
                activeDocumentId = if (workspace.activeDocumentId == id) {
                    remaining.lastOrNull()?.id
                } else {
                    workspace.activeDocumentId
                },
            )
        }
    }

    /**
     * Closes every open document except [keep].
     *
     * Unsaved work is closed with everything else, exactly as [closeDocument] does one at a time.
     * The two behaving differently would be the surprise; whichever way Quill decides to guard
     * against losing an unsaved buffer, it has to decide it in one place.
     */
    public fun closeOtherDocuments(keep: Long) {
        _state.value.documents.map { it.id }.filter { it != keep }.forEach(::closeDocument)
    }

    /** Closes every open document. */
    public fun closeAllDocuments() {
        _state.value.documents.map { it.id }.forEach(::closeDocument)
    }

    /** Closes the documents to the right of [id] in the tab strip. */
    public fun closeDocumentsAfter(id: Long) {
        val documents = _state.value.documents
        val index = documents.indexOfFirst { it.id == id }
        if (index < 0) return
        documents.drop(index + 1).map { it.id }.forEach(::closeDocument)
    }

    // ------------------------------------------------- closing, with a question when it costs work

    /**
     * Closes [id], asking first if that would throw away unsaved edits.
     *
     * This is what the tab's close button and Ctrl+W call; [closeDocument] is what actually closes
     * and asks nothing. Keeping the two apart matters because several callers genuinely must not
     * ask — resolving the question itself, replaying a Vim `:q!`, shutting the workspace down — and
     * a single function that sometimes opens a dialog and sometimes does not is a function whose
     * behaviour no caller can rely on.
     */
    public fun requestCloseDocument(id: Long) {
        requestClose(listOf(id))
    }

    /** Closes every document except [keep], asking about the unsaved ones. */
    public fun requestCloseOtherDocuments(keep: Long) {
        requestClose(_state.value.documents.map { it.id }.filter { it != keep })
    }

    /** Closes every document, asking about the unsaved ones. */
    public fun requestCloseAllDocuments() {
        requestClose(_state.value.documents.map { it.id })
    }

    /** Closes the documents to the right of [id] in the tab strip, asking about the unsaved ones. */
    public fun requestCloseDocumentsAfter(id: Long) {
        val documents = _state.value.documents
        val index = documents.indexOfFirst { it.id == id }
        if (index < 0) return
        requestClose(documents.drop(index + 1).map { it.id })
    }

    /**
     * Closes [ids] outright, or raises the question when any of them has unsaved edits.
     *
     * One question for the whole batch rather than one per document: "Close All" over twelve tabs
     * with four modified is one decision to the person making it, and four dialogs in a row is how
     * a confirmation becomes something you click through without reading.
     */
    private fun requestClose(ids: List<Long>) {
        val documents = _state.value.documents
        val closing = ids.mapNotNull { id -> documents.firstOrNull { it.id == id } }
        if (closing.isEmpty()) return

        val unsaved = closing.filter { it.isModified }
        if (unsaved.isEmpty()) {
            closing.forEach { closeDocument(it.id) }
            return
        }

        update {
            it.copy(
                confirm = Confirm.CloseDocuments(
                    ids = closing.map { session -> session.id },
                    unsavedNames = unsaved.map { session -> session.displayName },
                ),
            )
        }
    }

    /** Drops the pending question, doing nothing it asked about. Escape and the close button. */
    public fun dismissConfirm() {
        update { it.copy(confirm = null) }
    }

    /**
     * Asks about unsaved work before the window closes, and answers whether it may close now.
     *
     * Returns true when there is nothing to lose, in which case the caller exits immediately. False
     * means a question is on screen and the exit is now that question's to perform — which is why
     * [resolveConfirm] takes the exit action rather than the controller holding one.
     */
    public fun requestExit(): Boolean {
        val unsaved = _state.value.documents.filter { it.isModified }
        if (unsaved.isEmpty()) return true

        update {
            it.copy(
                confirm = Confirm.Exit(
                    ids = unsaved.map { session -> session.id },
                    unsavedNames = unsaved.map { session -> session.displayName },
                ),
            )
        }
        return false
    }

    /**
     * Carries out the pending question's [choice].
     *
     * [onNeedsPath] is called on the calling thread for any document that has never been saved,
     * because a file picker is a UI-thread affair; the writes it feeds then happen off it.
     * [onExit] closes the application, and is only ever reached from a question that asked about
     * closing it.
     */
    public fun resolveConfirm(
        choice: ConfirmChoice,
        onNeedsPath: (DocumentSession) -> Path? = { null },
        onExit: () -> Unit = {},
    ) {
        val pending = _state.value.confirm ?: return
        update { it.copy(confirm = null) }

        when (pending) {
            is Confirm.CloseDocuments -> when (choice) {
                ConfirmChoice.CANCEL -> Unit
                ConfirmChoice.DISCARD -> pending.ids.forEach(::closeDocument)
                ConfirmChoice.SAVE -> saveThenClose(pending.ids, onNeedsPath)
            }

            is Confirm.Exit -> when (choice) {
                ConfirmChoice.CANCEL -> Unit
                ConfirmChoice.DISCARD -> onExit()
                ConfirmChoice.SAVE -> saveThenExit(pending.ids, onNeedsPath, onExit)
            }
        }
    }

    /**
     * Writes every unsaved document and only then leaves.
     *
     * The exit waits for the last write, because the process ending mid-write is the one way this
     * can still lose the work it was asked to save. A cancelled picker abandons the exit entirely
     * and leaves the window up, which is the only outcome that lets the writer try again.
     */
    private fun saveThenExit(ids: List<Long>, onNeedsPath: (DocumentSession) -> Path?, onExit: () -> Unit) {
        val documents = _state.value.documents
        val plan = ArrayList<Pair<Long, Path>>(ids.size)
        for (id in ids) {
            val session = documents.firstOrNull { it.id == id } ?: continue
            if (!session.isModified) continue
            val target = saveTarget(id) { onNeedsPath(session) } ?: return
            plan += id to target
        }

        scope.launch {
            for ((id, target) in plan) {
                if (!persist(id, target)) return@launch
            }
            onExit()
        }
    }

    /**
     * Writes each of [ids] that needs writing and closes it once the bytes have landed.
     *
     * Every target is resolved before the first byte is written. A cancelled file picker — or a
     * file that changed on disk underneath — abandons the whole batch rather than closing the tabs
     * it got to first, because a half-finished "Save All" leaves no trace of which documents were
     * saved: the tabs that would have told you are the ones that are gone.
     */
    private fun saveThenClose(ids: List<Long>, onNeedsPath: (DocumentSession) -> Path?) {
        val documents = _state.value.documents
        val plan = ArrayList<Pair<Long, Path?>>(ids.size)
        for (id in ids) {
            val session = documents.firstOrNull { it.id == id } ?: continue
            if (!session.isModified) {
                plan += id to null
                continue
            }
            val target = saveTarget(id) { onNeedsPath(session) } ?: return
            plan += id to target
        }

        scope.launch {
            for ((id, target) in plan) {
                if (target != null && !persist(id, target)) return@launch
                closeDocument(id)
            }
        }
    }

    public fun selectDocument(id: Long) {
        update { it.copy(activeDocumentId = id) }
        recordVisit(id)
    }

    /**
     * Applies an editor change.
     *
     * The delta between old and new text is computed here and handed to the engine as a range
     * replacement, so the engine patches its rope instead of rebuilding it. A caret move or a pure
     * selection change produces no engine call at all.
     */
    public fun onTextChanged(id: Long, incoming: TextFieldValue) {
        applyEdit(id, incoming, record = true)
    }

    /**
     * The one funnel every edit passes through.
     *
     * @param record whether the result becomes an undo step. False when the edit *is* an undo.
     */
    private fun applyEdit(id: Long, incoming: TextFieldValue, record: Boolean) {
        val session = _state.value.documents.firstOrNull { it.id == id } ?: return
        val settings = _state.value.settings

        // Auto-closing and wrapping happen here rather than in the editor, because this is the one
        // funnel every edit passes through: a keystroke, a paste, an input method's commit and a
        // programmatic edit all arrive as a finished value, and comparing against the previous one
        // is the only way to know which it was.
        //
        // Except when restoring: an undo is not typing. Running a restored value through the pair
        // logic would let it insert a bracket into text the writer is stepping *back* to, so undo
        // would produce something that was never in the document.
        val value = if (record) {
            AutoPairs.apply(
                before = session.text,
                after = incoming,
                closeBrackets = settings.autoClosingBrackets,
                surroundSelection = settings.autoSurround,
            )
        } else {
            incoming
        }

        // Only a real edit becomes a step. On the restore path the history has already moved its
        // own cursor, and telling it again would push what was just undone back onto the stack.
        if (record) history(id).record(value)

        val previous = session.text.text
        val current = value.text

        if (previous == current) {
            updateDocument(id) { it.copy(text = value) }
            return
        }

        val handle = handles[id] ?: return
        val edit = TextDelta.between(previous, current)
        val engineVersion = try {
            handle.replace(edit.start, edit.end, edit.replacement)
            handle.version
        } catch (failure: QuillEngineException) {
            // Falling back to a full replace keeps the engine and the editor in step even if the
            // incremental path ever disagrees about offsets; the alternative is silent divergence.
            update { it.copy(notification = "Recovered from an edit mismatch: ${failure.message}") }
            handle.setText(current)
            handle.version
        }

        updateDocument(id) { it.copy(text = value, engineVersion = engineVersion) }
        derive(id, immediate = false)
    }

    /**
     * Switches the dialect [id] is parsed as.
     *
     * The engine drops its cached derivations, so this re-derives immediately rather than waiting
     * for the debounce: the user picked a dialect and expects to see it, not to have to type first.
     */
    public fun setFlavour(id: Long, flavour: MarkdownFlavour) {
        val handle = handles[id] ?: return
        runCatching { handle.flavour = flavour }
            .onSuccess {
                updateDocument(id) { it.copy(flavour = flavour) }
                derive(id, immediate = true)
            }
            .onFailure { failure ->
                update { it.copy(notification = "Could not switch to ${flavour.displayName}: ${failure.message}") }
            }
    }

    // ---------------------------------------------------------------- derivation

    private data class Derived(
        val version: Long,
        val blocks: List<MarkdownBlockIr>,
        val html: List<HtmlNode>,
        val outline: List<OutlineEntry>,
        val findings: List<Finding>,
        val stats: DocumentStats,
        val spans: List<StyleSpan>,
    )

    /**
     * Recomputes the derived views of a document.
     *
     * @param immediate skips the debounce, for changes the user did not type (open, save, replace).
     */
    private fun derive(id: Long, immediate: Boolean) {
        derivationJobs.remove(id)?.cancel()
        derivationJobs[id] = scope.launch {
            if (!immediate) delay(DERIVE_DEBOUNCE_MILLIS)
            val handle = handles[id] ?: return@launch

            val settings = _state.value.settings
            val window = highlightWindow(id)

            withContext(Dispatchers.Default) {
                runCatching {
                    val version = handle.version
                    Derived(
                        version = version,
                        blocks = handle.blocks(),
                        html = handle.htmlDom(),
                        outline = handle.outline(),
                        findings = inspect(handle, settings),
                        stats = handle.stats(),
                        // The window is asked for as the budget, not as the document's line count.
                        //
                        // Counting the lines meant pulling the whole document across the boundary and
                        // scanning it for newlines, on every keystroke, to produce a number the
                        // engine did not need: its highlighter walks lines and emits only inside the
                        // window, so a window past the end simply ends with the document. Measured on
                        // a 260KB file the count more than doubled this step — 20.3ms against 8.1ms —
                        // for byte-identical spans.
                        spans = handle.spans(window.first, window.last),
                    )
                }
            }
                .onSuccess { result ->
                    highlightedLines[id] = window
                    updateDocument(id) { session ->
                        // Drop a result that a newer edit has already superseded.
                        if (session.engineVersion > result.version) {
                            session
                        } else {
                            session.copy(
                                derivedVersion = result.version,
                                blocks = result.blocks,
                                html = result.html,
                                outline = result.outline,
                                findings = result.findings,
                                stats = result.stats,
                                spans = result.spans,
                                loadError = null,
                            )
                        }
                    }
                }
                .onFailure { failure -> updateDocument(id) { it.copy(loadError = failure.message) } }

            // A fresh outline is the only moment a contents list can be brought up to date. It
            // returns without an edit unless something actually changed, so this is not a write on
            // every keystroke.
            refreshTableOfContents(id)
        }
    }

    /**
     * Runs the inspections, honouring the two settings that suppress them.
     *
     * Weak warnings are filtered here rather than in the engine so the setting takes effect on the
     * next repaint instead of requiring a re-parse, and so the counts the widget shows and the rows
     * the problems list holds can never disagree.
     */
    private fun inspect(handle: QuillDocument, settings: QuillSettings): List<Finding> {
        if (!settings.inspectionsEnabled) return emptyList()
        val findings = handle.inspections()
        return if (settings.showWeakWarnings) findings else findings.filter { it.severity != Severity.WEAK }
    }

    // ---------------------------------------------------------------- files

    /**
     * Where a never-saved document should go, asked with the platform's own save dialog.
     *
     * Blocking, and deliberately called on the UI thread: a modal file dialog *is* the UI thread's
     * job, and answering it asynchronously would mean deciding what the rest of the window does
     * while it is open.
     */
    public fun promptForPath(session: DocumentSession): Path? = FileService.chooseSaveFile(
        suggestedName = session.path?.fileName?.toString() ?: "untitled.md",
        directory = session.path?.parent ?: _state.value.projectRoot,
    )

    /**
     * Saves a document, asking where to put it when it has never been on disk.
     *
     * This is what the menu, the toolbar and Ctrl+S call. [save] with a caller-supplied path stays
     * for the paths that must not open a dialog — auto-save on focus loss, and save-on-exit, where a
     * modal picker appearing as the window closes is a hang, not a prompt.
     */
    public fun saveWithPrompt(id: Long) {
        val session = _state.value.documents.firstOrNull { it.id == id } ?: return
        save(id) { promptForPath(session) }
    }

    /**
     * Writes a document somewhere new, and moves the document to it.
     *
     * Distinct from [saveWithPrompt] in that it asks *even when the document already has a file* —
     * that is the entire feature. The document then belongs to the new path: its tab renames, and
     * the next Ctrl+S writes there.
     */
    public fun saveAs(id: Long) {
        val session = _state.value.documents.firstOrNull { it.id == id } ?: return
        val target = promptForPath(session) ?: return
        scope.launch { persist(id, target) }
    }

    /** Saves a document. Documents that have never been saved need [onNeedsPath] to supply one. */
    public fun save(id: Long, onNeedsPath: () -> Path?) {
        val target = saveTarget(id, onNeedsPath) ?: return
        scope.launch { persist(id, target) }
    }

    /**
     * Where [id] should be written, or null with the reason already reported.
     *
     * Split out from [save] because the answer is needed on the UI thread — a file picker cannot be
     * opened from a coroutine — while the write itself must not happen there.
     */
    private fun saveTarget(id: Long, onNeedsPath: () -> Path?): Path? {
        val session = _state.value.documents.firstOrNull { it.id == id } ?: return null
        val target = session.path ?: onNeedsPath() ?: run {
            update { it.copy(notification = "This document has no file yet; use File > Export or save it elsewhere") }
            return null
        }

        // Refuse to overwrite a file somebody else has changed since Quill read it. Saving used to
        // be an unconditional write, so a `git checkout`, a formatter or a second editor touching
        // the file while a document sat open cost that work with no message at all. The writer is
        // told and given the choice; [saveOverwritingDisk] is the choice.
        if (session.path == target && changedOnDisk(session)) {
            updateDocument(id) { it.copy(conflictsWithDisk = true) }
            update {
                it.copy(
                    notification = "${target.fileName} changed on disk since you opened it. " +
                        "Reload it, or save again to overwrite.",
                )
            }
            return null
        }

        return target
    }

    /**
     * Writes [id] to [target], reporting the outcome, and answers whether it landed.
     *
     * A suspend function rather than something that launches, because callers exist that must not
     * carry on until the bytes are on disk — closing a document after saving it is the obvious one,
     * and doing that concurrently would race the write against the handle being freed.
     */
    private suspend fun persist(id: Long, target: Path): Boolean {
        val session = _state.value.documents.firstOrNull { it.id == id } ?: return false
        val text = applySaveActions(session.text.text, _state.value.settings)

        return withContext(Dispatchers.IO) { runCatching { fileService.write(target, text) } }
            .fold(
                onSuccess = {
                    // The buffer is rewritten to match what went to disk. Skipping this leaves the
                    // document permanently "modified" against a file it is identical to except for
                    // the whitespace the save action just removed.
                    val written = fileService.stamp(target)
                    if (text != session.text.text) {
                        val caret = session.text.selection.start.coerceIn(0, text.length)
                        updateDocument(id) { current ->
                            current.copy(
                                text = current.text.copy(text = text, selection = TextRange(caret)),
                                path = target,
                                savedText = text,
                                diskStamp = written,
                                conflictsWithDisk = false,
                            )
                        }
                        handles[id]?.let { handle -> runCatching { handle.setText(text) } }
                        derive(id, immediate = true)
                    } else {
                        updateDocument(id) {
                            it.copy(
                                path = target,
                                savedText = text,
                                diskStamp = written,
                                conflictsWithDisk = false,
                            )
                        }
                    }
                    update { it.copy(notification = "Saved ${target.fileName}") }
                    true
                },
                onFailure = { failure ->
                    update { it.copy(notification = "Could not save ${target.fileName}: ${failure.message}") }
                    false
                },
            )
    }

    /**
     * Applies the save-time settings to the text about to be written.
     *
     * Trailing whitespace is stripped everywhere except where Markdown gives it meaning: exactly two
     * trailing spaces are a hard line break, and removing them silently joins two lines the author
     * deliberately separated.
     */
    internal fun applySaveActions(text: String, settings: QuillSettings): String {
        var result = text

        if (settings.trimTrailingWhitespaceOnSave) {
            result = result.lineSequence().joinToString("\n") { line ->
                if (line.endsWith("  ") && !line.endsWith("   ") && line.isNotBlank()) line else line.trimEnd()
            }
        }

        if (settings.ensureNewlineOnSave && result.isNotEmpty()) {
            result = result.trimEnd('\n') + "\n"
        }

        return result
    }

    /** Exports a document to HTML. */
    /**
     * Exports the active document in [format].
     *
     * Every format goes through the same path deliberately: the same rendered tree the preview is
     * showing. Two renderers is how a document comes to look one way on screen and another in the
     * file, and that difference is invisible until somebody publishes.
     */
    public fun export(id: Long, format: ExportFormat, target: Path) {
        val handle = handles[id] ?: return
        val session = _state.value.documents.firstOrNull { it.id == id } ?: return
        val name = session.displayName.substringBeforeLast('.')
        val dark = _state.value.settings.darkTheme

        launchTask("Exporting to ${format.name}", cancellable = false) {
            detail(target.fileName?.toString())
            val message = withContext(Dispatchers.Default) {
                runCatching {
                    when (format) {
                        ExportFormat.HTML -> {
                            val options = ExportOptions.STANDALONE or (if (dark) ExportOptions.DARK else 0)
                            fileService.write(target, handle.exportHtml(name, options))
                            "Exported to ${target.fileName}"
                        }

                        ExportFormat.PDF -> {
                            val report = PdfExport.write(target, name, session.html)
                            buildString {
                                append("Exported ${report.pages} page")
                                if (report.pages != 1) append("s")
                                append(" to ${target.fileName}")
                                report.warning?.let { append(" — ").append(it) }
                            }
                        }

                        ExportFormat.DOCX -> {
                            OfficeExport.writeDocx(target, name, session.html)
                            "Exported to ${target.fileName}"
                        }

                        ExportFormat.EPUB -> {
                            OfficeExport.writeEpub(target, name, session.html)
                            "Exported to ${target.fileName}"
                        }

                        ExportFormat.CONFLUENCE, ExportFormat.NOTION, ExportFormat.GITHUB_README -> {
                            val conversion = requireNotNull(format.conversion)
                            fileService.write(target, handle.convert(conversion))
                            "Converted to ${format.label} in ${target.fileName}"
                        }
                    }
                }.getOrElse { "Export failed: ${it.message}" }
            }
            update { it.copy(notification = message) }
        }
    }

    public fun exportHtml(id: Long, target: Path) {
        val handle = handles[id] ?: return
        val session = _state.value.documents.firstOrNull { it.id == id } ?: return
        val dark = _state.value.settings.darkTheme

        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val options = ExportOptions.STANDALONE or (if (dark) ExportOptions.DARK else 0)
                    val html = handle.exportHtml(session.displayName.substringBeforeLast('.'), options)
                    fileService.write(target, html)
                }
            }
            update { workspace ->
                workspace.copy(
                    notification = result.fold(
                        onSuccess = { "Exported to ${target.fileName}" },
                        onFailure = { "Export failed: ${it.message}" },
                    )
                )
            }
        }
    }

    /** Opens a directory as the project root. */
    public fun openProject(root: Path) {
        launchTask("Scanning ${root.fileName ?: root}") {
            detail(root.toString())
            withContext(Dispatchers.IO) { runCatching { fileService.scan(root) } }
                .onSuccess { nodes -> update { it.copy(projectRoot = root, projectTree = nodes) } }
                .onFailure { failure -> update { it.copy(notification = "Could not read $root: ${failure.message}") } }
        }
    }

    /** Expands or collapses a directory in the project tool window. */
    public fun toggleDirectory(path: Path) {
        update { workspace -> workspace.copy(projectTree = workspace.projectTree.map { toggle(it, path) }) }
    }

    private fun toggle(node: FileNode, path: Path): FileNode = when {
        node.path == path -> node.copy(
            isExpanded = !node.isExpanded,
            // Children are read on first expansion only; collapsing keeps them for a cheap re-open.
            children = if (node.children.isEmpty() && !node.isExpanded) {
                fileService.children(node.path, node.depth + 1)
            } else {
                node.children
            },
        )
        node.isExpanded -> node.copy(children = node.children.map { toggle(it, path) })
        else -> node
    }

    // ---------------------------------------------------------------- find & replace

    public fun updateFind(transform: (FindState) -> FindState) {
        update { it.copy(find = transform(it.find)) }
        runSearch()
    }

    public fun setFindVisible(visible: Boolean, withReplace: Boolean = false) {
        update { it.copy(find = it.find.copy(visible = visible, replaceVisible = withReplace)) }
        if (visible) runSearch() else clearMatches()
    }

    private fun clearMatches() {
        val id = _state.value.activeDocumentId ?: return
        updateDocument(id) { it.copy(matches = emptyList(), currentMatch = -1) }
    }

    private fun searchFlags(find: FindState): Int {
        var flags = 0
        if (!find.caseSensitive) flags = flags or SearchFlags.CASE_INSENSITIVE
        if (find.wholeWord) flags = flags or SearchFlags.WHOLE_WORD
        if (find.regex) flags = flags or SearchFlags.REGEX
        return flags
    }

    private fun runSearch() {
        val workspace = _state.value
        val id = workspace.activeDocumentId ?: return
        val handle = handles[id] ?: return
        val find = workspace.find

        if (!find.visible || find.query.isEmpty()) {
            clearMatches()
            return
        }

        scope.launch {
            withContext(Dispatchers.Default) { runCatching { handle.search(find.query, searchFlags(find)) } }
                .onSuccess { matches ->
                    update { it.copy(find = it.find.copy(error = null)) }
                    updateDocument(id) { session ->
                        session.copy(matches = matches, currentMatch = if (matches.isEmpty()) -1 else 0)
                    }
                }
                .onFailure { failure ->
                    // An invalid regular expression is normal while the user is still typing it, so
                    // it is surfaced in the find bar rather than as an error notification.
                    update { it.copy(find = it.find.copy(error = failure.message)) }
                    updateDocument(id) { it.copy(matches = emptyList(), currentMatch = -1) }
                }
        }
    }

    // ---------------------------------------------------------------- project search

    /** The search currently walking the disk, so a new query can cancel it. */
    private var projectSearchJob: Job? = null

    /** Opens the project search dialog on [scope]. */
    public fun showProjectSearch(scope: ProjectSearch.Scope) {
        update {
            it.copy(
                projectSearch = it.projectSearch.copy(
                    visible = true,
                    scope = scope,
                    query = if (isList(scope)) "" else it.projectSearch.query,
                )
            )
        }
        runProjectSearch()
    }

    /**
     * Whether a scope is a list rather than a search.
     *
     * Recent and TODO both mean something with an empty query — "what did I change", "what did I
     * leave unfinished" — and the others mean nothing at all. That difference decides whether a
     * query survives a change of scope: carrying "rate limit" into the TODO tab hides the list
     * behind a filter nobody typed for it, while carrying it from Text to Regex is the whole point
     * of having both.
     */
    private fun isList(scope: ProjectSearch.Scope): Boolean =
        scope == ProjectSearch.Scope.RECENT || scope == ProjectSearch.Scope.TODO

    public fun hideProjectSearch() {
        projectSearchJob?.cancel()
        update { it.copy(projectSearch = it.projectSearch.copy(visible = false)) }
    }

    /** Changes what is being searched for, and re-runs. */
    public fun updateProjectSearch(
        query: String = _state.value.projectSearch.query,
        scope: ProjectSearch.Scope = _state.value.projectSearch.scope,
        caseSensitive: Boolean = _state.value.projectSearch.caseSensitive,
    ) {
        val previous = _state.value.projectSearch
        // Switching *to* a list scope drops the query; switching between two searches keeps it.
        val carried = if (scope != previous.scope && isList(scope)) "" else query

        update {
            it.copy(
                projectSearch = it.projectSearch.copy(
                    query = carried,
                    scope = scope,
                    caseSensitive = caseSensitive,
                )
            )
        }
        runProjectSearch()
    }

    /**
     * Runs the search on a worker, replacing whatever was running.
     *
     * Debounced like the derivation is, and for the same reason: a project search reads every file
     * under the root, and starting one per keystroke would have the disk doing a hundred times the
     * work for a query nobody finished typing. Cancellation is cooperative and checked inside the
     * walk, so an abandoned search stops rather than finishing into a discarded result.
     */
    private fun runProjectSearch() {
        projectSearchJob?.cancel()
        val workspace = _state.value
        val root = workspace.projectRoot
        val request = workspace.projectSearch

        if (!request.visible || root == null) {
            update { it.copy(projectSearch = it.projectSearch.copy(results = ProjectSearch.Results.EMPTY, running = false)) }
            return
        }

        update { it.copy(projectSearch = it.projectSearch.copy(running = true)) }

        projectSearchJob = launchTask("Searching ${root.fileName ?: root}") {
            delay(PROJECT_SEARCH_DEBOUNCE_MILLIS)
            detail(request.query.takeIf { it.isNotBlank() })
            val results = withContext(Dispatchers.IO) {
                ProjectSearch.run(
                    root = root,
                    scope = request.scope,
                    query = request.query,
                    caseSensitive = request.caseSensitive,
                    progress = { !isActive },
                )
            }

            // A result that arrived after the query moved on is not this query's result.
            val current = _state.value.projectSearch
            if (current.query != request.query || current.scope != request.scope) return@launchTask
            update { it.copy(projectSearch = it.projectSearch.copy(results = results, running = false)) }
        }
    }

    /**
     * Opens a search result and puts the caret on it.
     *
     * The offset has to be applied *after* the file has loaded, which it may not have when this is
     * called — so opening and jumping are one operation here rather than two the caller sequences.
     */
    public fun openHit(hit: ProjectSearch.Hit) {
        hideProjectSearch()
        val existing = _state.value.documents.firstOrNull { it.path == hit.path }
        if (existing != null) {
            update { it.copy(activeDocumentId = existing.id) }
            if (hit.offset >= 0) moveCaret(existing.id, hit.offset)
            return
        }

        openFile(hit.path)
        if (hit.offset < 0) return

        scope.launch {
            // The open is asynchronous and there is no completion to await from here, so this waits
            // for the document to appear rather than guessing at a delay. It gives up rather than
            // spinning: a file that never opens has already reported why.
            repeat(OPEN_POLL_ATTEMPTS) {
                val opened = _state.value.documents.firstOrNull { it.path == hit.path }
                if (opened != null) {
                    moveCaret(opened.id, hit.offset)
                    return@launch
                }
                delay(OPEN_POLL_INTERVAL_MILLIS)
            }
        }
    }

    /** Moves to the next or previous match, wrapping around. */
    public fun stepMatch(forward: Boolean) {
        val id = _state.value.activeDocumentId ?: return
        updateDocument(id) { session ->
            if (session.matches.isEmpty()) {
                session
            } else {
                val next = if (forward) {
                    (session.currentMatch + 1) % session.matches.size
                } else {
                    (session.currentMatch - 1 + session.matches.size) % session.matches.size
                }
                val match = session.matches[next]
                session.copy(
                    currentMatch = next,
                    text = session.text.copy(selection = TextRange(match.start, match.end)),
                )
            }
        }
    }

    /** Replaces every match in the active document. */
    public fun replaceAll() {
        val workspace = _state.value
        val id = workspace.activeDocumentId ?: return
        val handle = handles[id] ?: return
        val find = workspace.find
        if (find.query.isEmpty()) return

        scope.launch {
            withContext(Dispatchers.Default) {
                runCatching {
                    handle.replaceAll(find.query, find.replacement, searchFlags(find))
                    handle.text()
                }
            }
                .onSuccess { updated ->
                    updateDocument(id) { it.copy(text = TextFieldValue(updated), engineVersion = handle.version) }
                    derive(id, immediate = true)
                    runSearch()
                }
                .onFailure { failure -> update { it.copy(find = it.find.copy(error = failure.message)) } }
        }
    }

    /** Moves the caret to a document offset, used by the outline. Recorded in the history. */
    public fun moveCaret(id: Long, offset: Int) {
        navigating(id) { placeCaretAt(id, offset) }
    }

    private fun placeCaretAt(id: Long, offset: Int) {
        updateDocument(id) { session ->
            session.copy(text = session.text.copy(selection = TextRange(offset.coerceIn(0, session.text.text.length))))
        }
    }

    /** Selects a finding's range, so the problem is visible and not merely scrolled to. */
    public fun goToFinding(id: Long, finding: Finding) {
        navigating(id) { selectFinding(id, finding) }
    }

    private fun selectFinding(id: Long, finding: Finding) {
        updateDocument(id) { session ->
            val length = session.text.text.length
            val start = finding.start.coerceIn(0, length)
            val end = finding.end.coerceIn(start, length)
            session.copy(text = session.text.copy(selection = TextRange(start, end)))
        }
    }

    /**
     * Steps to the next or previous finding, wrapping at the ends.
     *
     * Wrapping matters more than it looks: the widget's arrows are how a document gets worked
     * through, and stopping dead at the last problem means noticing that and scrolling back up.
     */
    public fun goToFinding(session: DocumentSession, forward: Boolean) {
        val ordered = session.findings.sortedBy { it.start }
        if (ordered.isEmpty()) return

        val caret = session.text.selection.start
        val target = if (forward) {
            ordered.firstOrNull { it.start > caret } ?: ordered.first()
        } else {
            ordered.lastOrNull { it.start < caret } ?: ordered.last()
        }
        goToFinding(session.id, target)
    }

    // ---------------------------------------------------------------- navigation history

    /** Where the caret in [session] is, as a place the history can return to. */
    private fun placeOf(session: DocumentSession): NavigationPlace {
        val caret = session.caretPosition
        return NavigationPlace(
            documentId = session.id,
            path = session.path,
            line = caret.line + 1,
            column = caret.column + 1,
            label = "${session.displayName}:${caret.line + 1}",
        )
    }

    /**
     * Records a move from one place to another.
     *
     * The departure is written into the *current* entry rather than appended, when the history is
     * already standing in that document — otherwise every jump would leave two entries and Back
     * would need pressing twice to get anywhere.
     */
    private fun recordJump(from: NavigationPlace?, to: NavigationPlace) {
        update { workspace ->
            var history = workspace.navigation
            if (from != null) {
                val here = history.current
                history = if (here != null && here.documentId == from.documentId) {
                    history.reanchor(from)
                } else {
                    history.record(from)
                }
            }
            workspace.copy(navigation = history.record(to))
        }
    }

    /**
     * Runs [move] against [id] and records where it went.
     *
     * Every deliberate jump in the application goes through here: Go to Line, the outline, a
     * problem, a search result. Ordinary typing and arrow keys do not, which is the whole point —
     * a history that records the caret is a history where Back means "up one line".
     */
    private fun navigating(id: Long, move: () -> Unit) {
        val before = _state.value.documents.firstOrNull { it.id == id }?.let(::placeOf)
        move()
        val after = _state.value.documents.firstOrNull { it.id == id }?.let(::placeOf) ?: return
        recordJump(before, after)
    }

    /** Notes that the reader is now looking at [id], without moving anything. */
    public fun recordVisit(id: Long) {
        val session = _state.value.documents.firstOrNull { it.id == id } ?: return
        val place = placeOf(session)
        update { workspace ->
            val here = workspace.navigation.current
            // Re-selecting the tab you are already on is not somewhere new.
            if (here?.documentId == id) workspace else workspace.copy(navigation = workspace.navigation.record(place))
        }
    }

    /** Steps back to the previous place, reopening its file if the tab has since been closed. */
    public fun navigateBack() {
        step { it.back() }
    }

    /** Steps forward again, undoing a [navigateBack]. */
    public fun navigateForward() {
        step { it.forward() }
    }

    /**
     * Moves the history cursor with [step] and goes wherever it lands.
     *
     * The current entry is re-anchored to the live caret first, so Forward returns to where the
     * reader was standing rather than to wherever the entry was first written. Without that, a
     * Back-then-Forward round trip quietly moves the caret, which reads as the arrows being broken.
     */
    private fun step(step: (NavigationHistory) -> NavigationHistory) {
        val workspace = _state.value
        val anchored = workspace.activeDocument
            ?.takeIf { it.id == workspace.navigation.current?.documentId }
            ?.let { workspace.navigation.reanchor(placeOf(it)) }
            ?: workspace.navigation

        val moved = step(anchored)
        if (moved.index == workspace.navigation.index && moved === anchored) return

        update { it.copy(navigation = moved) }
        moved.current?.let(::goTo)
    }

    /**
     * Puts the caret at [place], opening its file first when the tab is gone.
     *
     * The document id is checked against the workspace rather than trusted, because a place can
     * outlive its document: the tab was closed, the id was reused by a later document, or the file
     * was reopened and now has a different one.
     */
    private fun goTo(place: NavigationPlace) {
        val open = place.documentId?.let { id -> _state.value.documents.firstOrNull { it.id == id } }
        if (open != null) {
            update { it.copy(activeDocumentId = open.id) }
            placeCaretAtLine(open.id, place.line, place.column)
            return
        }

        val path = place.path ?: return
        // Re-anchor the place onto the document it just got: the id in the history is dead, and
        // leaving it dead would make every later Back reopen the file from scratch.
        openFile(path) { id ->
            placeCaretAtLine(id, place.line, place.column)
            update { workspace ->
                workspace.copy(
                    navigation = workspace.navigation.copy(
                        places = workspace.navigation.places.map {
                            if (it.path == path && it.documentId == null) it.copy(documentId = id) else it
                        },
                    ),
                )
            }
        }
    }

    // ---------------------------------------------------------------- settings & chrome

    public fun updateSettings(transform: (QuillSettings) -> QuillSettings) {
        val updated = transform(_state.value.settings)
        if (updated.darkTheme != _state.value.settings.darkTheme) {
            engine.setDarkTheme(updated.darkTheme)
            // Code-fence colours are resolved by the engine, so every open preview has to be
            // re-derived when the palette changes.
            handles.keys.forEach { derive(it, immediate = true) }
        }
        update { it.copy(settings = updated) }
    }

    // ---------------------------------------------------------------- modes

    /**
     * Switches between writing and reading.
     *
     * One binding rather than three, because "am I editing or reading" is the question people
     * actually have; the three-way split, source and preview control is for arranging the window,
     * not for changing what you are doing with it. Coming back out of reading returns to whichever
     * arrangement was in use, so the toggle is genuinely a toggle.
     */
    public fun toggleReadingMode() {
        val current = _state.value.settings.viewMode
        if (current != ViewMode.PREVIEW) lastEditingMode = current
        updateSettings {
            it.copy(viewMode = if (current == ViewMode.PREVIEW) lastEditingMode else ViewMode.PREVIEW)
        }
    }

    /** Which arrangement reading mode was entered from. */
    private var lastEditingMode: ViewMode = ViewMode.SPLIT

    /** Turns Focus Mode on or off. */
    public fun toggleFocusMode() {
        updateSettings { it.copy(focusMode = !it.focusMode) }
    }

    /**
     * Feeds one keystroke to Vim, and reports whether Vim wanted it.
     *
     * Everything Vim needs beyond the text — saving, closing, searching — comes back as an effect
     * rather than being done inside the parser, which is what keeps that parser a pure function of
     * its keys and testable without a controller.
     */
    public fun vimKey(key: Vim.Key): Boolean {
        val workspace = _state.value
        if (!workspace.settings.vimMode) return false
        val id = workspace.activeDocumentId ?: return false
        val session = workspace.documents.firstOrNull { it.id == id } ?: return false

        val outcome = Vim.handle(workspace.vim, session.text, key)
        update { it.copy(vim = outcome.state) }

        if (outcome.value.text != session.text.text) {
            onTextChanged(id, outcome.value)
        } else if (outcome.value.selection != session.text.selection) {
            // A pure movement does not need the engine to hear about it.
            updateDocument(id) { it.copy(text = outcome.value) }
        }

        when (val effect = outcome.effect) {
            Vim.Effect.Save -> save(id) { null }
            Vim.Effect.Close -> closeDocument(id)
            Vim.Effect.SaveAndClose -> {
                save(id) { null }
                closeDocument(id)
            }
            Vim.Effect.ClearSearch -> setFindVisible(false)
            is Vim.Effect.Find -> {
                setFindVisible(true)
                updateFind { it.copy(query = effect.query) }
            }
            is Vim.Effect.StepMatch -> stepMatch(effect.forward)
            is Vim.Effect.Report -> update { it.copy(notification = effect.text) }
            null -> Unit
        }

        return outcome.consumed
    }

    public fun setViewMode(mode: ViewMode) {
        updateSettings { it.copy(viewMode = mode) }
    }

    public fun toggleTheme() {
        updateSettings { it.copy(darkTheme = !it.darkTheme) }
    }

    public fun setLeftToolWindow(tool: ToolWindow?) {
        update { it.copy(leftToolWindow = if (it.leftToolWindow == tool) null else tool) }
    }

    public fun setRightToolWindow(tool: ToolWindow?) {
        update { it.copy(rightToolWindow = if (it.rightToolWindow == tool) null else tool) }
    }

    public fun setBottomToolWindow(tool: ToolWindow?) {
        update { it.copy(bottomToolWindow = if (it.bottomToolWindow == tool) null else tool) }
    }

    /**
     * Widens or narrows a docked tool window by [delta] dp.
     *
     * Clamped rather than free: a panel dragged to nothing is indistinguishable from one that was
     * closed, except that it cannot be got back, and one dragged past half the window has stopped
     * being a side panel.
     */
    public fun resizeToolWindow(dock: Dock, delta: Float) {
        updateSettings { settings ->
            when (dock) {
                Dock.LEFT -> settings.copy(
                    leftToolWindowWidth = (settings.leftToolWindowWidth + delta)
                        .coerceIn(MIN_TOOL_WINDOW_WIDTH, MAX_TOOL_WINDOW_WIDTH),
                )
                Dock.RIGHT -> settings.copy(
                    rightToolWindowWidth = (settings.rightToolWindowWidth + delta)
                        .coerceIn(MIN_TOOL_WINDOW_WIDTH, MAX_TOOL_WINDOW_WIDTH),
                )
                Dock.BOTTOM -> settings
            }
        }
    }

    /** Toggles a tool window on whichever dock it belongs to. */
    public fun toggleToolWindow(tool: ToolWindow) {
        when (tool.dock) {
            Dock.LEFT -> setLeftToolWindow(tool)
            Dock.RIGHT -> setRightToolWindow(tool)
            Dock.BOTTOM -> setBottomToolWindow(tool)
        }
    }

    public fun setFeaturePaletteVisible(visible: Boolean) {
        update { it.copy(featurePaletteVisible = visible) }
    }

    public fun setCommandPaletteVisible(visible: Boolean) {
        update { it.copy(commandPaletteVisible = visible) }
    }

    // ---------------------------------------------------------------- dialogs

    /**
     * Moves the caret of [id] to [line] and [column], both one-based as a reader counts them.
     *
     * Clamped rather than refused. A reader who asks for line 900 of an 800-line document means the
     * end, and a jump that silently does nothing because the number was one too large is the kind
     * of thing that gets described as "the button does not work".
     */
    public fun goToLine(id: Long, line: Int, column: Int = 1) {
        navigating(id) { placeCaretAtLine(id, line, column) }
    }

    private fun placeCaretAtLine(id: Long, line: Int, column: Int) {
        val session = _state.value.documents.firstOrNull { it.id == id } ?: return
        val text = session.text.text

        var offset = 0
        var remaining = (line - 1).coerceAtLeast(0)
        while (remaining > 0) {
            val next = text.indexOf('\n', offset)
            if (next < 0) break
            offset = next + 1
            remaining--
        }

        val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        val target = (offset + (column - 1).coerceAtLeast(0)).coerceIn(offset, lineEnd)

        updateDocument(id) { it.copy(text = it.text.copy(selection = TextRange(target))) }
    }

    public fun showDialog(dialog: Dialog) {
        update { it.copy(dialog = dialog) }
    }

    public fun dismissDialog() {
        update { it.copy(dialog = null) }
    }

    /** Replaces the settings wholesale, which is how the Settings dialog applies its edits. */
    public fun applySettings(settings: QuillSettings) {
        val previous = _state.value.settings
        update { it.copy(settings = settings) }

        // The engine holds the palette, so a theme change has to reach it before anything is
        // re-derived — otherwise the next highlight comes back in the old scheme's colours.
        if (settings.darkTheme != previous.darkTheme) {
            runCatching { engine.setDarkTheme(settings.darkTheme) }
        }

        // Every setting that feeds derivation has to re-run it. Missing one does not fail
        // anywhere: the value lands in the state, nothing on screen moves, and the setting reads
        // as ignored until an unrelated keystroke happens to trigger a parse.
        val affectsDerivation = settings.darkTheme != previous.darkTheme ||
            settings.inspectionsEnabled != previous.inspectionsEnabled ||
            settings.showWeakWarnings != previous.showWeakWarnings

        if (affectsDerivation) {
            handles.keys.forEach { derive(it, immediate = true) }
        }
    }

    // ---------------------------------------------------------------- run configurations

    /** Adds a configuration and selects it. */
    public fun addRunConfiguration(task: RunTask): RunConfiguration {
        val configuration = RunConfiguration(
            id = nextId.getAndIncrement(),
            name = uniqueConfigurationName(task.label),
            task = task,
        )
        update {
            it.copy(
                runConfigurations = it.runConfigurations + configuration,
                selectedRunConfigurationId = configuration.id,
            )
        }
        return configuration
    }

    /** Appends a numeric suffix until the name is free, the way the IDE's dialog does. */
    private fun uniqueConfigurationName(base: String): String {
        val taken = _state.value.runConfigurations.map { it.name }.toSet()
        if (base !in taken) return base
        var index = 2
        while ("$base ($index)" in taken) index++
        return "$base ($index)"
    }

    public fun updateRunConfiguration(configuration: RunConfiguration) {
        update { workspace ->
            workspace.copy(
                runConfigurations = workspace.runConfigurations.map {
                    if (it.id == configuration.id) configuration else it
                }
            )
        }
    }

    public fun removeRunConfiguration(id: Long) {
        update { workspace ->
            val remaining = workspace.runConfigurations.filterNot { it.id == id }
            workspace.copy(
                runConfigurations = remaining,
                selectedRunConfigurationId = workspace.selectedRunConfigurationId
                    ?.takeIf { it != id }
                    ?: remaining.firstOrNull()?.id,
            )
        }
    }

    public fun selectRunConfiguration(id: Long) {
        update { it.copy(selectedRunConfigurationId = id) }
    }

    /**
     * Replaces the whole set, which is how the Run/Debug dialog applies its edits.
     *
     * The dialog builds its list locally so Cancel can discard it, and marks entries the user added
     * with a non-positive id because it has no id source of its own. Those are assigned real ids
     * here; anything absent from [configurations] was removed in the dialog and stays removed.
     *
     * @return the configurations as stored, with real ids.
     */
    public fun setRunConfigurations(
        configurations: List<RunConfiguration>,
        selectedId: Long?,
    ): List<RunConfiguration> {
        val remapped = mutableMapOf<Long, Long>()
        val stored = configurations.map { configuration ->
            if (configuration.id > 0) {
                configuration
            } else {
                val id = nextId.getAndIncrement()
                remapped[configuration.id] = id
                configuration.copy(id = id)
            }
        }

        val selection = selectedId
            ?.let { remapped[it] ?: it }
            ?.takeIf { candidate -> stored.any { it.id == candidate } }
            ?: stored.firstOrNull()?.id

        update { it.copy(runConfigurations = stored, selectedRunConfigurationId = selection) }
        return stored
    }

    /**
     * Runs a configuration against the document it names, or the focused one.
     *
     * Results land in the Notifications tool window rather than in a status message, because a run
     * produces something worth going back to — a path, a count, a finding total — and a status
     * message that clears on the next action loses it.
     */
    public fun run(configuration: RunConfiguration) {
        val session = configuration.targetPath
            ?.let { path -> _state.value.documents.firstOrNull { it.path == path } }
            ?: _state.value.activeDocument
            ?: run {
                notify("Nothing to run", "Open a document first.", NotificationSeverity.WARNING)
                return
            }

        val handle = handles[session.id] ?: return
        val name = configuration.name

        // Through [launchTask] so the status bar says something is happening. Exporting a large
        // document takes long enough to look like nothing happened, and "press Run, nothing
        // visible, a notification some seconds later" is indistinguishable from a broken button.
        launchTask(name, cancellable = false) {
            detail(session.displayName)
            val outcome = withContext(Dispatchers.Default) {
                runCatching {
                    when (configuration.task) {
                        RunTask.EXPORT_HTML -> {
                            var options = ExportOptions.NONE
                            if (configuration.standalone) options = options or ExportOptions.STANDALONE
                            if (configuration.darkTheme) options = options or ExportOptions.DARK
                            if (configuration.allowRawHtml) options = options or ExportOptions.ALLOW_RAW_HTML

                            val html = handle.exportHtml(session.displayName, options)
                            val target = configuration.outputPath
                                ?: session.path?.resolveSibling(
                                    session.displayName.substringBeforeLast('.') + ".html"
                                )
                                ?: error("this document has no file yet, so there is nowhere to export to")

                            withContext(Dispatchers.IO) { fileService.write(target, html) }
                            "Wrote ${html.length} characters to $target"
                        }

                        RunTask.INSPECT -> {
                            val summary = InspectionSummary.of(handle.inspections())
                            if (summary.total == 0) {
                                "No problems found"
                            } else {
                                "${summary.errors} errors, ${summary.warnings} warnings, " +
                                    "${summary.weak} weak warnings"
                            }
                        }

                        RunTask.WORD_COUNT -> {
                            val stats = handle.stats()
                            "${stats.words} words, ${stats.characters} characters, ${stats.lines} lines"
                        }
                    }
                }
            }

            outcome
                .onSuccess { message -> notify(name, message, NotificationSeverity.SUCCESS) }
                .onFailure { failure ->
                    notify(name, failure.message ?: failure.toString(), NotificationSeverity.ERROR)
                }
        }
    }

    // ---------------------------------------------------------------- notifications

    /**
     * Records a notification and brings the tool window holding them to the front.
     *
     * Opening the panel is deliberate rather than intrusive: every caller is the result of a run the
     * user just started, and a result they have to go looking for is one they will not see. Quill has
     * no balloon to show it in instead.
     */
    public fun notify(title: String, body: String, severity: NotificationSeverity = NotificationSeverity.INFO) {
        val entry = Notification(id = nextId.getAndIncrement(), title = title, body = body, severity = severity)
        update { workspace ->
            workspace.copy(
                // Newest first: the list is read from the top and only the recent entries matter.
                notifications = (listOf(entry) + workspace.notifications).take(MAX_NOTIFICATIONS),
                rightToolWindow = ToolWindow.NOTIFICATIONS,
            )
        }
    }

    public fun clearNotifications() {
        update { it.copy(notifications = emptyList()) }
    }

    public fun dismissNotification() {
        update { it.copy(notification = null) }
    }

    /** Highlights a fenced code block. Called from the preview's code renderer. */
    public fun highlightCode(code: String, language: String): List<ColorSpan> =
        runCatching { engine.highlightCode(code, language) }.getOrDefault(emptyList())

    override fun close() {
        // Cancel, then wait. `cancel` is a request, and a derivation sitting in a native call has no
        // suspension point at which to honour it — returning from here with one still running would
        // free the document it is reading. The handles guard themselves as well, so this is belt and
        // braces; what it buys is that shutdown does not block on a lock inside a parse.
        val running = derivationJobs.values.toList()
        derivationJobs.clear()
        running.forEach(Job::cancel)
        runBlocking { running.forEach { it.join() } }

        handles.values.forEach(QuillDocument::close)
        handles.clear()
        engine.close()
    }
}

/** A minimal range replacement turning one string into another. */
internal data class TextDelta(val start: Int, val end: Int, val replacement: String) {
    companion object {
        /**
         * Computes the smallest single-range edit between two strings by trimming the common prefix
         * and suffix.
         *
         * This is what makes typing an incremental operation. It is deliberately conservative: any
         * edit is expressible as one replaced range, so a multi-caret or reformat change still
         * produces a correct (if larger) delta rather than a wrong small one.
         *
         * Prefix and suffix boundaries are pulled back off surrogate pairs, because splitting one
         * would hand the engine an offset that is not a character boundary.
         */
        fun between(previous: String, current: String): TextDelta {
            var prefix = 0
            val maxPrefix = minOf(previous.length, current.length)
            while (prefix < maxPrefix && previous[prefix] == current[prefix]) {
                prefix++
            }
            if (prefix > 0 && previous[prefix - 1].isHighSurrogate()) {
                prefix--
            }

            var suffix = 0
            val maxSuffix = minOf(previous.length - prefix, current.length - prefix)
            while (
                suffix < maxSuffix &&
                previous[previous.length - 1 - suffix] == current[current.length - 1 - suffix]
            ) {
                suffix++
            }
            if (suffix > 0 && suffix < maxSuffix && previous[previous.length - suffix].isLowSurrogate()) {
                suffix--
            }

            return TextDelta(
                start = prefix,
                end = previous.length - suffix,
                replacement = current.substring(prefix, current.length - suffix),
            )
        }
    }
}
