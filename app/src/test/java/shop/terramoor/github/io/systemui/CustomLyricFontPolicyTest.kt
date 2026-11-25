package shop.terramoor.github.io.systemui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import shop.terramoor.github.io.settings.CapsuleBackup
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.isCustomFontId

class CustomLyricFontPolicyTest {
    private val fontId = "0123456789abcdef0123456789abcdef"

    @Test fun customFontAppliesToEveryLanguageAndTranslation() {
        for (text in listOf("Hello world", "中文歌词", "日本語の歌詞", "\uD55C\uAD6D\uC5B4")) {
            for (translation in listOf(false, true)) {
                assertEquals(LyricFontSource.CUSTOM, lyricFontSource(true, fontId, text, translation))
                assertEquals(LyricFontSource.SYSTEM, lyricFontSource(false, fontId, text, translation))
            }
        }
    }

    @Test fun withoutCustomFontOnlyJapaneseOriginalUsesTheBuiltInFont() {
        assertEquals(LyricFontSource.JAPANESE, lyricFontSource(true, null, "日本語の歌詞", false))
        assertEquals(LyricFontSource.SYSTEM, lyricFontSource(true, null, "日本語の歌詞", true))
        assertEquals(LyricFontSource.SYSTEM, lyricFontSource(true, null, "中文歌词", false))
    }

    @Test fun fontIdentifiersRejectPathsAndAreNotExportedInBackups() {
        assertTrue(isCustomFontId(fontId))
        assertFalse(isCustomFontId("../$fontId"))
        assertFalse(isCustomFontId(fontId.uppercase()))
        assertFalse(isCustomFontId(""))
        val encoded = CapsuleBackup.encode(CapsuleSettings(japaneseLyricFont = true, customLyricFontId = fontId))
        assertFalse(encoded.contains(fontId))
        val restored = CapsuleBackup.decode(encoded.byteInputStream())
        assertTrue(restored.japaneseLyricFont)
        assertEquals(null, restored.customLyricFontId)
    }
}
