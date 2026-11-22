package shop.terramoor.github.io.settings

import android.content.SharedPreferences
import org.json.JSONObject
import java.io.InputStream

internal object CapsuleBackup {
    private const val FORMAT = "terramoor.settings"
    private const val VERSION = 12
    private const val MAX_BYTES = 16 * 1024
    const val TRANSLATION_HELP_HIDDEN = "translation_help_hidden"

    data class Snapshot(val settings: CapsuleSettings, val translationHelpHidden: Boolean?)

    fun encode(settings: CapsuleSettings, translationHelpHidden: Boolean = false): String {
        val data = JSONObject()
            .put(CapsuleSettings.ENABLED, settings.enabled)
            .put(CapsuleSettings.DOUBLE_LINE, settings.doubleLine)
            .put(CapsuleSettings.SECOND_ROW_CONTENT, settings.secondRowContent.name)
            .put(CapsuleSettings.SWAP_TRANSLATION, settings.swapTranslation)
            .put(CapsuleSettings.SINGLE_LINE_SWITCH_MODE, settings.singleLineSwitchMode.name)
            .put(CapsuleSettings.DOUBLE_LINE_SWITCH_MODE, settings.doubleLineSwitchMode.name)
            .put(CapsuleSettings.MISSING_TRANSLATION_MODE, settings.missingTranslationMode.name)
            .put(CapsuleSettings.FALLBACK_SINGLE_LINE_EXPAND, settings.fallbackSingleLineExpand)
            .put(CapsuleSettings.ARTWORK, settings.artwork)
            .put(CapsuleSettings.LYRIC_TINT_ENABLED, settings.lyricTintEnabled)
            .put(CapsuleSettings.LYRIC_TINT_MODE, settings.lyricTintMode.name)
            .put(CapsuleSettings.SHOW_COVER, settings.showCover)
            .put(CapsuleSettings.SHOW_WAVEFORM, settings.showWaveform)
            .put(CapsuleSettings.LYRIC_ALIGNMENT, settings.lyricAlignment.name)
            .put(CapsuleSettings.CAMERA_FADE, settings.cameraFade)
            .put(CapsuleSettings.MOTION, settings.motion.name)
            .put(CapsuleSettings.NO_LYRICS, settings.noLyricsMode.name)
        CapsuleTuning.entries.forEach { data.put(it.key, settings[it]) }
        val appPreferences = JSONObject().put(TRANSLATION_HELP_HIDDEN, translationHelpHidden)
        return JSONObject().put("format", FORMAT).put("version", VERSION)
            .put("settings", data).put("app_preferences", appPreferences).toString(2)
    }

    fun decode(input: InputStream): CapsuleSettings = decodeSnapshot(input).settings

