package shop.terramoor.github.io

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.HardwareBufferRenderer
import android.graphics.Paint
import android.graphics.RenderNode
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.hardware.HardwareBuffer
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import shop.terramoor.github.io.lyrics.LyricFrame
import shop.terramoor.github.io.lyrics.LyricLine
import shop.terramoor.github.io.lyrics.LyricPresentation
import shop.terramoor.github.io.lyrics.LyricRows
import shop.terramoor.github.io.lyrics.Timeline
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.CapsuleTuning
import shop.terramoor.github.io.settings.LyricAlignment
import shop.terramoor.github.io.settings.LyricTintMode
import shop.terramoor.github.io.settings.MissingTranslationMode
import shop.terramoor.github.io.settings.PreviewLanguage
import shop.terramoor.github.io.settings.SecondRowContent
import androidx.test.platform.app.InstrumentationRegistry
import shop.terramoor.github.io.systemui.ArtworkLyricTint
import shop.terramoor.github.io.systemui.CameraLyricMask
import shop.terramoor.github.io.systemui.CapsuleCamera
import shop.terramoor.github.io.systemui.CapsuleLyricStrip
import shop.terramoor.github.io.systemui.EdgeFade
import shop.terramoor.github.io.systemui.LyricInk
import shop.terramoor.github.io.systemui.OuterLyricFeather
import shop.terramoor.github.io.systemui.OuterLyricMask
import shop.terramoor.github.io.systemui.TrackInfoDisplayMarquee
import shop.terramoor.github.io.systemui.artworkBitmap
import java.time.Duration
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CapsuleLyricDrawingTest {
    @Test
    fun adaptiveNotificationCoverSuppliesLyricTint() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cover = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            cover.eraseColor(Color.rgb(230, 68, 72))
            val plain = artworkBitmap(Icon.createWithBitmap(cover).loadDrawable(context))
            val adaptive = artworkBitmap(Icon.createWithAdaptiveBitmap(cover).loadDrawable(context))
            val tint = ArtworkLyricTint()
            val settings = CapsuleSettings(lyricTintMode = LyricTintMode.MEDIAN_CUT)
            val expected = tint.resolve(plain, null, settings)
            assertTrue(expected != LyricInk.WHITE)
            assertEquals(expected, tint.resolve(adaptive, null, settings))
            assertEquals("TINTED", tint.outcome)
            assertEquals(LyricInk.WHITE, tint.resolve(adaptive, null, settings.copy(lyricTintEnabled = false)))
            assertEquals("DISABLED", tint.outcome)
            assertEquals(expected, tint.resolve(adaptive, null, settings))
            assertEquals(LyricInk.WHITE, tint.resolve(null, null, settings))
            assertEquals("NO_ARTWORK", tint.outcome)
        } finally {
            cover.recycle()
        }
    }

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
        val frame = LyricFrame(LyricRows(
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ".repeat(2), "abcdefghijklmnopqrstuvwxyz".repeat(2),
        ), start, end)
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
            LyricRows("初音未来缤纷舞台，前方还有更多精彩内容", "它们不再属于任何人"),
            startMs = 0, endMs = 3000,
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
    fun textureUsesFixedParametersAndRetainsBrightnessControl() {
        val cover = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        cover.eraseColor(Color.rgb(90, 120, 180))
        val base = CapsuleSettings(lyricTintEnabled = true, lyricTintMode = LyricTintMode.TEXTURE)
            .withTuning(mapOf(CapsuleTuning.TEXTURE_BRIGHTNESS to 70, CapsuleTuning.TINT_STRENGTH to 100))
        val legacy = base.withTuning(mapOf(
            CapsuleTuning.TEXTURE_CONTRAST to 0,
            CapsuleTuning.TEXTURE_SATURATION to 0,
            CapsuleTuning.TEXTURE_BLUR to 0,
        ))
        try {
            val tint = ArtworkLyricTint()
            val expected = tint.resolve(cover, null, base).texture!!
            val actual = tint.resolve(cover, null, legacy).texture!!
            assertEquals(expected.getPixel(8, 8), actual.getPixel(8, 8))
            val darker = tint.resolve(cover, null, base.withTuning(mapOf(CapsuleTuning.TEXTURE_BRIGHTNESS to 0))).texture!!
            assertTrue(Color.red(darker.getPixel(8, 8)) < Color.red(expected.getPixel(8, 8)))
            val weaker = tint.resolve(cover, null, base.withTuning(mapOf(CapsuleTuning.TINT_STRENGTH to 25))).texture!!
            assertTrue(Color.red(weaker.getPixel(8, 8)) > Color.red(expected.getPixel(8, 8)))
        } finally {
            cover.recycle()
        }
    }

    @Test
    fun textureUsesTheCapsuleCropAndPhysicalPositionAcrossTheCamera() {
        val texture = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) for (x in 0 until 64) {
            texture.setPixel(x, y, when {
                y < 20 || y >= 44 -> Color.GREEN
                x < 32 -> Color.RED
                else -> Color.BLUE
            })
        }
        val image = Bitmap.createBitmap(300, 60, Bitmap.Config.ARGB_8888)
        val strip = CapsuleLyricStrip(3f)
        val frame = LyricFrame(LyricRows("MMMMMMMMMMMMMMMMMMMMMMMM"), 0, 5000)
        try {
            strip.update(frame, CapsuleSettings(cameraFade = false), LyricInk(Color.WHITE, Color.WHITE, texture))
            strip.draw(Canvas(image), 20f, 0f, 280f, 60f, CapsuleCamera(150f, 40f), 0,
                artworkArea = RectF(0f, 0f, 300f, 60f))
            var leftPixels = 0
            var rightPixels = 0
            for (y in 0 until 60) for (x in 35 until 230) {
                val pixel = image.getPixel(x, y)
                if (Color.alpha(pixel) < 240) continue
                if (x < 100) {
                    assertTrue("Left texture does not follow the artwork", Color.red(pixel) > 220 && Color.green(pixel) < 20)
                    leftPixels++
                } else if (x in 195 until 230) {
                    assertTrue("Right texture does not follow the artwork", Color.blue(pixel) > 220 && Color.green(pixel) < 20)
                    rightPixels++
                }
            }
            assertTrue("Missing left lyric pixels", leftPixels > 0)
            assertTrue("Missing right lyric pixels", rightPixels > 0)
        } finally {
            image.recycle()
            texture.recycle()
        }
    }

    @Test
    fun translationAndNextLyricBothUseSecondRowSizeAndOpacity() {
        val first = "FIRST"
        val second = "MMMM"
        val timeline = Timeline.fromLines(listOf(LyricLine(0, first, second), LyricLine(2000, second)))
        val settings = CapsuleSettings().withTuning(mapOf(
            CapsuleTuning.SECOND_ROW_OPACITY to 40, CapsuleTuning.SECOND_ROW_SIZE to 7,
        ))
        val lyrics = settings.copy(secondRowContent = SecondRowContent.LYRIC)
        val translated = LyricPresentation(timeline, settings).frameAt(0, 5000)
        val next = LyricPresentation(timeline, lyrics).frameAt(0, 5000)
        for ((frame, config) in listOf(translated to settings, next to lyrics)) {
            val normal = drawRows(frame, config)
            val brighter = drawRows(frame, config.withTuning(mapOf(CapsuleTuning.SECOND_ROW_OPACITY to 100)))
            val larger = drawRows(frame, config.withTuning(mapOf(CapsuleTuning.SECOND_ROW_SIZE to 11)))
            try {
                assertTrue(normal.ink(20 until 300, 61 until 120) > 0)
                assertTrue(!normal.sameAs(brighter))
                assertTrue(!normal.sameAs(larger))
            } finally {
                listOf(normal, brighter, larger).forEach(Bitmap::recycle)
            }
        }
    }

    @Test
    fun wideUpcomingLyricStaysStillAndDoesNotDriveFrameInvalidation() {
        val timeline = Timeline.fromLines(listOf(LyricLine(0, "I"), LyricLine(2000, "Next lyric ".repeat(30))))
        val settings = CapsuleSettings(secondRowContent = SecondRowContent.LYRIC)
        val strip = CapsuleLyricStrip(3f)
        val frame = checkNotNull(LyricPresentation(timeline, settings).frameAt(0, 4000))
        strip.update(frame, settings)
        val start = Bitmap.createBitmap(320, 120, Bitmap.Config.ARGB_8888)
        val middle = Bitmap.createBitmap(320, 120, Bitmap.Config.ARGB_8888)
        try {
            assertNull(strip.draw(Canvas(start), 20f, 0f, 300f, 120f, null, 0))
            assertNull(strip.draw(Canvas(middle), 20f, 0f, 300f, 120f, null, 1300))
            assertTrue(start.ink(20 until 300, 61 until 120) > 0)
            assertTrue(start.sameAs(middle))
        } finally {
            start.recycle()
            middle.recycle()
        }
    }

    @Test
    fun upcomingLyricMovesUpAndGrowsWithoutDisappearingAtTheBoundary() {
        val timeline = Timeline.fromLines(listOf(LyricLine(0, "I"), LyricLine(2000, "MMMMMM"), LyricLine(4500, "I")))
        val settings = CapsuleSettings(secondRowContent = SecondRowContent.LYRIC)
            .withTuning(mapOf(CapsuleTuning.FIRST_ROW_SIZE to 14, CapsuleTuning.SECOND_ROW_SIZE to 7))
        val presentation = LyricPresentation(timeline, settings)
        val previous = presentation.frameAt(0, 6000)
        val current = presentation.frameAt(1, 6000)
        val before = drawRows(previous, settings, 1999)
        val boundary = drawRows(current, settings, 2000)
        val settled = drawRows(current, settings, 2260)
        try {
            assertTrue(before.sameAs(boundary))
            // Ignore the short outgoing and incoming rows at the left edge.
            fun inkTop(image: Bitmap) = (0 until 120).first { y -> image.ink(80 until 300, y..y) > 0 }
            val oldTop = inkTop(before)
            val settledTop = inkTop(settled)
            assertTrue(settledTop < oldTop)
            var lastTop = oldTop
            for (position in listOf(2030L, 2070L, 2120L)) {
                val animating = drawRows(current, settings, position)
                try {
                    val top = inkTop(animating)
                    // Rounding at intermediate scales can shift a glyph edge by a pixel.
                    assertTrue(top in settledTop - 1..lastTop + 1)
                    lastTop = top
                } finally {
                    animating.recycle()
                }
            }
            assertTrue(lastTop < oldTop)
            assertTrue(settled.ink(160 until 300, 0 until 120) > before.ink(160 until 300, 0 until 120))
        } finally {
            listOf(before, boundary, settled).forEach(Bitmap::recycle)
        }
    }

    @Test
    fun missingTranslationDrawsTheSelectedFallbackAndOnlyExpandsTheSingleFallback() {
        val timeline = Timeline.fromLines(listOf(LyricLine(0, "FIRST"), LyricLine(2000, "Next lyric")))
        val defaults = CapsuleSettings()
        val single = defaults.copy(doubleLine = false)
        val compact = defaults.copy(fallbackSingleLineExpand = false)
        val double = defaults.copy(missingTranslationMode = MissingTranslationMode.DOUBLE_LYRICS)
        fun draw(settings: CapsuleSettings) = drawRows(LyricPresentation(timeline, settings).frameAt(0, 5000), settings)
        val ordinary = draw(single)
        val fallback = draw(compact)
        val expanded = draw(defaults)
        val twoRows = draw(double)
        val explicitLyrics = draw(defaults.copy(secondRowContent = SecondRowContent.LYRIC))
        try {
            assertTrue(ordinary.sameAs(fallback))
            assertTrue(expanded.ink(20 until 300, 0 until 120) > fallback.ink(20 until 300, 0 until 120))
            assertTrue(twoRows.sameAs(explicitLyrics))
            assertTrue(!ordinary.sameAs(twoRows))
        } finally {
            listOf(ordinary, fallback, expanded, twoRows, explicitLyrics).forEach(Bitmap::recycle)
        }
    }

    @Test
    fun untimedTrackPreviewScrollsItsTitleAndLeavesTheArtistStill() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val sample = PreviewLanguage.NO_SYNC_LYRICS
        assertNull(sample.timeline(resources))
        val frame = checkNotNull(sample.trackInfo(resources))
        val strip = CapsuleLyricStrip(3f)
        val settings = CapsuleSettings()
        val start = Bitmap.createBitmap(320, 120, Bitmap.Config.ARGB_8888)
        val during = Bitmap.createBitmap(320, 120, Bitmap.Config.ARGB_8888)
        try {
            strip.update(frame, settings)
            assertEquals(3000L, strip.draw(Canvas(start), 20f, 0f, 300f, 120f, null, 0))
            assertEquals(0L, strip.draw(Canvas(during), 20f, 0f, 300f, 120f, null, 3600))
            assertTrue(!start.sameAs(during))
            assertTrue(start.ink(20 until 300, 66 until 120) > 0)
            for (y in 66 until 120) for (x in 20 until 300) {
                assertEquals(start.getPixel(x, y), during.getPixel(x, y))
            }
            strip.update(LyricFrame.trackInfo("Short", "Artist"), settings)
            assertNull(strip.draw(Canvas(during), 20f, 0f, 300f, 120f, null, 1500))
        } finally {
            start.recycle()
            during.recycle()
        }
    }

    @Test
    fun trackTitleFollowsTheNativeViewportWhileItsMarqueeIsPaused() {
        val strip = CapsuleLyricStrip(2f)
        val marquee = TrackInfoDisplayMarquee()
        val narrow = Bitmap.createBitmap(320, 120, Bitmap.Config.ARGB_8888)
        val wide = Bitmap.createBitmap(320, 120, Bitmap.Config.ARGB_8888)
        try {
            strip.update(LyricFrame.trackInfo("Title", "Artist"), CapsuleSettings(lyricAlignment = LyricAlignment.CENTER))
            assertNull(strip.draw(Canvas(narrow), 20f, 0f, 160f, 120f, null, 0,
                trackInfoMarquee = marquee, trackInfoSettled = false))
            assertNull(strip.draw(Canvas(wide), 20f, 0f, 300f, 120f, null, 150,
                trackInfoMarquee = marquee, trackInfoSettled = false))
            assertTrue(narrow.ink(20 until 120, 0 until 66) > 0)
            assertEquals(0, wide.ink(20 until 120, 0 until 66))
            assertTrue(wide.ink(120 until 200, 0 until 66) > 0)
        } finally {
            narrow.recycle()
            wide.recycle()
        }
    }

    @Test
    fun previewSamplesUseTheSameFrameAndBoundaryRules() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        for (language in PreviewLanguage.entries) {
            val timeline = language.timeline(resources) ?: continue
            val settings = CapsuleSettings(secondRowContent = SecondRowContent.LYRIC)
            val presentation = LyricPresentation(timeline, settings)
            assertEquals(timeline.lines[1].startMs, presentation.nextBoundary(0))
            assertEquals(timeline.lines[1].text, checkNotNull(presentation.frameAt(0, 9100)).rows.second)
            val bitmap = drawRows(presentation.frameAt(0, 9100), settings)
            try {
                assertTrue(bitmap.ink(20 until 300, 0 until 120) > 0)
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun drawRows(frame: LyricFrame?, settings: CapsuleSettings, position: Long = 0): Bitmap =
        Bitmap.createBitmap(320, 120, Bitmap.Config.ARGB_8888).also { bitmap ->
            val strip = CapsuleLyricStrip(3f)
            strip.update(frame, settings)
            strip.draw(Canvas(bitmap), 20f, 0f, 300f, 120f, null, position)
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
