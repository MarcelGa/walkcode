package dev.walkcode.app.poll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class PollProtocolTest {
    @Test fun parsesPaneRecord() {
        val e = PollProtocol.parse("S\t100\twc:0.0\twc\tactive\t99\tabc\t2\t0\tbash\t| fake\tagent") as PollEvent.Pane
        assertEquals("wc:0.0", e.target)
        assertEquals(true, e.active)
        assertEquals(99L, e.since)
        assertEquals(2, e.bell)
        assertEquals("bash", e.command)
        assertEquals("| fake\tagent", e.title)   // title is last and may contain tabs
    }

    @Test fun parsesPlaceholdersAndCrlf() {
        val e = PollProtocol.parse("S\t1\tt:0.0\tt\tidle\t0\th\t0\t1\t-\t\r") as PollEvent.Pane
        assertNull(e.command)
        assertEquals(true, e.dead)
        assertEquals("", e.title)
    }

    @Test fun parsesOtherTypesAndIgnoresJunk() {
        assertEquals(PollEvent.Gone(5, "a:0.0"), PollProtocol.parse("G\t5\ta:0.0"))
        assertEquals(PollEvent.Ready(5), PollProtocol.parse("R\t5"))
        assertEquals(PollEvent.Heartbeat(5), PollProtocol.parse("H\t5"))
        assertNull(PollProtocol.parse("bash: tmux: command not found"))
        assertNull(PollProtocol.parse("X\t5"))
    }
}

class PollerCommandTest {
    private val assets = File("src/main/assets/poller")

    @Test fun windowsCommandFitsCmdLimit() {
        val cmd = PollerCommand.build(HostPlatform.WINDOWS, File(assets, "poll.ps1").readText(), PollTiming())
        assertTrue("length ${cmd.length}", cmd.length <= PollerCommand.WINDOWS_MAX_LENGTH)
        val script = String(Base64.getDecoder().decode(cmd.substringAfterLast(' ')), Charsets.UTF_16LE)
        assertTrue(script.startsWith("& {\n") && script.endsWith("\n} 2 4 30"))
    }

    @Test fun posixCommandRoundTrips() {
        val source = File(assets, "poll.sh").readText()
        val cmd = PollerCommand.build(HostPlatform.POSIX, source, PollTiming(1, 3, 10))
        val b64 = cmd.substringAfter("printf %s '").substringBefore("'")
        assertEquals(PollerCommand.minify(source), String(Base64.getDecoder().decode(b64)))
        assertTrue(cmd.endsWith("| bash -s -- 1 3 10"))
    }

    @Test fun minifyKeepsFormatStringsThatContainHash() {
        val out = PollerCommand.minify("  # comment\n\n  fmt=\$'#{a}\\t#{b}'\n    echo hi  \n")
        assertEquals("fmt=\$'#{a}\\t#{b}'\necho hi", out)
    }
}

class ActivityTrackerTest {
    private var nanos = 0L
    private fun tracker(quiet: Long = 10, enabled: (String) -> Boolean = { true }) =
        ActivityTracker(quietAfterSec = quiet, viewGraceSec = 3, quietEnabled = enabled, nanoTime = { nanos })

    private fun pane(t: Long, active: Boolean, since: Long, hash: String, bell: Int = 0, target: String = "wc:0.0") =
        PollEvent.Pane(t, target, target.substringBefore(':'), active, since, hash, bell, false, "bash", "agent")

    private fun advance(seconds: Long) { nanos += seconds * 1_000_000_000L }

    @Test fun firstSightingIsRead() {
        val tr = tracker()
        tr.onEvent(pane(100, false, 0, "h0"))
        assertEquals(false, tr.isUnread("wc:0.0"))
    }

    @Test fun turnBecomesUnreadThenQuietOnce() {
        val tr = tracker(quiet = 10)
        tr.onEvent(pane(100, false, 0, "h0"))
        assertTrue(tr.onEvent(pane(110, true, 110, "h1")).isEmpty())
        assertTrue(tr.onEvent(pane(118, false, 115, "h2")).isEmpty())   // idle since 115
        assertTrue(tr.isUnread("wc:0.0"))
        advance(5)                                                        // host 123: 8 s quiet
        assertTrue(tr.tick().isEmpty())
        advance(3)                                                        // host 126: 11 s quiet
        val alerts = tr.tick()
        assertEquals(listOf(ActivityTracker.Alert.Quiet("wc:0.0", "wc", 115)), alerts)
        advance(30)
        assertTrue("fires once per quiet period", tr.tick().isEmpty())
    }

