package shop.terramoor.github.io

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.HardwareBufferRenderer
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderNode
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.hardware.HardwareBuffer
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import shop.terramoor.github.io.lyrics.LyricFrame
import shop.terramoor.github.io.lyrics.LyricCountdown
import shop.terramoor.github.io.lyrics.LyricLine
import shop.terramoor.github.io.lyrics.LyricPresentation
import shop.terramoor.github.io.lyrics.LyricRows
import shop.terramoor.github.io.lyrics.LyricWord
import shop.terramoor.github.io.lyrics.PlaybackClock
import shop.terramoor.github.io.lyrics.Timeline
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.CapsuleTuning
import shop.terramoor.github.io.settings.LyricAlignment
import shop.terramoor.github.io.settings.LineMotion
import shop.terramoor.github.io.settings.LyricTintMode
import shop.terramoor.github.io.settings.MissingTranslationMode
import shop.terramoor.github.io.settings.PreviewLanguage
import shop.terramoor.github.io.settings.SecondRowContent
import androidx.test.platform.app.InstrumentationRegistry
import shop.terramoor.github.io.systemui.ArtworkLyricTint
import shop.terramoor.github.io.systemui.CameraLyricMask
import shop.terramoor.github.io.systemui.CapsuleArtworkBackground
import shop.terramoor.github.io.systemui.CapsuleCamera
import shop.terramoor.github.io.systemui.CapsuleLabels
import shop.terramoor.github.io.systemui.CapsuleLyricStrip
import shop.terramoor.github.io.systemui.EdgeFade
import shop.terramoor.github.io.systemui.LyricGradient
import shop.terramoor.github.io.systemui.LyricInk
import shop.terramoor.github.io.systemui.NativeContract
import shop.terramoor.github.io.systemui.OuterLyricFeather
import shop.terramoor.github.io.systemui.OuterLyricMask
import shop.terramoor.github.io.systemui.TrackInfoDisplayMarquee
import shop.terramoor.github.io.systemui.artworkBitmap
import shop.terramoor.github.io.systemui.hyperCoverLyricInk
import shop.terramoor.github.io.systemui.withCoverTintStrength
import java.time.Duration
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CapsuleLyricDrawingTest {
    @Test
    fun gradientStrengthAdjustsTheCachedCoverPaletteWithoutChangingItsExtraction() {
        val cover = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(cover)
            canvas.drawColor(Color.BLUE)
            canvas.drawRect(72f, 0f, 128f, 96f, Paint().apply { color = Color.GREEN })
            val settings = CapsuleSettings(lyricTintEnabled = true, lyricTintMode = LyricTintMode.GRADIENT)
            val reference = requireNotNull(hyperCoverLyricInk(cover))
            requireNotNull(reference.gradient)
            val tint = ArtworkLyricTint()
            assertEquals(reference, tint.resolve(cover, null, settings))
            val weak = settings.withTuning(mapOf(CapsuleTuning.TINT_STRENGTH to 25))
            val adjusted = tint.resolve(cover, cover, weak, baseColor = Color.WHITE)
            assertEquals(reference.withCoverTintStrength(25), adjusted)
            assertTrue(reference != adjusted)
            assertTrue(tint.cacheDetail.contains("seed=HIT"))
            assertEquals(reference, tint.resolve(cover, cover, settings, baseColor = Color.WHITE))
            assertEquals(LyricInk.WHITE, tint.resolve(null, cover, weak))
        } finally {
            cover.recycle()
        }
    }

    @Test
    fun pausedNativePresentationReleasesWidthAndSlotsAndAllowsResuming() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val capsule = FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 40)
                minimumWidth = 24
            }
            fun slot(): NativeContract.Slot {
                val frame = FrameLayout(context)
                val stock = View(context)
                frame.addView(stock)
                capsule.addView(frame)
                return NativeContract.Slot(frame, stock)
            }
            val title = slot()
            val subtitle = slot()
            val artwork = View(context).also(capsule::addView)
            val waveform = View(context).also(capsule::addView)
            val labels = CapsuleLabels(NativeContract.Target(capsule, title, subtitle, artwork, waveform))
            val settings = CapsuleSettings(showCover = false, showWaveform = false)
            val line = LyricFrame(LyricRows("Lyric", "Translation"), startMs = 0L, endMs = 2_000L)

            labels.render(line, settings, artwork = null)
            assertTrue(labels.hasCustomContent)
            assertTrue(capsule.layoutParams.width > 0)
            assertEquals(View.INVISIBLE, artwork.visibility)
            assertEquals(2, title.frame.childCount)

            labels.restoreForPause()
            labels.enforceNativeVisibility()
            labels.maintainNativeSizing()
            assertTrue(labels.nativeWhilePaused)
            assertFalse(labels.hasCustomContent)
            assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, capsule.layoutParams.width)
            assertEquals(24, capsule.minimumWidth)
            assertEquals(View.VISIBLE, artwork.visibility)
            assertEquals(View.VISIBLE, waveform.visibility)
            assertEquals(1, title.frame.childCount)

            capsule.minimumWidth = 30
            labels.restoreForPause()
            labels.render(line, settings, artwork = null)
            assertFalse(labels.nativeWhilePaused)
            assertTrue(labels.hasCustomContent)
            assertEquals(View.INVISIBLE, waveform.visibility)
            labels.close()
            assertEquals(30, capsule.minimumWidth)
        }
    }

    @Test
    fun interludeViewportKeepsNativeLayoutAndYieldsToSystemTransitions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val capsule = FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 40)
                minimumWidth = 24
            }
            fun slot(): NativeContract.Slot {
                val frame = FrameLayout(context)
                val stock = View(context)
                frame.addView(stock)
                capsule.addView(frame)
                return NativeContract.Slot(frame, stock)
            }
            val contentPage = FrameLayout(context).also(capsule::addView)
            val left = FrameLayout(context).also(contentPage::addView)
            val right = FrameLayout(context).also(capsule::addView)
            val cover = View(context).also(left::addView)
            val waveform = View(context).also(right::addView)
            val labels = CapsuleLabels(NativeContract.Target(capsule, slot(), slot(), cover, waveform, left, right))
            val settings = CapsuleSettings(introDots = true, showCover = true, showWaveform = true)
            val frame = LyricFrame.interlude(2000L, 9500L, LyricRows("Previous"))
            val clock = PlaybackClock(2000L, 1000L, 1f, true, 15000L)
            labels.render(frame, settings, artwork = null, clock = clock)
            val normal = capsule.layoutParams.width
            capsule.layout(0, 0, normal, 40)
            contentPage.layout(0, 0, normal, 40)
            left.layout(8, 0, 28, 40)
            right.layout(normal - 28, 0, normal - 8, 40)
            cover.layout(0, 0, 20, 40)
            waveform.layout(0, 0, 20, 40)
            val nativeOutline = capsule.outlineProvider
            val nativeClip = capsule.clipToOutline

            fun width(elapsed: Long, canCompact: Boolean = true, resumeInterlude: Boolean = false,
                      animatedWidth: Int? = null): Int {
                labels.prepareViewport(1000L + elapsed, canCompact, resumeInterlude, animatedWidth)
                assertEquals(normal, capsule.layoutParams.width)
                assertEquals(normal, capsule.minimumWidth)
                if (capsule.outlineProvider === nativeOutline) return normal
                assertTrue(capsule.clipToOutline)
                val outline = Outline().also { capsule.outlineProvider.getOutline(capsule, it) }
                val rect = Rect()
                assertTrue(outline.getRect(rect))
                assertEquals(rect.left, normal - rect.right)
                assertEquals(rect.left.toFloat(), left.translationX + cover.translationX, 0f)
                assertEquals(-rect.left.toFloat(), right.translationX + waveform.translationX, 0f)
                return rect.width()
            }
            assertTrue(labels.hasInterludeViewport)
            assertEquals(normal, width(0L))
            assertEquals(normal, width(260L))
            val compact = width(500L)
            assertTrue(compact < normal)
            labels.maintainNativeSizing()
            assertEquals(normal, capsule.layoutParams.width)
            assertTrue(width(3500L) > compact)
            assertTrue(width(7300L) in (compact + 1) until normal)
            assertEquals(normal, width(7490L))
            labels.render(LyricFrame(LyricRows("Next"), 9500L, 12000L), settings, artwork = null)
            assertFalse(labels.hasInterludeViewport)
            assertSame(nativeOutline, capsule.outlineProvider)

            labels.render(frame, settings, artwork = null, clock = clock)
            assertEquals(normal, width(500L))
            assertTrue(width(750L) < normal)
            assertEquals(normal, width(800L, canCompact = false))
            assertSame(nativeOutline, capsule.outlineProvider)
            assertEquals(nativeClip, capsule.clipToOutline)
            assertEquals(0f, left.translationX, 0f)
            assertEquals(0f, right.translationX, 0f)
            // A returning media card already animated the handoff. Its first visible frame
            // must use the current interlude width and stay there when ordinary drawing resumes.
            val resumed = width(900L, resumeInterlude = true)
            assertTrue(resumed < normal)
            assertTrue(width(916L) < normal)

            // An OEM transition may animate both the clipping edge and the cover transform.
            cover.translationX = -12f
            left.translationX = -18f
            val narrower = resumed - 20
            assertEquals(narrower, width(940L, resumeInterlude = true, animatedWidth = narrower))
            assertEquals(normal, width(950L, canCompact = false))
            assertEquals(-18f, left.translationX, 0f)
            assertEquals(-12f, cover.translationX, 0f)

            // The content page moves independently of the cover slot during a native handoff.
            contentPage.pivotX = normal / 2f
            contentPage.scaleX = 0.8f
            contentPage.translationX = -60f
            cover.alpha = 0.4f
            fun coverCenterInCapsule(): Float {
                val center = floatArrayOf(cover.width / 2f, cover.height / 2f)
                for (view in listOf(cover, left, contentPage)) {
                    view.matrix.mapPoints(center)
                    center[0] += view.left
                    center[1] += view.top
                }
                return center[0]
            }
            for (animatedWidth in listOf(26, 80, narrower)) {
                assertTrue(labels.prepareViewport(1952L, true, resumeInterlude = true, animatedWidth = animatedWidth))
                val leftEdge = (normal - animatedWidth) / 2
                assertEquals(minOf(normal / 2f, leftEdge + 18f), coverCenterInCapsule(), 0.001f)
            }
            // Some OEM frames already have camera-sized layout bounds with no extra clipping.
            capsule.layout(0, 0, 26, 40)
            assertTrue(labels.prepareViewport(1954L, true, resumeInterlude = true))
            assertSame(nativeOutline, capsule.outlineProvider)
            assertEquals(13f, coverCenterInCapsule(), 0.001f)
            labels.suspendViewport()
            assertEquals(-18f, left.translationX, 0f)
            assertEquals(-60f, contentPage.translationX, 0f)
            assertEquals(0.8f, contentPage.scaleX, 0f)
            assertEquals(0.4f, cover.alpha, 0f)
            capsule.layout(0, 0, normal, 40)
            contentPage.translationX = 0f
            contentPage.scaleX = 1f
            left.translationX = 0f
            cover.translationX = 0f
            assertEquals(normal, width(960L))
            assertTrue(width(1210L) < normal)

            // If the OEM moves a section independently, do not overwrite its translation.
            right.translationX = 28f
            assertEquals(normal, width(1220L))
            assertEquals(28f, right.translationX, 0f)
            assertEquals(0f, left.translationX, 0f)
            right.translationX = 0f
            assertEquals(normal, width(1240L))
            assertTrue(width(1490L) < normal)
            labels.restoreForPause()
            assertSame(nativeOutline, capsule.outlineProvider)
            assertEquals(nativeClip, capsule.clipToOutline)
            assertEquals(24, capsule.minimumWidth)
            assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, capsule.layoutParams.width)
        }
    }

    @Test
    fun introCountdownKeepsScrollingTrackInfoLeftAndTheThreeDotsRight() {
        val strip = CapsuleLyricStrip(1f)
        strip.update(LyricFrame.intro(3000L, LyricRows("ABCDEFGHIJKLMNOPQRSTUVWXYZ".repeat(2), "Artist")),
            CapsuleSettings(introDots = true, lyricAlignment = LyricAlignment.LEFT))
        val image = Bitmap.createBitmap(200, 40, Bitmap.Config.ARGB_8888)
        try {
            val camera = CapsuleCamera(124f, 16f)
            fun draw(position: Long): Long? {
                image.eraseColor(Color.TRANSPARENT)
                return strip.draw(Canvas(image), 0f, 0f, 200f, 40f, camera, position)
            }
            fun rows(y: Int): IntArray = IntArray(108 * 20).also { image.getPixels(it, 0, 108, 0, y, 108, 20) }

            assertEquals(0L, draw(0L))
            val titleAtStart = rows(0)
            val artistAtStart = rows(20)
            assertTrue(image.ink(0 until 108, 0 until 20) > 0)
            assertTrue(image.ink(0 until 108, 20 until 40) > 0)
            assertEquals(0L, draw(1500L))
            assertFalse(titleAtStart.contentEquals(rows(0)))
            assertArrayEquals(artistAtStart, rows(20))
            assertEquals(0, image.ink(108 until 140, 0 until 40))
            val starts = (140 until 199).count { x -> image.pixelAlpha(x, 20) <= 24 && image.pixelAlpha(x + 1, 20) > 24 }
            assertEquals(3, starts)
            assertNull(draw(3000L))
            assertEquals(0, image.ink(0 until 200, 0 until 40))
        } finally {
            image.recycle()
        }
    }

    @Test
    fun introDotsUseTheFixedLargerRadius() {
        val strip = CapsuleLyricStrip(2f)
        val frame = LyricFrame.intro(3000L, LyricRows("Title", "Artist"))
        val image = Bitmap.createBitmap(320, 64, Bitmap.Config.ARGB_8888)
        try {
            strip.update(frame, CapsuleSettings(introDots = true))
            strip.draw(Canvas(image), 0f, 0f, 320f, 64f, CapsuleCamera(160f, 24f), 1500L)
            // The center dot is at x=252 with radius a little over six physical pixels.
            assertTrue(image.pixelAlpha(257, 32) > 24)
            assertEquals(0, image.pixelAlpha(259, 32))
        } finally {
            image.recycle()
        }
    }

    @Test
    fun countdownDrawsAsTheOutgoingFrameForUpAndFadeButNotWhenDisabled() {
        val strip = CapsuleLyricStrip(1f)
        val image = Bitmap.createBitmap(220, 40, Bitmap.Config.ARGB_8888)
        val frame = LyricFrame(LyricRows("First lyric"), 9000L, 11000L,
            priorCountdown = LyricCountdown(2000L, 9000L))
        try {
            fun draw(motion: LineMotion, position: Long) {
                image.eraseColor(Color.TRANSPARENT)
                strip.update(frame, CapsuleSettings(introDots = true, motion = motion, lyricAlignment = LyricAlignment.LEFT))
                strip.draw(Canvas(image), 0f, 0f, 220f, 40f, CapsuleCamera(120f, 12f), position)
            }
            for (motion in listOf(LineMotion.UP, LineMotion.FADE)) {
                draw(motion, 9000L)
                assertTrue(image.ink(132 until 220, 0 until 40) > 0)
                assertEquals(0, image.ink(0 until 108, 0 until 40))
                draw(motion, 9260L)
                assertEquals(0, image.ink(132 until 220, 0 until 40))
                assertTrue(image.ink(0 until 108, 0 until 40) > 0)
            }
            draw(LineMotion.NONE, 9000L)
            assertEquals(0, image.ink(132 until 220, 0 until 40))
            assertTrue(image.ink(0 until 108, 0 until 40) > 0)
        } finally {
            image.recycle()
        }
    }

    @Test
    fun enteringInterludeUsesTheSelectedLineMotion() {
        val start = 2500L
        val timeline = Timeline.fromLines(listOf(LyricLine(0L, "First lyric", endMs = start), LyricLine(9500L, "Next")))
        val strip = CapsuleLyricStrip(1f)
        val image = Bitmap.createBitmap(220, 40, Bitmap.Config.ARGB_8888)
        val leftSide = 0 until 108
        val rightSide = 132 until 220
        val height = 0 until 40
        try {
            fun draw(motion: LineMotion, elapsed: Long) {
                val settings = CapsuleSettings(introDots = true, motion = motion, lyricAlignment = LyricAlignment.LEFT)
                val presentation = LyricPresentation(timeline, settings)
                val position = start + elapsed
                image.eraseColor(Color.TRANSPARENT)
                strip.update(presentation.frameAt(presentation.indexAt(position), 15000L), settings)
                strip.draw(Canvas(image), 0f, 0f, 220f, 40f, CapsuleCamera(120f, 12f), position)
            }

            for (motion in listOf(LineMotion.UP, LineMotion.FADE)) {
                draw(motion, 0L)
                assertTrue(image.ink(leftSide, height) > 0)
                assertEquals(0, image.ink(rightSide, height))

                draw(motion, 130L)
                if (motion == LineMotion.UP) {
                    assertTrue(image.ink(rightSide, 27..33) > 0)
                    assertEquals(0, image.ink(rightSide, 17..23))
                } else {
                    assertTrue(image.ink(rightSide, 17..23) > 0)
                    assertTrue(image.ink(leftSide, height) > 0)
                }

                draw(motion, 260L)
                assertEquals(0, image.ink(leftSide, height))
                assertTrue(image.ink(rightSide, 17..23) > 0)
            }
            draw(LineMotion.NONE, 0L)
            assertEquals(0, image.ink(leftSide, height))
            assertTrue(image.ink(rightSide, 17..23) > 0)
        } finally {
            image.recycle()
        }
    }

    @Test
    fun wordHighlightStartsBelowAndSettlesOntoTheOrdinaryBaseline() {
        val strip = CapsuleLyricStrip(2f)
        val bitmap = Bitmap.createBitmap(320, 80, Bitmap.Config.ARGB_8888)
        val words = listOf(LyricWord(0, 2, 1000L, 1500L), LyricWord(2, 3, 2000L, 2500L))
        val text = "H I"
        fun draw(rows: LyricRows, position: Long): List<Int> {
            bitmap.eraseColor(Color.TRANSPARENT)
            strip.update(LyricFrame(rows, 1000L, 4000L), CapsuleSettings(wordLyrics = true))
            strip.draw(Canvas(bitmap), 0f, 0f, 320f, 80f, null, position)
            return (0 until 70).flatMap { x -> (0 until 80).map { y -> bitmap.pixelAlpha(x, y) } }
        }
        try {
            val plain = draw(LyricRows(text), 2500L)
            val timed = LyricRows(text, firstWords = words)
            val unplayed = draw(timed, 900L)
            val active = draw(timed, 1250L)
            val completed = draw(timed, 2500L)
            fun firstGlyphTop(pixels: List<Int>) = (0 until 18 * 80)
                .filter { pixels[it] > 24 }.minOf { it % 80 }
            assertEquals(firstGlyphTop(plain) + 2, firstGlyphTop(unplayed))
            assertEquals(firstGlyphTop(plain), firstGlyphTop(active))
            assertEquals(plain, completed)
            assertTrue(active.sum() < completed.sum())
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun sustainedWordLiftsAndGlowsOnlyInTheFirstRow() {
        val strip = CapsuleLyricStrip(2f)
        val width = 160
        val height = 80
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val words = listOf(LyricWord(0, 1, 1000L, 3000L))
        val rows = LyricRows("H", "H", firstWords = words, secondWords = words)
        strip.update(LyricFrame(rows, 1000L, 4000L), CapsuleSettings(wordLyrics = true))
        fun draw(position: Long): IntArray {
            bitmap.eraseColor(Color.TRANSPARENT)
            strip.draw(Canvas(bitmap), 0f, 0f, width.toFloat(), height.toFloat(), null, position)
            return IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
        }
        fun inkTop(pixels: IntArray, ys: IntRange) = ys.first { y ->
            (0 until 32).any { x -> Color.alpha(pixels[y * width + x]) > 100 }
        }
        try {
            val settled = draw(3000L)
            val sustained = draw(2400L)
            assertTrue(inkTop(sustained, 0 until 40) < inkTop(settled, 0 until 40))
            assertEquals(inkTop(settled, 41 until height), inkTop(sustained, 41 until height))
            // After compensating for the 2 px lift, the halo reaches pixels outside the settled glyph.
            assertTrue((2 until 37).any { y -> (0 until 32).any { x ->
                Color.alpha(sustained[y * width + x]) > 3 && Color.alpha(settled[(y + 2) * width + x]) == 0
            } })
            assertArrayEquals(settled, draw(3300L))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun activeGlyphBrightensFromLeftToRightWithASoftEdge() {
        val strip = CapsuleLyricStrip(4f)
        val width = 320
        val height = 120
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val rows = LyricRows("H", firstWords = listOf(LyricWord(0, 1, 1000L, 2000L)))
        val ink = LyricGradient(Color.RED, Color.MAGENTA, Color.BLUE)
        strip.update(LyricFrame(rows, 1000L, 3000L), CapsuleSettings(wordLyrics = true),
            LyricInk(ink.center, ink.center, gradient = ink))
        fun draw(position: Long): IntArray {
            bitmap.eraseColor(Color.TRANSPARENT)
            strip.draw(Canvas(bitmap), 0f, 0f, width.toFloat(), height.toFloat(), null, position)
            return IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
        }
        try {
            val sung = draw(2000L)
            val halfway = draw(1500L)
            val later = draw(1850L)
            val solidPixels = sung.indices.filter { Color.alpha(sung[it]) >= 248 }
            val left = solidPixels.minBy { it % width }
            val right = solidPixels.maxBy { it % width }
            assertTrue(Color.alpha(halfway[left]) >= 246)
            assertTrue(Color.alpha(halfway[right]) in 134..145)
            assertTrue(solidPixels.any { Color.alpha(halfway[it]) in 155..230 })
            assertTrue(solidPixels.any { Color.alpha(later[it]) - Color.alpha(halfway[it]) >= 50 })
            for (channel in listOf(Color::red, Color::green, Color::blue)) {
                assertEquals(channel(sung[right]).toFloat(), channel(halfway[right]).toFloat(), 3f)
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun secondRowKeepsItsBaselineWithTimedOriginalOrUpcomingLyrics() {
        val strip = CapsuleLyricStrip(2f)
        val bitmap = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888)
        val words = listOf(LyricWord(0, 1, 1000L, 1500L))
        fun secondGlyph(nextStart: Long?, ranges: List<LyricWord>, position: Long): Pair<Int, Int> {
            val rows = LyricRows("Title", "H", nextLyricStartMs = nextStart, secondWords = ranges)
            strip.update(LyricFrame(rows, 0L, 4000L), CapsuleSettings(wordLyrics = true))
            bitmap.eraseColor(Color.TRANSPARENT)
            strip.draw(Canvas(bitmap), 0f, 0f, 160f, 100f, null, position)
            val box = 50 until 90
            return box.first { bitmap.ink(0 until 30, it..it) > 0 } to
                box.sumOf { y -> (0 until 30).sumOf { x -> bitmap.pixelAlpha(x, y) } }
        }
        try {
            val plain = secondGlyph(null, emptyList(), 900L)
            val unplayed = secondGlyph(null, words, 900L)
            val sung = secondGlyph(null, words, 1500L)
            assertEquals(plain.first, unplayed.first)
            assertTrue(unplayed.second < plain.second)
            assertEquals(plain, sung)
            val plainPreview = secondGlyph(1000L, emptyList(), 900L)
            val timedPreview = secondGlyph(1000L, words, 900L)
            assertEquals(plainPreview.first, timedPreview.first)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun untimedTranslationScrollsWithTheTimedOriginalInTheSecondRow() {
        val strip = CapsuleLyricStrip(2f)
        val words = listOf(LyricWord(0, 6, 1000L, 1400L), LyricWord(6, 14, 2000L, 2800L),
            LyricWord(14, 20, 2840L, 3200L))
        val frame = LyricFrame(LyricRows("ABCDEFGHIJKLMNOPQRSTUVWXYZ".repeat(2), "H".repeat(20),
            secondWords = words), 1000L, 4000L)
        val image = Bitmap.createBitmap(140, 80, Bitmap.Config.ARGB_8888)
        fun draw(position: Long): Pair<Long?, IntArray> {
            image.eraseColor(Color.TRANSPARENT)
            val delay = strip.draw(Canvas(image), 0f, 0f, 140f, 80f, null, position)
            val firstRow = IntArray(140 * 38)
            image.getPixels(firstRow, 0, 140, 0, 0, 140, 38)
            return delay to firstRow
        }
        try {
            strip.update(frame, CapsuleSettings(wordLyrics = true))
            val (_, atStart) = draw(1000L)
            val (openingDelay, beforeFollowPoint) = draw(1100L)
            val (nextWordDelay, atFirstWordEnd) = draw(1400L)
            val (remainingDelay, duringGap) = draw(1750L)
            val (activeDelay, duringSecondWord) = draw(2400L)
            val (_, atSecondWordEnd) = draw(2800L)
            val (shortGapDelay, duringShortGap) = draw(2810L)
            val (afterLastDelay, _) = draw(3200L)
            assertEquals(0L, openingDelay)
            assertEquals(600L, nextWordDelay)
            assertEquals(250L, remainingDelay)
            assertEquals(0L, activeDelay)
            assertEquals(30L, shortGapDelay)
            assertNull(afterLastDelay)
            assertArrayEquals(atStart, beforeFollowPoint)
            assertArrayEquals(atFirstWordEnd, duringGap)
            assertArrayEquals(atSecondWordEnd, duringShortGap)
            assertFalse(duringGap.contentEquals(duringSecondWord))
        } finally {
            image.recycle()
        }
    }

    @Test
    fun coverGradientKeepsTheSameGlyphColorsAcrossTheCameraGap() {
        val strip = CapsuleLyricStrip(2f)
        val settings = CapsuleSettings(cameraFade = false, lyricAlignment = LyricAlignment.LEFT)
        val gradient = LyricGradient(Color.RED, Color.MAGENTA, Color.BLUE)
        val image = Bitmap.createBitmap(280, 80, Bitmap.Config.ARGB_8888)
        val reference = Bitmap.createBitmap(240, 80, Bitmap.Config.ARGB_8888)
        try {
            strip.update(LyricFrame(LyricRows("HHHHHHHHHH"), 0L, 4000L), settings,
                LyricInk(gradient.start, gradient.start, gradient = gradient))
            strip.draw(Canvas(image), 0f, 0f, 280f, 80f, CapsuleCamera(105f, 20f), 1200L)
            strip.draw(Canvas(reference), 0f, 0f, 240f, 80f, null, 1200L)
            var firstDifference = 0
            var lastDifference = 0
            for (x in 0 until 240) for (y in 0 until 80) {
                val pixel = reference.getPixel(x, y)
                assertEquals(pixel, image.getPixel(if (x < 85) x else x + 40, y))
                if (Color.alpha(pixel) < 100) continue
                val difference = Color.red(pixel) - Color.blue(pixel)
                if (x in 0 until 42) firstDifference += difference
                if (x in 105 until 240) lastDifference += difference
            }
            assertTrue(firstDifference > 0)
            assertTrue(lastDifference < 0)
            assertEquals(0, image.ink(85 until 125, 0 until 80))
        } finally {
            image.recycle()
            reference.recycle()
        }
    }

    @Test
    fun capsuleWidthOvershootContinuesTheArtworkAcrossBothEdges() {
        val artwork = Bitmap.createBitmap(20, 8, Bitmap.Config.ARGB_8888)
        val normal = Bitmap.createBitmap(160, 48, Bitmap.Config.ARGB_8888)
        val expanded = Bitmap.createBitmap(160, 48, Bitmap.Config.ARGB_8888)
        try {
            val half = Paint().apply { color = Color.RED }
            val source = Canvas(artwork)
            source.drawRect(0f, 0f, 10f, 8f, half)
            half.color = Color.GREEN
            source.drawRect(10f, 0f, 20f, 8f, half)
            val layout = RectF(20f, 4f, 140f, 44f)
            val reveal = RectF(8f, 4f, 152f, 44f)
            val background = CapsuleArtworkBackground()
            background.draw(Canvas(normal), artwork, layout, layout, 30)
            background.draw(Canvas(expanded), artwork, layout, reveal, 30)
            for (x in 20 until 140) assertEquals(normal.getPixel(x, 24), expanded.getPixel(x, 24))
            for (x in 8 until 20) assertEquals(normal.getPixel(20, 24), expanded.getPixel(x, 24))
            for (x in 140 until 152) assertEquals(normal.getPixel(139, 24), expanded.getPixel(x, 24))
            assertEquals(0, expanded.pixelAlpha(7, 24))
            assertEquals(0, expanded.pixelAlpha(152, 24))
        } finally {
            artwork.recycle()
            normal.recycle()
            expanded.recycle()
        }
    }

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
            strip.update(frame, CapsuleSettings(cameraFade = fade))
            assertTrue(strip.paintDiagnostics().endsWith(if (fade) "cameraFadePx=7.0" else "cameraFadePx=0.0"))
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
    fun wholeWordAvoidanceClearsTheRightFadeAtRestButCanScrollIntoIt() {
        val strip = CapsuleLyricStrip(3f)
        val frame = LyricFrame(LyricRows("HHHHHHHHHHHHHHHHHHHHHHHHHHHHHH"), 0, 4000)
        val settings = CapsuleSettings(cameraWordAvoidance = true, lyricAlignment = LyricAlignment.LEFT)
        val camera = CapsuleCamera(180f, 10f)
        val buffer = 190 until 196
        val ys = 0 until 60
        val faded = Bitmap.createBitmap(360, 60, Bitmap.Config.ARGB_8888)
        val sharp = Bitmap.createBitmap(360, 60, Bitmap.Config.ARGB_8888)
        fun draw(image: Bitmap, fade: Boolean, position: Long) {
            image.eraseColor(Color.TRANSPARENT)
            strip.update(frame, settings.copy(cameraFade = fade))
            strip.draw(Canvas(image), 20f, 0f, 340f, 60f, camera, position)
        }
        try {
            draw(faded, true, 0)
            draw(sharp, false, 0)
            assertEquals(0, faded.ink(buffer, ys))
            assertEquals(0, sharp.ink(buffer, ys))
            assertTrue(faded.ink(196 until 245, ys) > 10)
            for (x in 190 until 245) for (y in ys) {
                assertEquals(sharp.pixelAlpha(x, y), faded.pixelAlpha(x, y))
            }
            assertTrue((400L..1200L step 20).any { position ->
                draw(faded, true, position)
                draw(sharp, false, position)
                buffer.any { x -> ys.any { y ->
                    val clear = sharp.pixelAlpha(x, y)
                    val soft = faded.pixelAlpha(x, y)
                    soft in 1 until clear
                } }
            })
        } finally {
            faded.recycle()
            sharp.recycle()
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
