package shop.terramoor.github.io.systemui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import shop.terramoor.github.io.settings.CapsuleSettings

/** Paints inside the caller's capsule clip, extending the blur into the OEM width overshoot. */
internal class CapsuleArtworkBackground {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val crop = Rect()
    private val edgeSource = Rect()
    private val edgeDestination = RectF()

    fun draw(canvas: Canvas, bitmap: Bitmap, layout: RectF, reveal: RectF, dimAlpha: Int) {
        artworkCrop(bitmap, layout.width() / layout.height(), crop)
        paint.alpha = CapsuleSettings.ARTWORK_BACKGROUND_ALPHA
        canvas.drawBitmap(bitmap, crop, layout, paint)
        if (reveal.left < layout.left) {
            drawEdge(canvas, bitmap, crop.left, reveal.left, layout.left, layout)
        }
        if (reveal.right > layout.right) {
            drawEdge(canvas, bitmap, crop.right - 1, layout.right, reveal.right, layout)
        }
        paint.color = Color.argb(dimAlpha, 0, 0, 0)
        canvas.drawRect(reveal, paint)
    }

    private fun drawEdge(canvas: Canvas, bitmap: Bitmap, sourceX: Int, left: Float, right: Float, layout: RectF) {
        edgeSource.set(sourceX, crop.top, sourceX + 1, crop.bottom)
        edgeDestination.set(left, layout.top, right, layout.bottom)
        canvas.drawBitmap(bitmap, edgeSource, edgeDestination, paint)
    }
}
