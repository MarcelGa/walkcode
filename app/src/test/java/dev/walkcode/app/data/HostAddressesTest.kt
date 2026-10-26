package dev.walkcode.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HostAddressesTest {

    private val host = Host(name = "vm", username = "u", shell = ShellKind.TMUX, lanHost = "dev.local", tailnetHost = "100.1.2.3")

    @Test fun lan_first_when_nothing_remembered() {
        assertEquals(listOf("dev.local", "100.1.2.3"), host.addresses)
    }

    @Test fun last_working_address_goes_first() {
        assertEquals(listOf("100.1.2.3", "dev.local"), host.copy(lastAddress = "100.1.2.3").addresses)
        assertEquals(listOf("dev.local", "100.1.2.3"), host.copy(lastAddress = "dev.local").addresses)
    }

    @Test fun stale_remembered_address_is_ignored() {
        assertEquals(listOf("dev.local", "100.1.2.3"), host.copy(lastAddress = "old.local").addresses)
        assertEquals(listOf("dev.local"), host.copy(tailnetHost = null, lastAddress = "100.1.2.3").addresses)
    }
}
