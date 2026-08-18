package dev.starfect.quill.model

import androidx.compose.runtime.Immutable

/** One binding, as the reference sheet lists it. */
@Immutable
public data class KeyBinding(
    public val action: String,
    public val keys: String,
)

/** A named block of bindings. */
@Immutable
public data class KeymapSection(
    public val title: String,
    public val bindings: List<KeyBinding>,
)

/**
 * What Quill's keyboard does, written down.
 *
 * This is a *reference*, not the keymap itself — `Shortcuts.kt` is the keymap, and it is code.
 * Keeping the two apart is a deliberate trade: a table that generated itself from the handler would
 * never drift, but the handler is a `when` over key events with modifiers spread across three
 * nested blocks, and the shapes it can express do not reduce to rows without inventing a small
 * language for them. `KeymapTest` compares this list against the handler's own behaviour instead,
 * by pressing every key in it, which catches drift without contorting either side.
 *
 * The sections follow what someone is doing rather than which modifier is held: a writer looking
 * for "how do I make this bold" does not know it is a Ctrl binding.
 */
public object Keymap {

    public val sections: List<KeymapSection> = listOf(
        KeymapSection(
            "Files",
            listOf(
                KeyBinding("New document", "Ctrl+N"),
                KeyBinding("Save", "Ctrl+S"),
                KeyBinding("Save as", "Ctrl+Shift+S"),
                KeyBinding("Close document", "Ctrl+W"),
            ),
        ),
        KeymapSection(
            "Writing",
            listOf(
                KeyBinding("Bold", "Ctrl+B"),
                KeyBinding("Italic", "Ctrl+I"),
                KeyBinding("Inline code", "Ctrl+Shift+C"),
                KeyBinding("Insert link", "Ctrl+Shift+K"),
                KeyBinding("Bullet list", "Ctrl+Shift+L"),
                KeyBinding("Task list", "Ctrl+Shift+T"),
                KeyBinding("Block quote", "Ctrl+Shift+Q"),
                KeyBinding("Heading level up", "Ctrl+Shift+."),
                KeyBinding("Heading level down", "Ctrl+Shift+,"),
                KeyBinding("Format table", "Ctrl+Alt+L"),
            ),
        ),
        KeymapSection(
            "Editing",
            listOf(
                KeyBinding("Undo", "Ctrl+Z"),
                KeyBinding("Redo", "Ctrl+Shift+Z  or  Ctrl+Y"),
                KeyBinding("Duplicate line", "Ctrl+D"),
                KeyBinding("Move line up", "Alt+Shift+Up"),
                KeyBinding("Move line down", "Alt+Shift+Down"),
                KeyBinding("Paste as Markdown", "Ctrl+V"),
            ),
        ),
        KeymapSection(
            "Finding things",
            listOf(
                KeyBinding("Find in document", "Ctrl+F"),
                KeyBinding("Replace", "Ctrl+R"),
                KeyBinding("Next match", "F3"),
                KeyBinding("Previous match", "Shift+F3"),
                KeyBinding("Find in project", "Ctrl+Shift+F"),
                KeyBinding("Find a file", "Ctrl+Shift+N"),
                KeyBinding("Unfinished work", "Ctrl+Shift+O"),
                KeyBinding("Recent files", "Ctrl+E"),
                KeyBinding("Search everywhere", "Ctrl+Shift+P"),
                KeyBinding("Markdown feature search", "Ctrl+K"),
                KeyBinding("Next problem", "F2"),
                KeyBinding("Previous problem", "Shift+F2"),
            ),
        ),
        KeymapSection(
            "Moving around",
            listOf(
                KeyBinding("Back", "Ctrl+Alt+Left"),
                KeyBinding("Forward", "Ctrl+Alt+Right"),
                KeyBinding("Go to line", "Ctrl+G"),
            ),
        ),
        KeymapSection(
            "The window",
            listOf(
                KeyBinding("Editor only", "Ctrl+1"),
                KeyBinding("Editor and preview", "Ctrl+2"),
                KeyBinding("Preview only", "Ctrl+3"),
                KeyBinding("Focus mode", "Ctrl+Shift+D"),
                KeyBinding("Reading mode", "Ctrl+Shift+M"),
                KeyBinding("Problems", "Ctrl+Shift+6"),
                KeyBinding("Run", "Shift+F10"),
                KeyBinding("Settings", "Ctrl+Alt+S"),
            ),
        ),
    )

    /** Every binding, flattened. */
    public val all: List<KeyBinding> get() = sections.flatMap { it.bindings }
}
