package io.github.andrealtb.coloroslyrics.provider.universal.api

import java.io.IOException
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Word-synced and line-synced lyrics via LyricsPlus v2 (aggregates Apple Music, LyricsPlus,
 * Musixmatch word-sync, and Spotify).
 */
internal class LyricsPlusSource(private val client: OkHttpClient) : LyricsSource {
    override val name = "lyricsplus"

    override fun fetchLyrics(query: TrackQuery): LyricResult? {
        val cleanTitle = query.title.trim()
        if (cleanTitle.isEmpty()) return null
        val artist = query.artist.trim()
        val primaryArtist = primaryArtistName(artist)
        val durSec = query.durationMs?.div(1000L)?.takeIf { it > 0L }
        val attempts = buildList {
            if (artist.isNotBlank()) add(artist to durSec)
            if (primaryArtist.isNotBlank() && (primaryArtist != artist || durSec != null)) add(primaryArtist to null)
            if (artist.isBlank()) add("" to durSec)
        }.distinct()

        for ((candArtist, candDur) in attempts) {
            val urlBuilder = BASE_URL.newBuilder()
                .addQueryParameter("title", cleanTitle)
                .addQueryParameter("source", "apple,lyricsplus,musixmatch-word,spotify")
            if (candArtist.isNotBlank()) urlBuilder.addQueryParameter("artist", candArtist)
            if (candDur != null) urlBuilder.addQueryParameter("duration", candDur.toString())

            val body = get(urlBuilder.build().toString()) ?: continue
            val json = runCatching { JSONObject(body) }.getOrNull() ?: continue
            val lyricsArr = json.optJSONArray("lyrics") ?: continue
            if (lyricsArr.length() == 0) continue

            val enhanced = toEnhancedLrc(lyricsArr)
            if (enhanced.isBlank()) continue
            val hasWordTiming = json.optString("type").equals("Word", ignoreCase = true) ||
                WordTimedLyricConverter.hasRealWordTiming(enhanced)
            return LyricResult(
                title = query.title,
                artist = query.artist,
                lyric = enhanced,
                rawLyric = enhanced,
                source = "universal/lyricsplus",
                format = if (hasWordTiming) LyricFormats.ENHANCED_LRC else LyricFormats.LRC,
            )
        }
        return null
    }

    private fun get(url: String): String? {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Color lyric (https://github.com/Luckyumimi/Color-lyric)")
            .build()
        return client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("LyricsPlus HTTP ${response.code}")
            val source = response.body.source()
            if (source.request(MAX_BODY_BYTES + 1L)) throw IOException("LyricsPlus response too large")
            source.readUtf8()
        }
    }

    private fun toEnhancedLrc(lyrics: JSONArray): String {
        val out = StringBuilder()
        for (i in 0 until lyrics.length()) {
            val lineObj = lyrics.optJSONObject(i) ?: continue
            val lineStart = lineObj.optLong("time", -1L)
            if (lineStart < 0L) continue
            val lineDur = lineObj.optLong("duration", 0L).coerceAtLeast(0L)
            val lineEnd = lineStart + lineDur
            val syllabus = lineObj.optJSONArray("syllabus")
            if (syllabus != null && syllabus.length() > 0) {
                data class Syllable(val start: Long, val end: Long, val text: String)
                val items = ArrayList<Syllable>(syllabus.length())
                for (j in 0 until syllabus.length()) {
                    val s = syllabus.optJSONObject(j) ?: continue
                    if (s.optBoolean("isBackground", false)) continue
                    val wText = s.optString("text")
                    if (wText.isEmpty()) continue
                    val wStart = s.optLong("time", -1L)
                    if (wStart < 0L) continue
                    val wDur = s.optLong("duration", 0L).coerceAtLeast(0L)
                    items += Syllable(wStart, wStart + wDur, wText)
                }
                if (items.isNotEmpty()) {
                    val rowStart = minOf(lineStart, items.first().start)
                    val row = StringBuilder()
                    var lastEnd = rowStart
                    for (j in items.indices) {
                        val cur = items[j]
                        row.append(formatAngleTime(cur.start)).append(cur.text)
                        val nextStart = items.getOrNull(j + 1)?.start
                        if (cur.end > cur.start && nextStart != null && cur.end + 60L < nextStart) {
                            row.append(formatAngleTime(cur.end))
                        }
                        if (cur.end > lastEnd) lastEnd = cur.end
                    }
                    val finalEnd = maxOf(lineEnd, lastEnd)
                    out.append(formatLrcTime(rowStart)).append(row)
                    if (finalEnd > rowStart) out.append(formatAngleTime(finalEnd))
                    out.append('\n')
                    continue
                }
            }
            val plain = lineObj.optString("text").trim()
            if (plain.isNotEmpty()) {
                out.append(formatLrcTime(lineStart)).append(plain).append('\n')
            }
        }
        return out.toString()
    }

    private fun primaryArtistName(artist: String): String =
        artist.split(ARTIST_SEPARATOR).firstOrNull()?.trim().orEmpty()

    private fun formatLrcTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val min = safe / 60_000L
        val sec = (safe % 60_000L) / 1000L
        val cs = (safe % 1000L) / 10L
        return String.format(Locale.US, "[%02d:%02d.%02d]", min, sec, cs)
    }

    private fun formatAngleTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val min = safe / 60_000L
        val sec = (safe % 60_000L) / 1000L
        val cs = (safe % 1000L) / 10L
        return String.format(Locale.US, "<%02d:%02d.%02d>", min, sec, cs)
    }

    private companion object {
        val BASE_URL = "https://lyricsplus.prjktla.my.id/v2/lyrics/get".toHttpUrl()
        const val MAX_BODY_BYTES = 2_000_000L
        val ARTIST_SEPARATOR = Regex(
            """\s*(?:/|&|,|、|;|\bfeat\.?|\bft\.?|\bwith\b|\bx\b)\s*""",
            RegexOption.IGNORE_CASE,
        )
    }
}
