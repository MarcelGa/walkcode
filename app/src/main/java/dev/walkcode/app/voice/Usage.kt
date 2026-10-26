package dev.walkcode.app.voice

import java.util.Locale

/**
 * Tokens one Realtime response or one transcription used, as the server reports them in `usage`.
 * Input counts include their cached part ([cachedTextIn], [cachedAudioIn]), which is billed far lower.
 */
data class Usage(
    val textIn: Long = 0,
    val audioIn: Long = 0,
    val cachedTextIn: Long = 0,
    val cachedAudioIn: Long = 0,
    val textOut: Long = 0,
    val audioOut: Long = 0,
) {
    val total: Long get() = textIn + audioIn + textOut + audioOut
    val cachedIn: Long get() = cachedTextIn + cachedAudioIn
}

/**
 * OpenAI list prices in dollars per 1M tokens. The app can only estimate from these: OpenAI's
 * billing page is what is actually charged (and a price change there is not seen here).
 */
data class Prices(
    val textIn: Double,
    val textOut: Double,
    val audioIn: Double,
    val audioOut: Double,
    val cachedTextIn: Double,
    val cachedAudioIn: Double,
) {
    fun cost(u: Usage): Double = (
        (u.textIn - u.cachedTextIn) * textIn + u.cachedTextIn * cachedTextIn +
            (u.audioIn - u.cachedAudioIn) * audioIn + u.cachedAudioIn * cachedAudioIn +
            u.textOut * textOut + u.audioOut * audioOut
        ) / 1_000_000.0
}

object UsageFormat {
    /** "$0.004", "$0.21", "$12.40": three decimals while a cent is still a big share of it. */
    fun usd(v: Double): String = if (v < 1.0) String.format(Locale.US, "$%.3f", v) else String.format(Locale.US, "$%.2f", v)

    /** "850", "12.3k", "1.2M". */
    fun tokens(n: Long): String = when {
        n < 1_000 -> n.toString()
        n < 1_000_000 -> String.format(Locale.US, "%.1fk", n / 1_000.0)
        else -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    }

    /** A per-1M price as a label: 32.0 -> "$32", 0.4 -> "$0.40". */
    fun rate(v: Double): String = if (v == Math.floor(v)) "$${v.toLong()}" else String.format(Locale.US, "$%.2f", v)
}
