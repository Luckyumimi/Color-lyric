package shop.terramoor.github.io

import io.github.andrealtb.coloroslyrics.provider.universal.UniversalCacheSourcePolicy
import io.github.andrealtb.coloroslyrics.provider.universal.NoLyricPolicy
import io.github.andrealtb.coloroslyrics.provider.universal.UniversalTrackPayloads
import io.github.andrealtb.coloroslyrics.provider.universal.UniversalLyricPayloadPolicy
import io.github.andrealtb.coloroslyrics.provider.universal.api.LyricLookupBudget
import io.github.andrealtb.coloroslyrics.provider.universal.session.RawSessionMetadata
import io.github.andrealtb.coloroslyrics.provider.universal.session.SessionObservation
import io.github.andrealtb.coloroslyrics.provider.universal.session.SessionRepository
import io.github.andrealtb.coloroslyrics.provider.universal.session.TrackIdentityResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import shop.terramoor.github.io.settings.floatingSelectedTabHit
import java.util.concurrent.TimeUnit

class BuiltinLyricsIsolationTest {
    @Test
    fun lateAutomaticResultCannotReplaceManualLyricsAndRefreshUsesOriginal() {
        val tracks = UniversalTrackPayloads()
        val key = "player|12"
        val full = """{"lyric":"[00:01.000]Hello","rawLyric":"[00:01.000]<00:01.000>Hello<00:02.000>","translationLyric":"[00:01.000]你好"}"""
        var visible = ""
        assertTrue(tracks.accept(key, full, isManual = true) { original ->
            visible = JSONObject(original).also {
                UniversalLyricPayloadPolicy.apply(it, wordTiming = false, rawLyric = false, translation = false)
            }.toString()
        })
        assertFalse(JSONObject(visible).has("rawLyric"))
        assertFalse(tracks.accept(key, "late network", isManual = false) { visible = it })
        assertTrue(tracks.refresh(key) { visible = it })
        assertTrue(JSONObject(visible).getString("rawLyric").contains("<00:01.000>"))
        assertTrue(JSONObject(visible).has("translationLyric"))
        tracks.clearManual(key)
        assertTrue(tracks.accept(key, "network", isManual = false) { visible = it })
        assertEquals("network", visible)
    }

    @Test
    fun generationsRemainDistinctForTwoSessionsAndAfterRecreation() {
        val resolver = TrackIdentityResolver()
        fun observation(id: String) = SessionObservation(
            userId = 0,
            ownerPackage = "example.player",
            sessionInstanceId = id,
            raw = RawSessionMetadata(mediaId = "same-song", title = "Song", artist = "Artist"),
            playbackState = 3,
            playing = true,
            observedAtElapsedMs = 10L,
        )
        val first = resolver.observe(observation("first")).descriptor.trackGeneration
        val second = resolver.observe(observation("second")).descriptor.trackGeneration
        assertNotEquals(first, second)
        assertEquals(first, resolver.observe(observation("first")).descriptor.trackGeneration)
        resolver.drop("first")
        val recreated = resolver.observe(observation("first")).descriptor.trackGeneration
        assertNotEquals(first, recreated)
        assertNotEquals(second, recreated)
        resolver.reset()
        assertNotEquals(recreated, resolver.observe(observation("first")).descriptor.trackGeneration)
    }

    @Test
    fun networkModeDoesNotReusePlayerLyricsButKeepsNetworkCache() {
        val player = """{"colorLyricCacheKind":"player","source":"player/example"}"""
        val network = """{"colorLyricCacheKind":"network","source":"universal/qq"}"""
        val legacyNetwork = """{"provider":"shop.terramoor.github.io.builtinlyrics"}"""
        assertTrue(UniversalCacheSourcePolicy.canUse(player, directMode = true))
        assertFalse(UniversalCacheSourcePolicy.canUse(player, directMode = false))
        assertTrue(UniversalCacheSourcePolicy.canUse(network, directMode = false))
        assertTrue(UniversalCacheSourcePolicy.canUse(legacyNetwork, directMode = false))
        assertTrue(UniversalCacheSourcePolicy.canUse(NoLyricPolicy.payloadJson("Song", "Artist", NoLyricPolicy.REASON_CREDITS), directMode = false))
        assertFalse(UniversalCacheSourcePolicy.canUse("not-json", directMode = false))
    }

    @Test
    fun anExhaustedSourceDoesNotConsumeTheNextSourcesAllowance() {
        val budget = LyricLookupBudget(0)
        repeat(LyricLookupBudget.MAX_CALLS_PER_SOURCE) { assertTrue(budget.acquire(1) != null) }
        assertEquals(null, budget.acquire(1))
        budget.beginSource(TimeUnit.SECONDS.toNanos(4))
        assertTrue(budget.acquire(TimeUnit.SECONDS.toNanos(4)) != null)
        budget.beginSource(TimeUnit.SECONDS.toNanos(16))
        assertEquals(null, budget.acquire(TimeUnit.SECONDS.toNanos(16)))
    }

    @Test
    fun selectedTabHitRespectsGapsAndRtl() {
        // Three 52dp items in a centered 196dp menu, total bar width 364dp.
        assertTrue(floatingSelectedTabHit(115f, 40f, 364f, 196f, 0f, 0f, 0, 3, false, 1f))
        assertFalse(floatingSelectedTabHit(152f, 40f, 364f, 196f, 0f, 0f, 0, 3, false, 1f))
        assertTrue(floatingSelectedTabHit(249f, 40f, 364f, 196f, 0f, 0f, 0, 3, true, 1f))
        assertFalse(floatingSelectedTabHit(249f, 10f, 364f, 196f, 0f, 0f, 0, 3, true, 1f))
    }

    @Test
    fun anEmptyPlayerSelectionDoesNotSelectAnUnboundSession() {
        val repository = SessionRepository()
        val observation = SessionObservation(0, "example.player", "first",
            RawSessionMetadata(title = "Song", artist = "Artist"), 3, true, 10L)
        repository.upsert(observation, setOf("example.player"))
        assertTrue(repository.snapshot().selected != null)
        repository.updateBoundFilter(emptySet(), 20L)
        assertEquals(null, repository.snapshot().selected)
        repository.upsert(observation.copy(observedAtElapsedMs = 30L), emptySet())
        assertEquals(null, repository.snapshot().selected)
        repository.updateBoundFilter(setOf("example.player"), 40L)
        assertTrue(repository.snapshot().selected != null)
    }
}
