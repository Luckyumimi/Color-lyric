package shop.terramoor.github.io.lyrics

import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Restores the song title for this module's Migu payload when the host exposes a lyric as TITLE. */
internal class MiguMediaIdentity {
    private data class Payload(val title: String, val artist: String, val duration: Long, val lines: Set<String>)
    private var observed: String? = null
    private var parsed: Payload? = null
    private var boundMediaId: String? = null

    @Synchronized
    fun resolve(owner: String?, title: String?, artist: String?, duration: Long, lyricInfo: String?, mediaId: String? = null): String? {
        if (owner != "cmccwm.mobilemusic") return title
        if (lyricInfo.isNullOrBlank()) {
            if (mediaId.isNullOrBlank() || mediaId != boundMediaId) return title
        } else if (observed != lyricInfo) {
            observed = lyricInfo
            parsed = parse(lyricInfo)
            boundMediaId = null
        }
        val payload = parsed ?: return title
        val raw = normalize(title)
        if (raw.isEmpty() || normalize(artist) != payload.artist || duration <= 0L || payload.duration <= 0L ||
            abs(duration - payload.duration) > 3_000L) return title
        if (raw == normalize(payload.title)) {
            if (!lyricInfo.isNullOrBlank()) boundMediaId = mediaId?.takeIf(String::isNotBlank)
            return title
        }
        if (raw !in payload.lines) return title
        if (!lyricInfo.isNullOrBlank()) boundMediaId = mediaId?.takeIf(String::isNotBlank)
        return payload.title
    }

    private fun parse(value: String): Payload? = runCatching {
        if (value.length > 262_144) return null
        val json = JSONObject(value)
        if (json.optString("provider") != "cmccwm.mobilemusic" ||
            json.optString("source") != "cmccwm.mobilemusic-v5") return null
        val title = json.optString("songName").trim()
        val artist = normalize(json.optString("artist"))
        val duration = json.optLong("durationMs")
        if (title.isEmpty() || artist.isEmpty() || duration <= 0L) return null
        val lines = Timeline.parse(json.optString("lyric"), "", "")?.lines
            ?.map { normalize(it.text) }?.filter(String::isNotEmpty)?.toSet().orEmpty()
        if (lines.isEmpty()) return null
        Payload(title, artist, duration, lines)
    }.getOrNull()

    private fun normalize(value: String?): String = Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFKC)
        .trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
}
