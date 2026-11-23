package shop.terramoor.github.io.systemui

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

private const val INTRO_DOT_RADIUS_DP = 3.15f

internal fun introDotFill(index: Int, position: Long, end: Long): Float {
    val fill = ((position.toDouble() / end.coerceAtLeast(1L)) * 3.0 - index).coerceIn(0.0, 1.0).toFloat()
    return 0.24f + 0.76f * fill * fill * (3f - 2f * fill)
}

internal class CapsuleIntroDots {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(
        canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, density: Float,
        position: Long, end: Long, color: Int, opacity: Float,
    ): Long? {
        if (right <= left || end <= 0 || position >= end) return null
        val endFade = (end - position).toFloat() / minOf(180L, end).coerceAtLeast(1L)
        val pulse = (sin((position % 1600L) / 1600.0 * PI) * 0.12 + 1.0).toFloat()
        val radius = minOf(INTRO_DOT_RADIUS_DP * density, (right - left) / 10f, (bottom - top) / 5f) * pulse
        val center = (left + right) * 0.5f
        val centerY = (top + bottom) * 0.5f
        paint.color = color
        repeat(3) { index ->
            paint.alpha = (255 * introDotFill(index, position, end) * endFade.coerceIn(0f, 1f) * opacity).roundToInt()
            canvas.drawCircle(center + (index - 1) * radius * 3.25f, centerY, radius, paint)
        }
        return 0L
    }
}
