package shop.terramoor.github.io

import io.github.andrealtb.coloroslyrics.provider.universal.UniversalLyricHistory
import io.github.andrealtb.coloroslyrics.provider.universal.api.LyricsSourceAggregator
import io.github.andrealtb.coloroslyrics.provider.universal.api.SearchCandidate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import shop.terramoor.github.io.settings.manualSearchSources
import shop.terramoor.github.io.settings.moveLyricSource
import shop.terramoor.github.io.settings.reorderEnabledLyricSources
import shop.terramoor.github.io.settings.collectManualCandidates

class LyricsPagePolicyTest {
    @Test
    fun searchRunsAllSourcesConcurrentlyAndKeepsResultsWhenOneFails() = runBlocking {
        val order = LyricsSourceAggregator.SourceId.entries.toList()
        val started = mutableSetOf<LyricsSourceAggregator.SourceId>()
        val gate = CompletableDeferred<Unit>()
        var final = emptyList<SearchCandidate>()
        withTimeout(2_000) {
            collectManualCandidates(order, search = { source ->
                started += source
                if (started.size == order.size) gate.complete(Unit)
                gate.await()
                if (source == LyricsSourceAggregator.SourceId.NETEASE) error("source unavailable")
                listOf(SearchCandidate(source.name, "Song", "Artist", "Album", source.label))
            }) { final = it }
        }
        assertEquals(order.toSet(), started)
        assertEquals(order.filterNot { it == LyricsSourceAggregator.SourceId.NETEASE }.map { it.label }, final.map { it.source })
    }
    @Test
    fun manualSearchUsesOnlyEnabledSourcesInPriorityOrder() {
        assertEquals(emptyList<LyricsSourceAggregator.SourceId>(), manualSearchSources(emptyList()))
        assertEquals(listOf(LyricsSourceAggregator.SourceId.APPLE_MUSIC, LyricsSourceAggregator.SourceId.QQ),
            manualSearchSources(listOf("APPLE_MUSIC", "INVALID", "APPLE_MUSIC", "QQ")))
        assertEquals(LyricsSourceAggregator.SourceId.entries.toList(),
            manualSearchSources(LyricsSourceAggregator.SourceId.entries.map { it.name }))
    }

    @Test
    fun manualSearchInterleavesSourcesSoOneCannotHideTheOthers() = runBlocking {
        val order = listOf(LyricsSourceAggregator.SourceId.QQ, LyricsSourceAggregator.SourceId.NETEASE)
        var result = emptyList<SearchCandidate>()
        collectManualCandidates(order, search = { source ->
            (0..2).map { SearchCandidate("${source.name}$it", "Song", "Artist", "Album", source.label) }
        }) { result = it }
        assertEquals(listOf("QQ0", "NETEASE0", "QQ1", "NETEASE1", "QQ2", "NETEASE2"), result.map { it.id })
    }

    @Test
    fun reorderMovesTheEnabledSourceWithoutLosingOrAddingSources() {
        val original = listOf("KUGOU", "QQ", "NETEASE", "APPLE_MUSIC")
        assertEquals(listOf("QQ", "NETEASE", "KUGOU", "APPLE_MUSIC"), moveLyricSource(original, "KUGOU", 2))
        assertEquals(listOf("APPLE_MUSIC", "KUGOU", "QQ", "NETEASE"), moveLyricSource(original, "APPLE_MUSIC", 0))
        assertEquals(original, moveLyricSource(original, "MISSING", 1))
        assertEquals(original, moveLyricSource(original, "QQ", -1))
        assertEquals(original, moveLyricSource(original, "QQ", 9))
    }

    @Test
    fun disabledSourceStaysInItsSlotWhenEnabledSourcesAreReordered() {
        val rows = listOf("QQ", "NETEASE", "KUGOU", "APPLE_MUSIC")
        val enabled = listOf("QQ", "KUGOU", "APPLE_MUSIC")
        assertEquals(rows, reorderEnabledLyricSources(rows, enabled))
        assertEquals(listOf("KUGOU", "NETEASE", "QQ", "APPLE_MUSIC"),
            reorderEnabledLyricSources(rows, moveLyricSource(enabled, "KUGOU", 0)))
    }

    @Test
    fun recentRowsKeepDifferentArtistsWithTheSameTitleAndMergeIncompleteIdentity() {
        val a = UniversalLyricHistory.row("Song", "Artist A", "qq", "ready")!!
        val b = UniversalLyricHistory.row("Song", "Artist B", "netease", "ready")!!
        val unknown = UniversalLyricHistory.row("Other", "", "qq", "fetching")!!
        val named = UniversalLyricHistory.row("Other", "Artist C", "qq", "ready")!!
        var history = UniversalLyricHistory.moveToTop("", a)
        history = UniversalLyricHistory.moveToTop(history, b)
        assertEquals(listOf("Artist B", "Artist A"), UniversalLyricHistory.parse(history).map { it.artist })
        history = UniversalLyricHistory.moveToTop(history, unknown)
        history = UniversalLyricHistory.moveToTop(history, named)
        assertEquals(listOf("Artist C", "Artist B", "Artist A"), UniversalLyricHistory.parse(history).map { it.artist })
        history = UniversalLyricHistory.replaceInPlace(history,
            UniversalLyricHistory.row("Song", "Artist A", "kugou", "ready")!!)
        assertEquals(listOf("Artist C", "Artist B", "Artist A"), UniversalLyricHistory.parse(history).map { it.artist })
        assertEquals("kugou", UniversalLyricHistory.parse(history).last().source)
    }
}
