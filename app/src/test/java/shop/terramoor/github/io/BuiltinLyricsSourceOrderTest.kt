package shop.terramoor.github.io

import io.github.andrealtb.coloroslyrics.provider.universal.api.LyricResult
import io.github.andrealtb.coloroslyrics.provider.universal.api.LyricsSource
import io.github.andrealtb.coloroslyrics.provider.universal.api.LyricsSourceAggregator
import io.github.andrealtb.coloroslyrics.provider.universal.api.TrackQuery
import io.github.andrealtb.coloroslyrics.provider.universal.LyricsSourceConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import shop.terramoor.github.io.settings.LyricsProviderMode
import org.junit.Test

class BuiltinLyricsSourceOrderTest {
    @Test
    fun defaultAndStoredOrderAndDisablesAreKept() {
        assertEquals(listOf("QQ", "NETEASE", "KUGOU", "APPLE_MUSIC"), LyricsSourceConfigStore.defaults)
        assertEquals(listOf("QQ", "APPLE_MUSIC"), LyricsSourceConfigStore.decode("[\"QQ\",\"APPLE_MUSIC\"]"))
        assertEquals(emptyList<String>(), LyricsSourceConfigStore.decode("[]"))
    }
    @Test
    fun downloadedSourceCanBeStoredWithoutChangingFreshDefaults() {
        assertEquals(listOf("LRCLIB", "LYRICSPLUS", "QQ"), LyricsSourceConfigStore.decode("[\"LRCLIB\",\"LYRICSPLUS\",\"QQ\"]"))
        assertEquals(LyricsSourceConfigStore.defaults, LyricsSourceConfigStore.decode("invalid"))
    }

    private class Source(
        override val name: String,
        private val result: LyricResult?,
        private val calls: MutableList<String>,
    ) : LyricsSource {
        override fun fetchLyrics(query: TrackQuery): LyricResult? { calls += name; return result }
    }

    @Test
    fun fallbackPreservesOrderAndWordPreferenceCanUpgradeALineResult() {
        val calls = mutableListOf<String>()
        val line = LyricResult("Song", "Artist", "[00:01]First\n[00:03]Second", source = "line")
        val words = line.copy(source = "words", rawLyric =
            "[00:01.000]<00:01.000>Fi<00:01.500>rst<00:02.000>\n[00:03.000]Second")
        val aggregator = LyricsSourceAggregator(
            qqSource = Source("qq", null, calls), netEaseSource = Source("netease", line, calls),
            kugouSource = Source("kugou", words, calls), appleMusicSource = Source("apple", null, calls),
        )
        val query = TrackQuery("Song", "Artist")
        val ordered = listOf(LyricsSourceAggregator.SourceId.QQ, LyricsSourceAggregator.SourceId.NETEASE,
            LyricsSourceAggregator.SourceId.KUGOU, LyricsSourceAggregator.SourceId.APPLE_MUSIC)
        assertEquals("line", aggregator.fetchFirst(query, ordered, preferWordTiming = false).result?.source)
        assertEquals(listOf("qq", "netease"), calls)
        calls.clear()
        assertEquals("words", aggregator.fetchFirst(query, ordered, preferWordTiming = true).result?.source)
        assertEquals(listOf("qq", "netease", "kugou"), calls)
    }

    @Test
    fun latePlayerLyricsStopTheNextOnlineSource() {
        val calls = mutableListOf<String>()
        val unused = Source("unused", null, calls)
        val aggregator = LyricsSourceAggregator(qqSource = Source("qq", null, calls),
            netEaseSource = unused, kugouSource = unused, appleMusicSource = unused)
        val report = aggregator.fetchFirst(TrackQuery("Song", "Artist"),
            listOf(LyricsSourceAggregator.SourceId.QQ, LyricsSourceAggregator.SourceId.NETEASE),
            shouldContinue = { calls.isEmpty() })
        assertEquals(listOf("qq"), calls)
        assertEquals("superseded", report.reason)
        assertNull(report.result)
    }

    @Test
    fun restoresSupportedExternalModesAndMigratesDisabledLyricInfo() {
        assertEquals(LyricsProviderMode.SUPER_LYRIC, LyricsProviderMode.fromId("super_lyric"))
        assertEquals(LyricsProviderMode.LYRICON, LyricsProviderMode.fromId("lyricon"))
        assertEquals(LyricsProviderMode.BUILTIN, LyricsProviderMode.fromId("lyric_info"))
        assertEquals(false, LyricsProviderMode.LYRIC_INFO.selectable)
        assertEquals(LyricsProviderMode.BUILTIN, LyricsProviderMode.fromId(null))
    }
}
