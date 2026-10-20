package dev.walkcode.app.data

import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The file written by Export and read by Import. Entries refer to each other by name,
 * because database ids differ between installs.
 *
 * Not included: sessions (their tmux names are tied to this install), the phone's SSH key
 * (it cannot leave the Keystore, so each host needs the new key, see `ssh/AuthorizeCommand`),
 * and the one-time battery prompt flag.
 */
@Serializable
data class ConfigFile(
    // No defaults: a file without them is not ours, and must fail to decode.
    val format: String,
    val version: Int,
    val hosts: List<HostEntry> = emptyList(),
    val projects: List<ProjectEntry> = emptyList(),
    /** Custom presets only; built-ins are seeded by the app and matched by name. */
    val agentPresets: List<PresetEntry> = emptyList(),
    val workspaces: List<WorkspaceEntry> = emptyList(),
    val settings: SettingsEntry = SettingsEntry(),
) {
    companion object {
        const val FORMAT = "walkcode-config"
        const val VERSION = 1
    }
}

@Serializable
data class HostEntry(
    val name: String,
    val username: String,
    val port: Int = 22,
    val lanHost: String? = null,
    val tailnetHost: String? = null,
    val shell: ShellKind,
    /** The pinned host key travels too, so the first connection is verified instead of trusted blindly. */
    val hostKeyAlgorithm: String? = null,
    val hostKeyFingerprint: String? = null,
)

@Serializable
data class ProjectEntry(val name: String, val color: Int? = null, val sortOrder: Int = 0)

@Serializable
data class PresetEntry(val name: String, val command: String, val resumeCommand: String? = null)

@Serializable
data class WorkspaceEntry(
    val name: String,
    val host: String,
    val project: String? = null,
    val directory: String,
    val agent: String? = null,
    val args: String? = null,
)

@Serializable
data class SettingsEntry(
    val quietAfterSec: Int = AppSettings.DEFAULT_QUIET_SEC,
    val quietAlerts: Boolean = true,
    val walkMode: Boolean = false,
    val dictationAutoSend: Boolean = true,
    val ttsEngine: String? = null,
    val ttsVoice: String? = null,
    val ttsRate: Float = 1f,
    val recognizer: String? = null,
    val recognitionLang: String? = null,
)

/** JSON reading and writing with the format checks. */
object ConfigCodec {
    private val json = Json {
        prettyPrint = true
        // Every field is written, defaults included, so the file reads on its own.
        encodeDefaults = true
        // Newer versions may add fields; unknown ones are ignored rather than failing the import.
        ignoreUnknownKeys = true
    }

    fun encode(file: ConfigFile): String = json.encodeToString(ConfigFile.serializer(), file)

    fun decode(text: String): ConfigFile {
        val file = try {
            json.decodeFromString(ConfigFile.serializer(), text)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Not a WalkCode config file", e)
        }
        require(file.format == ConfigFile.FORMAT) { "Not a WalkCode config file" }
        require(file.version <= ConfigFile.VERSION) {
            "Config file version ${file.version} is newer than this app understands. Update WalkCode first."
        }
        return file
    }
}

/**
 * Export and import of the setup. Import merges into what is already there: anything
 * whose name already exists is left alone, and settings are replaced by the file's values.
 */
class ConfigBackup(private val repo: WalkcodeRepository, private val settings: AppSettings) {

    suspend fun export(): ConfigFile {
        val hosts = repo.hosts.first()
        val projects = repo.projects.first()
        val presets = repo.agentPresets.first()
        val workspaces = repo.workspaces.first()
        val hostNames = hosts.associate { it.id to it.name }
        val projectNames = projects.associate { it.id to it.name }
        val presetNames = presets.associate { it.id to it.name }

        val workspaceEntries = mutableListOf<WorkspaceEntry>()
        for (w in workspaces) {
            val host = hostNames[w.hostId] ?: continue
            workspaceEntries += WorkspaceEntry(
                name = w.name,
                host = host,
                project = w.projectId?.let { projectNames[it] },
                directory = w.directory,
                agent = w.agentPresetId?.let { presetNames[it] },
                args = w.args,
            )
        }

        return ConfigFile(
            format = ConfigFile.FORMAT,
            version = ConfigFile.VERSION,
            hosts = hosts.map {
                HostEntry(
                    name = it.name, username = it.username, port = it.port, lanHost = it.lanHost,
                    tailnetHost = it.tailnetHost, shell = it.shell,
                    hostKeyAlgorithm = it.hostKeyAlgorithm, hostKeyFingerprint = it.hostKeyFingerprint,
                )
            },
            projects = projects.map { ProjectEntry(it.name, it.color, it.sortOrder) },
            agentPresets = presets.filter { !it.builtIn }.map { PresetEntry(it.name, it.command, it.resumeCommand) },
            workspaces = workspaceEntries,
            settings = SettingsEntry(
                quietAfterSec = settings.quietAfterSec.value,
                quietAlerts = settings.quietAlerts.value,
                walkMode = settings.walkMode.value,
                dictationAutoSend = settings.dictationAutoSend.value,
                ttsEngine = settings.ttsEngine.value,
                ttsVoice = settings.ttsVoice.value,
                ttsRate = settings.ttsRate.value,
                recognizer = settings.recognizer.value,
                recognitionLang = settings.recognitionLang.value,
            ),
        )
    }

