package shop.terramoor.github.io

import org.junit.Assert.assertEquals
import org.junit.Test
import shop.terramoor.github.io.settings.LyricAlignment
import shop.terramoor.github.io.systemui.CapsuleTextViewport
import shop.terramoor.github.io.systemui.MediaCapsulePreviewGeometry
import shop.terramoor.github.io.systemui.capsuleLyricsLeft
import shop.terramoor.github.io.systemui.capsuleLyricsRight

class CapsuleTextViewportTest {
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
    fun previewChangesAtEveryWholeCapsuleWidthStep() {
        val widths = (156..202 step 2).map { MediaCapsulePreviewGeometry(3.5f, it).width }
        assertEquals(546f, widths.first(), 0.001f)
        assertEquals(707f, widths.last(), 0.001f)
        widths.zipWithNext { a, b -> assertEquals(7f, b - a, 0.001f) }
        val wide = MediaCapsulePreviewGeometry(3.5f, 202)
        assertEquals(105f, wide.height, 0.001f)
        assertEquals(98.5f, capsuleLyricsLeft(wide.artworkLeft + wide.artworkSize, 3.5f), 0.001f)
        assertEquals(597.5f, capsuleLyricsRight(wide.width, wide.waveformLeft, 3.5f), 0.001f)
        assertEquals(354f, wide.cameraCenterX, 0.001f)
        for (density in listOf(1f, 2f, 3.5f, 4f)) {
            for (width in listOf(156, 174, 202)) {
                val preview = MediaCapsulePreviewGeometry(density, width)
                assertEquals(preview.height / 2f, preview.cameraCenterY, 0.001f)
            }
        }
    }
}
