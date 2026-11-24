package shop.terramoor.github.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import shop.terramoor.github.io.lyrics.LyricCountdown
import shop.terramoor.github.io.lyrics.LyricLine
import shop.terramoor.github.io.lyrics.LyricPresentation
import shop.terramoor.github.io.lyrics.LyricRows
import shop.terramoor.github.io.lyrics.LyricWord
import shop.terramoor.github.io.lyrics.Timeline
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.SecondRowContent
import shop.terramoor.github.io.systemui.countdownDotScale
import shop.terramoor.github.io.systemui.interludeCapsuleWidth
import shop.terramoor.github.io.systemui.interludeViewportInset

class LyricCountdownTest {
    private val enabled = CapsuleSettings(introDots = true, wordLyrics = true)

    @Test fun dotsCompleteWholeBreathsBeforeTheFinalSecond() {
        fun scale(elapsed: Long) = countdownDotScale(elapsed, 11000L, isIntro = true)
        assertEquals(1f, scale(0L), 0f)
        assertEquals(1.25f, scale(2500L), 0f)
        assertEquals(1f, scale(5000L), 0f)
        assertEquals(1.25f, scale(7500L), 0f)
        assertEquals(1f, scale(9999L), 0.0001f)
        assertEquals(1f, scale(10000L), 0f)
        assertEquals(1f, scale(11000L), 0f)
    }

    @Test fun shortIntroHoldsScaleAndInterludeLeavesRoomBeforeBreathing() {
        assertEquals(1f, countdownDotScale(1500L, 3999L, isIntro = true), 0f)
        assertEquals(1.25f, countdownDotScale(1500L, 4000L, isIntro = true), 0f)
        assertEquals(1f, countdownDotScale(500L, 7500L, isIntro = false), 0f)
        assertEquals(1.25f, countdownDotScale(3500L, 7500L, isIntro = false), 0f)
        assertEquals(1f, countdownDotScale(6500L, 7500L, isIntro = false), 0f)
    }

    @Test fun interludeWidthContractsAfterTheLyricTransitionBreathesAndReopensBeforeTheNextLine() {
        val gap = LyricCountdown(2000L, 9500L)
        fun width(time: Long, countdown: LyricCountdown = gap) = interludeCapsuleWidth(countdown, time,
            normalWidth = 170, height = 30f, density = 1f, leading = 25f, trailing = 28f,
            cameraOffset = 0f, cameraRadius = 11f, animateOutgoingLyric = true)

        assertEquals(170, width(2260L))
        val shrink = (2260L..2500L step 5L).map { width(it) }
        assertTrue(shrink.zipWithNext().all { (a, b) -> a - b in 0..2 })
        val compact = shrink.last()
        assertTrue(width(2380L) in (compact + 1) until 170)
        assertTrue(width(4000L) > compact)
        assertTrue(width(5500L) > width(4000L))
        assertEquals(compact, width(8500L))

        val reopen = (9080L..9500L step 5L).map { width(it) }
        assertEquals(compact, reopen.first())
        assertTrue(reopen.zipWithNext().all { (a, b) -> b - a in 0..2 })
        assertTrue(width(9270L) in (compact + 1) until 170)
        assertEquals(170, width(9468L))
        assertEquals(170, width(9500L))
        assertEquals(170, width(3500L, LyricCountdown(0L, 9500L)))
    }

    @Test fun compactWidthReservesControlsCameraAndDotsWithoutExceedingTheConfiguredWidth() {
        val gap = LyricCountdown(2000L, 9500L)
        fun width(maximum: Int, offset: Float, radius: Float) = interludeCapsuleWidth(gap, 5500L,
            normalWidth = maximum, height = 30f, density = 1f, leading = 25f, trailing = 28f,
            cameraOffset = offset, cameraRadius = radius, animateOutgoingLyric = true)

        assertTrue(width(180, 0f, 11f) > width(180, 0f, 0f))
        assertTrue(width(180, 3f, 11f) > width(180, 0f, 11f))
        assertEquals(156, width(156, 3f, 11f))
    }

    @Test fun interludeViewportBlendsFromFullWidthAfterNativeTakeover() {
        assertEquals(0 to true, interludeViewportInset(170, 100, 0L))
        val insets = (0L..240L step 16L).map { interludeViewportInset(170, 100, it).first }
        assertTrue(insets.zipWithNext().all { (a, b) -> b - a in 0..4 })
        assertEquals(35 to false, interludeViewportInset(170, 100, 240L))
        assertEquals(0 to false, interludeViewportInset(170, 170, 100L))
    }

