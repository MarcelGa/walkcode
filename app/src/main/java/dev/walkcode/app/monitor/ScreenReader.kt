package dev.walkcode.app.monitor

import dev.walkcode.app.walk.ResponseExtractor

/** What an agent's visible screen says at the moment an alert fires. */
data class ScreenReading(val state: State, val line: String?) {
    enum class State { NEEDS_INPUT, DONE, WORKING }
}

/**
 * Reads a pane capture to tell "the agent asks you something" from "the agent finished".
 * The bell and the quiet timer only say that something happened; the screen says what.
 * Pure string work so it is unit-testable without a host.
 *
 * Patterns cover Claude Code, Copilot CLI and opencode prompts plus generic shell prompts.
 * Only the bottom of the screen counts: an answered prompt can still sit higher up.
 */
object ScreenReader {

    private const val TAIL = 25
    private const val MAX_LINE = 160

    private val borderChars = Regex("^[\\s│┃║|╭╮╰╯─━═┌┐└┘├┤┬┴┼]+|[\\s│┃║|╭╮╰╯─━═┌┐└┘├┤┬┴┼]+$")

    private val prompt = Regex(
        "do you want to|would you like to|permission required|allow once|allow always|" +
            "enter to (select|confirm)|esc to cancel|\\(y/n\\)|\\[y/n]|press enter to continue|" +
            "^[❯>›]?\\s*1\\.\\s+(yes|allow|approve)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Shown by the agents only while a turn is running. */
    private val working = Regex("esc to interrupt|esc interrupt|ctrl\\+c to (interrupt|cancel)", RegexOption.IGNORE_CASE)

    /** A numbered or arrow-marked choice: not the question itself. */
    private val choice = Regex("^[❯>›]?\\s*\\d+\\.\\s")

    fun read(screen: String): ScreenReading? {
        val tail = screen.lineSequence()
            .map { it.replace(borderChars, "").trim() }
            .filter { line -> line.any { it.isLetterOrDigit() } }
            .toList()
            .takeLast(TAIL)
        if (tail.isEmpty()) return null

        val hit = tail.indexOfLast { prompt.containsMatchIn(it) }
        if (hit >= 0) {
            // The question usually sits just above the choices; fall back to the matched line.
            val question = tail.subList(0, hit + 1).lastOrNull { it.endsWith("?") && !choice.containsMatchIn(it) }
            return ScreenReading(ScreenReading.State.NEEDS_INPUT, shorten(question ?: tail[hit]))
        }
        if (tail.any { working.containsMatchIn(it) }) return ScreenReading(ScreenReading.State.WORKING, null)

        val last = ResponseExtractor.screenTail(screen, TAIL).lineSequence()
            .lastOrNull { !it.startsWith(">") && !it.startsWith("❯") && !it.startsWith("›") }
        return ScreenReading(ScreenReading.State.DONE, last?.let(::shorten))
    }

    private fun shorten(line: String): String = if (line.length <= MAX_LINE) line else line.take(MAX_LINE - 1) + "…"
}
