package dev.walkcode.app.ssh

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.walkcode.app.data.Host
import dev.walkcode.app.data.ShellKind
import dev.walkcode.app.data.WalkcodeRepository
import dev.walkcode.app.session.SessionService
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * M1 acceptance: [HostConnector] pins a host key on first use, reuses it on the
 * next connection, rejects a changed key, and falls back from the LAN address to
 * the tailnet address. Needs the device key authorized on the Docker test host,
 * so run it through tools/spikes/m1-connector.ps1.
 */
@RunWith(AndroidJUnit4::class)
class HostConnectorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo by lazy { WalkcodeRepository.get(context) }
    private val connector by lazy { HostConnector(DeviceKey.getOrCreate()) }

    @Test
    fun pinsOnFirstUse_thenMatches_andRejectsChange() {
      runBlocking {
        val hostId = repo.addHost("docker test host", "dev", ShellKind.TMUX, lanHost = "10.0.2.2", port = 2222)
        val host = repo.host(hostId)!!

        val first = connector.connect(host)
        try {
            assertTrue("first connect should trust", first.newlyTrusted)
            assertTrue("should be a SHA-256 fingerprint: ${first.hostKey.fingerprint}", first.hostKey.fingerprint.startsWith("SHA256:"))
            assertEquals("dev", echo(first.client))
        } finally {
            first.client.close()
        }

        repo.trustHostKey(hostId, first.hostKey.algorithm, first.hostKey.fingerprint)
        val trusted = repo.host(hostId)!!

        val second = connector.connect(trusted)
        try {
            assertFalse("still-trusted host should not re-pin", second.newlyTrusted)
            assertEquals(first.hostKey, second.hostKey)
        } finally {
            second.client.close()
        }

        val changed = trusted.copy(hostKeyFingerprint = "SHA256:this-is-not-the-real-key")
        try {
            connector.connect(changed).client.close()
            fail("expected HostKeyMismatchException")
        } catch (e: HostKeyMismatchException) {
            assertEquals("SHA256:this-is-not-the-real-key", e.expected.fingerprint)
            assertEquals(first.hostKey.fingerprint, e.observed.fingerprint)
        }
        Log.i(TAG, "pinned ${first.hostKey.algorithm} ${first.hostKey.fingerprint}")
      }
    }

    @Test
    fun fallsBackToSecondAddress() {
      runBlocking {
        val hostId = repo.addHost(
            "docker test host via fallback", "dev", ShellKind.TMUX,
            lanHost = "127.0.0.1", tailnetHost = "10.0.2.2", port = 2222,
        )
        val connected = connector.connect(repo.host(hostId)!!)
        try {
            assertEquals("10.0.2.2", connected.address)
            assertEquals("dev", echo(connected.client))
        } finally {
            connected.client.close()
        }
      }
    }

    /** On VPN the LAN address times out; once the tailnet one has worked it is tried first. */
    @Test
    fun remembersLastWorkingAddress() {
      runBlocking {
        val hostId = repo.addHost(
            "docker test host via vpn", "dev", ShellKind.TMUX,
            lanHost = "10.255.255.1", tailnetHost = "10.0.2.2", port = 2222,
        )
        val service = SessionService(repo, connector)

        val firstAttempts = mutableListOf<String>()
        service.connect(repo.host(hostId)!!, onAttempt = { firstAttempts += it }).client.close()
        assertEquals(listOf("10.255.255.1", "10.0.2.2"), firstAttempts)
        assertEquals("10.0.2.2", repo.host(hostId)!!.lastAddress)

        // A Host read before the first connect still uses the remembered address.
        val stale = Host(id = hostId, name = "stale", username = "dev", shell = ShellKind.TMUX, lanHost = "10.255.255.1", tailnetHost = "10.0.2.2", port = 2222)
        val secondAttempts = mutableListOf<String>()
        val started = System.currentTimeMillis()
        val second = service.connect(stale, onAttempt = { secondAttempts += it })
        val tookMs = System.currentTimeMillis() - started
        try {
            assertEquals(listOf("10.0.2.2"), secondAttempts)
            assertEquals("dev", echo(second.client))
            assertTrue("second connect took $tookMs ms", tookMs < 5_000)
        } finally {
            second.client.close()
        }
        Log.i(TAG, "remembered 10.0.2.2, second connect in $tookMs ms")
      }
    }

    @Test
    fun noAddress_fails() {
      runBlocking {
        val host = Host(name = "nowhere", username = "u", shell = ShellKind.TMUX)
        try {
            connector.connect(host).client.close()
            fail("expected NoAddressException")
        } catch (_: NoAddressException) {
        }
      }
    }

    private fun echo(ssh: SSHClient): String =
        ssh.startSession().use { session ->
            val cmd = session.exec("echo dev")
            val out = cmd.inputStream.bufferedReader().readText().trim()
            cmd.join(15, TimeUnit.SECONDS)
            assertEquals(0, cmd.exitStatus)
            out
        }

    private companion object {
        const val TAG = "WalkcodeSpike"
    }
}
