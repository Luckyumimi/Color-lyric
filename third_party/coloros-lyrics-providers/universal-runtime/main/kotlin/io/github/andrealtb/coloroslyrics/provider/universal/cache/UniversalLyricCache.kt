package io.github.andrealtb.coloroslyrics.provider.universal.cache

import android.content.ContentValues
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

internal object UniversalLyricCache : LyricCacheDatabase(
    File("/data/system/colorlyric_universal_lyric_cache.db"),
    File("/data/system/colorlyric_universal_lyric_cache.json"),
)

internal open class LyricCacheDatabase(
    private val file: File,
    private val legacyFile: File,
    private val now: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    data class Cleared(val songs: Int)
    data class Entry(val key: String, val title: String, val artist: String, val source: String)
    data class Page(val rows: List<Entry>, val total: Int)

    private var connection: SQLiteDatabase? = null

    @Synchronized
    fun get(title: String, artist: String): String? = runCatching {
        read(trackKey(title, artist), positiveOnly = false)
    }.getOrNull()

    @Synchronized
    fun find(title: String, artist: String): Pair<String, String>? {
        val key = trackKey(title, artist)
        return read(key, positiveOnly = true)?.let { key to it }
    }

    @Synchronized
    fun getByKey(key: String): String? = read(key, positiveOnly = true)

    @Synchronized
    fun put(title: String, artist: String, raw: String) {
        val payload = decode(raw) ?: return
        val timestamp = now()
        val values = values(title, artist, payload, timestamp) ?: return
        runCatching {
            val database = database()
            if (values.getAsLong("expires_at") == null || read(values.getAsString("cache_key"), true) == null) {
                database.replaceOrThrow(TABLE, null, values)
            }
        }.onFailure { Log.w(TAG, "Couldn't cache lyrics", it) }
    }

    @Synchronized
    fun updateByKey(key: String, raw: String): Boolean {
        if (raw.length > 262_144) return false
        val payload = decode(raw) ?: return false
        val original = getByKey(key)?.let(::JSONObject) ?: return false
        val title = original.optString("songName")
        val artist = original.optString("artist")
        val values = values(title, artist, payload, now()) ?: return false
        if (values.getAsLong("expires_at") != null) return false
        values.remove("cache_key")
        return database().update(TABLE, values, "cache_key = ?", arrayOf(key)) > 0
    }

    @Synchronized
    fun removeByKey(key: String): Boolean = database().delete(TABLE, "cache_key = ?", arrayOf(key)) > 0

    @Synchronized
    fun clear(): Cleared = transaction(database()) { database ->
        val songs = countSongs(database)
        database.delete(TABLE, null, null)
        Cleared(songs)
    }

    @Synchronized
    fun getCacheCount(): Int = runCatching { countSongs(database()) }.getOrDefault(0)

    @Synchronized
    fun page(offset: Int): Page {
        val database = database()
        val rows = mutableListOf<Entry>()
        database.rawQuery(
            "SELECT cache_key, title, artist, source FROM $TABLE WHERE expires_at IS NULL " +
                "ORDER BY title COLLATE NOCASE, cache_key LIMIT 100 OFFSET ?",
            arrayOf(offset.coerceAtLeast(0).toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += Entry(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3))
            }
        }
        return Page(rows, countSongs(database))
    }

    @Synchronized
    fun <T> withArtistPayloads(artist: String, block: (Sequence<String>) -> T): T {
        return database().rawQuery(
            "SELECT payload FROM $TABLE WHERE artist_key = ? AND expires_at IS NULL",
            arrayOf(normalize(artist)),
        ).use { cursor -> block(generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }) }
    }

    @Synchronized
    override fun close() {
        connection?.close()
        connection = null
    }

    private fun read(key: String, positiveOnly: Boolean): String? {
        val eligibility = if (positiveOnly) "expires_at IS NULL" else "(expires_at IS NULL OR expires_at > ?)"
        val args = if (positiveOnly) arrayOf(key) else arrayOf(key, now().toString())
        return database().rawQuery("SELECT payload FROM $TABLE WHERE cache_key = ? AND $eligibility", args)
            .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }

    private fun countSongs(database: SQLiteDatabase): Int =
        DatabaseUtils.longForQuery(database, "SELECT COUNT(*) FROM $TABLE WHERE expires_at IS NULL", null).toInt()

    private fun database(): SQLiteDatabase {
        connection?.let { return it }
        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            if (database.version == 0) transaction(database) {
                if (it.version == 0) {
                    it.execSQL("""
                        CREATE TABLE $TABLE (
                            cache_key TEXT PRIMARY KEY NOT NULL,
                            title TEXT NOT NULL, artist TEXT NOT NULL, artist_key TEXT NOT NULL,
                            source TEXT NOT NULL, payload TEXT NOT NULL,
                            saved_at INTEGER NOT NULL, expires_at INTEGER
                        )
                    """.trimIndent())
                    it.execSQL("CREATE INDEX lyrics_by_title ON $TABLE(title COLLATE NOCASE, cache_key) WHERE expires_at IS NULL")
                    it.execSQL("CREATE INDEX lyrics_by_artist ON $TABLE(artist_key) WHERE expires_at IS NULL")
                    migrateLegacy(it)
                    it.version = 1
                }
            }
            check(database.version == 1) { "Unsupported lyric cache schema ${database.version}" }
            connection = database
            return database
        } catch (error: Exception) {
            database.close()
            throw error
        }
    }

    private fun migrateLegacy(database: SQLiteDatabase) {
        val root = try {
            JSONObject(AtomicFile(legacyFile).readFully().toString(Charsets.UTF_8))
        } catch (error: FileNotFoundException) {
            if (legacyFile.exists() || File(legacyFile.path + ".bak").exists()) throw error
            return
        } catch (error: JSONException) {
            Log.w(TAG, "Keeping unreadable legacy lyric cache on disk", error)
            return
        }
        val timestamp = now()
        for (legacyKey in root.keys()) {
            val payload = decode(root.optString(legacyKey)) ?: continue
            val unversioned = legacyKey.replaceFirst(LEGACY_VERSION, "")
            val title = payload.optString("songName").ifBlank { unversioned.substringBefore('|', "") }
            val artist = if (payload.has("artist")) payload.optString("artist") else unversioned.substringAfter('|', "")
            val saved = payload.optLong("colorLyricCachedAt").coerceAtMost(timestamp)
            val values = values(title, artist, payload, saved) ?: continue
            val expires = values.getAsLong("expires_at")
            if (expires != null && (saved <= 0L || expires <= timestamp)) continue
            val key = values.getAsString("cache_key")
            val shouldInsert = database.rawQuery(
                "SELECT saved_at, expires_at FROM $TABLE WHERE cache_key = ?", arrayOf(key),
            ).use { cursor ->
                if (!cursor.moveToFirst()) true else {
                    val existingPositive = cursor.isNull(1)
                    val positive = expires == null
                    when {
                        positive != existingPositive -> positive
                        saved != cursor.getLong(0) -> saved > cursor.getLong(0)
                        else -> legacyKey.startsWith("v9|")
                    }
                }
            }
            if (shouldInsert) database.replaceOrThrow(TABLE, null, values)
        }
    }

    private fun values(title: String, artist: String, payload: JSONObject, saved: Long): ContentValues? {
        if (title.isBlank()) return null
        val miss = payload.optBoolean("noLyric")
        if (!miss && listOf("lyric", "rawLyric", "ttml").none { !payload.isNull(it) && payload.optString(it).isNotBlank() }) {
            return null
        }
        if (payload.optString("songName").isBlank()) payload.put("songName", title)
        if (!payload.has("artist")) payload.put("artist", artist)
        payload.put("colorLyricCachedAt", saved)
        return ContentValues().apply {
            put("cache_key", trackKey(title, artist))
            put("title", title)
            put("artist", artist)
            put("artist_key", normalize(artist))
            put("source", payload.optString("source"))
            put("payload", payload.toString())
            put("saved_at", saved)
            if (miss) put("expires_at", saved + MISS_TTL) else putNull("expires_at")
        }
    }

    private fun decode(raw: String): JSONObject? = try {
        JSONObject(raw)
    } catch (_: JSONException) {
        null
    }

    private fun <T> transaction(database: SQLiteDatabase, block: (SQLiteDatabase) -> T): T {
        database.beginTransaction()
        try {
            val result = block(database)
            database.setTransactionSuccessful()
            return result
        } finally {
            database.endTransaction()
        }
    }

    private fun trackKey(title: String, artist: String): String {
        val normalized = normalize(title)
        return "${normalized.length}:$normalized${normalize(artist)}"
    }

    private fun normalize(value: String): String = value.trim().lowercase()

    private companion object {
        const val TABLE = "lyrics"
        const val TAG = "ColorLyricCache"
        const val MISS_TTL = 10 * 60_000L
        val LEGACY_VERSION = Regex("^v\\d+\\|")
    }
}
