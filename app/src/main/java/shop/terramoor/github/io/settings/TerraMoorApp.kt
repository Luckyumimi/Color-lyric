package shop.terramoor.github.io.settings

import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import io.github.libxposed.service.TrustedXposedProvider
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import shop.terramoor.github.io.systemui.TargetFirmware

class TerraMoorApp : Application(), XposedServiceHelper.OnServiceListener {
    internal enum class Connection { CHECKING, DISCONNECTED, NO_SCOPE, READY, FAILED, UNSUPPORTED }

    internal data class State(
        val connection: Connection = Connection.CHECKING,
        val settings: CapsuleSettings = CapsuleSettings(),
    )

    private class Write(
        val edit: SharedPreferences.Editor.() -> Unit,
        val completion: CompletableDeferred<Boolean> = CompletableDeferred(),
    )

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(State())
    internal val state = mutableState.asStateFlow()
    private var service: XposedService? = null
    private var supported = false
    private var preferences: SharedPreferences? = null
    private var refreshGeneration = 0
    private var confirmed = CapsuleSettings()
    private val pendingTuning = mutableMapOf<CapsuleTuning, Int>()
    private val writes = ArrayDeque<Write>()
    private var writerRunning = false
    private val idle = MutableStateFlow(true)
    private val flushPendingTuning = Runnable { flushTuning() }

    override fun onCreate() {
        super.onCreate()
        supported = TargetFirmware.supported(this)
        if (!supported) {
            mutableState.value = State(Connection.UNSUPPORTED)
            return
        }
        XposedServiceHelper.registerListener(this)
        main.postDelayed({ if (service == null) refresh() }, 1200)
    }

    override fun onServiceBind(service: XposedService) {
        main.post {
            if (this.service !== service) {
                discardWrites()
                mutableState.value = State(Connection.CHECKING, confirmed)
            }
            this.service = service
            refresh()
        }
    }

    override fun onServiceDied(service: XposedService) {
        main.post {
            if (this.service === service) {
                this.service = null
                discardWrites()
                refresh()
            }
        }
    }

