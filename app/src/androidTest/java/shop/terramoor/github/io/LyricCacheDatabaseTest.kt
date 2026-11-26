package shop.terramoor.github.io

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.andrealtb.coloroslyrics.provider.universal.cache.LyricCacheDatabase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LyricCacheDatabaseTest {
    @Test
    fun migratesOldLyricsWithoutExpiryAndDoesNotRestoreDeletedRows() = withDatabase { db, legacy ->
        val now = TimeUnit.DAYS.toMillis(30)
        val original = lyric("Song").put("colorLyricCachedAt", 1L)
        legacy.writeText(JSONObject().apply {
            put("v9|song|artist", JSONObject().put("noLyric", true).put("colorLyricCachedAt", now).toString())
            put("v7|song|artist", original.toString())
            put("v9|other|artist", lyric("Other").toString())
        }.toString())

        LyricCacheDatabase(db, legacy) { now }.use { cache ->
            assertEquals(2, cache.page(0).total)
            assertEquals(listOf("Song"), cache.page(1).rows.map { it.title })
            val entry = checkNotNull(cache.find(" song ", "ARTIST"))
            assertEquals(original.optString("lyric"), JSONObject(entry.second).optString("lyric"))
            assertTrue(cache.updateByKey(entry.first, lyric("Song", "edited").toString()))
            assertTrue(cache.removeByKey(cache.page(0).rows.first().key))
        }
        LyricCacheDatabase(db, legacy) { now + TimeUnit.DAYS.toMillis(365) }.use { cache ->
            assertNull(cache.get("Other", "Artist"))
            cache.put("Song", "Artist", JSONObject().put("noLyric", true).toString())
            assertEquals("[00:01.000]edited", JSONObject(checkNotNull(cache.get("Song", "Artist"))).optString("lyric"))
            assertEquals(1, cache.clear().songs)
        }
        LyricCacheDatabase(db, legacy).use { cache -> assertEquals(0, cache.page(0).total) }
    }

    @Test
    fun negativeCacheStopsSuppressingSearchAfterTenMinutes() = withDatabase { db, legacy ->
        var now = 1L
        LyricCacheDatabase(db, legacy) { now }.use { cache ->
            cache.put("Missing", "Artist", """{"noLyric":true}""")
            assertNotNull(cache.get("Missing", "Artist"))
            assertNull(cache.find("Missing", "Artist"))
            assertEquals(0, cache.page(0).total)
            now += TimeUnit.MINUTES.toMillis(10)
            assertNull(cache.get("Missing", "Artist"))
        }
    }

    private fun lyric(title: String, line: String = "original"): JSONObject = JSONObject()
        .put("songName", title).put("artist", "Artist").put("lyric", "[00:01.000]$line")

    private fun withDatabase(test: (File, File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "lyric-cache").toFile()
        try {
            test(File(directory, "lyrics.db"), File(directory, "legacy.json"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
