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

    private fun nextLyric(index: Int): LyricLine? = (index + 1 until timeline.lines.size)
        .firstOrNull { timeline.lines[it].text.isNotBlank() }?.let { timeline.lines[it] }

    fun switchMode(index: Int): LyricSwitchMode {
        if (!settings.doubleLine || fallsBackToSingleLine(index)) return LyricSwitchMode.AFTER_END
        val hasSecondRow = (settings.secondRowContent == SecondRowContent.TRANSLATION && translation(index) != null) ||
            nextLyric(index) != null
        return if (hasSecondRow) LyricSwitchMode.AT_NEXT_START else LyricSwitchMode.AFTER_END
    }

    fun indexAt(positionMs: Long): Int = timeline.displayIndexAt(positionMs, ::switchMode)

    private fun displayStart(index: Int): Long = timeline.displayStart(
        index, if (index > 0) switchMode(index - 1) else LyricSwitchMode.AFTER_END,
    )

    fun nextBoundary(index: Int): Long? = (index + 1).takeIf { it in timeline.lines.indices }?.let(::displayStart)

    fun introAt(positionMs: Long, track: () -> LyricRows? = { null }): LyricFrame? {
        if (!settings.introDots) return null
        val first = timeline.lines.firstOrNull { it.text.isNotBlank() } ?: return null
        if (positionMs !in 0L until first.startMs) return null
        return LyricFrame.intro(first.startMs, track())
    }

    private fun rowsAt(index: Int): LyricRows? {
        val line = timeline.lines.getOrNull(index)?.takeIf { it.text.isNotBlank() } ?: return null
        val words = line.words.takeIf { settings.wordLyrics }.orEmpty()
        if (!settings.doubleLine) return LyricRows(line.text, firstWords = words)
        if (settings.secondRowContent == SecondRowContent.TRANSLATION) {
            translation(index)?.let {
                return if (settings.swapTranslation) LyricRows(it, line.text, secondWords = words)
                else LyricRows(line.text, it, firstWords = words)
            }
            if (fallsBackToSingleLine(index)) {
                return LyricRows(line.text, missingTranslationSingleLine = true, firstWords = words)
            }
        }
        val next = nextLyric(index)
        return LyricRows(line.text, second = next?.text, nextLyricStartMs = next?.startMs,
            firstWords = words, secondWords = next?.words?.takeIf { settings.wordLyrics }.orEmpty())
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
