package shop.terramoor.github.io.settings

import android.content.SharedPreferences

internal enum class CapsuleTuning(val key: String, val range: IntRange, val default: Int, val step: Int = 1) {
    TITLE_SIZE("title_size", 9..14, 11),
    TRANSLATION_SIZE("translation_size", 7..11, 9),
    TITLE_OPACITY("title_opacity", 50..100, 100, 5),
    TRANSLATION_OPACITY("translation_opacity", 40..100, 80, 5),
    ISLAND_WIDTH("island_width", 156..202, 170, 2),
    CAMERA_FADE_RADIUS("camera_fade_radius", 1..14, 3),
    BLUR("artwork_blur", 0..24, 14),
    ARTWORK_OPACITY("artwork_opacity", 10..100, 75, 5),
    ARTWORK_DIM("artwork_dim", 0..80, 25, 5);

    companion object {
        val FIRST_ROW_SIZE: CapsuleTuning get() = TITLE_SIZE
        val SECOND_ROW_SIZE: CapsuleTuning get() = TRANSLATION_SIZE
        val FIRST_ROW_OPACITY: CapsuleTuning get() = TITLE_OPACITY
        val SECOND_ROW_OPACITY: CapsuleTuning get() = TRANSLATION_OPACITY
    }
}

internal enum class LyricAlignment { CENTER, LEFT }
internal enum class LineMotion { NONE, UP, DOWN, FADE }
internal enum class NoLyricsMode { EMPTY, TRACK }
internal enum class SecondRowContent { TRANSLATION, LYRIC }
internal enum class LyricSwitchMode { AFTER_END, AT_NEXT_START }
internal enum class MissingTranslationMode { SINGLE_LINE, DOUBLE_LYRICS }

