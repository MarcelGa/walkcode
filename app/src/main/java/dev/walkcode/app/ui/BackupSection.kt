package dev.walkcode.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.walkcode.app.data.AppSettings
import dev.walkcode.app.data.ConfigBackup
import dev.walkcode.app.data.ConfigCodec
import dev.walkcode.app.data.ConfigFile
import dev.walkcode.app.data.WalkcodeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings section that exports the setup to a JSON file and imports one back. */
@Composable
fun BackupSection(repo: WalkcodeRepository, settings: AppSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backup = remember { ConfigBackup(repo, settings) }
    var pending by remember { mutableStateOf<ConfigFile?>(null) }
    var report by remember { mutableStateOf<ConfigBackup.Report?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val text = ConfigCodec.encode(backup.export())
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
                        ?: error("could not open the file for writing")
                }
            }.onSuccess {
                Toast.makeText(context, "Config exported", Toast.LENGTH_SHORT).show()
            }.onFailure { problem = "Export failed: ${it.message ?: it::class.java.simpleName}" }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("could not open the file")
                }
                ConfigCodec.decode(text)
            }.onSuccess { pending = it }
                .onFailure { problem = "Import failed: ${it.message ?: it::class.java.simpleName}" }
        }
    }

    SectionLabel("Backup")
    ListRow(
        title = "Export config",
        subtitle = "Hosts, projects, workspaces, agent presets and settings to a JSON file. No keys.",
        subtitleLines = 3,
        trailing = { Pill("Export", onClick = { exportLauncher.launch("walkcode-config.json") }) },
    )
    ListRow(
        title = "Import config",
        subtitle = "Adds what is missing; existing names are kept. Replaces the settings with the file's.",
        subtitleLines = 3,
        trailing = { Pill("Import", onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) },
    )

    pending?.let { file ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Import config?") },
            text = {
                Text(
                    "${file.hosts.size} hosts, ${file.workspaces.size} workspaces, " +
                        "${file.projects.size} projects, ${file.agentPresets.size} agent presets. " +
                        "Settings in the file replace the current ones.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    scope.launch {
                        runCatching { backup.import(file) }
                            .onSuccess { report = it }
                            .onFailure { problem = "Import failed: ${it.message ?: it::class.java.simpleName}" }
                    }
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }

    report?.let { r ->
        AlertDialog(
            onDismissRequest = { report = null },
            title = { Text("Config imported") },
            text = {
                Text(
                    buildString {
                        append("Hosts: ${r.hostsAdded.size} added")
                        if (r.hostsSkipped > 0) append(", ${r.hostsSkipped} already present")
                        append("\nWorkspaces: ${r.workspacesAdded} added")
                        if (r.workspacesSkipped > 0) append(", ${r.workspacesSkipped} skipped")
                        append("\nProjects: ${r.projectsAdded} added · Agent presets: ${r.presetsAdded} added")
                        if (r.hostsAdded.isNotEmpty()) {
                            append("\n\nAuthorize this phone on: ${r.hostsAdded.joinToString()}. ")
                            append("Open each host under Hosts and copy its command, or tap Test connection to check.")
                        }
                    },
                )
            },
            confirmButton = { TextButton(onClick = { report = null }) { Text("OK") } },
        )
    }

    problem?.let { message ->
        AlertDialog(
            onDismissRequest = { problem = null },
            title = { Text("Config") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { problem = null }) { Text("OK") } },
        )
    }
}
