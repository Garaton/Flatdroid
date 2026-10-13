package com.garathon.flatdroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.ui.theme.FlatdroidTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FlatdroidTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val output by viewModel.output.collectAsState()
                    val running by viewModel.running.collectAsState()
                    ProotTestScreen(
                        output = output,
                        running = running,
                        onRun = viewModel::runTest,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

@Composable
fun ProotTestScreen(output: String, running: Boolean, onRun: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = onRun, enabled = !running) {
            Text(if (running) "Running…" else "Run proot test")
        }
        Text(
            text = output,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ProotTestScreenPreview() {
    FlatdroidTheme {
        ProotTestScreen(output = "$ /bin/echo hi\nhi\n(exit 0)", running = false, onRun = {})
    }
}
