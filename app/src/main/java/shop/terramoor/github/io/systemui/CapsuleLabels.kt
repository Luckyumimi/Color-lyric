package shop.terramoor.github.io.systemui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import shop.terramoor.github.io.lyrics.LyricFrame
import shop.terramoor.github.io.lyrics.PlaybackClock
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.CapsuleTuning
import kotlin.math.roundToInt

/** Draws lyrics over the OEM capsule surface and leaves the OEM controls in their own views. */
internal class CapsuleLabels(val target: NativeContract.Target) {
    private val capsule = target.capsule
    private val density = capsule.resources.displayMetrics.density
    private val strip = CapsuleLyricStrip(density)
    private val filler = View(capsule.context).apply {
        visibility = View.INVISIBLE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var artwork: Bitmap? = null
    private var artworkAlpha = 0
    private var dimAlpha = 0
    private var displayLine: LyricFrame? = null
    private var clock: PlaybackClock? = null
    private var displaySettings: CapsuleSettings? = null
    private var sizedParams: ViewGroup.LayoutParams? = null
    private var nativeWidth = 0
    private val nativeMinimumWidth = capsule.minimumWidth
    private var appliedWidth: Int? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val area = RectF()
    private val clip = Path()
    private val crop = Rect()
    private val location = IntArray(2)
    private val offsets = IntArray(2)

    fun render(line: LyricFrame?, settings: CapsuleSettings, artwork: Bitmap?, clock: PlaybackClock? = null) {
        val changed = displayLine != line || displaySettings != settings || this.artwork !== artwork || this.clock != clock
        this.clock = clock
        displayLine = line
        displaySettings = settings
        this.artwork = artwork
        artworkAlpha = alpha(settings[CapsuleTuning.ARTWORK_OPACITY])
        dimAlpha = alpha(settings[CapsuleTuning.ARTWORK_DIM])
        if (changed) {
            strip.update(line, settings)
            capsule.invalidate()
        }
        // Keep the verified slot measured across gaps and card transitions. A single invisible
        // child lets the native container measure its own width, including reserved icon space.
        updateCapsuleWidth(capsuleWidthPixels(settings[CapsuleTuning.ISLAND_WIDTH], density))
        setFillerWidth(capsuleWidthPixels(CapsuleTuning.ISLAND_WIDTH.range.last / 2, density))
    }

    private fun cameraBounds(): Rect? =
        capsule.display?.cutout?.boundingRectTop?.takeUnless { it.isEmpty }
            ?: capsule.rootWindowInsets?.displayCutout?.boundingRectTop?.takeUnless { it.isEmpty }

    fun draw(canvas: Canvas, drawingWidth: Int) {
        if (!capsule.isShown) return
        if (displayLine == null && artwork == null) return
        if (drawingWidth <= 0 || capsule.height <= 0) return
        // Shape and timing come from the OEM animator. Keep the text viewport stable while the shape is clipped.
        val shapeLeft = (capsule.width - drawingWidth) / 2f
        area.set(shapeLeft, 0f, shapeLeft + drawingWidth, capsule.height.toFloat())
        clip.rewind()
        val radius = area.height() / 2f
        clip.addRoundRect(area, radius, radius, Path.Direction.CW)
        val save = canvas.save()
        try {
            canvas.clipPath(clip)
            drawArtwork(canvas)
            if (displayLine == null) return
            // Display cutout and getLocationOnScreen share display coordinates. Insets
            // delivered to the native capsule may already have consumed the cutout.
            val camera = cameraBounds()
            capsule.getLocationOnScreen(location)
            val scaleX = capsule.scaleX.takeIf { it > 0f } ?: 1f
            // Only the horizontal span of the display cutout defines the lyric gap.
            val lens = camera?.let {
                CapsuleCamera(
                    (it.exactCenterX() - location[0]) / scaleX,
                    it.width() / (2f * scaleX),
                )
            }
            val iconStart = viewLeft(target.artwork, 0f)
            val waveEnd = viewLeft(target.waveform, capsule.width.toFloat())
            val left = capsuleLyricsLeft(iconStart + target.artwork.width, density)
            val right = capsuleLyricsRight(capsule.width.toFloat(), waveEnd, density)
            val timing = clock
            val position = timing?.position(SystemClock.elapsedRealtime()) ?: displayLine?.line?.startMs ?: 0L
            val animate = strip.draw(
                canvas, left, area.top, right, area.bottom,
                lens, position,
            )
            if (animate && timing?.moving == true && timing.speed > 0f) {
                capsule.postInvalidateOnAnimation()
            }
        } finally {
            canvas.restoreToCount(save)
        }
    }

    private fun viewLeft(view: View, fallback: Float): Float {
        if (!view.isShown || view.width <= 0) return fallback
        view.getLocationOnScreen(offsets)
        val scale = capsule.scaleX.takeIf { it > 0f } ?: 1f
        return (offsets[0] - location[0]) / scale
    }

    private fun drawArtwork(canvas: Canvas) {
        val bitmap = artwork?.takeUnless { it.isRecycled } ?: return
        val aspect = area.width() / area.height()
        val sourceWidth = minOf(bitmap.width, (bitmap.height * aspect).roundToInt().coerceAtLeast(1))
        val sourceHeight = minOf(bitmap.height, (sourceWidth / aspect).roundToInt().coerceAtLeast(1))
        val left = (bitmap.width - sourceWidth) / 2
        val top = (bitmap.height - sourceHeight) / 2
        crop.set(left, top, left + sourceWidth, top + sourceHeight)
        paint.alpha = artworkAlpha
        canvas.drawBitmap(bitmap, crop, area, paint)
        paint.color = Color.argb(dimAlpha, 0, 0, 0)
        canvas.drawRect(area, paint)
    }

    private fun updateCapsuleWidth(requestedWidth: Int?) {
        val params = capsule.layoutParams ?: return
        if (params !== sizedParams || params.width != appliedWidth) {
            sizedParams = params
            nativeWidth = params.width
            appliedWidth = null
        }
        if (requestedWidth == null && appliedWidth == null) {
            if (capsule.minimumWidth != nativeMinimumWidth) capsule.minimumWidth = nativeMinimumWidth
            return
        }
        val width = if (requestedWidth == null) nativeWidth
        else if (nativeWidth >= 0) minOf(nativeWidth, requestedWidth)
        else requestedWidth
        val minimum = if (requestedWidth == null) nativeMinimumWidth else width
        if (capsule.minimumWidth != minimum) capsule.minimumWidth = minimum
        if (params.width != width) {
            params.width = width
            capsule.layoutParams = params
        }
        appliedWidth = width.takeUnless { it == nativeWidth }
    }

    private fun setFillerWidth(width: Int) {
        val frame = target.title.frame
        if (filler.parent == null) frame.addView(filler, FrameLayout.LayoutParams(width, 1, Gravity.START))
        else if (filler.layoutParams.width != width) {
            filler.layoutParams = FrameLayout.LayoutParams(width, 1, Gravity.START)
        }
    }

    private fun clearFiller() {
        val frame = target.title.frame
        if (filler.parent === frame) frame.removeView(filler)
    }

    fun close() {
        updateCapsuleWidth(null)
        clearFiller()
        displayLine = null
        artwork = null
        capsule.invalidate()
    }

    private fun alpha(percent: Int): Int = (percent * 255 + 50) / 100
}
