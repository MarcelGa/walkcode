package dev.walkcode.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface HostDao {
    @Query("SELECT * FROM hosts ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Host>>

    @Query("SELECT * FROM hosts WHERE id = :id")
    suspend fun get(id: Long): Host?

    @Upsert
    suspend fun upsert(host: Host): Long

    @Delete
    suspend fun delete(host: Host)

    @Query("UPDATE hosts SET hostKeyAlgorithm = :algorithm, hostKeyFingerprint = :fingerprint WHERE id = :id")
    suspend fun pinHostKey(id: Long, algorithm: String, fingerprint: String)
}

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeAll(): Flow<List<Project>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: Long): Project?

    @Upsert
    suspend fun upsert(project: Project): Long

    @Delete
    suspend fun delete(project: Project)
}

@Dao
interface AgentPresetDao {
    @Query("SELECT * FROM agent_presets ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeAll(): Flow<List<AgentPreset>>

    @Query("SELECT * FROM agent_presets WHERE id = :id")
    suspend fun get(id: Long): AgentPreset?

    @Upsert
    suspend fun upsert(preset: AgentPreset): Long

    @Query("SELECT COUNT(*) FROM agent_presets")
    suspend fun count(): Int
}

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM workspaces ORDER BY lastUsedAt DESC, name COLLATE NOCASE")
    fun observeAll(): Flow<List<Workspace>>

    @Query("SELECT * FROM workspaces WHERE id = :id")
    suspend fun get(id: Long): Workspace?

    @Upsert
    suspend fun upsert(workspace: Workspace): Long

    @Delete
    suspend fun delete(workspace: Workspace)

    @Query("UPDATE workspaces SET lastUsedAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long = System.currentTimeMillis())
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE workspaceId = :workspaceId ORDER BY ordinal, id")
    fun observeForWorkspace(workspaceId: Long): Flow<List<Session>>

    @Query("SELECT * FROM sessions ORDER BY id")
    fun observeAll(): Flow<List<Session>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: Long): Session?

    @Query("SELECT * FROM sessions WHERE tmuxName = :tmuxName")
    suspend fun getByTmuxName(tmuxName: String): Session?

    @Query("SELECT * FROM sessions WHERE workspaceId = :workspaceId")
    suspend fun forWorkspace(workspaceId: Long): List<Session>

    @Upsert
    suspend fun upsert(session: Session): Long

    @Delete
    suspend fun delete(session: Session)

    @Query("UPDATE sessions SET label = :label WHERE id = :id")
    suspend fun setLabel(id: Long, label: String?)

    @Query("UPDATE sessions SET watched = :watched WHERE id = :id")
    suspend fun setWatched(id: Long, watched: Boolean)

    @Query("UPDATE sessions SET lastViewedAt = :at, lastViewedHash = :hash WHERE id = :id")
    suspend fun markViewed(id: Long, at: Long, hash: String?)
}
