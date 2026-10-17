package dev.walkcode.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.walkcode.app.data.WalkcodeRepository
import dev.walkcode.app.monitor.Monitor
import dev.walkcode.app.monitor.MonitorService
import dev.walkcode.app.session.SessionService
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState

private sealed interface Route {
    data object Projects : Route
    data object Hosts : Route
    data class Project(val id: Long?) : Route
    data class Workspace(val id: Long) : Route
    data class WorkspaceEditor(val id: Long?, val projectId: Long?) : Route
    data class HostEditor(val id: Long?) : Route
    data class Terminal(val sessionId: Long, val resume: Boolean) : Route
}

private val Route.isTopLevel: Boolean get() = this == Route.Projects || this == Route.Hosts

@Composable
fun WalkcodeApp(pendingSession: Long? = null, onSessionOpened: () -> Unit = {}) {
    val context = LocalContext.current
    val repo = remember { WalkcodeRepository.get(context) }
    val service = remember { SessionService(repo) }
    val monitor = remember { Monitor.get(context) }
    // Top-level tabs replace each other; everything else stacks on the current tab.
    val stack = remember { mutableStateListOf<Route>(Route.Projects) }
    val current = stack.last()

    fun push(r: Route) { stack.add(r) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun selectTab(r: Route) { stack.clear(); stack.add(r) }

    BackHandler(enabled = stack.size > 1) { pop() }

    // Background monitoring runs while any session is watched, unless the OS paused it.
    val allSessions by repo.allSessions.collectAsState(emptyList())
    val paused by monitor.paused.collectAsState()
    val anyWatched = allSessions.any { it.watched }
    LaunchedEffect(anyWatched, paused) {
        if (anyWatched && !paused) MonitorService.start(context) else MonitorService.stop(context)
    }

    // Tapping an alert opens that session's terminal on top of its workspace.
    LaunchedEffect(pendingSession, allSessions) {
        val id = pendingSession ?: return@LaunchedEffect
        val s = allSessions.firstOrNull { it.id == id } ?: return@LaunchedEffect
        selectTab(Route.Projects)
        push(Route.Workspace(s.workspaceId))
        push(Route.Terminal(s.id, false))
        onSessionOpened()
    }

    WalkcodeTheme {
        Surface(Modifier.fillMaxSize(), color = WC.Panel2) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                when (val r = current) {
                    Route.Projects -> {
                        ProjectsScreen(repo, monitor, onOpenProject = { push(Route.Project(it)) })
                        Spacer1(Modifier.weight(1f))
                    }
                    Route.Hosts -> {
                        HostsScreen(repo, monitor, onOpenHost = { push(Route.HostEditor(it)) })
                        Spacer1(Modifier.weight(1f))
                    }
                    is Route.Project -> ProjectDetailScreen(
                        repo, monitor, r.id, onBack = ::pop,
                        onOpenWorkspace = { push(Route.Workspace(it)) },
                        onNewWorkspace = { push(Route.WorkspaceEditor(null, r.id)) },
                    )
                    is Route.Workspace -> WorkspaceScreen(
                        repo, service, monitor, r.id, onBack = ::pop,
                        onEdit = { push(Route.WorkspaceEditor(r.id, null)) },
                        onOpenSession = { sid, resume -> push(Route.Terminal(sid, resume)) },
                    )
                    is Route.WorkspaceEditor -> WorkspaceEditorScreen(
                        repo, r.id, r.projectId, onDone = ::pop,
                        // The workspace screen underneath no longer exists after a delete.
                        onDeleted = { pop(); pop() },
                    )
                    is Route.HostEditor -> HostEditorScreen(repo, r.id, onDone = ::pop)
                    is Route.Terminal -> TerminalScreen(repo, service, monitor, r.sessionId, r.resume, onBack = ::pop)
                }
                if (current.isTopLevel) BottomNav(current, ::selectTab)
            }
        }
    }
}

@Composable
private fun Spacer1(modifier: Modifier) {
    androidx.compose.foundation.layout.Box(modifier)
}

@Composable
private fun BottomNav(current: Route, onSelect: (Route) -> Unit) {
    Row(Modifier.fillMaxWidth().background(WC.Panel)) {
        for ((label, route) in listOf("Projects" to Route.Projects, "Hosts" to Route.Hosts)) {
            val selected = current == route
            Text(
                label, textAlign = TextAlign.Center, fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) WC.Accent else WC.Muted,
                modifier = Modifier.weight(1f).clickable { onSelect(route) }.padding(14.dp),
            )
        }
    }
}
