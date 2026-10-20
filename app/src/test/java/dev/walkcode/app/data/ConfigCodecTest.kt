package dev.walkcode.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigCodecTest {

    private val sample = ConfigFile(
        format = ConfigFile.FORMAT,
        version = ConfigFile.VERSION,
        hosts = listOf(
            HostEntry(
                name = "desktop", username = "galov", port = 22, lanHost = "192.168.1.56",
                tailnetHost = "100.66.206.62", shell = ShellKind.PSMUX,
                hostKeyAlgorithm = "ssh-ed25519", hostKeyFingerprint = "SHA256:abc",
            ),
        ),
        projects = listOf(ProjectEntry("Personal", color = 0xFF00AA00.toInt(), sortOrder = 1)),
        agentPresets = listOf(PresetEntry("Aider", "aider", resumeCommand = "aider --restore-chat-history")),
        workspaces = listOf(
            WorkspaceEntry(name = "Desktop", host = "desktop", project = "Personal", directory = "C:\\src", agent = "Aider", args = "--model x"),
        ),
        settings = SettingsEntry(quietAfterSec = 30, ttsEngine = "com.example.tts", ttsVoice = null, ttsRate = 1.2f),
    )

    @Test
    fun roundTrip_keepsEverything() {
        assertEquals(sample, ConfigCodec.decode(ConfigCodec.encode(sample)))
    }

    @Test
    fun encode_writesFormatAndVersion() {
        val text = ConfigCodec.encode(sample)
        assertTrue(text.contains("\"format\": \"walkcode-config\""))
        assertTrue(text.contains("\"version\": 1"))
    }

    @Test
    fun decode_ignoresUnknownFields() {
        val text = ConfigCodec.encode(sample).replaceFirst("{", "{\n  \"futureField\": [1, 2],")
        assertEquals(sample, ConfigCodec.decode(text))
    }

    @Test
    fun decode_rejectsOtherJson() {
        val e = assertThrows(IllegalArgumentException::class.java) { ConfigCodec.decode("{\"hosts\": []}") }
        assertEquals("Not a WalkCode config file", e.message)
    }

    @Test
    fun decode_rejectsGarbage() {
        assertThrows(IllegalArgumentException::class.java) { ConfigCodec.decode("not json") }
    }

    @Test
    fun decode_rejectsNewerVersion() {
        val text = ConfigCodec.encode(sample).replace("\"version\": 1", "\"version\": 99")
        val e = assertThrows(IllegalArgumentException::class.java) { ConfigCodec.decode(text) }
        assertTrue(e.message!!.contains("newer"))
    }
}
