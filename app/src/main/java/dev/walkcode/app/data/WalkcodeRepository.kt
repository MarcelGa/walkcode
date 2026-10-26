package dev.walkcode.app.data

import androidx.room.withTransaction
import dev.walkcode.app.voice.Usage
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Thin, testable wrapper over the DAOs. Holds the few rules that are more than
 * CRUD: session naming/ordinals and Trust-On-First-Use host-key pinning.
 */
class WalkcodeRepository(private val db: WalkcodeDatabase) {

    val hosts: Flow<List<Host>> = db.hosts().observeAll()
    val projects: Flow<List<Project>> = db.projects().observeAll()
    val agentPresets: Flow<List<AgentPreset>> = db.agentPresets().observeAll()
    val workspaces: Flow<List<Workspace>> = db.workspaces().observeAll()
    val allSessions: Flow<List<Session>> = db.sessions().observeAll()
    /** One row per day and model; few enough to sum in memory. */
    val voiceUsage: Flow<List<VoiceUsage>> = db.voiceUsage().observeAll()

    /** Adds one response's (or transcription's) tokens and estimated price to [day]'s row for [model]. */
    suspend fun addVoiceUsage(day: String, model: String, usage: Usage, usd: Double) = db.withTransaction {
        val row = db.voiceUsage().get(day, model) ?: VoiceUsage(day, model)
        db.voiceUsage().upsert(
            row.copy(
                calls = row.calls + 1,
                textIn = row.textIn + usage.textIn,
                audioIn = row.audioIn + usage.audioIn,
                cachedIn = row.cachedIn + usage.cachedIn,
                textOut = row.textOut + usage.textOut,
                audioOut = row.audioOut + usage.audioOut,
                costUsd = row.costUsd + usd,
            ),
        )
    }

    suspend fun clearVoiceUsage() = db.voiceUsage().clear()

    fun sessionsFor(workspaceId: Long): Flow<List<Session>> = db.sessions().observeForWorkspace(workspaceId)

    suspend fun host(id: Long): Host? = db.hosts().get(id)
    suspend fun workspace(id: Long): Workspace? = db.workspaces().get(id)
    suspend fun session(id: Long): Session? = db.sessions().get(id)

    // --- Hosts -------------------------------------------------------------

    suspend fun addHost(
        name: String,
        username: String,
        shell: ShellKind,
        lanHost: String? = null,
        tailnetHost: String? = null,
        port: Int = 22,
    ): Long = db.hosts().upsert(
        Host(name = name.trim(), username = username.trim(), shell = shell, lanHost = lanHost?.trim(), tailnetHost = tailnetHost?.trim(), port = port),
    )

    suspend fun updateHost(host: Host): Long = db.hosts().upsert(host)

    suspend fun deleteHost(host: Host) = db.hosts().delete(host)

    /** Records the host key seen on first successful connect. */
    suspend fun trustHostKey(hostId: Long, algorithm: String, fingerprint: String) =
        db.hosts().pinHostKey(hostId, algorithm, fingerprint)

    /** Remembers the address that just worked so the next connection tries it first. */
    suspend fun rememberAddress(hostId: Long, address: String) = db.hosts().setLastAddress(hostId, address)

    // --- Projects / presets ------------------------------------------------

    suspend fun addProject(name: String, color: Int? = null): Long =
        db.projects().upsert(Project(name = name.trim(), color = color, sortOrder = db.projects().nextSortOrder()))

    suspend fun deleteProject(project: Project) = db.projects().delete(project)

    suspend fun agentPreset(id: Long): AgentPreset? = db.agentPresets().get(id)

    suspend fun upsertAgentPreset(preset: AgentPreset): Long = db.agentPresets().upsert(preset)

    /** Workspaces that used it fall back to a plain shell (the foreign key is SET_NULL). */
    suspend fun deleteAgentPreset(preset: AgentPreset) = db.agentPresets().delete(preset)

    // --- Workspaces --------------------------------------------------------

