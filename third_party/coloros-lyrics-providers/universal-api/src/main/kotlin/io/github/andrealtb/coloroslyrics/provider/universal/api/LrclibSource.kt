package io.github.andrealtb.coloroslyrics.provider.universal.api

import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal class LrclibSource(private val client: OkHttpClient) : LyricsSource {
    override val name = "lrclib"

    override fun fetchLyrics(query: TrackQuery): LyricResult? {
        val rows = search { addQueryParameter("track_name", query.title).addQueryParameter("artist_name", query.artist) }
        return rows.mapIndexedNotNull { rank, row ->
            val song = candidate(row) ?: return@mapIndexedNotNull null
            val lyric = result(row) ?: return@mapIndexedNotNull null
            val score = SongMatchScorer.calculate(query.title, query.artist, query.durationMs,
                song.title, song.artist, song.durationMs.takeIf { it > 0 }, rank)
            (score to lyric).takeIf { score >= 0.65 }
        }.maxByOrNull { it.first }?.second
    }

    override fun searchCandidates(query: String): List<SearchCandidate> =
        search { addQueryParameter("q", query) }.mapNotNull(::candidate)

    override fun fetchLyricByCandidate(candidate: SearchCandidate): LyricResult? {
        val id = candidate.id.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return get(base.newBuilder().addPathSegments("get/$id").build().toString())?.let { result(JSONObject(it)) }
    }

    private fun search(query: okhttp3.HttpUrl.Builder.() -> Unit): List<JSONObject> {
        val url = base.newBuilder().addPathSegment("search").apply(query).build()
        val array = get(url.toString())?.let(::JSONArray) ?: return emptyList()
        return (0 until minOf(array.length(), 20)).mapNotNull(array::optJSONObject)
    }

    private fun get(url: String): String? {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Color lyric (https://github.com/Luckyumimi/Color-lyric)").build()
        return client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("LRCLIB HTTP ${response.code}")
            val source = response.body.source()
            if (source.request(2_000_001L)) throw IOException("LRCLIB response too large")
            source.readUtf8()
        }
    }

    private fun candidate(row: JSONObject): SearchCandidate? {
        if (row.optBoolean("instrumental") || row.isNull("syncedLyrics") || row.optString("syncedLyrics").isBlank()) return null
        val id = row.optLong("id").takeIf { it > 0 } ?: return null
        val title = row.optString("trackName").takeIf(String::isNotBlank) ?: return null
        val duration = row.optDouble("duration").takeIf { it in 0.0..86_400.0 } ?: 0.0
        return SearchCandidate(id.toString(), title, row.optString("artistName"), row.optString("albumName"),
            "LRCLIB", durationMs = (duration * 1000).toLong(), formatHint = LyricFormats.LRC)
    }

    private fun result(row: JSONObject): LyricResult? {
        val song = candidate(row) ?: return null
        val lrc = row.getString("syncedLyrics")
        return LyricResult(song.title, song.artist, lrc, rawLyric = lrc,
            source = "universal/lrclib", sourceTrackId = song.id, format = LyricFormats.LRC)
    }

    private companion object {
        val base = "https://lrclib.net/api".toHttpUrl()
    }
}
