package shop.terramoor.github.io.lyrics

import shop.terramoor.github.io.settings.LyricSwitchMode

internal data class LyricLine(val startMs: Long, val text: String, val translation: String? = null, val endMs: Long? = null)

internal class Timeline private constructor(internal val lines: List<LyricLine>) {
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

        fun parse(lyric: String, rawLyric: String, translation: String, ends: Map<Long, Long> = emptyMap()): Timeline? {
            val timedRaw = if (rawLyric != lyric) parseLane(rawLyric) else emptyList()
            val rawByTime = timedRaw.associateBy { it.startMs }
            val originals = parseLane(lyric).takeIf(::hasTimedText)
                ?: timedRaw.takeIf(::hasTimedText).orEmpty()
            if (originals.isEmpty()) return null
            val translations = parseLane(translation)
            // Each translation belongs to at most one source line. Exact timestamps win.
            val matched = arrayOfNulls<String>(originals.size)
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
            return Timeline(originals.mapIndexed { i, line ->
                val rawEnd = rawByTime[line.startMs]?.takeIf { it.text == line.text }?.endMs
                line.copy(translation = matched[i]?.takeUnless { it == line.text },
                    endMs = (line.endMs ?: rawEnd ?: ends[line.startMs])?.takeIf { it > line.startMs })
            })
        }

        // An untimed source can serialize every line with a zero timestamp.
        private fun hasTimedText(lines: List<LyricLine>): Boolean =
            lines.any { it.text.isNotBlank() } && lines.any { it.startMs > 0L }

        private fun parseLane(input: String): List<LyricLine> {
            val lines = sortedMapOf<Long, LyricLine>()
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
                val text = time.replace(line.substring(prefixEnd), "").trim().take(512)
                val explicitEnd = marks.lastOrNull()?.takeIf {
                    it.range.first >= prefixEnd && it.range.last == line.lastIndex && it.value.startsWith('<')
                }?.toMillis()
                // Keep timestamp-only lines as explicit gaps.
                for (start in starts) {
                    if (lines.size >= 4000) break
                    if (text.isNotEmpty() || !lines.containsKey(start)) {
                        lines[start] = LyricLine(start, text, endMs = explicitEnd?.takeIf { it > start })
                    }
                }
            }
            if (lines.values.none { it.text.isNotEmpty() }) return emptyList()
            return lines.values.toList()
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
