package shop.terramoor.github.io

import org.junit.Assert.assertEquals
import org.junit.Test
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.CapsuleTuning
import shop.terramoor.github.io.settings.LyricAlignment
import shop.terramoor.github.io.systemui.CapsuleTextViewport
import shop.terramoor.github.io.systemui.MediaCapsulePreviewGeometry
import shop.terramoor.github.io.systemui.capsuleLyricsLeft
import shop.terramoor.github.io.systemui.capsuleLyricsRight
import shop.terramoor.github.io.systemui.compactCapsuleCenter
import shop.terramoor.github.io.systemui.nativeCapsuleClipLeft

class CapsuleTextViewportTest {
    @Test
    fun animatedClipUsesNativeIntegerBoundsDuringOpeningAndOvershoot() {
        assertEquals(247, nativeCapsuleClipLeft(measuredWidth = 595, animatedWidth = 100))
        assertEquals(0, nativeCapsuleClipLeft(measuredWidth = 595, animatedWidth = 594))
        assertEquals(-8, nativeCapsuleClipLeft(measuredWidth = 595, animatedWidth = 612))
    }

    @Test
    fun longLineMovesAtConstantSpeedAndShowsItsLastGlyph() {
        val viewport = CapsuleTextViewport(120f, 352f, LyricAlignment.LEFT)
        for (alignment in LyricAlignment.entries) {
            val aligned = viewport.copy(alignment = alignment)
            val offsets = (0..4).map { aligned.textOffset(442f, it / 4f) }
            offsets.forEachIndexed { index, x -> assertEquals(-52.5f * index, x, 0.001f) }
            assertEquals(aligned.right, aligned.left + offsets.last() + 442f, 0.001f)
        }
    }

    @Test
    fun linesThatFitTheLogicalStripStayStill() {
        val viewport = CapsuleTextViewport(120f, 352f, LyricAlignment.CENTER)
        assertEquals(0f, viewport.travel(200f), 0.001f)
        assertEquals(16f, viewport.textOffset(200f, 1f), 0.001f)
        assertEquals(0f, viewport.copy(alignment = LyricAlignment.LEFT).textOffset(200f, 1f), 0.001f)
    }

    @Test
    fun centeredLyricsUseCapsuleCenterForAllElementCombinations() {
        val settings = CapsuleSettings()
        val geometry = MediaCapsulePreviewGeometry(1f, settings[CapsuleTuning.ISLAND_WIDTH])
        val gapLeft = geometry.cameraCenterX - geometry.cameraRadius
        val gapWidth = 2f * geometry.cameraRadius
        val center = compactCapsuleCenter(geometry.width / 2f, gapLeft, gapWidth)
        for (showCover in listOf(false, true)) for (showWaveform in listOf(false, true)) {
            val config = settings.copy(showCover = showCover, showWaveform = showWaveform)
            val left = capsuleLyricsLeft(geometry.artworkLeft + geometry.artworkSize, 1f, config)
            val right = capsuleLyricsRight(geometry.width, geometry.waveformLeft, 1f, config) - gapWidth
            val viewport = CapsuleTextViewport(left, right, LyricAlignment.CENTER, center)
            assertEquals(center, left + viewport.textOffset(20f, 0f) + 10f, 0.001f)
            assertEquals(0f, viewport.copy(alignment = LyricAlignment.LEFT).textOffset(20f, 0f), 0.001f)
            assertEquals(0f, viewport.textOffset(200f, 0f), 0.001f)
            assertEquals(-(200f - (right - left)), viewport.textOffset(200f, 1f), 0.001f)
        }
    }

    @Test
    fun capsuleCenterMapsAcrossCameraGapAndFitsAvailableStrip() {
        assertEquals(40f, compactCapsuleCenter(40f, 50f, 20f), 0.001f)
        assertEquals(50f, compactCapsuleCenter(60f, 50f, 20f), 0.001f)
        assertEquals(60f, compactCapsuleCenter(80f, 50f, 20f), 0.001f)
        val viewport = CapsuleTextViewport(30f, 100f, LyricAlignment.CENTER, 45f)
        assertEquals(0f, viewport.textOffset(40f, 0f), 0.001f)
        assertEquals(30f, viewport.copy(center = 95f).textOffset(40f, 0f), 0.001f)
    }