    @Test fun shortIntroSkipsDotsAndLongIntroBecomesTheOutgoingFrame() {
        val track = LyricRows("Song", "Artist")
        for (first in listOf(500L, 2999L, 3000L, 12000L)) {
            val source = Timeline.fromLines(listOf(LyricLine(first, "First"), LyricLine(first + 4000L, "Next")))
            val presentation = LyricPresentation(source, enabled)
            val countdown = LyricCountdown(0L, first).takeIf { first >= 3000L }
            assertEquals(countdown, presentation.introAt(first - 1) { track }?.countdown)
            assertEquals(first, presentation.nextBoundary(-1))
            assertNull(presentation.introAt(first))
            val lyric = checkNotNull(presentation.frameAt(presentation.indexAt(first), first + 8000L) { track })
            assertEquals(countdown, lyric.priorCountdown)
            assertEquals(track.takeIf { countdown != null }, lyric.priorRows)
            assertEquals(first, lyric.motionStartMs)
        }
    }

    @Test fun longGapHasItsOwnBoundariesAndNextLyricDoesNotAppearEarly() {
        val timeline = Timeline.fromLines(listOf(LyricLine(0L, "First", endMs = 2500L),
            LyricLine(9500L, "Next"), LyricLine(14000L, "Last")))
        for (double in listOf(false, true)) {
            val presentation = LyricPresentation(timeline, enabled.copy(doubleLine = double, secondRowContent = SecondRowContent.LYRIC))
            val duration = 18000L
            val frame = checkNotNull(presentation.frameAt(presentation.indexAt(2499L), duration))
            assertEquals("First", frame.rows.first)
            assertNull(frame.rows.second)
            assertEquals(2500L, presentation.nextBoundary(presentation.indexAt(2499L)))
            val countdown = LyricCountdown(2500L, 9500L)
            for (position in listOf(9499L, 5200L, 2500L)) { // Includes seeking backward inside the gap.
                val index = presentation.indexAt(position)
                val gap = checkNotNull(presentation.frameAt(index, duration))
                assertEquals(countdown, gap.countdown)
                assertEquals(2500L, gap.motionStartMs)
                assertEquals(frame.rows, gap.priorRows)
                assertEquals(LyricRows(""), gap.rows)
                assertEquals(9500L, presentation.nextBoundary(index))
            }
            val resumed = checkNotNull(presentation.frameAt(presentation.indexAt(9500L), duration))
            assertEquals("Next", resumed.rows.first)
            assertEquals(9500L, resumed.displayStartMs)
            assertEquals(countdown, resumed.priorCountdown)
            assertNull(resumed.priorRows)
        }
        val off = LyricPresentation(timeline, enabled.copy(introDots = false))
        assertEquals(listOf("First", "Next", "Last"), off.lines.map { it.text })
    }

    @Test fun gapRequiresATimedEndOrBlankMarkerAndAnotherLyric() {
        fun gaps(vararg lines: LyricLine) = LyricPresentation(Timeline.fromLines(lines.toList()), enabled)
            .lines.filter { it.isInstrumentalGap }
        val next = LyricLine(15000L, "Second")
        assertEquals(emptyList<LyricLine>(), gaps(LyricLine(0L, "Slow first lyric"), next))
        assertEquals(emptyList<LyricLine>(), gaps(LyricLine(0L, "First", endMs = 8001L), next))
        assertEquals(emptyList<LyricLine>(), gaps(LyricLine(0L, "First", endMs = 2000L), LyricLine(15000L, "")))
        assertEquals(emptyList<LyricLine>(), gaps(LyricLine(0L, "Overlapping", endMs = 13500L),
            LyricLine(1000L, "First", endMs = 2200L), next))
        assertEquals(2500L, gaps(LyricLine(0L, "First"), LyricLine(2500L, ""), LyricLine(7000L, ""), next).single().startMs)
        assertEquals(4000L, gaps(LyricLine(0L, "First", words = listOf(LyricWord(0, 5, 0L, 4000L))), next).single().startMs)
    }

    @Test fun ttmlEndAndExplicitBlankUseTheSameRealLyricBoundary() {
        val source = checkNotNull(Timeline.parse("[00:00]First\n[00:05]\n[00:18]Second", "", "", ends = mapOf(0L to 4000L)))
        val presentation = LyricPresentation(source, enabled)
        val gap = presentation.frameAt(presentation.indexAt(4000L), 25000L)?.countdown
        assertEquals(LyricCountdown(4000L, 18000L), gap)
        assertNotNull(presentation.frameAt(presentation.indexAt(18000L), 25000L)?.priorCountdown)
    }
}
