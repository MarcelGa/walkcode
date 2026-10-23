package dev.walkcode.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.ContextCompat
import dev.walkcode.app.data.AppSettings
import dev.walkcode.app.walk.Dictation
import dev.walkcode.app.walk.ResponseExtractor
import dev.walkcode.app.walk.Speaker
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import dev.walkcode.app.data.WalkcodeRepository
import dev.walkcode.app.data.displayName
import dev.walkcode.app.monitor.Monitor
import dev.walkcode.app.session.Attachment
import kotlinx.coroutines.launch
import dev.walkcode.app.session.SessionService
import dev.walkcode.app.terminal.ExtraKey
import dev.walkcode.app.terminal.ScreenCache
import dev.walkcode.app.terminal.SshTerminalTransport
import dev.walkcode.app.terminal.TerminalController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface TermState {
    data class Connecting(val detail: String) : TermState
    data object Attached : TermState
    data class Failed(val reason: String) : TermState
    data class Ended(val reason: String) : TermState
}

private const val TRANSCRIPT_ROWS = 2000

/** Connects to the session's host, starts the agent if the session is missing, and attaches. */
@Composable
fun ColumnScope.TerminalScreen(
    repo: WalkcodeRepository,
    service: SessionService,
    monitor: Monitor,
    settings: AppSettings,
    sessionId: Long,
    resume: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val workspaces by repo.workspaces.collectAsState(emptyList())
    val hosts by repo.hosts.collectAsState(emptyList())
    val presets by repo.agentPresets.collectAsState(emptyList())
    val sessions by repo.allSessions.collectAsState(emptyList())

    val session = sessions.firstOrNull { it.id == sessionId }
    val workspace = session?.let { s -> workspaces.firstOrNull { it.id == s.workspaceId } }
    val host = workspace?.let { w -> hosts.firstOrNull { it.id == w.hostId } }
    val preset = workspace?.let { w -> presets.firstOrNull { it.id == w.agentPresetId } }

    var state by remember { mutableStateOf<TermState>(TermState.Connecting("")) }
    var title by remember { mutableStateOf<String?>(null) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var attachment by remember { mutableStateOf<Attachment?>(null) }

    // Last screen of this session from an earlier visit, shown while the reconnect runs.
    val screens = remember(context) { ScreenCache.of(context) }
    val snapshot by produceState<String?>(null, sessionId) {
        value = withContext(Dispatchers.IO) { screens.load(sessionId) }?.takeIf { it.isNotBlank() }
    }

    val terminalView = remember { TerminalView(context, null) }
    // The controller is built once, so its listener reads through a bridge that is
    // re-pointed at the current composition's state setters.
    val bridge = remember { Bridge() }
    bridge.onModifiers = { c, a -> ctrl = c; alt = a }
    bridge.onTitle = { title = it }
    bridge.onFinished = { state = TermState.Ended(it) }
    val walk = remember { WalkState() }
    val controller = remember {
        TerminalController(context, terminalView, object : TerminalController.Listener {
            override fun onTitleChanged(title: String?) = bridge.onTitle(title)
            override fun onFinished(session: TerminalSession) =
                bridge.onFinished(session.exitReason ?: "session ended (exit ${session.exitStatus})")
            override fun onModifiersChanged(ctrl: Boolean, alt: Boolean) = bridge.onModifiers(ctrl, alt)
            override fun onScreenChanged() {
                walk.lastChange = SystemClock.uptimeMillis(); walk.dirty = true; walk.screenChanged = true
            }
        })
    }

    // --- Walk mode: read responses aloud, dictate input ---------------------------------------
    val scope = rememberCoroutineScope()
    val walkOn by settings.walkMode.collectAsState()
    val autoSend by settings.dictationAutoSend.collectAsState()
    var partial by remember { mutableStateOf<String?>(null) }
    val speaker = remember { Speaker(context, settings) }
    val dictation = remember {
        Dictation(
            context,
            settings,
            onPartial = { partial = it },
            onResult = { text ->
                partial = null
                scope.launch { walk.send(text) }
            },
            onFailure = { msg ->
                partial = null
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            },
        )
    }
    walk.sendText = { text ->
        controller.sendText(text)
        if (settings.dictationAutoSend.value) { delay(SUBMIT_DELAY_MS); controller.submit() }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { speaker.stop(); dictation.start(); partial = "" }
        else Toast.makeText(context, "Microphone permission denied", Toast.LENGTH_SHORT).show()
    }
    fun toggleDictation() {
        if (partial != null) { dictation.stop(); return }
        if (!dictation.available()) {
            Toast.makeText(context, "No speech recognizer on this device", Toast.LENGTH_LONG).show()
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            // Barge-in: stop talking before listening or the mic hears the phone.
            speaker.stop(); dictation.start(); partial = ""
        } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    DisposableEffect(Unit) {
        onDispose { dictation.cancel(); speaker.shutdown() }
    }
    LaunchedEffect(walkOn) { if (!walkOn) speaker.stop() }

    // Once the screen has been quiet for a moment after changing, speak what is new. Reads the
    // multiplexer's pane (with history) rather than the emulator, which only holds the live screen.
    LaunchedEffect(walkOn, attachment, session?.id, host?.id) {
        val a = attachment ?: return@LaunchedEffect
        val s = session ?: return@LaunchedEffect
        val h = host ?: return@LaunchedEffect
        if (!walkOn) return@LaunchedEffect
        walk.baseline = runCatching { service.capture(a.client, h, s) }.getOrNull()
        walk.dirty = false
        while (isActive) {
            delay(POLL_MS)
            if (!walk.dirty || SystemClock.uptimeMillis() - walk.lastChange < SETTLE_MS) continue
            walk.dirty = false
            val now = runCatching { service.capture(a.client, h, s) }.getOrNull() ?: continue
            val before = walk.baseline
            walk.baseline = now
            if (before == null) continue
            val text = ResponseExtractor.newText(before, now, walk.sent)
            if (text.isNotBlank()) speaker.speak(text)
        }
    }
    // Writes the screen to disk when it changed, so the cache survives the app being killed.
    LaunchedEffect(session?.id, attachment) {
        val id = session?.id ?: return@LaunchedEffect
        if (attachment == null) return@LaunchedEffect
        while (isActive) {
            delay(SNAPSHOT_MS)
            if (!walk.screenChanged) continue
            walk.screenChanged = false
            val text = terminalView.screenText() ?: continue
            withContext(Dispatchers.IO) { screens.save(id, text) }
        }
    }
    walk.capture = {
        val a = attachment; val s = session; val h = host
        if (a != null && s != null && h != null) runCatching { service.capture(a.client, h, s) }.getOrNull() else null
    }
    walk.active = walkOn

    LaunchedEffect(session?.id, host?.id, attempt) {
        val s = session ?: return@LaunchedEffect
        val w = workspace ?: return@LaunchedEffect
        val h = host ?: return@LaunchedEffect
        state = TermState.Connecting("")
        try {
            val a = service.launch(h, w, s, resume = resume && attempt == 0, onAttempt = { addr -> state = TermState.Connecting(addr) })
            attachment = a
            terminalView.attachSession(TerminalSession(SshTerminalTransport(a.client, a.command, a.pty), TRANSCRIPT_ROWS, controller))
            state = TermState.Attached
            controller.showKeyboard()
        } catch (e: Exception) {
            state = TermState.Failed(describeFailure(e))
        }
    }

    // The session on screen counts as viewed: no alerts for it, and leaving clears unread.
    DisposableEffect(session?.tmuxName) {
        val name = session?.tmuxName
        monitor.setFocused(name)
        onDispose {
            monitor.setFocused(null)
            if (name != null) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { repo.markSessionViewed(sessionId) }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            controller.view.screenText()?.let { text ->
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { screens.save(sessionId, text) }
            }
            controller.view.currentSession?.finishIfRunning()
            attachment?.let { service.close(it.client) }
        }
    }

    TopBar(listOfNotNull(workspace?.name, session?.displayName()).joinToString(" / "), onBack = onBack)
    Text(
        listOfNotNull(host?.name, workspace?.directory, preset?.name?.lowercase(), title).joinToString(" · "),
        color = WC.Muted, fontSize = 12.sp, maxLines = 1, fontFamily = FontFamily.Monospace,
        modifier = Modifier.fillMaxWidth().background(WC.Panel2).padding(horizontal = 14.dp, vertical = 6.dp),
    )

    Box(Modifier.weight(1f).fillMaxWidth().background(androidx.compose.ui.graphics.Color(0xFF0A0E13))) {
        AndroidView(factory = { terminalView }, modifier = Modifier.fillMaxWidth().padding(2.dp))
        when (val st = state) {
            is TermState.Connecting -> {
                val connecting = "Connecting" + if (st.detail.isNotEmpty()) " to ${st.detail}…" else "…"
                val cached = snapshot
                if (cached == null) {
                    Overlay(connecting, null, null)
                } else {
                    // Not a full overlay: the cached text stays readable under the banner.
                    CachedScreen(cached)
                    Text(
                        connecting, color = WC.Fg, fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.BottomCenter).background(WC.Panel2).padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
            is TermState.Failed -> Stalled(st.reason, snapshot, "Retry") { attempt++ }
            is TermState.Ended -> Stalled(st.reason, snapshot, "Reconnect") { attempt++ }
            TermState.Attached -> {}
        }
    }
    partial?.let {
        Text(
            if (it.isEmpty()) "Listening… tap Done when finished" else it,
            color = WC.Accent, fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().background(WC.Panel2).padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
    Row(
        Modifier.fillMaxWidth().background(WC.Panel).padding(start = 6.dp, top = 6.dp, end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val accent = ButtonDefaults.filledTonalButtonColors(containerColor = WC.Accent, contentColor = androidx.compose.ui.graphics.Color(0xFF04120A))
        val idle = ButtonDefaults.filledTonalButtonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF1B232E), contentColor = WC.Fg)
        FilledTonalButton(
            onClick = { settings.setWalkMode(!walkOn) },
            colors = if (walkOn) accent else idle,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        ) { Text(if (walkOn) "Walk: on" else "Walk: off", fontSize = 13.sp) }
        FilledTonalButton(
            onClick = ::toggleDictation,
            colors = if (partial != null) accent else idle,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        ) { Text(if (partial != null) "Done" else "Dictate", fontSize = 13.sp) }
        if (walkOn) {
            FilledTonalButton(
                onClick = { speaker.stop() },
                colors = idle,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            ) { Text("Hush", fontSize = 13.sp) }
        }
    }
    Row(
        Modifier.fillMaxWidth().background(WC.Panel).horizontalScroll(rememberScrollState()).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (key in ExtraKey.entries) {
            val active = (key == ExtraKey.CTRL && ctrl) || (key == ExtraKey.ALT && alt)
            FilledTonalButton(
                onClick = { key.press(controller) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                colors = if (active) ButtonDefaults.filledTonalButtonColors(containerColor = WC.Accent, contentColor = androidx.compose.ui.graphics.Color(0xFF04120A))
                else ButtonDefaults.filledTonalButtonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF1B232E), contentColor = WC.Fg),
            ) { Text(key.label, fontSize = 13.sp) }
        }
    }
}

/**
 * A failed or ended connection: the last saved screen stays readable, with the reason and the
 * retry action in a bar at the bottom. Without a saved screen this is the full overlay.
 */
@Composable
private fun BoxScope.Stalled(reason: String, cached: String?, action: String, onAction: () -> Unit) {
    if (cached == null) {
        Overlay(reason, action, onAction)
        return
    }
    CachedScreen(cached)
    Row(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(WC.Panel2).padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(reason, color = WC.Warn, fontSize = 13.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) { Text(action, color = WC.Accent) }
    }
}

/** The last saved screen, bottom-aligned and unwrapped so it lines up like the live terminal. */
@Composable
private fun CachedScreen(text: String) {
    Column(
        Modifier.fillMaxSize().padding(4.dp)
            .verticalScroll(rememberScrollState(Int.MAX_VALUE))
            .horizontalScroll(rememberScrollState()),
    ) {
        Text(text, color = WC.Fg, fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false)
    }
}

/** Visible screen plus scrollback as plain text; null until the emulator exists. */
private fun TerminalView.screenText(): String? =
    currentSession?.emulator?.screen?.transcriptTextWithFullLinesJoined

@Composable
private fun Overlay(message: String, action: String?, onAction: (() -> Unit)?) {
    Box(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color(0xCC0A0E13)).padding(24.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = WC.Fg)
            if (action != null && onAction != null) PrimaryButton(action, onClick = onAction)
        }
    }
}

/** How often a changed screen is written to the cache while attached. */
private const val SNAPSHOT_MS = 2000L
private const val POLL_MS = 500L
/** Screen quiet this long after a change counts as "the agent finished talking". */
private const val SETTLE_MS = 2500L
/** A CR in the same burst as the text reads as a pasted newline to some agents; give it a beat. */
private const val SUBMIT_DELAY_MS = 250L

/** Walk-mode bookkeeping shared between the terminal callbacks and the watcher loop. */
private class WalkState {
    @Volatile var lastChange = 0L
    @Volatile var dirty = false
    var active = false
    /** Set on every emulator update; cleared when the screen is written to the cache. */
    @Volatile var screenChanged = false
    /** Pane text at the last point whose output was already handled (spoken or ours). */
    var baseline: String? = null
    /** Last dictated text, so its echo in the agent's input line is not read back. */
    var sent: String? = null
    var capture: suspend () -> String? = { null }
    var sendText: suspend (String) -> Unit = {}

    suspend fun send(text: String) {
        // Re-baseline just before typing so only the agent's reply counts as new.
        if (active) { capture()?.let { baseline = it }; sent = text; dirty = false }
        sendText(text)
    }
}

private class Bridge {
    var onModifiers: (Boolean, Boolean) -> Unit = { _, _ -> }
    var onTitle: (String?) -> Unit = {}
    var onFinished: (String) -> Unit = {}
}
