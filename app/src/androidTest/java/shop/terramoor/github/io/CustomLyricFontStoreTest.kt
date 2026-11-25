package shop.terramoor.github.io

import android.graphics.fonts.Font
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import shop.terramoor.github.io.settings.CustomLyricFontStore
import shop.terramoor.github.io.settings.isCustomFontId
import shop.terramoor.github.io.systemui.JapaneseLyricFont
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals

@RunWith(AndroidJUnit4::class)
class CustomLyricFontStoreTest {
    @Test fun asynchronouslyLoadsCustomFontForLyricsAndRegularTranslation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = UUID.randomUUID().toString().replace("-", "")
        val file = CustomLyricFontStore.file(context, id)
        file.parentFile?.mkdirs()
        val ready = CountDownLatch(1)
        lateinit var fonts: JapaneseLyricFont
        try {
            context.assets.open("fonts/japanese_lyric_w6.ttf").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            instrumentation.runOnMainSync {
                fonts = JapaneseLyricFont(context, onCustomFontReady = { ready.countDown() })
                fonts.requestCustom(id)
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                assertEquals(600, requireNotNull(fonts.customTypeface(false, false)).weight)
                assertEquals(700, requireNotNull(fonts.customTypeface(true, false)).weight)
                assertEquals(400, requireNotNull(fonts.customTypeface(true, true)).weight)
                fonts.requestCustom(null)
                assertEquals(null, fonts.customTypeface(true, false))
            }
        } finally {
            CustomLyricFontStore.delete(context, id)
        }
    }

    @Test fun importsAReadableTtfAndRejectsInvalidBytesWithoutDeletingTheSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceId = UUID.randomUUID().toString().replace("-", "")
        val source = CustomLyricFontStore.file(context, sourceId)
        source.parentFile?.mkdirs()
        var imported: String? = null
        try {
            context.assets.open("fonts/japanese_lyric_w6.ttf").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            imported = CustomLyricFontStore.import(context, CustomLyricFontStore.uri(context.packageName, sourceId))
            assertTrue(isCustomFontId(imported))
            context.contentResolver.openFileDescriptor(CustomLyricFontStore.uri(context.packageName, imported), "r").use {
                assertNotNull(Font.Builder(requireNotNull(it)).build())
            }
            source.writeBytes(byteArrayOf(0, 1, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8))
            assertTrue(runCatching {
                CustomLyricFontStore.import(context, CustomLyricFontStore.uri(context.packageName, sourceId))
            }.isFailure)
            assertTrue(source.exists())
            assertTrue(CustomLyricFontStore.file(context, imported).exists())
        } finally {
            CustomLyricFontStore.delete(context, sourceId)
            CustomLyricFontStore.delete(context, imported)
        }
        assertFalse(source.exists())
    }
}
