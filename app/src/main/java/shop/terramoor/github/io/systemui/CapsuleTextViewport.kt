package shop.terramoor.github.io.systemui

import shop.terramoor.github.io.settings.LyricAlignment

/** Positions one logical lyric strip. The physical camera gap is inserted after layout. */
internal data class CapsuleTextViewport(
    val left: Float,
    val right: Float,
    val alignment: LyricAlignment,
) {
    private val width = right - left

    fun travel(lineWidth: Float): Float = (lineWidth - width).coerceAtLeast(0f)

    fun textOffset(lineWidth: Float, progress: Float): Float {
        val free = (width - lineWidth).coerceAtLeast(0f)
        val start = if (alignment == LyricAlignment.CENTER) free / 2f else 0f
        return start - travel(lineWidth) * progress.coerceIn(0f, 1f)
    }
}
