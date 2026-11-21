package shop.terramoor.github.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.CapsuleTuning
import shop.terramoor.github.io.systemui.ArtworkQuantization
import shop.terramoor.github.io.systemui.LabColor
import shop.terramoor.github.io.systemui.LyricInk
import shop.terramoor.github.io.systemui.channel
import shop.terramoor.github.io.systemui.lyricInk

class ArtworkLyricTintTest {
    private val red = 0xFFE84F62.toInt()
    private val black = 0xFF000000.toInt()
    private val white = -1

    @Test fun quantizersPickAColorAccentAndRejectGrayscale() {
        val muted = IntArray(128) { if (it < 100) black else 0xFF767676.toInt() }
        val redAccent = muted.copyOf().apply { fill(red, fromIndex = 104) }
        for (quantize in listOf(ArtworkQuantization::medianCut, ArtworkQuantization::oklch)) {
            assertNull(quantize(muted))
            assertEquals(red, quantize(redAccent)?.toRgb())
        }
    }

    @Test fun allFourStrengthsAffectBothRows() {
        val seed = LabColor.fromRgb(red)
        val strength = CapsuleTuning.TINT_STRENGTH
        val levels = (strength.range.first..strength.range.last step strength.step).map { level ->
            val settings = CapsuleSettings().withTuning(mapOf(strength to level))
            lyricInk(seed, intArrayOf(black), settings)
        }
        assertEquals(4, levels.size)
        assertTrue(levels.all { it.first == it.second && it.first != white })
        assertTrue(levels.zipWithNext().all { (a, b) -> channel(a.first, 8) > channel(b.first, 8) })
    }

    @Test fun brightBackgroundFallsBackToWhiteAndSecondRowAccountsForOpacity() {
        val seed = LabColor.fromRgb(red)
        val settings = CapsuleSettings().withTuning(mapOf(
            CapsuleTuning.SECOND_ROW_OPACITY to 40,
        ))
        assertEquals(LyricInk.WHITE, lyricInk(seed, intArrayOf(black, white), settings))
        val darkInk = lyricInk(seed, intArrayOf(black), settings)
        assertTrue(darkInk.first != white)
        assertEquals(white, darkInk.second)
    }
}
