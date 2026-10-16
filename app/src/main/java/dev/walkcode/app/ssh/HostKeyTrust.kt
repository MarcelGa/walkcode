package dev.walkcode.app.ssh

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

/** A host key we have seen and chosen to trust: negotiated algorithm plus SHA-256 fingerprint. */
data class PinnedHostKey(val algorithm: String, val fingerprint: String) {
    companion object {
        /**
         * OpenSSH-style SHA-256 fingerprint (`SHA256:<base64>`, no padding) over the
         * key's wire encoding, matching `ssh-keygen -lf`. sshj's own
         * `SecurityUtils.getFingerprint` still emits MD5, which is why this is computed here.
         */
        fun of(key: PublicKey): PinnedHostKey {
            val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
            val digest = MessageDigest.getInstance("SHA-256").digest(blob)
            val encoded = Base64.getEncoder().withoutPadding().encodeToString(digest)
            return PinnedHostKey(KeyType.fromKey(key).toString(), "SHA256:$encoded")
        }
    }
}

sealed interface HostKeyCheck {
    /** No pin was stored; the caller should persist [key]. */
    data class TrustedOnFirstUse(val key: PinnedHostKey) : HostKeyCheck

    /** Pin matched. */
    data class MatchesPin(val key: PinnedHostKey) : HostKeyCheck

    /** Pin did not match; the connection is refused. */
    data class Mismatch(val expected: PinnedHostKey, val observed: PinnedHostKey) : HostKeyCheck
}

/**
 * Trust On First Use: accepts the host key when there is no pin yet (recording it
 * through [onTrust] so the caller can store it), accepts a matching pin, and
 * rejects anything else. The decision is kept in [check] for the caller to read
 * after sshj reports the failure.
 */
class TofuHostKeyVerifier(
    private val expected: PinnedHostKey?,
    private val onTrust: (PinnedHostKey) -> Unit = {},
) : HostKeyVerifier {

    @Volatile var check: HostKeyCheck? = null
        private set

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val observed = PinnedHostKey.of(key)
        val outcome = when {
            expected == null -> HostKeyCheck.TrustedOnFirstUse(observed).also { onTrust(observed) }
            expected == observed -> HostKeyCheck.MatchesPin(observed)
            else -> HostKeyCheck.Mismatch(expected, observed)
        }
        check = outcome
        return outcome !is HostKeyCheck.Mismatch
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
}

/** A pinned host key differed from what the server presented. */
class HostKeyMismatchException(val expected: PinnedHostKey, val observed: PinnedHostKey) :
    Exception("host key changed: expected ${expected.fingerprint}, got ${observed.fingerprint}")
