package shop.terramoor.github.io.systemui

import android.graphics.Canvas
import android.graphics.Paint
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

internal class CapsuleIntroDots {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(
        canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, density: Float,
        position: Long, duration: Long, isIntro: Boolean, color: Int, opacity: Float, offsetY: Float = 0f,
    ): Long? {
        if (right <= left || duration <= 0 || opacity <= 0f) return null
        val pulse = countdownDotScale(position, duration, isIntro)
        val widthLimit = (right - left) / (2f * (1f + DOT_STEP_IN_RADII) * DOT_MAX_SCALE)
        val radius = minOf(INTRO_DOT_RADIUS_DP * density, widthLimit, (bottom - top) / 5f) * pulse
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
