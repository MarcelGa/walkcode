package dev.walkcode.app.ssh

import net.schmizz.sshj.Config
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.PublicKey
import java.security.Security

/** Replaces Android's stripped-down "BC" provider with full BouncyCastle (once). */
object SshProviders {
    const val BC = BouncyCastleProvider.PROVIDER_NAME
    @Volatile private var installed = false

    @Synchronized
    fun ensureInstalled() {
        if (installed) return
        val bc = BouncyCastleProvider().apply {
            // Conscrypt resolves certificate public keys through a KeyFactory looked up by OID.
            // If full BC answers for Ed25519 (1.3.101.112), the Android Keystore receives a
            // BC key named "Ed25519" instead of the OID it expects and rejects its own keys
            // ("Unsupported Android Keystore public key algorithm: Ed25519"). sshj looks Ed25519
            // up by name, so dropping only the OID aliases is enough.
            for (oid in ED25519_OIDS) {
                remove("Alg.Alias.KeyFactory.$oid")
                remove("Alg.Alias.KeyFactory.OID.$oid")
            }
        }
        Security.removeProvider(BC)
        // Appended, not inserted first: platform TLS and the Keystore keep their priority.
        // sshj asks for "BC" by name, so it still gets the full provider.
        Security.addProvider(bc)
        SecurityUtils.setRegisterBouncyCastle(false)
        SecurityUtils.setSecurityProvider(BC)
        installed = true
    }

    private val ED25519_OIDS = listOf("1.3.101.112")
}

object Ssh {
    fun config(): Config {
        SshProviders.ensureInstalled()
        return DefaultConfig().apply {
            keyAlgorithms = keyAlgorithms.map {
                if (it.name == KeystoreEd25519Signature.keyAlgorithm().name) KeystoreEd25519Signature.keyAlgorithm() else it
            }
        }
    }

    fun client(): SSHClient = SSHClient(config())
}

/** Accepts any host key and records its fingerprint. Spike only; M1 replaces it with TOFU. */
class RecordingHostKeyVerifier : HostKeyVerifier {
    @Volatile var fingerprint: String? = null
        private set

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        fingerprint = SecurityUtils.getFingerprint(key)
        return true
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
}
