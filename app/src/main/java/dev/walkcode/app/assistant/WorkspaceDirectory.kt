package dev.walkcode.app.assistant

import dev.walkcode.app.data.AgentPreset
import dev.walkcode.app.data.Host
import dev.walkcode.app.data.Project
import dev.walkcode.app.data.Session
import dev.walkcode.app.data.Workspace
import dev.walkcode.app.data.displayName
import dev.walkcode.app.monitor.SessionActivity

/** One terminal session as the voice assistant sees it. [id] is the multiplexer name: unique and stable. */
data class SessionRef(
    val id: String,
    val name: String,
    val workspace: String,
    val project: String?,
    val host: String,
    val agent: String?,
    /** What the monitor knows, in words; null when the session is not watched. */
    val status: String?,
    val session: Session,
    val hostEntity: Host,
)

/**
 * The assistant's picture of projects -> workspaces -> sessions, as text for the model, and the
 * lookup of the names it says back. Pure, so it is unit tested.
 */
object WorkspaceDirectory {

    fun sessions(
        projects: List<Project>,
        workspaces: List<Workspace>,
        hosts: List<Host>,
        presets: List<AgentPreset>,
        sessions: List<Session>,
        activity: Map<String, SessionActivity>,
    ): List<SessionRef> {
        val wsById = workspaces.associateBy { it.id }
        return sessions.sortedWith(compareBy({ it.workspaceId }, { it.sortOrder }, { it.ordinal })).mapNotNull { s ->
            val w = wsById[s.workspaceId] ?: return@mapNotNull null
            val h = hosts.firstOrNull { it.id == w.hostId } ?: return@mapNotNull null
            SessionRef(
                id = s.tmuxName,
                name = s.displayName(),
                workspace = w.name,
                project = projects.firstOrNull { it.id == w.projectId }?.name,
                host = h.name,
                agent = presets.firstOrNull { it.id == w.agentPresetId }?.name,
                status = activity[s.tmuxName]?.let(::describe),
                session = s,
                hostEntity = h,
            )
        }
    }

    /** "busy right now" / "quiet for 4 min, with output not seen yet". */
    fun describe(a: SessionActivity): String {
        val quiet = when {
            a.idleSec < 10 -> "producing output right now"
            a.idleSec < 120 -> "quiet for ${a.idleSec} s"
            a.idleSec < 7200 -> "quiet for ${a.idleSec / 60} min"
            else -> "quiet for ${a.idleSec / 3600} h"
        }
        return if (a.unread) "$quiet, with output the user has not seen" else quiet
    }

    /** The list_workspaces answer: one block per workspace, its sessions indented under it. */
    fun listing(refs: List<SessionRef>, workspaces: List<Workspace>, projects: List<Project>, hosts: List<Host>): String {
        if (workspaces.isEmpty()) return "There are no workspaces yet."
        val out = StringBuilder()
        for (w in workspaces.sortedWith(compareBy({ it.projectId }, { it.sortOrder }))) {
            val project = projects.firstOrNull { it.id == w.projectId }?.name
            val host = hosts.firstOrNull { it.id == w.hostId }?.name ?: "unknown host"
            out.append("Workspace \"${w.name}\"")
            if (project != null) out.append(" (project $project)")
            out.append(" on host $host, directory ${w.directory}\n")
            val mine = refs.filter { it.session.workspaceId == w.id }
            if (mine.isEmpty()) out.append("  no sessions\n")
            for (r in mine) {
                out.append("  session id=${r.id} name=\"${r.name}\"")
                r.agent?.let { out.append(" agent=$it") }
                out.append(" status=${r.status ?: "not monitored"}\n")
            }
        }
        return out.toString().trimEnd()
    }

    sealed interface Match {
        data class Found(val ref: SessionRef) : Match
        data class NotFound(val message: String) : Match
    }

    /**
     * Finds the session the model meant: its id, else a session or workspace name (a workspace with
     * one session stands for that session), exact first, then as a case-insensitive part.
     */
    fun resolve(refs: List<SessionRef>, query: String?): Match {
        val q = query?.trim().orEmpty()
        if (q.isEmpty()) return Match.NotFound("Say which session; call list_workspaces for the ids.")
        refs.firstOrNull { it.id == q }?.let { return Match.Found(it) }
        val passes: List<(SessionRef) -> Boolean> = listOf(
            { it.name.equals(q, ignoreCase = true) },
            { it.workspace.equals(q, ignoreCase = true) },
            { it.name.contains(q, ignoreCase = true) || it.workspace.contains(q, ignoreCase = true) },
        )
        for (pass in passes) {
            val hits = refs.filter(pass)
            if (hits.size == 1) return Match.Found(hits.single())
            if (hits.size > 1) {
                val options = hits.joinToString("; ") { "id=${it.id} (${it.workspace}, ${it.name})" }
                return Match.NotFound("\"$q\" matches several sessions: $options. Use one of the ids.")
            }
        }
        return Match.NotFound("No session matches \"$q\"; call list_workspaces for the ids.")
    }
}
