package io.github.andrealtb.coloroslyrics.provider.universal.api

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import io.github.andrealtb.coloroslyrics.provider.universal.UniversalDiagnostics

class LyricsSourceAggregator(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .addInterceptor { chain ->
            val budget = lookupBudget.get()
            if (budget != null) {
                val remaining = budget.acquire(System.nanoTime())
                    ?: throw IOException("Lyric lookup budget exhausted")
                chain.call().timeout().timeout(remaining, TimeUnit.NANOSECONDS)
            }
            chain.proceed(chain.request())
        }
        .build(),
    val qqSource: LyricsSource = QQMusicSource(client),
    val netEaseSource: LyricsSource = NetEaseSource(client),
    val appleMusicSource: LyricsSource = AppleMusicLyricSource(client),
    val kugouSource: LyricsSource = KugouSource(client)
) {
    private val lrclibSource: LyricsSource = LrclibSource(client)

    private val communitySources by lazy { SourceId.entries.filter { it.ordinal > SourceId.LRCLIB.ordinal }
        .associateWith { CommunityLyricsSource(client, it) } }

    companion object {
        private val lookupBudget = ThreadLocal<LyricLookupBudget>()

        private inline fun <T> bounded(block: () -> T): T {
            if (lookupBudget.get() != null) return block()
            lookupBudget.set(LyricLookupBudget(System.nanoTime()))
            return try { block() } finally { lookupBudget.remove() }
        }
        // Accessed by app IO jobs, so entering a screen never initializes the network stack.
        val shared: LyricsSourceAggregator by lazy { LyricsSourceAggregator() }
    }

    enum class SourceId(val label: String) {
        QQ("QQ音乐"),
        NETEASE("网易云音乐"),
        KUGOU("酷狗音乐"),
        APPLE_MUSIC("Apple Music"),
        LRCLIB("LRCLIB"),
        UNISON("Unison"),
        LRC_RED("lrc.red"),
        KARALYR("Karalyr"),
        AMLL("AMLL TTML DB"),
        LUNABEAT("LunaBeat / TTML Hub")
    }

    fun source(id: SourceId): LyricsSource = when (id) {
        SourceId.QQ -> qqSource
        SourceId.NETEASE -> netEaseSource
        SourceId.KUGOU -> kugouSource
        SourceId.APPLE_MUSIC -> appleMusicSource
        SourceId.LRCLIB -> lrclibSource
        SourceId.UNISON, SourceId.LRC_RED, SourceId.KARALYR, SourceId.AMLL, SourceId.LUNABEAT ->
            communitySources.getValue(id)
    }

    fun sourceOf(label: String): LyricsSource? =
        SourceId.values().firstOrNull { it.label == label }?.let(::source)

    /** Manual search for one source; a failing source yields no rows instead of throwing. */
    fun searchCandidates(id: SourceId, query: String): List<SearchCandidate> = bounded {
        runCatching { source(id).searchCandidates(query) }.getOrDefault(emptyList()).distinctBy { it.source + it.id }
    }

    /** Manual binding for one user-picked row; the row's own label selects the source. */
    fun fetchByCandidate(candidate: SearchCandidate, preferTtml: Boolean = false): LyricResult? = bounded {
        sourceOf(candidate.source)?.let { source ->
            runCatching { source.fetchLyricByCandidate(candidate) }.getOrNull()
        }?.let { if (preferTtml) TtmlLyricWriter.withConvertedTtml(it) else it }
    }

    /**
     * Fetches the best lyric for [query] under the user's source order.
     *
     * Priority is the contract: a lower-ranked source may only win when the higher-ranked one had
     * nothing usable. Without [preferWordTiming] the first source that returns real lyrics wins
     * outright; with it the first word-timed answer wins, and a line-timed answer from a higher
     * priority source still beats a line-timed answer from a lower one.
     *
     * A pure-music/credits-only payload never short-circuits the walk, so an "instrumental" entry
     * in one catalogue cannot block a real lyric in the next one.
     *
     * With [preferTtml] the enabled sources that deliver TTML are asked first (keeping their
     * relative order) and a delivered TTML wins outright; when none has one, the usual winner is
     * converted to TTML. Sources the user disabled stay disabled.
     */
    fun fetchFirst(
        query: TrackQuery,
        priority: List<SourceId> = SourceId.entries.filterNot { it == SourceId.LRCLIB },
        preferWordTiming: Boolean = false,
        preferTtml: Boolean = false,
        shouldContinue: () -> Boolean = { true },
    ): FetchReport = bounded {
        val report = fetchInOrder(query, priority, preferWordTiming, preferTtml, shouldContinue)
        val result = report.result ?: return@bounded report
        if (!preferTtml || SongMatchScorer.isInstrumentalOrCreditsOnly(result.lyric)) return@bounded report
        report.copy(result = TtmlLyricWriter.withConvertedTtml(result))
    }

    private fun fetchInOrder(
        query: TrackQuery,
        priority: List<SourceId>,
        preferWordTiming: Boolean,
        preferTtml: Boolean,
        shouldContinue: () -> Boolean,
    ): FetchReport {
        val ordered = priority.distinct().let { ids ->
            if (preferTtml) ids.sortedBy { if (source(it).deliversTtml) 0 else 1 } else ids
        }
        if (ordered.isEmpty()) {
            return FetchReport(result = null, transientFailure = false, attemptCount = 0, reason = "priorityEmpty")
        }
        val priorityText = ordered.joinToString(",") { it.name }
        var transientFailure = false
        val reasons = mutableListOf<String>()
        var lineFallback: LyricResult? = null
        var instrumentalFallback: LyricResult? = null

        for (id in ordered) {
            if (!shouldContinue()) return FetchReport(null, false, reasons.size, "superseded")
            // One unavailable provider must not spend the whole lookup budget and
            // prevent the remaining enabled providers from being tried.
            lookupBudget.get()?.beginSource(System.nanoTime())
            val started = System.currentTimeMillis()
            UniversalDiagnostics.sourceAttempt(id.name, query.generation, priorityText)
            val attempt = runCatching { source(id).fetchLyrics(query) }
            val elapsed = System.currentTimeMillis() - started
            val result = attempt.getOrNull()
            if (attempt.isFailure) {
                transientFailure = true
                val exceptionName = attempt.exceptionOrNull()?.javaClass?.simpleName ?: "Unknown"
                UniversalDiagnostics.sourceFailed(id.name, query.generation, exceptionName, elapsed)
                reasons += id.name + ":exception:" + exceptionName
                continue
            }
            if (result == null || result.lyric.isBlank()) {
                UniversalDiagnostics.sourceEmpty(id.name, query.generation, elapsed, "blankOrUnmatched")
                reasons += id.name + ":empty"
                continue
            }

            val instrumental = SongMatchScorer.isInstrumentalOrCreditsOnly(result.lyric)
            if (instrumental) {
                reasons += id.name + ":instrumental"
                if (instrumentalFallback == null) instrumentalFallback = result
                continue
            }

            val score = qualityScore(result)
            reasons += id.name + ":hit:" + score
            val wordTimed = WordTimedLyricConverter.hasRealWordTiming(result.rawLyric ?: result.lyric)
            if (preferTtml && result.format == LyricFormats.TTML) {
                return FetchReport(
                    result = result,
                    transientFailure = false,
                    attemptCount = reasons.size,
                    reason = "hit:" + id.name + ":ttml"
                )
            }
            if (!preferWordTiming || wordTimed) {
                return FetchReport(
                    result = result,
                    transientFailure = false,
                    attemptCount = reasons.size,
                    reason = "hit:" + id.name + if (wordTimed) ":wordTimed" else ":line"
                )
            }
            if (lineFallback == null) lineFallback = result
        }

        lineFallback?.let {
            return FetchReport(
                result = it,
                transientFailure = false,
                attemptCount = reasons.size,
                reason = "fallback:" + it.source
            )
        }
        instrumentalFallback?.let {
            return FetchReport(
                result = it,
                transientFailure = false,
                attemptCount = reasons.size,
                reason = "instrumental:" + it.source
            )
        }
        return FetchReport(
            result = null,
            transientFailure = transientFailure,
            attemptCount = reasons.size,
            reason = if (reasons.isEmpty()) "noSources" else reasons.joinToString(";")
        )
    }

    private fun qualityScore(result: LyricResult): Int {
        var score = 1
        if (WordTimedLyricConverter.hasRealWordTiming(result.rawLyric ?: result.lyric)) score += 100
        if (!result.translationLyric.isNullOrBlank()) score += 10
        return score
    }
}
