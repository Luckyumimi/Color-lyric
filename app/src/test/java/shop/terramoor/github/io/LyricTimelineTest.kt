package shop.terramoor.github.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import shop.terramoor.github.io.lyrics.LyricLine
import shop.terramoor.github.io.lyrics.LyricFrame
import shop.terramoor.github.io.lyrics.LyricPayload
import shop.terramoor.github.io.lyrics.LyricRows
import shop.terramoor.github.io.lyrics.PlaybackClock
import shop.terramoor.github.io.lyrics.Timeline
import shop.terramoor.github.io.settings.LyricSwitchMode

class LyricTimelineTest {
    @Test fun exactAndNearTranslationsAreUsedOnce() {
        val timeline = Timeline.parse(
            "[00:01.000]First\n[00:01.160]Second\n[00:03.100]Third\n[00:04.000]Fourth",
            "",
            "[00:01.160]第二句\n[00:03.180]第三句\n[00:04.000]Fourth",
        )!!
        assertEquals(listOf(null, "第二句", "第三句", null), timeline.lines.map { it.translation })
    }

    @Test fun enhancedLrcRetainsTheLineEndAndGaps() {
        val timeline = Timeline.parse("untimed", "[00:02.00]<00:02.00>Keep <00:02.45>spaces<00:03.00>\n[00:04]", "")!!
        assertEquals(listOf(LyricLine(2000, "Keep spaces", endMs = 3000), LyricLine(4000, "")),
            timeline.lines.map { it.copy(words = emptyList()) })
        assertNull(Timeline.parse("[ar:Artist]", "[00:99]invalid", "[00:00]translation only"))
    }

    @Test fun plainTextAndZeroOnlyLrcDoNotCreateATimeline() {
        assertNull(Timeline.parse("Untimed words", "[00:00.000]First\n[00:00.000]Last", ""))
        assertNull(Timeline.parse("[00:00]Static", "", "[00:01]Translation"))
    }

    @Test fun timedRawLyricsTakePrecedenceOverZeroOnlyPlainLyrics() {
        val timeline = Timeline.parse(
            "[00:00]First\n[00:00]Last",
            "[00:00]First\n[00:03]Last",
            "[00:03]末句",
        )!!
        assertEquals(listOf(LyricLine(0, "First"), LyricLine(3000, "Last", "末句")), timeline.lines)
    }

    @Test fun aTimedGapCanEndTheOnlyLyricLine() {
        val timeline = Timeline.parse("[00:00]First\n[00:04]", "", "")!!
        assertEquals(listOf(LyricLine(0, "First"), LyricLine(4000, "")), timeline.lines)
    }

    @Test fun lookupSupportsBackwardSeekAndExactBoundaries() {
        val timeline = Timeline.parse("[00:01][00:04]Repeat\n[00:02.25]Second", "", "")!!
        assertEquals(-1, timeline.indexAt(999))
        assertEquals(2, timeline.indexAt(4000))
        assertEquals(1, timeline.indexAt(2250))
        assertEquals(0, timeline.indexAt(1000))
    }

    @Test fun clockSchedulesAtPlaybackSpeedAndFreezesOnPause() {
        val playing = PlaybackClock(2000, 1000, 2f, true, 6000)
        assertEquals(3000L, playing.position(1500))
        assertEquals(500L, playing.delayTo(3000, 4000))
        assertEquals(6000L, playing.position(9000))
        assertNull(playing.delayTo(6000, 7000))
        val paused = playing.copy(basePositionMs = 4800, moving = false)
        assertEquals(4800L, paused.position(2000))
        assertNull(paused.delayTo(4800, 5000))
    }

    @Test fun payloadTitleGuardsAgainstPreviousSong() {
        assertTrue(LyricPayload.titlesMatch("Ａ Song", "a  song", true))
        assertFalse(LyricPayload.titlesMatch("Next", "Previous", true))
        assertFalse(LyricPayload.titlesMatch("Next", "", true))
        assertTrue(LyricPayload.titlesMatch("Track", "", false))
    }

    @Test fun providerRemasterAliasesRequireTheSameArtistAndNoConflictingEdition() {
        assertTrue(LyricPayload.titlesMatch("Song (2011 Remaster)", "Song", true, "Artist", "artist"))
        assertTrue(LyricPayload.titlesMatch("Song", "Song - Remastered 2011", true, "Artist", "artist"))
        assertFalse(LyricPayload.titlesMatch("Song - Remastered 2011", "Song", true, "Artist", "Other"))
        assertFalse(LyricPayload.titlesMatch("Song - Remastered 2011", "Song - Remastered 2023", true, "Artist", "Artist"))
        assertFalse(LyricPayload.titlesMatch("Song - Live", "Song", true, "Artist", "Artist"))
    }

    @Test fun nextLineAppearsAtExplicitEndAndScrollsOnItsOwnClock() {
        val timeline = Timeline.parse("[00:01]First\n[00:05]Second", "", "", mapOf(1000L to 2600L))!!
        assertEquals(0, timeline.displayIndexAt(2599))
        assertEquals(1, timeline.displayIndexAt(2600))
        assertEquals(2600L, timeline.scrollEnd(0, 8000))
        val byStart = LyricSwitchMode.AT_NEXT_START
        assertEquals(5000L, timeline.displayStart(1, byStart))
        assertEquals(5000L, timeline.scrollEnd(0, 8000, byStart))
        assertEquals(0, timeline.displayIndexAt(4999, byStart))
        assertEquals(1, timeline.displayIndexAt(5000, byStart))
        val frame = LyricFrame(LyricRows("Second"), startMs = 5000, endMs = 8000, displayStartMs = 2600)
        assertEquals(0f, frame.scrollFraction(4999))
        assertTrue(frame.scrollFraction(6000) > 0f)
    }

    @Test fun noEndUsesRealStartWithoutSkippingGapsOrFirstLine() {
        val timeline = Timeline.parse("[00:02]First\n[00:04]Second\n[00:05]\n[00:15]Third", "", "")!!
        for (mode in LyricSwitchMode.entries) {
            assertEquals(-1, timeline.displayIndexAt(1999, mode))
            assertEquals(2000L, timeline.displayStart(0, mode))
            assertEquals(0, timeline.displayIndexAt(3999, mode))
            assertEquals(4000L, timeline.scrollEnd(0, 18000, mode))
            assertEquals(1, timeline.displayIndexAt(4000, mode))
            assertEquals(2, timeline.displayIndexAt(14999, mode))
            assertEquals(3, timeline.displayIndexAt(15000, mode))
            assertEquals(18000L, timeline.scrollEnd(3, 18000, mode))
            assertEquals(0, timeline.displayIndexAt(2000, mode))
        }
    }

}
