package shop.terramoor.github.io.systemui

import java.lang.ref.WeakReference

internal enum class TintArtworkOrigin {
    NOT_REQUESTED, MEDIA_ALBUM_ART, NATIVE_COVER, WAITING_FOR_ALBUM_ART, WAITING_FOR_NATIVE_COVER_UPDATE,
}

internal data class TintArtwork<Image>(val bitmap: Image?, val origin: TintArtworkOrigin)

/** Remembers the last image in the verified cover slot across track and session handoffs. */
internal class TintArtworkSelection<Key : Any, Image : Any> {
    private class Revision<Key : Any, Image : Any>(val track: Key, bitmap: Image, private val generation: Int) {
        private val image = WeakReference(bitmap)
        fun matches(bitmap: Image, generation: Int) = image.get() === bitmap && this.generation == generation
    }

    private var track: Key? = null
    private var lastCover: Revision<Key, Image>? = null
    private var precedingCover: Revision<Key, Image>? = null

    fun onTrack(key: Key) {
        if (track == key) return
        // A short handoff may end before the new player ever replaces the old cover.
        precedingCover = lastCover?.takeUnless { it.track == key }
        track = key
    }

    fun select(
        enabled: Boolean, media: Image?, native: Image?, generation: Int, trackIdentified: Boolean,
    ): TintArtwork<Image> {
        val currentTrack = track?.takeIf { trackIdentified }
        val unchanged = native != null && precedingCover?.matches(native, generation) == true
        if (currentTrack != null && native != null && !unchanged && lastCover?.matches(native, generation) != true) {
            lastCover = Revision(currentTrack, native, generation)
        }
        return when {
            !enabled -> TintArtwork(null, TintArtworkOrigin.NOT_REQUESTED)
            media != null -> TintArtwork(media, TintArtworkOrigin.MEDIA_ALBUM_ART)
            currentTrack == null || native == null ->
                TintArtwork(null, TintArtworkOrigin.WAITING_FOR_ALBUM_ART)
            unchanged ->
                TintArtwork(null, TintArtworkOrigin.WAITING_FOR_NATIVE_COVER_UPDATE)
            else -> TintArtwork(native, TintArtworkOrigin.NATIVE_COVER)
        }
    }
}
