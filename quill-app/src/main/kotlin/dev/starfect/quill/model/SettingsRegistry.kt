package dev.starfect.quill.model

/**
 * Every setting Quill has, declared once.
 *
 * This list is the single source of truth. The properties file, the settings dialog, the search over
 * settings and the VS Code import all read it; none of them carries its own copy of what a setting
 * is called or what it may hold. Adding a setting means adding one entry here and one field to
 * [QuillSettings], and the rest follows.
 *
 * Keys are dotted and deliberately close to VS Code's own — `editor.fontSize`, `files.autoSave` —
 * because a settings file somebody has to read at three in the morning should look familiar, and
 * because it makes the correspondence in [Setting.vsCode] obvious rather than a lookup table.
 */
public object SettingsRegistry {

    // ------------------------------------------------------------------ appearance

    public val DarkTheme: BooleanSetting = BooleanSetting(
        key = "workbench.darkTheme",
        title = "Dark theme",
        description = "Use the dark colour scheme for the interface, the editor and exported HTML.",
        category = SettingCategory.APPEARANCE,
        default = true,
        read = { it.darkTheme },
        write = { settings, value -> settings.copy(darkTheme = value) },
    )

    public val Islands: BooleanSetting = BooleanSetting(
        key = "workbench.islands",
        title = "Rounded panels",
        description = "Separate the panels onto rounded surfaces over a recessed window background.",
        category = SettingCategory.APPEARANCE,
        default = false,
        read = { it.islands },
        write = { settings, value -> settings.copy(islands = value) },
    )

    public val UiFontSize: IntSetting = IntSetting(
        key = "workbench.fontSize",
        title = "Interface font size",
        description = "The size the whole interface is scaled from. Headers and labels move with it.",
        category = SettingCategory.APPEARANCE,
        default = 13,
        range = 8..32,
        read = { it.uiFontSize },
        write = { settings, value -> settings.copy(uiFontSize = value) },
    )

    // ------------------------------------------------------------------ editor

    public val EditorFontSize: IntSetting = IntSetting(
        key = "editor.fontSize",
        title = "Editor font size",
        description = "The size of the text in the source editor.",
        category = SettingCategory.EDITOR,
        default = 14,
        range = 8..48,
        read = { it.editorFontSize },
        write = { settings, value -> settings.copy(editorFontSize = value) },
        vsCode = "editor.fontSize",
    )

    public val ShowLineNumbers: BooleanSetting = BooleanSetting(
        key = "editor.lineNumbers",
        title = "Line numbers",
        description = "Show line numbers down the left edge of the editor.",
        category = SettingCategory.EDITOR,
        default = true,
        read = { it.showLineNumbers },
        write = { settings, value -> settings.copy(showLineNumbers = value) },
        vsCode = "editor.lineNumbers",
        // VS Code spells this one as a word rather than a boolean.
        trueWords = setOf("on", "relative", "interval"),
        falseWords = setOf("off"),
    )

    public val WordWrap: BooleanSetting = BooleanSetting(
        key = "editor.wordWrap",
        title = "Word wrap",
        description = "Wrap long lines to the width of the editor instead of scrolling sideways.",
        category = SettingCategory.EDITOR,
        default = true,
        read = { it.wordWrap },
        write = { settings, value -> settings.copy(wordWrap = value) },
        vsCode = "editor.wordWrap",
        trueWords = setOf("on", "wordwrapcolumn", "bounded"),
        falseWords = setOf("off"),
    )

    public val HighlightCaretRow: BooleanSetting = BooleanSetting(
        key = "editor.highlightCurrentLine",
        title = "Highlight the current line",
        description = "Tint the line the caret is on.",
        category = SettingCategory.EDITOR,
        default = true,
        read = { it.highlightCaretRow },
        write = { settings, value -> settings.copy(highlightCaretRow = value) },
        vsCode = "editor.renderLineHighlight",
        trueWords = setOf("all", "line", "gutter"),
        falseWords = setOf("none"),
    )

