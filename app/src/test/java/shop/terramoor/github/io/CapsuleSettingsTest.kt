package shop.terramoor.github.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.CapsuleTuning
import shop.terramoor.github.io.settings.LyricSwitchMode
import shop.terramoor.github.io.settings.MissingTranslationMode
import shop.terramoor.github.io.settings.SecondRowContent

class CapsuleSettingsTest {
    @Test
    fun newDefaultsUseIndependentRowSettings() {
        val settings = CapsuleSettings()
        assertTrue(settings.doubleLine)
        assertEquals(SecondRowContent.TRANSLATION, settings.secondRowContent)
        assertEquals(LyricSwitchMode.AFTER_END, settings.singleLineSwitchMode)
        assertEquals(LyricSwitchMode.AT_NEXT_START, settings.doubleLineSwitchMode)
        assertEquals(MissingTranslationMode.SINGLE_LINE, settings.missingTranslationMode)
        assertTrue(settings.fallbackSingleLineExpand)
        assertEquals(11, settings[CapsuleTuning.FIRST_ROW_SIZE])
        assertEquals(9, settings[CapsuleTuning.SECOND_ROW_SIZE])
        assertEquals(100, settings[CapsuleTuning.FIRST_ROW_OPACITY])
        assertEquals(80, settings[CapsuleTuning.SECOND_ROW_OPACITY])
    }

}
