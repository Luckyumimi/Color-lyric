package shop.terramoor.github.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import shop.terramoor.github.io.lyrics.LyricLine
import shop.terramoor.github.io.lyrics.LyricPresentation
import shop.terramoor.github.io.lyrics.LyricRows
import shop.terramoor.github.io.lyrics.LyricWord
import shop.terramoor.github.io.lyrics.LyricWordLine
import shop.terramoor.github.io.lyrics.Timeline
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.systemui.CameraWordBounds
import shop.terramoor.github.io.systemui.CameraWordShift
import shop.terramoor.github.io.systemui.CameraLyricTravel
import shop.terramoor.github.io.systemui.WordLyricScroll
import shop.terramoor.github.io.systemui.cameraLyricTravel
import shop.terramoor.github.io.systemui.cameraWordFade
import shop.terramoor.github.io.systemui.cameraWordShift
import shop.terramoor.github.io.systemui.introDotFill

class OptionalLyricEffectsTest {
    @Test fun scrollUsesEachTimedWordsWidthAndHoldsThroughTheGap() {
        val first = LyricWord(0, 1, 1000L, 1200L)
        val second = LyricWord(1, 6, 1500L, 2300L)
        val scroll = requireNotNull(WordLyricScroll.fromLayout(listOf(first, second), 100f) { 25f })

        assertEquals(WordLyricScroll.Sample(0f, 10L), scroll.sample(990L))
        assertEquals(WordLyricScroll.Sample(0.125f, 0L), scroll.sample(1100L))
        assertEquals(WordLyricScroll.Sample(0.25f, 150L), scroll.sample(1350L))
        assertEquals(WordLyricScroll.Sample(0.25f, 0L), scroll.sample(1500L))
        assertEquals(WordLyricScroll.Sample(0.625f, 0L), scroll.sample(1900L))
        assertEquals(WordLyricScroll.Sample(1f, null), scroll.sample(2300L))
    }

    @Test fun scrollCarriesSpeedThroughTheWordBoundaryWithoutOvershoot() {
        val words = listOf(LyricWord(0, 3, 1000L, 1200L), LyricWord(3, 6, 1200L, 2000L))
        val scroll = requireNotNull(WordLyricScroll.fromLayout(words, 100f) { 60f })
        val atJoin = scroll.sample(1200L).progress
        val speedBefore = atJoin - scroll.sample(1199L).progress
        val speedAfter = scroll.sample(1201L).progress - atJoin
        assertEquals(0.6f, atJoin, 0f)
        assertTrue(speedBefore > 0f)
        assertEquals(speedBefore, speedAfter, 0.00005f)
        var previous = 0f
        for (time in 1000L..2000L) {
            val now = scroll.sample(time).progress
            assertTrue(now >= previous && now <= 1f)
            previous = now
        }
    }

    @Test fun scrollKeepsMovingThroughAShortGapAndAnInstantaneousHighlight() {
        val words = listOf(LyricWord(0, 1, 1000L, 1200L), LyricWord(1, 4, 1230L), LyricWord(4, 8, 1230L, 1700L))
        val scroll = requireNotNull(WordLyricScroll.fromLayout(words, 100f) { if (it == 0) 25f else 55f })
        val inGap = scroll.sample(1215L)
        assertEquals(0L, inGap.redrawDelayMs)
        assertTrue(inGap.progress > scroll.sample(1200L).progress)
        assertTrue(inGap.progress < 0.25f)
        assertEquals(0.25f, scroll.sample(1230L).progress, 0f)
        assertTrue(scroll.sample(1231L).progress in 0.25f..0.26f)
    }

    @Test fun scrollRejectsWordBoundariesThatWouldMoveBackwards() {
        val words = (0..2).map { LyricWord(it, it + 1, it * 100L, (it + 1) * 100L) }
        assertNull(WordLyricScroll.fromLayout(words, 100f) { if (it == 0) 70f else 30f })
    }

    @Test fun unsungWordStartsBelowTheBaselineAndSettlesFromBelow() {
        val word = LyricWord(0, 1, 1000L, 1800L)
        assertEquals(1f, word.baselineOffset(999L), 0f)
        assertEquals(1f, word.baselineOffset(1000L), 0f)
        assertEquals(0.5f, word.baselineOffset(1085L), 0f)
        assertEquals(0f, word.baselineOffset(1170L), 0f)
        assertEquals(0f, word.baselineOffset(1800L), 0f)
    }

