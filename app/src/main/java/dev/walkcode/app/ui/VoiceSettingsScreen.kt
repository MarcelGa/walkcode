package dev.walkcode.app.ui

import android.content.ComponentName
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import dev.walkcode.app.data.AppSettings
import dev.walkcode.app.data.UsageTotal
import dev.walkcode.app.data.WalkcodeRepository
import dev.walkcode.app.voice.OpenAiKey
import dev.walkcode.app.voice.RealtimeProtocol
import dev.walkcode.app.voice.UsageFormat
import dev.walkcode.app.walk.VoiceCatalog
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Settings > Voice: dictation in the terminal bar, the walk-mode assistant and what it has cost. */
@Composable
fun ColumnScope.VoiceSettingsScreen(repo: WalkcodeRepository, settings: AppSettings, onBack: () -> Unit) {
    val autoSend by settings.dictationAutoSend.collectAsState()

    TopBar("Voice", onBack = onBack)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        // Walk mode itself is the voice assistant, switched on from the Projects screen.
        SectionLabel("Dictation")
        ListRow(
            title = "Send dictation automatically",
            subtitle = "Press Enter after the dictated text; off leaves it in the prompt for you to check",
            subtitleLines = 3,
            trailing = { Switch(checked = autoSend, onCheckedChange = settings::setDictationAutoSend) },
        )
        DictationSettings(settings)

        RealtimeSettings(settings)

        UsageSection(repo)
    }
}

/** Walk-mode spend from what the server reported, priced at list rates; OpenAI's bill is the real figure. */
@Composable
private fun ColumnScope.UsageSection(repo: WalkcodeRepository) {
    val rows by repo.voiceUsage.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var confirmReset by remember { mutableStateOf(false) }
    val today = LocalDate.now()

    SectionLabel("Usage")
    Text(
        "Estimated from the tokens OpenAI reports after each reply, at list prices. Your OpenAI billing page is what is charged.",
        color = WC.Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
    )
    for ((title, from) in listOf("Today" to today.toString(), "This month" to today.withDayOfMonth(1).toString(), "All time" to null)) {
        val t = UsageTotal.of(rows, from)
        ListRow(
            title = title,
            subtitle = "${UsageFormat.tokens(t.tokens)} tokens" +
                (if (t.cachedIn > 0) ", ${UsageFormat.tokens(t.cachedIn)} of them cached (cheap)" else ""),
            trailing = { Text(UsageFormat.usd(t.usd), color = WC.Fg, fontSize = 15.sp) },
        )
    }
    // This month by model, so the saving from a cheaper model is visible.
    val month = today.withDayOfMonth(1).toString()
    rows.filter { it.day >= month }.groupBy { it.model }.forEach { (model, r) ->
        val t = UsageTotal.of(r)
        Text(
            "${RealtimeProtocol.modelLabel(model)}: ${UsageFormat.usd(t.usd)} · ${UsageFormat.tokens(t.tokens)} tokens",
            color = WC.Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp),
        )
    }
    if (rows.isNotEmpty()) PrimaryButton("Reset usage", ghost = true, onClick = { confirmReset = true })

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset usage?") },
            text = { Text("Clears the totals on this phone. Your OpenAI account is not affected.") },
            confirmButton = { TextButton(onClick = { scope.launch { repo.clearVoiceUsage() }; confirmReset = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

/** Voice assistant (walk mode): the model, the preset voice and the OpenAI key (kept on this phone only). */
@Composable
private fun ColumnScope.RealtimeSettings(settings: AppSettings) {
    val savedKey by settings.openAiKey.collectAsState()
    val voice by settings.realtimeVoice.collectAsState()
    val model by settings.realtimeModel.collectAsState()
    var draft by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    // Also on the keyboard's Done key: with the keyboard open the Save button sits behind it.
    val save: () -> Unit = {
        if (draft.isNotBlank()) {
            settings.setOpenAiKey(draft)
            draft = ""
            focus.clearFocus()
        }
    }

    SectionLabel("Voice assistant")
    Text(
        "Walk mode on the Projects screen talks to this assistant about all your workspaces.",
        color = WC.Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
    )

    Text(
        "Model (audio price per 1M tokens, in / out). A change applies from the next start of walk mode.",
        color = WC.Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
    )
    for (m in RealtimeProtocol.MODELS) {
        ListRow(
            title = m.label,
            subtitle = m.priceLabel + if (m.id == RealtimeProtocol.CHEAPEST_MODEL) " — cheapest, weaker with functions" else "",
            onClick = { settings.setRealtimeModel(m.id) },
            trailing = { if (m.id == model) Text("●", color = WC.Accent) },
        )
    }

    RealtimeVoicePicker(
        label = "Default voice",
        selected = voice,
        onSelect = { v -> v?.let(settings::setRealtimeVoice) },
        apiKey = savedKey,
    )
    ListRow(
        title = "OpenAI API key",
        subtitle = savedKey?.let { "Saved: ${OpenAiKey.masked(it)} (${it.length} characters). On this phone only; never in config backups." }
            ?: "Not set. Needed for walk mode.",
        subtitleLines = 3,
        trailing = { if (savedKey != null) Pill("Remove", onClick = { settings.setOpenAiKey(null) }) },
    )
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text(if (savedKey != null) "Replace key" else "Paste key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { save() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    )
    PrimaryButton("Save key", enabled = draft.isNotBlank(), onClick = save)
}

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
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
private fun DictationSettings(settings: AppSettings) {
    val context = LocalContext.current
    val recognizer by settings.recognizer.collectAsState()
    val lang by settings.recognitionLang.collectAsState()
    var dialog by remember { mutableStateOf<String?>(null) }
    val recognizers = remember { VoiceCatalog.recognizers(context) }

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
        "recognizer" -> ChoiceDialog("Dictation recognizer", recognizers, recognizer, settings::setRecognizer) { dialog = null }
        "lang" -> ChoiceDialog(
            "Dictation language", languages.orEmpty(), lang, settings::setRecognitionLang,
            onDownload = download, downloading = downloading,
        ) { dialog = null }
    }
}