    suspend fun addWorkspace(
        name: String,
        hostId: Long,
        directory: String,
        projectId: Long? = null,
        agentPresetId: Long? = null,
        args: String? = null,
    ): Long = db.workspaces().upsert(
        Workspace(
            name = name.trim(),
            hostId = hostId,
            directory = directory.trim(),
            projectId = projectId,
            agentPresetId = agentPresetId,
            args = args,
            slug = Naming.slug(name),
            sortOrder = db.workspaces().nextSortOrder(),
        ),
    )

    suspend fun updateWorkspace(workspace: Workspace): Long = db.workspaces().upsert(workspace)

    suspend fun deleteWorkspace(workspace: Workspace) = db.workspaces().delete(workspace)

    suspend fun touchWorkspace(workspaceId: Long) = db.workspaces().touch(workspaceId)

    // --- Sessions ----------------------------------------------------------

    /** Adds another session for a workspace, picking the next free ordinal. */
    suspend fun createSession(workspaceId: Long, watched: Boolean = false): Session {
        val workspace = requireNotNull(db.workspaces().get(workspaceId)) { "no workspace $workspaceId" }
        val siblings = db.sessions().forWorkspace(workspaceId)
        val ordinal = Naming.nextOrdinal(siblings.map { it.ordinal })
        val name = Naming.tmuxName(workspace.slug, workspace.id, ordinal)
        val order = (siblings.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val session = Session(
            tmuxName = name, workspaceId = workspaceId, ordinal = ordinal, watched = watched, sortOrder = order,
            agentSessionId = UUID.randomUUID().toString(),
        )
        val id = db.sessions().upsert(session)
        return requireNotNull(db.sessions().get(id))
    }

    /** Gives a session created before conversation ids existed one, when its agent starts afresh. */
    suspend fun assignAgentSessionId(sessionId: Long): String =
        UUID.randomUUID().toString().also { db.sessions().setAgentSessionId(sessionId, it) }

    suspend fun sessionByName(tmuxName: String): Session? = db.sessions().getByTmuxName(tmuxName)

    suspend fun setSessionWatched(sessionId: Long, watched: Boolean) = db.sessions().setWatched(sessionId, watched)

    suspend fun renameSession(sessionId: Long, label: String?) = db.sessions().setLabel(sessionId, label?.trim()?.ifEmpty { null })

    /** Stores a pane title from the host as the session's automatic name; a user label still wins. */
    suspend fun recordPaneTitle(tmuxName: String, rawTitle: String) =
        db.sessions().setAutoTitle(tmuxName, cleanPaneTitle(rawTitle))

    suspend fun updateProject(project: Project): Long = db.projects().upsert(project)

    /** Marks a session as seen, storing the screen hash so unread can be recomputed. */
    suspend fun markSessionViewed(sessionId: Long, screenHash: String? = null, at: Long = System.currentTimeMillis()) =
        db.sessions().markViewed(sessionId, at, screenHash)

    suspend fun deleteSession(session: Session) = db.sessions().delete(session)

    // --- Manual order --------------------------------------------------------
    // Each takes the ids in their new on-screen order. Workspaces and sessions are only ever shown
    // within one project / workspace, so positions only need to be consistent within that group.

    suspend fun reorderProjects(ids: List<Long>) =
        db.withTransaction { ids.forEachIndexed { i, id -> db.projects().setSortOrder(id, i) } }

    suspend fun reorderWorkspaces(ids: List<Long>) =
        db.withTransaction { ids.forEachIndexed { i, id -> db.workspaces().setSortOrder(id, i) } }

    suspend fun reorderSessions(ids: List<Long>) =
        db.withTransaction { ids.forEachIndexed { i, id -> db.sessions().setSortOrder(id, i) } }

    companion object {
        @Volatile private var shared: WalkcodeRepository? = null

        fun get(context: android.content.Context): WalkcodeRepository =
            shared ?: synchronized(this) {
                shared ?: WalkcodeRepository(WalkcodeDatabase.get(context)).also { shared = it }
            }
    }
}
