package dev.walkcode.app.ui

import android.content.ComponentName
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.produceState
import dev.walkcode.app.walk.Speaker
import dev.walkcode.app.walk.VoiceCatalog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.walkcode.app.data.AppSettings
import dev.walkcode.app.data.WalkcodeRepository
import dev.walkcode.app.monitor.Battery

private val QuietChoices = listOf(5, 10, 15, 30, 60, 120)

@Composable
fun ColumnScope.SettingsScreen(repo: WalkcodeRepository, settings: AppSettings) {
    val context = LocalContext.current
    val quietSec by settings.quietAfterSec.collectAsState()
    val quietOn by settings.quietAlerts.collectAsState()
    val walkOn by settings.walkMode.collectAsState()
    val autoSend by settings.dictationAutoSend.collectAsState()

    // The exemption is granted in a system dialog, so re-read it whenever we come back.
    var unrestricted by remember { mutableStateOf(Battery.isUnrestricted(context)) }
    DisposableEffect(context) {
        val lifecycle = (context as ComponentActivity).lifecycle
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) unrestricted = Battery.isUnrestricted(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    TopBar("Settings")
    // weight: take only the space above the tab bar, otherwise a long list pushes the bar off screen.
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        SectionLabel("Alerts")
        ListRow(
            title = "Quiet alerts",
            subtitle = "Notify when a watched session has unread output and goes silent",
            subtitleLines = 3,
            trailing = { Switch(checked = quietOn, onCheckedChange = settings::setQuietAlerts) },
        )
        Text("Quiet after", color = WC.Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 20.dp, top = 8.dp))
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (s in (QuietChoices + quietSec).distinct().sorted()) {
                Pill("${s}s", hot = s == quietSec, onClick = { settings.setQuietAfterSec(s) })
            }
        }

        SectionLabel("Walk mode")
        ListRow(
            title = "Read responses aloud",
            subtitle = "Speak new agent output once it goes quiet (also the Walk button in a terminal)",
            subtitleLines = 3,
            trailing = { Switch(checked = walkOn, onCheckedChange = settings::setWalkMode) },
        )
        ListRow(
            title = "Send dictation automatically",
            subtitle = "Press Enter after the dictated text; off leaves it in the prompt for you to check",
            subtitleLines = 3,
            trailing = { Switch(checked = autoSend, onCheckedChange = settings::setDictationAutoSend) },
        )

        VoiceSettings(settings)

        SectionLabel("Background")
        ListRow(
            title = "Battery optimisation",
            subtitle = if (unrestricted) "Unrestricted — monitoring can run while the screen is off"
            else "Optimised — Android may suspend monitoring while the screen is off",
            subtitleLines = 3,
            trailing = { if (!unrestricted) Pill("Allow", onClick = { context.startActivity(Battery.requestIntent(context)) }) },
        )

        BackupSection(repo, settings)
    }
}

private val RateChoices = listOf(0.8f, 1.0f, 1.2f, 1.5f, 2.0f)

