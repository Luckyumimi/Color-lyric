package shop.terramoor.github.io

import com.hchen.superlyricapi.LyricView
import com.hchen.superlyricapi.LyricListener
import com.hchen.superlyricapi.Receiver
import com.hchen.superlyricapi.model.Line
import com.hchen.superlyricapi.model.Lyric
import com.hchen.superlyricapi.model.Word
import com.hchen.superlyricapi.model.Stop
import com.hchen.superlyricapi.model.StopReason
import io.github.andrealtb.coloroslyrics.provider.core.publisher.ColorOSLyricJsonEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import shop.terramoor.github.io.lyrics.provider.ExternalLyricsTrackMatcher
import shop.terramoor.github.io.lyrics.provider.ExternalLyricsCallbacks
import shop.terramoor.github.io.lyrics.provider.ExternalLyricsPublication
import shop.terramoor.github.io.lyrics.provider.SuperLyricExternalLyricsSource
import shop.terramoor.github.io.lyrics.provider.SuperLyricPublicationMapper

class SuperLyricExternalSourceTest {
    private class FakeReceiver : Receiver {
        var callback: LyricListener? = null
        var pull: () -> LyricView? = { null }
        override fun register(listener: LyricListener): Receiver.RegisterResult {
            callback = listener
            return Receiver.RegisterResult.OK
        }
        override fun unregister(listener: LyricListener): Boolean { callback = null; return true }
        override fun unregisterAll(): Boolean { callback = null; return true }
        override fun isRegistered(listener: LyricListener) = callback === listener
        override fun latest(): LyricView? = pull()
        override fun lastStop(): Stop? = null
    }

    private fun view(
        title: String = "Song",
        artist: String = "Artist",
        publisher: String? = "example.player",
        duration: Long = 20_000,
        lines: List<Line> = listOf(
            Line("Hello world", 1_000, 3_000,
                listOf(Word("Hello ", 1_000, 1_600), Word("world", 1_600, 3_000)), "你好", null)
        ),
    ): LyricView = LyricView(Lyric("track", title, artist, "Album", duration, lines), 0, 1_000, 1f, null, publisher)

    @Test
    fun preservesWordTimingsAndTranslationInPublishedPayload() {
        val publication = SuperLyricPublicationMapper.read(view())!!
        assertEquals("example.player", publication.playerPackage)
        assertEquals(2, publication.lines.single().words!!.size)
        val payload = ColorOSLyricJsonEncoder.encode(publication.track, publication.lines, 3, publication.playerPackage)!!
        assertTrue(payload.rawLyric.contains("<00:01.000>Hello "))
        assertTrue(payload.rawLyric.contains("<00:01.600>world"))
        assertTrue(payload.translationLyric.contains("你好"))
    }

    @Test
    fun matchesOnlyCurrentPlayerAndTrack() {
        val publication = SuperLyricPublicationMapper.read(view())!!
        assertTrue(ExternalLyricsTrackMatcher.matches("example.player", "Song", "Artist", 20_000, publication))
        assertTrue(ExternalLyricsTrackMatcher.matches("example.player", "Song", null, 20_000, publication))
        assertFalse(ExternalLyricsTrackMatcher.matches("other.player", "Song", "Artist", 20_000, publication))
        assertFalse(ExternalLyricsTrackMatcher.matches("example.player", "Different song", "Artist", 20_000, publication))
        assertFalse(ExternalLyricsTrackMatcher.matches("example.player", "Song", "Other artist", 20_000, publication))
        assertFalse(ExternalLyricsTrackMatcher.matches("example.player", "Song", "Artist", 23_001, publication))
    }

    @Test
    fun rejectsUnknownPublisherAndExcessiveData() {
        assertNull(SuperLyricPublicationMapper.read(view(publisher = null)))
        assertNull(SuperLyricPublicationMapper.read(view(title = " ")))
        assertNull(SuperLyricPublicationMapper.read(view(lines = List(2_001) { Line("x", 0, 1) })))
        assertNotNull(SuperLyricPublicationMapper.read(view(lines = listOf(Line("x", 0, 1)))))
    }

    @Test
    fun derivesMissingLineEndFromTheNextLine() {
        val result = SuperLyricPublicationMapper.read(view(lines = listOf(
            Line("first", 1_000, 1_000), Line("second", 4_000, 0),
        )))!!
        assertEquals(4_000L, result.lines[0].end)
        assertEquals(20_000L, result.lines[1].end)
    }

    @Test
    fun stopClearsCurrentTrackAndOldCallbacksCannotReviveIt() {
        val receiver = FakeReceiver()
        val source = SuperLyricExternalLyricsSource(receiver)
        var publications = 0
        var stops = 0
        source.start(object : ExternalLyricsCallbacks {
            override fun onActivePlayerChanged(packageName: String?) = Unit
            override fun onPublication(publication: ExternalLyricsPublication) { publications++ }
            override fun onStopped(packageName: String?) { stops++ }
        })
        val callback = receiver.callback!!
        callback.onLyric(view())
        callback.onLyric(view())
        assertEquals(1, publications)
        callback.onStop(Stop("track", StopReason.STOPPED, "other.player"))
        callback.onLyric(view())
        assertEquals(1, publications)
        assertEquals(0, stops)
        callback.onStop(Stop("old-track", StopReason.STOPPED, "example.player"))
        assertEquals(0, stops)
        callback.onStop(Stop("track", StopReason.STOPPED, "example.player"))
        assertEquals(1, stops)
        callback.onLyric(view())
        assertEquals(2, publications)
        source.stop()
        callback.onLyric(view())
        callback.onStop(Stop("track", StopReason.STOPPED, "example.player"))
        assertEquals(2, publications)
        assertEquals(1, stops)
    }

    @Test
    fun switchingBackPullsTheAlreadyPlayingSongAndDiscardsOldCallbacks() {
        val receiver = FakeReceiver()
        val source = SuperLyricExternalLyricsSource(receiver)
        val titles = mutableListOf<String?>()
        val callbacks = object : ExternalLyricsCallbacks {
            override fun onActivePlayerChanged(packageName: String?) = Unit
            override fun onPublication(publication: ExternalLyricsPublication) { titles += publication.track.title }
            override fun onStopped(packageName: String?) = Unit
        }
        receiver.pull = { view(title = "First song") }
        source.start(callbacks)
        val previous = receiver.callback!!
        source.stop()
        receiver.pull = { view(title = "Already playing") }
        source.start(callbacks)
        previous.onLyric(view(title = "Stale callback"))
        assertEquals(listOf("First song", "Already playing"), titles)
        source.stop()
    }

    @Test
    fun latestFailureDoesNotDiscardListenerAndLateSnapshotCannotReviveStoppedTrack() {
        val receiver = FakeReceiver()
        receiver.pull = { error("temporary Binder failure") }
        val source = SuperLyricExternalLyricsSource(receiver)
        val titles = mutableListOf<String?>()
        source.start(object : ExternalLyricsCallbacks {
            override fun onActivePlayerChanged(packageName: String?) = Unit
            override fun onPublication(publication: ExternalLyricsPublication) { titles += publication.track.title }
            override fun onStopped(packageName: String?) { titles += null }
        })
        receiver.callback!!.onLyric(view())
        receiver.pull = {
            receiver.callback!!.onStop(Stop("track", StopReason.STOPPED, "example.player"))
            view()
        }
        assertTrue(source.reconnect())
        assertEquals(listOf("Song", null), titles)
        source.stop()
    }

}
