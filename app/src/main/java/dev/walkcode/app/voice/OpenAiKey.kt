package dev.walkcode.app.voice

/** Cleaning and display of a pasted OpenAI API key. Pure, so it is unit tested. */
object OpenAiKey {

    private val whitespace = Regex("\\s+")

    /**
     * What to store for a pasted value. Pastes from password managers and notes often carry a
     * label line ("OpenAI\nsk-proj-…"), and an earlier build glued such lines together, so:
     * the longest token containing "sk-", cut from its first "sk-"; otherwise the longest token.
     */
    fun clean(raw: String?): String? {
        val tokens = raw?.split(whitespace)?.filter { it.isNotEmpty() }.orEmpty()
        if (tokens.isEmpty()) return null
        val keyLike = tokens.filter { it.contains("sk-") }.maxByOrNull { it.length }
        return keyLike?.substring(keyLike.indexOf("sk-")) ?: tokens.maxBy { it.length }
    }

    /** "sk-proj-…a1b2" — enough to recognise the key without showing it. */
    fun masked(key: String): String {
        val prefix = key.take(if (key.startsWith("sk-proj-")) 8 else 3)
        return if (key.length <= prefix.length + 4) "…" else "$prefix…${key.takeLast(4)}"
    }
}
