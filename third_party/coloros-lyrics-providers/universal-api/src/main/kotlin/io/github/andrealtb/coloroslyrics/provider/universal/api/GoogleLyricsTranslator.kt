package io.github.andrealtb.coloroslyrics.provider.universal.api

import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/**
 * Translates line-timed LRC lyrics via Google Translate's public endpoint while preserving
 * original line timestamps. Opt-in only (disabled by default).
 */
internal object GoogleLyricsTranslator {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private val LINE_REGEX = Regex("""^((?:\[\d{1,4}:\d{2}(?:[.:]\d{1,3})?])+)(.*)$""")
    private val BASE_URL = "https://translate.googleapis.com/translate_a/single".toHttpUrl()
    private const val CHUNK_SIZE = 30
    private const val MAX_BODY_BYTES = 1_000_000L

    fun resolveTargetLang(preference: String?): String {
        val raw = preference?.trim().orEmpty()
        if (raw.isNotEmpty() && !raw.equals("auto", ignoreCase = true)) return raw
        val locale = Locale.getDefault()
        val lang = locale.language.lowercase(Locale.ROOT)
        if (lang == "in") return "id"
        if (lang == "zh") {
            val script = locale.script
            val country = locale.country.uppercase(Locale.ROOT)
            return if (script.equals("Hant", ignoreCase = true) || country in setOf("TW", "HK", "MO")) {
                "zh-TW"
            } else {
                "zh-CN"
            }
        }
        return lang.ifEmpty { "en" }
    }

    fun translateLrc(lrc: String, targetLangPreference: String?): String? {
        if (lrc.isBlank()) return null
        val targetLang = resolveTargetLang(targetLangPreference)
        data class Entry(val tags: String, val text: String)

        val entries = ArrayList<Entry>()
        for (rawLine in lrc.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val match = LINE_REGEX.matchEntire(line) ?: continue
            val tags = match.groupValues[1]
            val text = match.groupValues[2].trim()
            if (text.isNotEmpty()) entries += Entry(tags, text)
        }
        if (entries.isEmpty()) return null

        val out = StringBuilder()
        var changedCount = 0
        var offset = 0
        while (offset < entries.size) {
            val end = minOf(offset + CHUNK_SIZE, entries.size)
            val batch = entries.subList(offset, end)
            val translated = translateBatch(batch.map { it.text }, targetLang) ?: return null
            if (translated.size != batch.size) return null
            for (i in batch.indices) {
                val orig = batch[i].text
                val tr = translated[i].trim()
                if (tr.isNotEmpty() && !tr.equals(orig, ignoreCase = true)) {
                    out.append(batch[i].tags).append(tr).append('\n')
                    changedCount++
                }
            }
            offset = end
        }
        return if (changedCount > 0) out.toString() else null
    }

    private fun translateBatch(lines: List<String>, targetLang: String): List<String>? {
        if (lines.isEmpty()) return emptyList()
        val joined = lines.joinToString("\n")
        val url = BASE_URL.newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", "auto")
            .addQueryParameter("tl", targetLang)
            .addQueryParameter("dt", "t")
            .build()
        val body = FormBody.Builder()
            .add("q", joined)
            .build()
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("User-Agent", "Mozilla/5.0")
            .build()
        val responseText = runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val source = response.body.source()
                if (source.request(MAX_BODY_BYTES + 1L)) return@use null
                source.readUtf8()
            }
        }.getOrNull() ?: return null

        val combined = runCatching {
            val root = JSONArray(responseText)
            val segments = root.optJSONArray(0) ?: return@runCatching null
            val sb = StringBuilder()
            for (i in 0 until segments.length()) {
                val seg = segments.optJSONArray(i) ?: continue
                val piece = seg.optString(0)
                if (piece.isNotEmpty()) sb.append(piece)
            }
            sb.toString()
        }.getOrNull() ?: return null

        val split = combined.split('\n')
        return if (split.size == lines.size) split else null
    }
}
