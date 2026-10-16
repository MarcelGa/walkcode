package dev.walkcode.app.poll

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.walkcode.app.ssh.DeviceKey
import dev.walkcode.app.ssh.RecordingHostKeyVerifier
import dev.walkcode.app.ssh.Ssh
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.method.AuthPublickey
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Spike 3: the host poller over SSH drives unread/quiet/attention on the phone.
 * Run per host with tools/spikes/spike3.ps1.
 */
@RunWith(AndroidJUnit4::class)
class PollSpikeTest {
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val platform = if (args.getString("platform") == "windows") HostPlatform.WINDOWS else HostPlatform.POSIX
    private val mux = if (platform == HostPlatform.WINDOWS) "psmux" else "tmux"
    private val target = "$SESSION:0.0"
    private val started = System.nanoTime()
    private val timeline = StringBuilder()

    private fun ms() = (System.nanoTime() - started) / 1_000_000
    private fun note(s: String) { timeline.append("${ms()}ms $s\n"); Log.i(TAG, s) }

    @Test
    fun pollTurns() {
        val host = args.getString("host")!!
        val port = args.getString("port")?.toInt() ?: 22
        val user = args.getString("user")!!
        val agent = args.getString("agent")!!

        Ssh.client().use { ssh ->
            ssh.addHostKeyVerifier(RecordingHostKeyVerifier())
            ssh.connectTimeout = 8_000
            ssh.connect(host, port)
            ssh.auth(user, AuthPublickey(DeviceKey.getOrCreate().keyProvider()))

            prepareSession(ssh, agent)

            val tracker = ActivityTracker(quietAfterSec = QUIET_SEC, viewGraceSec = 3)
            val alerts = ArrayList<Pair<Long, ActivityTracker.Alert>>()
            var firstActiveMs = -1L
            var readyMs = -1L
            val script = context.assets.open(if (platform == HostPlatform.WINDOWS) PollerCommand.WINDOWS_ASSET else PollerCommand.POSIX_ASSET)
                .bufferedReader().readText()
            val command = PollerCommand.build(platform, script, PollTiming(interval = 1, idleGrace = 3, heartbeat = 10))
            note("poller command length ${command.length}")

            HostPoller(ssh, command).use { poller ->
                var sentAt = Long.MAX_VALUE
                val thread = Thread {
                    runCatching {
                        poller.run { e ->
                            synchronized(tracker) {
                                if (e is PollEvent.Ready) readyMs = ms()
                                if (e is PollEvent.Pane && e.target == target) {
                                    note("event ${if (e.active) "active" else "idle"} since=${e.since} bell=${e.bell} title='${e.title}'")
                                    if (e.active && firstActiveMs < 0 && ms() >= sentAt) firstActiveMs = ms()
                                }
                                tracker.onEvent(e).forEach { a -> alerts += ms() to a; note("ALERT $a") }
                            }
                        }
                    }.onFailure { note("poller ended: $it ${poller.errorOutput()}") }
                }.apply { isDaemon = true; start() }

                waitFor(20_000) { readyMs >= 0 }
                check(readyMs >= 0) { "poller never became ready: ${poller.errorOutput()}" }

                // Turn 1, not focused: expect attention (bell) and quiet.
                sentAt = ms()
                exec(ssh, "$mux send-keys -t $SESSION \"task one\" Enter")
                note("sent task one")
                waitFor(40_000) {
                    synchronized(tracker) {
                        tracker.tick().forEach { a -> alerts += ms() to a; note("ALERT $a") }
                        alerts.any { it.second is ActivityTracker.Alert.NeedsAttention && it.first > sentAt } &&
                            alerts.any { it.second is ActivityTracker.Alert.Quiet && it.first > sentAt }
                    }
                }
                val turn1 = alerts.filter { it.first > sentAt && it.second.target == target }
                val attentionMs = turn1.firstOrNull { it.second is ActivityTracker.Alert.NeedsAttention }?.first?.minus(sentAt) ?: -1
                val quietMs = turn1.firstOrNull { it.second is ActivityTracker.Alert.Quiet }?.first?.minus(sentAt) ?: -1
                val unreadAfterTurn = synchronized(tracker) { tracker.isUnread(target) }
                val activeMs = if (firstActiveMs >= 0) firstActiveMs - sentAt else -1

                // Turn 2, focused (session on screen): expect no alerts and nothing unread.
                synchronized(tracker) { tracker.focused = target }
                val sent2 = ms()
                exec(ssh, "$mux send-keys -t $SESSION \"task two\" Enter")
                note("sent task two (focused)")
                Thread.sleep(((5 + 3 + QUIET_SEC + 3) * 1000).toLong())
                val focusedAlerts = synchronized(tracker) {
                    tracker.tick().forEach { a -> alerts += ms() to a }
                    alerts.count { it.first > sent2 && it.second.target == target }
                }
                synchronized(tracker) { tracker.focused = null }
                val unreadAfterFocus = synchronized(tracker) { tracker.isUnread(target) }

                val bytes = poller.bytesReceived.get()
                val result = "RESULT host=$host platform=$platform activeMs=$activeMs attentionMs=$attentionMs quietMs=$quietMs " +
                    "unreadAfterTurn=$unreadAfterTurn focusedAlerts=$focusedAlerts unreadAfterFocus=$unreadAfterFocus " +
                    "bytes=$bytes seconds=${ms() / 1000}"
                Log.i(TAG, result)
                Log.i(TAG, "TIMELINE\n$timeline")
                poller.close()
                thread.join(3_000)

                assertTrue("active reported", activeMs in 0..3_000)
                assertTrue("attention", attentionMs in 0..10_000)
                assertTrue("quiet", quietMs in 5_000..20_000)
                assertTrue("unread after unfocused turn", unreadAfterTurn)
                assertTrue("no alerts while focused", focusedAlerts == 0)
                assertTrue("read after focus", !unreadAfterFocus)
            }
            exec(ssh, "$mux kill-session -t $SESSION")
        }
    }

    private fun prepareSession(ssh: SSHClient, agent: String) {
        if (platform == HostPlatform.WINDOWS) {
            exec(ssh, "psmux kill-session -t $SESSION 2>nul & psmux new-session -d -s $SESSION -x 80 -y 24 " +
                "pwsh -NoLogo -NoExit -File %USERPROFILE%\\.config\\walkcode\\shell-init.ps1")
        } else {
            exec(ssh, "tmux kill-session -t $SESSION 2>/dev/null; tmux new-session -d -s $SESSION -x 80 -y 24")
        }
        Thread.sleep(3_000)
        exec(ssh, "$mux send-keys -t $SESSION \"$agent\" Enter")
        Thread.sleep(4_000)
    }

    private fun exec(ssh: SSHClient, command: String): String = ssh.startSession().use { s ->
        val c = s.exec(command)
        val out = c.inputStream.bufferedReader().readText()
        c.join(15, TimeUnit.SECONDS)
        out
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val end = ms() + timeoutMs
        while (ms() < end && !condition()) Thread.sleep(500)
    }

    private companion object {
        const val TAG = "WalkcodePoll"
        const val SESSION = "wc-poll"
        const val QUIET_SEC = 6L
    }
}
