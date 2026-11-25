package shop.terramoor.github.io

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import shop.terramoor.github.io.systemui.containsJapaneseKana

class JapaneseLyricScriptTest {
    @Test fun detectsKanaInMixedLyrics() {
        assertTrue(containsJapaneseKana("\u97f3\u697d\u3092"))
        assertTrue(containsJapaneseKana("\u30df\u30af"))
        assertTrue(containsJapaneseKana("\uff76\uff85"))
        assertTrue(containsJapaneseKana("\ud82c\udc00"))
    }

    @Test fun ignoresLinesWithoutKana() {
        assertFalse(containsJapaneseKana(null))
        assertFalse(containsJapaneseKana("\u65e5\u672c\u8a9e ABC 123"))
        assertFalse(containsJapaneseKana("\u30fc\u30fb"))
    }
}
