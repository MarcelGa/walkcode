package dev.walkcode.app.ssh

import dev.walkcode.app.data.Host
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient

/** Connecting to a host failed for every address it has. */
class HostUnreachableException(val hostName: String, val failures: Map<String, Exception>) :
    Exception("could not reach $hostName at ${failures.keys.joinToString()}", failures.values.firstOrNull())

/** A host with no usable address. */
class NoAddressException(val hostName: String) : Exception("host $hostName has no address")

/**
 * Opens an authenticated SSH connection to a [Host], trying its addresses in order
 * (LAN first, then tailnet) and pinning its host key on first use. On a host-key
 * mismatch it stops immediately: a shifted key means something is wrong, so trying
 * another address of the same machine would only hide it.
 */
class HostConnector(private val deviceKey: DeviceKey) {

    data class Connected(
        val client: SSHClient,
        val address: String,
        val hostKey: PinnedHostKey,
        /** True when this was the first connection and the key should be persisted. */
        val newlyTrusted: Boolean,
    )

    suspend fun connect(
        host: Host,
        connectTimeoutMs: Int = 10_000,
        authTimeoutMs: Int = 15_000,
        onAttempt: (String) -> Unit = {},
    ): Connected = withContext(Dispatchers.IO) {
        val addresses = host.addresses
        if (addresses.isEmpty()) throw NoAddressException(host.name)

        val expectedPin = host.hostKeyFingerprint?.let { fp ->
            PinnedHostKey(host.hostKeyAlgorithm ?: "", fp)
        }
        val failures = LinkedHashMap<String, Exception>()

        for (address in addresses) {
            onAttempt(address)
            val verifier = TofuHostKeyVerifier(expectedPin)
            val client = Ssh.client()
            try {
                client.connectTimeout = connectTimeoutMs
                client.timeout = authTimeoutMs
                client.addHostKeyVerifier(verifier)
                client.connect(address, host.port)
                client.authPublickey(host.username, deviceKey.keyProvider())

                val check = verifier.check
                val key = when (check) {
                    is HostKeyCheck.TrustedOnFirstUse -> return@withContext Connected(client, address, check.key, newlyTrusted = true)
                    is HostKeyCheck.MatchesPin -> Connected(client, address, check.key, newlyTrusted = false)
                    is HostKeyCheck.Mismatch -> throw HostKeyMismatchException(check.expected, check.observed)
                    null -> throw IllegalStateException("host key was never verified")
                }
                return@withContext key
            } catch (mismatch: HostKeyMismatchException) {
                runCatching { client.close() }
                throw mismatch
            } catch (e: Exception) {
                runCatching { client.close() }
                // sshj surfaces a rejected host key as a TransportException, so the
                // mismatch has to be read back from the verifier and reported as such
                // instead of being treated as a plain connection failure.
                (verifier.check as? HostKeyCheck.Mismatch)?.let {
                    throw HostKeyMismatchException(it.expected, it.observed)
                }
                failures[address] = e
            }
        }
        throw HostUnreachableException(host.name, failures)
    }
}