    public val VisualGuideColumn: IntSetting = IntSetting(
        key = "editor.rulerColumn",
        title = "Ruler column",
        description = "Draw a vertical guide at this column. Zero hides it.",
        category = SettingCategory.EDITOR,
        default = 0,
        range = 0..300,
        read = { it.visualGuideColumn },
        write = { settings, value -> settings.copy(visualGuideColumn = value) },
        vsCode = "editor.rulers",
    )

    public val SyncScrolling: BooleanSetting = BooleanSetting(
        key = "editor.syncScrolling",
        title = "Synchronise scrolling",
        description = "Keep the preview aligned with the caret as you move through the source.",
        category = SettingCategory.PREVIEW,
        default = true,
        read = { it.syncScrolling },
        write = { settings, value -> settings.copy(syncScrolling = value) },
        vsCode = "markdown.preview.scrollPreviewWithEditor",
    )

    // ------------------------------------------------------------------ typing

    public val TabWidth: IntSetting = IntSetting(
        key = "editor.tabSize",
        title = "Tab width",
        description = "How many spaces a tab stands for.",
        category = SettingCategory.TYPING,
        default = 4,
        range = 1..16,
        read = { it.tabWidth },
        write = { settings, value -> settings.copy(tabWidth = value) },
        vsCode = "editor.tabSize",
    )

    public val VimMode: BooleanSetting = BooleanSetting(
        key = "editor.vimMode",
        title = "Vim mode",
        description = "Modal editing: motions, operators, counts, registers and visual modes.",
        category = SettingCategory.TYPING,
        default = false,
        read = { it.vimMode },
        write = { settings, value -> settings.copy(vimMode = value) },
    )

    // ------------------------------------------------------------------ files

    public val SaveOnFocusLoss: BooleanSetting = BooleanSetting(
        key = "files.saveOnFocusChange",
        title = "Save when the window loses focus",
        description = "Write every modified document that has a file when you switch away.",
        category = SettingCategory.FILES,
        default = false,
        read = { it.saveOnFocusLoss },
        write = { settings, value -> settings.copy(saveOnFocusLoss = value) },
        vsCode = "files.autoSave",
        trueWords = setOf("onfocuschange", "onwindowchange"),
        falseWords = setOf("off", "afterdelay"),
    )

    public val TrimTrailingWhitespaceOnSave: BooleanSetting = BooleanSetting(
        key = "files.trimTrailingWhitespace",
        title = "Trim trailing whitespace on save",
        description = "Strip spaces and tabs from the end of every line when saving.",
        category = SettingCategory.FILES,
        default = false,
        read = { it.trimTrailingWhitespaceOnSave },
        write = { settings, value -> settings.copy(trimTrailingWhitespaceOnSave = value) },
        vsCode = "files.trimTrailingWhitespace",
    )

    public val EnsureNewlineOnSave: BooleanSetting = BooleanSetting(
        key = "files.insertFinalNewline",
        title = "End the file with a newline",
        description = "Ensure a saved file finishes with exactly one line ending.",
        category = SettingCategory.FILES,
        default = true,
        read = { it.ensureNewlineOnSave },
        write = { settings, value -> settings.copy(ensureNewlineOnSave = value) },
        vsCode = "files.insertFinalNewline",
    )

    // ------------------------------------------------------------------ markdown

    public val AutoTableOfContents: BooleanSetting = BooleanSetting(
        key = "markdown.autoTableOfContents",
        title = "Keep the table of contents current",
        description =
            "Rewrite the region between the toc markers as headings change. Documents without " +
                "the markers are never touched.",
        category = SettingCategory.MARKDOWN,
        default = true,
        read = { it.autoTableOfContents },
        write = { settings, value -> settings.copy(autoTableOfContents = value) },
    )

