package shop.terramoor.github.io.systemui

import shop.terramoor.github.io.settings.CapsuleSettings
import kotlin.math.roundToInt

/** Calibrated from the PKX110 media capsule at density 560. */
internal class MediaCapsulePreviewGeometry(density: Float, requestedWidthDp: Int) {
    private val scale = density / 3.5f
    val width = capsuleWidthPixels(requestedWidthDp, density).toFloat()
    val height = 105f * scale
    val artworkLeft = 18f * scale
    val artworkSize = 70f * scale
    val waveformLeft = width - 99f * scale
    val cameraCenterX = width / 2f + 0.5f * scale
    val cameraCenterY = height / 2f
    val cameraRadius = 39f * scale
}

internal fun capsuleLyricsLeft(artworkRight: Float, density: Float, settings: CapsuleSettings): Float =
    if (settings.showCover) maxOf(0f, artworkRight) else 7f * density

internal fun capsuleLyricsRight(width: Float, waveformLeft: Float, density: Float, settings: CapsuleSettings): Float =
    if (settings.showWaveform) {
        // Use 5 dp of leading space in the waveform slot.
        minOf(width, waveformLeft + 5f * density)
    } else {
        width - 3f * density
    }

internal fun capsuleWidthPixels(widthDp: Int, density: Float) = (widthDp * density).roundToInt()

/** Matches CapsuleView.setCapsuleDrawableWidth, including overshoot and integer rounding. */
internal fun nativeCapsuleClipLeft(measuredWidth: Int, animatedWidth: Int): Int =
    (measuredWidth - animatedWidth) / 2
