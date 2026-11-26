package shop.terramoor.github.io.lyrics.provider

/** Processes source requests in subscription order, including releases delivered before a request. */
internal class ExternalLyricsSelection {
    var epoch: Long = 0
        private set
    var source: ExternalLyricsProvider? = null
        private set

    fun update(provider: ExternalLyricsProvider, epoch: Long, active: Boolean): Boolean {
        if (provider != ExternalLyricsProvider.LYRICON && provider != ExternalLyricsProvider.SUPER_LYRIC) return false
        if (epoch <= 0 || epoch < this.epoch) return false
        if (epoch == this.epoch && source != provider) return false
        this.epoch = epoch
        source = provider.takeIf { active }
        return true
    }

    fun matches(provider: ExternalLyricsProvider, epoch: Long): Boolean =
        source == provider && this.epoch == epoch
}
