package dev.walkcode.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.walkcode.app.data.AgentPreset
import dev.walkcode.app.data.WalkcodeRepository
import kotlinx.coroutines.launch

/** Custom presets sort after the built-ins (0..3), as imported ones do in `ConfigBackup`. */
private const val CUSTOM_PRESET_ORDER = 100

@Composable
fun ColumnScope.AgentPresetsScreen(repo: WalkcodeRepository, onOpenPreset: (Long?) -> Unit, onBack: () -> Unit) {
    val presets by repo.agentPresets.collectAsState(emptyList())
    val workspaces by repo.workspaces.collectAsState(emptyList())

    TopBar("Agents", onBack = onBack) { BarAction("+") { onOpenPreset(null) } }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        if (presets.isEmpty()) EmptyHint("No agents yet. Add one with +.")
        for (p in presets) {
            val count = workspaces.count { it.agentPresetId == p.id }
            ListRow(
                title = p.name,
                subtitle = listOfNotNull(
                    p.command,
                    if (p.builtIn) "built-in" else null,
                    "$count workspace${if (count == 1) "" else "s"}",
                ).joinToString(" · "),
                onClick = { onOpenPreset(p.id) },
            )
        }
    }
}

@Composable
fun AgentPresetEditorScreen(repo: WalkcodeRepository, presetId: Long?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(presetId == null) }
    var existing by remember { mutableStateOf<AgentPreset?>(null) }
    var name by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var resume by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(presetId) {
        if (presetId != null) {
            repo.agentPreset(presetId)?.let { p ->
                existing = p; name = p.name; command = p.command; resume = p.resumeCommand.orEmpty()
            }
            loaded = true
        }
    }

    val builtIn = existing?.builtIn == true
    val valid = name.isNotBlank() && command.isNotBlank()

    TopBar(if (presetId == null) "New agent" else "Agent", onBack = onDone)
    if (!loaded) return
    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (builtIn) {
            EmptyHint("Built-in agents are fixed. Add your own with + to run another account, such as claude-work.")
            ListRow(title = name, subtitle = command)
            if (resume.isNotBlank()) ListRow(title = "Resume", subtitle = resume)
        } else {
            // The command is typed into the host's shell, so an alias or function there works too.
            // {id} is replaced by the session's own conversation id (see AgentCommand).
            FormField("Name", name, { name = it }, "Claude (work)")
            FormField("Command", command, { command = it }, "claude-work --session-id {id}", mono = true)
            FormField("Resume command", resume, { resume = it }, "optional, e.g. claude-work --resume {id}", mono = true)
            PrimaryButton("Save", enabled = valid, onClick = {
                scope.launch {
                    repo.upsertAgentPreset(
                        AgentPreset(
                            id = existing?.id ?: 0,
                            name = name.trim(),
                            command = command.trim(),
                            resumeCommand = resume.trim().ifEmpty { null },
                            sortOrder = existing?.sortOrder ?: CUSTOM_PRESET_ORDER,
                        ),
                    )
                    onDone()
                }
            })
            existing?.let { p ->
                if (confirmDelete) {
                    PrimaryButton("Really delete (its workspaces start a plain shell)", ghost = true, onClick = {
                        scope.launch { repo.deleteAgentPreset(p); onDone() }
                    })
                } else {
                    PrimaryButton("Delete agent", ghost = true, onClick = { confirmDelete = true })
                }
            }
        }
    }
}
