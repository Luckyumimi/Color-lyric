package shop.terramoor.github.io.lyrics.provider

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.LyricWord
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song

internal fun Song.toPublication(playerPackage: String): ExternalLyricsPublication? {
    val title = name?.trim().orEmpty()
    val sourceLines = lyrics.orEmpty()
    if (title.isBlank() || sourceLines.isEmpty()) return null
    val converted = sourceLines.mapNotNull { line ->
        val text = line.text.orEmpty()
        if (text.isBlank() || line.end <= line.begin) return@mapNotNull null
        val words = line.words.orEmpty().mapNotNull { word ->
            val wordText = word.text.orEmpty()
            if (wordText.isEmpty() || word.end <= word.begin) null
            else LyricWord(begin = word.begin, end = word.end, text = wordText)
        }
        RichLyricLine(
            begin = line.begin,
            end = line.end,
            duration = line.duration,
            text = text,
            words = words.takeIf { it.isNotEmpty() },
            secondary = line.translation ?: line.secondary,
            secondaryWords = line.translationWords.orEmpty().mapNotNull { word ->
                val wordText = word.text.orEmpty()
                if (wordText.isEmpty() || word.end <= word.begin) null
                else LyricWord(begin = word.begin, end = word.end, text = wordText)
            }.takeIf { it.isNotEmpty() },
        )
    }
    if (converted.isEmpty()) return null
    return ExternalLyricsPublication(
        playerPackage = playerPackage,
        track = TrackIdentity(
            id = id,
            title = title,
            artist = artist.orEmpty(),
            album = metadata?.getString("album").orEmpty(),
            durationMs = duration.coerceAtLeast(0L),
        ),
        lines = converted,
    )
}
