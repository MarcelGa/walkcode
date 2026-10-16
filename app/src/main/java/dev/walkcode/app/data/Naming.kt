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
