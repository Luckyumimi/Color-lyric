package shop.terramoor.github.io

import org.junit.Assert.assertEquals
import org.junit.Test
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.CapsuleTuning

class CapsuleTypographyTest {
    @Test fun singleLineGainsTwoDpUnlessCapped() {
        val settings = CapsuleSettings()
        assertEquals(11, settings.effectiveTitleSize(hasSecondLine = true))
        assertEquals(13, settings.effectiveTitleSize(hasSecondLine = false))
        assertEquals(14, settings.withTuning(mapOf(CapsuleTuning.TITLE_SIZE to 13)).effectiveTitleSize(false))
    }

    @Test fun translationSliderRangeMatchesEffectiveDrawingSize() {
        val settings = CapsuleSettings().withTuning(mapOf(CapsuleTuning.TITLE_SIZE to 14, CapsuleTuning.TRANSLATION_SIZE to 11))
        assertEquals(7..9, settings.translationSizeRange)
        assertEquals(9, settings.effectiveTranslationSize)
        assertEquals(11, settings.withTuning(mapOf(CapsuleTuning.TITLE_SIZE to 12)).effectiveTranslationSize)
    }
}
