package shop.terramoor.github.io.settings

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import io.github.andrealtb.coloroslyrics.provider.universal.UniversalSnapshotStore
import io.github.andrealtb.coloroslyrics.provider.universal.UniversalSnapshotUi
import io.github.andrealtb.coloroslyrics.provider.universal.api.LyricResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

internal object BuiltinLyricsEvents {
    data class Snapshot(val fields: Map<String, String> = emptyMap(), val artwork: Bitmap? = null)
    data class CacheRow(val key: String, val title: String, val artist: String, val source: String)
    data class CacheState(
        val rows: List<CacheRow> = emptyList(),
        val total: Int = 0,
        val operation: String = "",
        val key: String = "",
        val payload: String? = null,
        val success: Boolean = false,
        val revision: Long = 0,
    )

    private val mutableSnapshot = MutableStateFlow(Snapshot())
    val snapshot = mutableSnapshot.asStateFlow()
    private val mutableCache = MutableStateFlow(CacheState())
    val cache = mutableCache.asStateFlow()

    private val covers = object : android.util.LruCache<Pair<String, String>, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: Pair<String, String>, value: Bitmap): Int = value.allocationByteCount
    }

    private fun artworkKey(title: String, artist: String) =
        title.trim().lowercase() to artist.trim().lowercase()

    private fun coverFile(context: Context, key: Pair<String, String>): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("${key.first.length}:${key.first}${key.second}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "lyric_covers"), "$hash.webp")
    }

    fun cover(context: Context, title: String, artist: String): Bitmap? {
        val key = artworkKey(title, artist)
        return covers.get(key) ?: runCatching {
            AtomicFile(coverFile(context, key)).openRead().use { BitmapFactory.decodeStream(it) }
                ?.also { covers.put(key, it) }
        }.getOrNull()
    }

    private fun saveCover(context: Context, key: Pair<String, String>, artwork: Bitmap) {
        if (covers.put(key, artwork) === artwork) return
        val file = coverFile(context, key)
        if (file.isFile) return
        val directory = file.parentFile ?: return
        directory.mkdirs()
        val target = AtomicFile(file)
        val stream = target.startWrite()
        try {
            check(artwork.compress(Bitmap.CompressFormat.WEBP_LOSSY, 78, stream))
            target.finishWrite(stream)
        } catch (failure: Exception) {
            target.failWrite(stream)
            throw failure
        }
        directory.listFiles { candidate -> candidate.extension == "webp" }
            ?.sortedByDescending(File::lastModified)?.drop(120)?.forEach { it.delete() }
    }

    fun acceptSnapshot(context: Context, value: String, artwork: Bitmap?) {
        // Publish title and cover together so a new song cannot momentarily show an old cover.
        val fields = UniversalSnapshotUi.parse(value)
        val title = fields["title"].orEmpty()
        if (artwork != null && !artwork.isRecycled && title.isNotBlank() && title != "(missing)") {
            val artist = fields["artist"].orEmpty().takeUnless { it == "(missing)" }.orEmpty()
            runCatching { saveCover(context, artworkKey(title, artist), artwork) }
        }
        mutableSnapshot.value = Snapshot(fields, artwork)
    }

    fun acceptCacheResponse(intent: Intent) {
        val operation = intent.getStringExtra(UniversalSnapshotStore.EXTRA_CACHE_OPERATION).orEmpty()
        val key = intent.getStringExtra(UniversalSnapshotStore.EXTRA_CACHE_KEY).orEmpty()
        val payload = intent.getStringExtra(UniversalSnapshotStore.EXTRA_CACHE_PAYLOAD)
        val success = intent.getBooleanExtra(UniversalSnapshotStore.EXTRA_CACHE_OK, false)
        val previous = mutableCache.value
        val offset = intent.getIntExtra(UniversalSnapshotStore.EXTRA_CACHE_OFFSET, 0)
        val rows = if (operation == "list" && success && payload != null) {
            val items = runCatching {
                val array = JSONArray(payload)
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let {
                        CacheRow(it.optString("key"), it.optString("title"), it.optString("artist"), it.optString("source"))
                    }
                }
            }.getOrDefault(emptyList())
            (if (offset == 0) items else previous.rows + items).distinctBy { it.key }
        } else previous.rows
        mutableCache.value = previous.copy(
            rows = rows,
            total = if (operation == "list") intent.getIntExtra(UniversalSnapshotStore.EXTRA_CACHE_TOTAL, rows.size) else previous.total,
            operation = operation,
            key = key,
            payload = if (operation == "get" || operation == "find") payload else null,
            success = success,
            revision = previous.revision + 1,
        )
    }

    fun requestSnapshot(context: Context) {
        context.sendBroadcast(Intent(UniversalSnapshotStore.ACTION_REQUEST_SNAPSHOT).setPackage("android"))
    }

    fun cacheRequest(context: Context, operation: String, key: String = "", payload: String? = null,
                     offset: Int = 0, title: String = "", artist: String = "") {
        context.sendBroadcast(Intent(UniversalSnapshotStore.ACTION_CACHE_REQUEST).apply {
            setPackage("android")
            putExtra(UniversalSnapshotStore.EXTRA_CACHE_OPERATION, operation)
            putExtra(UniversalSnapshotStore.EXTRA_CACHE_KEY, key)
            putExtra(UniversalSnapshotStore.EXTRA_CACHE_OFFSET, offset)
            if (payload != null) putExtra(UniversalSnapshotStore.EXTRA_CACHE_PAYLOAD, payload)
            if (title.isNotEmpty()) putExtra("title", title)
            if (artist.isNotEmpty()) putExtra("artist", artist)
        })
    }

    fun bindManual(context: Context, title: String, artist: String, result: LyricResult, durationMs: Long?) {
        val json = JSONObject().apply {
            put("songName", title)
            put("artist", artist)
            durationMs?.takeIf { it > 0L }?.let { put("durationMs", it) }
            put("lyric", result.lyric)
            put("rawLyric", result.rawLyric ?: result.lyric)
            result.translationLyric?.let { put("translationLyric", it); put("transLyric", it) }
            result.ttml?.let { put("ttml", it) }
            put("lyricFormat", result.format)
            put("ttmlConverted", result.ttmlConverted)
            put("source", result.source)
            result.creditName?.let { put("creditName", it) }
            result.creditUrl?.let { put("creditUrl", it) }
            result.sourceUrl?.let { put("sourceUrl", it) }
            put("provider", "shop.terramoor.github.io.builtinlyrics")
            put("remark", "[ColorLyricBuiltin]")
        }.toString()
        context.sendBroadcast(Intent(UniversalSnapshotStore.ACTION_INJECT_REAL_LYRIC).apply {
            setPackage("android")
            putExtra(UniversalSnapshotStore.EXTRA_OWNER_PACKAGE, snapshot.value.fields["package"].orEmpty())
            putExtra(UniversalSnapshotStore.EXTRA_GENERATION, snapshot.value.fields["generation"]?.toLongOrNull() ?: -1L)
            putExtra(UniversalSnapshotStore.EXTRA_LYRIC_INFO, json)
            putExtra("extra_is_manual", true)
            putExtra("extra_track_key", "${title.trim()}|${artist.trim()}".lowercase())
        })
    }
}