/** Null id means "phone default". */
@Composable
private fun ChoiceDialog(
    title: String,
    options: List<VoiceCatalog.Choice>,
    selected: String?,
    onPick: (String?) -> Unit,
    onTest: ((String) -> Unit)? = null,
    onDownload: ((String) -> Unit)? = null,
    downloading: Map<String, Int> = emptyMap(),
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            var query by remember { mutableStateOf("") }
            Column {
                if (options.size > 6) FormField("Filter", query, { query = it }, placeholder = "e.g. english female")
                Column(Modifier.verticalScroll(rememberScrollState())) {
                // The default entry only makes sense in the unfiltered list.
                val shown = VoiceCatalog.filter(options, query)
                val all = if (query.isBlank()) listOf(VoiceCatalog.Choice("", "Phone default")) + shown else shown
                if (all.isEmpty()) Text("No match", color = WC.Muted, modifier = Modifier.padding(12.dp))
                for (o in all) {
                    val id = o.id.ifEmpty { null }
                    ListRow(
                        title = o.label,
                        onClick = { onPick(id); onDismiss() },
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                if (onTest != null && id != null) Pill("Test", onClick = { onTest(id) })
                                if (onDownload != null && id != null && o.missing) {
                                    val progress = downloading[id]
                                    when {
                                        progress == null -> Pill("Download", onClick = { onDownload(id) })
                                        progress > 0 -> Text("$progress%", color = WC.Muted)
                                        else -> Text("…", color = WC.Muted)
                                    }
                                }
                                if (id == selected) Text("●", color = WC.Accent)
                            }
                        },
                    )
                }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun VoiceSettings(settings: AppSettings) {
    val context = LocalContext.current
    val engine by settings.ttsEngine.collectAsState()
    val voice by settings.ttsVoice.collectAsState()
    val rate by settings.ttsRate.collectAsState()
    val recognizer by settings.recognizer.collectAsState()
    val lang by settings.recognitionLang.collectAsState()
    var dialog by remember { mutableStateOf<String?>(null) }

    // Loaded off a throwaway TTS instance; reloaded when the engine changes because voices belong to one engine.
    val info by produceState<VoiceCatalog.TtsInfo?>(null, engine) { value = VoiceCatalog.tts(context, engine) }
    val recognizers = remember { VoiceCatalog.recognizers(context) }
    val speaker = remember { Speaker(context, settings) }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }

    // Reloaded when the recognizer changes (each has its own installed languages) and after a download finishes.
    var refresh by remember { mutableIntStateOf(0) }
    val languages by produceState<List<VoiceCatalog.Choice>?>(null, recognizer, refresh) {
        value = VoiceCatalog.dictationLanguages(context, recognizer?.let(ComponentName::unflattenFromString))
    }
    // Tag to percent done (0 until the first progress report) for each language downloading now.
    val downloading = remember { mutableStateMapOf<String, Int>() }
    val download: (String) -> Unit = { tag ->
        downloading[tag] = 0
        val chosen = recognizer?.let(ComponentName::unflattenFromString)
        VoiceCatalog.downloadLanguage(context, chosen, tag, onProgress = { downloading[tag] = it }) { ok ->
            downloading.remove(tag)
            if (!ok) Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
            refresh++
        }
    }

    val engines = info?.engines.orEmpty()
    val voices = info?.voices.orEmpty().sortedBy { VoiceCatalog.voiceLabel(it) }

    SectionLabel("Voice")
    ListRow("Speech engine", engines.firstOrNull { it.id == engine }?.label ?: "Phone default", onClick = { dialog = "engine" })
    ListRow(
        "Voice",
        voices.firstOrNull { it.name == voice }?.let(VoiceCatalog::voiceLabel) ?: "Engine default",
        onClick = { dialog = "voice" },
    )
    Text("Speed", color = WC.Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 20.dp, top = 8.dp))
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (r in (RateChoices + rate).distinct().sorted()) Pill("${r}x", hot = r == rate, onClick = { settings.setTtsRate(r) })
        Pill("Test", onClick = { speaker.speak("This is how responses will sound while you walk.") })
    }
    ListRow(
        "Dictation recognizer",
        recognizers.firstOrNull { it.id == recognizer }?.label ?: "Phone default (on-device if available)",
        onClick = { dialog = "recognizer" },
    )
    ListRow(
        "Dictation language",
        languages.orEmpty().firstOrNull { it.id == lang }?.label ?: lang ?: "Phone language",
        onClick = { dialog = "lang" },
    )

    when (dialog) {
        "engine" -> ChoiceDialog("Speech engine", engines, engine, settings::setTtsEngine) { dialog = null }
        "voice" -> ChoiceDialog(
            "Voice", voices.map { VoiceCatalog.Choice(it.name, VoiceCatalog.voiceLabel(it)) }, voice, settings::setTtsVoice,
            onTest = { name -> voices.firstOrNull { it.name == name }?.let { speaker.preview(VoiceCatalog.sampleFor(it), name) } },
        ) { speaker.stop(); dialog = null }
        "recognizer" -> ChoiceDialog("Dictation recognizer", recognizers, recognizer, settings::setRecognizer) { dialog = null }
        "lang" -> ChoiceDialog(
            "Dictation language", languages.orEmpty(), lang, settings::setRecognitionLang,
            onDownload = download, downloading = downloading,
        ) { dialog = null }
    }
}
