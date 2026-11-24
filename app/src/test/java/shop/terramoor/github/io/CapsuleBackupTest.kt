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
import shop.terramoor.github.io.settings.MissingTranslationMode
import shop.terramoor.github.io.settings.PauseBehavior
import shop.terramoor.github.io.settings.SecondRowContent

class CapsuleBackupTest {
    @Test fun freshDefaultsUseSeparateSwitchModesAndTheExistingSliderValues() {
        val fresh = CapsuleSettings()
        assertEquals(PauseBehavior.KEEP_CURRENT, fresh.pauseBehavior)
        assertTrue(fresh.doubleLine)
        assertEquals(SecondRowContent.TRANSLATION, fresh.secondRowContent)
        assertFalse(fresh.swapTranslation)
        assertTrue(fresh.wordLyrics)
        assertFalse(fresh.cameraWordAvoidance)
        assertTrue(fresh.introDots)
        assertEquals(MissingTranslationMode.SINGLE_LINE, fresh.missingTranslationMode)
        assertTrue(fresh.fallbackSingleLineExpand)
        assertEquals(12, fresh[CapsuleTuning.FIRST_ROW_SIZE])
        assertEquals(8, fresh[CapsuleTuning.SECOND_ROW_SIZE])
        assertEquals(100, fresh[CapsuleTuning.FIRST_ROW_OPACITY])
        assertEquals(80, fresh[CapsuleTuning.SECOND_ROW_OPACITY])
        assertEquals(5, fresh[CapsuleTuning.BLUR])
        assertEquals(0, fresh[CapsuleTuning.ARTWORK_DIM])
        assertTrue(fresh.lyricTintEnabled)
        assertEquals(LyricTintMode.GRADIENT, fresh.lyricTintMode)
        assertEquals(100, fresh[CapsuleTuning.TINT_STRENGTH])
    }

