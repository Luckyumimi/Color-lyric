package shop.terramoor.github.io.systemui

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import io.github.libxposed.api.XposedInterface.HookHandle
import shop.terramoor.github.io.lyrics.LyricPayload
import shop.terramoor.github.io.lyrics.LyricFrame
import shop.terramoor.github.io.lyrics.LyricLine
import shop.terramoor.github.io.lyrics.PlaybackClock
import shop.terramoor.github.io.lyrics.Timeline
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.CapsuleTuning
import shop.terramoor.github.io.settings.NoLyricsMode
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/** Owns a single capsule and session, with all UI and callback state confined to the main thread. */
internal class CapsuleRuntime(
    private val host: NativeContract.Host,
    private val remotePreferences: () -> SharedPreferences,
    private val installPlugin: (NativeContract.Plugin, CapsuleRuntime) -> List<HookHandle>,
) {
    private val main = Handler(Looper.getMainLooper())
    private var firmwareOk: Boolean? = null
    private var preferences: SharedPreferences? = null
    private var preferenceAttempts = 0
    private var settings = CapsuleSettings(enabled = false)
    private var hostView: ViewGroup? = null
    private var observer: ViewTreeObserver? = null
    private var root: ViewGroup? = null
    private var plugin: NativeContract.Plugin? = null
    private var pluginHooks = emptyList<HookHandle>()
    private var rejectedLoader = WeakReference<ClassLoader>(null)
    private var mediaController = WeakReference<Any>(null)
    private var media: NativeContract.Media? = null
    private var mediaMissingSince: Long? = null
    private var destroyedToken: MediaSession.Token? = null
    private var session: Session? = null
    private var labels: CapsuleLabels? = null
    private var status: String? = null

    private val refresh = Runnable { protect("Capsule update", ::refreshTarget) }
    private val tick = Runnable { protect("Lyric rendering", ::render) }
    private val connect = Runnable { protect("Preferences reconnect", ::loadPreferences) }
    private val retryMedia = Runnable { protect("Media handoff") { readMedia(); postRefresh() } }
    private val globalLayout = ViewTreeObserver.OnGlobalLayoutListener { postRefresh() }
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        if (main.hasCallbacks(refresh)) {
            main.removeCallbacks(refresh)
            protect("Capsule update", ::refreshTarget)
        }
        true
    }
    private val applyPreferences = Runnable { protect("Preferences", ::applySettings) }
    private val onPreferences = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        main.removeCallbacks(applyPreferences)
        main.post(applyPreferences)
    }

    private fun applySettings() {
        val previous = settings
        settings = preferences?.let(CapsuleSettings::read) ?: CapsuleSettings(enabled = false)
        if (settings == previous) return
        if (!settings.enabled) {
            stopObservation()
            setStatus("Lyrics disabled")
            return
        }
        if (!previous.enabled) {
            startObservation()
            readMedia()
        }
        postRefresh()
        hostView?.invalidate()
    }

    fun hostChanged(instance: Any) = onMain("Capsule host") {
        val view = host.view(instance)
        if (view.display?.displayId != Display.DEFAULT_DISPLAY || !checkFirmware(view)) return@onMain
        if (hostView !== view) {
            stopObservation(releaseMedia = false)
            hostView = view
        }
        loadPreferences()
        startObservation()
        postRefresh()
    }

    fun hostDetached(instance: Any) = onMain("Capsule detach") {
        if (hostView === host.view(instance)) {
            stopObservation(releaseMedia = false)
            hostView = null
            main.removeCallbacks(connect)
        }
    }

    fun pluginRemoved(instance: Any) = onMain("Capsule plugin detach") {
        if (hostView === host.view(instance)) {
            detachPlugin()
            postRefresh()
        }
    }

    fun mediaChanged(instance: Any, sent: Boolean) = onMain("Media notification") {
        if (sent || mediaController.get() === instance || mediaController.get() == null) {
            mediaController = WeakReference(instance)
            if (settings.enabled) {
                readMedia()
                postRefresh()
            }
        }
    }

    fun mediaReleased(instance: Any) = onMain("Media release") {
        if (mediaController.get() === instance) {
            mediaController.clear()
            clearMediaRetry()
            media = null
            destroyedToken = null
            releaseSession()
        }
    }

    fun containerLaidOut(container: Any) {
        val currentRoot = root ?: return
        if (settings.enabled && plugin?.belongs(container, currentRoot) == true) postRefresh()
    }

    fun draw(instance: Any, canvas: Canvas) {
        val binding = labels ?: return
        val contract = plugin ?: return
        if (binding.target.capsule !== instance || !contract.hasOwner(binding.target.capsule, media?.key ?: return)) return
        binding.draw(canvas, contract.drawingWidth(binding.target.capsule))
    }

    fun stabilizeMediaContentFrame(callback: Any): (() -> Unit)? {
        val contract = plugin ?: return null
        val binding = labels ?: return null
        val active = binding.target.capsule
        val key = media?.key ?: return null
        if (!active.isShown || active.width <= 0 || !contract.hasOwner(active, key)) return null
        val view = contract.contentFrameView(callback)
        // Scope the fix to the page containing both native media controls. The system's
        // show/hide and expanded-card transitions use other paths and stay native.
        fun isWithinPage(child: View): Boolean {
            var parent = child.parent
            while (parent != null && parent !== active) {
                if (parent === view) return true
                parent = parent.parent
            }
            return false
        }
        if (!isWithinPage(binding.target.artwork) || !isWithinPage(binding.target.waveform)) return null
        return contract.stabilizeContentFrame(callback, view)
    }

    fun contentChanged(container: Any) {
        val currentRoot = root ?: return
        if (!settings.enabled || plugin?.belongs(container, currentRoot) != true) return
        // Add the sizing child before the OEM measures the new item and captures animation bounds.
        main.removeCallbacks(refresh)
        protect("Capsule content", ::refreshTarget)
    }

    private fun checkFirmware(view: ViewGroup): Boolean = firmwareOk ?: TargetFirmware.supported(view.context).also {
        firmwareOk = it
        if (!it) setStatus("Unsupported SystemUI or plugin version")
    }

    private fun loadPreferences() {
        if (preferences != null || hostView == null) return
        main.removeCallbacks(connect)
        try {
            val prefs = remotePreferences()
            prefs.registerOnSharedPreferenceChangeListener(onPreferences)
            preferences = prefs
            applySettings()
        } catch (failure: Throwable) {
            if (!isExpectedFailure(failure)) throw failure
            preferences?.unregisterOnSharedPreferenceChangeListener(onPreferences)
            preferences = null
            stopObservation()
            settings = CapsuleSettings(enabled = false)
            setStatus("Remote preferences unavailable")
            if (++preferenceAttempts <= 3) main.postDelayed(connect, 1200)
            return
        }
        if (!settings.enabled) setStatus("Lyrics disabled")
    }

    private fun startObservation() {
        val view = hostView ?: return
        if (!settings.enabled || observer?.isAlive == true) return
        observer = view.viewTreeObserver.takeIf { it.isAlive }?.also {
            it.addOnGlobalLayoutListener(globalLayout)
            it.addOnPreDrawListener(preDraw)
        }
    }

    private fun stopObservation(releaseMedia: Boolean = true) {
        main.removeCallbacks(refresh)
        if (releaseMedia) clearMediaRetry()
        observer?.takeIf { it.isAlive }?.let {
            it.removeOnPreDrawListener(preDraw)
            it.removeOnGlobalLayoutListener(globalLayout)
        }
        observer = null
        if (releaseMedia) {
            releaseSession()
            media = null
        }
        detachPlugin()
    }

    private fun detachPlugin() {
        clearLabels()
        pluginHooks.forEach { it.unhook() }
        pluginHooks = emptyList()
        plugin = null
        root = null
    }

    private fun readMedia() {
        val candidate = mediaController.get()?.let(host::activeMedia)
        val previous = media
        if (candidate == null && previous != null && labels != null) {
            val now = SystemClock.uptimeMillis()
            val missingSince = mediaMissingSince ?: now.also { mediaMissingSince = it }
            val remaining = MEDIA_HANDOFF_MS - (now - missingSince)
            if (remaining > 0L) {
                if (!main.hasCallbacks(retryMedia)) main.postDelayed(retryMedia, remaining)
                return
            }
        }
        clearMediaRetry()
        if (candidate?.token != previous?.token || candidate?.owner != previous?.owner) {
            val reuseLabels = candidate != null && candidate.key == previous?.key && candidate.owner == previous.owner
            releaseSession(keepLabels = reuseLabels)
            if (candidate != null && candidate.token != destroyedToken) destroyedToken = null
        }
        media = candidate
    }

    private fun clearMediaRetry() {
        main.removeCallbacks(retryMedia)
        mediaMissingSince = null
    }

    private fun postRefresh() {
        if (settings.enabled && !main.hasCallbacks(refresh)) main.post(refresh)
    }

    private fun refreshTarget() {
        if (!settings.enabled) return
        val currentRoot = hostView?.takeIf { it.isAttachedToWindow }?.let(NativeContract::root)
        if (root !== currentRoot) {
            detachPlugin()
            root = currentRoot
            val loader = currentRoot?.javaClass?.classLoader
            if (loader != null && rejectedLoader.get() !== loader) {
                try {
                    val contract = NativeContract.Plugin(loader)
                    pluginHooks = installPlugin(contract, this)
                    plugin = contract
                    Log.i(TAG, "Capsule plugin contract ready")
                } catch (failure: Throwable) {
                    if (!isExpectedFailure(failure)) throw failure
                    rejectedLoader = WeakReference(loader)
                    reportFailure("Plugin contract", failure)
                }
            }
        }
        if (currentRoot == null || plugin == null) {
            setStatus("Waiting for native capsule plugin")
            return
        }
        val candidate = media?.takeUnless { it.token == destroyedToken }
        val target = candidate?.let { plugin?.findTarget(currentRoot, it.key, labels?.target) }
        if (candidate == null || target == null) {
            clearLabels()
            setStatus(if (candidate == null) "Waiting for native media notification" else "Native media capsule not visible")
            return
        }
        if (session?.token != candidate.token) {
            val controller = MediaController(currentRoot.context, candidate.token)
            if (controller.packageName != candidate.owner) {
                releaseSession()
                setStatus("Native media and session owner differ")
                return
            }
            val watch = Session(candidate.token, controller)
            session = watch
            controller.registerCallback(watch, main)
            watch.metadataChanged(controller.metadata)
            watch.playbackChanged(controller.playbackState)
        }
        if (labels?.target != target) {
            clearLabels()
            labels = CapsuleLabels(target)
            Log.i(TAG, "Verified native media capsule label slots")
        }
        render()
    }

    private fun render() {
        main.removeCallbacks(tick)
        val binding = labels ?: return
        val currentRoot = root ?: return
        val candidate = media ?: return
        // Recheck ownership on every lyric boundary in case the OEM reused the view.
        if (plugin?.findTarget(currentRoot, candidate.key, binding.target) != binding.target) {
            clearLabels()
            return
        }
        val watch = session ?: return
        val background = if (settings.artwork) watch.artwork(candidate.artwork, settings[CapsuleTuning.BLUR]) else null
        val timeline = watch.timeline
        if (timeline == null) {
            val trackInfo = if (settings.noLyricsMode == NoLyricsMode.TRACK) watch.trackInfo() else null
            binding.render(trackInfo, settings, background)
            setStatus(if (trackInfo != null) "Showing track info without usable lyric timing"
                else "Waiting for timed lyrics from the active session")
            return
        }
        val clock = watch.playbackClock()
        val position = clock.position(SystemClock.elapsedRealtime())
        val index = timeline.displayIndexAt(position)
        val line = timeline.lines.getOrNull(index)?.takeIf { it.text.isNotBlank() }
        val next = (index + 1).takeIf { it in timeline.lines.indices }?.let(timeline::displayStart)
        if (line != null) {
            val prior = timeline.lines.getOrNull(index - 1)?.takeIf { it.text.isNotBlank() }
            val frame = LyricFrame(line, timeline.scrollEnd(index, watch.duration), prior,
                displayStartMs = timeline.displayStart(index))
            binding.render(frame, settings, background, clock)
            setStatus("Rendering lyrics in native media capsule")
        } else {
            val introInfo = if (index < 0 && settings.noLyricsMode == NoLyricsMode.TRACK) watch.trackInfo() else null
            binding.render(introInfo, settings, background)
            setStatus("Waiting for next lyric boundary")
        }
        if (next != null && binding.target.capsule.isShown) {
            clock.delayTo(position, next)?.let { main.postDelayed(tick, it) }
        }
    }

    private fun clearLabels() {
        main.removeCallbacks(tick)
        val binding = labels
        labels = null
        binding?.close()
    }

    private fun releaseSession(keepLabels: Boolean = false) {
        main.removeCallbacks(tick)
        val current = session
        session = null
        current?.cancelMetadataExpiry()
        if (!keepLabels) clearLabels()
        current?.controller?.unregisterCallback(current)
    }

    private fun onMain(step: String, block: () -> Unit) {
        if (Looper.myLooper() == main.looper) protect(step, block) else main.post { protect(step, block) }
    }

    private fun protect(step: String, block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            if (!isExpectedFailure(failure)) throw failure
            try { releaseSession() } catch (cleanup: Exception) { reportFailure("Capsule cleanup", cleanup) }
            setStatus("$step unavailable (${failure.javaClass.simpleName})")
        }
    }

    private fun setStatus(message: String) {
        if (status == message) return
        status = message
        Log.i(TAG, message)
    }

    private inner class Session(val token: MediaSession.Token, val controller: MediaController) : MediaController.Callback() {
        private val playback = CapsulePlayback()
        var timeline: Timeline? = null
            private set
        var duration = 0L
            private set
        private var metadata: MediaMetadata? = null
        private var track: Track? = null
        private var raw: String? = null
        private var previousTrackPayload: String? = null
        private var lastTrackPayload: String? = null
        private var metadataArtwork: Bitmap? = null
        private var bitmap: Bitmap? = null
        private var icon: Icon? = null
        private var blurred: Bitmap? = null
        private var blurStrength = -1
        private var previousArtwork: Bitmap? = null
        private var previousArtworkIcon: Icon? = null
        private var trackChanged = false
        private var missingTrack = false
        private var trackInfoAvailable = false
        private val expireMetadata = Runnable {
            protect("Media metadata expiry") {
                if (session === this) {
                    timeline = null
                    raw = null
                    if (missingTrack) trackInfoAvailable = false
                    render()
                }
            }
        }

        fun cancelMetadataExpiry() = main.removeCallbacks(expireMetadata)

        private fun markMetadataMissing(identityMissing: Boolean = false) {
            missingTrack = identityMissing
            if ((timeline != null || identityMissing && trackInfoAvailable) && !main.hasCallbacks(expireMetadata)) {
                main.postDelayed(expireMetadata, METADATA_GRACE_MS)
            }
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) = protect("Media metadata") {
            if (session === this) {
                metadataChanged(metadata)
                playbackChanged(controller.playbackState)
                render()
            }
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) = protect("Playback state") {
            if (session === this) {
                playbackChanged(state)
                render()
            }
        }

        override fun onSessionDestroyed() = protect("Session destruction") {
            if (session === this) {
                destroyedToken = token
                releaseSession()
                postRefresh()
            }
        }

        fun playbackChanged(state: PlaybackState?) {
            playback.update(state?.state, state?.position ?: -1L, state?.lastPositionUpdateTime ?: 0L,
                state?.playbackSpeed ?: 0f, duration, SystemClock.elapsedRealtime())
        }

        fun playbackClock(): PlaybackClock = playback.clock(duration)

        fun metadataChanged(data: MediaMetadata?) {
            if (data == null) {
                markMetadataMissing(identityMissing = true)
                return
            }
            val nextTrack = Track(data.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                data.getString(MediaMetadata.METADATA_KEY_TITLE), data.getString(MediaMetadata.METADATA_KEY_ARTIST))
            if (nextTrack.id.isNullOrBlank() && nextTrack.title.isNullOrBlank()) {
                markMetadataMissing(identityMissing = true)
                return
            }
            val nextPayload = data.getString(LyricPayload.KEY)?.takeIf { it.isNotBlank() }
            val changed = track != nextTrack
            if (changed) {
                cancelMetadataExpiry()
                if (track != null) {
                    previousTrackPayload = lastTrackPayload ?: previousTrackPayload
                    lastTrackPayload = null
                    trackChanged = true
                    playback.newTrack()
                    // The OEM may still be displaying the old notification artwork during a handoff.
                    previousArtwork = metadataArtwork
                    previousArtworkIcon = icon
                    clearArtworkCache()
                }
                raw = null
                timeline = null
            }
            when {
                nextPayload == null || nextPayload == previousTrackPayload -> markMetadataMissing()
                else -> {
                    cancelMetadataExpiry()
                    if (raw != nextPayload) {
                        raw = nextPayload
                        timeline = LyricPayload.parse(nextPayload, nextTrack.title, trackChanged, nextTrack.artist)
                        if (timeline != null) lastTrackPayload = nextPayload
                    }
                }
            }
            metadata = data
            track = nextTrack
            trackInfoAvailable = true
            duration = data.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0L }
                ?: if (changed) 0L else duration
        }

        fun trackInfo(): LyricFrame? {
            if (!trackInfoAvailable) return null
            val name = track?.title?.trim()?.take(512)?.takeIf { it.isNotEmpty() } ?: return null
            val byline = track?.artist?.trim()?.take(512)?.takeIf { it.isNotEmpty() }
            return LyricFrame(LyricLine(0, name, byline), 0, isTrackInfo = true)
        }

        fun artwork(fallback: Icon?, strength: Int): Bitmap? {
            val source = artworkSource(fallback)
            if (blurStrength != strength) {
                blurred = source?.let { blurArtwork(it, strength) }
                blurStrength = strength
            }
            return blurred
        }

        private fun clearArtworkCache() {
            metadataArtwork = null
            icon = null
            bitmap = null
            blurred = null
            blurStrength = -1
        }

        private fun artworkSource(fallback: Icon?): Bitmap? {
            val current = ( metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON))
                ?.takeUnless { it === previousArtwork }
            val currentIcon = fallback?.takeUnless { it === previousArtworkIcon }
            if (current != null && metadataArtwork === current && bitmap?.isRecycled != true) return bitmap
            if (current == null && metadataArtwork == null && icon === currentIcon) return bitmap
            metadataArtwork = current
            icon = currentIcon
            val source = current ?: currentIcon?.takeIf {
                it.type == Icon.TYPE_BITMAP || it.type == Icon.TYPE_ADAPTIVE_BITMAP
            }?.let { (hostView?.context?.let(it::loadDrawable) as? BitmapDrawable)?.bitmap }
            blurred = null
            blurStrength = -1
            bitmap = source?.takeUnless { it.isRecycled }?.let {
                val longest = maxOf(it.width, it.height)
                if (longest <= 128) it else Bitmap.createScaledBitmap(it,
                    (it.width * 128f / longest).roundToInt().coerceAtLeast(1),
                    (it.height * 128f / longest).roundToInt().coerceAtLeast(1), true)
            }
            return bitmap
        }

    }

    private data class Track(val id: String?, val title: String?, val artist: String?)

    private companion object {
        const val MEDIA_HANDOFF_MS = 300L
        const val METADATA_GRACE_MS = 750L
    }
}
