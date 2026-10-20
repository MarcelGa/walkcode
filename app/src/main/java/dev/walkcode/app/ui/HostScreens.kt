package dev.walkcode.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.walkcode.app.data.Host
import dev.walkcode.app.data.ShellKind
import dev.walkcode.app.data.WalkcodeRepository
import dev.walkcode.app.monitor.LinkStatus
import dev.walkcode.app.monitor.Monitor
import dev.walkcode.app.ssh.AuthorizeCommand
import dev.walkcode.app.ssh.DeviceKey
import dev.walkcode.app.ssh.HostConnector
import dev.walkcode.app.ssh.HostUnreachableException
import net.schmizz.sshj.userauth.UserAuthException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HostsScreen(repo: WalkcodeRepository, monitor: Monitor, onOpenHost: (Long?) -> Unit) {
    val hosts by repo.hosts.collectAsState(emptyList())
    val linkStatus by monitor.status.collectAsState()
    val workspaces by repo.workspaces.collectAsState(emptyList())
    val context = LocalContext.current
    var pubKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        pubKey = withContext(Dispatchers.Default) { runCatching { DeviceKey.getOrCreate().openSshPublicKey() }.getOrNull() }
    }

    TopBar("Hosts") { BarAction("+") { onOpenHost(null) } }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (hosts.isEmpty()) EmptyHint("No hosts yet. Add one with +, then open it and copy its authorize command to the host.")
        for (h in hosts) {
            val count = workspaces.count { it.hostId == h.id }
            ListRow(
                title = h.name,
                subtitle = listOfNotNull(
                    when (val s = linkStatus[h.id]) {
                        is LinkStatus.Connected -> "watching"
                        is LinkStatus.Connecting -> "connecting…"
                        is LinkStatus.Reconnecting -> "reconnecting…"
                        null -> if (h.hostKeyFingerprint != null) "trusted" else "not connected yet"
                    },
                    h.addresses.firstOrNull(),
                    "$count workspace${if (count == 1) "" else "s"}",
                ).joinToString(" · "),
                onClick = { onOpenHost(h.id) },
                leading = {
                    Dot(
                        when (linkStatus[h.id]) {
                            is LinkStatus.Connected -> WC.Accent
                            is LinkStatus.Connecting, is LinkStatus.Reconnecting -> WC.Warn
                            null -> if (h.hostKeyFingerprint != null) WC.Accent else WC.Off
                        },
                    )
                },
            )
        }
        SectionLabel("Device key")
        ListRow(
            title = "Ed25519 · generated on device",
            subtitle = pubKey ?: "unavailable",
            subtitleLines = 5,
            trailing = {
                Pill("Copy pubkey", onClick = { pubKey?.let { copy(context, "device key", it) } })
            },
        )
    }
}

