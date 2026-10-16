package com.garathon.flatdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

/** A switch with a title and an optional hint below it. */
@Composable
fun SwitchRow(checked: Boolean, onChange: (Boolean) -> Unit, title: String, hint: String? = null, enabled: Boolean = true) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
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
