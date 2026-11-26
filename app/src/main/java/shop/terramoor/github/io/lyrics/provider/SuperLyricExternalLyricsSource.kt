package shop.terramoor.github.io.lyrics.provider

import android.content.Context
import com.hchen.superlyricapi.LyricListener
import com.hchen.superlyricapi.LyricView
import com.hchen.superlyricapi.Receiver
import com.hchen.superlyricapi.SuperLyric
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.LyricWord
import io.github.andrealtb.coloroslyrics.provider.parser.lrc.model.RichLyricLine
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal class SuperLyricExternalLyricsSource(
    private val receiver: Receiver = SuperLyric.receiver(),
) : ExternalLyricsSource {
    override val provider = ExternalLyricsProvider.SUPER_LYRIC

    private val generation = AtomicLong()
    private val stateLock = Any()
    private var callbackRevision = 0L
    private val replayExecutor = Executors.newSingleThreadScheduledExecutor()
    private var replayTask: ScheduledFuture<*>? = null
    @Volatile private var callbacks: ExternalLyricsCallbacks? = null
    @Volatile private var listener: LyricListener? = null
    @Volatile private var activePackage: String? = null
    @Volatile private var lastPublication: ExternalLyricsPublication? = null
    override fun start(context: Context, callbacks: ExternalLyricsCallbacks) = start(callbacks)

    @Synchronized
    internal fun start(callbacks: ExternalLyricsCallbacks) {
        stop()
        this.callbacks = callbacks
        val ticket = generation.incrementAndGet()
        val next = object : LyricListener {
            override fun onLyric(view: LyricView) {
                synchronized(stateLock) {
                    if (ticket != generation.get()) return
                    callbackRevision++
                    accept(view, callbacks)
                }
            }

            override fun onStop(stop: com.hchen.superlyricapi.model.Stop) {
                synchronized(stateLock) {
                    if (ticket != generation.get()) return
                    val publisher = stop.publisher?.trim()?.takeIf(String::isNotBlank)
                    val current = activePackage
                    if (publisher != null && current != null && publisher != current) return
                    val trackId = lastPublication?.track?.id
                    if (!trackId.isNullOrBlank() && !stop.id.isNullOrBlank() && stop.id != trackId) return
                    callbackRevision++
                    activePackage = null
                    lastPublication = null
                    callbacks.onStopped(publisher ?: current)
                    callbacks.onActivePlayerChanged(null)
                }
            }
        }
        listener = next
        val result = receiver.register(next)
        check(result == Receiver.RegisterResult.OK) { "SuperLyric receiver registration failed: $result" }
        readLatest()
        replayTask?.cancel(false)
        replayTask = replayExecutor.scheduleWithFixedDelay({
            synchronized(this) {
                if (generation.get() == ticket) runCatching { reconnect() }
            }
        }, 1, 2, TimeUnit.SECONDS)
    }

    /** Re-registers after the service process is replaced; duplicate registration is harmless. */
    @Synchronized
    fun reconnect(): Boolean {
        val current = listener ?: return false
        val connected = receiver.register(current) == Receiver.RegisterResult.OK
        if (connected) readLatest()
        return connected
    }

    private fun readLatest() {
        val (ticket, revision) = synchronized(stateLock) { generation.get() to callbackRevision }
        val sink = callbacks ?: return
        val latest = runCatching { receiver.latest() }.getOrNull() ?: return
        synchronized(stateLock) {
            if (ticket == generation.get() && revision == callbackRevision) accept(latest, sink)
        }
    }

    private fun accept(view: LyricView, sink: ExternalLyricsCallbacks) {
        val publication = SuperLyricPublicationMapper.read(view) ?: return
        val previous = activePackage
        activePackage = publication.playerPackage
        if (previous != publication.playerPackage) sink.onActivePlayerChanged(publication.playerPackage)
        if (publication == lastPublication) return
        lastPublication = publication
        sink.onPublication(publication)
    }

    @Synchronized
    override fun stop() {
        replayTask?.cancel(false)
        replayTask = null
        val previous = synchronized(stateLock) {
            generation.incrementAndGet()
            listener.also {
                listener = null
                callbacks = null
                activePackage = null
                lastPublication = null
            }
        }
        if (previous != null) runCatching { receiver.unregister(previous) }
    }
}

internal object SuperLyricPublicationMapper {
    private const val MAX_LINES = 2_000
    private const val MAX_WORDS_PER_LINE = 500
    private const val MAX_TEXT = 120_000

    fun read(view: LyricView): ExternalLyricsPublication? {
        val lyric = view.lyric
        val packageName = view.publisher?.trim()?.takeIf(String::isNotBlank) ?: return null
        val title = lyric.title.trim()
        if (title.isBlank() || lyric.lines.isEmpty() || lyric.lines.size > MAX_LINES) return null
        var textSize = title.length + lyric.artist.length + lyric.album.length
        val converted = lyric.lines.mapIndexedNotNull { index, line ->
            if (line.text.isBlank() || line.start < 0L) return@mapIndexedNotNull null
            val end = line.end.takeIf { it > line.start }
                ?: lyric.lines.getOrNull(index + 1)?.start?.takeIf { it > line.start }
                ?: lyric.duration.takeIf { it > line.start }
                ?: (line.start + 3_000L)
            textSize += line.text.length + line.translation.orEmpty().length
            val sourceWords = line.words.orEmpty()
            if (sourceWords.size > MAX_WORDS_PER_LINE) return null
            val words = sourceWords.mapNotNull { word ->
                if (word.text.isEmpty() || word.start < line.start || word.end <= word.start) null
                else {
                    textSize += word.text.length
                    LyricWord(begin = word.start, end = word.end, text = word.text)
                }
            }
            RichLyricLine(
                begin = line.start,
                end = end,
                duration = end - line.start,
                text = line.text,
                words = words.takeIf { it.isNotEmpty() },
                secondary = line.translation,
            )
        }
        if (converted.isEmpty() || textSize > MAX_TEXT) return null
        return ExternalLyricsPublication(
            playerPackage = packageName,
            track = TrackIdentity(
                id = lyric.id,
                title = title,
                artist = lyric.artist,
                album = lyric.album,
                durationMs = lyric.duration.coerceAtLeast(0L),
            ),
            lines = converted,
        )
    }
}
