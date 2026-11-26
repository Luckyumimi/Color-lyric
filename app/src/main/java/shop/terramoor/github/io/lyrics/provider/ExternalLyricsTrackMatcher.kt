package shop.terramoor.github.io.lyrics.provider

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy

internal object ExternalLyricsTrackMatcher {
    fun matches(
        sessionPackage: String,
        sessionTitle: String?,
        sessionArtist: String?,
        sessionDurationMs: Long?,
        publication: ExternalLyricsPublication,
    ): Boolean {
        if (sessionPackage.isBlank() || publication.playerPackage != sessionPackage) return false
        val title = sessionTitle?.trim().orEmpty()
        val incomingTitle = publication.track.title?.trim().orEmpty()
        if (title.isBlank() || incomingTitle.isBlank()) return false
        val incomingArtist = publication.track.artist?.trim()?.takeIf(String::isNotBlank)
        val resolvedArtist = sessionArtist?.trim()?.takeIf(String::isNotBlank)
        return TrackIdentityPolicy.isSameTrack(
            TrackIdentity(
                title = title,
                artist = resolvedArtist ?: incomingArtist,
                durationMs = sessionDurationMs?.coerceAtLeast(0L) ?: 0L,
            ),
            TrackIdentity(
                title = incomingTitle,
                artist = incomingArtist ?: resolvedArtist,
                durationMs = publication.track.durationMs.coerceAtLeast(0L),
            ),
        )
    }
}
