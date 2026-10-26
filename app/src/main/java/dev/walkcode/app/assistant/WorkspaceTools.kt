package dev.walkcode.app.assistant

import dev.walkcode.app.data.WalkcodeRepository
import dev.walkcode.app.monitor.Monitor
import dev.walkcode.app.session.SessionService
import dev.walkcode.app.voice.RealtimeProtocol
import dev.walkcode.app.voice.ToolHost
import dev.walkcode.app.walk.ResponseExtractor
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * What walk mode's assistant can do across all workspaces: list them, read their screens and,
 * after the user said yes to a proposed text read back to them, type into a session. Screens are read over a short
 * SSH connection per host and call, like the session list on the workspace screen.
 */
class WorkspaceTools(
    private val repo: WalkcodeRepository,
    private val service: SessionService,
    private val monitor: Monitor,
) : ToolHost {

    /** The message read back to the user and waiting for their yes; a new proposal replaces it. */
    private var pending: Pair<SessionRef, String>? = null

    override val instructions: String =
        "You are Walkcode's walk-mode assistant. The user is away from the screen, often walking, and talks to you " +
            "about the coding agents (Claude Code, opencode and similar) running in terminal sessions of their workspaces. " +
            "Use the functions: list_workspaces to learn names and ids; workspace_overview to say what is going on, " +
            "what finished and which agents wait for an answer; read_session for more detail. To pass on a task or an " +
            "answer, call propose_message as soon as the user wants something sent (it sends nothing), read the exact " +
            "text back and ask; call send_proposed only after they said yes. Never talk about functions or steps. " +
            "Speak like a calm colleague in one to three short sentences: summarise, never read code, logs or long " +
            "output aloud, and name workspaces the way the user does. If a function fails, say what failed."

    override val definitions: List<JsonObject> = listOf(
        RealtimeProtocol.function(
            "list_workspaces",
            "Lists the user's workspaces (project, host, directory) and their terminal sessions with session ids, " +
                "the agent and a live status where known. Call this first to learn the ids the other functions take.",
        ),
        RealtimeProtocol.function(
            "workspace_overview",
            "Reads the last lines of the screen of every session, or only of one workspace's sessions. Use it to " +
                "tell the user what the agents are doing, what finished and which ones wait for an answer.",
            params = mapOf("workspace" to "Workspace name; leave out for all workspaces."),
        ),
        RealtimeProtocol.function(
            "read_session",
            "Reads about the last 60 lines of one session's screen.",
            params = mapOf("session" to "Session id from list_workspaces, or a workspace name when it has one session."),
            required = listOf("session"),
        ),
        RealtimeProtocol.function(
            "propose_message",
            "Prepares text to type into a session's agent, without sending it. Call it as soon as the user wants " +
                "something passed on, then read the exact text back and ask them to confirm.",
            params = mapOf(
                "session" to "Session id from list_workspaces, or a workspace name when it has one session.",
                "text" to "Exactly what to type, as the agent should receive it (Enter is pressed after it).",
            ),
            required = listOf("session", "text"),
        ),
        RealtimeProtocol.function(
            "send_proposed",
            "Types the proposed message into its session and presses Enter. Only after the user said yes to it.",
        ),
    )

    override fun label(name: String, arguments: String): String {
        val args = parse(arguments)
        return when (name) {
            "list_workspaces" -> "Looking at the workspaces"
            "workspace_overview" -> "Reading the screens" + (args.str("workspace")?.let { " of $it" } ?: "")
            "read_session" -> "Reading ${args.str("session") ?: "a session"}"
            "propose_message" -> "Proposed for ${args.str("session")}: ${args.str("text")}"
            "send_proposed" -> pending?.let { (ref, text) -> "Sending to ${ref.workspace}: $text" } ?: "Sending"
            else -> name
        }
    }

    override suspend fun call(name: String, arguments: String): String {
        val args = parse(arguments)
        return when (name) {
            "list_workspaces" -> {
                val refs = refs()
                WorkspaceDirectory.listing(refs, repo.workspaces.first(), repo.projects.first(), repo.hosts.first())
            }
            "workspace_overview" -> overview(args.str("workspace"))
            "read_session" -> when (val m = WorkspaceDirectory.resolve(refs(), args.str("session"))) {
                is WorkspaceDirectory.Match.NotFound -> m.message
                is WorkspaceDirectory.Match.Found -> screen(m.ref, READ_LINES)
            }
            "propose_message" -> propose(args.str("session"), args.str("text"))
            "send_proposed" -> sendProposed()
            else -> "Unknown function $name."
        }
    }

    private suspend fun refs(): List<SessionRef> = WorkspaceDirectory.sessions(
        repo.projects.first(), repo.workspaces.first(), repo.hosts.first(),
        repo.agentPresets.first(), repo.allSessions.first(), monitor.activity.value,
    )

    /** One SSH connection per host; a host that cannot be reached is reported, not fatal. */
    private suspend fun overview(workspace: String?): String {
        val all = refs()
        val chosen = if (workspace.isNullOrBlank()) all
        else all.filter { it.workspace.equals(workspace.trim(), ignoreCase = true) }
            .ifEmpty { all.filter { it.workspace.contains(workspace.trim(), ignoreCase = true) } }
        if (chosen.isEmpty()) {
            return if (workspace.isNullOrBlank()) "There are no sessions yet." else "No workspace matches \"$workspace\"."
        }
        val out = StringBuilder()
        for (refs in chosen.groupBy { it.hostEntity.id }.values) {
            val h = refs.first().hostEntity
            val connected = runCatching { service.connect(h) }
            val client = connected.getOrNull()?.client
            if (client == null) {
                val why = connected.exceptionOrNull()?.message
                for (r in refs) out.append(header(r)).append("  (host ${h.name} unreachable: $why)\n")
                continue
            }
            try {
                for (r in refs) {
                    // The visible screen only: enough to tell what an agent is doing or asking.
                    val text = runCatching { service.capture(client, h, r.session, history = null) }.getOrNull()
                    out.append(header(r))
                    if (text == null) out.append("  (not running)\n")
                    else out.append(ResponseExtractor.screenTail(text, OVERVIEW_LINES).prependIndent("  ")).append('\n')
                }
            } finally {
                service.close(client)
            }
        }
        return out.toString().take(MAX_OUTPUT)
    }

    private suspend fun screen(ref: SessionRef, lines: Int): String {
        val h = ref.hostEntity
        val connected = service.connect(h)
        try {
            val text = service.capture(connected.client, h, ref.session, history = 200)
                ?: return "${header(ref)}  (not running)"
            return (header(ref) + ResponseExtractor.screenTail(text, lines)).take(MAX_OUTPUT)
        } finally {
            service.close(connected.client)
        }
    }

    private suspend fun propose(session: String?, text: String?): String {
        if (text.isNullOrBlank()) return "Nothing to propose: text is empty."
        val ref = when (val m = WorkspaceDirectory.resolve(refs(), session)) {
            is WorkspaceDirectory.Match.NotFound -> return m.message
            is WorkspaceDirectory.Match.Found -> m.ref
        }
        pending = ref to text
        return "Not sent yet. Read it back and ask the user to confirm: \"$text\" to session \"${ref.name}\" " +
            "in workspace ${ref.workspace}."
    }

    /** Sends only what was proposed (and so read back); there is no way to send text the user did not hear. */
    private suspend fun sendProposed(): String {
        val (ref, text) = pending ?: return "Nothing is proposed. Propose the message first and read it back."
        pending = null
        service.sendText(ref.hostEntity, ref.session, text)
        return "Sent to \"${ref.name}\" in workspace ${ref.workspace}."
    }

    private fun header(r: SessionRef) =
        "Session id=${r.id} \"${r.name}\" in workspace ${r.workspace}" + (r.status?.let { " ($it)" } ?: "") + ":\n"

    private fun parse(arguments: String): JsonObject =
        runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrDefault(JsonObject(emptyMap()))

    private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private companion object {
        const val OVERVIEW_LINES = 12
        const val READ_LINES = 60
        /** Keeps one answer well inside the model's context and its spoken summary short. */
        const val MAX_OUTPUT = 8000
    }
}
