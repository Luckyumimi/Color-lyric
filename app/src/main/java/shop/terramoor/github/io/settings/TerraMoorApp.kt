package shop.terramoor.github.io.settings

import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import shop.terramoor.github.io.diagnostics.DiagnosticLog
import shop.terramoor.github.io.diagnostics.DiagnosticStore
import shop.terramoor.github.io.systemui.NativeContract

class TerraMoorApp : Application(), XposedServiceHelper.OnServiceListener {
    internal enum class Connection { CHECKING, DISCONNECTED, NO_SCOPE, READY, FAILED }

    internal data class State(
        val connection: Connection = Connection.CHECKING,
        val settings: CapsuleSettings = CapsuleSettings(),
    )

    private class Write(
        val edit: SharedPreferences.Editor.() -> Unit,
        val completion: CompletableDeferred<Boolean> = CompletableDeferred(),
    )

    internal val diagnostics = DiagnosticLog("App") { DiagnosticStore.append(this, it) }
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(State())
    internal val state = mutableState.asStateFlow()
    internal val restartReminder by lazy {
        SettingsRestartReminder(
            getSharedPreferences("systemui_restart_reminder", MODE_PRIVATE),
            Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1),
        )
    }
    private var service: XposedService? = null
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
        diagnostics.note("App process started, pid=${android.os.Process.myPid()}")
        XposedServiceHelper.registerListener(this)
        main.postDelayed({ if (service == null) refresh() }, 1200)
    }

    override fun onServiceBind(service: XposedService) {
        main.post {
            if (this.service !== service) {
                discardWrites()
                updateState(State(Connection.CHECKING, confirmed))
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

    internal fun diagnosticService(): XposedService? = service

    internal fun refresh() {
        val generation = ++refreshGeneration
        val bound = service
        if (bound == null) {
            updateState(State(Connection.DISCONNECTED))
            return
        }
        val existing = preferences
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val inScope = NativeContract.SYSTEM_UI in bound.scope
                    val prefs = existing ?: TrustedXposedProvider.readPreferences(bound, CapsuleSettings.GROUP)
                    Triple(inScope, prefs, if (existing == null) CapsuleSettings.read(prefs) else null)
                }
            }
            if (generation != refreshGeneration || service !== bound || preferences !== existing) return@launch
            val (inScope, prefs, initial) = result.getOrElse {
                diagnostics.failure("Read LSPosed scope and preferences", it)
                discardWrites()
                updateState(State(Connection.FAILED, confirmed))
                return@launch
            }
            attachPreferences(prefs, inScope, initial)
        }
    }

    internal fun attachPreferences(prefs: SharedPreferences, inScope: Boolean, initial: CapsuleSettings?) {
        if (initial != null) {
            confirmed = initial
            if (inScope) restartReminder.reconcile(initial)
        }
        if (!inScope) discardWrites()
        preferences = prefs
        updateState(if (!inScope) State(Connection.NO_SCOPE, confirmed) else
            State(Connection.READY, initial ?: state.value.settings))
    }

    internal fun set(key: String, checked: Boolean) {
        val current = state.value.settings
        val next = when (key) {
            CapsuleSettings.ENABLED -> current.copy(enabled = checked)
            CapsuleSettings.DOUBLE_LINE -> current.copy(doubleLine = checked)
            CapsuleSettings.ARTWORK -> current.copy(artwork = checked)
            CapsuleSettings.LYRIC_TINT_ENABLED -> current.copy(lyricTintEnabled = checked)
            CapsuleSettings.SHOW_COVER -> current.copy(showCover = checked)
            CapsuleSettings.SHOW_WAVEFORM -> current.copy(showWaveform = checked)
            CapsuleSettings.CAMERA_FADE -> current.copy(cameraFade = checked)
            CapsuleSettings.FALLBACK_SINGLE_LINE_EXPAND -> current.copy(fallbackSingleLineExpand = checked)
            else -> return
        }
        save(next) { putBoolean(key, checked) }
    }

    internal fun previewTuning(option: CapsuleTuning, value: Int) {
        if (state.value.connection != Connection.READY) return
        val next = option.normalize(value)
        if (state.value.settings[option] == next) return
        restartReminder.startTuning()
        pendingTuning[option] = next
        idle.value = false
        showSettings(state.value.settings.withTuning(mapOf(option to next)))
        if (!main.hasCallbacks(flushPendingTuning)) main.postDelayed(flushPendingTuning, TUNING_INTERVAL_MS)
    }

    internal fun finishTuning() {
        restartReminder.finishTuning()
        flushTuning()
    }

    private fun flushTuning() {
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
        save(state.value.settings.withTuning(mapOf(option to option.default))) {
            remove(option.key)
            if (option == CapsuleTuning.TINT_STRENGTH) CapsuleSettings.retiredTintKeys.forEach { remove(it) }
        }
    }

    internal fun setLyricAlignment(alignment: LyricAlignment) {
        save(state.value.settings.copy(lyricAlignment = alignment)) { putString(CapsuleSettings.LYRIC_ALIGNMENT, alignment.name) }
    }

    internal fun setLyricTintMode(mode: LyricTintMode) {
        save(state.value.settings.copy(lyricTintMode = mode)) { putString(CapsuleSettings.LYRIC_TINT_MODE, mode.name) }
    }

    internal fun setSecondRowContent(content: SecondRowContent) {
        save(state.value.settings.copy(secondRowContent = content)) {
            putString(CapsuleSettings.SECOND_ROW_CONTENT, content.name)
        }
    }

    internal fun setSingleLineSwitchMode(mode: LyricSwitchMode) {
        save(state.value.settings.copy(singleLineSwitchMode = mode)) {
            putString(CapsuleSettings.SINGLE_LINE_SWITCH_MODE, mode.name)
        }
    }

    internal fun setDoubleLineSwitchMode(mode: LyricSwitchMode) {
        save(state.value.settings.copy(doubleLineSwitchMode = mode)) {
            putString(CapsuleSettings.DOUBLE_LINE_SWITCH_MODE, mode.name)
        }
    }

    internal fun setMissingTranslationMode(mode: MissingTranslationMode) {
        save(state.value.settings.copy(missingTranslationMode = mode)) {
            putString(CapsuleSettings.MISSING_TRANSLATION_MODE, mode.name)
        }
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

    internal suspend fun revisionBeforeRestart(): Long {
        finishTuning()
        idle.first { it }
        return restartReminder.revision
    }

    internal fun resetTuning() {
        pendingTuning.clear()
        val defaults = CapsuleSettings()
        val current = state.value.settings
        val reset = defaults.copy(enabled = current.enabled, doubleLine = current.doubleLine, artwork = current.artwork)
        save(reset) {
            CapsuleTuning.entries.forEach { remove(it.key) }
            remove(CapsuleSettings.LYRIC_ALIGNMENT)
            remove(CapsuleSettings.LYRIC_TINT_ENABLED)
            remove(CapsuleSettings.LYRIC_TINT_MODE)
            CapsuleSettings.retiredTintKeys.forEach { remove(it) }
            remove(CapsuleSettings.CAMERA_FADE)
            remove(CapsuleSettings.MOTION)
            remove(CapsuleSettings.NO_LYRICS)
            writeLyricSettings(reset)
            remove(CapsuleSettings.SHOW_COVER)
            remove(CapsuleSettings.SHOW_WAVEFORM)
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
        val before = confirmed
        scope.launch {
            val stored = withContext(Dispatchers.IO) {
                runCatching {
                    val edit = prefs.edit().apply {
                        // Persist defaults/migration in the same transaction as the first edit.
                        if (!prefs.contains(CapsuleSettings.DOUBLE_LINE_SWITCH_MODE)) writeLyricSettings(before)
                        write.edit(this)
                    }
                    if (edit.commit()) CapsuleSettings.read(prefs) else {
                        diagnostics.note("LSPosed preference commit rejected")
                        null
                    }
                }.onFailure { diagnostics.failure("Write LSPosed preferences", it) }.getOrNull()
            }
            writerRunning = false
            val stillConnected = preferences === prefs && state.value.connection == Connection.READY
            if (stillConnected && stored != null) {
                confirmed = stored
                restartReminder.settingsCommitted(before, stored)
                diagnostics.note("Remote settings committed, $stored")
                // A newer optimistic value may already be visible. Keep it until it gets written.
                if (writes.isEmpty() && pendingTuning.isEmpty()) showSettings(stored)
            } else if (stillConnected) {
                discardWrites()
                updateState(State(Connection.FAILED, confirmed))
            }
            write.completion.complete(stillConnected && stored != null)
            if (!main.hasCallbacks(flushPendingTuning)) enqueueTuning()
            pumpWrites()
        }
    }

    private fun updateState(next: State) {
        if (state.value.connection != next.connection) diagnostics.note("LSPosed connection: ${next.connection}")
        mutableState.value = next
    }

    private fun showSettings(settings: CapsuleSettings) {
        mutableState.value = state.value.copy(settings = settings)
    }

    private fun discardWrites() {
        main.removeCallbacks(flushPendingTuning)
        preferences = null
        pendingTuning.clear()
        restartReminder.finishTuning()
        writes.forEach { it.completion.complete(false) }
        writes.clear()
        idle.value = true
    }

    private companion object {
        const val TUNING_INTERVAL_MS = 55L
    }
}
