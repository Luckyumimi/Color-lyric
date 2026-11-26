package shop.terramoor.github.io.settings

import shop.terramoor.github.io.R

internal enum class LyricsProviderMode(val id: String, val title: Int, val selectable: Boolean = true) {
    BUILTIN("builtin", R.string.lyrics_hub_network),
    SUPER_LYRIC("super_lyric", R.string.lyrics_provider_superlyric),
    LYRIC_INFO("lyric_info", R.string.lyrics_provider_lyricinfo, selectable = false),
    LYRICON("lyricon", R.string.lyrics_provider_lyricon);

    companion object {
        fun fromId(value: String?): LyricsProviderMode = entries.firstOrNull { it.id == value && it.selectable } ?: BUILTIN
    }
}
