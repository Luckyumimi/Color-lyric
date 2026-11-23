package shop.terramoor.github.io.systemui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
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
import kotlin.math.abs
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
    private var trackInfoShown = false
    private val trackInfoMarquee = TrackInfoDisplayMarquee()
    private val redrawTrackInfo = Runnable { if (capsule.isShown) capsule.invalidate() }
    private var displaySettings: CapsuleSettings? = null
    private var sizedParams: ViewGroup.LayoutParams? = null
    private var nativeWidth = 0
    private val nativeMinimumWidth = capsule.minimumWidth
    private var appliedWidth: Int? = null
    private val artworkBackground = CapsuleArtworkBackground()
    private val area = RectF()
    private val artworkArea = RectF()
    private val clip = Path()
    private val location = IntArray(2)

    val hasCustomContent: Boolean get() = displayLine != null || artwork != null

    fun render(
        line: LyricFrame?, settings: CapsuleSettings, artwork: Bitmap?,
        clock: PlaybackClock? = null, ink: LyricInk = LyricInk.WHITE,
    ) {
        val showingTrackInfo = line?.isTrackInfo == true && capsule.isShown
        val trackInfoVisibilityChanged = trackInfoShown != showingTrackInfo
        trackInfoShown = showingTrackInfo
        if (displayLine != line || trackInfoVisibilityChanged) {
            capsule.removeCallbacks(redrawTrackInfo)
            trackInfoMarquee.reset()
        }
        val changed = displayLine != line || displaySettings != settings || this.artwork !== artwork ||
            this.clock != clock || this.ink != ink || trackInfoVisibilityChanged
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

    fun paintDiagnostics() = strip.paintDiagnostics()

    fun draw(canvas: Canvas, animatedWidth: Int?): String {
        if (!capsule.isShown) return "CAPSULE_HIDDEN"
        if (displayLine == null && artwork == null) return "NO_LYRIC_OR_ARTWORK"
        val width = capsule.width
        val height = capsule.height.toFloat()
        if (width <= 0 || height <= 0) return "EMPTY_DRAWING_AREA"
        val drawingWidth = animatedWidth ?: width
        // Clip to the OEM drawable bounds without using the clip to resize the artwork or lyrics.
        val shapeLeft = if (animatedWidth != null) nativeCapsuleClipLeft(capsule.measuredWidth, drawingWidth) else 0
        area.set(shapeLeft.toFloat(), 0f, (shapeLeft + drawingWidth).toFloat(), height)
        artworkArea.set(0f, 0f, width.toFloat(), height)
        clip.rewind()
        val radius = area.height() / 2f
        clip.addRoundRect(area, radius, radius, Path.Direction.CW)
        val save = canvas.save()
        try {
            canvas.clipPath(clip)
            artwork?.takeUnless { it.isRecycled }?.let {
                artworkBackground.draw(canvas, it, artworkArea, area, dimAlpha)
            }
            val line = displayLine ?: return "ARTWORK_ONLY"
            val settings = displaySettings ?: return "ARTWORK_ONLY"
            // Display cutout and getLocationOnScreen share display coordinates. Insets
            // delivered to the native capsule may already have consumed the cutout.
            val camera = capsule.topDisplayCutout()
            capsule.getLocationOnScreen(location)
            val scaleX = capsule.scaleX.takeIf { it > 0f } ?: 1f
            // Only the horizontal span of the display cutout defines the lyric gap.
            val lens = camera?.let {
                CapsuleCamera(
                    (it.exactCenterX() - location[0]) / scaleX,
                    it.width() / (2f * scaleX),
                )
            }
            val iconStart = layoutLeft(target.artwork, 0f)
            val waveformLeft = layoutLeft(target.waveform, width.toFloat()).coerceIn(0f, width.toFloat())
            val left = capsuleLyricsLeft(iconStart + target.artwork.width, density, settings)
            val right = capsuleLyricsRight(width.toFloat(), waveformLeft, density, settings)
            val timing = clock
            val now = SystemClock.elapsedRealtime()
            val position = if (line.isTrackInfo) now else timing?.position(now) ?: line.startMs
            val settled = animatedWidth == null && abs(width - capsule.measuredWidth) <= 1
            val redrawDelay = strip.draw(
                canvas, left, area.top, right, area.bottom,
                lens, position, artworkArea = artworkArea,
                trackInfoMarquee = trackInfoMarquee, trackInfoSettled = settled,
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

    private fun layoutLeft(view: View, fallback: Float): Float {
        if (view.width <= 0) return fallback
        // The OEM translates the controls independently while opening or replacing the content.
        // Those translations must not move the lyric viewport or change its scroll distance.
        var left = 0
        var child = view
        while (child !== capsule) {
            val parent = child.parent as? View ?: return fallback
            left += child.left - parent.scrollX
            child = parent
        }
        return left.toFloat()
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
