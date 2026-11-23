package shop.terramoor.github.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import shop.terramoor.github.io.systemui.TintArtworkOrigin
import shop.terramoor.github.io.systemui.TintArtworkSelection

class TintArtworkSelectionTest {
    private val selection = TintArtworkSelection<Pair<String, String>, Any>()
    private val firstCover = Any()
    private val secondCover = Any()
    private val albumArt = Any()

    @Test fun verifiedCoverIsUsedWhenTheCurrentTrackHasNoMediaArtwork() {
        selection.onTrack("player" to "first")
        val result = selection.select(true, null, firstCover, 1, trackIdentified = true)
        assertEquals(TintArtworkOrigin.NATIVE_COVER, result.origin)
        assertSame(firstCover, result.bitmap)
    }

    @Test fun mediaArtworkTakesOverWhenItArrivesAfterTheNativeCover() {
        selection.onTrack("player" to "first")
        assertSame(firstCover, selection.select(true, null, firstCover, 1, trackIdentified = true).bitmap)
        val result = selection.select(true, albumArt, firstCover, 1, trackIdentified = true)
        assertEquals(TintArtworkOrigin.MEDIA_ALBUM_ART, result.origin)
        assertSame(albumArt, result.bitmap)
    }

    @Test fun unchangedOldCoverIsBlockedUntilTheSlotShowsAReplacement() {
        selection.onTrack("player" to "first")
        selection.select(true, null, firstCover, 1, trackIdentified = true)
        selection.onTrack("player" to "second")
        val waiting = selection.select(true, null, firstCover, 1, trackIdentified = true)
        assertEquals(TintArtworkOrigin.WAITING_FOR_NATIVE_COVER_UPDATE, waiting.origin)
        assertNull(waiting.bitmap)
        assertSame(secondCover, selection.select(true, null, secondCover, 2, trackIdentified = true).bitmap)
        assertNull(selection.select(true, null, firstCover, 1, trackIdentified = true).bitmap)
    }

    @Test fun replacingPixelsInTheSameBitmapUnlocksTheCurrentCover() {
        selection.onTrack("player" to "first")
        selection.select(true, null, firstCover, 1, trackIdentified = true)
        selection.onTrack("player" to "second")
        assertNull(selection.select(true, null, firstCover, 1, trackIdentified = true).bitmap)
        assertSame(firstCover, selection.select(true, null, firstCover, 2, trackIdentified = true).bitmap)
    }

    @Test fun rapidTrackChangesDoNotForgetAnUnchangedCover() {
        selection.onTrack("player" to "first")
        selection.select(true, null, firstCover, 1, trackIdentified = true)
        selection.onTrack("player" to "second")
        assertNull(selection.select(true, null, null, 0, trackIdentified = true).bitmap)
        selection.onTrack("player" to "third")
        assertNull(selection.select(true, null, firstCover, 1, trackIdentified = true).bitmap)
    }

    @Test fun sessionHandoffBlocksThePreviousCoverEvenAfterAnEmptyFrame() {
        selection.onTrack("first session" to "track")
        selection.select(true, albumArt, firstCover, 1, trackIdentified = true)
        selection.select(true, null, null, 0, trackIdentified = false)
        selection.onTrack("second session" to "track")
        assertNull(selection.select(true, null, firstCover, 1, trackIdentified = true).bitmap)
        assertSame(secondCover, selection.select(true, null, secondCover, 2, trackIdentified = true).bitmap)
    }

    @Test fun rebindingTheSameTrackKeepsAnAcceptedCover() {
        selection.onTrack("player" to "track")
        selection.select(true, null, firstCover, 1, trackIdentified = true)
        selection.onTrack("player" to "track")
        assertSame(firstCover, selection.select(true, null, firstCover, 1, trackIdentified = true).bitmap)
    }

    @Test fun returnsToTheOriginalCoverWhenTheInterveningSessionNeverReplacedIt() {
        val music = "music" to "song"
        selection.onTrack(music)
        assertSame(firstCover, selection.select(true, null, firstCover, 1, trackIdentified = true).bitmap)
        selection.onTrack("video" to "clip")
        assertNull(selection.select(true, null, firstCover, 1, trackIdentified = true).bitmap)
        assertNull(selection.select(true, null, null, 0, trackIdentified = false).bitmap)

        selection.onTrack(music)
        val restored = selection.select(true, null, firstCover, 1, trackIdentified = true)
        assertEquals(TintArtworkOrigin.NATIVE_COVER, restored.origin)
        assertSame(firstCover, restored.bitmap)
    }

    @Test fun returningToMusicStillRejectsTheVideosCoverIfItWasActuallyShown() {
        val music = "music" to "song"
        selection.onTrack(music)
        selection.select(true, null, firstCover, 1, trackIdentified = true)
        selection.onTrack("video" to "clip")
        assertSame(secondCover, selection.select(true, null, secondCover, 1, trackIdentified = true).bitmap)

        selection.onTrack(music)
        assertNull(selection.select(true, null, secondCover, 1, trackIdentified = true).bitmap)
        assertSame(firstCover, selection.select(true, null, firstCover, 1, trackIdentified = true).bitmap)
    }

    @Test fun disabledTintStillRemembersTheCoverButNeverSelectsItWithoutTrackIdentity() {
        selection.onTrack("player" to "first")
        val disabled = selection.select(false, albumArt, firstCover, 1, trackIdentified = true)
        assertEquals(TintArtworkOrigin.NOT_REQUESTED, disabled.origin)
        assertNull(disabled.bitmap)
        selection.onTrack("player" to "second")
        assertNull(selection.select(true, null, firstCover, 1, trackIdentified = true).bitmap)
        assertNull(selection.select(true, null, secondCover, 2, trackIdentified = false).bitmap)
    }
}
