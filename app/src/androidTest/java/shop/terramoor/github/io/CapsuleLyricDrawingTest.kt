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
import shop.terramoor.github.io.systemui.EdgeFade
import shop.terramoor.github.io.systemui.OuterLyricFeather
import shop.terramoor.github.io.systemui.OuterLyricMask
import java.time.Duration
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CapsuleLyricDrawingTest {
    @Test
    fun outerEdgesFadeWithoutChangingTheBackground() {
        val image = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        val ink = Paint().apply { color = Color.WHITE }
        val background = Paint().apply { color = Color.RED }
        canvas.drawRect(0f, 0f, 200f, 80f, background)
        val mask = OuterLyricMask()
        mask.draw(canvas, 20f, 10f, 180f, 70f, OuterLyricFeather(24f, 32f, 6f), EdgeFade(1f, 1f)) {
            it.drawRect(20f, 10f, 180f, 70f, ink)
        }
        try {
            assertEquals(Color.RED, image.getPixel(5, 40))
            assertTrue(Color.green(image.getPixel(20, 40)) < 20)
            assertTrue(Color.green(image.getPixel(28, 40)) in 50..150)
            assertEquals(255, Color.green(image.getPixel(60, 40)))
            assertEquals(255, Color.green(image.getPixel(141, 40)))
            assertTrue(Color.green(image.getPixel(150, 40)) in 190..245)
            assertTrue(Color.green(image.getPixel(157, 40)) in 105..150)
            assertTrue(Color.green(image.getPixel(165, 40)) in 25..65)
            for (x in 174..180) assertEquals(Color.RED, image.getPixel(x, 40))
        } finally {
            image.recycle()
        }
    }

    @Test
    fun outerEdgesCanFadeIndependently() {
        val image = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        val ink = Paint().apply { color = Color.WHITE }
        val mask = OuterLyricMask()
        try {
            mask.draw(canvas, 20f, 10f, 180f, 70f, OuterLyricFeather(24f, 24f, 4f), EdgeFade(0f, 1f)) {
                it.drawRect(20f, 10f, 180f, 70f, ink)
            }
            assertEquals(255, image.pixelAlpha(20, 40))
            assertTrue(image.pixelAlpha(179, 40) < 20)
            image.eraseColor(Color.TRANSPARENT)
            mask.draw(canvas, 20f, 10f, 180f, 70f, OuterLyricFeather(24f, 24f, 4f), EdgeFade(1f, 0f)) {
                it.drawRect(20f, 10f, 180f, 70f, ink)
            }
            assertTrue(image.pixelAlpha(20, 40) < 20)
            assertEquals(255, image.pixelAlpha(179, 40))
        } finally {
            image.recycle()
        }
    }

    @Test
    fun translatedLyricsStillFadeAtTheViewportEdges() {
        val image = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        val ink = Paint().apply { color = Color.WHITE }
        try {
            OuterLyricMask().draw(canvas, 20f, 10f, 180f, 70f, OuterLyricFeather(24f, 24f, 4f), EdgeFade(0f, 1f)) {
                it.translate(-35f, 10f)
                it.drawRect(55f, 0f, 215f, 60f, ink)
            }
            assertEquals(255, image.pixelAlpha(20, 40))
            assertEquals(255, image.pixelAlpha(100, 40))
            assertTrue(image.pixelAlpha(163, 40) in 105..150)
            assertEquals(0, image.pixelAlpha(176, 40))
            assertEquals(0, image.pixelAlpha(179, 40))
        } finally {
            image.recycle()
        }
    }

    @Test
    fun hardwareCameraLayerLeavesNoInkAtFractionalRightBoundary() {
        val width = 240
        val height = 80
        val root = RenderNode("Fractional lyric edge test").apply { setPosition(0, 0, width, height) }
        val foreground = Paint().apply { color = Color.WHITE }
        val canvas = root.beginRecording()
        val right = 180.75f
        canvas.save()
        try {
            canvas.clipRect(20.25f, 8f, right, 72f)
            CameraLyricMask().draw(canvas, 20.25f, 8f, right, 72f,
                CapsuleCamera(85f, 10f), 10f, 2f) { lyricLayer ->
                lyricLayer.save()
                try {
                    lyricLayer.clipRect(95f, 8f, right, 72f)
                    lyricLayer.translate(20f, 0f)
                    OuterLyricMask().draw(lyricLayer, 20.25f, 12.25f, right - 20f, 68.75f,
                        OuterLyricFeather(12f, 24f, 6f), EdgeFade(0f, 1f)) { ink ->
                        ink.translate(0.25f, 0.25f)
                        ink.drawRect(100f, 10f, 190f, 71f, foreground)
                    }
                } finally {
                    lyricLayer.restore()
                }
            }
        } finally {
            canvas.restore()
            root.endRecording()
        }
        val image = render(root, width, height)
        try {
            assertEquals(255, image.pixelAlpha(130, 40))
            assertTrue(image.pixelAlpha(163, 40) in 105..150)
            for (x in 176..184) {
                for (y in 8 until 72) assertEquals("Unexpected ink at ($x, $y)", 0, image.pixelAlpha(x, y))
            }
        } finally {
            image.recycle()
        }
    }

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
                // Compare the unmodified interior on both sides of the seam.
                for (x in (48 until 141) + (219 until 291)) {
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
