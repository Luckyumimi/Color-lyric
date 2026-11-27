package shop.terramoor.github.io.settings

import android.content.SharedPreferences
import kotlin.math.roundToInt

internal enum class CapsuleTuning(
    val key: String, val range: IntRange, val default: Int, val step: Int = 1, val since: Int = 1,
) {
    FIRST_ROW_SIZE("title_size", 9..14, 12),
    SECOND_ROW_SIZE("translation_size", 7..11, 8),
    FIRST_ROW_OPACITY("title_opacity", 50..100, 100, 5),
    SECOND_ROW_OPACITY("translation_opacity", 40..100, 80, 5),
    ISLAND_WIDTH("island_width", 156..202, 170, 2),
    BLUR("artwork_blur", 0..24, 5),
    ARTWORK_DIM("artwork_dim", 0..80, 0, 5),
    TINT_STRENGTH("tint_strength", 25..100, 100, 25, since = 8),
    TEXTURE_BRIGHTNESS("texture_brightness", 0..100, 70, since = 11),
    TEXTURE_CONTRAST("texture_contrast", 0..200, 100, since = 11),
    TEXTURE_SATURATION("texture_saturation", 0..200, 200, since = 11),
    TEXTURE_BLUR("texture_blur", 0..100, 100, since = 11);

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
internal enum class PauseBehavior { KEEP_CURRENT, RESTORE_NATIVE }
internal enum class SecondRowContent { TRANSLATION, LYRIC }
internal enum class LyricSwitchMode { AFTER_END, AT_NEXT_START }
internal enum class MissingTranslationMode { SINGLE_LINE, DOUBLE_LYRICS }
internal enum class LyricTintMode {
    MEDIAN_CUT, WALLPAPER, OKLCH, TEXTURE, GRADIENT;

    companion object {
        fun fromStored(value: String?): LyricTintMode? = when (value) {
            "MEDIAN_CUT", "WALLPAPER", "OKLCH" -> OKLCH
            "TEXTURE" -> TEXTURE
            "GRADIENT" -> GRADIENT
            else -> null
        }
    }
}

internal data class CapsuleSettings(
    val enabled: Boolean = true,
    val doubleLine: Boolean = true,
    val secondRowContent: SecondRowContent = SecondRowContent.TRANSLATION,
    val swapTranslation: Boolean = false,
    val wordLyrics: Boolean = true,
    val lyricBold: Boolean = true,
    val japaneseLyricFont: Boolean = false,
    val customLyricFontId: String? = null,
    val cameraWordAvoidance: Boolean = false,
    val introDots: Boolean = true,
    val missingTranslationMode: MissingTranslationMode = MissingTranslationMode.SINGLE_LINE,
    val fallbackSingleLineExpand: Boolean = true,
    val artwork: Boolean = true,
    val lyricTintEnabled: Boolean = true,
    val lyricTintMode: LyricTintMode = LyricTintMode.GRADIENT,
    val showCover: Boolean = true,
    val showWaveform: Boolean = true,
    val lyricAlignment: LyricAlignment = LyricAlignment.LEFT,
    val cameraFade: Boolean = true,
    val motion: LineMotion = LineMotion.UP,
    val noLyricsMode: NoLyricsMode = DEFAULT_NO_LYRICS_MODE,
    val pauseBehavior: PauseBehavior = PauseBehavior.KEEP_CURRENT,
    val cos16AodLyrics: Boolean = true,
    val cos16AodUnlockWhitelist: Boolean = true,
    private val tuning: Map<CapsuleTuning, Int> = emptyMap(),
) {

    fun withTuning(values: Map<CapsuleTuning, Int>): CapsuleSettings = copy(tuning = tuning + values)

    operator fun get(option: CapsuleTuning): Int = tuning[option] ?: option.default

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
        const val SWAP_TRANSLATION = "swap_translation"
        const val WORD_LYRICS = "word_lyrics"
        const val LYRIC_BOLD = "lyric_bold"
        const val JAPANESE_LYRIC_FONT = "japanese_lyric_font"
        const val CUSTOM_LYRIC_FONT_ID = "custom_lyric_font_id"
        const val CAMERA_WORD_AVOIDANCE = "camera_word_avoidance"
        const val INTRO_DOTS = "intro_dots"
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
        const val PAUSE_BEHAVIOR = "pause_behavior"
        const val COS16_AOD_LYRICS = "cos16_aod_lyrics"
        const val COS16_AOD_UNLOCK_WHITELIST = "cos16_aod_unlock_whitelist"
        private val DEFAULT_NO_LYRICS_MODE = NoLyricsMode.TRACK

        private val defaults = CapsuleSettings()
        fun read(prefs: SharedPreferences): CapsuleSettings {
            val storedTintMode = prefs.getString(LYRIC_TINT_MODE, null)
            return defaults.copy(
                enabled = prefs.getBoolean(ENABLED, defaults.enabled),
                doubleLine = if (prefs.contains(DOUBLE_LINE)) prefs.getBoolean(DOUBLE_LINE, defaults.doubleLine)
                    else prefs.getBoolean(LEGACY_TRANSLATION, defaults.doubleLine),
                secondRowContent = prefs.enumValue(SECOND_ROW_CONTENT, defaults.secondRowContent),
                swapTranslation = prefs.getBoolean(SWAP_TRANSLATION, defaults.swapTranslation),
                wordLyrics = prefs.getBoolean(WORD_LYRICS, defaults.wordLyrics),
                lyricBold = prefs.getBoolean(LYRIC_BOLD, defaults.lyricBold),
                japaneseLyricFont = prefs.getBoolean(JAPANESE_LYRIC_FONT, defaults.japaneseLyricFont),
                customLyricFontId = prefs.getString(CUSTOM_LYRIC_FONT_ID, null)?.takeIf(::isCustomFontId),
                cameraWordAvoidance = prefs.getBoolean(CAMERA_WORD_AVOIDANCE, defaults.cameraWordAvoidance),
                introDots = prefs.getBoolean(INTRO_DOTS, defaults.introDots),
                missingTranslationMode = prefs.enumValue(MISSING_TRANSLATION_MODE, defaults.missingTranslationMode),
                fallbackSingleLineExpand = prefs.getBoolean(FALLBACK_SINGLE_LINE_EXPAND, defaults.fallbackSingleLineExpand),
                artwork = prefs.getBoolean(ARTWORK, defaults.artwork),
                lyricTintEnabled = prefs.getBoolean(LYRIC_TINT_ENABLED,
                    defaults.lyricTintEnabled && storedTintMode != LEGACY_WHITE_TINT),
                lyricTintMode = if (storedTintMode == LEGACY_WHITE_TINT) LyricTintMode.OKLCH
                    else LyricTintMode.fromStored(storedTintMode) ?: defaults.lyricTintMode,
                showCover = prefs.getBoolean(SHOW_COVER, defaults.showCover),
                showWaveform = prefs.getBoolean(SHOW_WAVEFORM, defaults.showWaveform),
                lyricAlignment = prefs.enumValue(LYRIC_ALIGNMENT, defaults.lyricAlignment),
                cameraFade = prefs.getBoolean(CAMERA_FADE, defaults.cameraFade),
                motion = LineMotion.fromStored(prefs.getString(MOTION, null)) ?: defaults.motion,
                noLyricsMode = prefs.enumValue(NO_LYRICS, defaults.noLyricsMode),
                pauseBehavior = prefs.enumValue(PAUSE_BEHAVIOR, defaults.pauseBehavior),
                cos16AodLyrics = prefs.getBoolean(COS16_AOD_LYRICS, defaults.cos16AodLyrics),
                cos16AodUnlockWhitelist = prefs.getBoolean(COS16_AOD_UNLOCK_WHITELIST, defaults.cos16AodUnlockWhitelist),
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
