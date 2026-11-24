package shop.terramoor.github.io.systemui

import android.graphics.Bitmap
import com.juren233.hyperlyricsenhanced.common.color.ColorExtractor
import com.juren233.hyperlyricsenhanced.common.color.CoverTextGradientPaletteOptimizer
import com.juren233.hyperlyricsenhanced.common.color.PerceptualGradient

internal fun hyperCoverLyricInk(bitmap: Bitmap): LyricInk? =
    hyperCoverLyricInk(ColorExtractor.extractThemePalette(bitmap, 4).onBlackBackground.toIntArray())

internal fun hyperCoverLyricInk(darkColors: IntArray): LyricInk? {
    val colors = darkColors.distinct()
    val first = colors.firstOrNull() ?: return null
    // HyperLyrics-Enhanced's CoverColorHelper chooses endpoints on the dark palette.
    val second = when (colors.size) {
        1 -> null
        2 -> colors.last()
        else -> {
            val candidates = colors.drop(1)
            candidates.firstOrNull { PerceptualGradient.oklabDistance(first, it) >= 0.08 }
                ?: candidates.maxBy { PerceptualGradient.oklabDistance(first, it) }
        }
    }
    val anchors = if (second == null) intArrayOf(first) else PerceptualGradient.threeColorAnchors(first, second)
    val ink = CoverTextGradientPaletteOptimizer.optimize(anchors)
    if (ink.size == 1) return LyricInk(ink[0], ink[0])
    return LyricInk(ink[1], ink[1], gradient = LyricGradient(ink[0], ink[1], ink[2]))
}

internal fun LyricInk.withCoverTintStrength(percent: Int): LyricInk {
    if (percent == 100) return this
    val amount = percent / 100.0
    fun fade(color: Int) = blendInk(LyricInk.WHITE.first, color, amount)
    return copy(
        first = fade(first),
        second = fade(second),
        gradient = gradient?.let { LyricGradient(fade(it.start), fade(it.center), fade(it.end)) },
    )
}
