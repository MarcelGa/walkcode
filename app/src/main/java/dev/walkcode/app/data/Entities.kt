package dev.walkcode.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Which multiplexer the host runs, decided when the host is created. */
enum class ShellKind { TMUX, PSMUX }

/**
 * A machine Walkcode can reach over SSH. Addresses are stored separately because
 * the LAN name only resolves on the home network while the tailnet address works
 * everywhere (the app tries LAN first, then falls back).
 */
@Entity(tableName = "hosts")
data class Host(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val username: String,
    val port: Int = 22,
    val lanHost: String? = null,
    val tailnetHost: String? = null,
    val shell: ShellKind,
    /** Trust On First Use pin, filled in after the first successful connection. */
    val hostKeyAlgorithm: String? = null,
    val hostKeyFingerprint: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** Candidate addresses in the order the app should try them. */
    val addresses: List<String> get() = listOfNotNull(lanHost?.takeIf { it.isNotBlank() }, tailnetHost?.takeIf { it.isNotBlank() })
}

/** A local, colour-coded label grouping workspaces. Nothing about it reaches a host. */
@Entity(tableName = "projects")
data class Project(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Int? = null,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

/** A launchable agent command, e.g. "Claude Code" -> `claude`. */
@Entity(tableName = "agent_presets")
data class AgentPreset(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val command: String,
    val resumeCommand: String? = null,
    val builtIn: Boolean = false,
    val sortOrder: Int = 0,
)

/** A place an agent runs: host + directory + agent + optional label. */
@Entity(
    tableName = "workspaces",
    foreignKeys = [
        ForeignKey(Host::class, ["id"], ["hostId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Project::class, ["id"], ["projectId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(AgentPreset::class, ["id"], ["agentPresetId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("hostId"), Index("projectId"), Index("agentPresetId")],
)
data class Workspace(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val hostId: Long,
    val projectId: Long? = null,
    val directory: String,
    val agentPresetId: Long? = null,
    val args: String? = null,
    /** Stable component of the multiplexer session name: `wc-<slug>-<id>`. */
    val slug: String,
    val lastUsedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * One multiplexer session belonging to a workspace. Several sessions may share a
 * workspace; [ordinal] is the `-N` suffix (0 means no suffix).
 */
@Entity(
    tableName = "sessions",
    foreignKeys = [ForeignKey(Workspace::class, ["id"], ["workspaceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("workspaceId"), Index(value = ["tmuxName"], unique = true)],
)
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tmuxName: String,
    val workspaceId: Long,
    val ordinal: Int,
    val watched: Boolean = false,
    /** Optional user-given name shown instead of "#N". */
    val label: String? = null,
    /** When this session was last viewed, and the screen hash at that moment (unread detection). */
    val lastViewedAt: Long? = null,
    val lastViewedHash: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** `claude --continue`, used for built-in presets. */
object BuiltInAgents {
    fun defaults(): List<AgentPreset> = listOf(
        AgentPreset(id = 1, name = "Claude Code", command = "claude", resumeCommand = "claude --continue", builtIn = true, sortOrder = 0),
        AgentPreset(id = 2, name = "opencode", command = "opencode", resumeCommand = null, builtIn = true, sortOrder = 1),
        AgentPreset(id = 3, name = "Copilot CLI", command = "copilot", resumeCommand = null, builtIn = true, sortOrder = 2),
        AgentPreset(id = 4, name = "Hermes", command = "hermes", resumeCommand = null, builtIn = true, sortOrder = 3),
    )
}
