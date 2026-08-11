package dev.starfect.quill

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.starfect.quill.bridge.ExportOptions
import dev.starfect.quill.bridge.QuillDocument
import dev.starfect.quill.bridge.QuillEngine
import dev.starfect.quill.bridge.QuillEngineException
import dev.starfect.quill.bridge.SearchFlags
import dev.starfect.quill.bridge.wire.ColorSpan
import dev.starfect.quill.bridge.wire.DocumentStats
import dev.starfect.quill.bridge.wire.MarkdownBlockIr
import dev.starfect.quill.bridge.wire.OutlineEntry
import dev.starfect.quill.bridge.wire.StyleSpan
import dev.starfect.quill.io.FileService
import dev.starfect.quill.model.DocumentSession
import dev.starfect.quill.model.FileNode
import dev.starfect.quill.model.FindState
import dev.starfect.quill.model.QuillSettings
import dev.starfect.quill.model.ToolWindow
import dev.starfect.quill.model.ViewMode
import dev.starfect.quill.model.WorkspaceState
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        /**
         * Debounce before re-deriving. Long enough that a burst of keystrokes coalesces into one
         * parse, short enough that the preview still feels live.
         */
        const val DERIVE_DEBOUNCE_MILLIS = 120L

        /** How many lines to highlight; beyond this the viewport would need tracking anyway. */
        const val HIGHLIGHT_LINE_BUDGET = 5_000
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
                    handles[id] = engine.openDocument(text)
                    update { workspace ->
                        workspace.copy(
                            documents = workspace.documents + DocumentSession(
                                id = id,
                                path = path,
                                text = TextFieldValue(text),
                                savedText = text,
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

    /** Closes a document, releasing its engine handle. */
    public fun closeDocument(id: Long) {
        derivationJobs.remove(id)?.cancel()
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

    // ---------------------------------------------------------------- derivation

    private data class Derived(
        val version: Long,
        val blocks: List<MarkdownBlockIr>,
        val outline: List<OutlineEntry>,
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

            withContext(Dispatchers.Default) {
                runCatching {
                    val version = handle.version
                    val lineCount = handle.text().count { it == '\n' } + 1
                    Derived(
                        version = version,
                        blocks = handle.blocks(),
                        outline = handle.outline(),
                        stats = handle.stats(),
                        spans = handle.spans(0, lineCount.coerceAtMost(HIGHLIGHT_LINE_BUDGET)),
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
                                outline = result.outline,
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

    // ---------------------------------------------------------------- files

    /** Saves a document. Documents that have never been saved need [onNeedsPath] to supply one. */
    public fun save(id: Long, onNeedsPath: () -> Path?) {
        val session = _state.value.documents.firstOrNull { it.id == id } ?: return
        val target = session.path ?: onNeedsPath() ?: run {
            update { it.copy(notification = "This document has no file yet; use File > Export or save it elsewhere") }
            return
        }

        scope.launch {
            val text = session.text.text
            withContext(Dispatchers.IO) { runCatching { fileService.write(target, text) } }
                .onSuccess {
                    updateDocument(id) { it.copy(path = target, savedText = text) }
                    update { it.copy(notification = "Saved ${target.fileName}") }
                }
                .onFailure { failure ->
                    update { it.copy(notification = "Could not save ${target.fileName}: ${failure.message}") }
                }
        }
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

    public fun setCommandPaletteVisible(visible: Boolean) {
        update { it.copy(commandPaletteVisible = visible) }
    }

    public fun dismissNotification() {
        update { it.copy(notification = null) }
    }

    /** Highlights a fenced code block. Called from the preview's code renderer. */
    public fun highlightCode(code: String, language: String): List<ColorSpan> =
        runCatching { engine.highlightCode(code, language) }.getOrDefault(emptyList())

    override fun close() {
        derivationJobs.values.forEach(Job::cancel)
        derivationJobs.clear()
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
