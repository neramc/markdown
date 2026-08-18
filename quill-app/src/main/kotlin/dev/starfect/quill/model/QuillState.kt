package dev.starfect.quill.model

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.input.TextFieldValue
import dev.starfect.quill.bridge.MarkdownFlavour
import dev.starfect.quill.bridge.wire.DocumentStats
import dev.starfect.quill.bridge.wire.Finding
import dev.starfect.quill.bridge.wire.HtmlNode
import dev.starfect.quill.bridge.wire.InspectionSummary
import dev.starfect.quill.bridge.wire.MarkdownBlockIr
import dev.starfect.quill.bridge.wire.OutlineEntry
import dev.starfect.quill.bridge.wire.SearchMatch
import dev.starfect.quill.bridge.wire.StyleSpan
import dev.starfect.quill.editing.Vim
import dev.starfect.quill.io.FileStamp
import dev.starfect.quill.search.ProjectSearch
import java.nio.file.Path

/** How the editor and preview share the main area. */
public enum class ViewMode {
    /** Source only. */
    EDITOR,

    /** Source and preview side by side. */
    SPLIT,

    /** Preview only. */
    PREVIEW,
}

/**
 * A tool window in one of the three docks.
 *
 * Each is pinned to the edge the IDE puts it on: navigation on the left, the document's own outline
 * on the right, and everything transient along the bottom.
 */
public enum class ToolWindow(public val dock: Dock, public val label: String) {
    PROJECT(Dock.LEFT, "Project"),
    STRUCTURE(Dock.RIGHT, "Structure"),
    PROBLEMS(Dock.BOTTOM, "Problems"),
    NOTIFICATIONS(Dock.RIGHT, "Notifications"),
    DATABASE(Dock.RIGHT, "Database"),
    TERMINAL(Dock.BOTTOM, "Terminal"),
    ;

    public companion object {
        /** The tool windows on [dock], in stripe order. */
        public fun on(dock: Dock): List<ToolWindow> = entries.filter { it.dock == dock }
    }
}

/** Which edge a tool window docks to. */
public enum class Dock { LEFT, RIGHT, BOTTOM }

/** Zero-based caret line, column and absolute offset, all in UTF-16 units. */
@Immutable
public data class CaretPosition(val line: Int, val column: Int, val offset: Int)

/**
 * Everything the UI knows about one open document.
 *
 * The class is immutable and replaced wholesale on every change, which is what lets Compose skip
 * recomposition of panes whose slice of state did not move.
 *
 * [text] is the editor's own [TextFieldValue]; the engine holds the authoritative rope and is fed
 * edit deltas. [blocks], [outline], [stats] and [spans] are all *derived* and carry the
 * [derivedVersion] they were computed from, so a stale async result can be recognised and dropped
 * rather than overwriting a newer one.
 */
@Immutable
public data class DocumentSession(
    val id: Long,
    val path: Path?,
    val text: TextFieldValue = TextFieldValue(""),
    val savedText: String = "",
    val engineVersion: Long = 0,
    val derivedVersion: Long = -1,
    val flavour: MarkdownFlavour = MarkdownFlavour.GFM,
    val blocks: List<MarkdownBlockIr> = emptyList(),
    val html: List<HtmlNode> = emptyList(),
    val outline: List<OutlineEntry> = emptyList(),
    val findings: List<Finding> = emptyList(),
    val stats: DocumentStats = DocumentStats.EMPTY,
    val spans: List<StyleSpan> = emptyList(),
    val matches: List<SearchMatch> = emptyList(),
    val currentMatch: Int = -1,
    val loadError: String? = null,
    /**
     * What was on disk when Quill last read or wrote this file.
     *
     * Null for a document that has never been on disk. Compared before every save so that a file
     * somebody else changed is not overwritten in silence.
     */
    val diskStamp: FileStamp? = null,
    /**
     * Set when the file changed underneath a buffer that has unsaved edits.
     *
     * The two changes cannot be merged and Quill will not guess which to keep, so saving is refused
     * until the writer says which one wins.
     */
    val conflictsWithDisk: Boolean = false,
) {
    /** File name, or a placeholder for a document that has never been saved. */
    val displayName: String
        get() = path?.fileName?.toString() ?: "Untitled"

    /** Finding counts by severity, which is what the editor's inspection widget shows. */
    val inspectionSummary: InspectionSummary
        get() = InspectionSummary.of(findings)

    /** Whether the buffer differs from what is on disk. */
    val isModified: Boolean
        get() = text.text != savedText

    /** Zero-based caret line and column, both in UTF-16 units. */
    val caretPosition: CaretPosition
        get() {
            val offset = text.selection.start.coerceIn(0, text.text.length)
            val line = text.text.take(offset).count { it == '\n' }
            val lineStart = if (offset == 0) {
                0
            } else {
                text.text.lastIndexOf('\n', offset - 1).let { if (it < 0) 0 else it + 1 }
            }
            return CaretPosition(line, offset - lineStart, offset)
        }
}

