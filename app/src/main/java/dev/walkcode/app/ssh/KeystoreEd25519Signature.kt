package dev.walkcode.app.ssh

import com.hierynomus.sshj.key.KeyAlgorithms
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SSHRuntimeException
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.signature.Signature
import java.security.PrivateKey
import java.security.PublicKey

/**
 * `ssh-ed25519` signature that signs with an Android Keystore key and verifies
 * (server host keys) with BouncyCastle, exactly like sshj's own SignatureEdDSA.
 */
class KeystoreEd25519Signature : Signature {
    private var engine: java.security.Signature? = null

    override fun getSignatureName(): String = KeyType.ED25519.toString()

    override fun initVerify(pubkey: PublicKey) {
        engine = SecurityUtils.getSignature("Ed25519").apply { initVerify(pubkey) }
    }

    override fun initSign(prvkey: PrivateKey) {
        engine = if (prvkey is KeystorePrivateKey) {
            // No explicit provider: JCA delayed selection routes Keystore keys to AndroidKeyStore.
            java.security.Signature.getInstance("Ed25519").apply { initSign(prvkey.delegate) }
        } else {
            SecurityUtils.getSignature("Ed25519").apply { initSign(prvkey) }
        }
    }

    override fun update(H: ByteArray) = update(H, 0, H.size)

    override fun update(H: ByteArray, off: Int, len: Int) {
        requireEngine().update(H, off, len)
    }

    override fun sign(): ByteArray = requireEngine().sign()

    override fun encode(signature: ByteArray): ByteArray = signature

    override fun verify(sig: ByteArray): Boolean {
        val buf = Buffer.PlainBuffer(sig)
        val algo = buf.readString()
        if (algo != signatureName) throw SSHRuntimeException("Expected '$signatureName' signature, got '$algo'")
        return requireEngine().verify(buf.readBytes())
    }

    private fun requireEngine() = engine ?: throw IllegalStateException("Signature not initialised")

    class Factory : net.schmizz.sshj.common.Factory.Named<Signature> {
        override fun getName(): String = KeyType.ED25519.toString()
        override fun create(): Signature = KeystoreEd25519Signature()
    }

    companion object {
        /** Drop-in replacement for `KeyAlgorithms.EdDSA25519()`. */
        fun keyAlgorithm(): Factory.Named<com.hierynomus.sshj.key.KeyAlgorithm> =
            KeyAlgorithms.Factory(KeyType.ED25519.toString(), Factory(), KeyType.ED25519)
    }
}
