package shop.terramoor.github.io.systemui

import shop.terramoor.github.io.lyrics.PlaybackClock
import kotlin.math.abs

internal data class LyricBoundaryTick(val boundaryMs: Long, val uptimeMs: Long) {
    // Ignore rounding and sampling jitter, but reschedule after a seek or clock correction.
    fun sameDeadline(other: LyricBoundaryTick?): Boolean = other != null &&
        boundaryMs == other.boundaryMs && abs(uptimeMs - other.uptimeMs) <= 3L

    companion object {
        fun plan(clock: PlaybackClock, boundaryMs: Long, elapsedNow: Long, uptimeNow: Long): LyricBoundaryTick? =
            clock.delayTo(clock.position(elapsedNow), boundaryMs)?.let { delay ->
                LyricBoundaryTick(boundaryMs, uptimeNow + delay)
            }
    }
}