/**
 * The project-wide search dialog's state.
 *
 * Results live here rather than being recomputed by the dialog, because a search walks the disk:
 * it is a background job with a lifetime, and the dialog is a view of whatever that job last
 * produced.
 */
@Immutable
public data class ProjectSearchState(
    val visible: Boolean = false,
    val scope: ProjectSearch.Scope = ProjectSearch.Scope.FILE_NAMES,
    val query: String = "",
    val caseSensitive: Boolean = false,
    val results: ProjectSearch.Results = ProjectSearch.Results.EMPTY,
    val running: Boolean = false,
)

/** Find/replace panel state. */
@Immutable
public data class FindState(
    val visible: Boolean = false,
    val query: String = "",
    val replacement: String = "",
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val regex: Boolean = false,
    val replaceVisible: Boolean = false,
    val error: String? = null,
)

/** One node in the project tool window. */
@Immutable
public data class FileNode(
    val path: Path,
    val name: String,
    val isDirectory: Boolean,
    val depth: Int,
    val isExpanded: Boolean = false,
    val children: List<FileNode> = emptyList(),
)

/** User preferences. */
@Immutable
public data class QuillSettings(
    val darkTheme: Boolean = true,
    /**
     * The Islands surface style: rounded, separated panels on a recessed window background.
     *
     * A second axis rather than a value of [darkTheme], because the two answer different questions.
     * Light-versus-dark reaches the *engine* — it picks the palette for fenced-code highlighting and
     * for exported HTML — while this reaches only the shell's geometry. Folding them into one enum
     * would send a surface-style change across the FFI boundary for no reason.
     */
    val islands: Boolean = false,
    val viewMode: ViewMode = ViewMode.SPLIT,
    /**
     * Focus Mode: the window with everything but the words taken out of it.
     *
     * Not a view mode, because it is orthogonal to one — you can be in focus mode reading or
     * writing. It hides the panels, the tabs, the toolbar and the status bar, centres the text at a
     * readable measure, and dims every paragraph but the one the caret is in.
     */
    val focusMode: Boolean = false,
    /** Vim keys in the source editor. */
    val vimMode: Boolean = false,
    /**
     * Keep a document's `<!-- toc -->` region up to date as its headings change.
     *
     * Opt-in twice over: the setting has to be on *and* the document has to carry the markers, so
     * turning it on never makes a contents list appear in a file that did not ask for one.
     */
    val autoTableOfContents: Boolean = true,
    val showLineNumbers: Boolean = true,
    val editorFontSize: Int = 14,
    /**
     * The UI font size, which the whole type scale is derived from.
     *
     * The platform's default is 13 and its scale is expressed relative to it — a header is "default
     * +3", help text is "default −1" — so raising this moves every size in the shell together rather
     * than leaving the headers behind.
     */
    val uiFontSize: Int = 13,
    val wordWrap: Boolean = true,
    /** Highlight the line the caret is on. */
    val highlightCaretRow: Boolean = true,
    /** Show the whitespace and trailing-space inspections, which are noisy on imported documents. */
    val showWeakWarnings: Boolean = true,
    /** Run inspections at all. */
    val inspectionsEnabled: Boolean = true,
    /** Preview scrolling follows the caret. */
    val syncScrolling: Boolean = true,
    /** Save a modified document when the window loses focus. */
    val saveOnFocusLoss: Boolean = false,
    /** Strip trailing whitespace when saving. */
    val trimTrailingWhitespaceOnSave: Boolean = false,
    /** Ensure the file ends with exactly one newline when saving. */
    val ensureNewlineOnSave: Boolean = true,
    /** Soft-wrap column guide, or 0 to hide it. */
    val visualGuideColumn: Int = 0,
    /** Tab width in spaces. */
    val tabWidth: Int = 4,
    /** Keep the enclosing headings pinned above the text as it scrolls. */
    val stickyHeadings: Boolean = true,
    /** Show the whole document in miniature down the right edge. */
    val minimap: Boolean = false,
    /** Typing an opening bracket, backtick or quote inserts its partner. */
    val autoClosingBrackets: Boolean = true,
    /** Typing a pair character with a selection wraps it rather than replacing it. */
    val autoSurround: Boolean = true,
    /** Save a modified document a moment after typing stops. */
    val autoSaveAfterDelay: Boolean = false,
    /** How long "a moment" is, in milliseconds. */
    val autoSaveDelayMillis: Int = 1000,
    /**
     * Docked tool window widths, in dp, as the user has dragged them.
     *
     * A preference rather than window state: a panel width the user chose is exactly the kind of
     * thing that has to survive a restart, and having dragged it once is a stronger statement of
     * intent than most of the checkboxes above.
     */
    val leftToolWindowWidth: Float = 260f,
    val rightToolWindowWidth: Float = 280f,
)

