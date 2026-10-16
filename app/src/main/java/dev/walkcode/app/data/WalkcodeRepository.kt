package dev.walkcode.app.data

import kotlinx.coroutines.flow.Flow

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

    // --- Projects / presets ------------------------------------------------

    suspend fun addProject(name: String, color: Int? = null): Long =
        db.projects().upsert(Project(name = name.trim(), color = color))

    suspend fun deleteProject(project: Project) = db.projects().delete(project)

    suspend fun agentPreset(id: Long): AgentPreset? = db.agentPresets().get(id)

    suspend fun upsertAgentPreset(preset: AgentPreset): Long = db.agentPresets().upsert(preset)

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
        ),
    )

    suspend fun updateWorkspace(workspace: Workspace): Long = db.workspaces().upsert(workspace)

    suspend fun deleteWorkspace(workspace: Workspace) = db.workspaces().delete(workspace)

    suspend fun touchWorkspace(workspaceId: Long) = db.workspaces().touch(workspaceId)

    // --- Sessions ----------------------------------------------------------

    /** Adds another session for a workspace, picking the next free ordinal. */
    suspend fun createSession(workspaceId: Long, watched: Boolean = false): Session {
        val workspace = requireNotNull(db.workspaces().get(workspaceId)) { "no workspace $workspaceId" }
        val ordinal = Naming.nextOrdinal(db.sessions().forWorkspace(workspaceId).map { it.ordinal })
        val name = Naming.tmuxName(workspace.slug, workspace.id, ordinal)
        val id = db.sessions().upsert(Session(tmuxName = name, workspaceId = workspaceId, ordinal = ordinal, watched = watched))
        return requireNotNull(db.sessions().get(id))
    }

    suspend fun sessionByName(tmuxName: String): Session? = db.sessions().getByTmuxName(tmuxName)

    suspend fun setSessionWatched(sessionId: Long, watched: Boolean) = db.sessions().setWatched(sessionId, watched)

    /** Marks a session as seen, storing the screen hash so unread can be recomputed. */
    suspend fun markSessionViewed(sessionId: Long, screenHash: String? = null, at: Long = System.currentTimeMillis()) =
        db.sessions().markViewed(sessionId, at, screenHash)

    suspend fun deleteSession(session: Session) = db.sessions().delete(session)

    companion object {
        @Volatile private var shared: WalkcodeRepository? = null

        fun get(context: android.content.Context): WalkcodeRepository =
            shared ?: synchronized(this) {
                shared ?: WalkcodeRepository(WalkcodeDatabase.get(context)).also { shared = it }
            }
    }
}
