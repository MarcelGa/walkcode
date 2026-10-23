package dev.walkcode.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.walkcode.app.data.AppSettings
import dev.walkcode.app.data.Session
import dev.walkcode.app.data.displayName
import dev.walkcode.app.monitor.Battery
import dev.walkcode.app.data.Workspace
import dev.walkcode.app.data.WalkcodeRepository
import dev.walkcode.app.monitor.Monitor
import dev.walkcode.app.session.SessionService
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.walkcode.app.ssh.HostKeyMismatchException
import dev.walkcode.app.terminal.ScreenCache
import kotlinx.coroutines.launch

/** What the app knows about which of a workspace's sessions are alive on its host. */
private sealed interface HostState {
    data object Checking : HostState
    data class Known(val running: Set<String>) : HostState
    data class Unreachable(val reason: String) : HostState
}

fun describeFailure(e: Throwable): String = when (e) {
    is HostKeyMismatchException ->
        "Host key changed (expected ${e.expected.fingerprint}, got ${e.observed.fingerprint}). " +
            "Forget the host key in the host settings only if you expect this."
    else -> e.message ?: e::class.java.simpleName
}

@Composable
fun ColumnScope.WorkspaceScreen(
    repo: WalkcodeRepository,
    service: SessionService,
    monitor: Monitor,
    workspaceId: Long,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpenSession: (sessionId: Long, resume: Boolean) -> Unit,
) {
    val workspaces by repo.workspaces.collectAsState(emptyList())
    val hosts by repo.hosts.collectAsState(emptyList())
    val projects by repo.projects.collectAsState(emptyList())
    val presets by repo.agentPresets.collectAsState(emptyList())
    val sessions by repo.sessionsFor(workspaceId).collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    val activity by monitor.activity.collectAsState()
    // Alerts need the notification permission; ask the first time a session is watched.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val context = LocalContext.current

    val workspace = workspaces.firstOrNull { it.id == workspaceId }
    val host = workspace?.let { w -> hosts.firstOrNull { it.id == w.hostId } }
    val preset = workspace?.let { w -> presets.firstOrNull { it.id == w.agentPresetId } }
    val project = workspace?.let { w -> projects.firstOrNull { it.id == w.projectId } }

    var state by remember { mutableStateOf<HostState>(HostState.Checking) }
    var refresh by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<Session?>(null) }
    var renaming by remember { mutableStateOf<Session?>(null) }
    var askBattery by remember { mutableStateOf(false) }

    LaunchedEffect(host?.id, refresh) {
        val h = host ?: return@LaunchedEffect
        state = HostState.Checking
        state = try {
            val panes = service.runningSessions(h)
            for ((name, title) in panes) repo.recordPaneTitle(name, title)
            HostState.Known(panes.keys)
        } catch (e: Exception) {
            HostState.Unreachable(describeFailure(e))
        }
    }

    TopBar(listOfNotNull(project?.name, workspace?.name).joinToString(" / ").ifEmpty { "Workspace" }, onBack = onBack) {
        BarAction("↻") { refresh++ }
        BarAction("✎", onEdit)
    }
    if (workspace == null) return
    Text(
        listOfNotNull(host?.name, preset?.name?.lowercase()).joinToString(" · "),
        color = WC.Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 14.dp, top = 8.dp, end = 14.dp),
    )
    Text(
        workspace.directory, color = WC.Blue, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
    )
    (state as? HostState.Unreachable)?.let {
        Text(it.reason, color = WC.Warn, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
    }

    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        if (sessions.isEmpty()) EmptyHint("No sessions yet. Start one below.")
        for (s in sessions) {
            val running = (state as? HostState.Known)?.running?.contains(s.tmuxName)
            val label = s.displayName()
            val act = activity[s.tmuxName]?.takeIf { s.watched }
            ListRow(
                title = label,
                subtitle = when (running) {
                    true -> when {
                        act == null -> "running"
                        act.active -> "active"
                        act.unread -> "quiet ${act.idleSec}s · unread"
                        else -> "quiet ${act.idleSec}s"
                    }
                    false -> "stopped"
                    null -> if (state is HostState.Checking) "checking…" else "status unknown"
                },
                leading = { Dot(when (running) { true -> WC.Accent; false -> WC.Off; null -> WC.Warn }) },
                onClick = { onOpenSession(s.id, false) },
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (act?.unread == true) UnreadDot()
                        Pill(if (s.watched) "Watching" else "Watch", hot = s.watched, onClick = {
                            scope.launch { repo.setSessionWatched(s.id, !s.watched) }
                            if (!s.watched && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            // Watching only works in the background if Doze may not suspend us; ask once.
                            val settings = AppSettings.get(context)
                            if (!s.watched && !settings.batteryPromptShown && !Battery.isUnrestricted(context)) {
                                settings.batteryPromptShown = true
                                askBattery = true
                            }
                        })
                        if (running == false && preset?.resumeCommand != null) Pill("▶ resume", onClick = { onOpenSession(s.id, true) })
                        Pill("✎", onClick = { renaming = s })
                        Pill("✕", onClick = { pendingDelete = s })
                    }
                },
            )
        }
    }
    PrimaryButton("+ New session", ghost = true, onClick = {
        scope.launch {
            val s = repo.createSession(workspaceId)
            onOpenSession(s.id, false)
        }
    })

    if (askBattery) {
        AlertDialog(
            onDismissRequest = { askBattery = false },
            title = { Text("Allow background activity?") },
            text = { Text("To keep watching sessions while the screen is off, Walkcode needs to be exempt from battery optimisation. You can change this later in Settings.") },
            confirmButton = {
                TextButton(onClick = { askBattery = false; context.startActivity(Battery.requestIntent(context)) }) { Text("Allow") }
            },
            dismissButton = { TextButton(onClick = { askBattery = false }) { Text("Not now") } },
        )
    }

    renaming?.let { s ->
        var text by remember(s.id) { mutableStateOf(s.label.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename session") },
            text = { FormField("Name", text, { text = it }, placeholder = s.autoTitle?.takeIf { it.isNotBlank() } ?: "#${s.ordinal + 1}") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repo.renameSession(s.id, text) }
                    renaming = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    pendingDelete?.let { s ->
        val alive = (state as? HostState.Known)?.running?.contains(s.tmuxName) == true
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(if (alive) "Kill session?" else "Remove session?") },
            text = { Text(if (alive) "The agent running in it will be stopped." else "Only the local entry is removed.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        if (alive && host != null) runCatching { service.killSession(host, s) }
                        ScreenCache.of(context).delete(s.id)
                        repo.deleteSession(s)
                        refresh++
                    }
                }) { Text(if (alive) "Kill" else "Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}


@Composable
fun WorkspaceEditorScreen(
    repo: WalkcodeRepository,
    workspaceId: Long?,
    defaultProjectId: Long?,
    onDone: () -> Unit,
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val hosts by repo.hosts.collectAsState(emptyList())
    val projects by repo.projects.collectAsState(emptyList())
    val presets by repo.agentPresets.collectAsState(emptyList())

    var loaded by remember { mutableStateOf(workspaceId == null) }
    var existing by remember { mutableStateOf<Workspace?>(null) }
    var name by remember { mutableStateOf("") }
    var hostId by remember { mutableStateOf<Long?>(null) }
    var projectId by remember { mutableStateOf(defaultProjectId) }
    var presetId by remember { mutableStateOf<Long?>(1L) }
    var directory by remember { mutableStateOf("") }
    var args by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(workspaceId) {
        if (workspaceId != null) {
            repo.workspace(workspaceId)?.let { w ->
                existing = w; name = w.name; hostId = w.hostId; projectId = w.projectId
                presetId = w.agentPresetId; directory = w.directory; args = w.args.orEmpty()
            }
            loaded = true
        }
    }
    LaunchedEffect(hosts) { if (hostId == null) hostId = hosts.firstOrNull()?.id }

    val valid = name.isNotBlank() && directory.isNotBlank() && hostId != null

    TopBar(if (workspaceId == null) "New workspace" else "Edit workspace", onBack = onDone)
    if (!loaded) return
    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (hosts.isEmpty()) EmptyHint("Add a host first (Hosts tab).")
        FormField("Name", name, { name = it }, "TollBff")
        Picker("Project", projects.firstOrNull { it.id == projectId }, projects, { it.name }, { projectId = it?.id }, noneLabel = "No project")
        Picker("Host", hosts.firstOrNull { it.id == hostId }, hosts, { it.name }, { if (it != null) hostId = it.id })
        FormField("Directory", directory, { directory = it }, "~/src/tollbff", mono = true)
        Picker("Agent", presets.firstOrNull { it.id == presetId }, presets, { it.name }, { presetId = it?.id }, noneLabel = "Plain shell")
        FormField("Args", args, { args = it }, "optional", mono = true)
        PrimaryButton("Save", enabled = valid, onClick = {
            scope.launch {
                val base = existing
                val argsV = args.trim().ifEmpty { null }
                if (base == null) {
                    repo.addWorkspace(name, hostId!!, directory, projectId, presetId, argsV)
                } else {
                    // The slug (and so the multiplexer session names) stays fixed on rename.
                    repo.updateWorkspace(base.copy(name = name.trim(), hostId = hostId!!, directory = directory.trim(), projectId = projectId, agentPresetId = presetId, args = argsV))
                }
                onDone()
            }
        })
        existing?.let { w ->
            if (confirmDelete) {
                PrimaryButton("Really delete (sessions keep running on the host)", ghost = true, onClick = {
                    scope.launch { repo.deleteWorkspace(w); onDeleted() }
                })
            } else {
                PrimaryButton("Delete workspace", ghost = true, onClick = { confirmDelete = true })
            }
        }
    }
}
