package dev.walkcode.app.assistant

import dev.walkcode.app.data.AgentPreset
import dev.walkcode.app.data.Host
import dev.walkcode.app.data.Project
import dev.walkcode.app.data.Session
import dev.walkcode.app.data.ShellKind
import dev.walkcode.app.data.Workspace
import dev.walkcode.app.monitor.SessionActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceDirectoryTest {

    private val host = Host(id = 1, name = "homelab", username = "dev", shell = ShellKind.TMUX)
    private val project = Project(id = 1, name = "Work")
    private val claude = AgentPreset(id = 1, name = "Claude Code", command = "claude")
    private val api = Workspace(id = 1, name = "api", hostId = 1, projectId = 1, directory = "~/api", agentPresetId = 1, slug = "api")
    private val web = Workspace(id = 2, name = "web", hostId = 1, projectId = 1, directory = "~/web", agentPresetId = 1, slug = "web")
    private val apiMain = Session(id = 1, tmuxName = "wc-api-1", workspaceId = 1, ordinal = 0, label = "main")
    private val apiTests = Session(id = 2, tmuxName = "wc-api-2", workspaceId = 1, ordinal = 1, autoTitle = "Fix tests")
    private val webOnly = Session(id = 3, tmuxName = "wc-web-1", workspaceId = 2, ordinal = 0)

    private fun refs(activity: Map<String, SessionActivity> = emptyMap()) = WorkspaceDirectory.sessions(
        listOf(project), listOf(api, web), listOf(host), listOf(claude), listOf(webOnly, apiTests, apiMain), activity,
    )

    @Test fun sessions_carry_names_workspace_host_and_agent_in_order() {
        val r = refs()
        assertEquals(listOf("wc-api-1", "wc-api-2", "wc-web-1"), r.map { it.id })
        assertEquals(listOf("main", "Fix tests", "#1"), r.map { it.name })
        assertEquals("api", r[0].workspace)
        assertEquals("homelab", r[0].host)
        assertEquals("Claude Code", r[0].agent)
        assertEquals(null, r[0].status)
    }

    @Test fun status_says_busy_or_how_long_quiet_and_unseen_output() {
        assertEquals("producing output right now", WorkspaceDirectory.describe(SessionActivity(active = true, unread = false, idleSec = 3)))
        assertEquals("quiet for 45 s", WorkspaceDirectory.describe(SessionActivity(active = true, unread = false, idleSec = 45)))
        assertEquals(
            "quiet for 4 min, with output the user has not seen",
            WorkspaceDirectory.describe(SessionActivity(active = false, unread = true, idleSec = 250)),
        )
    }

    @Test fun listing_groups_sessions_under_workspaces() {
        val text = WorkspaceDirectory.listing(
            refs(mapOf("wc-api-1" to SessionActivity(active = true, unread = false, idleSec = 1))),
            listOf(api, web), listOf(project), listOf(host),
        )
        assertTrue(text, text.contains("Workspace \"api\" (project Work) on host homelab, directory ~/api"))
        assertTrue(text, text.contains("session id=wc-api-1 name=\"main\" agent=Claude Code status=producing output right now"))
        assertTrue(text, text.contains("session id=wc-web-1 name=\"#1\" agent=Claude Code status=not monitored"))
    }

    @Test fun resolves_id_then_names_then_parts() {
        val r = refs()
        assertEquals("wc-api-2", (WorkspaceDirectory.resolve(r, "wc-api-2") as WorkspaceDirectory.Match.Found).ref.id)
        assertEquals("wc-api-1", (WorkspaceDirectory.resolve(r, "MAIN") as WorkspaceDirectory.Match.Found).ref.id)
        // A workspace with one session stands for it.
        assertEquals("wc-web-1", (WorkspaceDirectory.resolve(r, "web") as WorkspaceDirectory.Match.Found).ref.id)
        assertEquals("wc-api-2", (WorkspaceDirectory.resolve(r, "tests") as WorkspaceDirectory.Match.Found).ref.id)
    }

    @Test fun ambiguous_or_unknown_names_explain_what_to_do() {
        val r = refs()
        val ambiguous = WorkspaceDirectory.resolve(r, "api") as WorkspaceDirectory.Match.NotFound
        assertTrue(ambiguous.message, ambiguous.message.contains("wc-api-1") && ambiguous.message.contains("wc-api-2"))
        val unknown = WorkspaceDirectory.resolve(r, "billing") as WorkspaceDirectory.Match.NotFound
        assertTrue(unknown.message, unknown.message.contains("list_workspaces"))
        assertTrue(WorkspaceDirectory.resolve(r, " ") is WorkspaceDirectory.Match.NotFound)
    }
}
