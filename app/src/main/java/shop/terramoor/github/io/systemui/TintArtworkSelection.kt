package shop.terramoor.github.io.systemui

import java.lang.ref.WeakReference

internal enum class TintArtworkOrigin {
    NOT_REQUESTED, MEDIA_ALBUM_ART, NATIVE_COVER, WAITING_FOR_ALBUM_ART, WAITING_FOR_NATIVE_COVER_UPDATE,
}

internal data class TintArtwork<Image>(val bitmap: Image?, val origin: TintArtworkOrigin)

/** Remembers the last image in the verified cover slot across track and session handoffs. */
internal class TintArtworkSelection<Key : Any, Image : Any> {
    private class Revision<T : Any>(bitmap: T, private val generation: Int) {
        private val image = WeakReference(bitmap)
        fun matches(bitmap: T, generation: Int) = image.get() === bitmap && this.generation == generation
    }

    private var track: Key? = null
    private var lastCover: Revision<Image>? = null
    private var precedingCover: Revision<Image>? = null

    fun onTrack(key: Key) {
        if (track == key) return
        precedingCover = lastCover
        track = key
    }

    fun select(
        enabled: Boolean, media: Image?, native: Image?, generation: Int, trackIdentified: Boolean,
    ): TintArtwork<Image> {
        val unchanged = native != null && precedingCover?.matches(native, generation) == true
        if (trackIdentified && native != null && !unchanged && lastCover?.matches(native, generation) != true) {
            lastCover = Revision(native, generation)
        }
        return when {
            !enabled -> TintArtwork(null, TintArtworkOrigin.NOT_REQUESTED)
            media != null -> TintArtwork(media, TintArtworkOrigin.MEDIA_ALBUM_ART)
            !trackIdentified || track == null || native == null ->
                TintArtwork(null, TintArtworkOrigin.WAITING_FOR_ALBUM_ART)
            unchanged ->
                TintArtwork(null, TintArtworkOrigin.WAITING_FOR_NATIVE_COVER_UPDATE)
            else -> TintArtwork(native, TintArtworkOrigin.NATIVE_COVER)
        }
    }
}
