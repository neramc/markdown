package dev.starfect.quill.model

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.input.TextFieldValue
import dev.starfect.quill.bridge.wire.DocumentStats
import dev.starfect.quill.bridge.wire.MarkdownBlockIr
import dev.starfect.quill.bridge.wire.OutlineEntry
import dev.starfect.quill.bridge.wire.SearchMatch
import dev.starfect.quill.bridge.wire.StyleSpan
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

/** A tool window in the left or right dock. */
public enum class ToolWindow { PROJECT, STRUCTURE }

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
    val blocks: List<MarkdownBlockIr> = emptyList(),
    val outline: List<OutlineEntry> = emptyList(),
    val stats: DocumentStats = DocumentStats.EMPTY,
    val spans: List<StyleSpan> = emptyList(),
    val matches: List<SearchMatch> = emptyList(),
    val currentMatch: Int = -1,
    val loadError: String? = null,
) {
    /** File name, or a placeholder for a document that has never been saved. */
    val displayName: String
        get() = path?.fileName?.toString() ?: "Untitled"

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
    val viewMode: ViewMode = ViewMode.SPLIT,
    val showLineNumbers: Boolean = true,
    val editorFontSize: Int = 14,
    val wordWrap: Boolean = true,
)

/** The whole application state. */
@Immutable
public data class WorkspaceState(
    val settings: QuillSettings = QuillSettings(),
    val projectRoot: Path? = null,
    val projectTree: List<FileNode> = emptyList(),
    val documents: List<DocumentSession> = emptyList(),
    val activeDocumentId: Long? = null,
    val find: FindState = FindState(),
    val commandPaletteVisible: Boolean = false,
    val leftToolWindow: ToolWindow? = ToolWindow.PROJECT,
    val rightToolWindow: ToolWindow? = ToolWindow.STRUCTURE,
    val notification: String? = null,
) {
    val activeDocument: DocumentSession?
        get() = documents.firstOrNull { it.id == activeDocumentId }
}
