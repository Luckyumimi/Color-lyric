package shop.terramoor.github.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import shop.terramoor.github.io.lyrics.LyricFrame
import shop.terramoor.github.io.lyrics.LyricLine
import shop.terramoor.github.io.lyrics.LyricPresentation
import shop.terramoor.github.io.lyrics.LyricRows
import shop.terramoor.github.io.lyrics.Timeline
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.MissingTranslationMode
import shop.terramoor.github.io.settings.SecondRowContent

class LyricPresentationTest {
    @Test fun trackInfoShowsTheArtistWhenAvailable() {
        val frame = LyricFrame.trackInfo("Song", "Artist")
        assertTrue(frame.isTrackInfo)
        assertEquals(LyricRows("Song", "Artist"), frame.rows)
        assertEquals(LyricRows("Song"), LyricFrame.trackInfo("Song", null).rows)
    }

    private val timeline = Timeline.fromLines(listOf(
        LyricLine(1000, "First", "Translation", endMs = 2000),
        LyricLine(3000, "Second", "  ", endMs = 4000),
        LyricLine(5000, "Third", "Other translation", endMs = 5500),
        LyricLine(6000, ""),
        LyricLine(9000, "Last", endMs = 9500),
    ))

    @Test fun swappingTranslationUsesRowPositionsAndPreservesFallbacks() {
        val settings = CapsuleSettings(swapTranslation = true)
        val presentation = LyricPresentation(timeline, settings)
        assertEquals(LyricRows("Translation", "First"), presentation.frameAt(0, 10000)?.rows)
        assertEquals(LyricRows("Translation", "First"), presentation.frameAt(1, 10000)?.priorRows)
        assertEquals(LyricRows("Second", missingTranslationSingleLine = true), presentation.frameAt(1, 10000)?.rows)
        val fallback = LyricPresentation(timeline, settings.copy(missingTranslationMode = MissingTranslationMode.DOUBLE_LYRICS))
        assertEquals(LyricRows("Second", "Third", nextLyricStartMs = 5000), fallback.frameAt(1, 10000)?.rows)
        val single = LyricPresentation(timeline, settings.copy(doubleLine = false))
        assertEquals(LyricRows("First"), single.frameAt(0, 10000)?.rows)
        val lyrics = LyricPresentation(timeline, settings.copy(secondRowContent = SecondRowContent.LYRIC))
        assertEquals(LyricRows("First", "Second", nextLyricStartMs = 3000), lyrics.frameAt(0, 10000)?.rows)
    }

    @Test fun nextLyricSkipsBlankRowsButTheTimelineKeepsTheGap() {
        val presentation = LyricPresentation(timeline, CapsuleSettings(secondRowContent = SecondRowContent.LYRIC))
        assertEquals(1000L, presentation.nextBoundary(-1))
        assertEquals(LyricRows("First", "Second", nextLyricStartMs = 3000), presentation.frameAt(0, 10000)?.rows)
        assertEquals(LyricRows("Third", "Last", nextLyricStartMs = 9000), presentation.frameAt(2, 10000)?.rows)
        assertNull(presentation.frameAt(presentation.indexAt(6000), 10000))
        assertEquals(9000L, presentation.nextBoundary(3))
        val last = checkNotNull(presentation.frameAt(4, 10000))
        assertEquals(LyricRows("Last"), last.rows)
        assertEquals(9500L, last.endMs)
        assertNull(last.priorRows)
        assertFalse(last.promotesPreviousLyric)
        assertNull(presentation.nextBoundary(4))
    }

