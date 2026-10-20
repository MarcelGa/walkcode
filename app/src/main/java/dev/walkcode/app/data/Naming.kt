package dev.walkcode.app.data

/**
 * Names for hosts' multiplexer sessions: `wc-<slug>-<workspaceId>[-<ordinal>]`.
 * tmux/psmux forbid `.` and `:` in session names, so the slug is restricted to
 * `[a-z0-9-]`.
 */
object Naming {
    const val PREFIX = "wc"

    private val DISALLOWED = Regex("[^a-z0-9]+")

    fun slug(name: String): String =
        name.lowercase().replace(DISALLOWED, "-").trim('-').ifEmpty { "ws" }

    fun sessionPrefix(slug: String, workspaceId: Long): String = "$PREFIX-$slug-$workspaceId"

    fun tmuxName(slug: String, workspaceId: Long, ordinal: Int): String {
        val base = sessionPrefix(slug, workspaceId)
        return if (ordinal <= 0) base else "$base-$ordinal"
    }

    /** Lowest non-negative ordinal not already used by [existing]. */
    fun nextOrdinal(existing: Collection<Int>): Int {
        var n = 0
        while (n in existing) n++
        return n
    }
}

/**
 * What the UI calls a session: the user's label, else the agent's title, else "#N" (ordinal 0 is #1).
 * The label is kept apart from [Session.autoTitle] so clearing the label brings the title back.
 */
fun Session.displayName(): String =
    label?.takeIf { it.isNotBlank() } ?: autoTitle?.takeIf { it.isNotBlank() } ?: "#${ordinal + 1}"

/**
 * Turns a multiplexer pane title into a session name. Agents such as Claude Code prefix the
 * title with a status glyph (a spinner or star while working), so leading symbols are dropped.
 * Null when nothing readable is left, which makes the session fall back to "#N".
 */
fun cleanPaneTitle(raw: String): String? =
    raw.trim().dropWhile { !it.isLetterOrDigit() }.replace(Regex("\\s+"), " ").trim().ifEmpty { null }
