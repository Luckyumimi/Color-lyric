package shop.terramoor.github.io.systemui

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.roundToInt

/** Adds weighted premultiplied layers before compositing their mixture onto the native view. */
internal class CapsuleLayerBlend {
    private val add = Paint().apply { blendMode = BlendMode.PLUS }

    fun <T> draw(canvas: Canvas, bounds: RectF, layers: Map<T, Float>, drawLayer: (T) -> Unit) {
        val mix = canvas.saveLayer(bounds, null)
        try {
            for ((value, opacity) in layers) {
                add.alpha = (opacity * 255f).roundToInt()
                if (add.alpha == 0) continue
                val layer = canvas.saveLayer(bounds, add)
                try {
                    drawLayer(value)
                } finally {
                    canvas.restoreToCount(layer)
                }
            }
        } finally {
            canvas.restoreToCount(mix)
        }
    }
}
