package dev.walkcode.app.ssh

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.schmizz.sshj.userauth.method.AuthPublickey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Spike 1: sshj authenticates with a Keystore-held Ed25519 key.
 *
 * Run with tools/spikes/spike1.ps1, which installs the APKs (without the
 * uninstall that connectedAndroidTest does, so the key survives), authorizes
 * the printed key on the hosts and then runs [connect] per host.
 */
@RunWith(AndroidJUnit4::class)
class SshSpikeTest {
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun deviceKey() {
        if (args.getString("reset") == "1") DeviceKey.delete()
        val key = DeviceKey.getOrCreate()
        val line = key.openSshPublicKey()
        File(context.filesDir, "device_key.pub").writeText(line + "\n")
        Log.i(TAG, "method=${DeviceKey.generationMethod} ${DeviceKey.lastAlgorithms} pub=$line")
        assertTrue(line.startsWith("ssh-ed25519 "))
    }

    @Test
    fun connect() {
        val host = args.getString("host") ?: error("missing -e host")
        val port = args.getString("port")?.toInt() ?: 22
        val user = args.getString("user") ?: error("missing -e user")
        val command = args.getString("cmd") ?: "echo walkcode-ok"
        val verifier = RecordingHostKeyVerifier()

        val started = System.nanoTime()
        Ssh.client().use { ssh ->
            ssh.addHostKeyVerifier(verifier)
            ssh.connectTimeout = 8_000
            ssh.connect(host, port)
            ssh.auth(user, AuthPublickey(DeviceKey.getOrCreate().keyProvider()))
            ssh.startSession().use { session ->
                val cmd = session.exec(command)
                val out = cmd.inputStream.bufferedReader().readText()
                cmd.join(15, TimeUnit.SECONDS)
                val ms = (System.nanoTime() - started) / 1_000_000
                Log.i(TAG, "host=$host:$port fp=${verifier.fingerprint} exit=${cmd.exitStatus} ms=$ms out=${out.trim().replace('\n', '|')}")
                assertEquals(0, cmd.exitStatus)
            }
        }
    }

    private companion object {
        const val TAG = "WalkcodeSpike"
    }
}
