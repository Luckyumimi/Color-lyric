package shop.terramoor.github.io

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import shop.terramoor.github.io.systemui.CapsulePlayback

class CapsulePlaybackTest {
    @Test fun bufferingAndUnknownPausePositionFreezeTheCurrentLine() {
        val playback = CapsulePlayback()
        playback.update(PlaybackState.STATE_PLAYING, 8_000L, 10_000L, 1f, 60_000L, 10_000L)
        playback.update(PlaybackState.STATE_BUFFERING, 0L, 11_000L, 0f, 60_000L, 11_000L)
        playback.update(PlaybackState.STATE_PAUSED, -1L, 11_250L, 0f, 60_000L, 11_250L)
        val paused = playback.clock(60_000L)
        assertEquals(9_000L, paused.position(12_000L))
        assertFalse(paused.moving)
        assertNull(paused.delayTo(9_000L, 9_500L))
        playback.update(PlaybackState.STATE_PLAYING, 9_000L, 12_000L, 1f, 60_000L, 12_000L)
        assertEquals(9_500L, playback.clock(60_000L).position(12_500L))
    }

    @Test fun aNewTrackDoesNotStartAtThePreviousTracksPosition() {
        val playback = CapsulePlayback()
        playback.update(PlaybackState.STATE_PLAYING, 35_000L, 50_000L, 1f, 120_000L, 50_000L)
        playback.newTrack()
        playback.update(PlaybackState.STATE_PLAYING, 35_000L, 50_000L, 1f, 120_000L, 50_500L)
        assertEquals(0L, playback.clock(120_000L).position(50_500L))
        playback.update(PlaybackState.STATE_PLAYING, 500L, 50_500L, 1f, 120_000L, 50_500L)
        assertEquals(700L, playback.clock(120_000L).position(50_700L))
    }

    @Test fun playbackSnapshotBeforeTrackChangeIsStaleEvenNearTheBeginning() {
        val playback = CapsulePlayback()
        playback.update(PlaybackState.STATE_PLAYING, 500, 10_000, 1f, 20_000, 10_000)
        playback.newTrack()
        playback.update(PlaybackState.STATE_PLAYING, 500, 10_000, 1f, 20_000, 11_000)
        assertEquals(0L, playback.clock(20_000).position(11_000))
    }

}
