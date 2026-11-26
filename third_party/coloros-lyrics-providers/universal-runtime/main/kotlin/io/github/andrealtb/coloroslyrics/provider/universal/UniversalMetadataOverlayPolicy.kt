package io.github.andrealtb.coloroslyrics.provider.universal

internal object UniversalMetadataOverlayPolicy {
    private const val UNIVERSAL_REMARK = "[ColorLyricBuiltin]"
    private const val UNIVERSAL_PROVIDER =
        "shop.terramoor.github.io.builtinlyrics"

    fun shouldPreserveExisting(existingLyricInfo: String?, force: Boolean): Boolean {
        if (force || existingLyricInfo == null) return false
        return !existingLyricInfo.contains(UNIVERSAL_REMARK) &&
            !existingLyricInfo.contains(UNIVERSAL_PROVIDER)
    }
}
