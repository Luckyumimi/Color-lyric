package shop.terramoor.github.io.systemui

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
    val cameraCenterY = 40.5f * scale
    val cameraRadius = 39f * scale
}

internal fun capsuleLyricsLeft(artworkRight: Float, density: Float) = maxOf(0f, artworkRight) + 3f * density

internal fun capsuleLyricsRight(width: Float, waveformLeft: Float, density: Float) =
    minOf(width, waveformLeft) - 3f * density

internal fun capsuleWidthPixels(widthDp: Int, density: Float) = (widthDp * density).roundToInt()
