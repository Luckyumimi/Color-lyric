package shop.terramoor.github.io.systemui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InterludeStatusBarSpaceTest {
    private val space = InterludeStatusBarSpace()
    private var nativeWidth = 695
    private val published = mutableListOf<Int>()

    private fun update(compact: Boolean, targetWidth: Int = 380): Int? = space.update(compact, targetWidth, { nativeWidth }) { width, _ ->
        published += width
        nativeWidth = width
    }

    @Test fun publishesOnceAndRestoresOriginalWidth() {
        assertNull(update(compact = false))
        assertEquals(380, update(compact = true))
        assertNull(update(compact = true))
        assertEquals(695, update(compact = false))
        assertNull(update(compact = false))
        assertEquals(listOf(380, 695), published)
    }

    @Test fun respectsNewNativeReservationWhenLeavingInterlude() {
        update(compact = true)
        nativeWidth = 0
        assertNull(update(compact = false))
        assertEquals(0, nativeWidth)
        assertEquals(listOf(380), published)
    }

    @Test fun restoresTheLatestNativeWidthAfterKeepingTheSameInterlude() {
        update(compact = true)
        nativeWidth = 605
        update(compact = true)
        assertEquals(605, update(compact = false))
        assertEquals(listOf(380, 380, 605), published)
    }

    @Test fun preservesNativeWidthsAtOrBelowTheTarget() {
        for (width in listOf(0, 360, 380)) {
            nativeWidth = width
            assertNull(update(compact = true))
            assertNull(update(compact = false))
            assertEquals(width, nativeWidth)
        }
        assertEquals(emptyList<Int>(), published)
    }

    @Test fun followsTheCurrentCompactViewportWidth() {
        assertEquals(380, update(compact = true, targetWidth = 380))
        assertEquals(392, update(compact = true, targetWidth = 392))
        assertEquals(695, update(compact = false))
        assertEquals(listOf(380, 392, 695), published)
    }
}