    @Test fun cameraAvoidanceScrollsTightlyPackedLinesAndFadesOnlyTheCrossingCluster() {
        val crossing = CameraWordBounds(1, 4, 20f, 50f)
        val units = listOf(CameraWordBounds(0, 1, 0f, 20f), crossing)
        assertEquals(CameraLyricTravel(18f, 0f), cameraLyricTravel(units, 50f, 50f, 38f, 0f))
        val right = requireNotNull(cameraWordFade(units, 38f, 8f, 1f, null))
        assertEquals(crossing, right.unit)
        assertTrue(right.majorityOnRight)
        assertEquals(1f / 3f, right.opacity, 0.0001f)
        assertEquals(0f, cameraWordFade(units, 38f, 3f, 1f, null)?.opacity ?: -1f, 0f)
        assertNull(cameraWordFade(units, 38f, 0f, 1f, CameraWordShift(4, -12f)))
    }
    @Test fun enhancedLrcRetainsTimingWithoutReplacingPlainLyrics() {
        val timeline = requireNotNull(Timeline.parse(
            "[00:03]a b!\n[00:06]next",
            "[00:03]<00:03>a <00:04>b!<00:05>\n[00:06]different",
            "[00:03]translated",
        ))
        val (first, next) = timeline.lines
        assertEquals("a b!", first.text)
        assertEquals("translated", first.translation)
        assertEquals(listOf(LyricWord(0, 2, 3000L, 4000L), LyricWord(2, 4, 4000L, 5000L)), first.words)
        assertTrue(next.words.isEmpty())
    }

    @Test fun matchingTtmlWinsAndMismatchedTtmlFallsBackToRaw() {
        val ttmlSpans = listOf(LyricWord(0, 1, 1100L, 1300L), LyricWord(1, 2, 1400L, 1500L))
        fun read(text: String) = requireNotNull(Timeline.parse("[00:01]甲 乙", "[00:01]<00:01>甲 <00:02>乙", "",
            ttmlWords = mapOf(1000L to LyricWordLine(text, ttmlSpans)))).lines.single().words
        val first = read("甲乙").first()
        assertEquals(LyricWord(0, 2, 1100L, 1300L), first)
        assertEquals(0.5f, first.fraction(1200L), 0f)
        assertEquals(0f, first.fraction(1000L), 0f)
        assertEquals(1f, first.fraction(1400L), 0f)
        assertEquals(LyricWord(2, 3, 2000L), read("丙乙").last())
    }

    @Test fun wordTimingFollowsTheOriginalTextWhenTranslationIsSwapped() {
        val words = listOf(LyricWord(0, 4, 1000L, 2000L))
        val timeline = Timeline.fromLines(listOf(LyricLine(1000L, "Sing", "译文", words = words)))
        val original = CapsuleSettings(swapTranslation = true)
        val disabled = requireNotNull(LyricPresentation(timeline, original).frameAt(0, 5000L)).rows
        val enabled = requireNotNull(LyricPresentation(timeline, original.copy(wordLyrics = true)).frameAt(0, 5000L)).rows
        assertEquals(disabled.first, enabled.first)
        assertEquals(disabled.second, enabled.second)
        assertTrue(disabled.secondWords.isEmpty())
        assertTrue(enabled.firstWords.isEmpty())
        assertEquals(words, enabled.secondWords)
    }

    @Test fun introUsesTheFirstNonblankLyricAsItsEnd() {
        val timeline = Timeline.fromLines(listOf(LyricLine(0L, ""), LyricLine(3000L, "First"), LyricLine(6800L, "")))
        assertNull(LyricPresentation(timeline, CapsuleSettings()).introAt(1000L))
        val presenter = LyricPresentation(timeline, CapsuleSettings(introDots = true))
        val track = LyricRows("Song", "Artist")
        val intro = requireNotNull(presenter.introAt(2999L) { track })
        assertTrue(intro.isIntro)
        assertEquals(track, intro.rows)
        assertEquals(3000L, intro.endMs)
        assertNull(presenter.introAt(3000L) { error("Track data read after the intro") })
        assertNull(presenter.introAt(6900L))
        assertEquals(1f, introDotFill(0, 1500L, 3000L), 0f)
        assertTrue(introDotFill(1, 1500L, 3000L) > introDotFill(2, 1500L, 3000L))
    }

    @Test fun staticCameraAvoidanceKeepsTheClusterWholeWhenSpaceAllows() {
        val units = listOf(CameraWordBounds(0, 1, 0f, 20f), CameraWordBounds(1, 6, 20f, 50f))
        assertEquals(CameraWordShift(1, 8f), cameraWordShift(units, 50f, 90f, 38f, 10f, 10f, 10f))
        assertEquals(CameraWordShift(6, -12f), cameraWordShift(units, 50f, 66f, 54f, 16f, 16f, 16f))
        assertNull(cameraWordShift(units, 50f, 50f, 38f, 0f, 0f, 0f))
    }

    @Test fun scrollingCameraAvoidanceUnwindsTheEndpointShift() {
        val units = listOf(CameraWordBounds(0, 1, 0f, 20f), CameraWordBounds(1, 4, 20f, 45f),
            CameraWordBounds(4, 6, 45f, 65f), CameraWordBounds(6, 7, 65f, 80f))
        assertEquals(CameraWordShift(1, 10f), cameraWordShift(units, 80f, 60f, 30f, 0f, 0f, -20f))
        assertEquals(CameraWordShift(1, 5f), cameraWordShift(units, 80f, 60f, 30f, -5f, 0f, -20f))
        assertNull(cameraWordShift(units, 80f, 60f, 30f, -10f, 0f, -20f))
        assertEquals(CameraWordShift(6, -15f), cameraWordShift(units, 80f, 60f, 30f, -20f, 0f, -20f))
    }
}
