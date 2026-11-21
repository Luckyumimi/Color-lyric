package shop.terramoor.github.io.lyrics

internal enum class LyricSecondRow { TRANSLATION, LYRIC }

internal data class LyricFrame(
    val line: LyricLine,
    val endMs: Long,
    val priorLine: LyricLine? = null,
    val isTrackInfo: Boolean = false,
    val displayStartMs: Long = line.startMs,
    val secondText: String? = null,
    val priorSecondText: String? = null,
    val secondRow: LyricSecondRow = LyricSecondRow.TRANSLATION,
    val priorSecondRow: LyricSecondRow = secondRow,
    val singleLineExpanded: Boolean = false,
    val priorSingleLineExpanded: Boolean = false,
) {
    fun scrollFraction(position: Long): Float = scrollFraction(line.startMs, endMs, position)

    companion object {
        fun scrollFraction(start: Long, end: Long, position: Long): Float {
            if (end <= start) return 0f
            val duration = end - start
            val lead = minOf(300L, duration / 6)
            val tail = minOf(180L, duration / 8)
            return ((position - start - lead).toFloat() / (duration - lead - tail)).coerceIn(0f, 1f)
        }
    }
}
