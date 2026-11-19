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

    fun parse(payload: String?, title: String?, titleChanged: Boolean, artist: String? = null): Timeline? {
        if (payload.isNullOrBlank() || payload.length > MAX_CHARS) return null
        val json = try { JSONObject(payload) } catch (_: org.json.JSONException) { return null }
        if (json.optBoolean("noLyric", false)) return null
        val payloadTitle = json.text("songName").ifBlank { json.text("title") }
        if (!titlesMatch(title, payloadTitle, titleChanged, artist, json.text("artist"))) return null
        return Timeline.parse(
            json.text("lyric"),
            json.text("rawLyric"),
            translationKeys.firstNotNullOfOrNull { json.text(it).takeIf(String::isNotBlank) }.orEmpty(),
            TtmlEnds.parse(json.text("ttml")),
        )
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
