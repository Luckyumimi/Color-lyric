package shop.terramoor.github.io.lyrics

private const val WORD_BASELINE_SETTLE_MS = 170L
private const val LONG_WORD_MIN_MS = 1200L
private const val LONG_WORD_EASE_MS = 320f

/** Text offsets address the original row in UTF-16. Times address the song playback clock. */
internal data class LyricWord(val from: Int, val until: Int, val startMs: Long, val endMs: Long? = null) {
    fun fraction(positionMs: Long): Float {
        if (positionMs < startMs) return 0f
        val end = endMs ?: startMs
        if (positionMs >= end) return 1f
        return (positionMs - startMs).toFloat() / (end - startMs)
    }

    fun baselineOffset(positionMs: Long): Float {
        if (positionMs < startMs) return 1f
        val duration = ((endMs ?: startMs) - startMs).coerceIn(0L, WORD_BASELINE_SETTLE_MS)
        if (duration == 0L || positionMs - startMs >= duration) return 0f
        val fraction = (positionMs - startMs).toFloat() / duration
        return 1f - fraction * fraction * (3f - 2f * fraction)
    }

    fun sustainEmphasis(positionMs: Long): Float {
        val end = endMs ?: return 0f
        if (end - startMs < LONG_WORD_MIN_MS || positionMs <= startMs || positionMs >= end) return 0f
        val rise = (positionMs - startMs - WORD_BASELINE_SETTLE_MS) / LONG_WORD_EASE_MS
        val fall = (end - positionMs) / LONG_WORD_EASE_MS
        val level = minOf(rise, fall).coerceIn(0f, 1f)
        return level * level * (3f - 2f * level)
    }
}

internal data class LyricWordLine(val text: String, val words: List<LyricWord>) {
    fun match(textToDraw: String, lineStart: Long, lineEnd: Long?): List<LyricWord> {
        val source = text.indices.filter { !text[it].isWhitespace() }
        val target = textToDraw.indices.filter { !textToDraw[it].isWhitespace() }
        if (source.size != target.size || source.indices.any { text[source[it]] != textToDraw[target[it]] }) return emptyList()
        val spans = words.mapNotNull { word ->
            val first = source.firstAtOrAfter(word.from)
            val last = source.firstAtOrAfter(word.until)
            if (first == last) null else word.copy(from = target[first], until = target[last - 1] + 1)
        }
        if (spans.isEmpty()) return emptyList()
        var endOfPrevious = 0
        var previousStart = lineStart
        for (word in spans) {
            if (word.from < endOfPrevious || word.startMs < previousStart ||
                lineEnd?.let { word.startMs > it } == true ||
                !UNTIMED_GAP.matches(textToDraw.substring(endOfPrevious, word.from))
            ) return emptyList()
            endOfPrevious = word.until
            previousStart = word.startMs
        }
        if (!UNTIMED_GAP.matches(textToDraw.substring(endOfPrevious))) return emptyList()
        return spans.mapIndexed { index, word ->
            val next = spans.getOrNull(index + 1)
            val limit = next?.startMs ?: lineEnd
            val end = word.endMs?.takeIf { it > word.startMs } ?: limit
            word.copy(
                from = if (index == 0) 0 else word.from,
                until = next?.from ?: textToDraw.length,
                endMs = end?.let { if (limit != null) minOf(it, limit) else it }?.takeIf { it > word.startMs },
            )
        }
    }

    private companion object {
        val UNTIMED_GAP = Regex("[\\p{P}\\s]*")
    }
}

private fun List<Int>.firstAtOrAfter(value: Int): Int = binarySearch(value).let { if (it < 0) -it - 1 else it }
