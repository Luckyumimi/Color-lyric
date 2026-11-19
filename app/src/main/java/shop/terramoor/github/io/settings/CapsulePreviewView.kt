package shop.terramoor.github.io.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import shop.terramoor.github.io.systemui.CapsuleCamera
import shop.terramoor.github.io.systemui.CapsuleLyricStrip
import shop.terramoor.github.io.systemui.MediaCapsulePreviewGeometry
import shop.terramoor.github.io.systemui.capsuleLyricsLeft
import shop.terramoor.github.io.systemui.capsuleLyricsRight
import shop.terramoor.github.io.systemui.blurArtwork
import kotlin.math.roundToInt
import kotlin.math.sin

/** Uses the same text, blur and timing renderer as the native capsule, with local sample lyrics. */
internal class CapsulePreviewView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val strip = CapsuleLyricStrip(density)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pill = RectF()
    private val coverRect = RectF()
    private val clip = Path()
    private val coverClip = Path()
    private val fallbackCover by lazy(::createCover)
    private val japaneseCover by lazy {
        BitmapFactory.decodeResource(resources, PreviewLanguage.JAPANESE.cover)
    }
    private var language = PreviewLanguage.JAPANESE
    private var blurredCover: Bitmap? = null
    private var blurStrength = -1
    private var sampleFrames = language.frames(resources)
    private var settings = CapsuleSettings()
    private var geometry = MediaCapsulePreviewGeometry(density, settings[CapsuleTuning.ISLAND_WIDTH])
    private var activeFrame = -1
    private var startedAt = SystemClock.elapsedRealtime()
    private var previewVisible = false

    fun setPreviewVisible(visible: Boolean) {
        if (visible == previewVisible) return
        previewVisible = visible
        if (visible) invalidate()
    }

    fun show(newSettings: CapsuleSettings, newLanguage: PreviewLanguage) {
        if (settings == newSettings && language == newLanguage) return
        if (language != newLanguage) {
            language = newLanguage
            sampleFrames = language.frames(resources)
            blurredCover = null
            blurStrength = -1
            startedAt = SystemClock.elapsedRealtime()
        } else if (settings.motion != newSettings.motion) {
            startedAt = SystemClock.elapsedRealtime() + 360L - sampleFrames[1].displayStartMs
        }
        if (settings[CapsuleTuning.ISLAND_WIDTH] != newSettings[CapsuleTuning.ISLAND_WIDTH]) {
            geometry = MediaCapsulePreviewGeometry(density, newSettings[CapsuleTuning.ISLAND_WIDTH])
        }
        settings = newSettings
        activeFrame = -1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
        val position = elapsed % PREVIEW_DURATION_MS
        val index = sampleFrames.indexOfLast { it.displayStartMs <= position }
        if (activeFrame != index) {
            activeFrame = index
            val frame = sampleFrames[index]
            val firstFrame = index == 0 && elapsed < PREVIEW_DURATION_MS
            strip.update(if (firstFrame) frame.copy(priorLine = null) else frame, settings)
        }
        val dp = density
        val availableWidth = width - 24 * dp
        if (availableWidth <= 0f || height <= 0) return
        val scale = minOf(1f, availableWidth / geometry.width, height / geometry.height)
        val halfHeight = geometry.height / 2f
        pill.set(0f, 0f, geometry.width, geometry.height)
        clip.rewind()
        clip.addRoundRect(pill, halfHeight, halfHeight, Path.Direction.CW)
        val save = canvas.save()
        canvas.translate((width - geometry.width * scale) / 2f, (height - geometry.height * scale) / 2f)
        canvas.scale(scale, scale)
        canvas.clipPath(clip)
        canvas.drawColor(Color.rgb(15, 16, 19))
        val cover = if (language == PreviewLanguage.JAPANESE) japaneseCover ?: fallbackCover else fallbackCover
        if (settings.artwork) {
            val strength = settings[CapsuleTuning.BLUR]
            if (blurStrength != strength) {
                blurStrength = strength
                blurredCover = blurArtwork(cover, strength)
            }
            paint.alpha = alpha(settings[CapsuleTuning.ARTWORK_OPACITY])
            canvas.drawBitmap(blurredCover ?: cover, null, pill, paint)
            paint.color = Color.BLACK
            paint.alpha = alpha(settings[CapsuleTuning.ARTWORK_DIM])
            canvas.drawRect(pill, paint)
        }
        coverRect.set(geometry.artworkLeft, halfHeight - geometry.artworkSize / 2f,
            geometry.artworkLeft + geometry.artworkSize, halfHeight + geometry.artworkSize / 2f)
        paint.alpha = 255
        coverClip.rewind()
        coverClip.addOval(coverRect, Path.Direction.CW)
        val coverSave = canvas.save()
        canvas.clipPath(coverClip)
        canvas.drawBitmap(cover, null, coverRect, paint)
        canvas.restoreToCount(coverSave)
        strip.draw(canvas, capsuleLyricsLeft(coverRect.right, dp), pill.top,
            capsuleLyricsRight(geometry.width, geometry.waveformLeft, dp), pill.bottom,
            CapsuleCamera(geometry.cameraCenterX, geometry.cameraRadius), position)
        paint.color = Color.BLACK
        canvas.drawCircle(geometry.cameraCenterX, geometry.cameraCenterY, geometry.cameraRadius, paint)
        paint.color = Color.WHITE
        repeat(4) { bar ->
            val x = geometry.waveformLeft + geometry.artworkSize / 2f + (bar * 3 - 5) * dp
            val barHeight = (3.4f + 2.2f * sin(position / 165f + bar * 1.1f)) * dp
            canvas.drawRoundRect(x, halfHeight - barHeight, x + 1.5f * dp, halfHeight + barHeight, dp, dp, paint)
        }
        canvas.restoreToCount(save)
        if (previewVisible && isShown && windowVisibility == VISIBLE) postInvalidateOnAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate()
    }

    private fun alpha(percent: Int) = (percent * 2.55f).roundToInt()

    private fun createCover(): Bitmap {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG)
        ink.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(),
            intArrayOf(0xFF353084.toInt(), 0xFFAD537E.toInt(), 0xFFF6A254.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), ink)
        ink.shader = null
        ink.color = 0xFFFFD8A0.toInt()
        canvas.drawCircle(65f, 38f, 15f, ink)
        ink.color = 0xFF25305B.toInt()
        val ridge = Path().apply {
            moveTo(0f, 78f)
            lineTo(28f, 52f)
            lineTo(55f, 72f)
            lineTo(96f, 54f)
            lineTo(96f, 96f)
            lineTo(0f, 96f)
            close()
        }
        canvas.drawPath(ridge, ink)
        return bitmap
    }
}
