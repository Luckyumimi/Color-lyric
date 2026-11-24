package shop.terramoor.github.io.systemui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InterludeStatusBarSpaceTest {
    private val space = InterludeStatusBarSpace()
    private var nativeWidth = 695
    private val published = mutableListOf<Int>()

    private fun update(compact: Boolean): Int? = space.update(compact, { nativeWidth }) { width, _ ->
        published += width
        nativeWidth = width
    }

    @Test fun publishesOnceAndRestoresOriginalWidth() {
        assertNull(update(compact = false))
        assertEquals(400, update(compact = true))
        assertNull(update(compact = true))
        assertEquals(695, update(compact = false))
        assertNull(update(compact = false))
        assertEquals(listOf(400, 695), published)
    }

    @Test fun respectsNewNativeReservationWhenLeavingInterlude() {
        update(compact = true)
        nativeWidth = 0
        assertNull(update(compact = false))
        assertEquals(0, nativeWidth)
        assertEquals(listOf(400), published)
    }

    @Test fun restoresTheLatestNativeWidthAfterKeepingTheSameInterlude() {
        update(compact = true)
        nativeWidth = 605
        update(compact = true)
        assertEquals(605, update(compact = false))
        assertEquals(listOf(400, 400, 605), published)
    }

    @Test fun preservesNativeWidthsAtOrBelowTheTarget() {
        for (width in listOf(0, 360, 400)) {
            nativeWidth = width
            assertNull(update(compact = true))
            assertNull(update(compact = false))
            assertEquals(width, nativeWidth)
        }
        assertEquals(emptyList<Int>(), published)
    }
}
