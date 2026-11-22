package shop.terramoor.github.io.settings

import android.content.SharedPreferences
import kotlin.math.roundToInt

internal enum class CapsuleTuning(
    val key: String, val range: IntRange, val default: Int, val step: Int = 1, val since: Int = 1,
) {
    FIRST_ROW_SIZE("title_size", 9..14, 11),
    SECOND_ROW_SIZE("translation_size", 7..11, 9),
    FIRST_ROW_OPACITY("title_opacity", 50..100, 100, 5),
    SECOND_ROW_OPACITY("translation_opacity", 40..100, 80, 5),
    ISLAND_WIDTH("island_width", 156..202, 170, 2),
    CAMERA_FADE_RADIUS("camera_fade_radius", 1..14, 3),
    BLUR("artwork_blur", 0..24, 14),
    ARTWORK_DIM("artwork_dim", 0..80, 25, 5),
    TINT_STRENGTH("tint_strength", 25..100, 100, 25, since = 8);

    fun normalize(value: Int): Int {
        val bounded = value.coerceIn(range)
        if (this != TINT_STRENGTH) return bounded
        return range.first + ((bounded - range.first) / step.toDouble()).roundToInt() * step
    }
}

internal enum class LyricAlignment { CENTER, LEFT }
internal enum class LineMotion {
    NONE, UP, FADE;

    companion object {
        fun fromStored(value: String?): LineMotion? =
            if (value == "DOWN") UP else entries.firstOrNull { it.name == value }
    }
}
internal enum class NoLyricsMode { EMPTY, TRACK }
internal enum class SecondRowContent { TRANSLATION, LYRIC }
internal enum class LyricSwitchMode { AFTER_END, AT_NEXT_START }
internal enum class MissingTranslationMode { SINGLE_LINE, DOUBLE_LYRICS }
internal enum class LyricTintMode { MEDIAN_CUT, WALLPAPER, OKLCH }