    @Test
    fun outerFadeTracksEachLinesActualOverflow() {
        val viewport = CapsuleTextViewport(20f, 220f, LyricAlignment.LEFT)
        val feather = viewport.outerFeather(1f)
        assertEquals(2f, feather.left, 0.001f)
        assertEquals(6f, feather.right, 0.001f)
        assertEquals(2f, feather.rightGuard, 0.001f)
        val short = viewport.edgeFade(160f, 0.5f, feather)
        assertEquals(0f, short.left, 0.001f)
        assertEquals(0f, short.right, 0.001f)
        val start = viewport.edgeFade(300f, 0f, feather)
        assertEquals(0f, start.left, 0.001f)
        assertEquals(1f, start.right, 0.001f)
        val early = viewport.edgeFade(300f, 1f / 108f, feather)
        assertEquals(0.5f, early.left, 0.001f)
        assertEquals(1f, early.right, 0.001f)
        val middle = viewport.edgeFade(300f, 0.5f, feather)
        assertEquals(1f, middle.left, 0.001f)
        assertEquals(1f, middle.right, 0.001f)
        assertEquals(1f, viewport.edgeFade(200.1f, 0.99f, feather).right, 0.001f)
        val end = viewport.edgeFade(300f, 1f, feather)
        assertEquals(1f, end.left, 0.001f)
        assertEquals(1f, end.right, 0.001f)
        val finalRight = viewport.left + viewport.textOffset(300f, 1f, feather.trailingInset) + 300f
        assertEquals(viewport.right - 8f, finalRight, 0.001f)

        val narrow = CapsuleTextViewport(0f, 3f, LyricAlignment.LEFT).outerFeather(3.5f)
        assertEquals(1.5f, narrow.left, 0.001f)
        assertEquals(1.5f, narrow.trailingInset, 0.001f)
    }

    @Test
    fun previewChangesAtEveryWholeCapsuleWidthStep() {
        val widths = (156..202 step 2).map { MediaCapsulePreviewGeometry(3.5f, it).width }
        assertEquals(546f, widths.first(), 0.001f)
        assertEquals(707f, widths.last(), 0.001f)
        widths.zipWithNext { a, b -> assertEquals(7f, b - a, 0.001f) }
        val wide = MediaCapsulePreviewGeometry(3.5f, 202)
        assertEquals(105f, wide.height, 0.001f)
        assertEquals(88f, capsuleLyricsLeft(wide.artworkLeft + wide.artworkSize, 3.5f, CapsuleSettings()), 0.001f)
        assertEquals(625.5f, capsuleLyricsRight(wide.width, wide.waveformLeft, 3.5f, CapsuleSettings()), 0.001f)
        assertEquals(354f, wide.cameraCenterX, 0.001f)
        for (density in listOf(1f, 2f, 3.5f, 4f)) {
            for (width in listOf(156, 170, 202)) {
                val preview = MediaCapsulePreviewGeometry(density, width)
                assertEquals(preview.height / 2f, preview.cameraCenterY, 0.001f)
            }
        }
    }

    @Test
    fun hiddenNativeElementsExpandOnlyTheirOwnLyricBoundary() {
        val defaults = CapsuleSettings()
        val density = 3.5f
        val preview = MediaCapsulePreviewGeometry(density, defaults[CapsuleTuning.ISLAND_WIDTH])
        val coverRight = preview.artworkLeft + preview.artworkSize
        for (showCover in listOf(false, true)) for (showWaveform in listOf(false, true)) {
            val settings = defaults.copy(showCover = showCover, showWaveform = showWaveform)
            val left = capsuleLyricsLeft(coverRight, density, settings)
            val right = capsuleLyricsRight(preview.width, preview.waveformLeft, density, settings)
            assertEquals(if (showCover) 88f else 24.5f, left, 0.001f)
            assertEquals(if (showWaveform) 513.5f else 584.5f, right, 0.001f)
        }
        assertEquals(595f, preview.width, 0.001f)
        // The native waveform view can disappear during an OEM transition.
        assertEquals(preview.width, capsuleLyricsRight(preview.width, preview.width, density, defaults), 0.001f)
    }
}
