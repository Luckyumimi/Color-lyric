package shop.terramoor.github.io.systemui

import android.graphics.Canvas
import android.graphics.Paint
import shop.terramoor.github.io.lyrics.LyricCountdown
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

private const val INTRO_DOT_RADIUS_DP = 3.15f
private const val DOT_STEP_IN_RADII = 3.25f
private const val BREATH_TARGET_MS = 4000L
private const val MIN_BREATH_BODY_MS = 3000L
private const val INTERLUDE_ENTRY_MS = 500L
private const val BREATH_SETTLE_MS = 1000L
private const val DOT_MAX_SCALE = 1.25f
internal const val INTERLUDE_REOPEN_MS = 420L
private const val INTERLUDE_FULL_WIDTH_LEAD_MS = 32L
internal const val VIEWPORT_HANDOFF_MS = 240L
private const val DOT_SIDE_PADDING_DP = 4f

private fun dotRadius(density: Float, height: Float) = minOf(INTRO_DOT_RADIUS_DP * density, height / 5f)

private fun dotSpan(radius: Float) = 2f * (1f + DOT_STEP_IN_RADII) * radius

internal fun introDotFill(index: Int, position: Long, end: Long): Float {
    val fill = ((position.toDouble() / end.coerceAtLeast(1L)) * 3.0 - index).coerceIn(0.0, 1.0).toFloat()
    return 0.24f + 0.76f * fill * fill * (3f - 2f * fill)
}

internal fun countdownDotScale(elapsedMs: Long, durationMs: Long, isIntro: Boolean): Float {
    val start = if (isIntro) 0L else INTERLUDE_ENTRY_MS
    val stop = durationMs - BREATH_SETTLE_MS
    val body = stop - start
    if (body < MIN_BREATH_BODY_MS || elapsedMs < start || elapsedMs >= stop) return 1f

    // Fit whole AMLL-style cycles into the breathing phase; the lyric transition remains separate.
    val cycles = (body / BREATH_TARGET_MS).coerceAtLeast(1L)
    val phase = (elapsedMs - start).toDouble() * cycles / body
    return 1f + ((DOT_MAX_SCALE - 1f) * (1.0 - cos(2.0 * PI * phase)) / 2.0).toFloat()
}

internal fun interludeCapsuleWidth(
    countdown: LyricCountdown, position: Long, normalWidth: Int, height: Float, density: Float,
    leading: Float, trailing: Float, cameraOffset: Float, cameraRadius: Float, animateOutgoingLyric: Boolean,
): Int {
    if (countdown.isIntro || position !in countdown.startMs until countdown.endMs) return normalWidth
    val elapsed = position - countdown.startMs
    val remaining = countdown.endMs - position
    val entryHold = if (animateOutgoingLyric) LYRIC_MOTION_DURATION_MS else 0L
    val shrink = ((elapsed - entryHold).toFloat() / (INTERLUDE_ENTRY_MS - entryHold)).coerceIn(0f, 1f)
    val reopen = ((remaining - INTERLUDE_FULL_WIDTH_LEAD_MS).toFloat() /
        (INTERLUDE_REOPEN_MS - INTERLUDE_FULL_WIDTH_LEAD_MS)).coerceIn(0f, 1f)
    val phase = minOf(shrink, reopen)
    if (phase <= 0f) return normalWidth

    val pulse = countdownDotScale(elapsed, countdown.endMs - countdown.startMs, isIntro = false)
    val dots = dotSpan(dotRadius(density, height) * pulse) + 2f * DOT_SIDE_PADDING_DP * density
    val contentWidth = if (cameraRadius > 0f) {
        // Keep the island centered while reserving the dots entirely to the right of the camera.
        2f * maxOf(leading + cameraRadius - cameraOffset, trailing + cameraRadius + cameraOffset + dots)
    } else leading + dots + trailing
    val compactWidth = maxOf(height, contentWidth).coerceAtMost(normalWidth.toFloat())
    val ease = phase * phase * (3f - 2f * phase)
    return (normalWidth - (normalWidth - compactWidth) * ease).roundToInt()
}

/** Blend in from full width when SystemUI returns control partway through an interlude. */
internal fun interludeViewportInset(width: Int, requestedWidth: Int, ownedForMs: Long): Pair<Int, Boolean> {
    val phase = (ownedForMs.toFloat() / VIEWPORT_HANDOFF_MS).coerceIn(0f, 1f)
    val inset = (width - requestedWidth) / 2f
    return (inset * phase * phase * (3f - 2f * phase)).toInt() to (inset >= 1f && phase < 1f)
}

internal class CapsuleIntroDots {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(
        canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, density: Float,
        position: Long, duration: Long, isIntro: Boolean, color: Int, opacity: Float, offsetY: Float = 0f,
    ): Long? {
        if (right <= left || duration <= 0 || opacity <= 0f) return null
        val pulse = countdownDotScale(position, duration, isIntro)
        val widthLimit = (right - left) / dotSpan(DOT_MAX_SCALE)
        val radius = minOf(dotRadius(density, bottom - top), widthLimit) * pulse
        val center = (left + right) * 0.5f
        val centerY = (top + bottom) * 0.5f + offsetY
        paint.color = color
        repeat(3) { index ->
            paint.alpha = (255 * introDotFill(index, position, duration) * opacity).roundToInt()
            canvas.drawCircle(center + (index - 1) * radius * DOT_STEP_IN_RADII, centerY, radius, paint)
        }
        return 0L
    }
}
