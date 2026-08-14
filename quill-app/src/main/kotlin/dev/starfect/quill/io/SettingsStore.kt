package dev.starfect.quill.io

import dev.starfect.quill.model.QuillSettings
import dev.starfect.quill.model.SettingsRegistry
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Reads and writes the user's preferences.
 *
 * A tool that forgets its theme every launch is a tool nobody trusts with anything larger. The
 * settings dialog was already complete and applied correctly; what was missing was that none of it
 * outlived the process.
 *
 * Deliberately a flat `.properties` file next to the recent-projects list, in the same
 * per-platform config directory. Two reasons: it is the format a user can open and fix by hand when
 * something goes wrong, and it needs no serialization framework — which for this application would
 * mean a reflective library and the ProGuard keep rules to go with it, for eighteen scalars.
 *
 * Every read is defensive. A settings file written by a newer version, hand-edited into nonsense, or
 * truncated by a full disk must not stop the application starting: an unreadable value falls back to
 * its default, one key at a time.
 */
public class SettingsStore(private val storePath: Path = defaultStorePath()) {

    public companion object {
        /**
         * What each setting used to be called, so an existing installation keeps its preferences.
         *
         * The keys became dotted when the registry arrived — `darkTheme` is now
         * `workbench.darkTheme` — and a rename that silently resets everybody's settings is a worse
         * outcome than the flat names were. Read-only: nothing is ever written under the old name,
         * so the file converts itself the first time it is saved.
         */
        private val LEGACY_KEYS: Map<String, String> = mapOf(
            "workbench.darkTheme" to "darkTheme",
            "workbench.islands" to "islands",
            "workbench.fontSize" to "uiFontSize",
            "workbench.focusMode" to "focusMode",
            "workbench.leftPanelWidth" to "leftToolWindowWidth",
            "workbench.rightPanelWidth" to "rightToolWindowWidth",
            "editor.fontSize" to "editorFontSize",
            "editor.lineNumbers" to "showLineNumbers",
            "editor.wordWrap" to "wordWrap",
            "editor.highlightCurrentLine" to "highlightCaretRow",
            "editor.rulerColumn" to "visualGuideColumn",
            "editor.tabSize" to "tabWidth",
            "editor.vimMode" to "vimMode",
            "editor.syncScrolling" to "syncScrolling",
            "files.saveOnFocusChange" to "saveOnFocusLoss",
            "files.trimTrailingWhitespace" to "trimTrailingWhitespaceOnSave",
            "files.insertFinalNewline" to "ensureNewlineOnSave",
            "markdown.autoTableOfContents" to "autoTableOfContents",
            "markdown.inspections" to "inspectionsEnabled",
            "markdown.weakWarnings" to "showWeakWarnings",
            "preview.viewMode" to "viewMode",
        )

        private fun defaultStorePath(): Path {
            val configured = System.getProperty("quill.config.dir")
            if (!configured.isNullOrBlank()) return Path.of(configured).resolve("settings.properties")

            val home = Path.of(System.getProperty("user.home", "."))
            val osName = System.getProperty("os.name").orEmpty().lowercase()
            val base = when {
                osName.contains("win") ->
                    System.getenv("APPDATA")?.let(Path::of) ?: home.resolve("AppData/Roaming")

                osName.contains("mac") -> home.resolve("Library/Application Support")

                else -> System.getenv("XDG_CONFIG_HOME")?.let(Path::of) ?: home.resolve(".config")
            }
            return base.resolve("Quill").resolve("settings.properties")
        }
    }

    /** Reads the stored settings, falling back to the default for anything missing or malformed. */
    public fun load(): QuillSettings {
        val properties = read() ?: return QuillSettings()

        // One pass over the registry. A setting whose key is absent, or whose value will not parse,
        // simply keeps its default — which is what makes a file written by a newer version, or
        // hand-edited into nonsense, still start the application.
        return SettingsRegistry.ALL.fold(QuillSettings()) { settings, setting ->
            val stored = properties.getProperty(setting.key)
                ?: LEGACY_KEYS[setting.key]?.let(properties::getProperty)
            if (stored == null) settings else setting.withText(settings, stored)
        }
    }

    /** Writes [settings], replacing whatever was there. Failure is silent and non-fatal. */
    public fun save(settings: QuillSettings) {
        val properties = Properties().apply {
            SettingsRegistry.ALL.forEach { setting -> setProperty(setting.key, setting.textOf(settings)) }
        }

        try {
            storePath.parent?.let { Files.createDirectories(it) }
            Files.newBufferedWriter(storePath, StandardCharsets.UTF_8).use { writer ->
                properties.store(writer, "Quill settings")
            }
        } catch (failure: IOException) {
            // Losing a preference is cosmetic. It must not surface as an error over the document.
        }
    }

    private fun read(): Properties? = try {
        if (!Files.isRegularFile(storePath)) {
            null
        } else {
            Properties().apply {
                Files.newBufferedReader(storePath, StandardCharsets.UTF_8).use(::load)
            }
        }
    } catch (failure: Exception) {
        when (failure) {
            is IOException, is IllegalArgumentException -> null
            else -> throw failure
        }
    }
}