/**
 * One entry in the Run/Debug configurations dialog.
 *
 * Quill's "run" is a document task — exporting, linting, opening the rendered file — so a
 * configuration names one of those and the arguments it takes. The shape mirrors the IDE's dialog
 * because that is where the user expects to find it, not because Quill runs processes.
 */
@Immutable
public data class RunConfiguration(
    val id: Long,
    val name: String,
    val task: RunTask,
    /** Document to run against, or null for whichever is focused. */
    val targetPath: Path? = null,
    val outputPath: Path? = null,
    val standalone: Boolean = true,
    val darkTheme: Boolean = true,
    val allowRawHtml: Boolean = false,
    val openAfterRun: Boolean = false,
    val storeAsProjectFile: Boolean = false,
)

/** What a [RunConfiguration] does. */
public enum class RunTask(public val label: String, public val description: String) {
    EXPORT_HTML("Export HTML", "Render the document to a standalone HTML file"),
    INSPECT("Inspect", "Run every inspection and report the findings"),
    WORD_COUNT("Word count", "Report the document's statistics"),
}

/** The modal dialog on screen, if any. */
public enum class Dialog { SETTINGS, RUN_CONFIGURATIONS, ABOUT, GO_TO_LINE, UPDATE, UNINSTALL }

/** The whole application state. */
@Immutable
public data class WorkspaceState(
    val settings: QuillSettings = QuillSettings(),
    val projectRoot: Path? = null,
    val projectTree: List<FileNode> = emptyList(),
    val documents: List<DocumentSession> = emptyList(),
    val activeDocumentId: Long? = null,
    val find: FindState = FindState(),
    val projectSearch: ProjectSearchState = ProjectSearchState(),
    /** Vim's own state: the mode, the pending keys, the register, the undo history. */
    val vim: Vim.State = Vim.State(),
    val commandPaletteVisible: Boolean = false,
    /** The Ctrl/Cmd+K list of everything Markdown can do. */
    val featurePaletteVisible: Boolean = false,
    val leftToolWindow: ToolWindow? = ToolWindow.PROJECT,
    val rightToolWindow: ToolWindow? = ToolWindow.STRUCTURE,
    val bottomToolWindow: ToolWindow? = null,
    val dialog: Dialog? = null,
    val runConfigurations: List<RunConfiguration> = emptyList(),
    val selectedRunConfigurationId: Long? = null,
    val notifications: List<Notification> = emptyList(),
    val notification: String? = null,
    /**
     * What Quill is doing in the background, newest last.
     *
     * Held in the workspace rather than in the status bar because more than the status bar wants
     * it: an export that is still running is a reason to warn before closing, and a project scan in
     * flight is why the tree looks empty.
     */
    val tasks: List<BackgroundTask> = emptyList(),
    /** A question Quill is waiting on before doing something it cannot undo. */
    val confirm: Confirm? = null,
    /** Where the reader has been, which is what the toolbar's back and forward arrows walk. */
    val navigation: NavigationHistory = NavigationHistory(),
) {
    val activeDocument: DocumentSession?
        get() = documents.firstOrNull { it.id == activeDocumentId }

    /** The tool window shown on [dock], or null when that dock is collapsed. */
    public fun toolWindow(dock: Dock): ToolWindow? = when (dock) {
        Dock.LEFT -> leftToolWindow
        Dock.RIGHT -> rightToolWindow
        Dock.BOTTOM -> bottomToolWindow
    }

    /** The configuration the run button uses, which is the selected one or the first defined. */
    val activeRunConfiguration: RunConfiguration?
        get() = runConfigurations.firstOrNull { it.id == selectedRunConfigurationId }
            ?: runConfigurations.firstOrNull()
}

