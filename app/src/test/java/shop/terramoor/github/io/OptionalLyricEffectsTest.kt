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
import shop.terramoor.github.io.systemui.cameraHeadInset
import shop.terramoor.github.io.systemui.cameraWordFade
import shop.terramoor.github.io.systemui.cameraWordShift
import shop.terramoor.github.io.systemui.introDotFill
import shop.terramoor.github.io.systemui.wordFollowAnchor
import shop.terramoor.github.io.systemui.wordFollowProgress

class OptionalLyricEffectsTest {
    @Test fun longWordEmphasisRisesAfterTheBaselineAndFadesBeforeTheWordEnds() {
        val word = LyricWord(0, 1, 1000L, 3000L)
        assertEquals(0f, word.sustainEmphasis(999L), 0f)
        assertEquals(0f, word.sustainEmphasis(1170L), 0f)
        assertEquals(0.5f, word.sustainEmphasis(1330L), 0f)
        assertEquals(1f, word.sustainEmphasis(1490L), 0f)
        assertEquals(1f, word.sustainEmphasis(2680L), 0f)
        assertEquals(0.5f, word.sustainEmphasis(2840L), 0f)
        assertEquals(0f, word.sustainEmphasis(3000L), 0f)
        assertEquals(0f, word.sustainEmphasis(3500L), 0f)
    }

    @Test fun untimedAndShortWordsNeverGetLongNoteEmphasis() {
        val word = LyricWord(0, 1, 1000L, 2200L)
        assertEquals(1f, word.sustainEmphasis(1600L), 0f)
        for (end in listOf(null, 1000L, 2199L)) {
            assertEquals(0f, word.copy(endMs = end).sustainEmphasis(1600L), 0f)
        }
    }

    @Test fun scrollWaitsForTheAnchorThenFollowsTheHighlightUntilTheTailIsVisible() {
        val first = LyricWord(0, 1, 1000L, 1200L)
        val second = LyricWord(1, 2, 1500L, 2300L)
        val scroll = requireNotNull(WordLyricScroll.fromLayout(listOf(first, second), 100f) {
            floatArrayOf(0f, 25f, 100f)[it]
        })
        fun progress(index: Int, fraction: Float) = wordFollowProgress(scroll.highlightAt(index, fraction), 40f, 0f, -30f)

        assertEquals(0f, progress(-1, 0f), 0f)
        assertEquals(0f, progress(0, first.fraction(1200L)), 0f)
        assertEquals(0f, progress(1, 0.20f), 0f)
        assertEquals(0.5f, progress(1, second.fraction(1820L)), 0.0001f)
        assertEquals(1f, progress(1, 0.60f), 0f)
        assertEquals(1f, progress(1, 0.85f), 0f)
    }

    @Test fun followAnchorUsesTheCenterOfTheVisibleRightSlot() {
        assertEquals(60f, wordFollowAnchor(120f, 0f, 8f), 0f)
        assertEquals(87f, wordFollowAnchor(120f, 54f, 8f), 0f)
        assertEquals(54f, wordFollowAnchor(120f, 0f, 66f), 0f)
        assertEquals(108f, wordFollowAnchor(120f, 108f, 14f), 0f)
    }

    @Test fun scrollCompensatesForRightwardCameraAvoidanceAtTheFollowPoint() {
        fun shiftAt(offset: Float) = (8f + offset).coerceAtLeast(0f)
        val travel = 30f
        val anchor = 60f
        val highlight = 57f
        assertEquals(0f, wordFollowProgress(48f, anchor, 0f, -travel, ::shiftAt), 0f)
        val progress = wordFollowProgress(highlight, anchor, 0f, -travel, ::shiftAt)
        val offset = -travel * progress
        assertEquals(anchor, highlight + offset + shiftAt(offset), 0.02f)
        assertEquals(1f, wordFollowProgress(100f, anchor, 0f, -travel, ::shiftAt), 0f)
        assertEquals(0f, wordFollowProgress(100f, anchor, 0f, 0f, ::shiftAt), 0f)
    }

    @Test fun scrollHoldsTheTimedWordEndAcrossShortOrLongGaps() {
        val word = LyricWord(0, 1, 1000L, 1200L)
        val next = LyricWord(1, 2, 1500L, 1700L)
        val scroll = requireNotNull(WordLyricScroll.fromLayout(listOf(word, next), 110f) {
            floatArrayOf(0f, 55f, 110f)[it]
        })
        fun follow(index: Int, fraction: Float) = wordFollowProgress(scroll.highlightAt(index, fraction), 45f, 0f, -40f)
        assertEquals(0.25f, follow(0, word.fraction(1200L)), 0f)
        assertEquals(0.25f, follow(0, word.fraction(1230L)), 0f)
        assertEquals(0.25f, follow(0, word.fraction(1499L)), 0f)
        assertEquals(0.25f, follow(1, next.fraction(1500L)), 0f)
        assertTrue(follow(1, next.fraction(1510L)) > 0.25f)
    }

    @Test fun scrollRejectsWordBoundariesThatWouldMoveBackwards() {
        val words = (0..2).map { LyricWord(it, it + 1, it * 100L, (it + 1) * 100L) }
        assertNull(WordLyricScroll.fromLayout(words, 100f) { floatArrayOf(0f, 70f, 30f, 100f)[it] })
        assertNull(WordLyricScroll.fromLayout(listOf(LyricWord(0, 1, 0L)), 100f) { it * 100f })
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
        val original = CapsuleSettings(swapTranslation = true, wordLyrics = false)
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
        assertNull(LyricPresentation(timeline, CapsuleSettings(introDots = false)).introAt(1000L))
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

    @Test fun rightCameraBufferOnlyOffsetsTheStartOfScrolling() {
        val head = 5f
        assertEquals(6f, cameraHeadInset(6f, head, head), 0f)
        assertEquals(4f, cameraHeadInset(6f, head, 3f), 0f)
        assertEquals(0f, cameraHeadInset(6f, head, -1f), 0f)
        assertEquals(0f, cameraHeadInset(6f, head, -20f), 0f)
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
