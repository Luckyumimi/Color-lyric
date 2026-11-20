package shop.terramoor.github.io.systemui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader

/** Fades only the lyric layer at the outer edges, leaving native controls and artwork intact. */
internal class OuterLyricMask {
    private val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var lastLeft = Float.NaN
    private var lastRight = Float.NaN
    private var lastFeather: OuterLyricFeather? = null
    private var lastLeftAlpha = -1
    private var lastRightAlpha = -1

    fun draw(
        canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float,
        feather: OuterLyricFeather, fade: EdgeFade, lyrics: (Canvas) -> Unit,
    ) {
        val leftAlpha = (255f * (1f - fade.left)).toInt()
        val rightAlpha = (255f * (1f - fade.right)).toInt()
        if (right <= left || bottom <= top) return
        if (leftAlpha == 255 && rightAlpha == 255) {
            drawClipped(canvas, left, top, right, bottom, lyrics)
            return
        }
        if (left != lastLeft || right != lastRight || feather != lastFeather ||
            leftAlpha != lastLeftAlpha || rightAlpha != lastRightAlpha
        ) {
            val width = right - left
            val rightStart = 1f - (feather.trailingInset / width)
            val rightSlope = feather.right / width
            val rightEnd = 1f - feather.rightGuard / width
            // Approximate smoothstep with fixed stops, then stay transparent before the clip.
            // A glyph crossing the viewport edge must not leave a visible vertical stroke.
            fun alphaAt(fraction: Float) = Color.argb(
                (rightAlpha + (255 - rightAlpha) * fraction + 0.5f).toInt(), 255, 255, 255,
            )
            paint.shader = LinearGradient(
                left, 0f, right, 0f,
                intArrayOf(
                    Color.argb(leftAlpha, 255, 255, 255), Color.WHITE, Color.WHITE,
                    alphaAt(0.957f), alphaAt(0.844f), alphaAt(0.5f),
                    alphaAt(0.156f), alphaAt(0.043f), alphaAt(0f), alphaAt(0f),
                ),
                floatArrayOf(
                    0f, feather.left / width, rightStart,
                    rightStart + rightSlope * 0.125f, rightStart + rightSlope * 0.25f,
                    rightStart + rightSlope * 0.5f, rightStart + rightSlope * 0.75f,
                    rightStart + rightSlope * 0.875f, rightEnd, 1f,
                ),
                Shader.TileMode.CLAMP,
            )
            lastLeft = left
            lastRight = right
            lastFeather = feather
            lastLeftAlpha = leftAlpha
            lastRightAlpha = rightAlpha
        }
        val save = canvas.saveLayer(left, top, right, bottom, null)
        try {
            // Keep the glyph clip inside the zero-alpha band. The mask fills the
            // whole layer, including any extra pixels from fractional clip rounding.
            val inkRight = if (rightAlpha == 0) right - feather.rightGuard / 2f else right
            drawClipped(canvas, left, top, inkRight, bottom, lyrics)
            canvas.drawPaint(paint)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    private fun drawClipped(
        canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float,
        lyrics: (Canvas) -> Unit,
    ) {
        val save = canvas.save()
        try {
            if (canvas.clipRect(left, top, right, bottom)) lyrics(canvas)
        } finally {
            canvas.restoreToCount(save)
        }
    }
}
