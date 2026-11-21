package shop.terramoor.github.io.settings

import android.content.res.Resources
import androidx.annotation.ArrayRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import shop.terramoor.github.io.R
import shop.terramoor.github.io.lyrics.LyricFrame
import shop.terramoor.github.io.lyrics.LyricLine
import shop.terramoor.github.io.lyrics.LyricSecondRow
import shop.terramoor.github.io.lyrics.Timeline

internal const val PREVIEW_DURATION_MS = 9100L
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

    fun frames(resources: Resources, settings: CapsuleSettings): List<LyricFrame> {
        val originals = resources.getStringArray(lyrics)
        val translations = resources.getStringArray(translation)
        val lines = lineStarts.mapIndexed { index, time ->
            LyricLine(time, originals[index], translations[index])
        }
        val timeline = Timeline.fromLines(lines)
        fun next(index: Int) = lines.drop(index + 1).firstOrNull { it.text.isNotBlank() }
        fun layout(index: Int): Triple<LyricSwitchMode, LyricSecondRow, String?> {
            val line = lines[index]
            if (!settings.doubleLine) return Triple(settings.singleLineSwitchMode, LyricSecondRow.TRANSLATION, null)
            if (settings.secondRowContent == SecondRowContent.LYRIC) {
                return Triple(settings.doubleLineSwitchMode, LyricSecondRow.LYRIC, next(index)?.text)
            }
            val translated = line.translation?.takeIf(String::isNotBlank)
            return if (translated != null) {
                Triple(settings.doubleLineSwitchMode, LyricSecondRow.TRANSLATION, translated)
            } else if (settings.missingTranslationMode == MissingTranslationMode.DOUBLE_LYRICS) {
                Triple(settings.doubleLineSwitchMode, LyricSecondRow.LYRIC, next(index)?.text)
            } else {
                Triple(settings.singleLineSwitchMode, LyricSecondRow.TRANSLATION, null)
            }
        }
        return lines.mapIndexed { index, line ->
            val (mode, row, second) = layout(index)
            val previous = index - 1
            val previousLayout = previous.takeIf { it >= 0 }?.let(::layout)
            val transitionMode = lines.getOrNull(index + 1)?.let { layout(index + 1).first } ?: mode
            LyricFrame(
                line = line,
                endMs = timeline.scrollEnd(index, PREVIEW_DURATION_MS, transitionMode),
                displayStartMs = timeline.displayStart(index, mode),
                priorLine = lines.getOrElse(index - 1) { lines.last() },
                secondText = second,
                priorSecondText = previousLayout?.third,
                secondRow = row,
                priorSecondRow = previousLayout?.second ?: row,
            )
        }
    }
}
