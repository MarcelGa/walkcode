package dev.walkcode.app.session

import dev.walkcode.app.data.AgentPreset

/**
 * The line typed into a new multiplexer session to start its agent. Preset commands may
 * contain [PLACEHOLDER], replaced by the session's own conversation id, so that resuming
 * reopens that session's conversation rather than the directory's most recent one
 * (`claude --continue` mixed up sessions sharing a workspace).
 */
object AgentCommand {
    const val PLACEHOLDER = "{id}"

    /** True when a fresh run of [preset] needs an id to be assigned to the session first. */
    fun needsId(preset: AgentPreset): Boolean = PLACEHOLDER in preset.command

    /**
     * [agentSessionId] is null only for sessions created before ids existed. Their
     * conversation id is unknown, so the placeholder is dropped: `claude --resume` without
     * an id lets the user pick the conversation instead of guessing it.
     */
    fun build(preset: AgentPreset, resume: Boolean, agentSessionId: String?, args: String?): String {
        val template = if (resume) preset.resumeCommand ?: preset.command else preset.command
        val base = template.replace(PLACEHOLDER, agentSessionId.orEmpty()).replace(Regex(" {2,}"), " ").trim()
        return listOfNotNull(base, args?.takeIf { it.isNotBlank() }).joinToString(" ")
    }
}
