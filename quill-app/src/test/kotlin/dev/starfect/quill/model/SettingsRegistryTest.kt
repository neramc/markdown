package dev.starfect.quill.model

import dev.starfect.quill.io.SettingsStore
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The registry has to describe every setting there is.
 *
 * A field added to [QuillSettings] without an entry here does not fail to compile — it fails to be
 * saved, which the user discovers by restarting and finding their change gone. That is the failure
 * this file exists to convert into a red build.
 */
class SettingsRegistryTest {

    private val temporary = Files.createTempDirectory("quill-settings-test")

    @AfterTest
    fun cleanUp() {
        temporary.toFile().deleteRecursively()
    }

    /** Every setting moved off its default, so a round trip has something to lose. */
    private fun everythingChanged(): QuillSettings {
        val defaults = QuillSettings()
        return SettingsRegistry.ALL.fold(defaults) { settings, setting -> setting.moveOffDefault(settings) }
    }

    private fun Setting<*>.moveOffDefault(settings: QuillSettings): QuillSettings = when (this) {
        is BooleanSetting -> set(settings, !get(settings))
        is IntSetting -> set(settings, if (get(settings) == range.first) range.last else range.first)
        is FloatSetting -> set(
            settings,
            if (get(settings) == range.start) range.endInclusive else range.start,
        )
        is ChoiceSetting<*> -> moveChoiceOffDefault(settings)
        is TextSetting -> set(settings, get(settings) + "-changed")
    }

    private fun <E : Enum<E>> ChoiceSetting<E>.moveChoiceOffDefault(settings: QuillSettings): QuillSettings {
        val current = get(settings)
        val other = choices.firstOrNull { it != current } ?: return settings
        return set(settings, other)
    }

    @Test
    fun `the registry covers every field of the settings`() {
        // Reflection is fine here and not in the application: a test is never shrunk, and the whole
        // point is to notice a field the registry does not know about.
        val fields = QuillSettings::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .filterNot { it.name.contains('$') }
            .map { it.name }

        assertEquals(
            fields.size,
            SettingsRegistry.ALL.size,
            "QuillSettings has ${fields.size} fields and the registry declares " +
                "${SettingsRegistry.ALL.size}. A field without an entry is a setting that is never " +
                "saved. Fields: $fields",
        )
    }

    @Test
    fun `every setting survives being written and read back`() {
        val store = SettingsStore(temporary.resolve("settings.properties"))
        val changed = everythingChanged()

        store.save(changed)
        val loaded = store.load()

        assertEquals(changed, loaded, "a setting that does not round-trip is a setting that is lost")
    }

    @Test
    fun `moving every setting off its default actually changes all of them`() {
        // Guards the test above: if `moveOffDefault` quietly failed to move something, the round
        // trip would pass while proving nothing about that setting.
        val defaults = QuillSettings()
        val changed = everythingChanged()

        val unmoved = SettingsRegistry.ALL.filter { it.textOf(defaults) == it.textOf(changed) }
        assertTrue(unmoved.isEmpty(), "these settings were never moved off their default: ${unmoved.map { it.key }}")
    }

    @Test
    fun `keys are unique`() {
        val duplicates = SettingsRegistry.ALL.groupBy { it.key }.filterValues { it.size > 1 }.keys
        assertTrue(duplicates.isEmpty(), "two settings share a key: $duplicates")
    }

    @Test
    fun `an unreadable value falls back to the default rather than failing the load`() {
        val file = temporary.resolve("broken.properties")
        Files.writeString(
            file,
            """
            editor.fontSize=not-a-number
            editor.tabSize=99999
            preview.viewMode=NOT_A_MODE
            workbench.darkTheme=maybe
            """.trimIndent(),
        )

        val loaded = SettingsStore(file).load()
        val defaults = QuillSettings()

        assertEquals(defaults.editorFontSize, loaded.editorFontSize, "nonsense must not become a font size")
        assertEquals(defaults.viewMode, loaded.viewMode, "an unknown mode must fall back")
        assertEquals(defaults.darkTheme, loaded.darkTheme, "a non-boolean must fall back")
        assertEquals(defaults.tabWidth, loaded.tabWidth, "an out-of-range tab width must fall back too")
    }

    @Test
    fun `settings written by the previous version are still read`() {
        // The keys became dotted when the registry arrived. Anybody upgrading has a file full of the
        // old names, and losing all of their preferences on upgrade is worse than the old names were.
        val file = temporary.resolve("legacy.properties")
        Files.writeString(
            file,
            """
            darkTheme=false
            editorFontSize=19
            tabWidth=2
            viewMode=PREVIEW
            wordWrap=false
            leftToolWindowWidth=333.0
            """.trimIndent(),
        )

        val loaded = SettingsStore(file).load()

        assertEquals(false, loaded.darkTheme)
        assertEquals(19, loaded.editorFontSize)
        assertEquals(2, loaded.tabWidth)
        assertEquals(ViewMode.PREVIEW, loaded.viewMode)
        assertEquals(false, loaded.wordWrap)
        assertEquals(333f, loaded.leftToolWindowWidth)
    }

    @Test
    fun `search finds a setting by name, by key and by what it does`() {
        assertTrue(SettingsRegistry.search("word wrap").contains(SettingsRegistry.WordWrap))
        assertTrue(SettingsRegistry.search("editor.tabSize").contains(SettingsRegistry.TabWidth))
        assertTrue(
            SettingsRegistry.search("vertical guide").contains(SettingsRegistry.VisualGuideColumn),
            "searching for what a setting does should find it, not only its name",
        )
        assertEquals(SettingsRegistry.ALL, SettingsRegistry.search("   "), "an empty search shows everything")
    }

    @Test
    fun `every setting says what it is and what it does`() {
        val unexplained = SettingsRegistry.ALL.filter {
            it.title.isBlank() || it.description.length < 20 || !it.description.endsWith(".")
        }
        assertTrue(
            unexplained.isEmpty(),
            "a setting nobody can understand is a setting nobody will touch: ${unexplained.map { it.key }}",
        )
    }
}
