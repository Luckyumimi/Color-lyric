package shop.terramoor.github.io

import com.juren233.hyperlyricsenhanced.common.color.CoverTextGradientPaletteOptimizer
import com.juren233.hyperlyricsenhanced.common.color.PerceptualGradient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.CapsuleTuning
import shop.terramoor.github.io.systemui.ArtworkQuantization
import shop.terramoor.github.io.systemui.LabColor
import shop.terramoor.github.io.systemui.LyricGradient
import shop.terramoor.github.io.systemui.LyricInk
import shop.terramoor.github.io.systemui.channel
import shop.terramoor.github.io.systemui.hyperCoverLyricInk
import shop.terramoor.github.io.systemui.lyricInk
import shop.terramoor.github.io.systemui.withCoverTintStrength

class ArtworkLyricTintTest {
    private val red = 0xFFE84F62.toInt()
    private val black = 0xFF000000.toInt()
    private val white = -1

    @Test fun gradientChoosesTheFirstDistinctUpstreamColorInPaletteOrder() {
        val blue = 0xFF729AFF.toInt()
        val similar = 0xFF749BFC.toInt()
        val neutral = 0xFFD4D4D4.toInt()
        assertEquals(upstreamGradient(blue, neutral), hyperCoverLyricInk(intArrayOf(blue, blue, similar, neutral, red)))
    }

    @Test fun gradientFallsBackToTheFarthestColorWhenTheCandidatesAreClose() {
        val first = 0xFFB3B3B3.toInt()
        val middle = 0xFFB9B9B9.toInt()
        val last = 0xFFBEBEBE.toInt()
        assertEquals(upstreamGradient(first, last), hyperCoverLyricInk(intArrayOf(first, middle, last)))
    }

    @Test fun gradientKeepsBothColorsWhenUpstreamOnlyFindsTwo() {
        val first = 0xFFB3B3B3.toInt()
        val second = 0xFFB9B9B9.toInt()
        assertEquals(upstreamGradient(first, second), hyperCoverLyricInk(intArrayOf(first, first, second)))
    }

    @Test fun uniformUpstreamPaletteUsesTheOriginalSolidLyricColor() {
        val color = CoverTextGradientPaletteOptimizer.optimizeColor(red)
        assertEquals(LyricInk(color, color), hyperCoverLyricInk(intArrayOf(red, red)))
    }

    @Test fun missingUpstreamPaletteDoesNotInventAColor() {
        assertNull(hyperCoverLyricInk(intArrayOf()))
    }

    @Test fun gradientStrengthFadesEveryStopAndKeepsTheFullPaletteAt100Percent() {
        val start = 0xFFFF0000.toInt()
        val center = 0xFF800080.toInt()
        val end = 0xFF0000FF.toInt()
        val ink = LyricInk(center, center, gradient = LyricGradient(start, center, end))
        assertEquals(ink, ink.withCoverTintStrength(100))
        val half = ink.withCoverTintStrength(50)
        val fadedCenter = 0xFFC080C0.toInt()
        assertEquals(
            LyricInk(fadedCenter, fadedCenter, gradient = LyricGradient(0xFFFF8080.toInt(), fadedCenter, 0xFF8080FF.toInt())),
            half,
        )
        assertEquals(listOf(191, 128, 64, 0), listOf(25, 50, 75, 100).map { strength ->
            channel(requireNotNull(ink.withCoverTintStrength(strength).gradient).start, 8)
        })
    }

    @Test fun gradientStrengthAlsoFadesASolidPaletteWithoutInventingStops() {
        val color = 0xFF0000FF.toInt()
        val expected = 0xFFBFBFFF.toInt()
        assertEquals(LyricInk(expected, expected), LyricInk(color, color).withCoverTintStrength(25))
        assertEquals(LyricInk.WHITE, LyricInk.WHITE.withCoverTintStrength(25))
    }

    private fun upstreamGradient(first: Int, second: Int): LyricInk {
        val (start, middle, end) = CoverTextGradientPaletteOptimizer.optimize(
            PerceptualGradient.threeColorAnchors(first, second),
        )
        return LyricInk(middle, middle, gradient = LyricGradient(start, middle, end))
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
