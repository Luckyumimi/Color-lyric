package shop.terramoor.github.io

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import shop.terramoor.github.io.lyrics.LyricFrame
import shop.terramoor.github.io.lyrics.LyricRows
import shop.terramoor.github.io.lyrics.LyricWord
import shop.terramoor.github.io.lyrics.TranslationRow
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.systemui.CapsuleLyricStrip
import shop.terramoor.github.io.systemui.JapaneseLyricFont
import shop.terramoor.github.io.systemui.LyricInk
import kotlin.math.ceil

@RunWith(AndroidJUnit4::class)
class JapaneseLyricFontTest {
    @Test
    fun customFontIsRequestedForAllLanguagesAndKeepsTranslationRegular() {
        val font = requireNotNull(JapaneseLyricFont(InstrumentationRegistry.getInstrumentation().targetContext).typeface(false))
        val calls = mutableListOf<Pair<Boolean, Boolean>>()
        val strip = CapsuleLyricStrip(3f).apply {
            customTypeface = { bold, translation -> calls.add(bold to translation); font }
        }
        val settings = CapsuleSettings(japaneseLyricFont = true, lyricBold = true,
            customLyricFontId = "0123456789abcdef0123456789abcdef")
        for (row in TranslationRow.entries) {
            calls.clear()
            val rows = if (row == TranslationRow.FIRST) LyricRows("中文", "English", translationRow = row)
                else LyricRows("English", "中文", translationRow = row)
            strip.update(LyricFrame(rows, 0, 3000), settings)
            assertEquals(setOf(true to true, true to false), calls.toSet())
        }
        calls.clear()
        strip.update(LyricFrame(LyricRows("English", "中文", translationRow = TranslationRow.SECOND), 0, 3000),
            settings.copy(japaneseLyricFont = false))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun translationsKeepRegularWeightInEitherRow() {
        val font = requireNotNull(JapaneseLyricFont(InstrumentationRegistry.getInstrumentation().targetContext).typeface(true))
        val calls = mutableListOf<Boolean>()
        val strip = CapsuleLyricStrip(3f) { bold -> calls.add(bold); font }
        val lyric = "\u97f3\u697d\u306e\u58f0"
        val translation = "\u3053\u3068\u3070"
        val settings = CapsuleSettings(japaneseLyricFont = true, lyricBold = true, cameraFade = false)
        for (row in TranslationRow.entries) {
            calls.clear()
            val rows = if (row == TranslationRow.FIRST) LyricRows(translation, lyric, translationRow = row)
                else LyricRows(lyric, translation, translationRow = row)
            strip.update(LyricFrame(rows, 0, 3000), settings)
            assertEquals(listOf(true), calls)
        }
        val normal = Bitmap.createBitmap(320, 100, Bitmap.Config.ARGB_8888)
        val bold = Bitmap.createBitmap(320, 100, Bitmap.Config.ARGB_8888)
        try {
            val frame = LyricFrame(LyricRows(translation, translationRow = TranslationRow.FIRST), 0, 3000)
            strip.update(frame, settings.copy(lyricBold = false))
            strip.draw(Canvas(normal), 10f, 0f, 310f, 100f, null, 0)
            strip.update(frame, settings)
            strip.draw(Canvas(bold), 10f, 0f, 310f, 100f, null, 0)
            assertTrue(normal.sameAs(bold))
        } finally {
            normal.recycle()
            bold.recycle()
        }
    }

    @Test
    fun boldSwitchSelectsTheRequestedWeightForSystemAndJapaneseText() {
        val fonts = JapaneseLyricFont(InstrumentationRegistry.getInstrumentation().targetContext)
        val regular = requireNotNull(fonts.typeface(false))
        val bold = requireNotNull(fonts.typeface(true))
        assertEquals(600, regular.weight)
        assertEquals(700, bold.weight)
        assertTrue(Paint().apply { typeface = bold }.hasGlyph("\u4e02"))
        val calls = mutableListOf<Boolean>()
        val strip = CapsuleLyricStrip(3f) { useBold ->
            calls.add(useBold)
            if (useBold) bold else regular
        }
        val normal = Bitmap.createBitmap(400, 120, Bitmap.Config.ARGB_8888)
        val strong = Bitmap.createBitmap(400, 120, Bitmap.Config.ARGB_8888)
        try {
            for (japanese in listOf(false, true)) {
                calls.clear()
                normal.eraseColor(Color.TRANSPARENT)
                strong.eraseColor(Color.TRANSPARENT)
                val frame = LyricFrame(LyricRows("\u97f3\u697d\u306e\u58f0", "TEXT"), 0, 3000)
                val settings = CapsuleSettings(japaneseLyricFont = japanese, lyricBold = false, cameraFade = false)
                strip.update(frame, settings)
                strip.draw(Canvas(normal), 10f, 0f, 390f, 120f, null, 0)
                strip.update(frame, settings.copy(lyricBold = true))
                strip.draw(Canvas(strong), 10f, 0f, 390f, 120f, null, 0)
                assertEquals(if (japanese) listOf(false, true) else emptyList(), calls)
                assertTrue(!normal.sameAs(strong))
            }
        } finally {
            normal.recycle()
            strong.recycle()
        }
    }

    @Test
    fun japaneseFontDescendersRemainVisibleDuringWordHighlight() {
        val font = requireNotNull(JapaneseLyricFont(InstrumentationRegistry.getInstrumentation().targetContext).typeface(false))
        val strip = CapsuleLyricStrip(5f) { font }
        val text = "\u3042gy"
        val settings = CapsuleSettings(japaneseLyricFont = true, lyricBold = false, cameraFade = false)
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        fun layout(face: Typeface): StaticLayout {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                textSize = 60f
                typeface = face
            }
            val width = ceil(Layout.getDesiredWidth(text, paint)).toInt() + 2
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setIncludePad(false).setMaxLines(1).build()
        }
        val reference = layout(Typeface.create(Typeface.DEFAULT, 600, false))
        val custom = layout(font)
        val bounds = Rect()
        custom.paint.getTextBounds(text, 0, text.length, bounds)
        val baseline = (200f - reference.height) / 2f + reference.getLineBaseline(0)
        val expectedBottom = baseline + bounds.bottom
        try {
            val words = listOf(LyricWord(0, 1, 1000, 1500), LyricWord(1, 3, 1500, 2000))
            for (timed in listOf(false, true)) {
                bitmap.eraseColor(Color.TRANSPARENT)
                val rows = LyricRows(text, firstWords = if (timed) words else emptyList())
                strip.update(LyricFrame(rows, 1000, 3000), settings)
                strip.draw(Canvas(bitmap), 20f, 0f, 380f, 200f, null, 1750)
                val lastInk = (0 until 200).last { y ->
                    (20 until 380).any { x -> Color.alpha(bitmap.getPixel(x, y)) > 0 }
                }
                assertTrue("Descender clipped, timed=$timed, bottom=$lastInk expected=$expectedBottom",
                    lastInk >= expectedBottom - 2f)
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun changingJapaneseFontKeepsTheOtherRowInPlace() {
        val font = JapaneseLyricFont(InstrumentationRegistry.getInstrumentation().targetContext).typeface(false)
        assertNotNull(font)
        val strip = CapsuleLyricStrip(3f) { font }
        val ink = LyricInk(Color.RED, Color.GREEN)
        val settings = CapsuleSettings(cameraFade = false)
        val original = Bitmap.createBitmap(400, 120, Bitmap.Config.ARGB_8888)
        val changed = Bitmap.createBitmap(400, 120, Bitmap.Config.ARGB_8888)
        try {
            for (next in listOf(false, true)) {
                for (japaneseFirst in listOf(false, true)) {
                    val japanese = "\u97f3\u697d\u306e\u58f0"
                    val latin = "ALPHA BETA"
                    val rows = if (japaneseFirst) LyricRows(japanese, latin, nextLyricStartMs = if (next) 3000 else null)
                        else LyricRows(latin, japanese, nextLyricStartMs = if (next) 3000 else null)
                    val frame = LyricFrame(rows, 0, 3000)
                    original.eraseColor(Color.TRANSPARENT)
                    changed.eraseColor(Color.TRANSPARENT)
                    strip.update(frame, settings.copy(japaneseLyricFont = false), ink)
                    strip.draw(Canvas(original), 10f, 0f, 390f, 120f, null, 0)
                    strip.update(frame, settings.copy(japaneseLyricFont = true), ink)
                    strip.draw(Canvas(changed), 10f, 0f, 390f, 120f, null, 0)
                    var pixels = 0
                    for (y in 0 until 120) for (x in 0 until 400) {
                        val before = original.getPixel(x, y)
                        val after = changed.getPixel(x, y)
                        val beforeRow = if (japaneseFirst) Color.green(before) > Color.red(before)
                            else Color.red(before) > Color.green(before)
                        val afterRow = if (japaneseFirst) Color.green(after) > Color.red(after)
                            else Color.red(after) > Color.green(after)
                        if (beforeRow || afterRow) {
                            assertEquals("row moved at ($x, $y), next=$next", before, after)
                            pixels++
                        }
                    }
                    assertTrue(pixels > 0)
                }
            }
        } finally {
            original.recycle()
            changed.recycle()
        }
    }

    @Test
    fun bundledFontLoadsAndOnlyKanaRowsRequestItWhenEnabled() {
        val font = JapaneseLyricFont(InstrumentationRegistry.getInstrumentation().targetContext).typeface(false)
        assertNotNull(font)
        var loads = 0
        val strip = CapsuleLyricStrip(3f) { loads++; font }
        val settings = CapsuleSettings(japaneseLyricFont = true)
        val japanese = LyricFrame(LyricRows("\u97f3\u697d\u306e\u58f0", "\u6b4c\u66f2\u306e\u4e16\u754c"), 0, 3000)
        val chinese = LyricFrame(LyricRows("\u97f3\u4e50", "\u4e16\u754c"), 0, 3000)
        val normal = Bitmap.createBitmap(400, 100, Bitmap.Config.ARGB_8888)
        val custom = Bitmap.createBitmap(400, 100, Bitmap.Config.ARGB_8888)
        try {
            strip.update(japanese, settings.copy(japaneseLyricFont = false))
            strip.draw(Canvas(normal), 10f, 0f, 390f, 100f, null, 0)
            strip.update(chinese, settings)
            assertEquals(0, loads)
            strip.update(japanese, settings)
            assertEquals(2, loads)
            strip.draw(Canvas(custom), 10f, 0f, 390f, 100f, null, 0)
            assertTrue(!normal.sameAs(custom))
        } finally {
            normal.recycle()
            custom.recycle()
        }
    }
}
