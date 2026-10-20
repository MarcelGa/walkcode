package dev.walkcode.app.ssh

import dev.walkcode.app.data.ShellKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizeCommandTest {

    private val key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIEexample walkcode@android"

    @Test
    fun posix_quotesKeyAndIsIdempotent() {
        val cmd = AuthorizeCommand.posix(key)
        assertTrue(cmd.contains("grep -qxF '$key' ~/.ssh/authorized_keys"))
        assertTrue(cmd.contains("echo '$key' >> ~/.ssh/authorized_keys"))
        assertFalse(cmd.contains("\n"))
    }

    @Test
    fun posix_escapesSingleQuotesInKey() {
        val cmd = AuthorizeCommand.posix("ssh-ed25519 AAA it's")
        assertTrue(cmd.contains("'ssh-ed25519 AAA it'\\''s'"))
    }

    @Test
    fun powershell_doublesSingleQuotesAndTargetsAdminFile() {
        val cmd = AuthorizeCommand.powershell("ssh-ed25519 AAA it's")
        assertTrue(cmd.startsWith("\$k = 'ssh-ed25519 AAA it''s'; "))
        assertTrue(cmd.contains("administrators_authorized_keys"))
        assertTrue(cmd.contains("icacls"))
        assertFalse(cmd.contains("\n"))
    }

    @Test
    fun forHost_picksSyntaxByShell() {
        assertEquals(AuthorizeCommand.posix(key), AuthorizeCommand.forHost(ShellKind.TMUX, key))
        assertEquals(AuthorizeCommand.powershell(key), AuthorizeCommand.forHost(ShellKind.PSMUX, key))
    }
}
