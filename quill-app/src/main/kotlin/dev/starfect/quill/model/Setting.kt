package dev.starfect.quill.model

/** Where a setting appears in the settings dialog. */
public enum class SettingCategory(public val label: String) {
    APPEARANCE("Appearance"),
    EDITOR("Editor"),
    TYPING("Typing"),
    FILES("Files"),
    MARKDOWN("Markdown"),
    PREVIEW("Preview"),
    ADVANCED("Advanced"),
}

/**
 * One setting, declared once.
 *
 * Every setting used to be written in three places — the data class, the loader and the saver — and
 * the settings dialog spelled out a fourth. Four lists that have to agree, kept in agreement by
 * hand, is a design that works at twenty settings and produces a silently-not-persisted preference
 * at sixty. The failure is invisible until a user restarts and their change is gone.
 *
 * So a setting is declared here once, carrying everything anybody needs to know about it: where it
 * lives in [QuillSettings], how it is written to and read from a properties file, what it is called,
 * what it does, and which VS Code setting it corresponds to. Persistence, the dialog, the search
 * over settings and the VS Code import are all derived from that one declaration and cannot drift
 * from it.
 *
 * The value still lives in [QuillSettings] as an ordinary field rather than in a map. Every reader
 * in the application says `settings.wordWrap` and gets a `Boolean`, checked at compile time; a map
 * would trade that for a uniformity only this file benefits from.
 */
public sealed class Setting<T>(
    /** The properties-file key, and the identifier in the settings UI. Dotted, like VS Code's. */
    public val key: String,
    public val title: String,
    public val description: String,
    public val category: SettingCategory,
    /** The VS Code setting this one corresponds to, when there is a fair correspondence. */
    public val vsCode: String? = null,
) {
    public abstract val default: T

    /** Reads this setting out of [settings]. */
    public abstract fun get(settings: QuillSettings): T

    /** Returns [settings] with this setting replaced by [value]. */
    public abstract fun set(settings: QuillSettings, value: T): QuillSettings

    /** Parses a stored string, or null when it is missing, malformed or out of range. */
    protected abstract fun decode(text: String): T?

    protected open fun encode(value: T): String = value.toString()

    /**
     * Interprets a value from VS Code's settings file, or null when it means nothing here.
     *
     * The argument is whatever the JSON held — a `Boolean`, a `Double`, a `String`, a list or a map.
     * Returning null for anything unrecognised is what lets the import skip a setting rather than
     * guess at it.
     */
    protected open fun fromVsCode(value: Any?): T? = when (value) {
        is String -> decode(value)
        else -> null
    }

    // ------------------------------------------------------------------ type-erased helpers
    //
    // The three below exist so callers can work over `Setting<*>` without star-projection
    // gymnastics at every site. Each is the generic pair of operations applied together.

    /** [settings] with this setting parsed out of [text], or unchanged when [text] is no good. */
    public fun withText(settings: QuillSettings, text: String): QuillSettings =
        decode(text)?.let { set(settings, it) } ?: settings

    /** This setting's current value, as it is written to the properties file. */
    public fun textOf(settings: QuillSettings): String = encode(get(settings))

    /** [settings] with this setting taken from a VS Code value, or null when it does not apply. */
    public fun withVsCode(settings: QuillSettings, value: Any?): QuillSettings? =
        fromVsCode(value)?.let { set(settings, it) }

    /** Whether [settings] holds this setting's default. */
    public fun isDefault(settings: QuillSettings): Boolean = get(settings) == default

    /** [settings] with this setting put back to its default. */
    public fun reset(settings: QuillSettings): QuillSettings = set(settings, default)

    /** Free-text match for the settings search, over the name, the key and the description. */
    public fun matches(query: String): Boolean {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return true
        return title.lowercase().contains(needle) ||
            key.lowercase().contains(needle) ||
            description.lowercase().contains(needle) ||
            vsCode?.lowercase()?.contains(needle) == true
    }
}

/** A setting that is on or off. */
public class BooleanSetting(
    key: String,
    title: String,
    description: String,
    category: SettingCategory,
    override val default: Boolean,
    private val read: (QuillSettings) -> Boolean,
    private val write: (QuillSettings, Boolean) -> QuillSettings,
    vsCode: String? = null,
    /** VS Code sometimes spells a boolean as a word — `"on"`/`"off"`, `"active"`/`"none"`. */
    private val trueWords: Set<String> = emptySet(),
    private val falseWords: Set<String> = emptySet(),
) : Setting<Boolean>(key, title, description, category, vsCode) {

    override fun get(settings: QuillSettings): Boolean = read(settings)
    override fun set(settings: QuillSettings, value: Boolean): QuillSettings = write(settings, value)
    override fun decode(text: String): Boolean? = text.trim().toBooleanStrictOrNull()

    override fun fromVsCode(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> when (val word = value.trim().lowercase()) {
            in trueWords -> true
            in falseWords -> false
            else -> word.toBooleanStrictOrNull()
        }
        else -> null
    }
}

