package shop.terramoor.github.io.settings

import io.github.andrealtb.coloroslyrics.provider.universal.api.LyricsSourceAggregator.SourceId
import io.github.andrealtb.coloroslyrics.provider.universal.api.SearchCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/** Only the enabled sources participate, in the user's configured priority order. */
internal fun manualSearchSources(enabled: List<String>): List<SourceId> =
    enabled.mapNotNull { name -> SourceId.entries.firstOrNull { it.name == name } }.distinct()

/** Call on the UI dispatcher: each search may switch to IO, then reports partial results here. */
internal suspend fun collectManualCandidates(
    sources: List<SourceId>,
    search: suspend (SourceId) -> List<SearchCandidate>,
    onUpdate: (List<SearchCandidate>) -> Unit,
) = supervisorScope {
    val ordered = sources.distinct()
    val partial = mutableMapOf<SourceId, List<SearchCandidate>>()
    ordered.forEach { source ->
        launch {
            val rows = try { search(source) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
            partial[source] = rows
            // A prolific first source must not hide all other sources behind pagination.
            val results = ordered.map { partial[it].orEmpty() }
            val largest = results.maxOfOrNull { it.size } ?: 0
            onUpdate((0 until largest).flatMap { index -> results.mapNotNull { it.getOrNull(index) } }
                .distinctBy { it.source to it.id })
        }
    }
}
