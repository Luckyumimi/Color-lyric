package shop.terramoor.github.io.lyrics

internal data class LyricRows(
    val first: String,
    val second: String? = null,
    val missingTranslationSingleLine: Boolean = false,
    val nextLyricStartMs: Long? = null,
    val firstWords: List<LyricWord> = emptyList(),
    val secondWords: List<LyricWord> = emptyList(),
)

internal data class LyricCountdown(val startMs: Long, val endMs: Long) {
    val isIntro: Boolean get() = startMs == 0L
}

internal data class LyricFrame(
    val rows: LyricRows,
    val startMs: Long,
    val endMs: Long,
    val priorRows: LyricRows? = null,
    val displayStartMs: Long = startMs,
    val isTrackInfo: Boolean = false,
    val countdown: LyricCountdown? = null,
    val priorCountdown: LyricCountdown? = null,
    val priorTrackProgress: Float? = null,
    val motionStartMs: Long = displayStartMs,
) {
    val isIntro: Boolean get() = countdown?.isIntro == true
    val promotesPreviousLyric: Boolean get() = priorRows?.nextLyricStartMs == startMs

    fun scrollFraction(position: Long): Float = scrollFraction(startMs, endMs, position)

    companion object {
        fun trackInfo(title: String, artist: String?) =
            LyricFrame(LyricRows(title, artist), startMs = 0L, endMs = 0L, isTrackInfo = true)

        fun intro(firstLyricMs: Long, track: LyricRows? = null) =
            LyricFrame(track ?: LyricRows(""), startMs = 0L, endMs = firstLyricMs,
                countdown = LyricCountdown(0L, firstLyricMs))

        fun interlude(startMs: Long, endMs: Long, priorRows: LyricRows?) =
            LyricFrame(LyricRows(""), startMs, endMs, priorRows = priorRows, countdown = LyricCountdown(startMs, endMs))

        fun scrollFraction(start: Long, end: Long, position: Long): Float {
            if (end <= start) return 0f
            val duration = end - start
            val lead = minOf(300L, duration / 6)
            val tail = minOf(180L, duration / 8)
            return ((position - start - lead).toFloat() / (duration - lead - tail)).coerceIn(0f, 1f)
        }
    }
}
