package dev.walkcode.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScreenCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun cache() = ScreenCache(tmp.root)

    @Test fun roundTripsPerSession() {
        val cache = cache()
        cache.save(1, "first")
        cache.save(2, "second")
        assertEquals("first", cache.load(1))
        assertEquals("second", cache.load(2))
    }

    @Test fun missingSessionIsNull() {
        assertNull(cache().load(42))
    }

    @Test fun saveKeepsOnlyTheLastLines() {
        val cache = cache()
        val text = (1..ScreenCache.KEEP_LINES + 50).joinToString("\n") { "line $it" }
        cache.save(1, text)
        val lines = cache.load(1)!!.lines()
        assertEquals(ScreenCache.KEEP_LINES, lines.size)
        assertEquals("line 51", lines.first())
        assertEquals("line ${ScreenCache.KEEP_LINES + 50}", lines.last())
    }

    @Test fun deleteRemovesOnlyThatSession() {
        val cache = cache()
        cache.save(1, "gone")
        cache.save(2, "kept")
        cache.delete(1)
        assertNull(cache.load(1))
        assertEquals("kept", cache.load(2))
    }
}
