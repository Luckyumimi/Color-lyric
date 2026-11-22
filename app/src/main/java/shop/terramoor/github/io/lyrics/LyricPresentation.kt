package shop.terramoor.github.io.lyrics

import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.LyricSwitchMode
import shop.terramoor.github.io.settings.MissingTranslationMode
import shop.terramoor.github.io.settings.SecondRowContent

/** Resolves the same rows and playback boundaries for SystemUI and the settings preview. */
internal class LyricPresentation(private val timeline: Timeline, private val settings: CapsuleSettings) {
    private fun translation(index: Int): String? = timeline.lines[index].translation?.takeIf(String::isNotBlank)

    private fun fallsBackToSingleLine(index: Int): Boolean = settings.doubleLine &&
        settings.secondRowContent == SecondRowContent.TRANSLATION &&
        settings.missingTranslationMode == MissingTranslationMode.SINGLE_LINE && translation(index) == null

    fun switchMode(index: Int): LyricSwitchMode =
        if (settings.canShowNextLyric && (settings.secondRowContent == SecondRowContent.LYRIC || translation(index) == null)) {
            settings.doubleLineSwitchMode
        } else settings.singleLineSwitchMode

    fun indexAt(positionMs: Long): Int = timeline.displayIndexAt(positionMs, ::switchMode)

    private fun displayStart(index: Int): Long = timeline.displayStart(
        index, if (index > 0) switchMode(index - 1) else settings.singleLineSwitchMode,
    )

    fun nextBoundary(index: Int): Long? = (index + 1).takeIf { it in timeline.lines.indices }?.let(::displayStart)

    private fun rowsAt(index: Int): LyricRows? {
        val line = timeline.lines.getOrNull(index)?.takeIf { it.text.isNotBlank() } ?: return null
        if (!settings.doubleLine) return LyricRows(line.text)
        if (settings.secondRowContent == SecondRowContent.TRANSLATION) {
            translation(index)?.let {
                return if (settings.swapTranslation) LyricRows(it, line.text) else LyricRows(line.text, it)
            }
            if (fallsBackToSingleLine(index)) return LyricRows(line.text, missingTranslationSingleLine = true)
        }
        val next = (index + 1 until timeline.lines.size).firstOrNull { timeline.lines[it].text.isNotBlank() }
            ?.let { timeline.lines[it] }
        return LyricRows(line.text, second = next?.text, nextLyricStartMs = next?.startMs)
    }

    fun frameAt(index: Int, durationMs: Long): LyricFrame? {
        val rows = rowsAt(index) ?: return null
        return LyricFrame(
            rows = rows,
            startMs = timeline.lines[index].startMs,
            endMs = timeline.scrollEnd(index, durationMs, switchMode(index)),
            priorRows = rowsAt(index - 1),
            displayStartMs = displayStart(index),
        )
    }
}
