package dev.walkcode.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.walkcode.app.assistant.WalkAssistant
import dev.walkcode.app.data.AppSettings
import dev.walkcode.app.voice.RealtimeProtocol
import dev.walkcode.app.voice.UsageFormat
import dev.walkcode.app.voice.VoiceSession

/**
 * Switches walk mode on or off: checks the key, asks for the microphone (and, once, for
 * notifications, so the "listening" notice with its Stop button shows).
 */
@Composable
fun rememberWalkToggle(walk: WalkAssistant, settings: AppSettings): () -> Unit {
    val context = LocalContext.current
    val on by walk.on.collectAsState()
    val start: () -> Unit = {
        if (!walk.start()) Toast.makeText(context, "Add an OpenAI API key in Settings first", Toast.LENGTH_LONG).show()
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { start() }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) Toast.makeText(context, "Walk mode needs the microphone", Toast.LENGTH_LONG).show()
        else if (!granted(context, Manifest.permission.POST_NOTIFICATIONS)) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else start()
    }
    return {
        when {
            on -> walk.stop()
            settings.openAiKey.value == null -> Toast.makeText(context, "Add an OpenAI API key in Settings first", Toast.LENGTH_LONG).show()
            !granted(context, Manifest.permission.RECORD_AUDIO) -> mic.launch(Manifest.permission.RECORD_AUDIO)
            !granted(context, Manifest.permission.POST_NOTIFICATIONS) -> notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> start()
        }
    }
}

private fun granted(context: android.content.Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** One line under the Projects top bar while walk mode is on or has just failed; tap for the conversation. */
@Composable
fun WalkStrip(walk: WalkAssistant, onOpen: () -> Unit) {
    val state by walk.session.state.collectAsState()
    val problem by walk.session.problem.collectAsState()
    val turns by walk.session.turns.collectAsState()
    if (state == VoiceSession.State.Idle) return
    val last = turns.lastOrNull { !it.fromUser }?.text
    val (text, color) = when (state) {
        VoiceSession.State.Connecting -> "Walk mode: connecting…" to WC.Muted
        VoiceSession.State.Error -> "Walk mode stopped: ${problem ?: "disconnected"}" to WC.Danger
        else -> (last?.let { "“$it”" } ?: "Walk mode: listening — ask about your workspaces") to WC.Accent
    }
    Text(
        text, color = color, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).background(WC.Panel).padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** Walk mode's conversation: status, transcript with the functions it ran, and typing instead of speaking. */
@Composable
fun ColumnScope.WalkScreen(walk: WalkAssistant, settings: AppSettings, onBack: () -> Unit) {
    val session = walk.session
    val voice by settings.realtimeVoice.collectAsState()
    val apiKey by settings.openAiKey.collectAsState()
    val state by session.state.collectAsState()
    val problem by session.problem.collectAsState()
    val output by session.output.collectAsState()
    val turns by session.turns.collectAsState()
    val spend by session.spend.collectAsState()
    var typed by remember { mutableStateOf("") }
    val toggle = rememberWalkToggle(walk, settings)
    val list = rememberLazyListState()
    LaunchedEffect(turns.size) { if (turns.isNotEmpty()) list.animateScrollToItem(turns.lastIndex) }
    val send: () -> Unit = {
        session.send(typed)
        typed = ""
    }

    TopBar("Walk mode", onBack = onBack)
    Column(Modifier.weight(1f).padding(16.dp)) {
        Text(
            when (state) {
                VoiceSession.State.Idle -> if (apiKey == null) "Add an OpenAI API key in Settings first." else "Off."
                VoiceSession.State.Connecting -> "Connecting…"
                VoiceSession.State.Live -> "Listening — speak or type. Voice: ${RealtimeProtocol.voiceLabel(voice)}" +
                    (output?.let { " · Audio: $it" } ?: "")
                VoiceSession.State.Error -> "Stopped."
            },
            color = WC.Muted, fontSize = 13.sp,
        )
        // Stays after Stop, so the cost of the conversation that just ended can still be read.
        if (spend.tokens > 0) {
            Text(
                "This conversation: ${UsageFormat.tokens(spend.tokens)} tokens · about ${UsageFormat.usd(spend.usd)}",
                color = WC.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
            )
        }
        problem?.let { p ->
            val hint = if (p.startsWith("Incorrect API key")) "\nCheck the key in Settings → Voice assistant." else ""
            Text(p + hint, color = WC.Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f), state = list) {
            items(turns) { turn ->
                when {
                    turn.tool -> Text(
                        "· ${turn.text}", color = WC.Muted, fontSize = 13.sp, fontStyle = FontStyle.Italic,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                    else -> Text(
                        (if (turn.fromUser) "You: " else "Walkcode: ") + turn.text,
                        color = if (turn.fromUser) WC.Fg else WC.Accent,
                        fontSize = 15.sp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    )
                }
            }
        }
        if (state == VoiceSession.State.Live) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    placeholder = { Text("Type instead of speaking", color = WC.Off) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.weight(1f),
                )
                Pill("Send", onClick = if (typed.isNotBlank()) send else null, hot = typed.isNotBlank())
            }
        }
        val on = state == VoiceSession.State.Live || state == VoiceSession.State.Connecting
        PrimaryButton(if (on) "Stop walk mode" else "Start walk mode", ghost = on, enabled = on || apiKey != null, onClick = toggle)
    }
}