    @Test fun bellAlertsImmediatelyOncePerIncrease() {
        val tr = tracker()
        tr.onEvent(pane(100, false, 0, "h0", bell = 4))                  // old bells are not news
        val a = tr.onEvent(pane(110, true, 110, "h1", bell = 6))         // psmux: +2 per bell
        assertEquals(listOf(ActivityTracker.Alert.NeedsAttention("wc:0.0", "wc")), a)
        assertTrue(tr.onEvent(pane(113, false, 110, "h1", bell = 6)).isEmpty())
    }

    @Test fun quietCanBeDisabledPerPane() {
        val tr = tracker(quiet = 5, enabled = { false })
        tr.onEvent(pane(100, false, 0, "h0"))
        tr.onEvent(pane(110, true, 110, "h1"))
        tr.onEvent(pane(114, false, 111, "h2"))
        advance(60)
        assertTrue(tr.tick().isEmpty())
        assertTrue(tr.isUnread("wc:0.0"))
    }

    @Test fun viewingClearsUnreadAndSuppressesPendingQuiet() {
        val tr = tracker(quiet = 10)
        tr.onEvent(pane(100, false, 0, "h0"))
        tr.onEvent(pane(110, true, 110, "h1"))
        tr.onEvent(pane(114, false, 111, "h2"))
        tr.markViewed("wc:0.0")
        assertEquals(false, tr.isUnread("wc:0.0"))
        advance(60)
        assertTrue(tr.tick().isEmpty())
    }

    @Test fun focusedPaneNeverAlertsAndStaysRead() {
        val tr = tracker(quiet = 5)
        tr.onEvent(pane(100, false, 0, "h0"))
        tr.focused = "wc:0.0"
        assertTrue(tr.onEvent(pane(110, true, 110, "h1", bell = 1)).isEmpty())
        assertTrue(tr.onEvent(pane(114, false, 111, "h2", bell = 1)).isEmpty())
        advance(60)
        assertTrue(tr.tick().isEmpty())
        assertEquals(false, tr.isUnread("wc:0.0"))
        tr.focused = null                                                 // leaving marks it viewed
        assertEquals(false, tr.isUnread("wc:0.0"))
    }

    @Test fun reconnectKeepsHistoryWhenScreenUnchanged() {
        val first = tracker()
        first.onEvent(pane(100, false, 0, "h0"))
        first.onEvent(pane(110, true, 110, "h1"))
        first.onEvent(pane(114, false, 111, "h2"))
        val state = first.persist()

        val same = tracker().apply { restore(state) }
        same.onEvent(pane(500, false, 0, "h2"))
        assertTrue("still unread from before", same.isUnread("wc:0.0"))

        val viewed = tracker().apply { restore(state.mapValues { it.value.copy(viewedAt = 200) }) }
        viewed.onEvent(pane(500, false, 0, "h2"))
        assertEquals(false, viewed.isUnread("wc:0.0"))

        val changed = tracker().apply { restore(state.mapValues { it.value.copy(viewedAt = 200) }) }
        changed.onEvent(pane(500, false, 0, "h9"))
        assertTrue("changed while disconnected", changed.isUnread("wc:0.0"))
    }

    @Test fun bellsWhileDisconnectedAlertOnReconnect() {
        val first = tracker()
        first.onEvent(pane(100, false, 0, "h0", bell = 2))
        val state = first.persist()
        val next = tracker().apply { restore(state) }
        assertEquals(1, next.onEvent(pane(500, false, 0, "h3", bell = 4)).count { it is ActivityTracker.Alert.NeedsAttention })
    }

    @Test fun goneRemovesPane() {
        val tr = tracker()
        tr.onEvent(pane(100, false, 0, "h0"))
        tr.onEvent(PollEvent.Gone(101, "wc:0.0"))
        assertTrue(tr.panes().isEmpty())
    }
}