    /** What an import did, for the summary shown to the user. */
    data class Report(
        /** Hosts created by this import. Each one needs the phone's key added on the host. */
        val hostsAdded: List<String>,
        val hostsSkipped: Int,
        val projectsAdded: Int,
        val presetsAdded: Int,
        val workspacesAdded: Int,
        val workspacesSkipped: Int,
    )

    suspend fun import(file: ConfigFile): Report {
        val hostIds = repo.hosts.first().associate { it.name to it.id }.toMutableMap()
        val hostsAdded = mutableListOf<String>()
        var hostsSkipped = 0
        for (h in file.hosts) {
            if (h.name in hostIds) { hostsSkipped++; continue }
            hostIds[h.name] = repo.updateHost(
                Host(
                    name = h.name, username = h.username, port = h.port, lanHost = h.lanHost,
                    tailnetHost = h.tailnetHost, shell = h.shell,
                    hostKeyAlgorithm = h.hostKeyAlgorithm, hostKeyFingerprint = h.hostKeyFingerprint,
                ),
            )
            hostsAdded += h.name
        }

        val projectIds = repo.projects.first().associate { it.name to it.id }.toMutableMap()
        var projectsAdded = 0
        for (p in file.projects) {
            if (p.name in projectIds) continue
            projectIds[p.name] = repo.updateProject(Project(name = p.name, color = p.color, sortOrder = p.sortOrder))
            projectsAdded++
        }

        val presetIds = repo.agentPresets.first().associate { it.name to it.id }.toMutableMap()
        var presetsAdded = 0
        for ((index, p) in file.agentPresets.withIndex()) {
            if (p.name in presetIds) continue
            presetIds[p.name] = repo.upsertAgentPreset(
                AgentPreset(name = p.name, command = p.command, resumeCommand = p.resumeCommand, sortOrder = CUSTOM_PRESET_ORDER + index),
            )
            presetsAdded++
        }

        // Workspaces are unique per host, so the same name on another host is a different workspace.
        val existing = repo.workspaces.first().map { "${it.hostId}|${it.name}" }.toSet()
        var workspacesAdded = 0
        var workspacesSkipped = 0
        for (w in file.workspaces) {
            val hostId = hostIds[w.host]
            if (hostId == null || "$hostId|${w.name}" in existing) { workspacesSkipped++; continue }
            repo.addWorkspace(
                name = w.name,
                hostId = hostId,
                directory = w.directory,
                projectId = w.project?.let { projectIds[it] },
                agentPresetId = w.agent?.let { presetIds[it] },
                args = w.args,
            )
            workspacesAdded++
        }

        applySettings(file.settings)

        return Report(hostsAdded, hostsSkipped, projectsAdded, presetsAdded, workspacesAdded, workspacesSkipped)
    }

    private fun applySettings(s: SettingsEntry) {
        settings.setQuietAfterSec(s.quietAfterSec)
        settings.setQuietAlerts(s.quietAlerts)
        settings.setWalkMode(s.walkMode)
        settings.setDictationAutoSend(s.dictationAutoSend)
        // The engine first: changing it clears the voice, which belongs to one engine.
        settings.setTtsEngine(s.ttsEngine)
        settings.setTtsVoice(s.ttsVoice)
        settings.setTtsRate(s.ttsRate)
        settings.setRecognizer(s.recognizer)
        settings.setRecognitionLang(s.recognitionLang)
    }

    private companion object {
        /** Imported custom presets sort after the built-ins (which use 0..3). */
        const val CUSTOM_PRESET_ORDER = 100
    }
}
