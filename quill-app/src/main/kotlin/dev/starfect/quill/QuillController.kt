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
import dev.starfect.quill.model.Dialog
import dev.starfect.quill.model.Dock
import dev.starfect.quill.model.DocumentSession
import dev.starfect.quill.model.FileNode
import dev.starfect.quill.model.FindState
import dev.starfect.quill.model.Notification
import dev.starfect.quill.model.NotificationSeverity
import dev.starfect.quill.editing.CleanPaste
import dev.starfect.quill.editing.MarkdownEdits
import dev.starfect.quill.editing.MarkdownFeatures
import dev.starfect.quill.editing.Vim
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

        /** How many lines to highlight; beyond this the viewport would need tracking anyway. */
        const val HIGHLIGHT_LINE_BUDGET = 5_000

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
        update { workspace ->
            workspace.copy(
                documents = workspace.documents + DocumentSession(id = id, path = null),
                activeDocumentId = id,
            )
        }
        derive(id, immediate = true)
    }

    /** Opens [path], focusing it if it is already open. */
    public fun openFile(path: Path) {
        val existing = _state.value.documents.firstOrNull { it.path == path }
        if (existing != null) {
            update { it.copy(activeDocumentId = existing.id) }
            return
        }

        scope.launch {
            withContext(Dispatchers.IO) { runCatching { fileService.read(path) } }
                .onSuccess { text ->
                    val id = nextId.getAndIncrement()
                    val handle = engine.openDocument(text)
                    handles[id] = handle

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
                            ),
                            activeDocumentId = id,
                        )
                    }
                    derive(id, immediate = true)
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
        val outline = session.outline

        if (outline.isEmpty()) {
            update { it.copy(notification = "This document has no headings to list") }
            return
        }

        // The shallowest heading becomes the top level, so a document whose headings all start at
        // ## does not get a contents list indented under nothing.
        val base = outline.minOf { it.level }
        val contents = outline.joinToString("\n") { entry ->
            val indent = "  ".repeat((entry.level - base).coerceAtLeast(0))
            "$indent- [${entry.title}](#${slug(entry.title)})"
        }

        edit { value ->
            val caret = value.selection.max
            val updated = value.text.substring(0, caret) + contents + "\n" + value.text.substring(caret)
            TextFieldValue(updated, TextRange(caret + contents.length + 1))
        }
    }

    /** GitHub's heading anchor rules, which is where these documents are usually read. */
    private fun slug(title: String): String = title.trim().lowercase()
        .replace(Regex("[^\\p{L}\\p{N}\\s-]"), "")
        .replace(Regex("\\s+"), "-")

    /** Closes a document, releasing its engine handle. */
    public fun closeDocument(id: Long) {
        // Same ordering as [close]: the document's derivation has to have finished before its handle
        // is released, or the worker is left reading freed memory.
        val running = derivationJobs.remove(id)
        running?.cancel()
        if (running != null) runBlocking { running.join() }
        handles.remove(id)?.close()
        update { workspace ->
            val remaining = workspace.documents.filterNot { it.id == id }
            workspace.copy(
                documents = remaining,
                activeDocumentId = if (workspace.activeDocumentId == id) {
                    remaining.lastOrNull()?.id
                } else {
                    workspace.activeDocumentId
                },
            )
        }
    }

    public fun selectDocument(id: Long) {
        update { it.copy(activeDocumentId = id) }
    }

    /**
     * Applies an editor change.
     *
     * The delta between old and new text is computed here and handed to the engine as a range
     * replacement, so the engine patches its rope instead of rebuilding it. A caret move or a pure
     * selection change produces no engine call at all.
     */
    public fun onTextChanged(id: Long, value: TextFieldValue) {
        val session = _state.value.documents.firstOrNull { it.id == id } ?: return
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
                        spans = handle.spans(0, HIGHLIGHT_LINE_BUDGET),
                    )
                }
            }
                .onSuccess { result ->
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

    /** Saves a document. Documents that have never been saved need [onNeedsPath] to supply one. */
    public fun save(id: Long, onNeedsPath: () -> Path?) {
        val session = _state.value.documents.firstOrNull { it.id == id } ?: return
        val target = session.path ?: onNeedsPath() ?: run {
            update { it.copy(notification = "This document has no file yet; use File > Export or save it elsewhere") }
            return
        }

        val text = applySaveActions(session.text.text, _state.value.settings)

        scope.launch {
            withContext(Dispatchers.IO) { runCatching { fileService.write(target, text) } }
                .onSuccess {
                    // The buffer is rewritten to match what went to disk. Skipping this leaves the
                    // document permanently "modified" against a file it is identical to except for
                    // the whitespace the save action just removed.
                    if (text != session.text.text) {
                        val caret = session.text.selection.start.coerceIn(0, text.length)
                        updateDocument(id) { current ->
                            current.copy(
                                text = current.text.copy(text = text, selection = TextRange(caret)),
                                path = target,
                                savedText = text,
                            )
                        }
                        handles[id]?.let { handle -> runCatching { handle.setText(text) } }
                        derive(id, immediate = true)
                    } else {
                        updateDocument(id) { it.copy(path = target, savedText = text) }
                    }
                    update { it.copy(notification = "Saved ${target.fileName}") }
                }
                .onFailure { failure ->
                    update { it.copy(notification = "Could not save ${target.fileName}: ${failure.message}") }
                }
        }
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
        scope.launch {
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
            it.copy(projectSearch = it.projectSearch.copy(visible = true, scope = scope))
        }
        runProjectSearch()
    }

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
        update {
            it.copy(
                projectSearch = it.projectSearch.copy(
                    query = query,
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

        projectSearchJob = scope.launch {
            delay(PROJECT_SEARCH_DEBOUNCE_MILLIS)
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
            if (current.query != request.query || current.scope != request.scope) return@launch
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

    /** Moves the caret to a document offset, used by the outline. */
    public fun moveCaret(id: Long, offset: Int) {
        updateDocument(id) { session ->
            session.copy(text = session.text.copy(selection = TextRange(offset.coerceIn(0, session.text.text.length))))
        }
    }

    /** Selects a finding's range, so the problem is visible and not merely scrolled to. */
    public fun goToFinding(id: Long, finding: Finding) {
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

        scope.launch {
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
