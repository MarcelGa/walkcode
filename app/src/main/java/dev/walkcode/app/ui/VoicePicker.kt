package dev.walkcode.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import dev.walkcode.app.voice.RealtimeProtocol
import dev.walkcode.app.voice.VoicePreview
import java.io.File

/**
 * Dropdown of the Realtime preset voices with a play button on the field and on every entry, so
 * voices can be compared without closing the menu. The sample introduces itself as [assistantName]
 * (a workspace's assistant, e.g. "Karl"), or by the voice's own name when there is none.
 *
 * With [noneLabel] the list starts with a "no voice of its own" entry that selects null; [noneVoice]
 * is the voice null stands for (the Settings default), so that entry can be played too.
 */
@Composable
fun RealtimeVoicePicker(
    label: String,
    selected: String?,
    onSelect: (String?) -> Unit,
    apiKey: String?,
    assistantName: String? = null,
    noneLabel: String? = null,
    noneVoice: String? = null,
) {
    val scope = rememberCoroutineScope()
    // Private files, not the cache dir: Android may clear that, and each sample was paid for.
    val dir = File(LocalContext.current.filesDir, "voice-samples")
    val preview = remember { VoicePreview(scope, dir) }
    DisposableEffect(Unit) { onDispose { preview.stop() } }
    val state by preview.state.collectAsState()
    var open by remember { mutableStateOf(false) }

    val play: (String) -> Unit = { voice ->
        if (apiKey != null) {
            val name = assistantName?.takeIf { it.isNotBlank() } ?: RealtimeProtocol.voiceLabel(voice)
            preview.play(apiKey, voice, VoicePreview.phrase(name))
        }
    }
    val fieldText = selected?.let(RealtimeProtocol::voiceLabel) ?: noneLabel.orEmpty()
    val fieldVoice = selected ?: noneVoice

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                OutlinedTextField(
                    value = fieldText,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(label) },
                    trailingIcon = { Text("▾", color = WC.Muted) },
                    modifier = Modifier.fillMaxWidth(),
                )
                // The text field swallows clicks, so a transparent layer on top opens the menu.
                Box(Modifier.matchParentSize().clickable { open = true })
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    if (noneLabel != null) {
                        DropdownMenuItem(
                            text = { VoiceEntry(noneLabel, selected == null) },
                            onClick = { onSelect(null); open = false },
                            trailingIcon = noneVoice?.let { v -> { PlayButton(v, state, apiKey != null, play, preview::stop) } },
                        )
                    }
                    for (v in RealtimeProtocol.VOICES) {
                        DropdownMenuItem(
                            text = { VoiceEntry(RealtimeProtocol.voiceLabel(v), v == selected) },
                            onClick = { onSelect(v); open = false },
                            trailingIcon = { PlayButton(v, state, apiKey != null, play, preview::stop) },
                        )
                    }
                }
            }
            if (fieldVoice != null) {
                Box(Modifier.padding(start = 8.dp, top = 6.dp)) {
                    PlayButton(fieldVoice, state, apiKey != null, play, preview::stop)
                }
            }
        }
        val hint = when {
            apiKey == null -> "Add an OpenAI API key to hear the voices."
            state is VoicePreview.State.Failed -> (state as VoicePreview.State.Failed).message
            else -> null
        }
        if (hint != null) {
            Text(
                hint, fontSize = 12.sp, color = if (apiKey == null) WC.Muted else WC.Danger,
                modifier = Modifier.padding(start = 8.dp, top = 4.dp),
            )
        }
    }
}

@Composable
private fun VoiceEntry(text: String, chosen: Boolean) {
    Text(if (chosen) "$text  ●" else text, color = if (chosen) WC.Accent else WC.Fg)
}

/** Play, a spinner glyph while the sample downloads, stop while it plays. Disabled without a key. */
@Composable
private fun PlayButton(
    voice: String,
    state: VoicePreview.State,
    enabled: Boolean,
    onPlay: (String) -> Unit,
    onStop: () -> Unit,
) {
    val glyph = when (state) {
        VoicePreview.State.Loading(voice) -> "…"
        VoicePreview.State.Playing(voice) -> "■"
        else -> "▶"
    }
    val busy = glyph != "▶"
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(WC.Panel)
            .let { if (enabled) it.clickable { if (busy) onStop() else onPlay(voice) } else it },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = if (!enabled) WC.Off else if (busy) WC.Warn else WC.Accent, fontSize = 16.sp)
    }
}
