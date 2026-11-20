package shop.terramoor.github.io.systemui

internal data class MediaCard(val id: String?, val owner: String?)

internal enum class MediaCardRoute { ACTIVE_PLAYER_ID, ONLY_CAPSULE_CARD, ACTIVE_PLAYER_NOT_PRESENT, AMBIGUOUS, EMPTY }

internal data class MediaCardSelection(val route: MediaCardRoute, val card: MediaCard? = null)

/** Use the controller's active player when known. A fallback never guesses among cards. */
internal fun selectMediaCard(preferredId: String?, cards: List<MediaCard>): MediaCardSelection {
    if (cards.isEmpty()) return MediaCardSelection(MediaCardRoute.EMPTY)
    if (preferredId == null) {
        val only = cards.singleOrNull() ?: return MediaCardSelection(MediaCardRoute.AMBIGUOUS)
        return MediaCardSelection(MediaCardRoute.ONLY_CAPSULE_CARD, only)
    }
    val matches = cards.filter { it.id == preferredId }
    return when (matches.size) {
        0 -> MediaCardSelection(MediaCardRoute.ACTIVE_PLAYER_NOT_PRESENT)
        1 -> MediaCardSelection(MediaCardRoute.ACTIVE_PLAYER_ID, matches.single())
        else -> MediaCardSelection(MediaCardRoute.AMBIGUOUS)
    }
}
