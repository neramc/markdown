package dev.starfect.quill.io.vscode

import dev.starfect.quill.model.QuillSettings
import dev.starfect.quill.model.Setting
import dev.starfect.quill.model.SettingsRegistry
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/** One installation Quill found settings for. */
public data class VsCodeInstallation(
    /** "VS Code", "VS Code Insiders", "VSCodium", "Cursor". */
    public val name: String,
    public val settingsFile: Path,
)

/** One setting the import changed. */
public data class ImportedSetting(
    public val setting: Setting<*>,
    /** The VS Code key it came from. */
    public val from: String,
    public val before: String,
    public val after: String,
)

/**
 * What an import did, so the user can see it rather than take it on trust.
 *
 * Both halves matter. The settings that came across are the point; the ones that did not are how
 * somebody finds out that their carefully chosen font ligature preference is not going to appear,
 * instead of assuming it did and wondering later why nothing looks right.
 */
public data class ImportReport(
    public val installation: VsCodeInstallation,
    public val settings: QuillSettings,
    public val imported: List<ImportedSetting>,
    /** Keys VS Code had that Quill has no equivalent for. */
    public val unsupported: List<String>,
    /** Keys that map here but whose value made no sense. */
    public val unreadable: List<String>,
    public val failure: String? = null,
) {
    public val changedCount: Int get() = imported.size
}

/**
 * Reads settings out of a VS Code installation.
 *
 * People arriving from VS Code have already made a hundred small decisions — how big the text is,
 * whether lines wrap, how wide a tab is, whether the file gets a final newline — and asking them to
 * make all of those again is the sort of friction that decides whether a tool gets used twice. The
 * ones that mean the same thing in both editors are copied; the rest are listed rather than guessed
 * at.
 *
 * A setting is only carried across when the correspondence is honest. `editor.fontSize` means the
 * same thing in both; `editor.fontLigatures` has nothing to answer to here. Half-mapping a setting
 * so the import number looks bigger produces an editor that behaves in ways the user did not ask
 * for and cannot trace back.
 */
public object VsCodeSettings {

    /**
     * Where each flavour keeps its user settings.
     *
     * Insiders, VSCodium and Cursor are here because the people most likely to want this feature are
     * exactly the people who do not run the plain build.
     */
    private val FLAVOURS: List<Pair<String, String>> = listOf(
        "VS Code" to "Code",
        "VS Code Insiders" to "Code - Insiders",
        "VSCodium" to "VSCodium",
        "Cursor" to "Cursor",
        "Windsurf" to "Windsurf",
    )

    /** Every installation on this machine that has a settings file, in the order above. */
    public fun installations(): List<VsCodeInstallation> {
        val root = userDataRoot() ?: return emptyList()
        return FLAVOURS.mapNotNull { (name, directory) ->
            val file = root.resolve(directory).resolve("User").resolve("settings.json")
            if (file.isRegularFile()) VsCodeInstallation(name, file) else null
        }
    }

    /** Where VS Code keeps per-user data on this platform. */
    private fun userDataRoot(): Path? {
        val override = System.getProperty("quill.vscode.root")
        if (!override.isNullOrBlank()) return Path.of(override)

        val home = Path.of(System.getProperty("user.home", "."))
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            osName.contains("win") ->
                System.getenv("APPDATA")?.let(Path::of) ?: home.resolve("AppData/Roaming")

            osName.contains("mac") || osName.contains("darwin") ->
                home.resolve("Library/Application Support")

            else -> System.getenv("XDG_CONFIG_HOME")?.let(Path::of) ?: home.resolve(".config")
        }
    }

    /** Applies [installation]'s settings on top of [current]. */
    public fun importFrom(installation: VsCodeInstallation, current: QuillSettings): ImportReport {
        val text = try {
            Files.readString(installation.settingsFile)
        } catch (failure: Exception) {
            return ImportReport(
                installation, current, emptyList(), emptyList(), emptyList(),
                failure = "Could not read ${installation.settingsFile}: ${failure.message}",
            )
        }

        val parsed = try {
            Jsonc.parse(text)
        } catch (failure: Jsonc.MalformedException) {
            return ImportReport(
                installation, current, emptyList(), emptyList(), emptyList(),
                failure = "${installation.settingsFile.fileName} could not be read: ${failure.message}",
            )
        }

        val root = parsed as? Map<*, *> ?: return ImportReport(
            installation, current, emptyList(), emptyList(), emptyList(),
            failure = "${installation.settingsFile.fileName} does not hold a settings object",
        )

        return apply(installation, flatten(root), current)
    }

    /**
     * The settings that apply to a Markdown document, with the language block winning.
     *
     * VS Code lets a setting be overridden per language — `"[markdown]": { "editor.wordWrap": "on" }`
     * — and somebody who has written that has said something specific about editing Markdown, which
     * is the only thing Quill does. Taking the global value and ignoring the override would import
     * the opposite of what they asked for.
     */
    internal fun flatten(root: Map<*, *>): Map<String, Any?> {
        val flat = LinkedHashMap<String, Any?>()
        root.forEach { (key, value) -> if (key is String && !key.startsWith("[")) flat[key] = value }

        root.entries
            .filter { (key, _) -> key is String && coversMarkdown(key) }
            .mapNotNull { it.value as? Map<*, *> }
            .forEach { block -> block.forEach { (key, value) -> if (key is String) flat[key] = value } }

        return flat
    }

    /**
     * Whether a language-scope key applies to Markdown.
     *
     * VS Code writes a scope two ways — `"[markdown]"` and, for several languages at once,
     * `"[markdown][plaintext]"` with the groups run together rather than comma-separated. Matching
     * only the first spelling means silently ignoring the settings of anybody who shares one block
     * between prose formats, which is most people who write one.
     */
    private fun coversMarkdown(key: String): Boolean {
        if (!key.startsWith("[")) return false
        return SCOPE_GROUP.findAll(key)
            .flatMap { match -> match.groupValues[1].split(',').asSequence() }
            .any { it.trim().equals("markdown", ignoreCase = true) }
    }

    private val SCOPE_GROUP = Regex("""\[([^\]]*)]""")

    private fun apply(
        installation: VsCodeInstallation,
        values: Map<String, Any?>,
        current: QuillSettings,
    ): ImportReport {
        val known = SettingsRegistry.ALL.filter { it.vsCode != null }.associateBy { it.vsCode!! }

        var settings = current
        val imported = mutableListOf<ImportedSetting>()
        val unreadable = mutableListOf<String>()

        values.forEach { (key, raw) ->
            val setting = known[key] ?: return@forEach
            val before = setting.textOf(settings)
            val updated = setting.withVsCode(settings, raw)
            if (updated == null) {
                unreadable += key
                return@forEach
            }
            val after = setting.textOf(updated)
            if (after != before) {
                imported += ImportedSetting(setting, key, before, after)
            }
            settings = updated
        }

        val unsupported = values.keys
            .filterNot { it in known }
            // Extension settings are not VS Code's own and were never going to map.
            .filterNot { it.substringBefore('.') !in QUILLS_CONCERNS }
            .sorted()

        return ImportReport(installation, settings, imported, unsupported, unreadable)
    }

    /**
     * The setting namespaces worth reporting as unsupported.
     *
     * Somebody's VS Code holds hundreds of keys belonging to extensions, telemetry and languages
     * Quill has never heard of. Listing all of them as "not imported" would bury the four that
     * actually matter under noise, and imply Quill ought to have supported `python.analysis`.
     */
    private val QUILLS_CONCERNS = setOf("editor", "files", "markdown", "workbench")
}
