package shop.terramoor.github.io.lyrics.provider

import io.github.proify.lyricon.central.CentralRuntime
import io.github.proify.lyricon.central.provider.player.ActivePlayerListener
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.ProviderInfo

internal class CentralLyricsListener(
    private val publish: (CentralLyricsListener, String?, ExternalLyricsPublication?) -> Unit,
) : ActivePlayerListener {
    private var active: ProviderInfo? = null
    private var latest: ExternalLyricsPublication? = null

    fun start() { CentralRuntime.activePlayers.addListener(this) }
    fun stop() { CentralRuntime.activePlayers.removeListener(this) }

    @Synchronized
    fun replay() { publish(this, active?.providerPackageName, latest) }

    @Synchronized
    override fun onActiveProviderChanged(providerInfo: ProviderInfo?) {
        if (active == providerInfo) return
        active = providerInfo
        latest = null
        replay()
    }

    @Synchronized
    override fun onSongChanged(song: Song?) {
        val next = active?.playerPackageName?.let { song?.toPublication(it) }
        if (latest == next) return
        latest = next
        replay()
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) = Unit
    override fun onPositionChanged(position: Long) = Unit
    override fun onSeekTo(position: Long) = Unit
    override fun onSendText(text: String?) = Unit
    override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) = Unit
    override fun onDisplayRomaChanged(isDisplayRoma: Boolean) = Unit
}