    @Test fun missingTranslationUsesTheConfiguredFallbackAndKeepsPriorRows() {
        val defaults = CapsuleSettings()
        val single = LyricPresentation(timeline, defaults)
        val frame = checkNotNull(single.frameAt(1, 10000))
        assertEquals(LyricRows("Second", missingTranslationSingleLine = true), frame.rows)
        assertEquals(LyricRows("First", "Translation"), frame.priorRows)
        assertEquals(14, defaults.effectiveFirstRowSize(frame.rows.missingTranslationSingleLine))
        assertEquals(12, defaults.copy(fallbackSingleLineExpand = false).effectiveFirstRowSize(frame.rows.missingTranslationSingleLine))

        val double = LyricPresentation(timeline, defaults.copy(missingTranslationMode = MissingTranslationMode.DOUBLE_LYRICS))
        assertEquals(LyricRows("Second", "Third", nextLyricStartMs = 5000), double.frameAt(1, 10000)?.rows)
        assertEquals(LyricRows("Third", "Other translation"), double.frameAt(2, 10000)?.rows)
        assertEquals(LyricRows("Second", "Third", nextLyricStartMs = 5000), double.frameAt(2, 10000)?.priorRows)
        assertTrue(checkNotNull(double.frameAt(2, 10000)).promotesPreviousLyric)
    }

    @Test fun matchingTranslationTextDoesNotBecomeTheNextLyric() {
        val repeatedText = Timeline.fromLines(listOf(LyricLine(0, "One", "Two"), LyricLine(1000, "Two", "三")))
        val translation = LyricPresentation(repeatedText, CapsuleSettings())
        assertFalse(checkNotNull(translation.frameAt(1, 3000)).promotesPreviousLyric)
        val lyrics = LyricPresentation(repeatedText, CapsuleSettings(secondRowContent = SecondRowContent.LYRIC))
        assertTrue(checkNotNull(lyrics.frameAt(1, 3000)).promotesPreviousLyric)
    }

    @Test fun outgoingLayoutChoosesItsOwnBoundaryAndScrollEnd() {
        val defaults = CapsuleSettings()
        val mixed = LyricPresentation(timeline, defaults)
        assertEquals(0, mixed.indexAt(2000))
        assertEquals(1, mixed.indexAt(3000))
        assertEquals(3000L, mixed.nextBoundary(0))
        assertEquals(3000L, mixed.frameAt(0, 10000)?.endMs)
        assertEquals(LyricRows("First", "Translation"), mixed.frameAt(0, 10000)?.rows)
        assertEquals(3000L, mixed.frameAt(1, 10000)?.displayStartMs)
        assertEquals(1, mixed.indexAt(3999))
        assertEquals(4000L, mixed.nextBoundary(1))
        assertEquals(4000L, mixed.frameAt(1, 10000)?.endMs)
        assertEquals(2, mixed.indexAt(4000))
        assertEquals(4000L, mixed.frameAt(2, 10000)?.displayStartMs)
        assertEquals(0, mixed.indexAt(1999))

        val singleOnly = LyricPresentation(timeline, defaults.copy(doubleLine = false))
        assertEquals(2000L, singleOnly.nextBoundary(0))
        assertEquals(1, singleOnly.indexAt(2000))
        assertEquals(LyricRows("First"), singleOnly.frameAt(0, 10000)?.rows)
    }

    @Test fun translationAndNextLyricBothUseDoubleRowTiming() {
        val settings = CapsuleSettings(missingTranslationMode = MissingTranslationMode.DOUBLE_LYRICS)
        val presentation = LyricPresentation(timeline, settings)
        assertEquals(3000L, presentation.nextBoundary(0))
        assertEquals(1, presentation.indexAt(4000))
        assertEquals(5000L, presentation.nextBoundary(1))
        assertEquals(5000L, presentation.frameAt(1, 10000)?.endMs)
        assertEquals(2, presentation.indexAt(5000))
        assertEquals(5000L, presentation.frameAt(2, 10000)?.displayStartMs)
    }

    @Test fun explicitNextLyricUsesDoubleTimingEvenWhenTheLineHasATranslation() {
        val settings = CapsuleSettings(secondRowContent = SecondRowContent.LYRIC)
        val presentation = LyricPresentation(timeline, settings)
        assertEquals(0, presentation.indexAt(2000))
        assertEquals(3000L, presentation.nextBoundary(0))
        assertEquals(3000L, presentation.frameAt(0, 10000)?.endMs)
        assertEquals(3000L, presentation.frameAt(1, 10000)?.displayStartMs)
    }
}