@Composable
fun HostEditorScreen(repo: WalkcodeRepository, hostId: Long?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(hostId == null) }
    var existing by remember { mutableStateOf<Host?>(null) }
    var name by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var lan by remember { mutableStateOf("") }
    var tailnet by remember { mutableStateOf("") }
    var shell by remember { mutableStateOf(ShellKind.TMUX) }
    var confirmDelete by remember { mutableStateOf(false) }
    var phoneKey by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        phoneKey = withContext(Dispatchers.Default) { runCatching { DeviceKey.getOrCreate().openSshPublicKey() }.getOrNull() }
    }
    LaunchedEffect(hostId) {
        if (hostId != null) {
            repo.host(hostId)?.let { h ->
                existing = h; name = h.name; user = h.username; port = h.port.toString()
                lan = h.lanHost.orEmpty(); tailnet = h.tailnetHost.orEmpty(); shell = h.shell
            }
            loaded = true
        }
    }
    val portInt = port.toIntOrNull()
    val valid = name.isNotBlank() && user.isNotBlank() && portInt != null && portInt in 1..65535 &&
        (lan.isNotBlank() || tailnet.isNotBlank())

    TopBar(if (hostId == null) "New host" else "Edit host", onBack = onDone)
    if (!loaded) return
    Column(Modifier.verticalScroll(rememberScrollState())) {
        FormField("Name", name, { name = it }, "home-server")
        FormField("User", user, { user = it })
        FormField("Port", port, { port = it }, number = true)
        FormField("LAN address (tried first)", lan, { lan = it }, "dev.local", mono = true)
        FormField("Tailnet address (fallback)", tailnet, { tailnet = it }, "100.x.y.z", mono = true)
        Picker(
            "Multiplexer", shell, ShellKind.entries,
            render = { if (it == ShellKind.TMUX) "tmux (Linux / macOS)" else "psmux (Windows)" },
            onSelect = { if (it != null) shell = it },
        )
        existing?.let { h ->
            SectionLabel("Phone key")
            ListRow(
                title = "Authorize command",
                subtitle = if (h.shell == ShellKind.TMUX) "Paste into a shell on the host" else "Paste into PowerShell on the host, run as administrator",
                subtitleLines = 2,
                trailing = {
                    Pill("Copy", onClick = {
                        phoneKey?.let { copy(context, "authorize command", AuthorizeCommand.forHost(h.shell, it)) }
                    })
                },
            )
            PrimaryButton(if (testing) "Testing…" else "Test connection", ghost = true, enabled = !testing && phoneKey != null, onClick = {
                testing = true
                scope.launch {
                    testResult = testConnection(repo, h)
                    // Pick up a host key pinned by the test, so Save does not write back the old pin.
                    existing = repo.host(h.id)
                    testing = false
                }
            })
            testResult?.let {
                Text(it, color = WC.Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
            }

            SectionLabel("Host key")
            val fp = h.hostKeyFingerprint
            Text(
                fp ?: "Not pinned yet. The key is pinned on the first successful connection.",
                color = if (fp != null) WC.Blue else WC.Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
            if (fp != null) {
                PrimaryButton("Forget host key", ghost = true, onClick = {
                    scope.launch { repo.updateHost(h.copy(hostKeyAlgorithm = null, hostKeyFingerprint = null)); onDone() }
                })
            }
        }
        PrimaryButton("Save", enabled = valid, onClick = {
            scope.launch {
                val base = existing
                val lanV = lan.trim().ifEmpty { null }
                val tailV = tailnet.trim().ifEmpty { null }
                if (base == null) {
                    repo.addHost(name, user, shell, lanV, tailV, portInt!!)
                } else {
                    // The pin stays on edit: a changed address that serves a different key
                    // is then reported as a mismatch instead of being silently re-trusted.
                    repo.updateHost(base.copy(name = name.trim(), username = user.trim(), port = portInt!!, lanHost = lanV, tailnetHost = tailV, shell = shell))
                }
                onDone()
            }
        })
        existing?.let { h ->
            if (confirmDelete) {
                PrimaryButton("Really delete (removes its workspaces)", ghost = true, onClick = {
                    scope.launch { repo.deleteHost(h); onDone() }
                })
            } else {
                PrimaryButton("Delete host", ghost = true, onClick = { confirmDelete = true })
            }
        }
    }
}

fun copy(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
}

/** Connects once with this phone's key, the same way the monitor does, and reports the outcome. */
private suspend fun testConnection(repo: WalkcodeRepository, host: Host): String = try {
    val connected = HostConnector(DeviceKey.getOrCreate()).connect(host)
    runCatching { connected.client.close() }
    if (connected.newlyTrusted) repo.trustHostKey(host.id, connected.hostKey.algorithm, connected.hostKey.fingerprint)
    "Connected via ${connected.address}. This phone is authorized."
} catch (e: HostUnreachableException) {
    // The connector keeps per-address failures; a refused login means the key is not on the host yet.
    if (e.failures.values.any { it is UserAuthException }) {
        "Login refused. This phone's key is not on the host yet: run the authorize command there, then test again."
    } else {
        describeFailure(e)
    }
} catch (e: Exception) {
    describeFailure(e)
}
