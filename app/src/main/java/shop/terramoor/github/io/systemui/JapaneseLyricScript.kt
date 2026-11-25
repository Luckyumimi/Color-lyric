package shop.terramoor.github.io.systemui

internal enum class LyricFontSource { SYSTEM, JAPANESE, CUSTOM }

internal fun lyricFontSource(enabled: Boolean, customFontId: String?, text: CharSequence?,
                             translation: Boolean): LyricFontSource = when {
    !enabled -> LyricFontSource.SYSTEM
    customFontId != null -> LyricFontSource.CUSTOM
    !translation && containsJapaneseKana(text) -> LyricFontSource.JAPANESE
    else -> LyricFontSource.SYSTEM
}

internal fun containsJapaneseKana(text: CharSequence?): Boolean {
    if (text == null) return false
    var index = 0
    while (index < text.length) {
        val codePoint = Character.codePointAt(text, index)
        if (codePoint in 0x3041..0x3096 || codePoint in 0x309d..0x309f ||
            codePoint in 0x30a1..0x30fa || codePoint in 0x30fd..0x30ff ||
            codePoint in 0x31f0..0x31ff || codePoint in 0xff66..0xff9d ||
            codePoint in 0x1b000..0x1b16f
        ) return true
        index += Character.charCount(codePoint)
    }
    return false
}
