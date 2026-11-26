package shop.terramoor.github.io.systemui

import kotlin.math.roundToInt

/** Retargets from the currently visible mixture, including during a rapid skip. */
internal class CapsuleColorTransition<T>(initial: T) {
    private var start = mapOf(initial to 1f)
    var target: T = initial
        private set
    private var startedAt: Long? = null

    fun snap(value: T) {
        target = value
        start = mapOf(value to 1f)
        startedAt = null
    }

    fun moveTo(value: T, now: Long) {
        if (target == value) return
        start = sample(now)
        target = value
        startedAt = now
    }

    fun isMoving(now: Long): Boolean = startedAt?.let { now - it < DURATION_MS } == true

    fun needsBackground(): Boolean = target != null || start.keys.any { it != null }

    fun sample(now: Long): Map<T, Float> {
        val from = startedAt ?: return start
        if (!isMoving(now)) {
            snap(target)
            return start
        }
        val t = ((now - from).toFloat() / DURATION_MS).coerceIn(0f, 1f)
        if (t == 0f) return start
        val eased = t * t * (3f - 2f * t)
        val mixture = LinkedHashMap<T, Float>(start.size + 1)
        for ((value, weight) in start) {
            val faded = weight * (1f - eased)
            if (faded >= 1f / 255f) mixture[value] = faded
        }
        mixture[target] = (mixture[target] ?: 0f) + eased
        return mixture
    }

    companion object {
        const val DURATION_MS = 300L
        const val ARTWORK_WAIT_MS = 450L
    }
}

internal fun blendLyricInks(inks: Map<LyricInk, Float>): LyricInk {
    if (inks.size == 1) return inks.keys.first()
    val total = inks.values.sum()
    fun mixed(color: (LyricInk) -> Int): Int {
        fun component(shift: Int) = (inks.entries.sumOf { (ink, weight) ->
            (channel(color(ink), shift) * weight).toDouble()
        } / total).roundToInt().coerceIn(0, 255)
        return opaqueRgb(component(16), component(8), component(0))
    }
    val gradient = if (inks.keys.any { it.gradient != null }) LyricGradient(
        mixed { it.gradient?.start ?: it.first },
        mixed { it.gradient?.center ?: it.first },
        mixed { it.gradient?.end ?: it.first },
    ) else null
    return LyricInk(mixed { it.first }, mixed { it.second }, gradient = gradient)
}
