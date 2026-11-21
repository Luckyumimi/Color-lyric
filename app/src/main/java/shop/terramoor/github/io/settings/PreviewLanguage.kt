package shop.terramoor.github.io.settings

import android.content.res.Resources
import androidx.annotation.ArrayRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import shop.terramoor.github.io.R
import shop.terramoor.github.io.lyrics.LyricFrame
import shop.terramoor.github.io.lyrics.LyricLine
import shop.terramoor.github.io.lyrics.Timeline

internal const val PREVIEW_DURATION_MS = 9100L
private val lineStarts = longArrayOf(0L, 3500L, 5400L)
private val lineEnds = longArrayOf(3000L, 4900L, 8700L)

internal enum class PreviewLanguage(
    @StringRes val label: Int,
    @ArrayRes private val lyrics: Int,
    @ArrayRes private val translation: Int = R.array.preview_lyrics_chinese,
    @DrawableRes val cover: Int = 0,
) {
    JAPANESE(R.string.preview_japanese, R.array.preview_lyrics_japanese,
        R.array.preview_lyrics_japanese_chinese, R.drawable.preview_tsukuyomi_step),
    ENGLISH(R.string.preview_english, R.array.preview_lyrics_english),
    KOREAN(R.string.preview_korean, R.array.preview_lyrics_korean),
    NO_SYNC_LYRICS(R.string.preview_no_sync_lyrics, 0);

    fun timeline(resources: Resources): Timeline? {
        if (this == NO_SYNC_LYRICS) return null
        val originals = resources.getStringArray(lyrics)
        val translations = resources.getStringArray(translation)
        val lines = lineStarts.mapIndexed { index, time ->
            LyricLine(time, originals[index], translations[index], lineEnds[index])
        }
        return Timeline.fromLines(lines)
    }

    fun trackInfo(resources: Resources): LyricFrame? = if (this == NO_SYNC_LYRICS) {
        LyricFrame.trackInfo(resources.getString(R.string.preview_track_title), resources.getString(R.string.preview_track_artist))
    } else null
}
