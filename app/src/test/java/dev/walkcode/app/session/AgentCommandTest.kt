package dev.walkcode.app.session

import dev.walkcode.app.data.AgentPreset
import dev.walkcode.app.data.BuiltInAgents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCommandTest {
    private val claude = BuiltInAgents.defaults().first { it.name == "Claude Code" }
    private val id = "0b7c2f4e-1d2a-4c3b-9e8f-112233445566"

    @Test
    fun freshRunPinsTheConversationId() {
        assertTrue(AgentCommand.needsId(claude))
        assertEquals("claude --session-id $id", AgentCommand.build(claude, resume = false, agentSessionId = id, args = null))
    }

    @Test
    fun resumeReopensThatConversation() {
        assertEquals("claude --resume $id --model opus", AgentCommand.build(claude, resume = true, agentSessionId = id, args = "--model opus"))
    }

    @Test
    fun legacySessionResumesThroughThePicker() {
        assertEquals("claude --resume", AgentCommand.build(claude, resume = true, agentSessionId = null, args = " "))
    }

    @Test
    fun presetsWithoutPlaceholderAreUnchanged() {
        val p = AgentPreset(name = "x", command = "opencode", resumeCommand = null)
        assertFalse(AgentCommand.needsId(p))
        assertEquals("opencode", AgentCommand.build(p, resume = true, agentSessionId = id, args = null))
        val old = AgentPreset(name = "y", command = "claude", resumeCommand = "claude --continue")
        assertEquals("claude --continue", AgentCommand.build(old, resume = true, agentSessionId = id, args = null))
    }
}
