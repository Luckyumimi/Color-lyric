package shop.terramoor.github.io.systemui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import kotlin.math.ceil
import kotlin.math.floor

internal data class CapsuleCamera(val x: Float, val radiusX: Float)

/** Feathers both vertical edges of the camera gap without touching the capsule artwork. */
internal class CameraLyricMask {
    private val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val layer by lazy { RenderNode("TerraMoor lyrics") }
    private val shader by lazy { RuntimeShader(SOURCE) }
    private var gradientCamera: CapsuleCamera? = null
    private var gradientWidth = 0f
    private var effectCamera: CapsuleCamera? = null
    private var effectWidth = 0f
    private var effectBlur = 0f

    fun draw(
        canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float,
        camera: CapsuleCamera, featherWidth: Float, blur: Float, lyrics: (Canvas) -> Unit,
    ) {
        if (featherWidth <= 0f) {
            val save = canvas.save()
            try {
                canvas.clipOutRect(camera.x - camera.radiusX, top, camera.x + camera.radiusX, bottom)
                lyrics(canvas)
            } finally {
                canvas.restoreToCount(save)
            }
        } else if (blur > 0f && canvas.isHardwareAccelerated) {
            val x = floor(left).toInt()
            val y = floor(top).toInt()
            layer.setPosition(x, y, ceil(right).toInt(), ceil(bottom).toInt())
            val local = camera.copy(x = camera.x - x)
            if (local != effectCamera || featherWidth != effectWidth || blur != effectBlur) {
                shader.setFloatUniform("cameraX", local.x)
                shader.setFloatUniform("radius", local.radiusX)
                shader.setFloatUniform("feather", featherWidth)
                shader.setFloatUniform("blurRadius", blur)
                layer.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "ink"))
                effectCamera = local
                effectWidth = featherWidth
                effectBlur = blur
            }
            val recording = layer.beginRecording()
            try {
                recording.translate(-x.toFloat(), -y.toFloat())
                lyrics(recording)
            } finally {
                layer.endRecording()
            }
            canvas.drawRenderNode(layer)
        } else {
            if (camera != gradientCamera || featherWidth != gradientWidth) {
                val outer = camera.radiusX + featherWidth
                val start = featherWidth / (2f * outer)
                paint.shader = LinearGradient(
                    camera.x - outer, 0f, camera.x + outer, 0f,
                    intArrayOf(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT, Color.WHITE),
                    floatArrayOf(0f, start, 1f - start, 1f), Shader.TileMode.CLAMP,
                )
                gradientCamera = camera
                gradientWidth = featherWidth
            }
            val save = canvas.saveLayer(left, top, right, bottom, null)
            try {
                lyrics(canvas)
                canvas.drawRect(left, top, right, bottom, paint)
            } finally {
                canvas.restoreToCount(save)
            }
        }
    }

    private companion object {
        // The filter samples the premultiplied lyric layer with a small Gaussian kernel.
        // Pixels outside the transition keep the original lyric layer.
        const val SOURCE = """
            uniform shader ink;
            uniform float cameraX;
            uniform float radius;
            uniform float feather;
            uniform float blurRadius;

            half4 main(float2 p) {
                float distance = abs(p.x - cameraX) - radius;
                if (distance < 0.0) return half4(0.0);
                if (distance >= feather) return ink.eval(p);
                float visibility = distance / feather;
                float stepSize = blurRadius * (1.0 - visibility);
                float2 left = float2(p.x - stepSize, p.y);
                float2 right = float2(p.x + stepSize, p.y);
                float2 dy = float2(0.0, stepSize);
                half4 color = ink.eval(p) * 4.0;
                color += (ink.eval(left) + ink.eval(right) +
                          ink.eval(p + dy) + ink.eval(p - dy)) * 2.0;
                color += ink.eval(left + dy) + ink.eval(left - dy) +
                         ink.eval(right + dy) + ink.eval(right - dy);
                return color * half(visibility * 0.0625);
            }
        """
    }
}
