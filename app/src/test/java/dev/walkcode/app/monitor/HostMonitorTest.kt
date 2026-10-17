package dev.walkcode.app.monitor

import dev.walkcode.app.poll.PollEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class HostMonitorTest {

    private class FakeLink(private val events: List<PollEvent>, private val endWith: Exception = IOException("poller ended")) : PollLink {
        override val address = "fake"
        override fun run(onEvent: (PollEvent) -> Unit) {
            events.forEach(onEvent)
            throw endWith
        }
        override fun close() {}
    }

    private fun pane(t: Long, session: String, bell: Int, hash: String = "h", active: Boolean = false, since: Long = 100) =
        PollEvent.Pane(t, "$session:0.0", session, active, since = since, hash = hash, bell = bell, dead = false, command = "claude", title = "")

    @Test
    fun alertsOnlyForWatchedSessions() = runTest {
        val alerts = ArrayList<HostAlert>()
        val link = FakeLink(
            listOf(
                pane(1000, "wc-a-1", bell = 0), pane(1000, "wc-b-2", bell = 0), PollEvent.Ready(1000),
                // Bell counters go up for both; only the watched one may alert.
                pane(1005, "wc-a-1", bell = 1), pane(1005, "wc-b-2", bell = 1),
            ),
        )
        val monitor = HostMonitor(1, "h", open = { link }, watched = { setOf("wc-a-1") }, onAlert = { alerts += it },
            io = StandardTestDispatcher(testScheduler))
        monitor.launchIn(backgroundScope)
        runCurrent()
        assertEquals(1, alerts.size)
        assertEquals("wc-a-1", alerts[0].session)
        assertEquals(HostAlert.Kind.ATTENTION, alerts[0].kind)
    }

    @Test
    fun focusedSessionDoesNotAlert() = runTest {
        val alerts = ArrayList<HostAlert>()
        val link = FakeLink(listOf(pane(1000, "wc-a-1", 0), PollEvent.Ready(1000), pane(1005, "wc-a-1", 1)))
        val monitor = HostMonitor(1, "h", open = { link }, watched = { setOf("wc-a-1") }, onAlert = { alerts += it },
            io = StandardTestDispatcher(testScheduler))
        monitor.setFocused("wc-a-1")
        monitor.launchIn(backgroundScope)
        runCurrent()
        assertTrue("focused session must not alert: $alerts", alerts.isEmpty())
    }

    @Test
    fun reconnectsWithBackoffAndRecovers() = runTest {
        var opens = 0
        val statuses = ArrayList<LinkStatus>()
        val monitor = HostMonitor(
            1, "h",
            open = {
                opens++
                if (opens < 3) throw IOException("unreachable") else FakeLink(listOf(pane(1000, "wc-a-1", 0), PollEvent.Ready(1000)))
            },
            watched = { setOf("wc-a-1") }, onAlert = {},
            backoffMs = listOf(1_000, 2_000), io = StandardTestDispatcher(testScheduler),
        )
        monitor.launchIn(backgroundScope)
        runCurrent()
        assertEquals("first attempt fails", LinkStatus.Reconnecting(1, "unreachable"), monitor.status.value)
        advanceTimeBy(1_001); runCurrent()
        assertEquals("second attempt fails", LinkStatus.Reconnecting(2, "unreachable"), monitor.status.value)
        assertEquals(2, opens)
        advanceTimeBy(2_001); runCurrent()
        assertEquals(3, opens)
        // The link connected and its poller then ended, so the monitor is retrying again from 1.
        assertTrue(monitor.status.value is LinkStatus.Reconnecting)
        assertTrue("activity from the connected link is published", monitor.activity.value.containsKey("wc-a-1"))
    }

    @Test
    fun quietAlertAfterOutputGoesSilent() = runTest {
        val alerts = ArrayList<HostAlert>()
        val link = FakeLink(
            listOf(
                pane(1000, "wc-a-1", 0, hash = "x"), PollEvent.Ready(1000),
                pane(1010, "wc-a-1", 0, hash = "y", active = true, since = 1010),   // output arrives
                pane(1014, "wc-a-1", 0, hash = "y", active = false, since = 1010),  // goes idle
                PollEvent.Heartbeat(1040),                            // 26 s of silence >= 15 s
            ),
        )
        val monitor = HostMonitor(1, "h", open = { link }, watched = { setOf("wc-a-1") }, onAlert = { alerts += it },
            io = StandardTestDispatcher(testScheduler))
        monitor.launchIn(backgroundScope)
        runCurrent()
        assertTrue("expected a quiet alert, got $alerts", alerts.any { it.kind == HostAlert.Kind.QUIET })
    }
}
