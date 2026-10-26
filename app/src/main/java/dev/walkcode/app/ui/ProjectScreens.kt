package dev.walkcode.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.walkcode.app.data.Project
import dev.walkcode.app.monitor.Monitor
import dev.walkcode.app.assistant.WalkAssistant
import dev.walkcode.app.data.AppSettings
import androidx.compose.foundation.layout.fillMaxWidth
import dev.walkcode.app.data.WalkcodeRepository
import kotlinx.coroutines.launch

val ProjectPalette = listOf(0xFF3DDC84, 0xFF6AA9FF, 0xFFFFB020, 0xFFFF6B6B, 0xFFC792EA, 0xFF4DD0E1).map { it.toInt() }

fun projectColor(p: Project?): Color = p?.color?.let { Color(it) } ?: WC.Muted

@Composable
fun ProjectsScreen(
    repo: WalkcodeRepository,
    monitor: Monitor,
    walk: WalkAssistant,
    settings: AppSettings,
    onOpenProject: (Long?) -> Unit,
    onOpenWalk: () -> Unit,
) {
    val walkOn by walk.on.collectAsState()
    val toggleWalk = rememberWalkToggle(walk, settings)
    val paused by monitor.paused.collectAsState()
    val projects by repo.projects.collectAsState(emptyList())
    val workspaces by repo.workspaces.collectAsState(emptyList())
    val hosts by repo.hosts.collectAsState(emptyList())
    val presets by repo.agentPresets.collectAsState(emptyList())
    val sessions by repo.allSessions.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }

    fun summary(projectId: Long?): String {
        val ws = workspaces.filter { it.projectId == projectId }
        val agents = ws.mapNotNull { w -> presets.firstOrNull { it.id == w.agentPresetId }?.name }.distinct()
        val hostNames = ws.mapNotNull { w -> hosts.firstOrNull { it.id == w.hostId }?.name }.distinct()
        return listOfNotNull(
            "${ws.size} workspace${if (ws.size == 1) "" else "s"}",
            agents.takeIf { it.isNotEmpty() }?.joinToString(", "),
            hostNames.takeIf { it.isNotEmpty() }?.let { "${it.size} host${if (it.size == 1) "" else "s"}" },
        ).joinToString(" · ")
    }
    fun sessionCount(projectId: Long?): Int {
        val ids = workspaces.filter { it.projectId == projectId }.map { it.id }.toSet()
        return sessions.count { it.workspaceId in ids }
    }

    TopBar("Projects") {
        // Walk mode: the voice assistant for all workspaces (see WalkScreen).
        Pill(if (walkOn) "Walk: on" else "Walk: off", onClick = toggleWalk, hot = walkOn)
        BarAction("+") { adding = true }
    }
    WalkStrip(walk, onOpen = onOpenWalk)
    if (paused) {
        Text(
            "Monitoring paused 2014 tap to resume", color = WC.Warn, fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().clickable { monitor.resume() }.background(WC.Panel).padding(14.dp),
        )
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 4.dp)) {
        if (projects.isEmpty() && workspaces.isEmpty()) {
            EmptyHint("Add a host in the Hosts tab, then create a project with + and a workspace inside it.")
        }
        ReorderableColumn(projects, { it.id }, { ids -> scope.launch { repo.reorderProjects(ids) } }) { p ->
            Card(onClick = { onOpenProject(p.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(projectColor(p)))
                    Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    val n = sessionCount(p.id)
                    if (n > 0) Badge(n)
                }
                Text(summary(p.id), color = WC.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (workspaces.any { it.projectId == null }) {
            Card(onClick = { onOpenProject(null) }) {
                Text("No project", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(summary(null), color = WC.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }

    if (adding) {
        var name by remember { mutableStateOf("") }
        var color by remember { mutableStateOf(ProjectPalette[projects.size % ProjectPalette.size]) }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("New project") },
            text = {
                Column {
                    FormField("Name", name, { name = it })
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (c in ProjectPalette) {
                            Box(Modifier.size(if (c == color) 30.dp else 24.dp).clip(CircleShape).background(Color(c)).clickable { color = c })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    scope.launch { repo.addProject(name, color) }
                    adding = false
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ProjectDetailScreen(
    repo: WalkcodeRepository,
    monitor: Monitor,
    projectId: Long?,
    onBack: () -> Unit,
    onOpenWorkspace: (Long) -> Unit,
    onNewWorkspace: () -> Unit,
) {
    val projects by repo.projects.collectAsState(emptyList())
    val workspaces by repo.workspaces.collectAsState(emptyList())
    val hosts by repo.hosts.collectAsState(emptyList())
    val presets by repo.agentPresets.collectAsState(emptyList())
    val sessions by repo.allSessions.collectAsState(emptyList())
    val activity by monitor.activity.collectAsState()
    val scope = rememberCoroutineScope()
    val project = projects.firstOrNull { it.id == projectId }
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    TopBar(project?.name ?: "No project", onBack = onBack) {
        BarAction("+", onNewWorkspace)
        if (project != null) BarAction("✎") { editing = true }
        if (project != null) BarAction("✕") { confirmDelete = true }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 4.dp)) {
        val list = workspaces.filter { it.projectId == projectId }
        if (list.isEmpty()) EmptyHint("No workspaces. Add one with +.")
        ReorderableColumn(list, { it.id }, { ids -> scope.launch { repo.reorderWorkspaces(ids) } }) { w ->
            val host = hosts.firstOrNull { it.id == w.hostId }
            val preset = presets.firstOrNull { it.id == w.agentPresetId }
            val n = sessions.count { it.workspaceId == w.id }
            ListRow(
                title = w.name,
                subtitle = listOfNotNull(host?.name, preset?.name?.lowercase(), if (n == 0) "no sessions" else "$n session${if (n == 1) "" else "s"}").joinToString(" · "),
                path = w.directory,
                onClick = { onOpenWorkspace(w.id) },
                leading = { Dot(if (n > 0) WC.Accent else WC.Off) },
                trailing = {
                    val unread = sessions.any { it.workspaceId == w.id && it.watched && activity[it.tmuxName]?.unread == true }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (unread) UnreadDot()
                        if (n > 0) Badge(n)
                    }
                },
            )
        }
    }
    if (editing && project != null) {
        var name by remember(project.id) { mutableStateOf(project.name) }
        var color by remember(project.id) { mutableStateOf(project.color ?: ProjectPalette[0]) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Edit project") },
            text = {
                Column {
                    FormField("Name", name, { name = it })
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (c in ProjectPalette) {
                            Box(Modifier.size(if (c == color) 30.dp else 24.dp).clip(CircleShape).background(Color(c)).clickable { color = c })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    scope.launch { repo.updateProject(project.copy(name = name.trim(), color = color)) }
                    editing = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete && project != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete project?") },
            text = { Text("Workspaces stay; they just lose the label.") },
            confirmButton = { TextButton(onClick = { scope.launch { repo.deleteProject(project); onBack() } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
