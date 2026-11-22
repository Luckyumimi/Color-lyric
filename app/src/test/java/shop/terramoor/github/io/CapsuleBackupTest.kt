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
import shop.terramoor.github.io.settings.LyricTintMode
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
        assertTrue(fresh.lyricTintEnabled)
        assertEquals(LyricTintMode.OKLCH, fresh.lyricTintMode)
        assertEquals(100, fresh[CapsuleTuning.TINT_STRENGTH])
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

    @Test fun v11RoundTripPreservesTintAndInactiveSettings() {
        val expected = CapsuleSettings(
            doubleLine = false,
            secondRowContent = SecondRowContent.LYRIC,
            singleLineSwitchMode = LyricSwitchMode.AT_NEXT_START,
            doubleLineSwitchMode = LyricSwitchMode.AFTER_END,
            missingTranslationMode = MissingTranslationMode.DOUBLE_LYRICS,
            fallbackSingleLineExpand = false,
            lyricTintEnabled = false,
            lyricTintMode = LyricTintMode.TEXTURE,
        ).withTuning(CapsuleTuning.entries.associateWith {
            if (it == CapsuleTuning.TINT_STRENGTH) 75 else it.range.last
        })
        val encoded = CapsuleBackup.encode(expected)
        val root = JSONObject(encoded)
        assertEquals(11, root.getInt("version"))
        val data = root.getJSONObject("settings")
        assertFalse(data.getBoolean(CapsuleSettings.LYRIC_TINT_ENABLED))
        assertFalse(data.has("translation"))
        assertTrue(CapsuleSettings.retiredTintKeys.none(data::has))
        assertEquals(expected, CapsuleBackup.decode(encoded.byteInputStream()))
    }

    @Test fun legacyTintModesImportAsOklch() {
        for (mode in listOf("MEDIAN_CUT", "WALLPAPER")) {
            val backup = JSONObject(CapsuleBackup.encode(CapsuleSettings()))
            backup.getJSONObject("settings").put(CapsuleSettings.LYRIC_TINT_MODE, mode)
            val imported = CapsuleBackup.decode(backup.toString().byteInputStream())
            assertEquals(LyricTintMode.OKLCH, imported.lyricTintMode)
            assertEquals("OKLCH", JSONObject(CapsuleBackup.encode(imported))
                .getJSONObject("settings").getString(CapsuleSettings.LYRIC_TINT_MODE))
        }
    }

    @Test fun v10BackupDefaultsNewTextureSettings() {
        val original = CapsuleSettings(lyricTintMode = LyricTintMode.OKLCH)
        val backup = JSONObject(CapsuleBackup.encode(original)).put("version", 10)
        val data = backup.getJSONObject("settings")
        val options = listOf(CapsuleTuning.TEXTURE_BRIGHTNESS, CapsuleTuning.TEXTURE_CONTRAST,
            CapsuleTuning.TEXTURE_SATURATION, CapsuleTuning.TEXTURE_BLUR)
        options.forEach { data.remove(it.key) }
        val decoded = CapsuleBackup.decode(backup.toString().byteInputStream())
        assertEquals(LyricTintMode.OKLCH, decoded.lyricTintMode)
        options.forEach { assertEquals(it.default, decoded[it]) }
    }

    @Test fun v10StoresHelpVisibilityAndKeepsCameraFadeAsBoolean() {
        val settings = CapsuleSettings(cameraFade = false, tuning = CapsuleTuning.entries.associateWith { it.default })
        val encoded = CapsuleBackup.encode(settings, translationHelpHidden = true)
        val snapshot = CapsuleBackup.decodeSnapshot(encoded.byteInputStream())
        assertEquals(CapsuleBackup.Snapshot(settings, true), snapshot)
        val v9 = JSONObject(encoded).put("version", 9)
        v9.remove("app_preferences")
        val old = CapsuleBackup.decodeSnapshot(v9.toString().byteInputStream())
        assertEquals(CapsuleBackup.Snapshot(settings, null), old)
    }

    @Test fun v8TintModesMigrateToAnExplicitSwitchWithoutChangingStrength() {
        val saved = CapsuleSettings(lyricTintMode = LyricTintMode.OKLCH)
            .withTuning(mapOf(CapsuleTuning.TINT_STRENGTH to 50))
        val backup = JSONObject(CapsuleBackup.encode(saved)).put("version", 8)
        val data = backup.getJSONObject("settings")
        data.remove(CapsuleSettings.LYRIC_TINT_ENABLED)
        val enabled = CapsuleBackup.decode(backup.toString().byteInputStream())
        assertTrue(enabled.lyricTintEnabled)
        assertEquals(LyricTintMode.OKLCH, enabled.lyricTintMode)
        assertEquals(50, enabled[CapsuleTuning.TINT_STRENGTH])

        data.put(CapsuleSettings.LYRIC_TINT_MODE, "WHITE")
        val disabled = CapsuleBackup.decode(backup.toString().byteInputStream())
        assertFalse(disabled.lyricTintEnabled)
        assertEquals(LyricTintMode.OKLCH, disabled.lyricTintMode)
        assertEquals(50, disabled[CapsuleTuning.TINT_STRENGTH])
    }

    @Test fun v7TintStrengthsMergeIntoTheNearestSharedStep() {
        val backup = JSONObject(CapsuleBackup.encode(CapsuleSettings(lyricTintMode = LyricTintMode.OKLCH)))
        backup.put("version", 7)
        val data = backup.getJSONObject("settings")
        data.remove(CapsuleTuning.TINT_STRENGTH.key)
        data.remove(CapsuleSettings.LYRIC_TINT_ENABLED)
        data.put(CapsuleSettings.LEGACY_FIRST_ROW_TINT, 100)
        data.put(CapsuleSettings.LEGACY_SECOND_ROW_TINT, 40)
        val imported = CapsuleBackup.decode(backup.toString().byteInputStream())
        assertTrue(imported.lyricTintEnabled)
        assertEquals(LyricTintMode.OKLCH, imported.lyricTintMode)
        assertEquals(75, imported[CapsuleTuning.TINT_STRENGTH])
    }

    @Test fun v6WithoutTintSettingsImportsAsWhiteWithoutChangingLyrics() {
        val backup = JSONObject(CapsuleBackup.encode(CapsuleSettings(secondRowContent = SecondRowContent.LYRIC)))
        backup.put("version", 6)
        val data = backup.getJSONObject("settings")
        data.remove(CapsuleSettings.LYRIC_TINT_ENABLED)
        data.remove(CapsuleSettings.LYRIC_TINT_MODE)
        data.remove(CapsuleTuning.TINT_STRENGTH.key)
        val imported = CapsuleBackup.decode(backup.toString().byteInputStream())
        assertFalse(imported.lyricTintEnabled)
        assertEquals(LyricTintMode.OKLCH, imported.lyricTintMode)
        assertEquals(SecondRowContent.LYRIC, imported.secondRowContent)
        assertEquals(LyricSwitchMode.AT_NEXT_START, imported.doubleLineSwitchMode)
        assertEquals(100, imported[CapsuleTuning.TINT_STRENGTH])
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
