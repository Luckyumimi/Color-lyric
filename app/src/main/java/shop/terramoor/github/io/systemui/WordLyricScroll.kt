package shop.terramoor.github.io.systemui

import shop.terramoor.github.io.lyrics.LyricWord

/** A monotone cubic scroll on the playback clock, with a shared speed at each join. */
internal class WordLyricScroll private constructor(private val knots: List<Knot>, private val speeds: FloatArray) {
    private data class Knot(val time: Long, val progress: Float)
    data class Sample(val progress: Float, val redrawDelayMs: Long?)

    fun sample(position: Long): Sample {
        if (position < knots.first().time) return Sample(0f, knots.first().time - position)
        if (position >= knots.last().time) return Sample(1f, null)
        val match = knots.binarySearchBy(position, selector = Knot::time)
        val index = if (match >= 0) match else -match - 2
        val start = knots[index]
        val end = knots[index + 1]
        if (start.progress == end.progress) return Sample(start.progress, end.time - position)

        val duration = (end.time - start.time).toFloat()
        val t = (position - start.time) / duration
        val remaining = 1f - t
        val distance = end.progress - start.progress
        val startControl = speeds[index] * duration / 3f
        val endControl = distance - speeds[index + 1] * duration / 3f
        val moved = 3f * remaining * t * (remaining * startControl + t * endControl) + t * t * t * distance
        return Sample(start.progress + moved.coerceIn(0f, distance), 0L)
    }

    companion object {
        private const val SHORT_GAP_MS = 50L

        fun fromLayout(words: List<LyricWord>, lineWidth: Float, wordEnd: (Int) -> Float): WordLyricScroll? {
            if (words.isEmpty() || !lineWidth.isFinite() || lineWidth <= 0f) return null
            val stops = FloatArray(words.size + 1)
            for (index in 0 until words.lastIndex) {
                val end = (wordEnd(index) / lineWidth).coerceIn(0f, 1f)
                if (!end.isFinite() || end < stops[index]) return null
                stops[index + 1] = end
            }
            stops[words.size] = 1f

            val knots = ArrayList<Knot>()
            for ((index, word) in words.withIndex()) {
                val start = word.startMs
                val nextStart = words.getOrNull(index + 1)?.startMs ?: Long.MAX_VALUE
                val end = minOf(word.endMs ?: start, nextStart)
                // Instantaneous highlights share a neighboring timed interval instead of jumping.
                if (end <= start) continue
                val previous = knots.lastOrNull()
                when {
                    previous == null -> knots += Knot(start, 0f)
                    start < previous.time -> return null
                    start - previous.time in 1L..SHORT_GAP_MS -> knots[knots.lastIndex] = previous.copy(time = start)
                    start > previous.time -> knots += Knot(start, previous.progress)
                }
                knots += Knot(end, stops[index + 1])
            }
            if (knots.isEmpty()) return null
            knots[knots.lastIndex] = knots.last().copy(progress = 1f)

            fun segmentSpeed(index: Int): Float = (knots[index + 1].progress - knots[index].progress) /
                (knots[index + 1].time - knots[index].time)
            val speeds = FloatArray(knots.size)
            for (index in 1 until knots.lastIndex) {
                val left = segmentSpeed(index - 1)
                val right = segmentSpeed(index)
                val slower = minOf(left, right)
                // The harmonic mean keeps the join smooth and limits each tangent to twice
                // the slower segment's speed. At a true hold the shared speed stays zero.
                if (slower > 0f) speeds[index] = 2f * slower / (1f + slower / maxOf(left, right))
            }
            return WordLyricScroll(knots, speeds)
        }
    }
}