    @Test fun v5TranslationFlagMigratesWithLegacySliderKeys() {
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
            assertEquals(MissingTranslationMode.SINGLE_LINE, settings.missingTranslationMode)
            assertFalse(settings.wordLyrics)
            assertFalse(settings.introDots)
            assertTrue(settings.fallbackSingleLineExpand)
            assertEquals(12, settings[CapsuleTuning.FIRST_ROW_SIZE])
            assertEquals(8, settings[CapsuleTuning.SECOND_ROW_SIZE])
            assertEquals(95, settings[CapsuleTuning.FIRST_ROW_OPACITY])
            assertEquals(70, settings[CapsuleTuning.SECOND_ROW_OPACITY])
            assertEquals(14, settings[CapsuleTuning.BLUR])
            assertEquals(25, settings[CapsuleTuning.ARTWORK_DIM])
            val exported = JSONObject(CapsuleBackup.encode(settings)).getJSONObject("settings")
            assertFalse(exported.has("translation"))
            assertFalse(exported.has("artwork_opacity"))
        }
    }

    @Test fun v11BackupDefaultsToOriginalOrder() {
        val root = JSONObject(CapsuleBackup.encode(CapsuleSettings(swapTranslation = true)))
        root.put("version", 11)
        root.getJSONObject("settings").remove(CapsuleSettings.SWAP_TRANSLATION)
        val restored = CapsuleBackup.decode(root.toString().byteInputStream())
        assertFalse(restored.swapTranslation)
    }

    @Test fun backupRetainsSavedSettingsInsteadOfApplyingNewDefaults() {
        val settings = CapsuleSettings(wordLyrics = false, introDots = false, lyricTintMode = LyricTintMode.OKLCH)
            .withTuning(mapOf(CapsuleTuning.FIRST_ROW_SIZE to 11, CapsuleTuning.SECOND_ROW_SIZE to 9))
        val restored = CapsuleBackup.decode(CapsuleBackup.encode(settings).byteInputStream())
        assertEquals(LyricTintMode.OKLCH, restored.lyricTintMode)
        assertEquals(11, restored[CapsuleTuning.FIRST_ROW_SIZE])
        assertEquals(9, restored[CapsuleTuning.SECOND_ROW_SIZE])
        assertFalse(restored.wordLyrics)
        assertFalse(restored.introDots)
    }

    @Test fun v12BackupLeavesOptionalEffectsDisabled() {
        val root = JSONObject(CapsuleBackup.encode(CapsuleSettings(swapTranslation = true)))
        root.put("version", 12)
        val data = root.getJSONObject("settings")
        data.remove(CapsuleSettings.WORD_LYRICS)
        data.remove(CapsuleSettings.CAMERA_WORD_AVOIDANCE)
        data.remove(CapsuleSettings.INTRO_DOTS)
        val restored = CapsuleBackup.decode(root.toString().byteInputStream())
        assertTrue(restored.swapTranslation)
        assertFalse(restored.wordLyrics)
        assertFalse(restored.cameraWordAvoidance)
        assertFalse(restored.introDots)
    }

    @Test fun v13BackupIgnoresRetiredDisplaySettings() {
        val root = JSONObject(CapsuleBackup.encode(CapsuleSettings(doubleLine = false, introDots = true, cameraFade = false)))
        val retiredSettings = mapOf("intro_dot_size" to 50, "camera_fade_radius" to 14,
            "single_line_switch_mode" to "AT_NEXT_START", "double_line_switch_mode" to "AFTER_END")
        val data = root.getJSONObject("settings")
        retiredSettings.forEach { (key, value) -> data.put(key, value) }
        val restored = CapsuleBackup.decode(root.toString().byteInputStream())
        assertFalse(restored.doubleLine)
        assertTrue(restored.introDots)
        assertFalse(restored.cameraFade)
        val exported = JSONObject(CapsuleBackup.encode(restored)).getJSONObject("settings")
        assertTrue(retiredSettings.keys.none(exported::has))
    }

    @Test fun v13BackupDefaultsToKeepingCurrentStyleOnPause() {
        val backup = JSONObject(CapsuleBackup.encode(CapsuleSettings())).put("version", 13)
        backup.getJSONObject("settings").remove(CapsuleSettings.PAUSE_BEHAVIOR)
        val restored = CapsuleBackup.decode(backup.toString().byteInputStream())
        assertEquals(PauseBehavior.KEEP_CURRENT, restored.pauseBehavior)
    }

    @Test fun v14RoundTripPreservesTintPauseBehaviorAndInactiveSettings() {
        val expected = CapsuleSettings(
            doubleLine = false,
            secondRowContent = SecondRowContent.LYRIC,
            swapTranslation = true,
            wordLyrics = true,
            cameraWordAvoidance = true,
            cameraFade = true,
            introDots = true,
            missingTranslationMode = MissingTranslationMode.DOUBLE_LYRICS,
            fallbackSingleLineExpand = false,
            lyricTintEnabled = false,
            lyricTintMode = LyricTintMode.TEXTURE,
            pauseBehavior = PauseBehavior.RESTORE_NATIVE,
        ).withTuning(CapsuleTuning.entries.associateWith {
            if (it == CapsuleTuning.TINT_STRENGTH) 75 else it.range.last
        })
        val encoded = CapsuleBackup.encode(expected)
        val root = JSONObject(encoded)
        assertEquals(14, root.getInt("version"))
        val data = root.getJSONObject("settings")
        assertFalse(data.getBoolean(CapsuleSettings.LYRIC_TINT_ENABLED))
        assertFalse(data.has("translation"))
        assertTrue(CapsuleSettings.retiredTintKeys.none(data::has))
        assertEquals(expected, CapsuleBackup.decode(encoded.byteInputStream()))
    }

    @Test fun oldBackupRestoresBothCameraPreferencesIndependently() {
        for (fade in listOf(true, false)) {
            val root = JSONObject(CapsuleBackup.encode(CapsuleSettings(cameraWordAvoidance = true, cameraFade = fade)))
                .put("version", 13)
            assertEquals(fade, root.getJSONObject("settings").getBoolean(CapsuleSettings.CAMERA_FADE))

            val restored = CapsuleBackup.decode(root.toString().byteInputStream())
            assertTrue(restored.cameraWordAvoidance)
            assertEquals(fade, restored.cameraFade)
        }
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
        assertEquals(CapsuleBackup.Snapshot(settings.copy(wordLyrics = false, introDots = false), null), old)
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