internal data class CapsuleSettings(
    val enabled: Boolean = true,
    val doubleLine: Boolean = true,
    val secondRowContent: SecondRowContent = SecondRowContent.TRANSLATION,
    val singleLineSwitchMode: LyricSwitchMode = LyricSwitchMode.AFTER_END,
    val doubleLineSwitchMode: LyricSwitchMode = LyricSwitchMode.AT_NEXT_START,
    val missingTranslationMode: MissingTranslationMode = MissingTranslationMode.SINGLE_LINE,
    val fallbackSingleLineExpand: Boolean = true,
    val artwork: Boolean = true,
    val showCover: Boolean = true,
    val showWaveform: Boolean = true,
    val lyricAlignment: LyricAlignment = LyricAlignment.LEFT,
    val cameraFade: Boolean = true,
    val motion: LineMotion = LineMotion.UP,
    val noLyricsMode: NoLyricsMode = DEFAULT_NO_LYRICS_MODE,
    private val tuning: Map<CapsuleTuning, Int> = emptyMap(),
) {
    @Deprecated("Use doubleLine")
    val translation: Boolean get() = doubleLine

    fun withTuning(values: Map<CapsuleTuning, Int>): CapsuleSettings = copy(tuning = tuning + values)

    operator fun get(option: CapsuleTuning): Int = tuning[option] ?: option.default

    fun effectiveTitleSize(hasSecondLine: Boolean, expandSingleLine: Boolean = false): Int =
        (get(CapsuleTuning.FIRST_ROW_SIZE) + if (!hasSecondLine && expandSingleLine) 2 else 0)
            .coerceAtMost(CapsuleTuning.FIRST_ROW_SIZE.range.last)

    val translationSizeRange: IntRange
        get() = CapsuleTuning.SECOND_ROW_SIZE.range.let { it.first..minOf(it.last, 23 - get(CapsuleTuning.FIRST_ROW_SIZE)) }

    val effectiveTranslationSize: Int
        get() = get(CapsuleTuning.SECOND_ROW_SIZE).coerceIn(translationSizeRange)

    val secondRowSizeRange: IntRange get() = translationSizeRange
    val effectiveSecondRowSize: Int get() = effectiveTranslationSize

    companion object {
        const val GROUP = "capsule"
        const val ENABLED = "enabled"
        const val DOUBLE_LINE = "double_line"
        const val SECOND_ROW_CONTENT = "second_row_content"
        const val SINGLE_LINE_SWITCH_MODE = "single_line_switch_mode"
        const val DOUBLE_LINE_SWITCH_MODE = "double_line_switch_mode"
        const val MISSING_TRANSLATION_MODE = "missing_translation_mode"
        const val FALLBACK_SINGLE_LINE_EXPAND = "fallback_single_line_expand"
        // Retained for reading v5 preferences and backups.
        const val TRANSLATION = "translation"
        const val ARTWORK = "artwork"
        const val SHOW_COVER = "show_cover"
        const val SHOW_WAVEFORM = "show_waveform"
        const val LYRIC_ALIGNMENT = "lyric_alignment"
        const val CAMERA_FADE = "camera_fade"
        const val MOTION = "line_motion"
        const val NO_LYRICS = "no_lyrics_mode"
        private val DEFAULT_NO_LYRICS_MODE = NoLyricsMode.TRACK

        private val defaults = CapsuleSettings()
        private val legacyKeys = buildSet {
            add(TRANSLATION)
            add(ARTWORK)
            add(SHOW_COVER)
            add(SHOW_WAVEFORM)
            add(LYRIC_ALIGNMENT)
            add(CAMERA_FADE)
            add(MOTION)
            add(NO_LYRICS)
            CapsuleTuning.entries.forEach { add(it.key) }
        }

        fun read(prefs: SharedPreferences): CapsuleSettings {
            val hasLegacySettings = prefs.all.keys.any(legacyKeys::contains)
            val doubleLine = if (prefs.contains(DOUBLE_LINE)) {
                prefs.getBoolean(DOUBLE_LINE, defaults.doubleLine)
            } else {
                prefs.getBoolean(TRANSLATION, defaults.doubleLine)
            }
            val secondRowContent = prefs.getString(SECOND_ROW_CONTENT, null)
                ?.let { value -> SecondRowContent.entries.firstOrNull { it.name == value } }
                ?: defaults.secondRowContent
            val singleSwitch = prefs.getString(SINGLE_LINE_SWITCH_MODE, null)
                ?.let { value -> LyricSwitchMode.entries.firstOrNull { it.name == value } }
                ?: if (hasLegacySettings) LyricSwitchMode.AFTER_END else defaults.singleLineSwitchMode
            val doubleSwitch = prefs.getString(DOUBLE_LINE_SWITCH_MODE, null)
                ?.let { value -> LyricSwitchMode.entries.firstOrNull { it.name == value } }
                ?: if (hasLegacySettings) LyricSwitchMode.AFTER_END else defaults.doubleLineSwitchMode
            return CapsuleSettings(
            enabled = prefs.getBoolean(ENABLED, defaults.enabled),
            doubleLine = doubleLine,
            secondRowContent = secondRowContent,
            singleLineSwitchMode = singleSwitch,
            doubleLineSwitchMode = doubleSwitch,
            missingTranslationMode = prefs.getString(MISSING_TRANSLATION_MODE, null)
                ?.let { value -> MissingTranslationMode.entries.firstOrNull { it.name == value } }
                ?: defaults.missingTranslationMode,
            fallbackSingleLineExpand = prefs.getBoolean(FALLBACK_SINGLE_LINE_EXPAND, defaults.fallbackSingleLineExpand),
            artwork = prefs.getBoolean(ARTWORK, defaults.artwork),
            showCover = prefs.getBoolean(SHOW_COVER, defaults.showCover),
            showWaveform = prefs.getBoolean(SHOW_WAVEFORM, defaults.showWaveform),
            lyricAlignment = prefs.getString(LYRIC_ALIGNMENT, null).let { value ->
                LyricAlignment.entries.firstOrNull { it.name == value } ?: defaults.lyricAlignment
            },
            cameraFade = prefs.getBoolean(CAMERA_FADE, defaults.cameraFade),
            motion = LineMotion.entries.firstOrNull { it.name == prefs.getString(MOTION, null) } ?: defaults.motion,
            noLyricsMode = NoLyricsMode.entries.firstOrNull { it.name == prefs.getString(NO_LYRICS, null) }
                ?: defaults.noLyricsMode,
            tuning = CapsuleTuning.entries.associateWith {
                prefs.getInt(it.key, it.default).coerceIn(it.range)
            },
            )
        }
    }
}
