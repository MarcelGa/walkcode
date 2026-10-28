package dev.walkcode.app.voice

/**
 * Rolling sessions: a long walk is split into fresh model sessions at quiet moments, each started
 * with the last few minutes of the transcript. The model's context then stays short however long
 * walk mode is on: it does not drift, it stays under the server's own session limit, and
 * every reply stops re-reading (and paying for) the whole walk. What is lost is only older talk; the
 * workspaces themselves are read again over SSH whenever they matter. Pure, so it is unit tested.
 */
object Rolling {
    /** Silence (no speech, no reply, no function running) before a fresh session is started. */
    const val QUIET_MS = 20_000L
    /** After twice the interval, any pause between turns will do, so a chatty walk still rolls. */
    const val SHORT_QUIET_MS = 4_000L
    /** How much of the transcript, newest first, the next session is given. */
    const val CARRY_CHARS = 4_000
    /** Shown in the transcript where a session rolled over; never carried into the next one. */
    const val NOTE = "Fresh session, carrying on from the last few minutes"

    /**
     * How long one session runs. Long enough that a few questions share it, short enough that the
     * context (and what each reply re-reads) stays small.
     */
    const val EVERY_MS = 15 * 60_000L

    /** Whether to roll now: old enough, nothing in progress, and quiet long enough. */
    fun due(nowMs: Long, sinceMs: Long, lastActivityMs: Long, busy: Boolean, everyMs: Long = EVERY_MS): Boolean {
        if (busy) return false
        val age = nowMs - sinceMs
        if (age < everyMs) return false
        val quiet = nowMs - lastActivityMs
        return quiet >= if (age >= 2 * everyMs) SHORT_QUIET_MS else QUIET_MS
    }

    /**
     * The most recent turns, oldest first, cut to [maxChars] at a turn boundary (the newest turn
     * alone is shortened from its start if it is longer). Null when nothing was said yet.
     */
    fun carryover(turns: List<VoiceSession.Turn>, maxChars: Int = CARRY_CHARS): String? {
        val lines = ArrayDeque<String>()
        var used = 0
        for (turn in turns.asReversed()) {
            if (turn.tool && turn.text == NOTE) continue
            val text = turn.text.trim().ifEmpty { continue }
            val line = when {
                turn.tool -> "(done: $text)"
                turn.fromUser -> "User: $text"
                else -> "You: $text"
            }
            if (used + line.length + 1 > maxChars) {
                if (lines.isEmpty()) lines.addFirst("…" + line.takeLast(maxChars - 1))
                break
            }
            lines.addFirst(line)
            used += line.length + 1
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** [base] instructions, plus the carried-over transcript so the new session picks up where the last one was. */
    fun instructions(base: String, carry: String?): String = if (carry == null) base else
        "$base\n\nThis conversation is already under way; the most recent part, oldest first (\"You\" is you):\n" +
            "$carry\n\nCarry on from there. Do not greet the user again or mention that anything restarted. " +
            "Read workspaces again with the functions rather than relying on old results."
}
