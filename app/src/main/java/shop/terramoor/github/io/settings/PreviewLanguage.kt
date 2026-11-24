package shop.terramoor.github.io.settings

import android.content.res.Resources
import android.icu.text.BreakIterator
import androidx.annotation.ArrayRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import shop.terramoor.github.io.R
import shop.terramoor.github.io.lyrics.LyricFrame
import shop.terramoor.github.io.lyrics.LyricLine
import shop.terramoor.github.io.lyrics.LyricRows
import shop.terramoor.github.io.lyrics.LyricWord
import shop.terramoor.github.io.lyrics.Timeline

internal const val PREVIEW_DURATION_MS = 9100L
internal const val PREVIEW_INTRO_MS = 5000L
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

    fun timeline(resources: Resources, intro: Boolean = false): Timeline? {
        if (this == NO_SYNC_LYRICS) return null
        val originals = resources.getStringArray(lyrics)
        val translations = resources.getStringArray(translation)
        val lead = if (intro) PREVIEW_INTRO_MS else 0L
        val lines = lineStarts.mapIndexed { index, time ->
            val start = time + lead
            val end = lineEnds[index] + lead
            val text = originals[index]
            val boundaries = BreakIterator.getCharacterInstance().apply { setText(text) }
            val ranges = ArrayList<IntRange>()
            var from = boundaries.first()
            var until = boundaries.next()
            while (until != BreakIterator.DONE) {
                if (text.substring(from, until).isNotBlank()) ranges += from until until
                from = until
                until = boundaries.next()
            }
            val words = ranges.mapIndexed { i, range ->
                LyricWord(range.first, ranges.getOrNull(i + 1)?.first ?: text.length,
                    start + (end - start) * i / ranges.size, start + (end - start) * (i + 1) / ranges.size)
            }
            LyricLine(start, text, translations[index], end, words)
        }
        return Timeline.fromLines(lines)
    }

    fun trackInfo(resources: Resources): LyricFrame? = if (this == NO_SYNC_LYRICS) {
        val rows = sampleTrack(resources)
        LyricFrame.trackInfo(rows.first, rows.second)
    } else null

    fun sampleTrack(resources: Resources): LyricRows = if (this == JAPANESE) {
        LyricRows("ツクヨミステップ")
    } else {
        LyricRows(resources.getString(R.string.preview_track_title), resources.getString(R.string.preview_track_artist))
    }
}
