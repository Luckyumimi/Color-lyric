package shop.terramoor.github.io

import android.app.Instrumentation
import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import shop.terramoor.github.io.settings.CapsuleBackup
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.CapsuleTuning
import shop.terramoor.github.io.settings.LyricAlignment
import shop.terramoor.github.io.settings.LyricTintMode
import shop.terramoor.github.io.settings.MissingTranslationMode
import shop.terramoor.github.io.settings.PauseBehavior
import shop.terramoor.github.io.settings.SecondRowContent
import shop.terramoor.github.io.settings.TerraMoorApp
import shop.terramoor.github.io.settings.writeSettings

@RunWith(AndroidJUnit4::class)
class CapsuleCameraSettingsTest {
    @Test
    fun freshPreferencesUseTheUserDefaults() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val name = "capsule-fresh-defaults"
        context.deleteSharedPreferences(name)
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            val settings = CapsuleSettings.read(preferences)
            assertEquals(true, settings.enabled)
            assertEquals(true, settings.doubleLine)
            assertEquals(true, settings.artwork)
            assertEquals(PauseBehavior.KEEP_CURRENT, settings.pauseBehavior)
            assertEquals(LyricAlignment.LEFT, settings.lyricAlignment)
            assertTrue(settings.wordLyrics)
            assertTrue(settings.introDots)
            assertEquals(LyricTintMode.GRADIENT, settings.lyricTintMode)
            assertEquals(12, settings[CapsuleTuning.FIRST_ROW_SIZE])
            assertEquals(8, settings[CapsuleTuning.SECOND_ROW_SIZE])
            assertEquals(170, settings[CapsuleTuning.ISLAND_WIDTH])
            assertEquals(5, settings[CapsuleTuning.BLUR])
            assertEquals(0, settings[CapsuleTuning.ARTWORK_DIM])
            assertEquals(CapsuleSettings().withTuning(CapsuleTuning.entries.associateWith { it.default }), settings)
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    @Test
    fun oldTintModesMigrateToOklch() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val name = "capsule-legacy-tint-modes"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            for (mode in listOf("MEDIAN_CUT", "WALLPAPER")) {
                preferences.edit().putString(CapsuleSettings.LYRIC_TINT_MODE, mode).commit()
                assertEquals(LyricTintMode.OKLCH, CapsuleSettings.read(preferences).lyricTintMode)
            }
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    @Test
    fun backupRestorePersistsFadeAndClearsRetiredPreferences() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val prefs = context.getSharedPreferences("camera-settings-test", 0)
        try {
            for (fade in listOf(false, true)) {
                val expected = CapsuleSettings(cameraFade = fade, lyricAlignment = LyricAlignment.LEFT)
                    .withTuning(mapOf(CapsuleTuning.ISLAND_WIDTH to 174))
                val imported = CapsuleBackup.decode(CapsuleBackup.encode(expected).byteInputStream())
                prefs.edit().putInt("camera_fade_radius", 11).putString("single_line_switch_mode", "AT_NEXT_START")
                    .putString("double_line_switch_mode", "AFTER_END").putInt("intro_dot_size", 50).commit()
                prefs.edit().apply { writeSettings(imported) }.commit()
                val actual = CapsuleSettings.read(prefs)
                assertEquals(fade, actual.cameraFade)
                assertFalse(prefs.contains("camera_fade_radius"))
                assertFalse(prefs.contains("single_line_switch_mode"))
                assertFalse(prefs.contains("double_line_switch_mode"))
                assertFalse(prefs.contains("intro_dot_size"))
                assertEquals(LyricAlignment.LEFT, actual.lyricAlignment)
                assertEquals(174, actual[CapsuleTuning.ISLAND_WIDTH])
            }
        } finally {
            context.deleteSharedPreferences("camera-settings-test")
        }
    }

    @Test
    fun versionOneBackupKeepsFadeAndIgnoresOldRadius() {
        val legacy = JSONObject(CapsuleBackup.encode(CapsuleSettings(cameraFade = false)))
        legacy.put("version", 1)
        legacy.getJSONObject("settings").apply {
            put(CapsuleSettings.LEGACY_TRANSLATION, true)
            remove(CapsuleSettings.LYRIC_ALIGNMENT)
            remove(CapsuleTuning.ISLAND_WIDTH.key)
            put("text_width", 48)
            put("camera_fade_radius", 8)
        }
        val imported = CapsuleBackup.decode(legacy.toString().byteInputStream())
        assertEquals(LyricAlignment.CENTER, imported.lyricAlignment)
        assertEquals(202, imported[CapsuleTuning.ISLAND_WIDTH])
        assertFalse(imported.cameraFade)
        assertFalse(JSONObject(CapsuleBackup.encode(imported)).getJSONObject("settings").has("camera_fade_radius"))
    }

    @Test
    fun versionThreeRoundBackupRestoresWithoutRetiredShape() {
        val data = JSONObject(CapsuleBackup.encode(CapsuleSettings(cameraFade = true)))
        data.put("version", 3)
        data.getJSONObject("settings").put("camera_shape", "ROUND").put(CapsuleSettings.LEGACY_TRANSLATION, true)
        val restored = CapsuleBackup.decode(data.toString().byteInputStream())
        assertEquals(true, restored.cameraFade)
        assertFalse(JSONObject(CapsuleBackup.encode(restored)).getJSONObject("settings").has("camera_shape"))
    }

    @Test
    fun capsuleElementsRoundTripAndOldBackupsKeepBothVisible() {
        for (cover in listOf(false, true)) for (waveform in listOf(false, true)) {
            val settings = CapsuleSettings(showCover = cover, showWaveform = waveform)
            val restored = CapsuleBackup.decode(CapsuleBackup.encode(settings).byteInputStream())
            assertEquals(cover, restored.showCover)
            assertEquals(waveform, restored.showWaveform)
        }
        val legacy = JSONObject(CapsuleBackup.encode(CapsuleSettings(showCover = false, showWaveform = false)))
        legacy.put("version", 4)
        legacy.getJSONObject("settings").apply {
            put(CapsuleSettings.LEGACY_TRANSLATION, true)
            remove(CapsuleSettings.SHOW_COVER)
            remove(CapsuleSettings.SHOW_WAVEFORM)
        }
        val restored = CapsuleBackup.decode(legacy.toString().byteInputStream())
        assertEquals(true, restored.showCover)
        assertEquals(true, restored.showWaveform)
    }

    @Test
    fun oldPreferencesAndV5BackupMigrateWithExplicitNewKeysTakingPrecedence() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val name = "capsule-v5-lyrics"
        context.deleteSharedPreferences(name)
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            prefs.edit().putBoolean(CapsuleSettings.LEGACY_TRANSLATION, false)
                .putInt(CapsuleTuning.FIRST_ROW_SIZE.key, 12).commit()
            val migrated = CapsuleSettings.read(prefs)
            assertFalse(migrated.doubleLine)
            assertEquals(12, migrated[CapsuleTuning.FIRST_ROW_SIZE])
            val backup = JSONObject(CapsuleBackup.encode(migrated)).put("version", 5)
            backup.getJSONObject("settings").apply {
                remove(CapsuleSettings.DOUBLE_LINE)
                remove(CapsuleSettings.SECOND_ROW_CONTENT)
                remove(CapsuleSettings.MISSING_TRANSLATION_MODE)
                remove(CapsuleSettings.FALLBACK_SINGLE_LINE_EXPAND)
                put(CapsuleSettings.LEGACY_TRANSLATION, false)
            }
            assertEquals(migrated, CapsuleBackup.decode(backup.toString().byteInputStream()))

            prefs.edit().putBoolean(CapsuleSettings.DOUBLE_LINE, true).commit()
            assertEquals(true, CapsuleSettings.read(prefs).doubleLine)
            val v6 = migrated.copy(secondRowContent = SecondRowContent.LYRIC, fallbackSingleLineExpand = false)
            prefs.edit().apply { writeSettings(CapsuleBackup.decode(CapsuleBackup.encode(v6).byteInputStream())) }.commit()
            assertFalse(prefs.contains(CapsuleSettings.LEGACY_TRANSLATION))
            assertEquals(v6, CapsuleSettings.read(prefs))
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    @Test
    fun wordAvoidanceAndCameraFadeCanBeChangedIndependently() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val name = "capsule-camera-effects-independent"
        context.deleteSharedPreferences(name)
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val app = Instrumentation.newApplication(TerraMoorApp::class.java, context) as TerraMoorApp
        fun snapshot() = runBlocking { withTimeout(5000) { withContext(Dispatchers.Main) { app.snapshotForBackup() } } }

        try {
            instrumentation.runOnMainSync {
                app.attachPreferences(prefs, inScope = true, initial = CapsuleSettings.read(prefs))
            }
            for (fade in listOf(true, false)) {
                instrumentation.runOnMainSync {
                    app.set(CapsuleSettings.CAMERA_FADE, fade)
                    app.set(CapsuleSettings.CAMERA_WORD_AVOIDANCE, true)
                }
                val avoiding = checkNotNull(snapshot())
                assertTrue(avoiding.cameraWordAvoidance)
                assertEquals(fade, avoiding.cameraFade)
                assertEquals(fade, prefs.getBoolean(CapsuleSettings.CAMERA_FADE, !fade))

                instrumentation.runOnMainSync { app.set(CapsuleSettings.CAMERA_FADE, !fade) }
                val changed = checkNotNull(snapshot())
                assertTrue(changed.cameraWordAvoidance)
                assertEquals(!fade, changed.cameraFade)
                assertEquals(!fade, prefs.getBoolean(CapsuleSettings.CAMERA_FADE, fade))

                instrumentation.runOnMainSync { app.set(CapsuleSettings.CAMERA_WORD_AVOIDANCE, false) }
                val restored = checkNotNull(snapshot())
                assertFalse(restored.cameraWordAvoidance)
                assertEquals(!fade, restored.cameraFade)
            }
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    @Test
    fun oldPreferencesCanKeepBothCameraEffectsEnabled() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val name = "capsule-camera-effect-legacy"
        context.deleteSharedPreferences(name)
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            assertFalse(CapsuleSettings.read(prefs).cameraWordAvoidance)
            assertTrue(CapsuleSettings.read(prefs).cameraFade)
            prefs.edit()
                .putBoolean(CapsuleSettings.CAMERA_WORD_AVOIDANCE, true)
                .putBoolean(CapsuleSettings.CAMERA_FADE, true)
                .apply()
            val settings = CapsuleSettings.read(prefs)
            assertTrue(settings.cameraWordAvoidance)
            assertTrue(settings.cameraFade)
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    @Test
    fun applicationSerializesSliderAndLyricControlWritesWithoutReplacingIndependentModes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val name = "capsule-lyric-writes"
        context.deleteSharedPreferences(name)
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val app = Instrumentation.newApplication(TerraMoorApp::class.java, context) as TerraMoorApp
        val delivered = mutableListOf<CapsuleSettings>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { source, _ ->
            delivered += CapsuleSettings.read(source)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            instrumentation.runOnMainSync {
                app.attachPreferences(prefs, inScope = true, initial = CapsuleSettings.read(prefs))
                app.previewTuning(CapsuleTuning.FIRST_ROW_SIZE, 12)
                app.set(CapsuleSettings.DOUBLE_LINE, false)
                app.setSecondRowContent(SecondRowContent.LYRIC)
                app.setMissingTranslationMode(MissingTranslationMode.DOUBLE_LYRICS)
                app.set(CapsuleSettings.FALLBACK_SINGLE_LINE_EXPAND, false)
                app.setLyricTintMode(LyricTintMode.OKLCH)
                app.setPauseBehavior(PauseBehavior.RESTORE_NATIVE)
                app.previewTuning(CapsuleTuning.TINT_STRENGTH, 50)
                app.set(CapsuleSettings.LYRIC_TINT_ENABLED, false)
            }
            val confirmed = runBlocking { withTimeout(5000) { withContext(Dispatchers.Main) { app.snapshotForBackup() } } }
            instrumentation.waitForIdleSync()
            assertFalse(delivered.isEmpty())
            assertEquals(confirmed, delivered.last())
            val settings = checkNotNull(confirmed)
            assertFalse(settings.doubleLine)
            assertEquals(12, settings[CapsuleTuning.FIRST_ROW_SIZE])
            assertEquals(SecondRowContent.LYRIC, settings.secondRowContent)
            assertEquals(MissingTranslationMode.DOUBLE_LYRICS, settings.missingTranslationMode)
            assertFalse(settings.fallbackSingleLineExpand)
            assertFalse(settings.lyricTintEnabled)
            assertEquals(LyricTintMode.OKLCH, settings.lyricTintMode)
            assertEquals(PauseBehavior.RESTORE_NATIVE, settings.pauseBehavior)
            assertEquals(50, settings[CapsuleTuning.TINT_STRENGTH])
        } finally {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            context.deleteSharedPreferences(name)
        }
    }

    @Test
    fun backupWithRetiredSlidersPreservesTheSavedIslandWidth() {
        val saved = CapsuleSettings(showCover = false, showWaveform = false)
            .withTuning(mapOf(CapsuleTuning.ISLAND_WIDTH to 186))
        val backup = JSONObject(CapsuleBackup.encode(saved))
        val settings = backup.getJSONObject("settings")
        val retiredValues = mapOf(
            "lyric_cover_gap" to 6,
            "lyric_left_inset" to 9,
            "lyric_waveform_gap" to -5,
            "lyric_right_inset" to 13,
            "lyric_left_fade_width" to 4,
            "lyric_right_fade_width" to 18,
        )
        retiredValues.forEach { (key, value) -> settings.put(key, value) }
        val restored = CapsuleBackup.decode(backup.toString().byteInputStream())
        assertEquals(186, restored[CapsuleTuning.ISLAND_WIDTH])
        assertFalse(restored.showCover)
        assertFalse(restored.showWaveform)
        val exported = JSONObject(CapsuleBackup.encode(restored)).getJSONObject("settings")
        retiredValues.keys.forEach { assertFalse(exported.has(it)) }
    }
}
