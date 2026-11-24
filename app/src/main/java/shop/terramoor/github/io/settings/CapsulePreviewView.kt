package shop.terramoor.github.io.settings

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import shop.terramoor.github.io.lyrics.LyricPresentation
import shop.terramoor.github.io.systemui.ArtworkLyricTint
import shop.terramoor.github.io.systemui.CapsuleCamera
import shop.terramoor.github.io.systemui.CapsuleLyricStrip
import shop.terramoor.github.io.systemui.LyricInk
import shop.terramoor.github.io.systemui.MediaCapsulePreviewGeometry
import shop.terramoor.github.io.systemui.capsuleLyricsLeft
import shop.terramoor.github.io.systemui.capsuleLyricsRight
import shop.terramoor.github.io.systemui.blurArtwork
import shop.terramoor.github.io.systemui.artworkCrop
import kotlin.math.roundToInt
import kotlin.math.sin

/** Uses the same text, blur and timing renderer as the native capsule, with local sample lyrics. */
internal class CapsulePreviewView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val strip = CapsuleLyricStrip(density)
    private val tint = ArtworkLyricTint()
    private var ink = LyricInk.WHITE
    private val baseColor = Color.rgb(15, 16, 19)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val backgroundCrop = Rect()
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
    private var settings = CapsuleSettings()
    private var timeline = language.timeline(resources)
    private var presentation = timeline?.let { LyricPresentation(it, settings) }
    private var geometry = MediaCapsulePreviewGeometry(density, settings[CapsuleTuning.ISLAND_WIDTH])
    private var activeFrame = Int.MIN_VALUE
    private var startedAt = SystemClock.elapsedRealtime()
    private var previewVisible = false
    private var holdRedrawPending = false
    private val redrawAfterHold = Runnable {
        holdRedrawPending = false
        if (canAnimate()) invalidate()
    }

    fun setPreviewVisible(visible: Boolean) {
        if (visible == previewVisible) return
        previewVisible = visible
        if (visible) invalidate() else cancelHoldRedraw()
    }

    fun show(newSettings: CapsuleSettings, newLanguage: PreviewLanguage) {
        if (settings == newSettings && language == newLanguage) return
        val motionChanged = settings.motion != newSettings.motion
        val languageChanged = language != newLanguage
        val introChanged = settings.introDots != newSettings.introDots
        if (languageChanged || introChanged) {
            language = newLanguage
            timeline = language.timeline(resources, intro = newSettings.introDots)
            if (languageChanged) {
                blurredCover = null
                blurStrength = -1
            }
            startedAt = SystemClock.elapsedRealtime()
        }
        presentation = timeline?.let { LyricPresentation(it, newSettings) }
        if (motionChanged && !languageChanged && !introChanged) presentation?.nextBoundary(0)?.let {
            startedAt = SystemClock.elapsedRealtime() + 360L - it
        }
        if (presentation == null && newSettings.noLyricsMode != settings.noLyricsMode) startedAt = SystemClock.elapsedRealtime()
        if (settings[CapsuleTuning.ISLAND_WIDTH] != newSettings[CapsuleTuning.ISLAND_WIDTH]) {
            geometry = MediaCapsulePreviewGeometry(density, newSettings[CapsuleTuning.ISLAND_WIDTH])
        }
        settings = newSettings
        val cover = previewCover()
        ink = tint.resolve(cover, previewBackground(cover), settings,
            aspect = geometry.width / geometry.height, baseColor = baseColor)
        activeFrame = Int.MIN_VALUE
        cancelHoldRedraw()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        cancelHoldRedraw()
        val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
        val duration = PREVIEW_DURATION_MS + if (settings.introDots && presentation != null) PREVIEW_INTRO_MS else 0L
        val position = elapsed % duration
        val lyrics = presentation
        val index = lyrics?.indexAt(position) ?: -1
        if (activeFrame != index) {
            activeFrame = index
            val frame = if (lyrics == null) {
                language.trackInfo(resources).takeIf { settings.noLyricsMode == NoLyricsMode.TRACK }
            } else {
                val trackRows = { language.sampleTrack(resources) }
                val timed = lyrics.introAt(position, trackRows) ?: lyrics.frameAt(index, duration, trackRows)
                if (index == 0 && timed?.priorCountdown == null && elapsed >= PREVIEW_DURATION_MS) {
                    timed?.copy(priorRows = lyrics.frameAt(lyrics.lines.lastIndex, PREVIEW_DURATION_MS)?.rows)
                } else timed
            }
            strip.update(frame, settings, ink)
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
        canvas.drawColor(baseColor)
        val cover = previewCover()
        previewBackground(cover)?.let { background ->
            paint.alpha = CapsuleSettings.ARTWORK_BACKGROUND_ALPHA
            artworkCrop(background, pill.width() / pill.height(), backgroundCrop)
            canvas.drawBitmap(background, backgroundCrop, pill, paint)
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
        if (settings.showCover) canvas.drawBitmap(cover, null, coverRect, paint)
        canvas.restoreToCount(coverSave)
        val redrawDelay = strip.draw(canvas, capsuleLyricsLeft(coverRect.right, dp, settings), pill.top,
            capsuleLyricsRight(geometry.width, geometry.waveformLeft, dp, settings), pill.bottom,
            CapsuleCamera(geometry.cameraCenterX, geometry.cameraRadius), if (lyrics == null) elapsed else position,
            artworkArea = pill)
        paint.color = Color.BLACK
        canvas.drawCircle(geometry.cameraCenterX, geometry.cameraCenterY, geometry.cameraRadius, paint)
        paint.color = Color.WHITE
        if (settings.showWaveform) repeat(4) { bar ->
            val x = geometry.waveformLeft + geometry.artworkSize / 2f + (bar * 3 - 5) * dp
            val barHeight = (3.4f + 2.2f * sin(position / 165f + bar * 1.1f)) * dp
            canvas.drawRoundRect(x, halfHeight - barHeight, x + 1.5f * dp, halfHeight + barHeight, dp, dp, paint)
        }
        canvas.restoreToCount(save)
        if (!canAnimate()) return
        when {
            lyrics != null || settings.showWaveform || redrawDelay == 0L -> postInvalidateOnAnimation()
            redrawDelay != null -> holdRedrawPending = postDelayed(redrawAfterHold, redrawDelay)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (language == PreviewLanguage.NO_SYNC_LYRICS || settings.introDots) {
            activeFrame = Int.MIN_VALUE
            invalidate()
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) cancelHoldRedraw() else if (previewVisible) invalidate()
    }

    override fun onDetachedFromWindow() {
        cancelHoldRedraw()
        super.onDetachedFromWindow()
    }

    private fun canAnimate() = previewVisible && isAttachedToWindow && isShown && windowVisibility == VISIBLE

    private fun cancelHoldRedraw() {
        if (!holdRedrawPending) return
        removeCallbacks(redrawAfterHold)
        holdRedrawPending = false
    }

    private fun alpha(percent: Int) = (percent * 2.55f).roundToInt()

    private fun previewCover(): Bitmap =
        if (language == PreviewLanguage.JAPANESE) japaneseCover ?: fallbackCover else fallbackCover

    private fun previewBackground(cover: Bitmap): Bitmap? {
        if (!settings.artwork) return null
        val strength = settings[CapsuleTuning.BLUR]
        if (blurStrength != strength) {
            blurStrength = strength
            blurredCover = blurArtwork(cover, strength)
        }
        return blurredCover ?: cover
    }

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
