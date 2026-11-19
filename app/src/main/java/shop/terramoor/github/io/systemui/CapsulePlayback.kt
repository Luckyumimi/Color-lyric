package shop.terramoor.github.io.systemui

import android.media.session.PlaybackState
import shop.terramoor.github.io.lyrics.PlaybackClock

/** Bridges temporary player states without jumping back to the beginning of the current line. */
internal class CapsulePlayback {
    private var current: PlaybackClock? = null
    private var previousTrackUpdate = 0L
    private var awaitingTrackPosition = false

    fun newTrack() {
        previousTrackUpdate = current?.updatedAtMs ?: 0L
        current = null
        awaitingTrackPosition = true
    }

    fun update(state: Int?, position: Long, updateTime: Long, speed: Float, duration: Long, now: Long) {
        val stable = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_PAUSED
        if (stable && position >= 0) {
            if (awaitingTrackPosition && previousTrackUpdate > 0 && updateTime <= previousTrackUpdate &&
                (updateTime > 0 || position > TRACK_START_WINDOW_MS)
            ) return
            awaitingTrackPosition = false
            current = PlaybackClock(position, updateTime, speed, state == PlaybackState.STATE_PLAYING, duration)
        } else if (current?.moving == true) {
            current = current?.let { it.copy(basePositionMs = it.position(now), moving = false) }
        }
    }

    fun clock(duration: Long): PlaybackClock = current?.copy(durationMs = duration)
        ?: PlaybackClock(0L, 0L, 0f, false, duration)

    private companion object {
        const val TRACK_START_WINDOW_MS = 1000L
    }
}
