package dev.walkcode.app.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator

class HostKeyTrustTest {

    companion object {
        private lateinit var keyA: KeyPair
        private lateinit var keyB: KeyPair

        @JvmStatic
        @BeforeClass
        fun setUp() {
            SshProviders.ensureInstalled()
            val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            keyA = gen.generateKeyPair()
            keyB = gen.generateKeyPair()
        }
    }

    @Test
    fun firstUse_isTrustedAndReported() {
        var reported: PinnedHostKey? = null
        val v = TofuHostKeyVerifier(expected = null) { reported = it }

        assertTrue(v.verify("h", 22, keyA.public))

        val expected = PinnedHostKey.of(keyA.public)
        assertEquals(HostKeyCheck.TrustedOnFirstUse(expected), v.check)
        assertEquals(expected, reported)
    }

    @Test
    fun matchingPin_isAccepted() {
        val pin = PinnedHostKey.of(keyA.public)
        val v = TofuHostKeyVerifier(expected = pin)

        assertTrue(v.verify("h", 22, keyA.public))
        assertEquals(HostKeyCheck.MatchesPin(pin), v.check)
    }

    @Test
    fun changedKey_isRejected() {
        val pin = PinnedHostKey.of(keyA.public)
        val v = TofuHostKeyVerifier(expected = pin)

        assertFalse(v.verify("h", 22, keyB.public))
        assertEquals(HostKeyCheck.Mismatch(pin, PinnedHostKey.of(keyB.public)), v.check)
    }

    @Test
    fun pin_detectsAlgorithmAndFingerprint() {
        val pin = PinnedHostKey.of(keyA.public)
        assertEquals("ssh-rsa", pin.algorithm)
        assertTrue(pin.fingerprint.startsWith("SHA256:"))
    }
}
