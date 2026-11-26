package shop.terramoor.github.io

import io.github.andrealtb.coloroslyrics.provider.universal.LyricsSourceConfigStore
import io.github.andrealtb.coloroslyrics.provider.universal.UniversalSnapshotUi
import io.github.andrealtb.coloroslyrics.provider.universal.api.LyricFormats
import io.github.andrealtb.coloroslyrics.provider.universal.api.LyricsSourceAggregator
import io.github.andrealtb.coloroslyrics.provider.universal.api.LyricsSourceAggregator.SourceId
import io.github.andrealtb.coloroslyrics.provider.universal.api.TrackQuery
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommunityLyricsSourceTest {
    @Test
    fun communitySourcesAreOptInAndSurvivePreferenceReload() {
        val sources = listOf("UNISON", "LRC_RED", "KARALYR", "AMLL", "LUNABEAT")
        assertEquals(sources, LyricsSourceConfigStore.decode(LyricsSourceConfigStore.encode(sources)))
        assertEquals(emptyList<String>(), LyricsSourceConfigStore.defaults.intersect(sources.toSet()).toList())
        assertEquals("180000", UniversalSnapshotUi.parse("durationMs=180000\n")["durationMs"])
    }

    @Test
    fun unisonReturnsTimedLyricsAndAttributionThroughManualSearch() {
        val aggregator = aggregator { path ->
            when (path) {
                "/lyrics/search" -> 200 to """{"data":[{"id":7,"song":"Song","artist":"Artist","duration":180,"format":"lrc"}]}"""
                "/lyrics/7" -> 200 to """{"data":{"videoId":"abc123","format":"lrc","lyrics":"[00:02.00]Some lyrics\n[00:05.00]More lyrics","submitter":{"displayName":"Curator","keyId":"curator123"}}}"""
                else -> error(path)
            }
        }
        val row = aggregator.searchCandidates(SourceId.UNISON, "Song Artist").single()
        assertEquals(180_000L, row.durationMs)
        val lyric = checkNotNull(aggregator.fetchByCandidate(row))
        assertEquals("Curator", lyric.creditName)
        assertEquals("https://unison.betterlyrics.org/song/abc123", lyric.sourceUrl)
        assertEquals(LyricFormats.LRC, lyric.format)
    }

    @Test
    fun redFallsBackToLrcAndRejectsWrongDurationBeforeDownloading() {
        val paths = mutableListOf<String>()
        val aggregator = aggregator { path ->
            paths += path
            when (path) {
                "/search.json" -> 200 to """{"hits":[{"isrc":"USQX91300108","title":"Song","artist":"Artist","duration":180}]}"""
                "/s/USQX91300108.ttml" -> 404 to ""
                "/s/USQX91300108.lrc" -> 200 to "[00:02.00]Some lyrics\n[00:05.00]More lyrics"
                else -> error(path)
            }
        }
        val source = aggregator.source(SourceId.LRC_RED)
        assertNull(source.fetchLyrics(TrackQuery("Song", "Artist", durationMs = 120_000)))
        assertEquals(listOf("/search.json"), paths)
        assertEquals(LyricFormats.LRC, source.fetchLyrics(TrackQuery("Song", "Artist", durationMs = 180_000))?.format)
        assertEquals(listOf("/search.json", "/search.json", "/s/USQX91300108.ttml", "/s/USQX91300108.lrc"), paths)
    }

    private fun aggregator(fixture: (String) -> Pair<Int, String>): LyricsSourceAggregator = LyricsSourceAggregator(
        client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (status, body) = fixture(request.url.encodedPath)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status)
                .message("fixture").body(body.toResponseBody()).build()
        }.build(),
    )
}
