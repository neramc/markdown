package dev.starfect.quill.io

import dev.starfect.quill.model.QuillSettings
import dev.starfect.quill.model.ViewMode
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
        val defaults = QuillSettings()
        val properties = read() ?: return defaults

        fun bool(key: String, fallback: Boolean) =
            properties.getProperty(key)?.toBooleanStrictOrNull() ?: fallback

        fun int(key: String, fallback: Int, range: IntRange) =
            properties.getProperty(key)?.toIntOrNull()?.takeIf { it in range } ?: fallback

        return QuillSettings(
            darkTheme = bool("darkTheme", defaults.darkTheme),
            islands = bool("islands", defaults.islands),
            viewMode = properties.getProperty("viewMode")
                ?.let { name -> ViewMode.entries.firstOrNull { it.name == name } }
                ?: defaults.viewMode,
            showLineNumbers = bool("showLineNumbers", defaults.showLineNumbers),
            editorFontSize = int("editorFontSize", defaults.editorFontSize, 8..48),
            uiFontSize = int("uiFontSize", defaults.uiFontSize, 8..32),
            wordWrap = bool("wordWrap", defaults.wordWrap),
            highlightCaretRow = bool("highlightCaretRow", defaults.highlightCaretRow),
            showWeakWarnings = bool("showWeakWarnings", defaults.showWeakWarnings),
            inspectionsEnabled = bool("inspectionsEnabled", defaults.inspectionsEnabled),
            syncScrolling = bool("syncScrolling", defaults.syncScrolling),
            saveOnFocusLoss = bool("saveOnFocusLoss", defaults.saveOnFocusLoss),
            trimTrailingWhitespaceOnSave =
                bool("trimTrailingWhitespaceOnSave", defaults.trimTrailingWhitespaceOnSave),
            ensureNewlineOnSave = bool("ensureNewlineOnSave", defaults.ensureNewlineOnSave),
            visualGuideColumn = int("visualGuideColumn", defaults.visualGuideColumn, 0..300),
            tabWidth = int("tabWidth", defaults.tabWidth, 1..16),
        )
    }

    /** Writes [settings], replacing whatever was there. Failure is silent and non-fatal. */
    public fun save(settings: QuillSettings) {
        val properties = Properties().apply {
            setProperty("darkTheme", settings.darkTheme.toString())
            setProperty("islands", settings.islands.toString())
            setProperty("viewMode", settings.viewMode.name)
            setProperty("showLineNumbers", settings.showLineNumbers.toString())
            setProperty("editorFontSize", settings.editorFontSize.toString())
            setProperty("uiFontSize", settings.uiFontSize.toString())
            setProperty("wordWrap", settings.wordWrap.toString())
            setProperty("highlightCaretRow", settings.highlightCaretRow.toString())
            setProperty("showWeakWarnings", settings.showWeakWarnings.toString())
            setProperty("inspectionsEnabled", settings.inspectionsEnabled.toString())
            setProperty("syncScrolling", settings.syncScrolling.toString())
            setProperty("saveOnFocusLoss", settings.saveOnFocusLoss.toString())
            setProperty("trimTrailingWhitespaceOnSave", settings.trimTrailingWhitespaceOnSave.toString())
            setProperty("ensureNewlineOnSave", settings.ensureNewlineOnSave.toString())
            setProperty("visualGuideColumn", settings.visualGuideColumn.toString())
            setProperty("tabWidth", settings.tabWidth.toString())
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
