package com.garathon.flatdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Icon
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.R

// Rows for choices: the whole row, label included, is one touch target, so tapping the text
// works like tapping the control (the control itself takes no clicks of its own).

@Composable
fun CheckboxRow(checked: Boolean, onChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(
        modifier = modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp))
    }
}

@Composable
fun RadioRow(selected: Boolean, onSelect: () -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(
        modifier = modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp))
    }
}

/**
 * A switch with a title and an optional hint below it. A [developer] setting (shown only with
 * developer options) has a wrench in front of it.
 */
@Composable
fun SwitchRow(checked: Boolean, onChange: (Boolean) -> Unit, title: String, hint: String? = null, enabled: Boolean = true, developer: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (developer) DeveloperMark()
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * A section header on the settings screens. Its look is set here for all of them:
 * [SECTION_TITLE_TOP_PADDING] separates it from the section above, and [sectionTitleStyle]
 * is its text size (setting titles are 16 sp, the page title `headlineSmall` 24 sp).
 */
@Composable
fun SectionTitle(text: String, danger: Boolean = false) {
    Text(
        text,
        style = sectionTitleStyle(),
        color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = SECTION_TITLE_TOP_PADDING),
    )
}

/** Extra space above each section header. */
val SECTION_TITLE_TOP_PADDING = 12.dp

/** The section headers' text style: `titleLarge` (22 sp). */
@Composable
fun sectionTitleStyle() = MaterialTheme.typography.titleLarge

/** The wrench in front of a developer setting, so it is told apart from the others. */
@Composable
fun DeveloperMark() {
    Icon(
        Icons.Filled.Build,
        contentDescription = stringResource(R.string.developer_setting),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}

/** Options shared by the setup settings screen and the settings screen. */

/** "Only download translations for my languages"; [deviceLanguages] is Flatpak's value, e.g. "de;en". */
@Composable
fun LanguagesOption(onlyMyLanguages: Boolean, deviceLanguages: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Column {
        CheckboxRow(onlyMyLanguages, onChange, stringResource(R.string.settings_languages, deviceLanguages.replace(";", ", ")), enabled = enabled)
        Text(stringResource(R.string.settings_languages_hint), style = MaterialTheme.typography.bodySmall)
    }
}
