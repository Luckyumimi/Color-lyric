package shop.terramoor.github.io.systemui

import shop.terramoor.github.io.lyrics.LyricWord

/** The timing track mapped to the actual glyph advances of its row. */
internal class WordLyricScroll private constructor(private val starts: FloatArray, private val ends: FloatArray) {
    fun highlightAt(index: Int, fraction: Float): Float {
        if (index !in starts.indices) return 0f
        return starts[index] + (ends[index] - starts[index]) * fraction
    }

    companion object {
        fun fromLayout(words: List<LyricWord>, lineWidth: Float, advance: (Int) -> Float): WordLyricScroll? {
            if (!lineWidth.isFinite() || lineWidth <= 0f || words.none { (it.endMs ?: it.startMs) > it.startMs }) return null
            val starts = FloatArray(words.size)
            val ends = FloatArray(words.size)
            var previousEnd = 0f
            for ((index, word) in words.withIndex()) {
                val start = advance(word.from).coerceIn(0f, lineWidth)
                val end = advance(word.until).coerceIn(0f, lineWidth)
                if (!start.isFinite() || !end.isFinite() || start < previousEnd || end < start) return null
                starts[index] = start
                ends[index] = end
                previousEnd = end
            }
            return WordLyricScroll(starts, ends)
        }
    }
}

/** Camera gap has already been removed from these coordinates. */
internal fun wordFollowAnchor(viewWidth: Float, seam: Float, trailingInset: Float): Float {
    val start = seam.takeIf { it > 0f && it < viewWidth } ?: 0f
    return ((start + viewWidth) / 2f).coerceAtMost((viewWidth - trailingInset).coerceAtLeast(start))
}

internal inline fun wordFollowProgress(
    highlight: Float, anchor: Float, head: Float, tail: Float, rightShiftAt: (Float) -> Float = { 0f },
): Float {
    val travel = head - tail
    if (travel <= 0f) return 0f
    val target = (anchor - highlight).coerceIn(tail, head)
    if (target == tail) return 1f
    val shift = rightShiftAt(target)
    if (shift <= 0f || target + highlight + shift <= anchor) return (head - target) / travel

    // The rightward camera shift eases out as the text moves left from the head.
    // Solve with the shift at the resulting position to avoid overshoot at the follow point.
    var left = tail
    var right = target
    repeat(12) {
        val middle = (left + right) / 2f
        if (middle + highlight + rightShiftAt(middle) > anchor) right = middle else left = middle
    }
    return (head - left) / travel
}