/** A whole number, clamped to [range] rather than rejected — a value slightly out is still intent. */
public class IntSetting(
    key: String,
    title: String,
    description: String,
    category: SettingCategory,
    override val default: Int,
    public val range: IntRange,
    private val read: (QuillSettings) -> Int,
    private val write: (QuillSettings, Int) -> QuillSettings,
    vsCode: String? = null,
) : Setting<Int>(key, title, description, category, vsCode) {

    override fun get(settings: QuillSettings): Int = read(settings)

    /** Clamped, because a caller setting a value is expressing intent — a dragged edge, a slider. */
    override fun set(settings: QuillSettings, value: Int): QuillSettings =
        write(settings, value.coerceIn(range))

    /**
     * Rejected rather than clamped when out of range.
     *
     * The two directions are not symmetric. A stored `9999` for a font size is a broken file, and
     * clamping it hands the user a interface at maximum size that they never asked for and cannot
     * account for; falling back to the default says "that meant nothing to me" in the only way a
     * settings file can.
     */
    override fun decode(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in range }

    /**
     * Clamped, unlike [decode]. An imported value is somebody's real preference in another editor
     * rather than a corrupt file, so the nearest thing Quill can offer beats ignoring it.
     */
    override fun fromVsCode(value: Any?): Int? = when (value) {
        // JSON has one number type, so an integer arrives as a Double.
        is Number -> value.toInt().coerceIn(range)
        is String -> value.trim().toIntOrNull()?.coerceIn(range)
        // `editor.rulers` is a list, and may hold objects rather than plain numbers. Quill draws
        // one guide, so the first column wins.
        is List<*> -> value.firstNotNullOfOrNull { entry ->
            when (entry) {
                is Number -> entry.toInt()
                is Map<*, *> -> (entry["column"] as? Number)?.toInt()
                else -> null
            }
        }?.coerceIn(range)
        else -> null
    }
}

/** A fractional value, for the sizes the UI drags rather than types. */
public class FloatSetting(
    key: String,
    title: String,
    description: String,
    category: SettingCategory,
    override val default: Float,
    public val range: ClosedFloatingPointRange<Float>,
    private val read: (QuillSettings) -> Float,
    private val write: (QuillSettings, Float) -> QuillSettings,
    vsCode: String? = null,
) : Setting<Float>(key, title, description, category, vsCode) {

    override fun get(settings: QuillSettings): Float = read(settings)
    override fun set(settings: QuillSettings, value: Float): QuillSettings =
        write(settings, value.coerceIn(range))

    /** Rejected rather than clamped when out of range, for the reason given on [IntSetting]. */
    override fun decode(text: String): Float? = text.trim().toFloatOrNull()?.takeIf { it in range }

    override fun fromVsCode(value: Any?): Float? = when (value) {
        is Number -> value.toFloat().coerceIn(range)
        is String -> value.trim().toFloatOrNull()?.coerceIn(range)
        else -> null
    }
}

/** One of a fixed set of choices. */
public class ChoiceSetting<E : Enum<E>>(
    key: String,
    title: String,
    description: String,
    category: SettingCategory,
    override val default: E,
    public val choices: List<E>,
    private val read: (QuillSettings) -> E,
    private val write: (QuillSettings, E) -> QuillSettings,
    vsCode: String? = null,
    /** VS Code's spelling of each choice, where it differs from the enum's own name. */
    private val vsCodeNames: Map<String, E> = emptyMap(),
    /** How each choice reads in the dialog, where the enum name would be shouting. */
    public val labels: Map<E, String> = emptyMap(),
) : Setting<E>(key, title, description, category, vsCode) {

    override fun get(settings: QuillSettings): E = read(settings)
    override fun set(settings: QuillSettings, value: E): QuillSettings = write(settings, value)
    override fun decode(text: String): E? = choices.firstOrNull { it.name == text.trim() }

    /** The choice's label, falling back to a readable form of its name. */
    public fun labelOf(choice: E): String =
        labels[choice] ?: choice.name.lowercase().replaceFirstChar { it.uppercase() }

    override fun fromVsCode(value: Any?): E? = when (value) {
        is String -> vsCodeNames[value.trim().lowercase()] ?: decode(value)
        else -> null
    }
}

/** Free text: a font family, a file pattern, a command. */
public class TextSetting(
    key: String,
    title: String,
    description: String,
    category: SettingCategory,
    override val default: String,
    private val read: (QuillSettings) -> String,
    private val write: (QuillSettings, String) -> QuillSettings,
    vsCode: String? = null,
) : Setting<String>(key, title, description, category, vsCode) {

    override fun get(settings: QuillSettings): String = read(settings)
    override fun set(settings: QuillSettings, value: String): QuillSettings = write(settings, value.trim())
    override fun decode(text: String): String = text

    override fun fromVsCode(value: Any?): String? = when (value) {
        is String -> value
        // VS Code's font family is a comma-separated list; the first entry is the one meant.
        is List<*> -> value.filterIsInstance<String>().firstOrNull()
        else -> null
    }
}
