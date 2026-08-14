package dev.starfect.quill.ui.dialog

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.starfect.quill.model.BooleanSetting
import dev.starfect.quill.model.ChoiceSetting
import dev.starfect.quill.model.FloatSetting
import dev.starfect.quill.model.IntSetting
import dev.starfect.quill.model.QuillSettings
import dev.starfect.quill.model.Setting
import dev.starfect.quill.model.SettingsRegistry
import dev.starfect.quill.model.TextSetting
import dev.starfect.quill.ui.theme.LocalShellPalette
import dev.starfect.quill.ui.theme.LocalTypeScale
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Results for a settings search, rendered from the registry rather than from a hand-written page.
 *
 * The curated pages are better for the common case — they group things the way somebody looking for
 * them would expect, and they can say more than a one-line description. But they are also a list
 * somebody has to remember to add to, and a setting that exists and appears on no page is a setting
 * nobody can reach. Search is the safety net: it renders whatever the registry holds, so every
 * setting is always reachable by name, by key, or by what it does.
 */
@Composable
public fun SettingsSearchResults(
    query: String,
    settings: QuillSettings,
    onChange: (QuillSettings) -> Unit,
) {
    val shell = LocalShellPalette.current
    val matches = SettingsRegistry.search(query)

    if (matches.isEmpty()) {
        Text(
            "No setting matches \"$query\".",
            color = shell.mutedText,
            fontSize = LocalTypeScale.current.medium,
        )
        return
    }

    matches.groupBy { it.category }.forEach { (category, group) ->
        GroupHeader(category.label)
        group.forEach { setting ->
            SettingControl(setting, settings, onChange)
        }
        Spacer(Modifier.height(10.dp))
    }
}

/** One setting, rendered according to what kind of value it holds. */
@Composable
public fun SettingControl(
    setting: Setting<*>,
    settings: QuillSettings,
    onChange: (QuillSettings) -> Unit,
) {
    val shell = LocalShellPalette.current

    when (setting) {
        is BooleanSetting -> CheckboxRow(
            text = setting.title,
            checked = setting.get(settings),
            onCheckedChange = { onChange(setting.set(settings, it)) },
        )

        is IntSetting -> FormRow(setting.title) {
            val options = remember(setting.key) { setting.choicesForRange() }
            ListComboBox(
                items = options.map(Int::toString),
                selectedIndex = options.indexOf(setting.get(settings)).coerceAtLeast(0),
                onSelectedItemChange = { index -> onChange(setting.set(settings, options[index])) },
                modifier = Modifier.width(110.dp),
            )
        }

        is FloatSetting -> FormRow(setting.title) {
            Text(
                "%.0f".format(setting.get(settings)),
                color = shell.mutedText,
                fontSize = LocalTypeScale.current.medium,
            )
        }

        is ChoiceSetting<*> -> FormRow(setting.title) { ChoiceControl(setting, settings, onChange) }

        is TextSetting -> FormRow(setting.title) {
            TextField(
                value = TextFieldValue(setting.get(settings)),
                onValueChange = { onChange(setting.set(settings, it.text)) },
                modifier = Modifier.width(220.dp),
            )
        }
    }

    Text(
        setting.description,
        color = shell.mutedText,
        fontSize = LocalTypeScale.current.medium,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

/** Split out so the enum's type parameter has somewhere to be named. */
@Composable
private fun <E : Enum<E>> ChoiceControl(
    setting: ChoiceSetting<E>,
    settings: QuillSettings,
    onChange: (QuillSettings) -> Unit,
) {
    val labels = setting.choices.map(setting::labelOf)
    ListComboBox(
        items = labels,
        selectedIndex = setting.choices.indexOf(setting.get(settings)).coerceAtLeast(0),
        onSelectedItemChange = { index -> onChange(setting.set(settings, setting.choices[index])) },
        modifier = Modifier.width(220.dp),
    )
}

/**
 * The values a numeric setting offers in a list.
 *
 * A combo box rather than a free text field, because a settings dialog that accepts "twelve" and
 * silently keeps the old value is worse than one that offers twelve. Wide ranges are stepped so the
 * list stays a list rather than becoming a scroll.
 */
private fun IntSetting.choicesForRange(): List<Int> {
    val span = range.last - range.first
    val step = when {
        span <= 24 -> 1
        span <= 120 -> 10
        else -> 20
    }
    val stepped = (range.first..range.last step step).toMutableList()
    if (stepped.lastOrNull() != range.last) stepped += range.last
    return stepped
}

/** The search field at the top of the dialog. */
@Composable
public fun SettingsSearchField(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shell = LocalShellPalette.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("Search settings", color = shell.mutedText, fontSize = LocalTypeScale.current.medium)
            },
        )
    }
}

