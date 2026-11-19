package shop.terramoor.github.io.settings

import android.content.res.Resources
import androidx.annotation.ArrayRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import shop.terramoor.github.io.R
import shop.terramoor.github.io.lyrics.LyricFrame
import shop.terramoor.github.io.lyrics.LyricLine

internal const val PREVIEW_DURATION_MS = 9100L
private const val PREVIEW_LEAD_MS = 350L
private val lineStarts = longArrayOf(0L, 3500L, 5400L)

internal enum class PreviewLanguage(
    @StringRes val label: Int,
    @ArrayRes private val lyrics: Int,
    @ArrayRes private val translation: Int = R.array.preview_lyrics_chinese,
    @DrawableRes val cover: Int = 0,
) {
    JAPANESE(R.string.preview_japanese, R.array.preview_lyrics_japanese,
        R.array.preview_lyrics_japanese_chinese, R.drawable.preview_tsukuyomi_step),
    ENGLISH(R.string.preview_english, R.array.preview_lyrics_english),
    KOREAN(R.string.preview_korean, R.array.preview_lyrics_korean);

    fun frames(resources: Resources): List<LyricFrame> {
        val originals = resources.getStringArray(lyrics)
        val translations = resources.getStringArray(translation)
        val lines = lineStarts.mapIndexed { index, time ->
            LyricLine(time, originals[index], translations[index])
        }
        return lines.mapIndexed { index, line ->
            LyricFrame(
                line = line,
                endMs = lines.getOrNull(index + 1)?.let { it.startMs - PREVIEW_LEAD_MS } ?: PREVIEW_DURATION_MS,
                displayStartMs = (line.startMs - PREVIEW_LEAD_MS).coerceAtLeast(0L),
                priorLine = lines.getOrElse(index - 1) { lines.last() },
            )
        }
    }
}
