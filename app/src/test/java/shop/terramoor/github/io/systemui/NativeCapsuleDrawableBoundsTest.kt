package shop.terramoor.github.io.systemui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCapsuleDrawableBoundsTest {
    private fun matches(recorded: Int?, left: Int, top: Int, right: Int, bottom: Int) =
        nativeCapsuleDrawableMatches(665, 105, 391, recorded, left, top, right, bottom)

    @Test fun acceptsOnlyTheActualCenteredForegroundAndRecordedWidth() {
        assertTrue(matches(391, 137, 0, 528, 105))
        assertFalse(matches(391, 0, 0, 665, 105))
        assertFalse(matches(0, 137, 0, 528, 105))
        assertFalse(matches(null, 137, 0, 528, 105))
        assertFalse(matches(391, 137, 0, 528, 104))
        assertFalse(matches(391, 138, 0, 529, 105))
    }

    @Test fun rejectsInvalidOrOversizedGeometry() {
        assertFalse(nativeCapsuleDrawableMatches(0, 105, 0, 0, 0, 0, 0, 105))
        assertFalse(nativeCapsuleDrawableMatches(100, 30, 101, 101, 0, 0, 101, 30))
    }
}
