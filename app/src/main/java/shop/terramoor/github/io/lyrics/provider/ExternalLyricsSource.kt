package shop.terramoor.github.io.lyrics.provider

import android.content.Context
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine

internal enum class ExternalLyricsProvider(val id: String) {
    BUILTIN("builtin"),
    LYRIC_INFO("lyric_info"),
    LYRICON("lyricon"),
    SUPER_LYRIC("super_lyric");

    companion object {
        fun fromId(id: String?): ExternalLyricsProvider = entries.firstOrNull { it.id == id } ?: BUILTIN
    }
}

@kotlinx.serialization.Serializable
internal data class ExternalLyricsPublication(
    val playerPackage: String,
    val track: TrackIdentity,
    val lines: List<RichLyricLine>,
)

internal interface ExternalLyricsCallbacks {
    fun onActivePlayerChanged(packageName: String?)
    fun onPublication(publication: ExternalLyricsPublication)
    fun onStopped(packageName: String?)
}

internal interface ExternalLyricsSource {
    val provider: ExternalLyricsProvider
    fun start(context: Context, callbacks: ExternalLyricsCallbacks)
    fun stop()
}