internal data class CapsuleSettings(
    val enabled: Boolean = true,
    val doubleLine: Boolean = true,
    val secondRowContent: SecondRowContent = SecondRowContent.TRANSLATION,
    val singleLineSwitchMode: LyricSwitchMode = LyricSwitchMode.AFTER_END,
    val doubleLineSwitchMode: LyricSwitchMode = LyricSwitchMode.AT_NEXT_START,
    val missingTranslationMode: MissingTranslationMode = MissingTranslationMode.SINGLE_LINE,
    val fallbackSingleLineExpand: Boolean = true,
    val artwork: Boolean = true,
    val lyricTintEnabled: Boolean = true,
    val lyricTintMode: LyricTintMode = LyricTintMode.WALLPAPER,
    val showCover: Boolean = true,
    val showWaveform: Boolean = true,
    val lyricAlignment: LyricAlignment = LyricAlignment.LEFT,
    val cameraFade: Boolean = true,
    val motion: LineMotion = LineMotion.UP,
    val noLyricsMode: NoLyricsMode = DEFAULT_NO_LYRICS_MODE,
    private val tuning: Map<CapsuleTuning, Int> = emptyMap(),
) {
    fun withTuning(values: Map<CapsuleTuning, Int>): CapsuleSettings = copy(tuning = tuning + values)

    operator fun get(option: CapsuleTuning): Int = tuning[option] ?: option.default

    val canShowNextLyric: Boolean
        get() = doubleLine &&
            (secondRowContent == SecondRowContent.LYRIC || missingTranslationMode == MissingTranslationMode.DOUBLE_LYRICS)

    fun effectiveFirstRowSize(missingTranslationSingleLine: Boolean): Int =
        (get(CapsuleTuning.FIRST_ROW_SIZE) + if (missingTranslationSingleLine && fallbackSingleLineExpand) 2 else 0)
            .coerceAtMost(CapsuleTuning.FIRST_ROW_SIZE.range.last)

    val secondRowSizeRange: IntRange
        get() = CapsuleTuning.SECOND_ROW_SIZE.range.let { it.first..minOf(it.last, 23 - get(CapsuleTuning.FIRST_ROW_SIZE)) }

    val effectiveSecondRowSize: Int
        get() = get(CapsuleTuning.SECOND_ROW_SIZE).coerceIn(secondRowSizeRange)

    companion object {
        const val GROUP = "capsule"
        const val ENABLED = "enabled"
        const val LEGACY_TRANSLATION = "translation"
        const val DOUBLE_LINE = "double_line"
        const val SECOND_ROW_CONTENT = "second_row_content"
        const val SINGLE_LINE_SWITCH_MODE = "single_line_switch_mode"
        const val DOUBLE_LINE_SWITCH_MODE = "double_line_switch_mode"
        const val MISSING_TRANSLATION_MODE = "missing_translation_mode"
        const val FALLBACK_SINGLE_LINE_EXPAND = "fallback_single_line_expand"
        const val ARTWORK = "artwork"
        const val LYRIC_TINT_ENABLED = "lyric_tint_enabled"
        const val LYRIC_TINT_MODE = "lyric_tint_mode"
        const val LEGACY_WHITE_TINT = "WHITE"
        const val LEGACY_FIRST_ROW_TINT = "first_row_tint"
        const val LEGACY_SECOND_ROW_TINT = "second_row_tint"
        val retiredTintKeys = listOf(LEGACY_FIRST_ROW_TINT, LEGACY_SECOND_ROW_TINT,
            "tint_saturation", "tint_lightness", "tint_hue", "tint_contrast")

        fun migrateTintStrength(first: Int, second: Int): Int = CapsuleTuning.TINT_STRENGTH.normalize(
            ((first.coerceIn(0..100) + second.coerceIn(0..100)) / 2.0).roundToInt(),
        )
        // Preserve the original default blend with the native capsule surface.
        const val ARTWORK_BACKGROUND_ALPHA = 191
        const val SHOW_COVER = "show_cover"
        const val SHOW_WAVEFORM = "show_waveform"
        const val LYRIC_ALIGNMENT = "lyric_alignment"
        const val CAMERA_FADE = "camera_fade"
        const val MOTION = "line_motion"
        const val NO_LYRICS = "no_lyrics_mode"
        private val DEFAULT_NO_LYRICS_MODE = NoLyricsMode.TRACK

        private val defaults = CapsuleSettings()
        private val legacyKeys = setOf(ENABLED, LEGACY_TRANSLATION, ARTWORK, SHOW_COVER,
            SHOW_WAVEFORM, LYRIC_ALIGNMENT, CAMERA_FADE, MOTION, NO_LYRICS, "camera_shape", "text_width",
            "artwork_opacity") +
            CapsuleTuning.entries.filter { it.since < 6 }.map { it.key }

        fun migratedLegacy(translation: Boolean = defaults.doubleLine) =
            defaults.copy(doubleLine = translation, doubleLineSwitchMode = LyricSwitchMode.AFTER_END)

        fun read(prefs: SharedPreferences): CapsuleSettings {
            val legacy = if (legacyKeys.any(prefs::contains)) {
                migratedLegacy(prefs.getBoolean(LEGACY_TRANSLATION, defaults.doubleLine))
            } else defaults
            val storedTintMode = prefs.getString(LYRIC_TINT_MODE, null)
            return legacy.copy(
                enabled = prefs.getBoolean(ENABLED, defaults.enabled),
                doubleLine = prefs.getBoolean(DOUBLE_LINE, legacy.doubleLine),
                secondRowContent = prefs.enumValue(SECOND_ROW_CONTENT, legacy.secondRowContent),
                singleLineSwitchMode = prefs.enumValue(SINGLE_LINE_SWITCH_MODE, legacy.singleLineSwitchMode),
                doubleLineSwitchMode = prefs.enumValue(DOUBLE_LINE_SWITCH_MODE, legacy.doubleLineSwitchMode),
                missingTranslationMode = prefs.enumValue(MISSING_TRANSLATION_MODE, legacy.missingTranslationMode),
                fallbackSingleLineExpand = prefs.getBoolean(FALLBACK_SINGLE_LINE_EXPAND, legacy.fallbackSingleLineExpand),
                artwork = prefs.getBoolean(ARTWORK, defaults.artwork),
                lyricTintEnabled = prefs.getBoolean(LYRIC_TINT_ENABLED,
                    defaults.lyricTintEnabled && storedTintMode != LEGACY_WHITE_TINT),
                lyricTintMode = LyricTintMode.entries.firstOrNull { it.name == storedTintMode } ?: defaults.lyricTintMode,
                showCover = prefs.getBoolean(SHOW_COVER, defaults.showCover),
                showWaveform = prefs.getBoolean(SHOW_WAVEFORM, defaults.showWaveform),
                lyricAlignment = prefs.enumValue(LYRIC_ALIGNMENT, defaults.lyricAlignment),
                cameraFade = prefs.getBoolean(CAMERA_FADE, defaults.cameraFade),
                motion = LineMotion.fromStored(prefs.getString(MOTION, null)) ?: defaults.motion,
                noLyricsMode = prefs.enumValue(NO_LYRICS, defaults.noLyricsMode),
                tuning = CapsuleTuning.entries.associateWith { option ->
                    if (option == CapsuleTuning.TINT_STRENGTH && !prefs.contains(option.key) &&
                        (prefs.contains(LEGACY_FIRST_ROW_TINT) || prefs.contains(LEGACY_SECOND_ROW_TINT))) {
                        migrateTintStrength(prefs.getInt(LEGACY_FIRST_ROW_TINT, 60), prefs.getInt(LEGACY_SECOND_ROW_TINT, 45))
                    } else option.normalize(prefs.getInt(option.key, option.default))
                },
            )
        }

        private inline fun <reified T : Enum<T>> SharedPreferences.enumValue(key: String, fallback: T): T {
            val value = getString(key, null)
            return enumValues<T>().firstOrNull { it.name == value } ?: fallback
        }
    }
}
