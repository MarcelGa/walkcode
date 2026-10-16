package dev.walkcode.app.spike

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import dev.walkcode.app.ssh.DeviceKey
import dev.walkcode.app.ssh.RecordingHostKeyVerifier
import dev.walkcode.app.ssh.Ssh
import dev.walkcode.app.terminal.ExtraKey
import dev.walkcode.app.terminal.SshTerminalTransport
import dev.walkcode.app.terminal.TerminalController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.method.AuthPublickey

/**
 * Spike 2 (debug builds only): attach to a tmux/psmux session over SSH in a Termux terminal.
 *
 * adb shell am start -n dev.walkcode.app/.spike.TerminalSpikeActivity \
 *   --es host 10.0.2.2 --ei port 2222 --es user dev --es cmd "tmux -u attach -t spike"
 */
class TerminalSpikeActivity : ComponentActivity() {

    private var status by mutableStateOf("Connecting…")
    private var title by mutableStateOf<String?>(null)
    private var ctrl by mutableStateOf(false)
    private var alt by mutableStateOf(false)
    private var bells = 0
    private var client: SSHClient? = null
    private lateinit var controller: TerminalController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val host = intent.getStringExtra("host") ?: "10.0.2.2"
        val port = intent.getIntExtra("port", 22)
        val user = intent.getStringExtra("user") ?: "dev"
        val cmd = intent.getStringExtra("cmd") ?: "tmux -u new-session -A -s spike"
        val pty = intent.getBooleanExtra("pty", true)

        val terminalView = TerminalView(this, null)
        controller = TerminalController(this, terminalView, object : TerminalController.Listener {
            override fun onTitleChanged(title: String?) { this@TerminalSpikeActivity.title = title }
            override fun onBell() { bells++; Log.i(TAG, "bell #$bells") }
            override fun onFinished(session: TerminalSession) {
                status = "Ended: ${session.exitReason ?: "exit ${session.exitStatus}"}"
                Log.i(TAG, status)
            }
            override fun onModifiersChanged(ctrl: Boolean, alt: Boolean) {
                this@TerminalSpikeActivity.ctrl = ctrl; this@TerminalSpikeActivity.alt = alt
            }
        })
        terminalView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            terminalView.mEmulator?.let { Log.i(TAG, "size ${it.mColumns}x${it.mRows}") }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                        Text(
                            "$user@$host:$port · ${title ?: status}",
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        AndroidView(factory = { terminalView }, modifier = Modifier.weight(1f).fillMaxWidth())
                        ExtraKeysRow()
                    }
                }
            }
        }

        lifecycleScope.launch {
            try {
                val ssh = withContext(Dispatchers.IO) {
                    Ssh.client().apply {
                        addHostKeyVerifier(RecordingHostKeyVerifier())
                        connectTimeout = 8_000
                        connect(host, port)
                        auth(user, AuthPublickey(DeviceKey.getOrCreate().keyProvider()))
                    }
                }
                client = ssh
                status = "Connected"
                Log.i(TAG, "connected $user@$host:$port pty=$pty cmd=$cmd")
                terminalView.attachSession(TerminalSession(SshTerminalTransport(ssh, cmd, pty), TRANSCRIPT_ROWS, controller))
                controller.showKeyboard()
            } catch (e: Exception) {
                status = "Failed: ${e.message}"
                Log.e(TAG, "connect failed", e)
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ExtraKeysRow() {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF121820)).horizontalScroll(rememberScrollState()).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (key in ExtraKey.entries) {
                val active = (key == ExtraKey.CTRL && ctrl) || (key == ExtraKey.ALT && alt)
                FilledTonalButton(
                    onClick = { Log.i(TAG, "extra key ${key.name}"); key.press(controller) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                    colors = if (active) androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF3DDC84))
                    else androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(),
                ) { Text(key.label) }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.view.currentSession?.finishIfRunning()
        val c = client ?: return
        Thread { runCatching { c.disconnect() } }.start()
    }

    private companion object {
        const val TAG = "WalkcodeTerm"
        const val TRANSCRIPT_ROWS = 2000
    }
}
