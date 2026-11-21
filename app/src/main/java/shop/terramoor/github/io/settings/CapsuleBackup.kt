package shop.terramoor.github.io.settings

import android.content.SharedPreferences
import org.json.JSONObject
import java.io.InputStream

internal object CapsuleBackup {
    private const val FORMAT = "terramoor.settings"
    private const val VERSION = 6
    private const val MAX_BYTES = 16 * 1024

    fun encode(settings: CapsuleSettings): String {
        val data = JSONObject()
            .put(CapsuleSettings.ENABLED, settings.enabled)
            .put(CapsuleSettings.DOUBLE_LINE, settings.doubleLine)
            .put(CapsuleSettings.SECOND_ROW_CONTENT, settings.secondRowContent.name)
            .put(CapsuleSettings.SINGLE_LINE_SWITCH_MODE, settings.singleLineSwitchMode.name)
            .put(CapsuleSettings.DOUBLE_LINE_SWITCH_MODE, settings.doubleLineSwitchMode.name)
            .put(CapsuleSettings.MISSING_TRANSLATION_MODE, settings.missingTranslationMode.name)
            .put(CapsuleSettings.FALLBACK_SINGLE_LINE_EXPAND, settings.fallbackSingleLineExpand)
            .put(CapsuleSettings.ARTWORK, settings.artwork)
            .put(CapsuleSettings.SHOW_COVER, settings.showCover)
            .put(CapsuleSettings.SHOW_WAVEFORM, settings.showWaveform)
            .put(CapsuleSettings.LYRIC_ALIGNMENT, settings.lyricAlignment.name)
            .put(CapsuleSettings.CAMERA_FADE, settings.cameraFade)
            .put(CapsuleSettings.MOTION, settings.motion.name)
            .put(CapsuleSettings.NO_LYRICS, settings.noLyricsMode.name)
        CapsuleTuning.entries.forEach { data.put(it.key, settings[it]) }
        return JSONObject().put("format", FORMAT).put("version", VERSION).put("settings", data).toString(2)
    }

    fun decode(input: InputStream): CapsuleSettings {
        val bytes = input.readNBytes(MAX_BYTES + 1)
        require(bytes.size <= MAX_BYTES)
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        val version = root.opt("version") as? Int
        require(root.opt("format") == FORMAT && version != null && version in 1..VERSION)
        val data = root.getJSONObject("settings")
        fun flag(key: String) = data.get(key) as? Boolean ?: error("Invalid setting")
        val tuning = CapsuleTuning.entries.associateWith { option ->
            if (version < 3 && option == CapsuleTuning.ISLAND_WIDTH) return@associateWith 202 // Historical width for backups before v3.
            (data.get(option.key) as? Int)?.takeIf { it in option.range } ?: error("Invalid tuning")
        }
        return CapsuleSettings(
            enabled = flag(CapsuleSettings.ENABLED),
            doubleLine = if (version < 6) {
                (data.opt(CapsuleSettings.TRANSLATION) as? Boolean)
                    ?: data.optBoolean(CapsuleSettings.DOUBLE_LINE, true)
            } else data.getBoolean(CapsuleSettings.DOUBLE_LINE),
            secondRowContent = if (version < 6) SecondRowContent.TRANSLATION
            else SecondRowContent.valueOf(data.getString(CapsuleSettings.SECOND_ROW_CONTENT)),
            singleLineSwitchMode = if (version < 6) LyricSwitchMode.AFTER_END
            else LyricSwitchMode.valueOf(data.getString(CapsuleSettings.SINGLE_LINE_SWITCH_MODE)),
            doubleLineSwitchMode = if (version < 6) LyricSwitchMode.AFTER_END
            else LyricSwitchMode.valueOf(data.getString(CapsuleSettings.DOUBLE_LINE_SWITCH_MODE)),
            missingTranslationMode = if (version < 6) MissingTranslationMode.SINGLE_LINE
            else MissingTranslationMode.valueOf(data.getString(CapsuleSettings.MISSING_TRANSLATION_MODE)),
            fallbackSingleLineExpand = if (version < 6) true
            else data.getBoolean(CapsuleSettings.FALLBACK_SINGLE_LINE_EXPAND),
            artwork = flag(CapsuleSettings.ARTWORK),
            showCover = if (version < 5) true else flag(CapsuleSettings.SHOW_COVER),
            showWaveform = if (version < 5) true else flag(CapsuleSettings.SHOW_WAVEFORM),
            lyricAlignment = if (version < 3) LyricAlignment.CENTER
            else LyricAlignment.valueOf(data.getString(CapsuleSettings.LYRIC_ALIGNMENT)),
            cameraFade = flag(CapsuleSettings.CAMERA_FADE),
            motion = LineMotion.valueOf(data.getString(CapsuleSettings.MOTION)),
            noLyricsMode = NoLyricsMode.valueOf(data.getString(CapsuleSettings.NO_LYRICS)),
            tuning = tuning,
        )
    }
}

internal fun SharedPreferences.Editor.writeSettings(settings: CapsuleSettings) {
    putBoolean(CapsuleSettings.ENABLED, settings.enabled)
    putBoolean(CapsuleSettings.DOUBLE_LINE, settings.doubleLine)
    putString(CapsuleSettings.SECOND_ROW_CONTENT, settings.secondRowContent.name)
    putString(CapsuleSettings.SINGLE_LINE_SWITCH_MODE, settings.singleLineSwitchMode.name)
    putString(CapsuleSettings.DOUBLE_LINE_SWITCH_MODE, settings.doubleLineSwitchMode.name)
    putString(CapsuleSettings.MISSING_TRANSLATION_MODE, settings.missingTranslationMode.name)
    putBoolean(CapsuleSettings.FALLBACK_SINGLE_LINE_EXPAND, settings.fallbackSingleLineExpand)
    remove(CapsuleSettings.TRANSLATION)
    putBoolean(CapsuleSettings.ARTWORK, settings.artwork)
    putBoolean(CapsuleSettings.SHOW_COVER, settings.showCover)
    putBoolean(CapsuleSettings.SHOW_WAVEFORM, settings.showWaveform)
    remove("camera_shape") // Retired in version 4.
    putString(CapsuleSettings.LYRIC_ALIGNMENT, settings.lyricAlignment.name)
    putBoolean(CapsuleSettings.CAMERA_FADE, settings.cameraFade)
    putString(CapsuleSettings.MOTION, settings.motion.name)
    putString(CapsuleSettings.NO_LYRICS, settings.noLyricsMode.name)
    CapsuleTuning.entries.forEach { putInt(it.key, settings[it]) }
}
