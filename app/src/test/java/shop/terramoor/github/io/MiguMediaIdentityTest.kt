package shop.terramoor.github.io

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.publisher.ColorOSLyricJsonEncoder
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import shop.terramoor.github.io.lyrics.LyricPayload
import shop.terramoor.github.io.lyrics.MiguMediaIdentity

class MiguMediaIdentityTest {
    private val track = TrackIdentity(title = "Song", artist = "Artist", durationMs = 120_000)
    private val payload = ColorOSLyricJsonEncoder.encode(track, listOf(
        RichLyricLine(begin = 1_000, end = 3_000, text = "First lyric"),
        RichLyricLine(begin = 3_000, end = 5_000, text = "Second lyric"),
    ), 1, "cmccwm.mobilemusic")!!.jsonValue

    @Test
    fun lyricTitlesResolveToOneStableTrackAndFeedTheExistingParser() {
        val identity = MiguMediaIdentity()
        val first = identity.resolve("cmccwm.mobilemusic", "First lyric", "Artist", 120_000, payload)
        val second = identity.resolve("cmccwm.mobilemusic", "Second lyric", "Artist", 120_000, payload)
        assertEquals("Song", first)
        assertEquals(first, second)
        assertNotNull(LyricPayload.inspect(payload, first, true, "Artist").timeline)
    }

    @Test
    fun unrelatedOwnersArtistsDurationsAndTitlesAreNeverRewritten() {
        val identity = MiguMediaIdentity()
        assertEquals("First lyric", identity.resolve("other.player", "First lyric", "Artist", 120_000, payload))
        assertEquals("First lyric", identity.resolve("cmccwm.mobilemusic", "First lyric", "Other", 120_000, payload))
        assertEquals("First lyric", identity.resolve("cmccwm.mobilemusic", "First lyric", "Artist", 125_000, payload))
        assertEquals("First lyric", identity.resolve("cmccwm.mobilemusic", "First lyric", "Artist", 0, payload))
        assertEquals("Other title", identity.resolve("cmccwm.mobilemusic", "Other title", "Artist", 120_000, payload))
    }

    @Test
    fun temporarilyMissingPayloadCanOnlyReuseTheSameStableMediaId() {
        val identity = MiguMediaIdentity()
        assertEquals("Song", identity.resolve("cmccwm.mobilemusic", "First lyric", "Artist", 120_000, payload, "id-a"))
        assertEquals("Song", identity.resolve("cmccwm.mobilemusic", "Second lyric", "Artist", 120_000, null, "id-a"))
        assertEquals("Second lyric", identity.resolve("cmccwm.mobilemusic", "Second lyric", "Artist", 120_000, null, "id-b"))
        assertEquals("Second lyric", identity.resolve("cmccwm.mobilemusic", "Second lyric", "Artist", 120_000, null, null))
    }
}
