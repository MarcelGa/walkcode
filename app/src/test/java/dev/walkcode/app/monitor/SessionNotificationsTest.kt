package dev.walkcode.app.monitor

import dev.walkcode.app.data.Project
import dev.walkcode.app.data.Session
import dev.walkcode.app.data.Workspace
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionNotificationsTest {
    private fun workspace(id: Long, name: String, projectId: Long? = null, sortOrder: Int = 0) =
        Workspace(id = id, name = name, hostId = 1, projectId = projectId, directory = "~", slug = name, sortOrder = sortOrder)

    private fun session(id: Long, workspaceId: Long, label: String?, watched: Boolean = true, sortOrder: Int = 0) =
        Session(id = id, tmuxName = "wc-$id", workspaceId = workspaceId, ordinal = 0, watched = watched, label = label, sortOrder = sortOrder)

    @Test fun each_watched_session_gets_a_labelled_row_in_its_workspace() {
        val rows = SessionRows.of(
            sessions = listOf(
                session(1, 20, "build"),
                session(2, 10, "tests", sortOrder = 1),
                session(3, 10, "docs", sortOrder = 0),
                session(4, 10, "not watched", watched = false),
            ),
            workspaces = listOf(workspace(20, "Later", sortOrder = 1), workspace(10, "First", projectId = 5, sortOrder = 0)),
            projects = listOf(Project(id = 5, name = "Work")),
            messages = mapOf("wc-3" to "Done: wrote it"),
        )
        assertEquals(listOf("docs", "tests", "build"), rows.map { it.label })
        assertEquals(listOf("Work · First", "Work · First", "Later"), rows.map { it.workspace })
        assertEquals(listOf("Done: wrote it", SessionRows.NO_MESSAGE, SessionRows.NO_MESSAGE), rows.map { it.message })
    }

    @Test fun a_session_of_an_unknown_workspace_has_no_row() {
        assertTrue(SessionRows.of(listOf(session(1, 99, "x")), emptyList(), emptyList(), emptyMap()).isEmpty())
    }

    private val prompt = ScreenReading(ScreenReading.State.NEEDS_INPUT, "Proceed?")
    private val bell = HostAlert(1, "h", "s", HostAlert.Kind.ATTENTION, reading = prompt, screenChangedAt = 100)

    @Test fun a_second_bell_on_an_unchanged_screen_is_a_repeat() {
        assertTrue(bell.isRepeatOf(bell.copy()))
    }

    @Test fun the_first_alert_is_no_repeat() {
        assertFalse(bell.isRepeatOf(null))
    }

    @Test fun the_same_question_on_a_new_screen_alerts_again() {
        assertFalse(bell.copy(screenChangedAt = 160).isRepeatOf(bell))
    }

    @Test fun a_different_text_alerts_again() {
        assertFalse(bell.copy(reading = ScreenReading(ScreenReading.State.DONE, "Fixed")).isRepeatOf(bell))
    }

    @Test fun row_message_only_says_what_the_screen_can_prove() {
        assertEquals("Needs your input: Proceed?", prompt.message())
        assertEquals("Working: Reading 3 files", ScreenReading(ScreenReading.State.WORKING, "Reading 3 files").message())
        assertEquals("Working", ScreenReading(ScreenReading.State.WORKING, null).message())
        // No "Done:" here: an agent without a running-turn hint would read as finished mid-turn.
        assertEquals("compiling", ScreenReading(ScreenReading.State.DONE, "compiling").message())
        assertNull(ScreenReading(ScreenReading.State.DONE, null).message())
    }

    private class Reads(var screen: String? = "last line") {
        val captured = ArrayList<String>()
        val readings = ArrayList<Pair<String, ScreenReading>>()
        val refresh = ScreenRefresh(
            capture = { captured += it; screen },
            onReading = { s, r -> readings += s to r },
        )
    }

    @Test fun reads_a_screen_once_per_change() = runTest {
        val r = Reads()
        r.refresh.pass(setOf("a"), mapOf("a" to 100))
        r.refresh.pass(setOf("a"), mapOf("a" to 100))
        assertEquals("unchanged screen is not read again", listOf("a"), r.captured)
        r.refresh.pass(setOf("a"), mapOf("a" to 101))
        assertEquals(listOf("a", "a"), r.captured)
        assertEquals("last line", r.readings.last().second.line)
    }

    @Test fun reads_only_watched_sessions_with_known_activity() = runTest {
        val r = Reads()
        r.refresh.pass(setOf("a", "b"), mapOf("a" to 100, "c" to 100))
        assertEquals(listOf("a"), r.captured)
    }

    @Test fun retries_after_a_failed_read() = runTest {
        val r = Reads(screen = null)
        r.refresh.pass(setOf("a"), mapOf("a" to 100))
        assertTrue(r.readings.isEmpty())
        r.screen = "back"
        r.refresh.pass(setOf("a"), mapOf("a" to 100))
        assertEquals("back", r.readings.single().second.line)
    }

    @Test fun a_blank_screen_is_read_once_and_keeps_the_old_message() = runTest {
        val r = Reads(screen = "   ")
        r.refresh.pass(setOf("a"), mapOf("a" to 100))
        r.refresh.pass(setOf("a"), mapOf("a" to 100))
        assertEquals(1, r.captured.size)
        assertTrue(r.readings.isEmpty())
    }
}
