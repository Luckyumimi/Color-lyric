package shop.terramoor.github.io

import org.junit.Assert.assertEquals
import org.junit.Test
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.CapsuleTuning

class CapsuleTypographyTest {
    @Test fun singleLineGainsTwoDpUnlessCapped() {
        val settings = CapsuleSettings()
        assertEquals(14, settings.effectiveFirstRowSize(true))
        assertEquals(12, settings.effectiveFirstRowSize(false))
        assertEquals(12, settings.copy(fallbackSingleLineExpand = false).effectiveFirstRowSize(true))
        assertEquals(14, settings.withTuning(mapOf(CapsuleTuning.FIRST_ROW_SIZE to 13)).effectiveFirstRowSize(true))
    }

    @Test fun translationSliderRangeMatchesEffectiveDrawingSize() {
        val settings = CapsuleSettings().withTuning(mapOf(CapsuleTuning.FIRST_ROW_SIZE to 14, CapsuleTuning.SECOND_ROW_SIZE to 11))
        assertEquals(7..9, settings.secondRowSizeRange)
        assertEquals(9, settings.effectiveSecondRowSize)
        assertEquals(11, settings.withTuning(mapOf(CapsuleTuning.FIRST_ROW_SIZE to 12)).effectiveSecondRowSize)
    }
}
