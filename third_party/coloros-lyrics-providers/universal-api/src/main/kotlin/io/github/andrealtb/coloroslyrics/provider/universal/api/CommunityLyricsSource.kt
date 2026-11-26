package io.github.andrealtb.coloroslyrics.provider.universal.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.text.Normalizer
import java.util.concurrent.TimeUnit

internal class CommunityLyricsSource(
    private val client: OkHttpClient,
    private val id: LyricsSourceAggregator.SourceId,
) : LyricsSource {
    override val name = id.label
    override val deliversTtml = id != LyricsSourceAggregator.SourceId.KARALYR
    private val source = "universal/${id.name.lowercase()}"
    private val base = when (id) {
        LyricsSourceAggregator.SourceId.UNISON -> "https://unison.betterlyrics.org/"
        LyricsSourceAggregator.SourceId.LRC_RED -> "https://lrc.red/"
        LyricsSourceAggregator.SourceId.KARALYR -> "https://www.karalyr.com/"
        LyricsSourceAggregator.SourceId.AMLL -> "https://amlldb.bikonoo.com/"
        LyricsSourceAggregator.SourceId.LUNABEAT -> "https://2755337087.github.io/ttml-hub/"
        else -> error("Unsupported community source")
    }.toHttpUrl()

    override fun fetchLyrics(query: TrackQuery): LyricResult? {
        if (query.title.isBlank() || query.artist.isBlank()) return null
        val term = listOf(query.title, query.artist).joinToString(" ")
        val rows = search(if (id == LyricsSourceAggregator.SourceId.AMLL) query.title else term)
        val candidates = rows.mapNotNull { row ->
            if (row.artist.isBlank()) return@mapNotNull null
            if (query.durationMs != null && row.durationMs > 0 &&
                kotlin.math.abs(query.durationMs - row.durationMs) > 6_000) return@mapNotNull null
            val score = SongMatchScorer.calculate(query.title, query.artist, query.durationMs?.takeIf { row.durationMs > 0 },
                row.title, row.artist, row.durationMs.takeIf { it > 0 })
            if (score < 0.82) return@mapNotNull null
            val sameAlbum = !query.album.isNullOrBlank() && normalized(query.album) == normalized(row.album)
            row to (score + if (sameAlbum) 0.04 else 0.0)
        }.sortedByDescending { it.second }
        val best = candidates.firstOrNull() ?: return null
        val runnerUp = candidates.getOrNull(1)
        if (runnerUp != null && best.second - runnerUp.second < 0.015 &&
            (best.first.durationMs != runnerUp.first.durationMs ||
                normalized(best.first.album) != normalized(runnerUp.first.album))) return null
        return fetchLyricByCandidate(best.first)
    }

    override fun searchCandidates(query: String): List<SearchCandidate> = search(query.trim())

    private fun search(query: String): List<SearchCandidate> {
        if (query.isBlank()) return emptyList()
        val rows = when (id) {
            LyricsSourceAggregator.SourceId.UNISON -> get("lyrics/search", query)?.let { JSONObject(it).optJSONArray("data") }
            LyricsSourceAggregator.SourceId.LRC_RED -> get("search.json", query.filterNot { it in "'’‘ʼ`´" })
                ?.let { JSONObject(it).optJSONArray("hits") }
            LyricsSourceAggregator.SourceId.KARALYR -> get("api/search", query)?.let(::JSONArray)
            LyricsSourceAggregator.SourceId.AMLL -> {
                val url = checkNotNull(base.resolve("api/search-lyrics"))
                val json = JSONObject().put("query", query).put("type", "all")
                val request = Request.Builder().url(url).post(json.toString().toRequestBody(JSON)).build()
                read(request)?.let(::JSONArray)
            }
            LyricsSourceAggregator.SourceId.LUNABEAT -> return lunaSearch(query)
            else -> error("Unsupported community source")
        } ?: return emptyList()
        return (0 until minOf(rows.length(), 50)).mapNotNull { rows.optJSONObject(it)?.let(::candidate) }
    }

    private fun candidate(row: JSONObject): SearchCandidate? {
        val (key, title, artist) = when (id) {
            LyricsSourceAggregator.SourceId.UNISON -> Triple(row.optString("id"), row.optString("song"), row.optString("artist"))
            LyricsSourceAggregator.SourceId.LRC_RED -> Triple(row.optString("isrc"), row.optString("title"), row.optString("artist"))
            LyricsSourceAggregator.SourceId.KARALYR -> {
                if (row.optJSONObject("karalyr")?.optBoolean("has_lyrics") == false) return null
                Triple(row.optString("id"), row.optString("trackName"), row.optString("artistName"))
            }
            LyricsSourceAggregator.SourceId.AMLL -> Triple(row.optString("file"), row.optString("title"), row.optString("artist"))
            else -> return null
        }
        if (!validKey(key) || title.isBlank()) return null
        val duration = row.optDouble("duration").takeIf { it in 0.0..86_400.0 } ?: 0.0
        val format = when (id) {
            LyricsSourceAggregator.SourceId.UNISON -> row.optString("format").uppercase()
            LyricsSourceAggregator.SourceId.AMLL -> LyricFormats.TTML
            else -> ""
        }
        val album = row.optJSONArray("album")?.optString(0) ?: row.optString("albumName", row.optString("album"))
        return SearchCandidate(key, title, artist, album, name, coverUrl = row.optString("cover"),
            durationMs = (duration * 1000).toLong(), formatHint = format)
    }

    override fun fetchLyricByCandidate(candidate: SearchCandidate): LyricResult? {
        val key = candidate.id
        if (!validKey(key) || candidate.source != name) return null
        return when (id) {
            LyricsSourceAggregator.SourceId.UNISON -> {
                val data = get("lyrics/$key")?.let { JSONObject(it).optJSONObject("data") } ?: return null
                (if (data.optString("format") == "ttml") ttml(candidate, data.optString("lyrics"))
                else lrc(candidate, data.optString("lyrics")))?.copy(
                    creditName = data.optJSONObject("submitter")?.optString("displayName")?.take(120),
                    creditUrl = data.optJSONObject("submitter")?.optString("keyId")?.takeIf { it.matches(KEY) }
                        ?.let { "https://unison.betterlyrics.org/curator/$it" },
                    sourceUrl = data.optString("videoId").takeIf { it.matches(KEY) }
                        ?.let { "https://unison.betterlyrics.org/song/$it" },
                )
            }
            LyricsSourceAggregator.SourceId.LRC_RED -> ttml(candidate, get("s/$key.ttml")) ?: lrc(candidate, get("s/$key.lrc"))
            LyricsSourceAggregator.SourceId.KARALYR -> {
                val data = get("api/get/$key")?.let(::JSONObject) ?: return null
                lrc(candidate, data.optString("syncedLyrics"))
            }
            LyricsSourceAggregator.SourceId.AMLL -> ttml(candidate, get("raw-lyrics/$key"))
            LyricsSourceAggregator.SourceId.LUNABEAT -> {
                val song = lunaCatalog().firstOrNull { it.candidate.id == key } ?: return null
                val document = get(song.path) ?: return null
                val hash = MessageDigest.getInstance("SHA-256").digest(document.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                if (hash != song.hash) throw IOException("TTML Hub lyric checksum mismatch")
                ttml(candidate, document)
            }
            else -> null
        }
    }

    private fun ttml(row: SearchCandidate, text: String?): LyricResult? =
        TtmlLyrics.toResult(text, TrackQuery(row.title, row.artist), source, row.id)

    private fun lrc(row: SearchCandidate, text: String?): LyricResult? {
        val lines = StructuredLyrics.parseLrc(text)
        if (lines.isEmpty()) return null
        val wordTimed = WordTimedLyricConverter.hasRealWordTiming(text.orEmpty())
        return LyricResult(row.title, row.artist, StructuredLyrics.toPlainLrc(lines), rawLyric = text,
            source = source, sourceTrackId = row.id, format = if (wordTimed) LyricFormats.ENHANCED_LRC else LyricFormats.LRC)
    }

    private fun validKey(key: String): Boolean = key.matches(KEY) && when (id) {
        LyricsSourceAggregator.SourceId.UNISON -> key.toLongOrNull()?.let { it > 0 } == true
        LyricsSourceAggregator.SourceId.LRC_RED -> key.matches(ISRC)
        LyricsSourceAggregator.SourceId.AMLL -> key.endsWith(".ttml")
        else -> true
    }

    private data class LunaSong(val candidate: SearchCandidate, val path: String, val hash: String, val keywords: String)
    @Volatile private var lunaCache: Pair<Long, List<LunaSong>>? = null

    private fun lunaSearch(query: String): List<SearchCandidate> {
        val tokens = query.split(Regex("\\s+")).map(::normalized).filter(String::isNotBlank)
        if (tokens.isEmpty()) return emptyList()
        return lunaCatalog().asSequence().filter { song -> tokens.all { it in song.keywords } }
            .take(50).map { it.candidate }.toList()
    }

    @Synchronized private fun lunaCatalog(): List<LunaSong> {
        val previous = lunaCache
        val now = System.nanoTime()
        if (previous != null && now < previous.first) return previous.second
        val songs = try {
            val json = JSONObject(get("api/v1/songs.json") ?: throw IOException("TTML Hub index missing"))
            if (json.optInt("schemaVersion") !in 1..2) throw IOException("Unsupported TTML Hub index")
            val rows = json.getJSONArray("songs")
            (0 until rows.length()).mapNotNull { i ->
                val row = rows.optJSONObject(i) ?: return@mapNotNull null
                val key = row.optString("id")
                val path = row.optString("path")
                val hash = row.optString("sha256")
                val title = row.optString("title")
                if (!validKey(key) || title.isBlank() || !path.matches(LUNA_PATH) || !hash.matches(SHA256)) return@mapNotNull null
                val artist = row.strings("artists").joinToString(", ")
                val album = row.optString("album")
                val candidate = SearchCandidate(key, title, artist, album, name,
                    durationMs = row.optLong("durationMs").coerceAtLeast(0), formatHint = LyricFormats.TTML)
                val keywords = (listOf(title, artist, album) + row.strings("aliases") + row.strings("albums"))
                    .joinToString("|") { normalized(it) }
                LunaSong(candidate, path, hash, keywords)
            }
        } catch (failure: Exception) {
            if (previous == null) throw failure
            lunaCache = now + TimeUnit.MINUTES.toNanos(5) to previous.second
            return previous.second
        }
        lunaCache = now + TimeUnit.HOURS.toNanos(6) to songs
        return songs
    }

    private fun JSONObject.strings(key: String): List<String> = optJSONArray(key)?.let { values ->
        (0 until values.length()).map { values.optString(it) }.filter(String::isNotBlank)
    }.orEmpty()

    private fun normalized(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase().filter { it.isLetterOrDigit() }

    private fun get(path: String, query: String? = null): String? {
        val url = checkNotNull(base.resolve(path)).newBuilder().apply { if (query != null) addQueryParameter("q", query) }.build()
        return read(Request.Builder().url(url).build())
    }

    private fun read(request: Request): String? = client.newCall(request.newBuilder()
        .header("User-Agent", "Color lyric (https://github.com/Luckyumimi/Color-lyric)").build()).execute().use { response ->
        if (response.code == 404) return null
        if (!response.isSuccessful) throw IOException("$name HTTP ${response.code}")
        val body = response.body.source()
        if (body.request(4_000_001)) throw IOException("$name response too large")
        body.readUtf8()
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val KEY = Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,127}")
        val ISRC = Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")
        val LUNA_PATH = Regex("lyrics/[a-f0-9]{2}/[a-f0-9]{16}\\.ttml")
        val SHA256 = Regex("[a-f0-9]{64}")
    }
}