    fun decodeSnapshot(input: InputStream): Snapshot {
        val bytes = input.readNBytes(MAX_BYTES + 1)
        require(bytes.size <= MAX_BYTES)
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        val version = root.opt("version") as? Int
        require(root.opt("format") == FORMAT && version != null && version in 1..VERSION)
        val data = root.getJSONObject("settings")
        fun flag(key: String) = data.get(key) as? Boolean ?: error("Invalid setting")
        val tuning = CapsuleTuning.entries.associateWith { option ->
            if (version == 7 && option == CapsuleTuning.TINT_STRENGTH) {
                return@associateWith CapsuleSettings.migrateTintStrength(
                    data.getInt(CapsuleSettings.LEGACY_FIRST_ROW_TINT), data.getInt(CapsuleSettings.LEGACY_SECOND_ROW_TINT),
                )
            }
            if (version < option.since) return@associateWith option.default
            if (version < 3 && option == CapsuleTuning.ISLAND_WIDTH) return@associateWith 202 // Historical width for backups before v3.
            (data.get(option.key) as? Int)?.takeIf { it in option.range && option.normalize(it) == it }
                ?: error("Invalid tuning")
        }
        val lyrics = if (version < 6) {
            CapsuleSettings.migratedLegacy(flag(CapsuleSettings.LEGACY_TRANSLATION))
        } else CapsuleSettings(
            doubleLine = flag(CapsuleSettings.DOUBLE_LINE),
            secondRowContent = SecondRowContent.valueOf(data.getString(CapsuleSettings.SECOND_ROW_CONTENT)),
            swapTranslation = if (version >= 12) flag(CapsuleSettings.SWAP_TRANSLATION) else false,
            singleLineSwitchMode = LyricSwitchMode.valueOf(data.getString(CapsuleSettings.SINGLE_LINE_SWITCH_MODE)),
            doubleLineSwitchMode = LyricSwitchMode.valueOf(data.getString(CapsuleSettings.DOUBLE_LINE_SWITCH_MODE)),
            missingTranslationMode = MissingTranslationMode.valueOf(data.getString(CapsuleSettings.MISSING_TRANSLATION_MODE)),
            fallbackSingleLineExpand = flag(CapsuleSettings.FALLBACK_SINGLE_LINE_EXPAND),
        )
        val storedTintMode = if (version >= 7) data.getString(CapsuleSettings.LYRIC_TINT_MODE)
            else CapsuleSettings.LEGACY_WHITE_TINT
        val legacyTintDisabled = version < 9 && storedTintMode == CapsuleSettings.LEGACY_WHITE_TINT
        val settings = lyrics.copy(
            enabled = flag(CapsuleSettings.ENABLED),
            artwork = flag(CapsuleSettings.ARTWORK),
            lyricTintEnabled = if (version >= 9) flag(CapsuleSettings.LYRIC_TINT_ENABLED) else !legacyTintDisabled,
            lyricTintMode = if (legacyTintDisabled) lyrics.lyricTintMode else LyricTintMode.fromStored(storedTintMode) ?: error("Invalid lyric tint mode"),
            showCover = if (version < 5) true else flag(CapsuleSettings.SHOW_COVER),
            showWaveform = if (version < 5) true else flag(CapsuleSettings.SHOW_WAVEFORM),
            lyricAlignment = if (version < 3) LyricAlignment.CENTER
            else LyricAlignment.valueOf(data.getString(CapsuleSettings.LYRIC_ALIGNMENT)),
            cameraFade = flag(CapsuleSettings.CAMERA_FADE),
            motion = LineMotion.fromStored(data.getString(CapsuleSettings.MOTION)) ?: error("Invalid line motion"),
            noLyricsMode = NoLyricsMode.valueOf(data.getString(CapsuleSettings.NO_LYRICS)),
            tuning = tuning,
        )
        val helpHidden = if (version < 10) null else {
            root.getJSONObject("app_preferences").get(TRANSLATION_HELP_HIDDEN) as? Boolean
                ?: error("Invalid app preference")
        }
        return Snapshot(settings, helpHidden)
    }
}

internal fun SharedPreferences.Editor.writeLyricSettings(settings: CapsuleSettings) {
    putBoolean(CapsuleSettings.DOUBLE_LINE, settings.doubleLine)
    putString(CapsuleSettings.SECOND_ROW_CONTENT, settings.secondRowContent.name)
    putBoolean(CapsuleSettings.SWAP_TRANSLATION, settings.swapTranslation)
    putString(CapsuleSettings.SINGLE_LINE_SWITCH_MODE, settings.singleLineSwitchMode.name)
    putString(CapsuleSettings.DOUBLE_LINE_SWITCH_MODE, settings.doubleLineSwitchMode.name)
    putString(CapsuleSettings.MISSING_TRANSLATION_MODE, settings.missingTranslationMode.name)
    putBoolean(CapsuleSettings.FALLBACK_SINGLE_LINE_EXPAND, settings.fallbackSingleLineExpand)
    remove(CapsuleSettings.LEGACY_TRANSLATION)
}

internal fun SharedPreferences.Editor.writeSettings(settings: CapsuleSettings) {
    putBoolean(CapsuleSettings.ENABLED, settings.enabled)
    writeLyricSettings(settings)
    putBoolean(CapsuleSettings.ARTWORK, settings.artwork)
    putBoolean(CapsuleSettings.LYRIC_TINT_ENABLED, settings.lyricTintEnabled)
    putString(CapsuleSettings.LYRIC_TINT_MODE, settings.lyricTintMode.name)
    putBoolean(CapsuleSettings.SHOW_COVER, settings.showCover)
    putBoolean(CapsuleSettings.SHOW_WAVEFORM, settings.showWaveform)
    remove("camera_shape") // Retired in version 4.
    putString(CapsuleSettings.LYRIC_ALIGNMENT, settings.lyricAlignment.name)
    putBoolean(CapsuleSettings.CAMERA_FADE, settings.cameraFade)
    putString(CapsuleSettings.MOTION, settings.motion.name)
    putString(CapsuleSettings.NO_LYRICS, settings.noLyricsMode.name)
    CapsuleSettings.retiredTintKeys.forEach { remove(it) }
    CapsuleTuning.entries.forEach { putInt(it.key, settings[it]) }
}
