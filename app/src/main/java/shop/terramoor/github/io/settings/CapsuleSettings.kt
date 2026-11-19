package shop.terramoor.github.io.settings

import android.content.SharedPreferences

internal enum class CapsuleTuning(val key: String, val range: IntRange, val default: Int, val step: Int = 1) {
    TITLE_SIZE("title_size", 9..14, 11),
    TRANSLATION_SIZE("translation_size", 7..11, 9),
    TITLE_OPACITY("title_opacity", 50..100, 100, 5),
    TRANSLATION_OPACITY("translation_opacity", 40..100, 80, 5),
    ISLAND_WIDTH("island_width", 156..202, 174, 2),
    CAMERA_FADE_RADIUS("camera_fade_radius", 1..14, 3),
    BLUR("artwork_blur", 0..24, 14),
    ARTWORK_OPACITY("artwork_opacity", 10..100, 75, 5),
    ARTWORK_DIM("artwork_dim", 0..80, 25, 5),
}

internal enum class LyricAlignment { CENTER, LEFT }
internal enum class LineMotion { NONE, UP, DOWN, FADE }
internal enum class NoLyricsMode { EMPTY, TRACK }

internal data class CapsuleSettings(
    val enabled: Boolean = true,
    val translation: Boolean = true,
    val artwork: Boolean = true,
    val lyricAlignment: LyricAlignment = LyricAlignment.LEFT,
    val cameraFade: Boolean = true,
    val motion: LineMotion = LineMotion.UP,
    val noLyricsMode: NoLyricsMode = DEFAULT_NO_LYRICS_MODE,
    private val tuning: Map<CapsuleTuning, Int> = emptyMap(),
) {
    fun withTuning(values: Map<CapsuleTuning, Int>): CapsuleSettings = copy(tuning = tuning + values)

    operator fun get(option: CapsuleTuning): Int = tuning[option] ?: option.default

    fun effectiveTitleSize(hasSecondLine: Boolean): Int =
        (get(CapsuleTuning.TITLE_SIZE) + if (hasSecondLine) 0 else 2).coerceAtMost(CapsuleTuning.TITLE_SIZE.range.last)

    val translationSizeRange: IntRange
        get() = CapsuleTuning.TRANSLATION_SIZE.range.let { it.first..minOf(it.last, 23 - get(CapsuleTuning.TITLE_SIZE)) }

    val effectiveTranslationSize: Int
        get() = get(CapsuleTuning.TRANSLATION_SIZE).coerceIn(translationSizeRange)

    companion object {
        const val GROUP = "capsule"
        const val ENABLED = "enabled"
        const val TRANSLATION = "translation"
        const val ARTWORK = "artwork"
        const val LYRIC_ALIGNMENT = "lyric_alignment"
        const val CAMERA_FADE = "camera_fade"
        const val MOTION = "line_motion"
        const val NO_LYRICS = "no_lyrics_mode"
        private val DEFAULT_NO_LYRICS_MODE = NoLyricsMode.TRACK

        private val defaults = CapsuleSettings()

        fun read(prefs: SharedPreferences) = CapsuleSettings(
            enabled = prefs.getBoolean(ENABLED, defaults.enabled),
            translation = prefs.getBoolean(TRANSLATION, defaults.translation),
            artwork = prefs.getBoolean(ARTWORK, defaults.artwork),
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
