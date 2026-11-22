package shop.terramoor.github.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import shop.terramoor.github.io.lyrics.PlaybackClock
import shop.terramoor.github.io.systemui.LyricBoundaryTick

class LyricBoundaryTickTest {
    private val clock = PlaybackClock(0L, 10_000L, 1f, true, 60_000L)

    @Test fun decreasingDelayDoesNotReplaceTheSamePendingTick() {
        val initial = checkNotNull(LyricBoundaryTick.plan(clock, 27_890L, 10_000L, 6_000L))
        assertEquals(33_890L, initial.uptimeMs)
        for (elapsed in 0L..1_000L step 3) {
            val refreshed = checkNotNull(LyricBoundaryTick.plan(clock, 27_890L, 10_000L + elapsed, 6_002L + elapsed))
            assertTrue(refreshed.sameDeadline(initial))
        }
    }

    @Test fun seekAndSleepShiftTheDeadlineAndPauseCancelsIt() {
        val initial = checkNotNull(LyricBoundaryTick.plan(clock, 27_890L, 10_000L, 6_000L))
        val afterSeek = checkNotNull(LyricBoundaryTick.plan(clock.copy(basePositionMs = 1_000L), 27_890L, 10_000L, 6_000L))
        val afterSleep = checkNotNull(LyricBoundaryTick.plan(clock, 27_890L, 10_500L, 6_000L))
        assertFalse(afterSeek.sameDeadline(initial))
        assertFalse(afterSleep.sameDeadline(initial))
        assertFalse(initial.copy(boundaryMs = 27_891L).sameDeadline(initial))
        assertNull(LyricBoundaryTick.plan(clock.copy(moving = false), 27_890L, 10_000L, 6_000L))
        assertNull(LyricBoundaryTick.plan(clock, 27_890L, 37_890L, 33_890L))
    }
}