    public val InspectionsEnabled: BooleanSetting = BooleanSetting(
        key = "markdown.inspections",
        title = "Run inspections",
        description = "Check the document as you type and report what looks wrong.",
        category = SettingCategory.MARKDOWN,
        default = true,
        read = { it.inspectionsEnabled },
        write = { settings, value -> settings.copy(inspectionsEnabled = value) },
    )

    public val ShowWeakWarnings: BooleanSetting = BooleanSetting(
        key = "markdown.weakWarnings",
        title = "Show weak warnings",
        description = "Include the whitespace and trailing-space inspections, which are noisy on imported text.",
        category = SettingCategory.MARKDOWN,
        default = true,
        read = { it.showWeakWarnings },
        write = { settings, value -> settings.copy(showWeakWarnings = value) },
    )

    // ------------------------------------------------------------------ preview and modes

    public val PreviewViewMode: ChoiceSetting<ViewMode> = ChoiceSetting(
        key = "preview.viewMode",
        title = "View mode",
        description = "Whether the window shows the source, the preview, or both.",
        category = SettingCategory.PREVIEW,
        default = ViewMode.SPLIT,
        choices = ViewMode.entries.toList(),
        read = { it.viewMode },
        write = { settings, value -> settings.copy(viewMode = value) },
        labels = mapOf(
            ViewMode.EDITOR to "Source only",
            ViewMode.SPLIT to "Source and preview",
            ViewMode.PREVIEW to "Preview only",
        ),
    )

    public val FocusMode: BooleanSetting = BooleanSetting(
        key = "workbench.focusMode",
        title = "Focus mode",
        description = "One centred column, every paragraph but the current one dimmed, nothing else on screen.",
        category = SettingCategory.APPEARANCE,
        default = false,
        read = { it.focusMode },
        write = { settings, value -> settings.copy(focusMode = value) },
    )

    // ------------------------------------------------------------------ layout

    public val LeftToolWindowWidth: FloatSetting = FloatSetting(
        key = "workbench.leftPanelWidth",
        title = "Left panel width",
        description = "How wide the left dock is, in points.",
        category = SettingCategory.ADVANCED,
        default = 260f,
        range = 150f..640f,
        read = { it.leftToolWindowWidth },
        write = { settings, value -> settings.copy(leftToolWindowWidth = value) },
    )

    public val RightToolWindowWidth: FloatSetting = FloatSetting(
        key = "workbench.rightPanelWidth",
        title = "Right panel width",
        description = "How wide the right dock is, in points.",
        category = SettingCategory.ADVANCED,
        default = 280f,
        range = 150f..640f,
        read = { it.rightToolWindowWidth },
        write = { settings, value -> settings.copy(rightToolWindowWidth = value) },
    )

    /**
     * Every setting, in the order the dialog shows them.
     *
     * Declared explicitly rather than gathered by reflection: reflection over Kotlin objects needs
     * `kotlin-reflect` and the ProGuard keep rules to survive shrinking, and would turn a missing
     * entry from a compile error into a setting that silently stops being saved.
     */
    public val ALL: List<Setting<*>> = listOf(
        DarkTheme,
        Islands,
        UiFontSize,
        FocusMode,
        EditorFontSize,
        ShowLineNumbers,
        WordWrap,
        HighlightCaretRow,
        VisualGuideColumn,
        TabWidth,
        VimMode,
        SaveOnFocusLoss,
        TrimTrailingWhitespaceOnSave,
        EnsureNewlineOnSave,
        AutoTableOfContents,
        InspectionsEnabled,
        ShowWeakWarnings,
        PreviewViewMode,
        SyncScrolling,
        LeftToolWindowWidth,
        RightToolWindowWidth,
    )

    /** The settings in [category], in declaration order. */
    public fun inCategory(category: SettingCategory): List<Setting<*>> =
        ALL.filter { it.category == category }

    /** The settings matching a search, in declaration order. */
    public fun search(query: String): List<Setting<*>> = ALL.filter { it.matches(query) }

    /** The setting with this key, or null. */
    public fun byKey(key: String): Setting<*>? = ALL.firstOrNull { it.key == key }
}