    internal fun refresh() {
        if (!supported) return
        val generation = ++refreshGeneration
        val bound = service
        if (bound == null) {
            mutableState.value = State(Connection.DISCONNECTED)
            return
        }
        val existing = preferences
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val inScope = TargetFirmware.SYSTEM_UI in bound.scope
                    val prefs = existing ?: TrustedXposedProvider.readPreferences(bound, CapsuleSettings.GROUP)
                    Triple(inScope, prefs, if (existing == null) CapsuleSettings.read(prefs) else null)
                }
            }
            if (generation != refreshGeneration || service !== bound || preferences !== existing) return@launch
            val (inScope, prefs, initial) = result.getOrElse {
                discardWrites()
                mutableState.value = State(Connection.FAILED, confirmed)
                return@launch
            }
            if (initial != null) confirmed = initial
            if (!inScope) discardWrites()
            preferences = prefs
            mutableState.value = if (!inScope) State(Connection.NO_SCOPE, confirmed) else
                State(Connection.READY, initial ?: state.value.settings)
        }
    }

    internal fun set(key: String, checked: Boolean) {
        val current = state.value.settings
        val next = when (key) {
            CapsuleSettings.ENABLED -> current.copy(enabled = checked)
            CapsuleSettings.TRANSLATION -> current.copy(translation = checked)
            CapsuleSettings.ARTWORK -> current.copy(artwork = checked)
            CapsuleSettings.CAMERA_FADE -> current.copy(cameraFade = checked)
            else -> return
        }
        save(next) { putBoolean(key, checked) }
    }

    internal fun previewTuning(option: CapsuleTuning, value: Int) {
        if (state.value.connection != Connection.READY) return
        val next = value.coerceIn(option.range)
        pendingTuning[option] = next
        idle.value = false
        showSettings(state.value.settings.withTuning(mapOf(option to next)))
        if (!main.hasCallbacks(flushPendingTuning)) main.postDelayed(flushPendingTuning, TUNING_INTERVAL_MS)
    }

    internal fun flushTuning() {
        main.removeCallbacks(flushPendingTuning)
        if (writerRunning) return
        enqueueTuning()
        pumpWrites()
    }

    private fun enqueueTuning() {
        if (pendingTuning.isEmpty()) return
        val changes = pendingTuning.toMap()
        pendingTuning.clear()
        writes.addLast(Write({ changes.forEach { (option, value) -> putInt(option.key, value) } }))
    }

    internal fun reset(option: CapsuleTuning) {
        pendingTuning.remove(option)
        save(state.value.settings.withTuning(mapOf(option to option.default))) { remove(option.key) }
    }

    internal fun setLyricAlignment(alignment: LyricAlignment) {
        save(state.value.settings.copy(lyricAlignment = alignment)) { putString(CapsuleSettings.LYRIC_ALIGNMENT, alignment.name) }
    }

    internal fun setMotion(motion: LineMotion) {
        save(state.value.settings.copy(motion = motion)) { putString(CapsuleSettings.MOTION, motion.name) }
    }

    internal fun setNoLyrics(mode: NoLyricsMode) {
        save(state.value.settings.copy(noLyricsMode = mode)) { putString(CapsuleSettings.NO_LYRICS, mode.name) }
    }

    internal suspend fun snapshotForBackup(): CapsuleSettings? {
        flushTuning()
        idle.first { it }
        return confirmed.takeIf { state.value.connection == Connection.READY }
    }

    internal suspend fun restore(settings: CapsuleSettings): Boolean {
        pendingTuning.clear()
        return save(settings) { writeSettings(settings) }?.await() == true
    }

    internal fun resetTuning() {
        pendingTuning.clear()
        val defaults = CapsuleSettings()
        val current = state.value.settings
        save(defaults.copy(enabled = current.enabled, translation = current.translation, artwork = current.artwork)) {
            CapsuleTuning.entries.forEach { remove(it.key) }
            remove(CapsuleSettings.LYRIC_ALIGNMENT)
            remove(CapsuleSettings.CAMERA_FADE)
            remove(CapsuleSettings.MOTION)
            remove(CapsuleSettings.NO_LYRICS)
        }
    }

    private fun save(next: CapsuleSettings, edit: SharedPreferences.Editor.() -> Unit): CompletableDeferred<Boolean>? {
        if (state.value.connection != Connection.READY) return null
        main.removeCallbacks(flushPendingTuning)
        enqueueTuning()
        idle.value = false
        showSettings(next)
        val write = Write(edit)
        writes.addLast(write)
        pumpWrites()
        return write.completion
    }

    private fun pumpWrites() {
        if (writerRunning) return
        val prefs = preferences ?: return
        val write = writes.removeFirstOrNull() ?: run {
            idle.value = pendingTuning.isEmpty()
            return
        }
        writerRunning = true
        scope.launch {
            val stored = withContext(Dispatchers.IO) {
                runCatching {
                    if (prefs.edit().apply(write.edit).commit()) CapsuleSettings.read(prefs) else null
                }.getOrNull()
            }
            writerRunning = false
            val stillConnected = preferences === prefs && state.value.connection == Connection.READY
            if (stillConnected && stored != null) {
                confirmed = stored
                // A newer optimistic value may already be visible. Keep it until it gets written.
                if (writes.isEmpty() && pendingTuning.isEmpty()) showSettings(stored)
            } else if (stillConnected) {
                discardWrites()
                mutableState.value = State(Connection.FAILED, confirmed)
            }
            write.completion.complete(stillConnected && stored != null)
            if (!main.hasCallbacks(flushPendingTuning)) enqueueTuning()
            pumpWrites()
        }
    }

    private fun showSettings(settings: CapsuleSettings) {
        mutableState.value = state.value.copy(settings = settings)
    }

    private fun discardWrites() {
        main.removeCallbacks(flushPendingTuning)
        preferences = null
        pendingTuning.clear()
        writes.forEach { it.completion.complete(false) }
        writes.clear()
        idle.value = true
    }

    private companion object {
        const val TUNING_INTERVAL_MS = 55L
    }
}
