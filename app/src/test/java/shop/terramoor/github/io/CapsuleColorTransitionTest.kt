package shop.terramoor.github.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import shop.terramoor.github.io.systemui.CapsuleColorTransition
import shop.terramoor.github.io.systemui.LyricGradient
import shop.terramoor.github.io.systemui.LyricInk
import shop.terramoor.github.io.systemui.blendLyricInks

class CapsuleColorTransitionTest {
    @Test
    fun rapidSkipContinuesFromTheVisibleBackgroundMix() {
        val color = CapsuleColorTransition("first")
        color.moveTo("second", 100L)
        val midpoint = color.sample(250L)
        assertEquals(mapOf("first" to 0.5f, "second" to 0.5f), midpoint)

        color.moveTo("third", 250L)
        assertEquals(midpoint, color.sample(250L))
        assertEquals(mapOf("first" to 0.25f, "second" to 0.25f, "third" to 0.5f), color.sample(400L))
        assertEquals(mapOf("third" to 1f), color.sample(550L))
        assertFalse(color.isMoving(550L))
    }

    @Test
    fun fadesToNativeBackgroundAndDropsThePreviousArtwork() {
        val color = CapsuleColorTransition<String?>("cover")
        color.moveTo(null, 100L)
        assertEquals(mapOf("cover" to 0.5f, null to 0.5f), color.sample(250L))
        assertEquals(mapOf(null to 1f), color.sample(400L))
        assertFalse(color.needsBackground())
    }

    @Test
    fun interpolatesBothLyricRowsAndGradientStops() {
        val red = 0xffff0000.toInt()
        val blue = 0xff0000ff.toInt()
        val purple = 0xff800080.toInt()
        val previous = LyricInk(red, blue, gradient = LyricGradient(red, blue, red))
        val next = LyricInk(blue, red)
        assertEquals(LyricInk(purple, purple, gradient = LyricGradient(purple, blue, purple)),
            blendLyricInks(mapOf(previous to 0.5f, next to 0.5f)))
    }
}
