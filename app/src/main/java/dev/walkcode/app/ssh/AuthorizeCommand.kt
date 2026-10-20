package dev.walkcode.app.ssh

import dev.walkcode.app.data.ShellKind

/**
 * One line to paste on a host so it accepts this phone's key. Idempotent: running it
 * again adds nothing. Keys are re-added after a reinstall because the Keystore key is new.
 */
object AuthorizeCommand {

    fun forHost(shell: ShellKind, publicKey: String): String = when (shell) {
        ShellKind.TMUX -> posix(publicKey)
        ShellKind.PSMUX -> powershell(publicKey)
    }

    /** For a Linux or macOS host with a normal `~/.ssh/authorized_keys`. */
    fun posix(publicKey: String): String {
        val key = "'" + publicKey.replace("'", "'\\''") + "'"
        return "mkdir -p ~/.ssh && touch ~/.ssh/authorized_keys && " +
            "(grep -qxF $key ~/.ssh/authorized_keys || echo $key >> ~/.ssh/authorized_keys) && " +
            "chmod 700 ~/.ssh && chmod 600 ~/.ssh/authorized_keys"
    }

    /**
     * For a Windows host. sshd reads `administrators_authorized_keys` for members of the
     * Administrators group, and that file needs the ACL below, so the one-liner refuses to
     * run from a non-elevated shell instead of writing to the file sshd ignores.
     */
    fun powershell(publicKey: String): String {
        val key = "'" + publicKey.replace("'", "''") + "'"
        return "\$k = $key; " +
            "\$admin = [bool]((whoami /groups) -match 'S-1-5-32-544'); " +
            "\$elevated = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent())" +
            ".IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator); " +
            "if (\$admin -and -not \$elevated) { throw 'Open PowerShell as administrator and paste this again' }; " +
            "\$f = if (\$admin) { \"\$env:ProgramData\\ssh\\administrators_authorized_keys\" } else { \"\$HOME\\.ssh\\authorized_keys\" }; " +
            "New-Item -ItemType Directory -Force (Split-Path \$f) | Out-Null; " +
            "if (-not (Select-String -Path \$f -SimpleMatch \$k -Quiet -ErrorAction SilentlyContinue)) { Add-Content -Path \$f -Value \$k }; " +
            "if (\$admin) { icacls \$f /inheritance:r /grant 'Administrators:F' /grant 'SYSTEM:F' | Out-Null }; " +
            "\"authorized: \$f\""
    }
}
