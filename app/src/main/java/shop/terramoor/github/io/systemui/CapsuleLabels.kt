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
    private val coverVisibility = NativeSlotVisibility(target.artwork)
    private val waveformVisibility = NativeSlotVisibility(target.waveform)
    private val filler = View(capsule.context).apply {
        visibility = View.INVISIBLE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var artwork: Bitmap? = null
    private var ink = LyricInk.WHITE
    private var dimAlpha = 0
    private var displayLine: LyricFrame? = null
    private var clock: PlaybackClock? = null
    private var trackInfoShownAt = 0L
    private val redrawTrackInfo = Runnable { if (capsule.isShown) capsule.invalidate() }
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

    fun render(
        line: LyricFrame?, settings: CapsuleSettings, artwork: Bitmap?,
        clock: PlaybackClock? = null, ink: LyricInk = LyricInk.WHITE,
    ) {
        if (displayLine != line) {
            capsule.removeCallbacks(redrawTrackInfo)
            if (line?.isTrackInfo == true) trackInfoShownAt = SystemClock.elapsedRealtime()
        }
        val changed = displayLine != line || displaySettings != settings || this.artwork !== artwork ||
            this.clock != clock || this.ink != ink
        this.clock = clock
        displayLine = line
        displaySettings = settings
        enforceNativeVisibility()
        this.artwork = artwork
        this.ink = ink
        dimAlpha = alpha(settings[CapsuleTuning.ARTWORK_DIM])
        if (changed) {
            strip.update(line, settings, ink)
            capsule.invalidate()
        }
        maintainNativeSizing()
    }

    fun maintainNativeSizing() {
        val settings = displaySettings ?: return
        // Keep the verified slot measured across gaps and card transitions. A single invisible
        // child lets the native container measure its own width, including reserved icon space.
        updateCapsuleWidth(capsuleWidthPixels(settings[CapsuleTuning.ISLAND_WIDTH], density))
        setFillerWidth(capsuleWidthPixels(CapsuleTuning.ISLAND_WIDTH.range.last / 2, density))
    }

    private fun cameraBounds(): Rect? =
        capsule.display?.cutout?.boundingRectTop?.takeUnless { it.isEmpty }
            ?: capsule.rootWindowInsets?.displayCutout?.boundingRectTop?.takeUnless { it.isEmpty }

    fun paintDiagnostics() = strip.paintDiagnostics()

    fun draw(canvas: Canvas, drawingWidth: Int): String {
        if (!capsule.isShown) return "CAPSULE_HIDDEN"
        if (displayLine == null && artwork == null) return "NO_LYRIC_OR_ARTWORK"
        if (drawingWidth <= 0 || capsule.height <= 0) return "EMPTY_DRAWING_AREA"
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
            val line = displayLine ?: return "ARTWORK_ONLY"
            val settings = displaySettings ?: return "ARTWORK_ONLY"
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
            val width = capsule.width.toFloat()
            val waveformLeft = viewLeft(target.waveform, width).coerceIn(0f, width)
            val left = capsuleLyricsLeft(iconStart + target.artwork.width, density, settings)
            val right = capsuleLyricsRight(width, waveformLeft, density, settings)
            val timing = clock
            val now = SystemClock.elapsedRealtime()
            val position = if (line.isTrackInfo) now - trackInfoShownAt else timing?.position(now) ?: line.startMs
            val redrawDelay = strip.draw(
                canvas, left, area.top, right, area.bottom,
                lens, position, artworkArea = area,
            )
            if (line.isTrackInfo) {
                capsule.removeCallbacks(redrawTrackInfo)
                if (capsule.windowVisibility == View.VISIBLE && redrawDelay != null) {
                    if (redrawDelay == 0L) capsule.postInvalidateOnAnimation()
                    else capsule.postDelayed(redrawTrackInfo, redrawDelay)
                }
            } else if (redrawDelay == 0L && timing?.moving == true && timing.speed > 0f) {
                capsule.postInvalidateOnAnimation()
            }
            return "LYRICS_DRAWN"
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
        artworkCrop(bitmap, area.width() / area.height(), crop)
        paint.alpha = CapsuleSettings.ARTWORK_BACKGROUND_ALPHA
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

    fun enforceNativeVisibility() {
        val settings = displaySettings ?: return
        coverVisibility.apply(!settings.showCover)
        waveformVisibility.apply(!settings.showWaveform)
    }

    fun close() {
        capsule.removeCallbacks(redrawTrackInfo)
        coverVisibility.restore()
        waveformVisibility.restore()
        updateCapsuleWidth(null)
        clearFiller()
        displayLine = null
        artwork = null
        capsule.invalidate()
    }

    private fun alpha(percent: Int): Int = (percent * 255 + 50) / 100
}
