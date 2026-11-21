package shop.terramoor.github.io.systemui

import kotlin.math.roundToLong

/** Local display time, independent of media position, pause and lyric timing. */
internal object TrackTitleMarquee {
    private const val START_HOLD_MS = 900L
    private const val END_HOLD_MS = 3000L
    private const val SPEED_DP_PER_SECOND = 32f

    data class Sample(val progress: Float, val redrawDelayMs: Long?)

    fun sample(elapsedMs: Long, overflowDp: Float, trailingInsetDp: Float): Sample {
        if (overflowDp <= 0f) return Sample(0f, null)
        val duration = ((overflowDp + trailingInsetDp) * 1000f / SPEED_DP_PER_SECOND).roundToLong().coerceAtLeast(1L)
        val forwardEnd = START_HOLD_MS + duration
        val returnStart = forwardEnd + END_HOLD_MS
        val cycle = returnStart + duration
        val position = elapsedMs.coerceAtLeast(0) % cycle
        return when {
            position < START_HOLD_MS -> Sample(0f, START_HOLD_MS - position)
            position < forwardEnd -> Sample((position - START_HOLD_MS).toFloat() / duration, 0L)
            position < returnStart -> Sample(1f, returnStart - position)
            else -> Sample((cycle - position).toFloat() / duration, 0L)
        }
    }
}
