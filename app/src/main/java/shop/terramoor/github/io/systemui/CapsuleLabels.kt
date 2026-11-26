package shop.terramoor.github.io.systemui

import android.animation.ValueAnimator
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
import shop.terramoor.github.io.settings.LineMotion
import kotlin.math.abs
import kotlin.math.roundToInt

/** Draws lyrics over the OEM capsule surface and leaves the OEM controls in their own views. */
internal class CapsuleLabels(
    val target: NativeContract.Target,
    modulePackage: String = target.capsule.context.packageName,
    onFontFailure: (Throwable) -> Unit = {},
) {
    private val capsule = target.capsule
    private val density = capsule.resources.displayMetrics.density
    private val japaneseFont: JapaneseLyricFont = JapaneseLyricFont(capsule.context, modulePackage, onFontFailure) {
        displaySettings?.let { strip.update(displayLine, it, ink); capsule.invalidate() }
    }
    private val strip: CapsuleLyricStrip = CapsuleLyricStrip(density, japaneseFont::typeface).apply {
        customTypeface = japaneseFont::customTypeface
    }
    private val coverVisibility = NativeSlotVisibility(target.artwork)
    private val waveformVisibility = NativeSlotVisibility(target.waveform)
    private val filler = View(capsule.context).apply {
        visibility = View.INVISIBLE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val backgrounds = CapsuleColorTransition<Bitmap?>(null)
    private val inks = CapsuleColorTransition(LyricInk.WHITE)
    private val layerBlend = CapsuleLayerBlend()
    private var requestedArtwork: Bitmap? = null
    private var requestedInk = LyricInk.WHITE
    private var artworkHoldUntil = 0L
    private var inkHoldUntil = 0L
    private var ink = LyricInk.WHITE
    private var dimAlpha = 0
    private var displayLine: LyricFrame? = null
    private var displayTrackIdentity: Any? = null
    private var clock: PlaybackClock? = null
    private var trackInfoShown = false
    private val trackInfoMarquee = TrackInfoDisplayMarquee()
    private val redrawContent = Runnable { if (capsule.isShown) capsule.invalidate() }
    private var displaySettings: CapsuleSettings? = null
    private var sizedParams: ViewGroup.LayoutParams? = null
    private var nativeWidth = 0
    private var nativeMinimumWidth = capsule.minimumWidth
    private var appliedWidth: Int? = null
    private val viewport = CapsuleVisualViewport(target)
    private var viewportStartedAt: Long? = null
    private var viewportPosition: Long? = null
    private val artworkBackground = CapsuleArtworkBackground()
    private val area = RectF()
    private val artworkArea = RectF()
    private val clip = Path()
    private val location = IntArray(2)

    val hasCustomContent: Boolean get() = displayLine != null || backgrounds.needsBackground()
    val hasInterludeViewport: Boolean get() = displayLine?.countdown?.isIntro == false
    val viewportWidth: Int get() = (capsule.width - viewport.inset * 2).coerceAtLeast(0)
    val hasCompactViewport: Boolean get() = viewport.inset > 0
    val coverAnchorX: Float? get() = viewport.coverAnchorX
    val shouldReserveCompactStatusBarSpace: Boolean get() {
        val countdown = displayLine?.countdown?.takeUnless { it.isIntro } ?: return false
        val position = viewportPosition ?: return false
        return viewport.inset > 0 && countdown.endMs - position > INTERLUDE_REOPEN_MS
    }
    var nativeWhilePaused = false
        private set

    fun render(
        frame: LyricFrame?, settings: CapsuleSettings, artwork: Bitmap?,
        clock: PlaybackClock? = null, ink: LyricInk = LyricInk.WHITE, trackIdentity: Any? = null, tintArtworkPending: Boolean = false,
    ) {
        if (displaySettings == null) nativeMinimumWidth = capsule.minimumWidth
        nativeWhilePaused = false
        val line = if (frame != null && !frame.isTrackInfo && frame.countdown == null &&
            trackIdentity != null && trackIdentity === displayTrackIdentity &&
            clock?.moving == true && settings.motion != LineMotion.NONE
        ) {
            val position = clock.position(SystemClock.elapsedRealtime())
            val outgoing = displayLine
            when {
                outgoing?.isTrackInfo == true && trackInfoShown -> frame.copy(
                    priorRows = outgoing.rows, priorCountdown = null,
                    priorTrackProgress = trackInfoMarquee.progress, motionStartMs = position,
                )
                outgoing?.priorTrackProgress != null && outgoing.displayStartMs == frame.displayStartMs &&
                    position in outgoing.motionStartMs until (outgoing.motionStartMs + LYRIC_MOTION_DURATION_MS) -> frame.copy(
                        priorRows = outgoing.priorRows, priorCountdown = null,
                        priorTrackProgress = outgoing.priorTrackProgress, motionStartMs = outgoing.motionStartMs,
                    )
                else -> frame
            }
        } else frame
        val showingTrackInfo = line?.isTrackInfo == true && capsule.isShown
        val trackInfoVisibilityChanged = trackInfoShown != showingTrackInfo
        trackInfoShown = showingTrackInfo
        if (displayLine != line || trackInfoVisibilityChanged) {
            capsule.removeCallbacks(redrawContent)
            trackInfoMarquee.reset()
        }
        val changed = displayLine != line || displaySettings != settings || requestedArtwork !== artwork ||
            this.clock != clock || requestedInk != ink || trackInfoVisibilityChanged
        if (displayLine?.countdown != line?.countdown || displayTrackIdentity !== trackIdentity ||
                displaySettings?.showCover != settings.showCover || displaySettings?.showWaveform != settings.showWaveform ||
                displaySettings?.get(CapsuleTuning.ISLAND_WIDTH) != settings[CapsuleTuning.ISLAND_WIDTH]) {
            suspendViewport()
        }
        val now = SystemClock.elapsedRealtime()
        val previousSettings = displaySettings
        val animate = previousSettings == settings && ValueAnimator.areAnimatorsEnabled()
        if (animate && displayTrackIdentity != null && displayTrackIdentity !== trackIdentity) {
            val deadline = now + CapsuleColorTransition.ARTWORK_WAIT_MS
            artworkHoldUntil = if (settings.artwork && backgrounds.needsBackground()) deadline else 0L
            inkHoldUntil = if (settings.lyricTintEnabled && inks.target != LyricInk.WHITE) deadline else 0L
        }
        requestedArtwork = artwork
        requestedInk = ink
        if (!animate) {
            artworkHoldUntil = 0L
            inkHoldUntil = 0L
            backgrounds.snap(artwork)
            inks.snap(ink)
        } else {
            if (artwork != null) artworkHoldUntil = 0L
            if (!tintArtworkPending) inkHoldUntil = 0L
            advanceColors(now)
        }
        viewportPosition = null
        this.clock = clock
        displayLine = line
        displayTrackIdentity = trackIdentity
        displaySettings = settings
        japaneseFont.requestCustom(settings.customLyricFontId.takeIf { settings.japaneseLyricFont })
        enforceNativeVisibility()
        dimAlpha = alpha(settings[CapsuleTuning.ARTWORK_DIM])
        if (changed) {
            strip.update(line, settings, this.ink)
            capsule.invalidate()
        }
        maintainNativeSizing()
    }

    private fun advanceColors(now: Long) {
        if (now >= artworkHoldUntil) backgrounds.moveTo(requestedArtwork, now)
        if (now >= inkHoldUntil) inks.moveTo(requestedInk, now)
    }

    fun maintainNativeSizing() {
        val settings = displaySettings ?: return
        // Keep the verified slot measured across gaps and card transitions. A single invisible
        // child lets the native container measure its own width, including reserved icon space.
        val normalWidth = capsuleWidthPixels(settings[CapsuleTuning.ISLAND_WIDTH], density)
        updateCapsuleWidth(normalWidth)
        setFillerWidth(capsuleWidthPixels(CapsuleTuning.ISLAND_WIDTH.range.last / 2, density))
    }

    fun prepareViewport(now: Long, canCompact: Boolean, resumeInterlude: Boolean = false,
                        animatedWidth: Int? = null): Boolean {
        val settings = displaySettings ?: return false
        val line = displayLine ?: return false
        val countdown = line.countdown?.takeUnless { it.isIntro } ?: return false
        val width = capsule.width
        val height = capsule.height.toFloat()
        if (!canCompact || width <= 0 || height <= 0f ||
            (settings.showCover && target.artwork.width <= 0) || (settings.showWaveform && target.waveform.width <= 0)) {
            suspendViewport()
            return false
        }
        val position = clock?.position(now) ?: line.startMs
        val lens = resolveCamera(includeWindowFallback = true)
        val leading = capsuleLyricsLeft(layoutLeft(target.artwork, 0f) + target.artwork.width, density, settings)
        val trailing = if (settings.showWaveform) {
            maxOf(target.waveform.width.toFloat(), width - layoutLeft(target.waveform, width.toFloat()))
        } else 3f * density
        val requested = interludeCapsuleWidth(countdown, position, width, height, density,
            leading, trailing, lens?.let { it.x - width / 2f } ?: 0f, lens?.radiusX ?: 0f,
            line.priorRows != null && settings.motion != LineMotion.NONE)
        val startedAt = viewportStartedAt ?: if (resumeInterlude) now - VIEWPORT_HANDOFF_MS else now
        viewportStartedAt = startedAt
        val (interludeInset, settling) = interludeViewportInset(width, requested, now - startedAt)
        val nativeInset = animatedWidth?.let { nativeCapsuleClipLeft(width, it) } ?: 0
        val inset = maxOf(interludeInset, nativeInset).coerceAtMost((width - 1) / 2)
        if (!viewport.apply(inset, settings.showCover, settings.showWaveform, followEdge = resumeInterlude)) {
            suspendViewport()
            return false
        }
        // The width envelope and the countdown paint use the same playback position.
        viewportPosition = position
        if (settling) capsule.postInvalidateOnAnimation()
        return true
    }

    fun suspendViewport() {
        viewport.restore()
        viewportStartedAt = null
        viewportPosition = null
    }

    fun paintDiagnostics() = strip.paintDiagnostics()

    fun draw(canvas: Canvas, animatedWidth: Int?): String {
        if (!capsule.isShown) return "CAPSULE_HIDDEN"
        val now = SystemClock.elapsedRealtime()
        advanceColors(now)
        val artworkLayers = backgrounds.sample(now)
        val inkLayers = inks.sample(now)
        if (displayLine == null && artworkLayers.keys.none { it != null }) return "NO_LYRIC_OR_ARTWORK"
        val width = capsule.width
        val height = capsule.height.toFloat()
        if (width <= 0 || height <= 0) return "EMPTY_DRAWING_AREA"
        // During a media card handoff the outline still supplies the interlude viewport.
        // The native animated clip can narrow it further without moving the lyric slots.
        val inset = viewport.inset
        val drawingWidth = animatedWidth ?: (width - 2 * inset)
        // Clip to the OEM drawable bounds without using the clip to resize the artwork or lyrics.
        val shapeLeft = if (animatedWidth != null) nativeCapsuleClipLeft(capsule.measuredWidth, drawingWidth) else inset
        area.set(shapeLeft.toFloat(), 0f, (shapeLeft + drawingWidth).toFloat(), height)
        artworkArea.set(0f, 0f, width.toFloat(), height)
        clip.rewind()
        val radius = area.height() / 2f
        clip.addRoundRect(area, radius, radius, Path.Direction.CW)
        val save = canvas.save()
        try {
            canvas.clipPath(clip)
            if (backgrounds.isMoving(now) || inks.isMoving(now)) {
                capsule.postInvalidateOnAnimation()
            } else {
                val next = listOf(artworkHoldUntil, inkHoldUntil).filter { it > now }.minOrNull()
                if (next != null) capsule.postInvalidateDelayed(next - now)
            }
            if (artworkLayers.size == 1) {
                artworkLayers.keys.first()?.takeUnless { it.isRecycled }?.let {
                    artworkBackground.draw(canvas, it, artworkArea, area, dimAlpha)
                }
            } else {
                layerBlend.draw(canvas, area, artworkLayers) { image ->
                    image?.takeUnless { it.isRecycled }?.let {
                        artworkBackground.draw(canvas, it, artworkArea, area, dimAlpha)
                    }
                }
            }
            val line = displayLine ?: return "ARTWORK_ONLY"
            val settings = displaySettings ?: return "ARTWORK_ONLY"
            val lens = resolveCamera(line.countdown != null || line.priorCountdown != null || settings.cameraWordAvoidance)
            val iconStart = layoutLeft(target.artwork, 0f)
            val waveformLeft = layoutLeft(target.waveform, width.toFloat()).coerceIn(0f, width.toFloat())
            val left = capsuleLyricsLeft(iconStart + target.artwork.width, density, settings) + inset
            val right = capsuleLyricsRight(width.toFloat(), waveformLeft, density, settings) - inset
            val timing = clock
            val position = if (line.isTrackInfo) now else viewportPosition ?: timing?.position(now) ?: line.startMs
            val settled = animatedWidth == null && abs(width - capsule.measuredWidth) <= 1
            fun drawLyrics(color: LyricInk): Long? {
                if (ink != color) {
                    ink = color
                    strip.update(line, settings, color)
                }
                return strip.draw(canvas, left, area.top, right, area.bottom,
                    lens, position, artworkArea = artworkArea,
                    trackInfoMarquee = trackInfoMarquee, trackInfoSettled = settled)
            }
            val redrawDelay = if (inkLayers.size > 1 && inkLayers.keys.any { it.texture != null }) {
                var delay: Long? = null
                layerBlend.draw(canvas, area, inkLayers) { color ->
                    drawLyrics(color)?.let { delay = minOf(delay ?: it, it) }
                }
                delay
            } else drawLyrics(blendLyricInks(inkLayers))
            capsule.removeCallbacks(redrawContent)
            if (line.isTrackInfo) {
                if (capsule.windowVisibility == View.VISIBLE && redrawDelay != null) {
                    if (redrawDelay == 0L) capsule.postInvalidateOnAnimation()
                    else capsule.postDelayed(redrawContent, redrawDelay)
                }
            } else if (redrawDelay != null && timing?.moving == true && timing.speed > 0f) {
                if (redrawDelay == 0L) capsule.postInvalidateOnAnimation()
                else timing.delayTo(position, position + redrawDelay)?.let { capsule.postDelayed(redrawContent, it) }
            }
            return "LYRICS_DRAWN"
        } finally {
            canvas.restoreToCount(save)
        }
    }

    private fun resolveCamera(includeWindowFallback: Boolean): CapsuleCamera? {
        // Display cutout and getLocationOnScreen share display coordinates. Insets
        // delivered to the native capsule may already have consumed the cutout.
        val displayCamera = capsule.topDisplayCutout()
        val windowCamera = if (displayCamera == null && includeWindowFallback) {
            capsule.rootWindowInsets?.displayCutout?.boundingRectTop?.takeUnless { it.isEmpty }
        } else null
        val camera = displayCamera ?: windowCamera ?: return null
        if (windowCamera != null) capsule.getLocationInWindow(location) else capsule.getLocationOnScreen(location)
        val scaleX = capsule.scaleX.takeIf { it > 0f } ?: 1f
        return CapsuleCamera((camera.exactCenterX() - location[0]) / scaleX, camera.width() / (2f * scaleX))
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

    fun restoreForPause() {
        nativeWhilePaused = true
        close()
    }

    fun close() {
        suspendViewport()
        val previousSettings = displaySettings ?: return
        displaySettings = null
        capsule.removeCallbacks(redrawContent)
        coverVisibility.restore()
        waveformVisibility.restore()
        updateCapsuleWidth(null)
        clearFiller()
        displayLine = null
        displayTrackIdentity = null
        requestedArtwork = null
        requestedInk = LyricInk.WHITE
        artworkHoldUntil = 0L
        inkHoldUntil = 0L
        backgrounds.snap(null)
        inks.snap(LyricInk.WHITE)
        ink = LyricInk.WHITE
        strip.update(null, previousSettings, ink)
        clock = null
        trackInfoShown = false
        trackInfoMarquee.reset()
        capsule.invalidate()
    }

    private fun alpha(percent: Int): Int = (percent * 255 + 50) / 100
}
