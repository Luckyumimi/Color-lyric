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
import org.junit.Test
import org.junit.runner.RunWith
import shop.terramoor.github.io.settings.CapsuleBackup
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.CapsuleTuning
import shop.terramoor.github.io.settings.LyricAlignment
import shop.terramoor.github.io.settings.LyricSwitchMode
import shop.terramoor.github.io.settings.MissingTranslationMode
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
            assertEquals(LyricSwitchMode.AT_NEXT_START, settings.doubleLineSwitchMode)
            assertEquals(true, settings.artwork)
            assertEquals(LyricAlignment.LEFT, settings.lyricAlignment)
            assertEquals(170, settings[CapsuleTuning.ISLAND_WIDTH])
            assertEquals(25, settings[CapsuleTuning.ARTWORK_DIM])
            assertEquals(CapsuleSettings().withTuning(CapsuleTuning.entries.associateWith { it.default }), settings)
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    @Test
    fun backupRestorePersistsFadeAndRadius() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val prefs = context.getSharedPreferences("camera-settings-test", 0)
        try {
            for (fade in listOf(false, true)) {
                val expected = CapsuleSettings(cameraFade = fade, lyricAlignment = LyricAlignment.LEFT)
                    .withTuning(mapOf(CapsuleTuning.CAMERA_FADE_RADIUS to 11, CapsuleTuning.ISLAND_WIDTH to 174))
                val imported = CapsuleBackup.decode(CapsuleBackup.encode(expected).byteInputStream())
                prefs.edit().apply { writeSettings(imported) }.commit()
                val actual = CapsuleSettings.read(prefs)
                assertEquals(fade, actual.cameraFade)
                assertEquals(11, actual[CapsuleTuning.CAMERA_FADE_RADIUS])
                assertEquals(LyricAlignment.LEFT, actual.lyricAlignment)
                assertEquals(174, actual[CapsuleTuning.ISLAND_WIDTH])
            }
        } finally {
            context.deleteSharedPreferences("camera-settings-test")
        }
    }

    @Test
    fun versionOneBackupKeepsFadeAndRadius() {
        val legacy = JSONObject(CapsuleBackup.encode(CapsuleSettings(cameraFade = false)
            .withTuning(mapOf(CapsuleTuning.CAMERA_FADE_RADIUS to 8))))
        legacy.put("version", 1)
        legacy.getJSONObject("settings").apply {
            put(CapsuleSettings.LEGACY_TRANSLATION, true)
            remove(CapsuleSettings.LYRIC_ALIGNMENT)
            remove(CapsuleTuning.ISLAND_WIDTH.key)
            put("text_width", 48)
        }
        val imported = CapsuleBackup.decode(legacy.toString().byteInputStream())
        assertEquals(LyricAlignment.CENTER, imported.lyricAlignment)
        assertEquals(202, imported[CapsuleTuning.ISLAND_WIDTH])
        assertFalse(imported.cameraFade)
        assertEquals(8, imported[CapsuleTuning.CAMERA_FADE_RADIUS])
    }

    @Test
    fun versionThreeRoundBackupRestoresWithoutRetiredShape() {
        val data = JSONObject(CapsuleBackup.encode(CapsuleSettings(cameraFade = true)
            .withTuning(mapOf(CapsuleTuning.CAMERA_FADE_RADIUS to 9))))
        data.put("version", 3)
        data.getJSONObject("settings").put("camera_shape", "ROUND").put(CapsuleSettings.LEGACY_TRANSLATION, true)
        val restored = CapsuleBackup.decode(data.toString().byteInputStream())
        assertEquals(9, restored[CapsuleTuning.CAMERA_FADE_RADIUS])
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
            assertEquals(LyricSwitchMode.AFTER_END, migrated.doubleLineSwitchMode)
            assertEquals(12, migrated[CapsuleTuning.FIRST_ROW_SIZE])
            val backup = JSONObject(CapsuleBackup.encode(migrated)).put("version", 5)
            backup.getJSONObject("settings").apply {
                remove(CapsuleSettings.DOUBLE_LINE)
                remove(CapsuleSettings.SECOND_ROW_CONTENT)
                remove(CapsuleSettings.SINGLE_LINE_SWITCH_MODE)
                remove(CapsuleSettings.DOUBLE_LINE_SWITCH_MODE)
                remove(CapsuleSettings.MISSING_TRANSLATION_MODE)
                remove(CapsuleSettings.FALLBACK_SINGLE_LINE_EXPAND)
                put(CapsuleSettings.LEGACY_TRANSLATION, false)
            }
            assertEquals(migrated, CapsuleBackup.decode(backup.toString().byteInputStream()))

            prefs.edit().putBoolean(CapsuleSettings.DOUBLE_LINE, true).commit()
            assertEquals(true, CapsuleSettings.read(prefs).doubleLine)
            val v6 = migrated.copy(secondRowContent = SecondRowContent.LYRIC,
                doubleLineSwitchMode = LyricSwitchMode.AT_NEXT_START, fallbackSingleLineExpand = false)
            prefs.edit().apply { writeSettings(CapsuleBackup.decode(CapsuleBackup.encode(v6).byteInputStream())) }.commit()
            assertFalse(prefs.contains(CapsuleSettings.LEGACY_TRANSLATION))
            assertEquals(v6, CapsuleSettings.read(prefs))
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
                app.setSingleLineSwitchMode(LyricSwitchMode.AT_NEXT_START)
                app.setMissingTranslationMode(MissingTranslationMode.DOUBLE_LYRICS)
                app.set(CapsuleSettings.FALLBACK_SINGLE_LINE_EXPAND, false)
            }
            val confirmed = runBlocking { withTimeout(5000) { withContext(Dispatchers.Main) { app.snapshotForBackup() } } }
            instrumentation.waitForIdleSync()
            assertFalse(delivered.isEmpty())
            assertEquals(confirmed, delivered.last())
            val settings = checkNotNull(confirmed)
            assertFalse(settings.doubleLine)
            assertEquals(12, settings[CapsuleTuning.FIRST_ROW_SIZE])
            assertEquals(SecondRowContent.LYRIC, settings.secondRowContent)
            assertEquals(LyricSwitchMode.AT_NEXT_START, settings.singleLineSwitchMode)
            assertEquals(LyricSwitchMode.AT_NEXT_START, settings.doubleLineSwitchMode)
            assertEquals(MissingTranslationMode.DOUBLE_LYRICS, settings.missingTranslationMode)
            assertFalse(settings.fallbackSingleLineExpand)
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
