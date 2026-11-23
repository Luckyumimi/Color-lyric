package shop.terramoor.github.io.systemui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsulePauseTimeoutTest {
    private val timeout = CapsulePauseTimeout<String>()

    @Test
    fun onlyTheVerifiedPausedOwnerCanHoldTheTimeout() {
        assertFalse(timeout.retain { it == "music" })
        assertNull(timeout.update("music"))
        assertFalse(timeout.retain { it == "other player" })
        assertTrue(timeout.retain { it == "music" })
    }

    @Test
    fun refreshingTheBindingKeepsTheDeferredTimeoutUntilDisable() {
        timeout.update("music")
        assertTrue(timeout.retain { it == "music" })
        assertNull(timeout.update("music"))

        assertEquals("music", timeout.update(null))
        assertNull(timeout.update(null))
        assertFalse(timeout.retain { it == "music" })
    }

    @Test
    fun resumeDiscardsTheEarlierTimeoutBeforeTheNextPause() {
        timeout.update("music")
        assertTrue(timeout.retain { it == "music" })
        assertNull(timeout.update(null, restore = false))

        timeout.update("music")
        assertNull(timeout.update(null))
    }

    @Test
    fun handoffReturnsOnlyTheOldOwnersDeferredTimeout() {
        timeout.update("music")
        assertTrue(timeout.retain { it == "music" })
        assertEquals("music", timeout.update("video"))

        assertFalse(timeout.retain { it == "music" })
        assertNull(timeout.update(null))
    }
}
