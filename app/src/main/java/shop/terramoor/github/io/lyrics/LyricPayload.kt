package shop.terramoor.github.io.lyrics

import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

internal object LyricPayload {
    const val KEY = "lyricInfo"
    private const val MAX_CHARS = 262_144
    private val translationKeys = listOf(
        "translationLyric", "translatedLyric", "translateLyric", "transLyric",
        "lyricTranslation", "translationLrc", "transLrc", "translation",
    )

    enum class Outcome { EMPTY, TOO_LARGE, INVALID_JSON, PROVIDER_NO_LYRIC, TRACK_MISMATCH, NO_TIMED_LINES, PARSED }

    data class Result(val outcome: Outcome, val timeline: Timeline? = null,
                      val hasLrc: Boolean = false, val hasTtml: Boolean = false)

    fun parse(payload: String?, title: String?, titleChanged: Boolean, artist: String? = null): Timeline? =
        inspect(payload, title, titleChanged, artist).timeline

    fun inspect(payload: String?, title: String?, titleChanged: Boolean, artist: String? = null): Result {
        if (payload.isNullOrBlank()) return Result(Outcome.EMPTY)
        if (payload.length > MAX_CHARS) return Result(Outcome.TOO_LARGE)
        val json = try { JSONObject(payload) } catch (_: org.json.JSONException) { return Result(Outcome.INVALID_JSON) }
        if (json.optBoolean("noLyric", false)) return Result(Outcome.PROVIDER_NO_LYRIC)
        val payloadTitle = json.text("songName").ifBlank { json.text("title") }
        val payloadArtist = json.text("artist")
        if (!titlesMatch(title, payloadTitle, titleChanged, artist, payloadArtist)) return Result(Outcome.TRACK_MISMATCH)
        val lyric = json.text("lyric")
        val raw = json.text("rawLyric")
        val ttml = json.text("ttml")
        val timeline = Timeline.parse(lyric, raw,
            translationKeys.firstNotNullOfOrNull { json.text(it).takeIf(String::isNotBlank) }.orEmpty(),
            TtmlEnds.parse(ttml),
            TtmlWords.parse(ttml),
            titles = listOfNotNull(title, payloadTitle.takeIf(String::isNotBlank)),
            artist = artist?.takeIf(String::isNotBlank) ?: payloadArtist,
        )
        return Result(if (timeline == null) Outcome.NO_TIMED_LINES else Outcome.PARSED,
            timeline, lyric.isNotBlank() || raw.isNotBlank(), ttml.isNotBlank())
    }

    private fun JSONObject.text(key: String): String = (opt(key) as? String).orEmpty()

    internal fun titlesMatch(
        title: String?, payloadTitle: String, titleChanged: Boolean,
        artist: String? = null, payloadArtist: String = "",
    ): Boolean {
        if (title.isNullOrBlank() || payloadTitle.isBlank()) return !titleChanged
        val current = normalize(title)
        val supplied = normalize(payloadTitle)
        if (current == supplied) return true
        if (artist.isNullOrBlank() || payloadArtist.isBlank() || normalize(artist) != normalize(payloadArtist)) return false
        val currentCore = current.replace(remasterSuffix, "").trim()
        val suppliedCore = supplied.replace(remasterSuffix, "").trim()
        return currentCore.isNotEmpty() && currentCore == suppliedCore && (current == currentCore || supplied == suppliedCore)
    }

    private val remasterSuffix = Regex(
        """(?:\s+[-–—]\s+|\s*[(\[])(?:(?:19|20)\d{2}\s+)?remaster(?:ed)?(?:\s+(?:19|20)\d{2})?[)\]]?$""",
    )

    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")
}
