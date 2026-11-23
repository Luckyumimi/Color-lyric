package shop.terramoor.github.io.systemui

import kotlin.math.roundToLong

/** Local display time, independent of media position, pause and lyric timing. */
internal object TrackTitleMarquee {
    private const val HOLD_MS = 3000L
    private const val SPEED_DP_PER_SECOND = 32f

    data class Sample(val progress: Float, val redrawDelayMs: Long?)

    fun sample(elapsedMs: Long, overflowDp: Float, trailingInsetDp: Float): Sample {
        if (overflowDp <= 0f) return Sample(0f, null)
        val duration = ((overflowDp + trailingInsetDp) * 1000f / SPEED_DP_PER_SECOND).roundToLong().coerceAtLeast(1L)
        val forwardEnd = HOLD_MS + duration
        val returnStart = forwardEnd + HOLD_MS
        val cycle = returnStart + duration
        val position = elapsedMs.coerceAtLeast(0) % cycle
        return when {
            position < HOLD_MS -> Sample(0f, HOLD_MS - position)
            position < forwardEnd -> Sample((position - HOLD_MS).toFloat() / duration, 0L)
            position < returnStart -> Sample(1f, returnStart - position)
            else -> Sample((cycle - position).toFloat() / duration, 0L)
        }
    }
}

/** Drive the marquee independently of the viewport, which still follows each native animation frame. */
internal class TrackInfoDisplayMarquee {
    private var startTime: Long? = null
    private var elapsed = 0L
    private var progress = 0f

    fun sample(now: Long, settled: Boolean, overflowDp: Float, insetDp: Float): TrackTitleMarquee.Sample {
        if (!settled) {
            startTime = null
            return TrackTitleMarquee.Sample(progress, null)
        }
        val start = startTime ?: (now - elapsed).also { startTime = it }
        elapsed = now - start
        return TrackTitleMarquee.sample(elapsed, overflowDp, insetDp).also { progress = it.progress }
    }

    fun reset() {
        startTime = null
        elapsed = 0L
        progress = 0f
    }
}
