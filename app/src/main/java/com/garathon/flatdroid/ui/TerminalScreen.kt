package com.garathon.flatdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.R
import com.garathon.flatdroid.ui.theme.FlatdroidTheme

/**
 * The Alpine terminal (Settings → Terminal): the single command field (one command, its output,
 * no session; not a terminal emulator).
 */
@Composable
fun TerminalScreen(
    output: String,
    running: Boolean,
    onRun: (String) -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(output) { scroll.scrollTo(scroll.maxValue) }

    Column(
        modifier = modifier.fillMaxSize().aboveKeyboard().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.terminal_title), style = MaterialTheme.typography.headlineSmall)
        CommandInput(running, onRun, onCancel, Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onBack) {
                Text(stringResource(R.string.settings_back))
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll)) {
            LogText(output, Modifier.fillMaxWidth())
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TerminalScreenPreview() {
    FlatdroidTheme {
        TerminalScreen(output = "$ flatpak remotes\nflathub\n(exit 0)", running = false, onRun = {}, onCancel = {}, onBack = {})
    }
}
