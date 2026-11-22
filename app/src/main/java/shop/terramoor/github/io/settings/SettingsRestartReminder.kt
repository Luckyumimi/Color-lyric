package shop.terramoor.github.io.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local UI state, separate from SystemUI preferences and backups. */
internal class SettingsRestartReminder(private val prefs: SharedPreferences, private val bootCount: Int) {
    private val savedBootCount = prefs.getInt(BOOT_COUNT, -1)
    private var pending = prefs.getBoolean(PENDING, false) &&
        (bootCount < 0 || savedBootCount < 0 || savedBootCount == bootCount)
    // Older versions persisted only the pending flag; don't guess their original settings.
    private var baseline = if (pending) prefs.getString(BASELINE, null)?.let { snapshot ->
        runCatching { CapsuleBackup.decode(snapshot.byteInputStream()) }.getOrNull()
    } else null
    private var tuning = false
    private val mutableVisible = MutableStateFlow(pending)
    val visible = mutableVisible.asStateFlow()
    var revision = 0L
        private set

    fun startTuning() { tuning = true }

    fun finishTuning() {
        tuning = false
        mutableVisible.value = pending
    }

    fun settingsCommitted(before: CapsuleSettings, after: CapsuleSettings) {
        if (before == after) return
        revision++
        if (!pending) {
            pending = true
            baseline = before
            prefs.edit().putBoolean(PENDING, true).putInt(BOOT_COUNT, bootCount)
                .putString(BASELINE, CapsuleBackup.encode(before)).apply()
        }
        reconcile(after)
    }

    fun reconcile(confirmed: CapsuleSettings) {
        if (pending && confirmed == baseline) clearPending()
        if (!tuning) mutableVisible.value = pending
    }

    fun systemUiRestarted(appliedRevision: Long) {
        if (!pending || appliedRevision != revision) return
        clearPending()
        mutableVisible.value = false
    }

    private fun clearPending() {
        pending = false
        baseline = null
        prefs.edit().remove(PENDING).remove(BOOT_COUNT).remove(BASELINE).apply()
    }

    private companion object {
        const val PENDING = "pending"
        const val BOOT_COUNT = "boot_count"
        const val BASELINE = "baseline"
    }
}
