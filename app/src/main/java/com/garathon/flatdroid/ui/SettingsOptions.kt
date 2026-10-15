package com.garathon.flatdroid.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.garathon.flatdroid.R

/** Options shared by the setup settings screen and the settings screen. */

/** "Only download translations for my languages"; [deviceLanguages] is Flatpak's value, e.g. "de;en". */
@Composable
fun LanguagesOption(onlyMyLanguages: Boolean, deviceLanguages: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = onlyMyLanguages, onCheckedChange = onChange, enabled = enabled)
            Text(stringResource(R.string.settings_languages, deviceLanguages.replace(";", ", ")))
        }
        Text(stringResource(R.string.settings_languages_hint), style = MaterialTheme.typography.bodySmall)
    }
}

/** Options used by only the setup settings screen */


/** Options used by only the settings screen */

