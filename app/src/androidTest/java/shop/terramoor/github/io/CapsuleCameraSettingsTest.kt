package shop.terramoor.github.io

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import shop.terramoor.github.io.settings.CapsuleBackup
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.CapsuleTuning
import shop.terramoor.github.io.settings.LyricAlignment
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
            assertEquals(true, settings.translation)
            assertEquals(true, settings.artwork)
            assertEquals(LyricAlignment.LEFT, settings.lyricAlignment)
            assertEquals(170, settings[CapsuleTuning.ISLAND_WIDTH])
            assertEquals(75, settings[CapsuleTuning.ARTWORK_OPACITY])
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
        data.getJSONObject("settings").put("camera_shape", "ROUND")
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
            remove(CapsuleSettings.SHOW_COVER)
            remove(CapsuleSettings.SHOW_WAVEFORM)
        }
        val restored = CapsuleBackup.decode(legacy.toString().byteInputStream())
        assertEquals(true, restored.showCover)
        assertEquals(true, restored.showWaveform)
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
