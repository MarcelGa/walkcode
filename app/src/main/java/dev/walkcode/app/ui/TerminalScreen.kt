package dev.walkcode.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import dev.walkcode.app.data.WalkcodeRepository
import dev.walkcode.app.data.displayName
import dev.walkcode.app.monitor.Monitor
import dev.walkcode.app.session.Attachment
import kotlinx.coroutines.launch
import dev.walkcode.app.session.SessionService
import dev.walkcode.app.terminal.ExtraKey
import dev.walkcode.app.terminal.SshTerminalTransport
import dev.walkcode.app.terminal.TerminalController

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

    val terminalView = remember { TerminalView(context, null) }
    // The controller is built once, so its listener reads through a bridge that is
    // re-pointed at the current composition's state setters.
    val bridge = remember { Bridge() }
    bridge.onModifiers = { c, a -> ctrl = c; alt = a }
    bridge.onTitle = { title = it }
    bridge.onFinished = { state = TermState.Ended(it) }
    val controller = remember {
        TerminalController(context, terminalView, object : TerminalController.Listener {
            override fun onTitleChanged(title: String?) = bridge.onTitle(title)
            override fun onFinished(session: TerminalSession) =
                bridge.onFinished(session.exitReason ?: "session ended (exit ${session.exitStatus})")
            override fun onModifiersChanged(ctrl: Boolean, alt: Boolean) = bridge.onModifiers(ctrl, alt)
        })
    }

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
            is TermState.Connecting -> Overlay("Connecting" + if (st.detail.isNotEmpty()) " to ${st.detail}…" else "…", null, null)
            is TermState.Failed -> Overlay(st.reason, "Retry") { attempt++ }
            is TermState.Ended -> Overlay(st.reason, "Reconnect") { attempt++ }
            TermState.Attached -> {}
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

@Composable
private fun Overlay(message: String, action: String?, onAction: (() -> Unit)?) {
    Box(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color(0xCC0A0E13)).padding(24.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = WC.Fg)
            if (action != null && onAction != null) PrimaryButton(action, onClick = onAction)
        }
    }
}

private class Bridge {
    var onModifiers: (Boolean, Boolean) -> Unit = { _, _ -> }
    var onTitle: (String?) -> Unit = {}
    var onFinished: (String) -> Unit = {}
}
