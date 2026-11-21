package shop.terramoor.github.io

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import shop.terramoor.github.io.settings.CapsuleBackup
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.CapsuleTuning
import shop.terramoor.github.io.settings.LineMotion
import shop.terramoor.github.io.settings.LyricSwitchMode
import shop.terramoor.github.io.settings.MissingTranslationMode
import shop.terramoor.github.io.settings.SecondRowContent

class CapsuleBackupTest {
    @Test fun freshDefaultsUseSeparateSwitchModesAndTheExistingSliderValues() {
        val fresh = CapsuleSettings()
        assertTrue(fresh.doubleLine)
        assertEquals(SecondRowContent.TRANSLATION, fresh.secondRowContent)
        assertEquals(LyricSwitchMode.AFTER_END, fresh.singleLineSwitchMode)
        assertEquals(LyricSwitchMode.AT_NEXT_START, fresh.doubleLineSwitchMode)
        assertEquals(MissingTranslationMode.SINGLE_LINE, fresh.missingTranslationMode)
        assertTrue(fresh.fallbackSingleLineExpand)
        assertEquals(11, fresh[CapsuleTuning.FIRST_ROW_SIZE])
        assertEquals(9, fresh[CapsuleTuning.SECOND_ROW_SIZE])
        assertEquals(100, fresh[CapsuleTuning.FIRST_ROW_OPACITY])
        assertEquals(80, fresh[CapsuleTuning.SECOND_ROW_OPACITY])
    }

    @Test fun v5TranslationFlagMigratesWithLegacyTimingAndSliderKeys() {
        for (translation in listOf(false, true)) {
            val v5 = """{
                "format":"terramoor.settings","version":5,"settings":{
                    "enabled":true,"translation":$translation,"artwork":false,
                    "show_cover":false,"show_waveform":true,"lyric_alignment":"LEFT",
                    "camera_fade":true,"line_motion":"UP","no_lyrics_mode":"TRACK",
                    "title_size":12,"translation_size":8,"title_opacity":95,"translation_opacity":70,
                    "island_width":174,"camera_fade_radius":3,"artwork_blur":14,"artwork_opacity":75,"artwork_dim":25
                }
            }"""
            val settings = CapsuleBackup.decode(v5.byteInputStream())
            assertEquals(translation, settings.doubleLine)
            assertEquals(SecondRowContent.TRANSLATION, settings.secondRowContent)
            assertEquals(LyricSwitchMode.AFTER_END, settings.singleLineSwitchMode)
            assertEquals(LyricSwitchMode.AFTER_END, settings.doubleLineSwitchMode)
            assertEquals(MissingTranslationMode.SINGLE_LINE, settings.missingTranslationMode)
            assertTrue(settings.fallbackSingleLineExpand)
            assertEquals(12, settings[CapsuleTuning.FIRST_ROW_SIZE])
            assertEquals(8, settings[CapsuleTuning.SECOND_ROW_SIZE])
            assertEquals(95, settings[CapsuleTuning.FIRST_ROW_OPACITY])
            assertEquals(70, settings[CapsuleTuning.SECOND_ROW_OPACITY])
            assertEquals(25, settings[CapsuleTuning.ARTWORK_DIM])
            val exported = JSONObject(CapsuleBackup.encode(settings)).getJSONObject("settings")
            assertFalse(exported.has("translation"))
            assertFalse(exported.has("artwork_opacity"))
        }
    }

    @Test fun v6RoundTripPreservesInactiveSettingsAndDoesNotExportTheOldToggle() {
        val expected = CapsuleSettings(
            doubleLine = false,
            secondRowContent = SecondRowContent.LYRIC,
            singleLineSwitchMode = LyricSwitchMode.AT_NEXT_START,
            doubleLineSwitchMode = LyricSwitchMode.AFTER_END,
            missingTranslationMode = MissingTranslationMode.DOUBLE_LYRICS,
            fallbackSingleLineExpand = false,
        ).withTuning(CapsuleTuning.entries.associateWith { it.range.last })
        val encoded = CapsuleBackup.encode(expected)
        val root = JSONObject(encoded)
        assertEquals(6, root.getInt("version"))
        assertFalse(root.getJSONObject("settings").has("translation"))
        assertEquals(expected, CapsuleBackup.decode(encoded.byteInputStream()))
    }

    @Test fun removedDownwardMotionImportsAndReexportsAsUpwardMotion() {
        val backup = JSONObject(CapsuleBackup.encode(CapsuleSettings()))
        backup.getJSONObject("settings").put(CapsuleSettings.MOTION, "DOWN")
        val migrated = CapsuleBackup.decode(backup.toString().byteInputStream())
        assertEquals(LineMotion.UP, migrated.motion)
        val exported = JSONObject(CapsuleBackup.encode(migrated)).getJSONObject("settings")
        assertEquals("UP", exported.getString(CapsuleSettings.MOTION))
    }
}
