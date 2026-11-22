package shop.terramoor.github.io.systemui

import shop.terramoor.github.io.settings.LyricAlignment

/** Positions one logical lyric strip. The physical camera gap is inserted after layout. */
internal data class CapsuleTextViewport(
    val left: Float,
    val right: Float,
    val alignment: LyricAlignment,
    val center: Float = (left + right) / 2f,
) {
    private val width = right - left

    fun travel(lineWidth: Float): Float = (lineWidth - width).coerceAtLeast(0f)

    fun outerFeather(density: Float): OuterLyricFeather {
        val halfWidth = width.coerceAtLeast(0f) / 2f
        val rightGuard = minOf(2f * density, halfWidth / 4f)
        return OuterLyricFeather(
            left = minOf(2f * density, halfWidth),
            right = minOf(6f * density, halfWidth - rightGuard),
            rightGuard = rightGuard,
        )
    }

    fun edgeFade(lineWidth: Float, progress: Float, feather: OuterLyricFeather): EdgeFade {
        val distance = travel(lineWidth)
        if (distance <= 0f || feather.left <= 0f) return EdgeFade(0f, 0f)
        val moved = (distance + feather.trailingInset) * progress.coerceIn(0f, 1f)
        return EdgeFade((moved / feather.left).coerceIn(0f, 1f), 1f)
    }

    fun textOffset(lineWidth: Float, progress: Float, trailingInset: Float = 0f): Float {
        val free = (width - lineWidth).coerceAtLeast(0f)
        val start = if (alignment == LyricAlignment.CENTER) {
            (center - left - lineWidth / 2f).coerceIn(0f, free)
        } else 0f
        val distance = travel(lineWidth)
        val paddedDistance = if (distance > 0f) distance + trailingInset.coerceAtLeast(0f) else 0f
        return start - paddedDistance * progress.coerceIn(0f, 1f)
    }
}

internal data class EdgeFade(val left: Float, val right: Float)

internal data class OuterLyricFeather(val left: Float, val right: Float, val rightGuard: Float) {
    val trailingInset: Float get() = right + rightGuard
}

/** Maps a physical capsule center into the strip with the camera gap removed. */
internal fun compactCapsuleCenter(center: Float, gapLeft: Float, gapWidth: Float): Float =
    center - (center - gapLeft).coerceIn(0f, gapWidth)
