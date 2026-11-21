package shop.terramoor.github.io.systemui

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

internal fun artworkBitmap(drawable: Drawable?): Bitmap? {
    val bitmapDrawable = when (drawable) {
        is BitmapDrawable -> drawable
        is AdaptiveIconDrawable -> drawable.foreground as? BitmapDrawable
        else -> null
    }
    return bitmapDrawable?.bitmap?.takeUnless { it.isRecycled }
}

internal fun artworkCrop(bitmap: Bitmap, aspect: Float, out: Rect) {
    val width = minOf(bitmap.width, (bitmap.height * aspect).roundToInt().coerceAtLeast(1))
    val height = minOf(bitmap.height, (width / aspect).roundToInt().coerceAtLeast(1))
    val left = (bitmap.width - width) / 2
    val top = (bitmap.height - height) / 2
    out.set(left, top, left + width, top + height)
}

/** The caller supplies a bounded thumbnail and caches the result by source and strength. */
internal fun blurArtwork(source: Bitmap, strength: Int): Bitmap {
    val readable = if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false) ?: return source
    } else source
    if (strength <= 0) return readable
    val width = readable.width
    val height = readable.height
    val pixels = IntArray(width * height)
    val scratch = IntArray(pixels.size)
    readable.getPixels(pixels, 0, width, 0, 0, width, height)
    val radius = (strength * maxOf(width, height) / 128f).roundToInt().coerceAtLeast(1)
    repeat(3) {
        boxPass(pixels, scratch, width, height, 1, width, radius)
        boxPass(scratch, pixels, height, width, width, 1, radius)
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

private fun boxPass(input: IntArray, output: IntArray, length: Int, lines: Int,
                    pixelStride: Int, lineStride: Int, radius: Int) {
    val divisor = 2 * radius + 1
    for (line in 0 until lines) {
        val start = line * lineStride
        var a = 0
        var r = 0
        var g = 0
        var b = 0
        for (offset in -radius..radius) {
            val color = input[start + offset.coerceIn(0, length - 1) * pixelStride]
            a += color ushr 24
            r += (color ushr 16) and 255
            g += (color ushr 8) and 255
            b += color and 255
        }
        for (x in 0 until length) {
            output[start + x * pixelStride] = (a / divisor shl 24) or
                (r / divisor shl 16) or (g / divisor shl 8) or (b / divisor)
            val entering = input[start + (x + radius + 1).coerceAtMost(length - 1) * pixelStride]
            val leaving = input[start + (x - radius).coerceAtLeast(0) * pixelStride]
            a += (entering ushr 24) - (leaving ushr 24)
            r += ((entering ushr 16) and 255) - ((leaving ushr 16) and 255)
            g += ((entering ushr 8) and 255) - ((leaving ushr 8) and 255)
            b += (entering and 255) - (leaving and 255)
        }
    }
}
