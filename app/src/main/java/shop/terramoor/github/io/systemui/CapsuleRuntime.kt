package shop.terramoor.github.io.systemui

import android.animation.ValueAnimator
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
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import io.github.libxposed.api.XposedInterface.HookHandle
import shop.terramoor.github.io.diagnostics.DiagnosticLog
import shop.terramoor.github.io.lyrics.LyricPayload
import shop.terramoor.github.io.lyrics.LyricFrame
import shop.terramoor.github.io.lyrics.LyricPresentation
import shop.terramoor.github.io.lyrics.PlaybackClock
import shop.terramoor.github.io.lyrics.Timeline
import shop.terramoor.github.io.settings.CapsuleSettings
import shop.terramoor.github.io.settings.CapsuleSettingsUpdate
import shop.terramoor.github.io.settings.CapsuleTuning
import shop.terramoor.github.io.settings.NoLyricsMode
import shop.terramoor.github.io.settings.PauseBehavior
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** Owns a single capsule and session, with all UI and callback state confined to the main thread. */
internal class CapsuleRuntime(
    private val host: NativeContract.Host,
    private val diagnostics: DiagnosticLog,
    private val modulePackage: String,
    private val remotePreferences: () -> SharedPreferences,
    private val installPlugin: (NativeContract.Plugin, CapsuleRuntime) -> List<HookHandle>,
) {
    private val main = Handler(Looper.getMainLooper())
    private var preferences: SharedPreferences? = null
    private var preferenceAttempts = 0
    private var settingsUpdateRegistered = false
    private var receivedCommittedSettings = false
    private var settings = CapsuleSettings(enabled = false)
    private var tintDrawFrame: String? = null
    private var hostView: ViewGroup? = null
    private var observer: ViewTreeObserver? = null
    private var root: ViewGroup? = null
    private var plugin: NativeContract.Plugin? = null
    private var pluginHooks = emptyList<HookHandle>()
    private var rejectedLoader = WeakReference<ClassLoader>(null)
    private var mediaController = WeakReference<Any>(null)
    private var dispatched: StatusBarNotification? = null
    private var media: NativeContract.Media? = null
    private var mediaMissingSince: Long? = null
    private var destroyedToken: MediaSession.Token? = null
    private var session: Session? = null
    private val pauseTimeout = CapsulePauseTimeout<NativeContract.PauseOwner>()
    private val tintArtworkSelection = TintArtworkSelection<Pair<MediaSession.Token, Track>, Bitmap>()
    private var labels: CapsuleLabels? = null
    private val centeredMedia = WeakHashMap<ViewGroup, String>()
    private val centerLocation = IntArray(2)
    private var renderedShown: Boolean? = null
    private var layoutChanged = false
    private var scheduledTick: LyricBoundaryTick? = null
    private var status: String? = null
    private val stageMessages = mutableMapOf<String, String>()

    private val refresh = Runnable { protect("Capsule update") { refreshTarget(forceRender = true) } }
    private val tick = Runnable {
        scheduledTick = null
        protect("Lyric rendering", ::render)
    }
    private val connect = Runnable { protect("Preferences reconnect", ::loadPreferences) }
    private val retryMedia = Runnable { protect("Media handoff") { readMedia(); postRefresh() } }
    private val globalLayout = ViewTreeObserver.OnGlobalLayoutListener { if (settings.enabled) layoutChanged = true }
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        val refreshPending = main.hasCallbacks(refresh)
        if (refreshPending || layoutChanged) {
            if (refreshPending) main.removeCallbacks(refresh)
            protect("Capsule update") { refreshTarget(forceRender = refreshPending) }
        }
        val binding = labels
        binding?.enforceNativeVisibility()
        if (binding != null && (renderedShown != binding.target.capsule.isShown ||
                !binding.nativeWhilePaused && (settings.artwork || settings.lyricTintEnabled) &&
                session?.artworkChanged(binding.target.artwork) == true)) {
            protect("Capsule visibility or artwork", ::render)
        }
        true
    }
    private val applyPreferences = Runnable {
        if (!receivedCommittedSettings) protect("Preferences", ::readPreferences)
    }
    private val onPreferences = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        trace("preferences", "CHANGE_RECEIVED")
        main.removeCallbacks(applyPreferences)
        main.post(applyPreferences)
    }

    private fun readPreferences() = applySettings(
        preferences?.let(CapsuleSettings::read) ?: CapsuleSettings(enabled = false),
    )

    private fun applySettings(next: CapsuleSettings) {
        val previous = settings
        settings = next
        diagnostics.note("Settings read, changed=${settings != previous}, $settings")
        trace("tint/settings", "module=${settings.enabled}, enabled=${settings.lyricTintEnabled}, " +
            "algorithm=${settings.lyricTintMode}, strength=${settings[CapsuleTuning.TINT_STRENGTH]}, " +
            "background=${settings.artwork}, firstOpacity=${settings[CapsuleTuning.FIRST_ROW_OPACITY]}, " +
            "secondOpacity=${settings[CapsuleTuning.SECOND_ROW_OPACITY]}")
        if (settings == previous) return
        if (settings.introDots != previous.introDots) session?.lastLoggedLine = null
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
        val displayId = view.display?.displayId
        if (displayId != Display.DEFAULT_DISPLAY) {
            trace("host", "NOT_ON_PRIMARY_DISPLAY, id=$displayId, attached=${view.isAttachedToWindow}")
            return@onMain
        }
        if (hostView !== view) {
            stopObservation(releaseMedia = false)
            hostView = view
            trace("host", "ATTACHED, children=${view.childCount}")
        }
        loadPreferences()
        startObservation()
        postRefresh()
    }

    fun hostDetached(instance: Any) = onMain("Capsule detach") {
        if (hostView === host.view(instance)) {
            stopObservation(releaseMedia = false)
            hostView = null
            trace("host", "DETACHED")
            main.removeCallbacks(connect)
        }
    }

    fun pluginRemoved(instance: Any) = onMain("Capsule plugin detach") {
        if (hostView === host.view(instance)) {
            detachPlugin()
            diagnostics.note("Native capsule plugin detached")
            postRefresh()
        }
    }

    fun mediaChanged(instance: Any, sent: Boolean, notification: StatusBarNotification?) = onMain("Media notification") {
        trace("media callback", "sent=$sent, route=${if (notification == null) "controller" else "dispatch"}")
        if (sent || mediaController.get() === instance || mediaController.get() == null) {
            mediaController = WeakReference(instance)
            dispatched = notification
            if (settings.enabled) {
                readMedia()
                postRefresh()
            }
        }
    }

    fun mediaReleased(instance: Any) = onMain("Media release") {
        if (mediaController.get() === instance) {
            mediaController.clear()
            dispatched = null
            trace("media callback", "CONTROLLER_RELEASED")
            clearMediaRetry()
            media = null
            destroyedToken = null
            releaseSession()
        }
    }

    fun containerLaidOut(container: Any) {
        val currentRoot = root ?: return
        if (settings.enabled && plugin?.belongs(container, currentRoot) == true) layoutChanged = true
    }

    fun draw(instance: Any, canvas: Canvas) {
        val binding = labels ?: return
        if (binding.nativeWhilePaused) return
        val contract = plugin ?: return
        val candidate = media ?: return
        val capsule = binding.target.capsule
        if (capsule !== instance || session?.token != candidate.token || !contract.hasOwner(capsule, candidate.key)) return
        val animatedWidth = contract.animatedDrawingWidth(capsule)
        if (animatedWidth == null && centeredMedia[capsule] != candidate.key && capsule.width > 0) {
            val center = capsule.topDisplayCutout()?.exactCenterX()
            val scale = capsule.horizontalScreenScale()
            if (center != null && scale != null && abs(center - visibleCenter(capsule, scale, null)) <= 2f) {
                centeredMedia[capsule] = candidate.key
            }
        }
        val result = binding.draw(canvas, animatedWidth)
        trace("draw", result)
        val frame = tintDrawFrame
        if (lastTintDrawFrame != frame || lastTintDrawResult != result) {
            lastTintDrawFrame = frame
            lastTintDrawResult = result
            if (frame != null) trace("tint/draw", "result=$result, $frame, ${binding.paintDiagnostics()}")
        }
    }

    fun anchorMediaClose(shape: NativeContract.Plugin.CloseAnimation.Shape, animator: ValueAnimator) {
        if (Looper.myLooper() != main.looper || !settings.enabled) return
        val contract = plugin ?: return
        val currentRoot = root ?: return
        val capsule = shape.capsule
        val container = shape.container
        val key = centeredMedia[capsule] ?: return
        if (capsule.parent !== container || !contract.belongs(container, currentRoot) ||
            !contract.hasOwner(capsule, key) || !contract.isSingleLane(container)) return

        var reported = false
        var valid = true
        // The OEM listener writes the animated rectangle first. Keep its size and move both edges
        // by the same amount using the current on-screen position, even if the parent remeasures.
        animator.addUpdateListener(object : ValueAnimator.AnimatorUpdateListener {
            override fun onAnimationUpdate(animation: ValueAnimator) {
                if (!valid) return
                try {
                    if (!settings.enabled || plugin !== contract || root !== currentRoot ||
                        centeredMedia[capsule] != key ||
                        capsule.parent !== container || !capsule.isAttachedToWindow ||
                        !contract.hasOwner(capsule, key) || !contract.isSingleLane(container)) {
                        valid = false
                        return
                    }
                    val center = capsule.topDisplayCutout()?.exactCenterX() ?: return
                    val parentScale = container.horizontalScreenScale() ?: return
                    val capsuleScale = capsule.scaleX * parentScale
                    if (capsuleScale <= 0f || capsule.width <= 0) return
                    val nativeCenter = visibleCenter(capsule, capsuleScale, contract.animatedDrawingWidth(capsule))
                    val offset = capsuleCloseOffset(center, nativeCenter, parentScale)
                    if (offset == 0) return
                    capsule.offsetLeftAndRight(offset)
                    if (!reported) {
                        reported = true
                        trace("close geometry", "offsetPx=$offset, nativeCenter=$nativeCenter, cutoutCenter=$center")
                    }
                } catch (failure: Throwable) {
                    if (!isExpectedFailure(failure)) throw failure
                    valid = false
                    report("Media close frame", failure)
                }
            }
        })
    }

    private fun visibleCenter(capsule: View, screenScale: Float, animatedWidth: Int?): Float {
        capsule.getLocationOnScreen(centerLocation)
        val center = if (animatedWidth == null) capsule.width / 2f
        else nativeCapsuleClipLeft(capsule.measuredWidth, animatedWidth) + animatedWidth / 2f
        return centerLocation[0] + center * screenScale
    }

    private fun View.horizontalScreenScale(): Float? {
        var view: View? = this
        var scale = 1f
        while (view != null) {
            if (view.rotation != 0f || view.rotationX != 0f || view.rotationY != 0f) return null
            scale *= view.scaleX
            view = view.parent as? View
        }
        return scale.takeIf { it.isFinite() && it > 0f }
    }

    fun stabilizeMediaContentFrame(callback: Any): (() -> Unit)? {
        val contract = plugin ?: return null
        val binding = labels ?: return null
        if (binding.nativeWhilePaused) return null
        val target = binding.target
        val active = target.capsule
        val key = media?.takeUnless { it.token == destroyedToken }?.key ?: return null
        if (!active.isShown || active.width <= 0 || !contract.hasOwner(active, key)) return null
        if (contract.isAnimatingWidth(active)) return null
        val animation = contract.animation ?: return null
        val view = animation.view(callback)
        // A frame callback may run between the OEM's measurements. Use only the existing binding.
        if (!target.isContentPage(view)) return null
        return animation.stabilize(callback, view)
    }

    fun retainPausedCapsule(callback: Any): Boolean {
        val timeout = host.pauseTimeout ?: return false
        return pauseTimeout.retain { timeout.matches(it, callback) }.also { retained ->
            if (retained) diagnostics.note("Paused media capsule retained at native status bar timeout")
        }
    }

    private fun updatePauseOwner(binding: CapsuleLabels? = null, restore: Boolean = true) {
        val timeout = host.pauseTimeout ?: return
        val active = media
        val watch = session
        val controller = mediaController.get()
        val next = if (settings.enabled && settings.pauseBehavior == PauseBehavior.KEEP_CURRENT &&
            binding?.hasCustomContent == true && active != null &&
            controller != null && watch?.token == active.token && watch.isPaused) {
            NativeContract.PauseOwner(controller, active.key, active.token, active.owner)
        } else null
        pauseTimeout.update(next, restore)?.let { owner ->
            timeout.restore(owner) { report("Media pause timeout restore", it) }
        }
    }

    fun contentChanged(container: Any) {
        val currentRoot = root ?: return
        if (!settings.enabled || plugin?.belongs(container, currentRoot) != true) return
        // Add the sizing child before the OEM measures the new item and captures animation bounds.
        val refreshPending = main.hasCallbacks(refresh)
        if (refreshPending) main.removeCallbacks(refresh)
        protect("Capsule content") { refreshTarget(forceRender = refreshPending) }
    }

    private fun loadPreferences() {
        val view = hostView ?: return
        if (!settingsUpdateRegistered) {
            try {
                CapsuleSettingsUpdate.register(view.context.applicationContext, modulePackage, main) { next ->
                    receivedCommittedSettings = true
                    main.removeCallbacks(applyPreferences)
                    trace("preferences", "COMMIT_RECEIVED")
                    protect("Committed capsule settings") { applySettings(next) }
                }
                settingsUpdateRegistered = true
            } catch (failure: Exception) {
                report("Register committed capsule settings", failure)
            }
        }
        if (preferences != null) return
        main.removeCallbacks(connect)
        try {
            val prefs = remotePreferences()
            prefs.registerOnSharedPreferenceChangeListener(onPreferences)
            preferences = prefs
            diagnostics.note("Remote preferences connected")
            if (!receivedCommittedSettings) readPreferences()
        } catch (failure: Throwable) {
            if (!isExpectedFailure(failure)) throw failure
            preferences?.unregisterOnSharedPreferenceChangeListener(onPreferences)
            preferences = null
            if (!receivedCommittedSettings) {
                stopObservation()
                settings = CapsuleSettings(enabled = false)
            }
            diagnostics.failure("Remote preferences attempt ${preferenceAttempts + 1}", failure)
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
        layoutChanged = false
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
        if (plugin != null) diagnostics.note("Native capsule plugin hooks detached, count=${pluginHooks.size}")
        clearLabels()
        centeredMedia.clear()
        pluginHooks.forEach { it.unhook() }
        pluginHooks = emptyList()
        plugin = null
        root = null
    }

    private fun readMedia() {
        val controller = mediaController.get()
        val candidate = if (controller == null) {
            trace("media", "NO_CONTROLLER_CALLBACK")
            null
        } else {
            host.activeMedia(controller, dispatched) { trace("media", it) }
        }
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
            // A player handoff can reuse the same native card. Keep its sizing child attached
            // while switching sessions so the OEM does not measure a different width in between.
            val reuseLabels = candidate != null && candidate.key == previous?.key && labels?.let {
                plugin?.hasOwner(it.target.capsule, candidate.key) == true
            } == true
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

    private fun refreshTarget(forceRender: Boolean) {
        layoutChanged = false
        if (!settings.enabled) return
        val view = hostView?.takeIf { it.isAttachedToWindow }
        val currentRoot = if (view == null) {
            trace("root", "HOST_NOT_ATTACHED")
            null
        } else {
            NativeContract.root(view) { trace("root", it) }
        }
        if (root !== currentRoot) {
            detachPlugin()
            root = currentRoot
            val loader = currentRoot?.javaClass?.classLoader
            if (currentRoot != null) trace("plugin loader", "present=${loader != null}, root=${currentRoot.javaClass.name}")
            if (currentRoot != null && loader != null && rejectedLoader.get() !== loader) {
                try {
                    val contract = NativeContract.Plugin(loader, currentRoot.resources) { feature, failure ->
                        report("Optional $feature", failure)
                    }
                    diagnostics.note("Required plugin classes, drawing method and 7 named resource IDs resolved")
                    pluginHooks = installPlugin(contract, this)
                    plugin = contract
                    Log.i(TAG, "Capsule plugin contract ready")
                    trace("plugin", "BOUND, animation_stabilizer=${contract.animation != null}")
                } catch (failure: Throwable) {
                    if (!isExpectedFailure(failure)) throw failure
                    rejectedLoader = WeakReference(loader)
                    trace("plugin", "REJECTED")
                    report("Plugin contract", failure)
                }
            }
        }
        if (currentRoot == null || plugin == null) {
            setStatus(if (currentRoot != null && rejectedLoader.get() === currentRoot.javaClass.classLoader) {
                "Native capsule plugin contract did not match"
            } else "Waiting for native capsule plugin")
            return
        }
        val candidate = media?.takeUnless { it.token == destroyedToken }
        val target = candidate?.let { active ->
            plugin?.findTarget(currentRoot, active.key, labels?.target) { trace("target", it) }
        }
        if (candidate == null || target == null) {
            clearLabels()
            setStatus(if (candidate == null) "Waiting for native media notification" else "Native media capsule not visible")
            return
        }
        val newSession = session?.token != candidate.token
        if (newSession) {
            val controller = MediaController(currentRoot.context, candidate.token)
            if (controller.packageName != candidate.owner) {
                releaseSession()
                setStatus("Native media and session owner differ")
                return
            }
            val watch = Session(candidate.token, controller)
            session = watch
            val ownerVersion = runCatching {
                val info = currentRoot.context.packageManager.getPackageInfo(candidate.owner, 0)
                "${info.versionName} (${info.longVersionCode})"
            }.getOrDefault("unavailable")
            diagnostics.note("Media session bound, owner verified, player=${candidate.owner}, version=$ownerVersion")
            controller.registerCallback(watch, main)
            watch.metadataChanged(controller.metadata)
            watch.playbackChanged(controller.playbackState)
        }
        val newBinding = labels?.target != target
        if (newBinding) {
            clearLabels()
            labels = CapsuleLabels(target)
            Log.i(TAG, "Verified native media capsule label slots")
            fun node(view: View) = "${view.javaClass.name}(${view.width}x${view.height},shown=${view.isShown})"
            diagnostics.note("Native capsule bound: capsule=${node(target.capsule)}, titleFrame=${node(target.title.frame)}, title=${node(target.title.stock)}")
            diagnostics.note("Native capsule slots: translationFrame=${node(target.translation.frame)}, translation=${node(target.translation.stock)}, artwork=${node(target.artwork)}, waveform=${node(target.waveform)}")
        }
        if (forceRender || newSession || newBinding || renderedShown != target.capsule.isShown) {
            render()
        } else {
            labels?.maintainNativeSizing()
        }
    }

    private fun render() {
        val binding = labels ?: return
        val currentRoot = root ?: return
        val candidate = media ?: return
        // Recheck ownership on every lyric boundary in case the OEM reused the view.
        if (plugin?.findTarget(currentRoot, candidate.key, binding.target) { trace("target", it) } != binding.target) {
            trace("render", "OWNER_OR_TARGET_CHANGED; restoring native content")
            clearLabels()
            return
        }
        val watch = session ?: return
        val shown = binding.target.capsule.isShown
        renderedShown = shown
        if (watch.isPaused && settings.pauseBehavior == PauseBehavior.RESTORE_NATIVE) {
            centeredMedia.remove(binding.target.capsule)
            binding.restoreForPause()
            setLyricTick(null)
            tintDrawFrame = null
            updatePauseOwner()
            setStatus("Using native media capsule while paused")
            return
        }
        val tintEnabled = settings.lyricTintEnabled
        val artwork = watch.artworkSource(candidate.artwork, binding.target.artwork)
        val background = if (settings.artwork) watch.background(artwork, settings[CapsuleTuning.BLUR]) else null
        val ink = watch.tint.resolve(watch.tintArtwork.bitmap, background, settings,
            settings[CapsuleTuning.ISLAND_WIDTH] / 30f)
        val correlation = watch.tintCorrelation()
        if (tintEnabled) {
            trace("tint/source", "$correlation, ${watch.artworkObservation}")
            trace("tint/extraction", "$correlation, ${watch.tint.extractionDetail}")
            trace("tint/contrast", "$correlation, ${watch.tint.contrastDetail}")
            trace("tint/cache", "$correlation, ${watch.tint.cacheDetail}")
        }
        trace("tint/palette", "enabled=$tintEnabled, algorithm=${settings.lyricTintMode}, " +
            "$correlation, strength=${settings[CapsuleTuning.TINT_STRENGTH]}, source=${watch.tintArtwork.origin}, backgroundSource=${watch.artworkOrigin}, " +
            "background=${background != null}, extraction=${watch.tint.extraction}, result=${watch.tint.outcome}, ${ink.diagnosticColors()}")
        val presentation = watch.timeline?.let { LyricPresentation(it, settings) }
        if (presentation == null || presentation.lines.isEmpty()) {
            setLyricTick(null)
            val trackInfo = if (settings.noLyricsMode == NoLyricsMode.TRACK) watch.trackInfo() else null
            renderFrame(binding, trackInfo, "NO_TIMELINE", background, ink)
            trace("render", "NO_TIMELINE, trackInfo=${trackInfo != null}, artwork=${background != null}, shown=${binding.target.capsule.isShown}, size=${binding.target.capsule.width}x${binding.target.capsule.height}")
            setStatus(if (trackInfo != null) "Showing track info without usable lyric timing"
                else "Waiting for timed lyrics from the active session")
            return
        }
        val clock = watch.playbackClock()
        val now = SystemClock.elapsedRealtime()
        val uptime = SystemClock.uptimeMillis()
        val position = clock.position(now)
        val index = if (watch.hasPlaybackPosition) presentation.indexAt(position) else -1
        val line = presentation.lines.getOrNull(index)?.takeIf { it.text.isNotBlank() }
        val next = presentation.nextBoundary(index)
        val frame = if (watch.hasPlaybackPosition) {
            val trackRows = { watch.trackInfo()?.rows }
            presentation.introAt(position, trackRows) ?: presentation.frameAt(index, watch.duration, trackRows)
        } else null
        if (watch.lastLoggedLine != index) {
            watch.lastLoggedLine = index
            val mode = index.takeIf { it >= 0 }?.let(presentation::switchMode)
            diagnostics.note("[lyric schedule] index=$index/${presentation.lines.size}, positionMs=$position, range=${line?.startMs}..${line?.endMs}, scrollEnd=${frame?.endMs}, displayStart=${frame?.displayStartMs}, next=$next, mode=$mode, secondRow=${frame?.rows?.second != null}, translated=${!line?.translation.isNullOrBlank()}, shown=${binding.target.capsule.isShown}")
        }
        if (frame != null) {
            val phase = when {
                frame.isIntro -> "INTRO_DOTS"
                frame.countdown != null -> "INTERLUDE_DOTS"
                else -> "SYNCED"
            }
            renderFrame(binding, frame, phase, background, ink, clock)
            setStatus(if (frame.countdown != null) "Rendering lyric countdown" else "Rendering lyrics in native media capsule")
        } else {
            val introInfo = if (index < 0 && settings.noLyricsMode == NoLyricsMode.TRACK) watch.trackInfo() else null
            renderFrame(binding, introInfo, "INTRO_OR_GAP", background, ink)
            setStatus("Waiting for next lyric boundary")
        }
        val planned = if (shown && next != null) LyricBoundaryTick.plan(clock, next, now, uptime) else null
        setLyricTick(planned)
        trace("scheduler", when {
            planned != null -> "NEXT_LINE index=$index, targetMs=$next"
            !shown || next == null -> "NO_NEXT_OR_HIDDEN index=$index, shown=$shown"
            else -> "NOT_MOVING index=$index"
        })
    }

    private fun setLyricTick(planned: LyricBoundaryTick?) {
        val previous = scheduledTick
        if (planned == previous || planned?.sameDeadline(previous) == true) return
        if (previous != null) main.removeCallbacks(tick)
        scheduledTick = planned
        if (planned != null) main.postAtTime(tick, planned.uptimeMs)
    }

    private var lastTintDrawFrame: String? = null
    private var lastTintDrawResult: String? = null

    private fun renderFrame(
        binding: CapsuleLabels, frame: LyricFrame?, phase: String, background: Bitmap?, ink: LyricInk,
        clock: PlaybackClock? = null,
    ) {
        binding.render(frame, settings, background, clock, ink, trackIdentity = session?.trackIdentity)
        updatePauseOwner(binding)
        val kind = when {
            frame == null -> "NO_TEXT"
            frame.isTrackInfo -> "TRACK_INFO"
            frame.isIntro -> "INTRO_DOTS"
            frame.countdown != null -> "INTERLUDE_DOTS"
            else -> "LYRICS"
        }
        val detail = "${session?.tintCorrelation()}, phase=$phase, kind=$kind, secondRow=${frame?.rows?.second != null}, opacity=${settings[CapsuleTuning.FIRST_ROW_OPACITY]}/${settings[CapsuleTuning.SECOND_ROW_OPACITY]}, ${ink.diagnosticColors()}"
        tintDrawFrame = detail
        trace("tint/frame", detail)
    }

    private fun LyricInk.diagnosticColors(): String {
        val rows = "first=#${first.toUInt().toString(16)}, second=#${second.toUInt().toString(16)}"
        val stops = gradient ?: return rows
        return "$rows, gradient=#${stops.start.toUInt().toString(16)}..#${stops.end.toUInt().toString(16)}"
    }

    private fun clearLabels() {
        updatePauseOwner()
        setLyricTick(null)
        renderedShown = null
        tintDrawFrame = null
        lastTintDrawFrame = null
        lastTintDrawResult = null
        val binding = labels
        labels = null
        if (binding != null) {
            binding.close()
            diagnostics.note("Native capsule content restored and labels released")
        }
    }

    private fun releaseSession(keepLabels: Boolean = false) {
        updatePauseOwner()
        setLyricTick(null)
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
            report(step, failure)
            try { releaseSession() } catch (cleanup: Exception) { report("Capsule cleanup", cleanup) }
            setStatus("$step unavailable (${failure.javaClass.simpleName})")
        }
    }

    private fun trace(stage: String, detail: String) {
        if (stageMessages.put(stage, detail) == detail) return
        val message = "[$stage] $detail"
        Log.i(TAG, message)
        diagnostics.note(message)
    }

    private fun report(step: String, failure: Throwable) {
        reportFailure(step, failure)
        diagnostics.failure(step, failure)
    }

    private fun setStatus(message: String) {
        if (status == message) return
        status = message
        Log.i(TAG, message)
        diagnostics.note(message)
    }

    private inner class Session(val token: MediaSession.Token, val controller: MediaController) : MediaController.Callback() {
        private val playback = CapsulePlayback()
        private var trackSequence = 0L
        var trackIdentity = Any()
            private set
        fun tintCorrelation() = "pid=${android.os.Process.myPid()}, session=${System.identityHashCode(this)}, track=$trackSequence, calculation=${tint.calculationId}"
        val hasPlaybackPosition: Boolean get() = playback.hasPosition
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
        private var nativeArtwork: Bitmap? = null
        private var nativeArtworkGeneration = 0
        private var nativeArtworkIsDirectBitmap = false
        private var previousNativeArtwork: Bitmap? = null
        private var selectedArtwork: Bitmap? = null
        private var selectedArtworkGeneration = 0
        private var observedMediaTint: Bitmap? = null
        private var observedMediaTintGeneration = 0
        private var bitmap: Bitmap? = null
        private var waitsForNativeArtwork = false
        var artworkOrigin = "NONE"
            private set
        var artworkObservation = "not observed"
            private set
        var tintArtwork = TintArtwork<Bitmap>(null, TintArtworkOrigin.NOT_REQUESTED)
            private set
        val tint = ArtworkLyricTint()
        private var icon: Icon? = null
        private var iconArtwork: Bitmap? = null
        private var blurred: Bitmap? = null
        private var blurStrength = -1
        private var previousArtwork: Bitmap? = null
        private var previousArtworkIcon: Icon? = null
        private var trackChanged = false
        private var missingTrack = false
        private var trackInfoAvailable = false
        private var lastPlaybackState: Int? = null
        val isPaused: Boolean get() = lastPlaybackState == PlaybackState.STATE_PAUSED
        private var lyricStatus: String? = null
        var lastLoggedLine: Int? = null
        private val expireMetadata = Runnable {
            protect("Media metadata expiry") {
                if (session === this) {
                    timeline = null
                    raw = null
                    if (missingTrack) trackInfoAvailable = false
                    diagnostics.note("Media metadata grace period expired")
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
                // A payload or artwork update is not a playback-state update. Pull a fresh
                // snapshot only on a track handoff, when playback may have arrived first.
                if (metadataChanged(metadata)) playbackChanged(controller.playbackState)
                render()
            }
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) = protect("Playback state") {
            if (session === this) {
                playbackChanged(state)
                if (labels == null) postRefresh() else render()
            }
        }

        override fun onSessionDestroyed() = protect("Session destruction") {
            if (session === this) {
                destroyedToken = token
                diagnostics.note("Media session destroyed")
                releaseSession()
                postRefresh()
            }
        }

        fun playbackChanged(state: PlaybackState?) {
            val now = SystemClock.elapsedRealtime()
            val expectedPosition = playback.clock(duration).position(now)
            if (!playback.update(state?.state, state?.position ?: -1L, state?.lastPositionUpdateTime ?: 0L,
                    state?.playbackSpeed ?: 0f, duration, now)) return
            val seek = state != null && lastPlaybackState == state.state && state.position >= 0L &&
                kotlin.math.abs(state.position - expectedPosition) > 1500L
            if (lastPlaybackState != state?.state || seek) {
                lastPlaybackState = state?.state
                if (state?.state == PlaybackState.STATE_PLAYING) updatePauseOwner(restore = false)
                diagnostics.note("Playback state=${state?.state}, positionMs=${state?.position}, speed=${state?.playbackSpeed}, seek=$seek, expectedMs=$expectedPosition, durationMs=$duration")
            }
        }

        fun playbackClock(): PlaybackClock = playback.clock(duration)

        fun metadataChanged(data: MediaMetadata?): Boolean {
            if (data == null) {
                logLyrics("Media metadata missing")
                markMetadataMissing(identityMissing = true)
                return false
            }
            val nextTrack = Track(data.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                data.getString(MediaMetadata.METADATA_KEY_TITLE), data.getString(MediaMetadata.METADATA_KEY_ARTIST))
            if (nextTrack.id.isNullOrBlank() && nextTrack.title.isNullOrBlank()) {
                logLyrics("Media metadata has no track identity")
                markMetadataMissing(identityMissing = true)
                return false
            }
            missingTrack = false
            val nextPayload = data.getString(LyricPayload.KEY)?.takeIf { it.isNotBlank() }
            val changed = track != nextTrack
            if (changed) {
                tintArtworkSelection.onTrack(token to nextTrack)
                trackSequence++
                trackIdentity = Any()
                lyricStatus = null
                lastLoggedLine = null
                diagnostics.note("Track metadata changed, previous track present=${track != null}")
                cancelMetadataExpiry()
                if (track != null) {
                    previousTrackPayload = lastTrackPayload ?: previousTrackPayload
                    lastTrackPayload = null
                    trackChanged = true
                    playback.newTrack()
                    // The OEM may still be displaying the old notification artwork during a handoff.
                    previousArtwork = metadataArtwork
                    previousArtworkIcon = icon
                    previousNativeArtwork = nativeArtwork
                    clearArtworkCache()
                }
                raw = null
                timeline = null
            }
            when {
                nextPayload == null || nextPayload == previousTrackPayload -> {
                    logLyrics(if (nextPayload == null) "No lyricInfo in metadata" else "Waiting for new track lyricInfo")
                    markMetadataMissing()
                }
                else -> {
                    cancelMetadataExpiry()
                    if (raw != nextPayload) {
                        lastLoggedLine = null
                        raw = nextPayload
                        val parsed = LyricPayload.inspect(nextPayload, nextTrack.title, trackChanged, nextTrack.artist)
                        timeline = parsed.timeline
                        val lines = timeline?.lines
                        if (lines != null) lastTrackPayload = nextPayload
                        val outcome = if (lines == null) {
                            "[lyrics] ${parsed.outcome}, chars=${nextPayload.length}, LRC=${parsed.hasLrc}, TTML=${parsed.hasTtml}"
                        } else {
                            "[lyrics] PARSED, chars=${nextPayload.length}, lines=${lines.size}, translated=${lines.count { !it.translation.isNullOrBlank() }}, LRC=${parsed.hasLrc}, TTML=${parsed.hasTtml}"
                        }
                        logLyrics(outcome)
                    }
                }
            }
            metadata = data
            track = nextTrack
            trackInfoAvailable = true
            duration = data.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0L }
                ?: if (changed) 0L else duration
            return changed
        }

        private fun logLyrics(message: String) {
            if (lyricStatus == message) return
            lyricStatus = message
            diagnostics.note(message)
        }

        fun trackInfo(): LyricFrame? {
            if (!trackInfoAvailable) return null
            val name = track?.title?.trim()?.take(512)?.takeIf { it.isNotEmpty() } ?: return null
            val byline = track?.artist?.trim()?.take(512)?.takeIf { it.isNotEmpty() }
            return LyricFrame.trackInfo(name, byline)
        }

        fun background(source: Bitmap?, strength: Int): Bitmap? {
            if (blurStrength != strength) {
                blurred = source?.let { blurArtwork(it, strength) }
                blurStrength = strength
            }
            return blurred
        }

        private fun clearArtworkCache() {
            metadataArtwork = null
            nativeArtwork = null
            nativeArtworkGeneration = 0
            nativeArtworkIsDirectBitmap = false
            selectedArtwork = null
            selectedArtworkGeneration = 0
            observedMediaTint = null
            observedMediaTintGeneration = 0
            icon = null
            iconArtwork = null
            bitmap = null
            blurred = null
            blurStrength = -1
        }

        fun artworkChanged(cover: View): Boolean {
            val drawable = (cover as? ImageView)?.drawable
            val currentNative = artworkBitmap(drawable)
            val currentTint = if (settings.lyricTintEnabled) mediaTintArtwork() else null
            val watchNative = waitsForNativeArtwork || settings.lyricTintEnabled
            return (watchNative && (currentNative !== nativeArtwork ||
                (currentNative?.generationId ?: 0) != nativeArtworkGeneration ||
                (drawable is BitmapDrawable) != nativeArtworkIsDirectBitmap)) ||
                currentTint !== observedMediaTint || (currentTint?.generationId ?: 0) != observedMediaTintGeneration ||
                (selectedArtwork?.takeUnless { it.isRecycled }?.generationId ?: 0) != selectedArtworkGeneration
        }

        private fun mediaTintArtwork(): Bitmap? {
            val data = metadata ?: return null
            return sequenceOf(MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART)
                .mapNotNull { data.getBitmap(it) }
                .firstOrNull { !it.isRecycled && it !== previousArtwork }
        }

        fun artworkSource(fallback: Icon?, cover: View): Bitmap? {
            val drawable = (cover as? ImageView)?.drawable
            nativeArtwork = artworkBitmap(drawable)
            nativeArtworkGeneration = nativeArtwork?.generationId ?: 0
            nativeArtworkIsDirectBitmap = drawable is BitmapDrawable
            observedMediaTint = if (settings.lyricTintEnabled) mediaTintArtwork() else null
            observedMediaTintGeneration = observedMediaTint?.generationId ?: 0
            tintArtwork = tintArtworkSelection.select(
                settings.lyricTintEnabled, observedMediaTint, nativeArtwork.takeIf { nativeArtworkIsDirectBitmap },
                nativeArtworkGeneration, trackIdentified = track != null && !missingTrack,
            )
            if (!settings.lyricTintEnabled && !settings.artwork) return null

            val data = metadata
            val current = data?.let {
                fun currentBitmap(key: String) = it.getBitmap(key)?.takeUnless { image ->
                    image.isRecycled || image === previousArtwork
                }
                currentBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: currentBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: currentBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            }
            val currentIcon = fallback?.takeUnless { it === previousArtworkIcon }
            if (icon !== currentIcon) {
                icon = currentIcon
                iconArtwork = null
            }
            if (current == null && iconArtwork == null && currentIcon != null &&
                (currentIcon.type == Icon.TYPE_BITMAP || currentIcon.type == Icon.TYPE_ADAPTIVE_BITMAP)) {
                val context = hostView?.context
                if (context != null) iconArtwork = artworkBitmap(currentIcon.loadDrawable(context))
            }
            metadataArtwork = current
            val iconBitmap = iconArtwork?.takeUnless { it.isRecycled }
            waitsForNativeArtwork = cover is ImageView && current == null && iconBitmap == null
            // The verified slot may still carry the preceding track's cover while the OEM updates it.
            val nativeBitmap = nativeArtwork?.takeUnless { it === previousNativeArtwork }
            val source = current ?: iconBitmap ?: nativeBitmap
            artworkOrigin = when {
                current != null -> "MEDIA_METADATA"
                iconBitmap != null -> "NOTIFICATION_BITMAP"
                nativeBitmap != null -> "NATIVE_COVER"
                else -> "NONE"
            }
            artworkObservation = "origin=$artworkOrigin, metadataBitmap=${current != null}, " +
                "notificationType=${fallback?.type}, notificationBitmap=${iconBitmap != null}, " +
                "nativeView=${cover.javaClass.simpleName}, drawable=${drawable?.javaClass?.simpleName}, nativeBitmap=${nativeBitmap != null}, " +
                "priorNotificationIgnored=${fallback != null && currentIcon == null}, " +
                "priorNativeIgnored=${nativeArtwork != null && nativeBitmap == null}, " +
                "tintAlbumArtReady=${observedMediaTint != null}, tintSource=${tintArtwork.origin}, " +
                "waitingForNative=$waitsForNativeArtwork, selected=${source?.width}x${source?.height}, config=${source?.config}, " +
                "identity=${source?.let(System::identityHashCode)}, generation=${source?.takeUnless { it.isRecycled }?.generationId}"
            trace("tint/metadata", "${tintCorrelation()}, albumArt=${data?.containsKey(MediaMetadata.METADATA_KEY_ALBUM_ART)}, art=${data?.containsKey(MediaMetadata.METADATA_KEY_ART)}, displayIcon=${data?.containsKey(MediaMetadata.METADATA_KEY_DISPLAY_ICON)}, selectedCacheHit=${selectedArtwork === source && bitmap?.isRecycled != true}")
            val sourceGeneration = source?.generationId ?: 0
            if (selectedArtwork === source && selectedArtworkGeneration == sourceGeneration && bitmap?.isRecycled != true) return bitmap
            selectedArtwork = source
            selectedArtworkGeneration = sourceGeneration
            blurred = null
            blurStrength = -1
            bitmap = source?.let {
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
