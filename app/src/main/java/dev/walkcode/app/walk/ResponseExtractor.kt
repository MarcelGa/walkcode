package dev.walkcode.app.walk

/**
 * Turns two screen captures of an agent session into the text worth reading aloud: what
 * appeared since [before], minus TUI chrome (boxes, spinners, status footers) and the
 * user's own input. Pure string work so it is unit-testable without a host.
 *
 * The agents redraw in place, so a plain suffix diff does not work; lines are compared as
 * a multiset instead, which also survives the capture window scrolling.
 */
object ResponseExtractor {

    private val borderChars = Regex("^[\\s│┃║|╭╮╰╯─━═┌┐└┘├┤┬┴┼]+|[\\s│┃║|╭╮╰╯─━═┌┐└┘├┤┬┴┼]+$")
    private val bullet = Regex("^[●⏺•◆▪*-]\\s+")
    private val inputLine = Regex("^[>❯›]\\s?.*")
    private val spinner = Regex("^[✻✽✶✢✳·*]\\s*\\S+…")
    private val noise = Regex(
        "esc to interrupt|ctrl\\+[a-z] to |\\? for shortcuts|shift\\+tab|tokens?\\b.*\\bctx|" +
            "bypass permissions|auto-accept|press enter|^\\(thinking\\)|^[⎿└]\\s*(running|waiting)",
        RegexOption.IGNORE_CASE,
    )
    private val url = Regex("https?://\\S+")
    private val markdown = Regex("[`*_#]+")

    fun newText(before: String, after: String, sent: String? = null): String {
        val seen = HashMap<String, Int>()
        for (line in before.lineSequence()) seen.merge(line.trim(), 1, Int::plus)

        val out = ArrayList<String>()
        for (raw in after.lineSequence()) {
            val key = raw.trim()
            val left = seen[key] ?: 0
            if (left > 0) { seen[key] = left - 1; continue }
            clean(raw, sent)?.let(out::add)
        }
        return out.joinToString("\n")
    }

    /**
     * The last [maxLines] meaningful lines of a screen capture, without TUI chrome, for the voice
     * assistant to summarise. Unlike [newText] the input line is kept: a question the agent is
     * waiting on often sits right above it.
     */
    fun screenTail(screen: String, maxLines: Int): String =
        screen.lineSequence()
            .mapNotNull { raw ->
                val line = raw.replace(borderChars, "")
                when {
                    !inputLine.matches(line) -> clean(raw, null)
                    line.drop(1).any { it.isLetterOrDigit() } -> line.trim()
                    else -> null   // an empty prompt
                }
            }
            .toList()
            .takeLast(maxLines)
            .joinToString("\n")

    private fun clean(raw: String, sent: String?): String? {
        var line = raw.replace(borderChars, "")
        if (line.none { it.isLetterOrDigit() }) return null
        if (inputLine.matches(line) || spinner.containsMatchIn(line) || noise.containsMatchIn(line)) return null
        line = line.replace(bullet, "").replace(url, "link").replace(markdown, "").trim()
        if (line.none { it.isLetterOrDigit() }) return null
        if (sent != null && sent.isNotBlank() && line.equals(sent.trim(), ignoreCase = true)) return null
        return line
    }

    /** Splits [text] into chunks no longer than [max] chars on line boundaries (TTS input is capped). */
    fun chunks(text: String, max: Int): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (line in text.lineSequence()) {
            // A single over-long line is cut hard; real prose lines are far shorter.
            for (piece in line.chunked(max)) {
                if (cur.isNotEmpty() && cur.length + piece.length + 1 > max) { out.add(cur.toString()); cur.clear() }
                if (cur.isNotEmpty()) cur.append('\n')
                cur.append(piece)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }
}
