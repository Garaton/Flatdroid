package com.garathon.flatdroid.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.R
import com.garathon.flatdroid.ui.FlatdroidIcon
import com.garathon.flatdroid.ui.theme.FlatdroidTheme

/** While the Alpine environment is reset: a screen of its own, so it doesn't pass for a step of setup. [detail] is what it is doing. */
@Composable
fun ResetScreen(detail: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FlatdroidIcon(size = 64.dp)
        Text(stringResource(R.string.reset_screen_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        CircularProgressIndicator()
        Text(
            detail.ifEmpty { stringResource(R.string.reset_running) },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.reset_screen_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ResetScreenPreview() {
    FlatdroidTheme { ResetScreen("Resetting the Alpine environment…") }
}
