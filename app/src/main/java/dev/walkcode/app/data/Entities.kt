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
 * everywhere (the app tries LAN first, then falls back). The address that last worked
 * is tried first, so on VPN the app does not wait for the LAN name to time out again.
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
    /** The address of the last successful connection; local to this phone, not in the config backup. */
    val lastAddress: String? = null,
) {
    /** Candidate addresses in the order the app should try them: the last one that worked, then LAN, then tailnet. */
    val addresses: List<String> get() {
        val configured = listOfNotNull(lanHost?.takeIf { it.isNotBlank() }, tailnetHost?.takeIf { it.isNotBlank() })
        // A remembered address no longer configured (the host was edited) is ignored.
        val last = lastAddress?.takeIf { it in configured } ?: return configured
        return listOf(last) + (configured - last)
    }
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
    /** Manual position within its project (long-press drag); new workspaces go last. */
    val sortOrder: Int = 0,
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
    /** The agent's pane title, cleaned (see [cleanPaneTitle]); shown when there is no label. */
    val autoTitle: String? = null,
    /** When this session was last viewed, and the screen hash at that moment (unread detection). */
    val lastViewedAt: Long? = null,
    val lastViewedHash: String? = null,
    /** Manual position within its workspace (long-press drag); new sessions go last. */
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * The agent's own conversation id (a UUID), substituted for `{id}` in preset commands so a
     * stopped session resumes its own conversation. Null for sessions created before it existed.
     */
    val agentSessionId: String? = null,
)

/** Claude Code is started with a fixed conversation id so each session resumes its own conversation. */
object BuiltInAgents {
    fun defaults(): List<AgentPreset> = listOf(
        AgentPreset(id = 1, name = "Claude Code", command = "claude --session-id {id}", resumeCommand = "claude --resume {id}", builtIn = true, sortOrder = 0),
        AgentPreset(id = 2, name = "opencode", command = "opencode", resumeCommand = null, builtIn = true, sortOrder = 1),
        AgentPreset(id = 3, name = "Copilot CLI", command = "copilot", resumeCommand = null, builtIn = true, sortOrder = 2),
        AgentPreset(id = 4, name = "Hermes", command = "hermes", resumeCommand = null, builtIn = true, sortOrder = 3),
    )
}

/**
 * Walk-mode spend for one day (local date, `2026-10-26`) and one model, summed from what the server
 * reports per response. [costUsd] is fixed at the list price of the time, so a later price change
 * does not rewrite history. Not part of the config backup.
 */
@Entity(tableName = "voice_usage", primaryKeys = ["day", "model"])
data class VoiceUsage(
    val day: String,
    val model: String,
    /** Responses (or transcriptions) counted. */
    val calls: Long = 0,
    val textIn: Long = 0,
    val audioIn: Long = 0,
    /** The cached part of the input (text and audio), billed at a fraction of the rate. */
    val cachedIn: Long = 0,
    val textOut: Long = 0,
    val audioOut: Long = 0,
    val costUsd: Double = 0.0,
) {
    val tokens: Long get() = textIn + audioIn + textOut + audioOut
}

/** A sum of [VoiceUsage] rows: today's, this month's or all of them. */
data class UsageTotal(val tokens: Long = 0, val cachedIn: Long = 0, val usd: Double = 0.0) {
    companion object {
        /** Rows on or after [fromDay] (ISO dates sort as text); null sums all of them. */
        fun of(rows: List<VoiceUsage>, fromDay: String? = null): UsageTotal =
            rows.filter { fromDay == null || it.day >= fromDay }
                .fold(UsageTotal()) { t, r -> UsageTotal(t.tokens + r.tokens, t.cachedIn + r.cachedIn, t.usd + r.costUsd) }
    }
}
