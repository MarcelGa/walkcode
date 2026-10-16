package dev.walkcode.app.poll

import java.util.Base64

enum class HostPlatform { POSIX, WINDOWS }

/** Poller timing, in seconds. */
data class PollTiming(val interval: Int = 2, val idleGrace: Int = 4, val heartbeat: Int = 30)

/**
 * Builds the remote command that streams the poller's output. The script is sent inline on
 * every connect (no files on the host, no version drift).
 */
object PollerCommand {
    const val POSIX_ASSET = "poller/poll.sh"
    const val WINDOWS_ASSET = "poller/poll.ps1"

    /** Windows OpenSSH runs commands through cmd.exe, whose command line limit is 8191. */
    const val WINDOWS_MAX_LENGTH = 8000

    fun build(platform: HostPlatform, script: String, timing: PollTiming): String {
        val body = minify(script)
        val args = "${timing.interval} ${timing.idleGrace} ${timing.heartbeat}"
        return when (platform) {
            HostPlatform.POSIX -> {
                val b64 = Base64.getEncoder().encodeToString(body.toByteArray(Charsets.UTF_8))
                // A pipe works from any login shell (bash, zsh, fish) and needs no quoting of the script.
                "printf %s '$b64' | base64 -d | bash -s -- $args"
            }
            HostPlatform.WINDOWS -> {
                val wrapped = "& {\n$body\n} $args"
                val b64 = Base64.getEncoder().encodeToString(wrapped.toByteArray(Charsets.UTF_16LE))
                "pwsh -NoLogo -NoProfile -NonInteractive -EncodedCommand $b64".also {
                    check(it.length <= WINDOWS_MAX_LENGTH) { "Windows poller command too long (${it.length})" }
                }
            }
        }
    }

    /** Drops comment-only and blank lines and leading indentation. Both scripts avoid heredocs. */
    fun minify(script: String): String = script.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .joinToString("\n")
}
