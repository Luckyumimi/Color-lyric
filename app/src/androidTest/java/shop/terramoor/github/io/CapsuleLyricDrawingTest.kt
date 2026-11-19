package shop.terramoor.github.io

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.HardwareBufferRenderer
import android.graphics.Paint
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import shop.terramoor.github.io.lyrics.LyricFrame
import shop.terramoor.github.io.lyrics.LyricLine
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.CapsuleTuning
import shop.terramoor.github.io.systemui.CameraLyricMask
import shop.terramoor.github.io.systemui.CapsuleCamera
import shop.terramoor.github.io.systemui.CapsuleLyricStrip
import java.time.Duration
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CapsuleLyricDrawingTest {
    @Test
    fun originalAndTranslationDrawOnBothSidesWithAndWithoutFade() {
        val start = 1200L
        val end = 5800L
        val frame = LyricFrame(LyricLine(
            start, "ABCDEFGHIJKLMNOPQRSTUVWXYZ".repeat(2), "abcdefghijklmnopqrstuvwxyz".repeat(2),
        ), end)
        val strip = CapsuleLyricStrip(3.5f)
        for (fade in listOf(false, true)) {
            val settings = CapsuleSettings(cameraFade = fade)
                .withTuning(mapOf(CapsuleTuning.CAMERA_FADE_RADIUS to 14))
            strip.update(frame, settings)
            for (position in listOf(start, (start + end) / 2, end)) {
                val image = Bitmap.createBitmap(710, 108, Bitmap.Config.ARGB_8888)
                strip.draw(Canvas(image), 100f, 0f, 620f, 108f, CapsuleCamera(329f, 39f), position)
                for (row in listOf(8 until 57, 58 until 103)) {
                    val state = "fade=$fade, position=$position"
                    assertTrue("Left lyrics missing ($state)", image.ink(100 until 290, row) > 10)
                    assertTrue("Right lyrics missing ($state)", image.ink(368 until 620, row) > 10)
                }
                assertEquals("Text under the camera", 0, image.ink(310 until 349, 38 until 71))
                image.recycle()
            }
        }
    }

    @Test
    fun cameraGapJoinsTheSameGlyphAcrossTheGapWithoutFeather() {
        val strip = CapsuleLyricStrip(3.5f)
        // Fractional bounds exercise pixel snapping at both edges of the cutout.
        val camera = CapsuleCamera(179.75f, 38.75f)
        val frame = LyricFrame(
            LyricLine(0, "初音未来缤纷舞台，前方还有更多精彩内容", "它们不再属于任何人"),
            3000,
        )
        strip.update(frame, CapsuleSettings(cameraFade = false))
        for (position in listOf(0L, 400L, 620L, 1150L, 3000L)) {
            val masked = Bitmap.createBitmap(360, 108, Bitmap.Config.ARGB_8888)
            val reference = Bitmap.createBitmap(360, 108, Bitmap.Config.ARGB_8888)
            try {
                strip.draw(Canvas(masked), 20f, 0f, 340f, 108f, camera, position)
                strip.draw(Canvas(reference), 20f, 0f, 262f, 108f, null, position)
                assertEquals(0, masked.ink(141 until 219, 0 until 108))
                for (x in (20 until 141) + (219 until 340)) {
                    val compactX = if (x < 141) x else x - 78
                    for (y in 0 until 108) assertEquals(reference.getPixel(compactX, y), masked.getPixel(x, y))
                }
            } finally {
                masked.recycle()
                reference.recycle()
            }
        }
    }

    @Test
    fun softwareMaskFadesBothStraightEdges() {
        val image = Bitmap.createBitmap(200, 120, Bitmap.Config.ARGB_8888)
        val ink = Paint().apply { color = Color.WHITE }
        val mask = CameraLyricMask()
        val camera = CapsuleCamera(100f, 20f)
        try {
            for (feather in listOf(0f, 24f)) {
                image.eraseColor(Color.TRANSPARENT)
                mask.draw(Canvas(image), 12f, 3f, 188f, 117f, camera, feather, 0f) {
                    it.drawRect(12f, 3f, 188f, 117f, ink)
                }
                assertClearCenter(image)
                val leftEdge = image.pixelAlpha(79, 60)
                val rightEdge = image.pixelAlpha(120, 60)
                assertEquals(leftEdge, rightEdge)
                if (feather == 0f) assertEquals(255, leftEdge)
                else {
                    assertTrue(leftEdge in 1..20)
                    assertEquals(leftEdge, image.pixelAlpha(79, 27))
                    assertTrue(image.pixelAlpha(69, 60) > leftEdge)
                }
            }
        } finally {
            image.recycle()
        }
    }

    @Test
    fun hardwareShaderBlursOnlyNearTheStraightSeam() {
        val width = 200
        val height = 120
        val root = RenderNode("Camera blur test").apply { setPosition(0, 0, width, height) }
        val mask = CameraLyricMask()
        val white = Paint().apply { color = Color.WHITE }
        val camera = CapsuleCamera(100f, 20f)
        val canvas = root.beginRecording()
        mask.draw(canvas, 12f, 3f, 188f, 117f, camera, 24f, 3f) {
            it.drawRect(18f, 7f, 72f, 113f, white)
            it.drawRect(75f, 7f, 80f, 113f, white)
            it.drawRect(120f, 7f, 138f, 113f, white)
        }
        root.endRecording()
        val drawn = render(root, width, height)
        try {
            assertClearCenter(drawn)
            assertEquals(255, drawn.pixelAlpha(25, 60))
            assertTrue("Blur should reach a nearby empty column", drawn.pixelAlpha(73, 59) > 0)
            assertTrue(drawn.pixelAlpha(79, 59) < 20)
            assertEquals(drawn.pixelAlpha(73, 59).toFloat(), drawn.pixelAlpha(73, 20).toFloat(), 3f)
        } finally {
            drawn.recycle()
        }
    }

    private fun render(root: RenderNode, width: Int, height: Int): Bitmap =
        HardwareBuffer.create(width, height, HardwareBuffer.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE).use { buffer ->
            HardwareBufferRenderer(buffer).use { renderer ->
                renderer.setContentRoot(root)
                val pixels = FutureTask {
                    val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, null)
                    checkNotNull(hardwareBitmap)
                    try {
                        hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                    } finally {
                        hardwareBitmap.recycle()
                    }
                }
                renderer.obtainRenderRequest().draw({ it.run() }) { result ->
                    result.fence.use { fence ->
                        if (result.status == HardwareBufferRenderer.RenderResult.SUCCESS &&
                            fence.await(Duration.ofSeconds(5))
                        ) pixels.run() else pixels.cancel(false)
                    }
                }
                checkNotNull(pixels.get(10, TimeUnit.SECONDS))
            }
        }

    private fun assertClearCenter(bitmap: Bitmap) {
        for (x in 80 until 120) {
            for (y in 3 until 117) {
                assertEquals("Lyric pixel inside the clear center at ($x, $y)", 0, bitmap.pixelAlpha(x, y))
            }
        }
    }

    private fun Bitmap.pixelAlpha(x: Int, y: Int) = Color.alpha(getPixel(x, y))

    private fun Bitmap.ink(xs: IntRange, ys: IntRange): Int =
        ys.sumOf { y -> xs.count { x -> Color.alpha(getPixel(x, y)) > 24 } }
}
