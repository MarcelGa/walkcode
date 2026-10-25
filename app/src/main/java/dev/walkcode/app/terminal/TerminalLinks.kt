package dev.walkcode.app.terminal

/**
 * Finds http(s) URLs in terminal text. The emulator buffer has no link model, so links are
 * recognised on tap from the plain text of the line.
 */
object TerminalLinks {
    private val URL = Regex("""https?://[^\s<>"'`]+""")

    // Sentence punctuation often follows a URL in prose; a closing bracket is dropped too, so
    // "(see https://x.y/z)" opens https://x.y/z. A URL that legitimately ends in ")" loses it.
    private const val TRAILING = ".,;:!?)]}"

    /** Returns the URL covering character [index] of [text], or null when there is none. */
    fun urlAt(text: String, index: Int): String? {
        for (match in URL.findAll(text)) {
            val url = match.value.trimEnd { it in TRAILING }
            if (index >= match.range.first && index < match.range.first + url.length) return url
        }
        return null
    }
}
