package io.github.andrealtb.coloroslyrics.provider.universal

import android.content.Context

/**
 * Recent-track rows on the home screen. system_server keeps the authoritative rows, because it
 * observes every track and fetch result while the app may be frozen or not running, and sends
 * them with each snapshot; the app merges them into its stored list so rows also survive a
 * reboot. Source and status are raw snapshot keys, localized only when displayed.
 */
internal object UniversalLyricHistory {
    private const val PREFS_NAME = "universal_ui_history"
    private const val KEY_ORDERED = "ordered"
    const val MAX_ROWS = 5

    /** Snapshot keys that carry system_server's rows, newest first. */
    val snapshotKeys: List<String> = List(MAX_ROWS) { "history$it" }

    data class Entry(
        val title: String,
        val artist: String,
        val source: String,
        val status: String,
        val durationMs: Long? = null,
    )

    fun record(context: Context, snapshot: Map<String, String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val previous = prefs.getString(KEY_ORDERED, "").orEmpty()
        val updated = update(previous, snapshot) ?: return
        if (updated != previous) prefs.edit().putString(KEY_ORDERED, updated).apply()
    }

    fun load(context: Context): List<Entry> =
        parse(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_ORDERED, "").orEmpty())

    /**
     * Returns the stored rows merged with the snapshot: system_server's rows first, then the
     * current track. Null when the snapshot carries neither.
     */
    internal fun update(previous: String, snapshot: Map<String, String>): String? {
        val serverRows = snapshotKeys.mapNotNull { key ->
            snapshot[key]?.takeIf { it.substringBefore('\t').isNotBlank() }
        }
        var rows: String? = null
        if (serverRows.isNotEmpty()) {
            // A stored row still fetching that system_server no longer tracks can never finish.
            var merged = previous.lines()
                .filter { it.isNotBlank() && (serverRows.any { current -> sameTrack(it, current) } || !isFetching(it)) }
                .joinToString("\n")
            for (row in serverRows.asReversed()) merged = moveToTop(merged, row)
            rows = merged
        }
        val current = row(
            field(snapshot, "title", "(missing)"),
            field(snapshot, "artist", "(missing)"),
            snapshot["lyricSource"],
            snapshot["lyricStatus"],
            snapshot["durationMs"]?.toLongOrNull(),
        ) ?: return rows
        return moveToTop(rows ?: previous, current)
    }

    /** One stored row, or null without a title. Fields never contain separators. */
    internal fun row(
        title: String?, artist: String?, source: String?, status: String?, durationMs: Long? = null,
    ): String? {
        val cleanTitle = clean(title)
        if (cleanTitle.isEmpty()) return null
        val fields = listOf(
            cleanTitle,
            clean(artist),
            clean(source),
            clean(status).ifEmpty { "fetching" }
        )
        return (if (durationMs != null && durationMs > 0L) fields + durationMs.toString() else fields).joinToString("\t")
    }

    /** Puts [row] first, replacing the same song without conflating different artists. */
    internal fun moveToTop(previous: String, row: String): String {
        val rows = previous.lines().filter { it.isNotBlank() }
        val replacement = keepDuration(row, rows.firstOrNull { sameTrack(it, row) })
        return (listOf(replacement) + rows.filterNot { sameTrack(it, row) }).take(MAX_ROWS).joinToString("\n")
    }

    /** Replaces the row of the same song where it stands; a new song goes first. */
    internal fun replaceInPlace(previous: String, row: String): String {
        val rows = previous.lines().filter { it.isNotBlank() }
        val index = rows.indexOfFirst { sameTrack(it, row) }
        if (index < 0) return moveToTop(previous, row)
        return rows.toMutableList().apply { set(index, keepDuration(row, rows[index])) }.joinToString("\n")
    }

    /** `historyN=row` snapshot lines for system_server's rows. */
    internal fun snapshotLines(rows: String): String = rows.lines()
        .filter { it.isNotBlank() }
        .take(MAX_ROWS)
        .mapIndexed { index, row -> "${snapshotKeys[index]}=$row\n" }
        .joinToString("")

    internal fun parse(raw: String): List<Entry> = raw.lines().filter { it.isNotBlank() }.map { line ->
        val parts = line.split('\t')
        if (parts.size >= 4) {
            Entry(parts[0], parts[1], parts[2], parts[3], parts.getOrNull(4)?.toLongOrNull())
        } else {
            Entry(
                title = parts.getOrElse(0) { "" },
                artist = "",
                source = parts.getOrElse(1) { "—" },
                status = parts.getOrElse(2) { "" }
            )
        }
    }

    /** Includes labels that older builds stored already localized. */
    private val fetchingStatuses = setOf("pending", "fetching", "正在获取", "Fetching")

    private fun isFetching(row: String): Boolean = parse(row).firstOrNull()?.status in fetchingStatuses

    private fun keepDuration(row: String, previous: String?): String {
        if (row.count { it == '\t' } >= 4 || previous == null) return row
        val durationMs = previous.split('\t').getOrNull(4)?.toLongOrNull() ?: return row
        return "$row\t$durationMs"
    }

    private fun sameTrack(first: String, second: String): Boolean {
        fun parts(row: String): Pair<String, String> {
            val fields = row.split('\t')
            val title = fields.firstOrNull().orEmpty().trim().lowercase()
            val artist = if (fields.size >= 4) fields[1].trim().lowercase() else ""
            return title to artist
        }
        val left = parts(first)
        val right = parts(second)
        return left.first.isNotEmpty() && left.first == right.first &&
            (left.second == right.second || left.second.isEmpty() || right.second.isEmpty())
    }

    private fun field(snapshot: Map<String, String>, key: String, missing: String): String =
        snapshot[key].orEmpty().takeUnless { it == missing }.orEmpty()

    private fun clean(value: String?): String =
        value.orEmpty().replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
}
