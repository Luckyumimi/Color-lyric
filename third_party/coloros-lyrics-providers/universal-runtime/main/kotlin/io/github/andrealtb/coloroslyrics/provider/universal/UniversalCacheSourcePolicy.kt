package io.github.andrealtb.coloroslyrics.provider.universal

import org.json.JSONObject

/** Direct-player results must not masquerade as network results after changing modes. */
internal object UniversalCacheSourcePolicy {
    const val KIND_KEY = "colorLyricCacheKind"
    const val PLAYER = "player"
    const val NETWORK = "network"

    fun canUse(payload: String, directMode: Boolean): Boolean {
        if (directMode) return true
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return false
        return when (json.optString(KIND_KEY)) {
            PLAYER -> false
            NETWORK -> true
            else -> {
                val source = json.optString("source")
                if (source.startsWith("player/")) false
                else json.optString("provider") == "shop.terramoor.github.io.builtinlyrics" ||
                    json.optString("remark").contains("[ColorLyricBuiltin]")
            }
        }
    }
}
