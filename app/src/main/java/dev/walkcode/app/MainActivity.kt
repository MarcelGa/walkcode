package dev.walkcode.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.walkcode.app.ssh.DeviceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { DeviceKeyScreen() }
            }
        }
    }
}

/** Placeholder until M1: shows the phone's SSH public key. */
@Composable
private fun DeviceKeyScreen() {
    var text by remember { mutableStateOf("Generating device key…") }
    LaunchedEffect(Unit) {
        text = withContext(Dispatchers.Default) {
            runCatching { DeviceKey.getOrCreate().openSshPublicKey() }
                .getOrElse { "Key error: ${it.message}" }
        }
    }
    Column(Modifier.padding(24.dp)) {
        Text("Walkcode", style = MaterialTheme.typography.headlineMedium)
        Text("Device public key", Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleSmall)
        SelectionContainer {
            Text(text, Modifier.padding(top = 8.dp), fontFamily = FontFamily.Monospace)
        }
    }
}
