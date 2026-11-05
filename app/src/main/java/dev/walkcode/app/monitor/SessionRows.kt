package dev.walkcode.app.monitor

import dev.walkcode.app.data.Project
import dev.walkcode.app.data.Session
import dev.walkcode.app.data.Workspace
import dev.walkcode.app.data.displayName

/** One watched session's item in the notification shade. */
data class SessionRow(
    val sessionId: Long,
    val session: String,
    val workspaceId: Long,
    /** "Project · Workspace", the header of the group the row sits in. */
    val workspace: String,
    val label: String,
    val message: String,
)

object SessionRows {
    /** Shown until the first screen read; the host may be slow to connect or unreachable. */
    const val NO_MESSAGE = "No message yet"

    /** A row per watched session, workspace by workspace in the order the app lists them. */
    fun of(
        sessions: List<Session>,
        workspaces: List<Workspace>,
        projects: List<Project>,
        messages: Map<String, String>,
    ): List<SessionRow> {
        val projectNames = projects.associate { it.id to it.name }
        val watched = sessions.filter { it.watched }.groupBy { it.workspaceId }
        return workspaces.sortedBy { it.sortOrder }.flatMap { w ->
            val header = listOfNotNull(w.projectId?.let(projectNames::get), w.name).joinToString(" · ")
            watched[w.id].orEmpty().sortedWith(compareBy({ it.sortOrder }, { it.ordinal })).map { s ->
                SessionRow(s.id, s.tmuxName, w.id, header, s.displayName(), messages[s.tmuxName] ?: NO_MESSAGE)
            }
        }
    }
}
