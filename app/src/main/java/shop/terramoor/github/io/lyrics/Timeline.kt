package shop.terramoor.github.io.lyrics

import shop.terramoor.github.io.settings.LyricSwitchMode

internal data class LyricLine(
    val startMs: Long,
    val text: String,
    val translation: String? = null,
    val endMs: Long? = null,
    val words: List<LyricWord> = emptyList(),
    val isInstrumentalGap: Boolean = false,
)

internal class Timeline private constructor(
    internal val lines: List<LyricLine>, titles: List<String> = emptyList(), artist: String? = null,
    translationInput: String = "",
) {
    internal val withoutIntroCredits: Timeline by lazy(LazyThreadSafetyMode.NONE) {
        val first = IntroLyricCredits.prefixSize(lines, titles, artist)
        val retained = lines.subList(first, lines.size)
        if (retained.isEmpty() || translationInput.isBlank()) {
            if (first == 0) this else Timeline(retained)
        } else {
            val cleaned = parseLane(translationInput, readWords = false, introEndMs = retained.first().startMs)
            // Keep other intro anchors, but a copyright notice cannot own a nearby translation.
            val introAnchors = lines.subList(0, first).filterNot { IntroLyricCredits.isCopyrightNotice(it.text) }
            val matched = matchTranslations(introAnchors + retained, cleaned)
            Timeline(retained.mapIndexed { index, line -> line.withTranslation(matched[introAnchors.size + index]) })
        }
    }

    internal val withInstrumentalGaps: Timeline by lazy(LazyThreadSafetyMode.NONE) {
        var inserted = false
        var latestVocalEnd = 0L
        var previousHasEnd = false
        var firstBlank: LyricLine? = null
        var blankIndex = 0
        val expanded = buildList {
            for (line in lines) {
                if (line.text.isBlank()) {
                    if (firstBlank == null) {
                        firstBlank = line
                        blankIndex = size
                    }
                    add(line)
                    continue
                }
                val blank = firstBlank
                val gapStart = if (previousHasEnd) latestVocalEnd else blank?.let { maxOf(it.startMs, latestVocalEnd) }
                if (gapStart != null && line.startMs - gapStart >= 7_000L) {
                    if (blank != null) while (size > blankIndex) removeAt(lastIndex)
                    add(LyricLine(gapStart, "", endMs = line.startMs, isInstrumentalGap = true))
                    inserted = true
                }
                add(line)
                firstBlank = null
                val wordEnd = line.words.lastOrNull()?.takeIf { it.until == line.text.length }?.endMs
                val end = maxOf(line.endMs ?: 0L, wordEnd ?: 0L)
                previousHasEnd = end > line.startMs
                latestVocalEnd = maxOf(latestVocalEnd, end)
            }
        }
        if (inserted) Timeline(expanded) else this
    }

    internal fun indexAt(positionMs: Long): Int {
        var low = 0
        var high = lines.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (lines[mid].startMs <= positionMs) low = mid + 1 else high = mid
        }
        return low - 1
    }

    // The outgoing row layout controls when the next line replaces it.
    fun displayStart(index: Int, outgoingMode: LyricSwitchMode = LyricSwitchMode.AFTER_END): Long {
        val current = lines[index]
        val previous = lines.getOrNull(index - 1) ?: return current.startMs
        if (outgoingMode == LyricSwitchMode.AT_NEXT_START || previous.text.isBlank() || current.text.isBlank()) {
            return current.startMs
        }
        return previous.endMs?.coerceIn(previous.startMs + 1, current.startMs)
            ?: current.startMs
    }

    fun displayIndexAt(positionMs: Long, mode: LyricSwitchMode = LyricSwitchMode.AFTER_END): Int =
        displayIndexAt(positionMs) { mode }

    fun displayIndexAt(positionMs: Long, modeForLine: (Int) -> LyricSwitchMode): Int {
        val timedIndex = indexAt(positionMs)
        val next = timedIndex + 1
        return if (timedIndex >= 0 && next in lines.indices &&
            displayStart(next, modeForLine(timedIndex)) <= positionMs
        ) next else timedIndex
    }

    fun scrollEnd(index: Int, durationMs: Long, mode: LyricSwitchMode = LyricSwitchMode.AFTER_END): Long {
        val boundary = if (index + 1 in lines.indices) displayStart(index + 1, mode) else durationMs
        // A real end still bounds scrolling into an explicit gap or past the last line.
        if (index + 1 in lines.indices && lines[index + 1].text.isNotBlank()) return boundary
        val explicitEnd = lines[index].endMs ?: return boundary
        return if (boundary > 0L) minOf(boundary, explicitEnd) else explicitEnd
    }

    companion object {
        fun fromLines(lines: List<LyricLine>): Timeline = Timeline(lines.sortedBy { it.startMs }.distinctBy { it.startMs })

        private val time = Regex("""(?:\[(\d{1,4}):(\d{2})(?:[.:](\d{1,3}))?]|<(\d{1,4}):(\d{2})(?:[.:](\d{1,3}))?>)""")

        fun parse(
            lyric: String, rawLyric: String, translation: String, ends: Map<Long, Long> = emptyMap(),
            ttmlWords: Map<Long, LyricWordLine> = emptyMap(),
            titles: List<String> = emptyList(), artist: String? = null,
        ): Timeline? {
            val timedRaw = if (rawLyric != lyric) parseLane(rawLyric) else emptyList()
            val rawByTime = timedRaw.associateBy { it.startMs }
            val originals = parseLane(lyric).takeIf(::hasTimedText)
                ?: timedRaw.takeIf(::hasTimedText).orEmpty()
            if (originals.isEmpty()) return null
            val translations = parseLane(translation, readWords = false)
            val matched = matchTranslations(originals, translations)
            return Timeline(originals.mapIndexed { i, line ->
                val raw = rawByTime[line.startMs]
                val rawEnd = raw?.takeIf { it.text == line.text }?.endMs
                val end = (line.endMs ?: rawEnd ?: ends[line.startMs])?.takeIf { it > line.startMs }
                val words = ttmlWords[line.startMs]?.match(line.text, line.startMs, end).orEmpty()
                    .ifEmpty { raw?.wordTimingFor(line, end).orEmpty() }
                    .ifEmpty { line.wordTimingFor(line, end) }
                line.withTranslation(matched[i]).copy(endMs = end, words = words)
            }, titles, artist, translation)
        }

        private fun matchTranslations(originals: List<LyricLine>, translations: List<LyricLine>): Array<String?> {
            // Each translation belongs to at most one source line. Exact timestamps win.
            val matched = arrayOfNulls<String>(originals.size)
            if (translations.isEmpty()) return matched
            val translatedByTime = translations.associateBy { it.startMs }
            val used = HashSet<Long>()
            for ((i, line) in originals.withIndex()) {
                translatedByTime[line.startMs]?.let {
                    matched[i] = it.text
                    used += it.startMs
                }
            }
            var first = 0
            for ((i, line) in originals.withIndex()) {
                if (matched[i] != null) continue
                while (first < translations.size && translations[first].startMs < line.startMs - 120L) first++
                var closest: LyricLine? = null
                for (j in first until translations.size) {
                    val candidate = translations[j]
                    if (candidate.startMs > line.startMs + 120L) break
                    if (candidate.startMs in used) continue
                    val distance = kotlin.math.abs(candidate.startMs - line.startMs)
                    if (closest == null || distance < kotlin.math.abs(closest.startMs - line.startMs)) {
                        // Don't take a translation that is closer to an adjacent original line.
                        val before = originals.getOrNull(i - 1)
                        val after = originals.getOrNull(i + 1)
                        if ((before == null || kotlin.math.abs(candidate.startMs - before.startMs) > distance) &&
                            (after == null || kotlin.math.abs(candidate.startMs - after.startMs) > distance)
                        ) closest = candidate
                    }
                }
                closest?.let {
                    matched[i] = it.text
                    used += it.startMs
                }
            }
            return matched
        }

        private fun LyricLine.withTranslation(value: String?): LyricLine {
            val translated = value?.takeUnless { it == text }
            return if (translation == translated) this else copy(translation = translated)
        }

        private fun LyricLine.wordTimingFor(line: LyricLine, endMs: Long?): List<LyricWord> =
            if (words.isEmpty()) emptyList() else LyricWordLine(text, words).match(line.text, line.startMs, endMs)

        // An untimed source can serialize every line with a zero timestamp.
        private fun hasTimedText(lines: List<LyricLine>): Boolean =
            lines.any { it.text.isNotBlank() } && lines.any { it.startMs > 0L }

        private fun parseLane(input: String, readWords: Boolean = true, introEndMs: Long? = null): List<LyricLine> {
            val lines = sortedMapOf<Long, LyricLine>()
            // Keep candidates until the leading credits have been excluded, then collapse duplicate timestamps.
            val candidates = introEndMs?.let { ArrayList<LyricLine>() }
            for (inputLine in input.lineSequence().take(4000)) {
                if (inputLine.length > 8192) continue
                val line = inputLine.trim()
                val marks = time.findAll(line).take(512).toList()
                if (marks.firstOrNull()?.range?.first != 0) continue
                var prefixEnd = 0
                val starts = ArrayList<Long>()
                for (mark in marks) {
                    if (mark.range.first != prefixEnd) break
                    mark.toMillis()?.let(starts::add)
                    prefixEnd = mark.range.last + 1
                    // Angle tags in enhanced LRC give word timing, not repeat-line timestamps.
                    if (mark.value.startsWith('<')) break
                }
                val plain = time.replace(line.substring(prefixEnd), "")
                val text = plain.trim().take(512)
                if (candidates != null && IntroLyricCredits.skipTranslationRow(text)) continue
                val explicitEnd = marks.lastOrNull()?.takeIf {
                    it.range.first >= prefixEnd && it.range.last == line.lastIndex && it.value.startsWith('<')
                }?.toMillis()
                val words = if (readWords) parseWords(line, marks, plain, text) else emptyList()
                // Keep timestamp-only lines as explicit gaps.
                for (start in starts) {
                    if (candidates != null) {
                        if (candidates.size >= 4000) break
                        candidates += LyricLine(start, text)
                    } else if (lines.size >= 4000) break
                    else if (text.isNotEmpty() || !lines.containsKey(start)) {
                        lines[start] = LyricLine(start, text, endMs = explicitEnd?.takeIf { it > start }, words = words)
                    }
                }
            }
            if (candidates != null) {
                val firstText = candidates.asSequence().filterNot { IntroLyricCredits.isOpeningTranslationCredit(it.text) }
                    .minOfOrNull { it.startMs } ?: return emptyList()
                val openingEnd = maxOf(firstText, introEndMs)
                for (line in candidates) {
                    if (line.startMs <= openingEnd && IntroLyricCredits.isOpeningTranslationCredit(line.text)) continue
                    lines[line.startMs] = line
                }
            }
            if (lines.values.none { it.text.isNotEmpty() }) return emptyList()
            return lines.values.toList()
        }

        private fun parseWords(line: String, marks: List<MatchResult>, plain: String, text: String): List<LyricWord> {
            if (text.isEmpty() || plain.trim().length != text.length) return emptyList()
            val firstWord = marks.indexOfFirst { it.value.startsWith('<') }
            if (firstWord < 0 || firstWord > 1 || marks.drop(firstWord).any { !it.value.startsWith('<') }) return emptyList()
            val linePrefix = if (firstWord == 0) 0 else marks[0].range.last + 1
            val leadingSpace = plain.length - plain.trimStart().length
            var offset = marks[firstWord].range.first - linePrefix - leadingSpace
            val words = ArrayList<LyricWord>()
            for (index in firstWord until marks.size) {
                val mark = marks[index]
                val from = mark.range.last + 1
                val next = marks.getOrNull(index + 1)
                val length = (next?.range?.first ?: line.length) - from
                val start = mark.toMillis() ?: return emptyList()
                val textFrom = offset.coerceIn(0, text.length)
                val textUntil = (offset + length).coerceIn(0, text.length)
                if (textUntil > textFrom) words += LyricWord(textFrom, textUntil, start, next?.toMillis())
                offset += length
            }
            return words
        }

        private fun MatchResult.toMillis(): Long? {
            val base = if (groupValues[1].isNotEmpty()) 1 else 4
            val minutes = groupValues[base].toLong()
            val seconds = groupValues[base + 1].toInt()
            if (seconds >= 60) return null
            return (minutes * 60 + seconds) * 1000 + groupValues[base + 2].padEnd(3, '0').toLong()
        }
    }
}

internal data class PlaybackClock(
    val basePositionMs: Long,
    val updatedAtMs: Long,
    val speed: Float,
    val moving: Boolean,
    val durationMs: Long,
) {
    fun position(nowMs: Long): Long {
        val elapsed = if (moving && speed.isFinite() && speed > 0 && updatedAtMs > 0) {
            ((nowMs - updatedAtMs).coerceAtLeast(0) * speed.toDouble()).toLong()
        } else 0L
        val base = basePositionMs.coerceAtLeast(0)
        val projected = base + elapsed.coerceAtMost(Long.MAX_VALUE - base)
        return if (durationMs > 0) projected.coerceAtMost(durationMs) else projected
    }

    fun delayTo(positionMs: Long, nextMs: Long): Long? {
        if (!moving || updatedAtMs <= 0 || !speed.isFinite() || speed <= 0 || nextMs <= positionMs) return null
        if (durationMs > 0 && nextMs > durationMs) return null
        return kotlin.math.ceil((nextMs - positionMs) / speed.toDouble()).toLong().coerceAtLeast(1)
    }
}
