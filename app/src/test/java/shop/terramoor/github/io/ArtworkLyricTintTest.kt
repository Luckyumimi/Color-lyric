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
import shop.terramoor.github.io.systemui.lyricGradientInk

class ArtworkLyricTintTest {
    private val red = 0xFFE84F62.toInt()
    private val black = 0xFF000000.toInt()
    private val white = -1

    @Test fun gradientKeepsTwoRepresentativeColorsAndIgnoresSparsePixels() {
        val blue = 0xFF386BD4.toInt()
        val detail = 0xFF00FF00.toInt()
        val cover = IntArray(256) { if (it < 180) red else if (it < 255) blue else detail }
        val (dominant, other) = ArtworkQuantization.gradientAccents(cover)
        assertEquals(red, dominant?.toRgb())
        assertEquals(blue, other?.toRgb())
        val first = requireNotNull(dominant)
        val second = requireNotNull(other)
        val strong = requireNotNull(lyricGradientInk(first, second, CapsuleSettings()).gradient)
        val weakSettings = CapsuleSettings().withTuning(mapOf(CapsuleTuning.TINT_STRENGTH to 25))
        val weak = requireNotNull(lyricGradientInk(first, second, weakSettings).gradient)
        assertTrue(strong.start != strong.center && strong.center != strong.end)
        val strongDistance = LabColor.fromRgb(strong.start).distanceSquared(LabColor.fromRgb(strong.end))
        val weakDistance = LabColor.fromRgb(weak.start).distanceSquared(LabColor.fromRgb(weak.end))
        assertTrue(weakDistance < strongDistance)
    }

    @Test fun uniformCoverExposesOnlyASingleGradientAccent() {
        val (dominant, other) = ArtworkQuantization.gradientAccents(IntArray(64) { red })
        assertEquals(red, dominant?.toRgb())
        assertNull(other)
    }

    @Test fun blueCoverKeepsItsPaleAreasInTheLyricGradient() {
        val blue = 0xFF386BD4.toInt()
        val darkBlue = 0xFF0D2969.toInt()
        val offWhite = 0xFFF4F1EA.toInt()
        val cover = IntArray(256) { i ->
            when {
                i < 136 -> blue
                i < 220 -> darkBlue
                else -> offWhite
            }
        }
        val (first, second) = ArtworkQuantization.gradientAccents(cover)
        assertEquals(blue, first?.toRgb())
        assertEquals(offWhite, second?.toRgb())
        val gradient = requireNotNull(lyricGradientInk(requireNotNull(first), requireNotNull(second), CapsuleSettings()).gradient)
        val start = LabColor.fromRgb(gradient.start)
        val end = LabColor.fromRgb(gradient.end)
        assertTrue(start.chroma - end.chroma > 0.05)
        assertTrue(end.lightness >= 0.94 && end.chroma < 0.025)
        assertTrue(LabColor.fromRgb(gradient.center).chroma in end.chroma..start.chroma)
    }

    @Test fun sparsePixelsDoNotBecomeASeparateGradientColor() {
        val blue = 0xFF386BD4.toInt()
        val cover = IntArray(256) { if (it < 254) blue else if (it == 254) black else white }
        val (first, second) = ArtworkQuantization.gradientAccents(cover)
        assertEquals(blue, first?.toRgb())
        assertNull(second)
    }

    @Test fun gradientsAcceptNeutralColorsAsEitherEndpoint() {
        val gray = 0xFF777777.toInt()
        val cover = IntArray(256) { if (it < 170) white else gray }
        val (first, second) = ArtworkQuantization.gradientAccents(cover)
        assertEquals(white, first?.toRgb())
        assertEquals(gray, second?.toRgb())
        val gradient = requireNotNull(lyricGradientInk(requireNotNull(first), requireNotNull(second), CapsuleSettings()).gradient)
        assertTrue(LabColor.fromRgb(gradient.start).lightness - LabColor.fromRgb(gradient.end).lightness > 0.10)
    }

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

    @Test fun brightBackgroundAndLowOpacityPreserveTint() {
        val seed = LabColor.fromRgb(red)
        val settings = CapsuleSettings().withTuning(mapOf(
            CapsuleTuning.SECOND_ROW_OPACITY to 40,
        ))
        val brightInk = lyricInk(seed, intArrayOf(black, white), settings)
        val darkInk = lyricInk(seed, intArrayOf(black), settings)
        assertEquals(darkInk, brightInk)
        assertEquals(brightInk.first, brightInk.second)
        assertTrue(brightInk.first != white)
    }

    @Test fun neutralArtworkRetainsANeutralInkInsteadOfWhite() {
        val ink = lyricInk(LabColor.fromRgb(0xFF777777.toInt()), intArrayOf(black), CapsuleSettings())
        assertTrue(ink.first != white)
        assertEquals(ink.first, ink.second)
    }
}
