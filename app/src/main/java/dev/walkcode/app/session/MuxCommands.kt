package dev.walkcode.app.session

import dev.walkcode.app.data.ShellKind

/**
 * Builds the remote commands that create and attach multiplexer sessions. Pure string
 * work so it can be unit-tested without a host.
 *
 * POSIX hosts run commands through the user's login shell, Windows OpenSSH runs them
 * through cmd.exe; quoting differs accordingly.
 */
class MuxCommands(private val shell: ShellKind) {

    private val mux = if (shell == ShellKind.TMUX) "tmux" else "psmux"

    /** True when the session PTY should be allocated (psmux on Windows must not get one). */
    val usesPty: Boolean get() = shell == ShellKind.TMUX

    fun hasSession(name: String): String = "$mux has-session -t $name"

    /**
     * One line per pane: `session|pane title`. Session names never contain `|`, so the first
     * one splits the line. Lists panes rather than sessions because the title lives on the pane.
     */
    fun listPanes(): String = when (shell) {
        ShellKind.TMUX -> "tmux list-panes -a -F '#{session_name}|#{pane_title}' 2>/dev/null"
        ShellKind.PSMUX -> "psmux list-panes -a -F \"#{session_name}|#{pane_title}\" 2>nul"
    }

    fun killSession(name: String): String = "$mux kill-session -t $name"

    fun attach(name: String): String = when (shell) {
        ShellKind.TMUX -> "tmux -u attach -t $name"
        ShellKind.PSMUX -> "psmux attach -t $name"
    }

    /**
     * Dumps the pane text with wrapped lines joined. The attached terminal is an alternate
     * screen without scrollback, so history can only be read from the multiplexer itself.
     * [history] adds that many lines above the visible screen (null: visible screen only).
     */
    fun capture(name: String, history: Int?): String =
        "$mux capture-pane -p -J -t $name" + if (history != null) " -S -$history" else ""

    /** Creates a detached session in [directory] (80x24; the attach resizes it to the phone). */
    fun newSession(name: String, directory: String): String = when (shell) {
        ShellKind.TMUX ->
            "tmux new-session -d -s $name -c ${posixPath(directory)} -x 80 -y 24"
        // The shell is started through the init script so Ctrl+C reaches the agents.
        ShellKind.PSMUX ->
            "psmux new-session -d -s $name -c ${windowsPath(directory)} -x 80 -y 24 " +
                // The marker variable has to be set before pwsh starts: the user's profile runs first
                // and may use it to pick a plain prompt for the phone terminal.
                "cmd /c \"set WALKCODE_SESSION=1&& pwsh -NoLogo -NoExit -File %USERPROFILE%\\.config\\walkcode\\shell-init.ps1\""
    }

    /** Types [commandLine] into the session and submits it. */
    fun sendCommand(name: String, commandLine: String): List<String> = when (shell) {
        ShellKind.TMUX -> listOf(
            "tmux send-keys -t $name -l -- ${posixQuote(commandLine)}",
            "tmux send-keys -t $name Enter",
        )
        // Named Enter does not submit on Windows hosts; a raw carriage return does.
        ShellKind.PSMUX -> listOf(
            "psmux send-keys -t $name ${windowsQuote(commandLine)}",
            "psmux send-keys -t $name -H 0d",
        )
    }

    companion object {
        fun posixQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

        /** `~` must stay outside the quotes to expand; everything else is quoted. */
        fun posixPath(path: String): String {
            val p = path.trim()
            return when {
                p == "~" -> "\"\$HOME\""
                p.startsWith("~/") -> "\"\$HOME\"" + posixQuote(p.substring(1))
                else -> posixQuote(p)
            }
        }

        fun windowsQuote(s: String): String = "\"" + s.replace("\"", "\\\"") + "\""

        /** cmd.exe expands `%USERPROFILE%` even inside double quotes. */
        fun windowsPath(path: String): String {
            val p = path.trim()
            val expanded = when {
                p == "~" -> "%USERPROFILE%"
                p.startsWith("~/") || p.startsWith("~\\") -> "%USERPROFILE%\\" + p.substring(2)
                else -> p
            }
            return windowsQuote(expanded.replace('/', '\\'))
        }
    }
}