/**
 * Something the user has asked for that Quill will not do until they have said so twice.
 *
 * Described as data rather than as a callback so the workspace stays comparable and immutable — a
 * lambda in the state makes every copy unequal to the last and every pane recompose. The controller
 * reads the description back and performs it, which also means the decision and the action are
 * testable apart from the dialog.
 *
 * The bar for being here is *losing something that cannot be recovered*. Closing a modified
 * document qualifies; closing an unmodified one does not, and asking about it would train people to
 * dismiss the question without reading it — which is how a confirmation stops working.
 */
@Immutable
public sealed interface Confirm {

    /** Closing documents with unsaved edits. */
    @Immutable
    public data class CloseDocuments(
        /** Every document being closed, including the saved ones. */
        public val ids: List<Long>,
        /** The names with unsaved edits, which is what the question is about. */
        public val unsavedNames: List<String>,
    ) : Confirm

    /**
     * Throwing away a buffer's edits to read the file again.
     *
     * Reload is the only action in the editor that destroys work and produces nothing — a close at
     * least offers to save it, and an overwrite at least keeps what you wrote. It earns a question
     * on its own.
     */
    @Immutable
    public data class ReloadDocument(
        public val id: Long,
        public val name: String,
    ) : Confirm

    /**
     * Leaving Quill with unsaved edits.
     *
     * Distinct from [CloseDocuments] even though the machinery is the same, because the answer
     * means something different: Discard here throws away every buffer at once and there is no
     * window left to notice it in. The wording differs for the same reason.
     */
    @Immutable
    public data class Exit(
        /** Every document with unsaved edits, which is everything Save has to write. */
        public val ids: List<Long>,
        /** Their names. */
        public val unsavedNames: List<String>,
    ) : Confirm
}

/** What the user chose. */
public enum class ConfirmChoice { SAVE, DISCARD, CANCEL }

/** One entry in the Notifications tool window. */
@Immutable
public data class Notification(
    val id: Long,
    val title: String,
    val body: String,
    val severity: NotificationSeverity = NotificationSeverity.INFO,
    val timestamp: Long = System.currentTimeMillis(),
)

/** How prominently a [Notification] is drawn. */
public enum class NotificationSeverity { INFO, SUCCESS, WARNING, ERROR }
