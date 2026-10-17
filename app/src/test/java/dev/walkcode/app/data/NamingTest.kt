package dev.walkcode.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NamingTest {
    @Test fun slug_basic() {
        assertEquals("my-project", Naming.slug("My Project"))
    }

    @Test fun slug_strips_and_collapses() {
        assertEquals("a-b-c", Naming.slug("  A // B__C  "))
    }

    @Test fun slug_empty_falls_back() {
        assertEquals("ws", Naming.slug("!!!"))
    }

    @Test fun slug_removes_tmux_forbidden() {
        // tmux/psmux reject '.' and ':' in session names.
        assertEquals("v1-2", Naming.slug("v1.2"))
        assertEquals("a-b", Naming.slug("a:b"))
    }

    @Test fun session_name_without_ordinal() {
        assertEquals("wc-web-7", Naming.tmuxName("web", 7, 0))
    }

    @Test fun session_name_with_ordinal() {
        assertEquals("wc-web-7-2", Naming.tmuxName("web", 7, 2))
    }

    @Test fun next_ordinal_fills_gap() {
        assertEquals(0, Naming.nextOrdinal(emptyList()))
        assertEquals(3, Naming.nextOrdinal(listOf(0, 1, 2)))
        assertEquals(1, Naming.nextOrdinal(listOf(0, 2)))
    }
}

class SessionDisplayNameTest {
    private fun session(ordinal: Int, label: String?) =
        Session(tmuxName = "wc-x-1", workspaceId = 1, ordinal = ordinal, label = label)

    @org.junit.Test fun fallsBackToOrdinal() {
        org.junit.Assert.assertEquals("#1", session(0, null).displayName())
        org.junit.Assert.assertEquals("#3", session(2, "  ").displayName())
    }

    @org.junit.Test fun labelWins() {
        org.junit.Assert.assertEquals("build", session(1, "build").displayName())
    }
}
