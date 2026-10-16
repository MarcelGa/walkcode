package dev.walkcode.app.ssh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.NamedParameterSpec
import java.security.spec.X509EncodedKeySpec

/**
 * The phone's SSH identity: one Ed25519 key pair generated inside the Android
 * Keystore. The private key never leaves secure hardware; signing goes through
 * [KeystoreEd25519Signature].
 */
class DeviceKey private constructor(
    /** Opaque Keystore handle; usable only for signing. */
    val keystorePrivate: PrivateKey,
    /** Public key re-encoded with BouncyCastle so sshj recognises it as Ed25519. */
    val public: PublicKey,
) {
    /** `ssh-ed25519 AAAA... comment`, ready for `authorized_keys`. */
    fun openSshPublicKey(comment: String = DEFAULT_COMMENT): String {
        val blob = Buffer.PlainBuffer().putPublicKey(public).compactData
        return "${KeyType.ED25519} ${Base64.encodeToString(blob, Base64.NO_WRAP)} $comment"
    }

    fun keyProvider(): KeyProvider = object : KeyProvider {
        override fun getPrivate(): PrivateKey = KeystorePrivateKey(keystorePrivate)
        override fun getPublic(): PublicKey = this@DeviceKey.public
        override fun getType(): KeyType = KeyType.ED25519
    }

    companion object {
        const val ALIAS = "walkcode-device-ed25519"
        const val DEFAULT_COMMENT = "walkcode@android"

        /** Which Keystore parameter style created the key (for the spike report). */
        @Volatile var generationMethod: String = "existing"
            private set

        /** Algorithm names reported by Keystore and the certificate (for the spike report). */
        @Volatile var lastAlgorithms: String = ""
            private set

        fun getOrCreate(): DeviceKey {
            SshProviders.ensureInstalled()
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!ks.containsAlias(ALIAS)) generate()
            // Not getEntry(): PrivateKeyEntry insists the private key and certificate report the
            // same algorithm name, which Keystore (OID) and the certificate parser disagree on.
            val private = ks.getKey(ALIAS, null) as PrivateKey
            val certKey = ks.getCertificate(ALIAS).publicKey
            lastAlgorithms = "private=${private.algorithm} cert=${certKey.algorithm}"
            return DeviceKey(private, toBouncyCastle(certKey))
        }

        fun delete() {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
        }

        private fun generate() {
            val attempts = listOf<Pair<String, () -> java.security.spec.AlgorithmParameterSpec>>(
                "EC+NamedParameterSpec.ED25519" to { NamedParameterSpec.ED25519 },
                "EC+ECGenParameterSpec(ed25519)" to { ECGenParameterSpec("ed25519") },
            )
            var last: Exception? = null
            for ((name, spec) in attempts) {
                try {
                    val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                    kpg.initialize(
                        KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                            .setAlgorithmParameterSpec(spec())
                            .setDigests(KeyProperties.DIGEST_NONE)
                            .build(),
                    )
                    kpg.generateKeyPair()
                    generationMethod = name
                    return
                } catch (e: Exception) {
                    last = e
                }
            }
            throw IllegalStateException("Keystore Ed25519 key generation failed", last)
        }

        /** Ed25519 SubjectPublicKeyInfo starts with OID 1.3.101.112 (06 03 2B 65 70). */
        private fun toBouncyCastle(key: PublicKey): PublicKey {
            val spki = key.encoded
            require(spki.size == 44 && spki[4] == 0x06.toByte() && spki[7] == 0x65.toByte() && spki[8] == 0x70.toByte()) {
                "Keystore key is not Ed25519 (algorithm=${key.algorithm}, ${spki.size} bytes)"
            }
            return KeyFactory.getInstance("Ed25519", SshProviders.BC).generatePublic(X509EncodedKeySpec(spki))
        }
    }
}

/**
 * Wraps the Keystore private key so sshj's `KeyType.fromKey()` classifies it
 * as Ed25519 regardless of the algorithm name the Keystore reports.
 */
class KeystorePrivateKey(val delegate: PrivateKey) : PrivateKey {
    override fun getAlgorithm(): String = "Ed25519"
    override fun getFormat(): String? = null
    override fun getEncoded(): ByteArray? = null
}
