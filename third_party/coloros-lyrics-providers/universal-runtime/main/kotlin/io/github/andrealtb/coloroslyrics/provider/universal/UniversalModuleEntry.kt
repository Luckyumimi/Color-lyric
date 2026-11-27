package io.github.andrealtb.coloroslyrics.provider.universal

import android.annotation.SuppressLint
import android.util.Log
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.UserHandle
import android.os.SystemClock
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticEvent
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticHasher
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.publisher.ColorOSLyricJsonEncoder
import io.github.andrealtb.coloroslyrics.provider.core.publisher.MetadataParcelGuard
import io.github.andrealtb.coloroslyrics.provider.universal.session.ActiveSessionSelector
import io.github.andrealtb.coloroslyrics.provider.universal.session.BluetoothLyricDemux
import io.github.andrealtb.coloroslyrics.provider.universal.session.PlaybackClockQuality
import io.github.andrealtb.coloroslyrics.provider.universal.session.PlaybackStates
import io.github.andrealtb.coloroslyrics.provider.universal.session.RawSessionMetadata
import io.github.andrealtb.coloroslyrics.provider.universal.session.ResolvedSession
import io.github.andrealtb.coloroslyrics.provider.universal.session.SessionObservation
import io.github.andrealtb.coloroslyrics.provider.universal.session.SessionRepository
import io.github.andrealtb.coloroslyrics.provider.universal.session.TrackDescriptor
import io.github.andrealtb.coloroslyrics.provider.universal.session.TrackIdentityResolver
import io.github.andrealtb.coloroslyrics.provider.universal.session.UniversalMonitorSnapshot
import io.github.andrealtb.coloroslyrics.provider.universal.cache.UniversalLyricCache
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import org.json.JSONObject
import shop.terramoor.github.io.lyrics.LyricPayload
import shop.terramoor.github.io.lyrics.CapsuleLyricModeSync
import shop.terramoor.github.io.lyrics.MiguMediaIdentity
import shop.terramoor.github.io.lyrics.provider.ExternalLyricsCallbacks
import shop.terramoor.github.io.lyrics.provider.ExternalLyricsPublication
import shop.terramoor.github.io.lyrics.provider.ExternalLyricsTrackMatcher
import shop.terramoor.github.io.lyrics.provider.ExternalLyricsRelay
import shop.terramoor.github.io.lyrics.provider.ExternalLyricsSource
import shop.terramoor.github.io.lyrics.provider.SuperLyricServiceSource

class UniversalModuleEntry : XposedModule() {
    private data class ExternalOverlayState(
        val ownerPackage: String,
        val sessionId: String,
        val trackKey: String,
        val metadataKey: String,
        val originalLyricInfo: String?,
        val publishedLyricInfo: String,
    )

    companion object {
        private val DEFAULT_BOUND_PACKAGES = setOf(
            "com.salt.music",
            "com.apple.android.music",
            "com.spotify.music",
            "remix.myplayer",
            "com.maxmpz.audioplayer",
            "ink.trantor.coneplayer"
        )
        private const val BINDINGS_FILE_PATH = "/data/system/colorlyric_universal_bindings.json"
        private const val PROVIDER_MODE_BUILTIN = "builtin"
        private const val PROVIDER_MODE_LYRICON = "lyricon"
        private const val PROVIDER_MODE_SUPER_LYRIC = "super_lyric"
        private const val PROVIDER_MODE_FILE_PATH = "/data/system/colorlyric_universal_provider_mode.txt"
    }

    private val repository = SessionRepository(
        resolver = TrackIdentityResolver(),
        selector = ActiveSessionSelector()
    )
    @Volatile
    private var latestSnapshotText: String? = null
    private var latestArtworkIdentity: Pair<String, Long>? = null
    @Volatile
    private var lastBroadcastSnapshotText: String? = null
    @Volatile
    private var lastBroadcastArtworkRevision = 0L
    private val snapshotLock = Any()
    /**
     * Recent bound-player tracks with their latest lyric status, sent with every snapshot. Kept
     * here because the app misses broadcasts while it is frozen or not running. Written under
     * [snapshotLock].
     */
    @Volatile
    private var recentHistory = ""
    private val configExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "UniversalConfigWriter").apply { isDaemon = true }
    }
    @Volatile
    private var receiverRegistered = false
    private var lastSelectedInstanceId: String? = null
    private var lastGeneration: Long? = null
    private val isOverriding = ThreadLocal<Boolean>()
    @Volatile
    private var boundPackages: Set<String> = emptySet()
    @Volatile
    private var cachedMetadataField: Field? = null
    @Volatile
    private var cachedLockField: Field? = null
    @Volatile
    private var cachedPushMetadataUpdateMethod: Method? = null
    private val realLyricsByPackage = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val nativeLyricsByTrack = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val trackPayloads = UniversalTrackPayloads()
    private val externalLyricsByTrack = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val externalProviderLock = Any()
    private val externalOverlays = java.util.concurrent.ConcurrentHashMap<String, ExternalOverlayState>()
    @Volatile
    private var activeExternalPackage: String? = null
    @Volatile
    private var latestExternalPublication: ExternalLyricsPublication? = null
    @Volatile private var superLyricConnection = "idle"
    @Volatile private var superLyricDelivery = "waiting_for_lyrics"
    @Volatile
    private var providerMode: String = loadProviderMode()
    @Volatile
    private var disabledLyriconProviders: Set<String> = runCatching {
        java.io.File("/data/system/colorlyric_disabled_lyricon_providers.txt").readLines().toSet()
    }.getOrDefault(emptySet())
    @Volatile
    private var externalSource: ExternalLyricsSource? = null
    @Volatile
    private var externalReceiverActive = false
    private val providerModeGeneration = java.util.concurrent.atomic.AtomicLong()
    private val superLyricRetryScheduled = java.util.concurrent.atomic.AtomicBoolean()
    private val inFlightFetches = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val searchConfigRevision = java.util.concurrent.atomic.AtomicLong()
    /**
     * Title/artist the lyric lookup used when the resolved identity had no title yet
     * (`package|generation`). The monitor snapshot shows the same identity the lyric matched.
     */
    private val lookupIdentityByKey = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String?>>()
    // Multiple MediaSessionRecords may belong to the same package at once.
    // Never resolve a record by package alone when publishing an async result.
    private val activeRecords = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val trackBySession = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val miguIdentities = java.util.concurrent.ConcurrentHashMap<String, MiguMediaIdentity>()
    // A stalled request for a skipped song must not monopolize the only worker.
    private val backgroundExecutor = java.util.concurrent.Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "UniversalLyricWorker").apply { isDaemon = true }
    }
    /**
     * SystemUI creates its media card asynchronously after the first
     * MediaSession callback.  A metadata push that happens before that card
     * exists can be missed, which is why a cold-start track used to require a
     * manual skip.  Keep a tiny, bounded replay queue for the freshly fetched
     * payload; each replay is generation guarded and therefore cannot leak a
     * previous song into the current session.
     */
    private val metadataReplayExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "UniversalLyricMetadataReplay").apply { isDaemon = true }
    }
    private val aggregator by lazy { UniversalFeatureConfig.ensureLoaded(); io.github.andrealtb.coloroslyrics.provider.universal.api.LyricsSourceAggregator() }
    @Volatile
    private var sourcePriority: List<io.github.andrealtb.coloroslyrics.provider.universal.api.LyricsSourceAggregator.SourceId> =
        io.github.andrealtb.coloroslyrics.provider.universal.LyricsSourceConfigStore.decode(loadSourceConfig()).mapNotNull {
            runCatching { io.github.andrealtb.coloroslyrics.provider.universal.api.LyricsSourceAggregator.SourceId.valueOf(it) }.getOrNull()
        }
    @Volatile
    private var wordTimingEnabled: Boolean = loadWordTimingEnabled()
    @Volatile
    private var translationEnabled: Boolean = loadTranslationEnabled()
    @Volatile
    private var autoTranslateEnabled: Boolean = loadAutoTranslateEnabled()
    @Volatile
    private var autoTranslateLang: String = loadAutoTranslateLang()
    @Volatile
    private var rawLyricEnabled: Boolean = loadRawLyricEnabled()
    @Volatile
    private var debugEnabled: Boolean = loadDebugEnabled()
    @Volatile
    private var clockLineFallbackEnabled: Boolean = loadClockLineFallbackEnabled()
    /** Read-only PlaybackState grain per player; coarse clocks publish line-timed rawLyric. */
    private val clockQuality = PlaybackClockQuality()
    private val scheduledIdentitySettle = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val fetchBackoffUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private data class ArtworkSnapshot(
        val source: java.lang.ref.WeakReference<android.graphics.Bitmap>,
        val sourceGenerationId: Int,
        val bitmap: android.graphics.Bitmap,
        val revision: Long,
        val trackGeneration: Long,
    )
    private val nextArtworkRevision = java.util.concurrent.atomic.AtomicLong(SystemClock.elapsedRealtimeNanos())
    private val artworkBySession = java.util.concurrent.ConcurrentHashMap<String, ArtworkSnapshot>()

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        runCatching {
            io.github.andrealtb.coloroslyrics.provider.runtimediagnostics.ProviderRuntimeDiagnostics.initialize(
                moduleApplicationInfo.packageName, param.processName,
                io.github.andrealtb.coloroslyrics.provider.core.diagnostics.ProviderBuildIdentity.describe(javaClass),
                "$frameworkName / $frameworkVersion ($frameworkVersionCode)", apiVersion, frameworkProperties)
        }
        StructuredDiagnostics.configure(debugEnabled = debugEnabled)
        io.github.andrealtb.coloroslyrics.provider.runtimediagnostics.ProviderRuntimeDiagnostics.configConsumed(
            debugEnabled, -1L, "BOOTSTRAP_NO_REVISION")
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = UniversalDiagnostics.COMPONENT,
                area = "bootstrap",
                event = "MODULE_LOADED",
                process = param.processName,
                reason = "framework=${frameworkName} api=${apiVersion} buildId=" +
                    io.github.andrealtb.coloroslyrics.provider.core.diagnostics.ProviderBuildIdentity.describe(javaClass)
            )
        )
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        val classLoader = param.classLoader
        val stubClass = classLoader.loadClass("com.android.server.media.MediaSessionRecord\$SessionStub")
        val recordClass = classLoader.loadClass("com.android.server.media.MediaSessionRecord")
        val setMetadata = stubClass.getDeclaredMethod(
            "setMetadata",
            MediaMetadata::class.java,
            java.lang.Long.TYPE,
            String::class.java
        )
        val setPlaybackState = stubClass.getDeclaredMethod(
            "setPlaybackState",
            PlaybackState::class.java
        )
        // ColorOS 17 (Android 17) refactored SessionStub: the record is now held through the
        // field "mRecord" as a WeakReference; older builds used the synthetic outer field
        // "this$0" (a direct reference). Resolve by known names first, then by any field
        // typed as the record, and unwrap the weak reference when reading.
        val recordField = arrayOf("this\$0", "mRecord").firstNotNullOfOrNull { name ->
            runCatching { stubClass.getDeclaredField(name) }.getOrNull()
        } ?: stubClass.declaredFields.firstOrNull { it.type.name == recordClass.name }
        ?: error("SessionStub record reference field not found")
        recordField.isAccessible = true
        fun recordOf(stub: Any?): Any? {
            if (stub == null) return null
            val raw = recordField.get(stub) ?: return null
            return if (raw is java.lang.ref.WeakReference<*>) raw.get() else raw
        }
        val packageField = fieldOrNull(recordClass, "mPackageName")
        val userField = fieldOrNull(recordClass, "mUserId")
        val metadataField = fieldOrNull(recordClass, "mMetadata")
        val playbackField = fieldOrNull(recordClass, "mPlaybackState")
        val contextField = fieldOrNull(recordClass, "mContext")
        val lockField = fieldOrNull(recordClass, "mLock")
        val pushMetadataUpdateMethod = runCatching {
            recordClass.getDeclaredMethod("pushMetadataUpdate").apply { isAccessible = true }
        }.getOrNull()
        val pushSessionDestroyedMethod = runCatching {
            recordClass.getDeclaredMethod("pushSessionDestroyed").apply { isAccessible = true }
        }.getOrNull()
        boundPackages = loadBoundPackages()
        repository.updateBoundFilter(boundPackages, SystemClock.elapsedRealtime())
        currentSystemContext()?.let(::ensureRequestReceiver)
        hook(setMetadata)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("universal:setMetadata")
            .intercept { chain ->
                val stub = chain.thisObject
                val record = recordOf(stub) ?: return@intercept chain.proceed()
                val ownerPackage = packageField?.get(record) as? String ?: return@intercept chain.proceed()
                val commandContext = (contextField?.get(record) as? Context) ?: currentSystemContext()
                commandContext?.let(::ensureRequestReceiver)
                val incoming = chain.getArg(0) as? MediaMetadata
                UniversalDiagnostics.metadataHookEntered(
                    ownerPackage,
                    incoming != null,
                    incoming?.getString(MediaMetadata.METADATA_KEY_TITLE),
                    incoming?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                    incoming?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                    chain.getArg(2) as? String
                )
                if (!boundPackages.contains(ownerPackage)) {
                    refreshBindingsFromDisk()
                    if (!boundPackages.contains(ownerPackage)) {
                        UniversalDiagnostics.metadataFiltered(ownerPackage, boundPackages.size)
                        return@intercept chain.proceed()
                    }
                }
                cachedMetadataField = metadataField
                cachedLockField = lockField
                cachedPushMetadataUpdateMethod = pushMetadataUpdateMethod
                activeRecords[System.identityHashCode(record).toString(16)] = record

                var rewritten: MediaMetadata? = null
                if (incoming != null) {
                    try {
                        rewritten = enrichMetadataBeforeSet(
                            record,
                            ownerPackage,
                            incoming,
                            chain.getArg(2) as? String,
                            userField,
                            playbackField,
                            contextField
                        )
                    } catch (error: Throwable) {
                        UniversalDiagnostics.metadataHookFailed(ownerPackage, error)
                    }
                }
                val replacement = rewritten ?: return@intercept chain.proceed()
                val newArgs = chain.args.toTypedArray()
                newArgs[0] = replacement
                val result = chain.proceed(newArgs)
                result
            }
        hookAfter(setPlaybackState, "setPlaybackState") {
            val record = recordOf(it) ?: return@hookAfter
            publish(record, packageField, userField, metadataField, playbackField, contextField, lockField, pushMetadataUpdateMethod)
        }
        if (pushSessionDestroyedMethod != null) {
            hookAfter(pushSessionDestroyedMethod, "pushSessionDestroyed") {
                val record = it
                val sessionId = System.identityHashCode(record).toString(16)
                repository.drop(sessionId, SystemClock.elapsedRealtime())
                activeRecords.remove(sessionId)
                externalOverlays.remove(sessionId)
                artworkBySession.remove(sessionId)
                miguIdentities.remove(sessionId)
                trackBySession.remove(sessionId)?.let(::dropTrackState)
                scheduledIdentitySettle.remove(sessionId)
            }
        }
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = UniversalDiagnostics.COMPONENT,
                area = "bootstrap",
                event = "SYSTEM_SERVER_HOOKED",
                process = "system_server",
                reason = "record=${recordClass.name}"
            )
        )
    }

    private fun hookAfter(method: Method, id: String, block: (Any?) -> Unit) {
        hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("universal:$id")
            .intercept(AfterHooker(block))
    }

    private class AfterHooker(private val block: (Any?) -> Unit) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            block(chain.thisObject)
            return result
        }
    }

    private fun publish(
        stub: Any,
        packageField: Field?,
        userField: Field?,
        metadataField: Field?,
        playbackField: Field?,
        contextField: Field?,
        lockField: Field?,
        pushMetadataUpdateMethod: Method?
    ) {
        val record = stub
        val ownerPackage = packageField?.get(record) as? String ?: return
        cachedMetadataField = metadataField
        cachedLockField = lockField
        cachedPushMetadataUpdateMethod = pushMetadataUpdateMethod
        val context = (contextField?.get(record) as? Context) ?: currentSystemContext()
        context?.let(::ensureRequestReceiver)

        // GATE CHECK: If package is not in user's bound player list, completely ignore it.
        // This prevents video apps (Bilibili, YouTube) from being tracked or hijacking lyrics!
        if (!boundPackages.contains(ownerPackage)) {
            return
        }
        activeRecords[System.identityHashCode(record).toString(16)] = record

        val metadata = metadataField?.get(record) as? MediaMetadata
        val playback = playbackField?.get(record) as? PlaybackState
        val state = playback?.state
        val observation = SessionObservation(
            userId = (userField?.get(record) as? Int) ?: 0,
            ownerPackage = ownerPackage,
            sessionInstanceId = System.identityHashCode(record).toString(16),
            raw = RawSessionMetadata(
                mediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                title = metadata?.let { resolvedMetadataTitle(ownerPackage, observationId(record), it) },
                displayTitle = metadata?.let { resolvedMetadataDisplayTitle(ownerPackage, observationId(record), it) },
                artist = metadata?.let { resolvedMetadataArtist(ownerPackage, it) },
                albumArtist = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                displaySubtitle = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)
                ,playbackQueueItemId = playback?.activeQueueItemId?.takeIf { it >= 0L }
                ,playbackExtrasMediaId = playbackExtrasMediaId(playback)
                ,mediaUri = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
                ,positionMs = playback?.position
            ),
            playbackState = state,
            playing = PlaybackStates.isPlaying(state),
            observedAtElapsedMs = SystemClock.elapsedRealtime()
        )
        repository.setNotificationAccessGranted(true)
        repository.onListenerConnected()
        repository.upsert(observation, boundPackages)
        val recordSession = repository.get(observation.sessionInstanceId)
        if (metadata != null && recordSession != null) {
            rememberArtwork(observation.sessionInstanceId, recordSession.descriptor.trackGeneration, metadata)
        }
        val snapshot = publishSnapshot(context)

        if (recordSession != null) {
            UniversalDiagnostics.metadataObserved(recordSession, "after-setPlaybackState")
            noteIdentityGate(recordSession)
            syncSessionLyrics(record, recordSession)
            if (playback != null) observeClockQuality(record, recordSession, playback)
        }
        logSelection(snapshot)
    }

    /** Drops other generations' payloads, re-attaches the current one, and fetches when missing. */
    private fun syncSessionLyrics(record: Any, session: ResolvedSession) {
        val ownerPackage = session.descriptor.ownerPackage
        val currentGen = session.descriptor.trackGeneration
        rememberSessionTrack(session)
        if (providerMode != PROVIDER_MODE_BUILTIN) {
            if (usesExternalSubscriber()) {
                val key = "$ownerPackage|$currentGen"
                if (externalLyricsByTrack[key].isNullOrBlank()) latestExternalPublication?.let(::applyExternalPublication)
            }
            return
        }
        overlayMetadata(record, session, cachedMetadataField, cachedLockField, cachedPushMetadataUpdateMethod)

        val title = session.descriptor.title
        val existingPayload = realLyricsByPackage["$ownerPackage|$currentGen"]
        val retryUpgradedMiss = shouldRetryMiss(existingPayload, session.descriptor.artist, session.descriptor.durationMs)
        if ((existingPayload.isNullOrBlank() || retryUpgradedMiss) && !title.isNullOrBlank()) {
            if (retryUpgradedMiss) fetchBackoffUntil.remove("$ownerPackage|$currentGen")
            queueBackgroundFetch(
                ownerPackage,
                title,
                session.descriptor.artist.orEmpty(),
                session.descriptor.album,
                session.descriptor.durationMs,
                currentGen
            )
        }
    }

    @Synchronized
    private fun rememberSessionTrack(session: ResolvedSession) {
        val descriptor = session.descriptor
        val current = repository.get(descriptor.sessionInstanceId)?.descriptor ?: return
        if (current.trackGeneration != descriptor.trackGeneration) return
        val key = "${descriptor.ownerPackage}|${descriptor.trackGeneration}"
        val previous = trackBySession.put(descriptor.sessionInstanceId, key)
        if (previous != null && previous != key) {
            restoreExternalOverlay(descriptor.sessionInstanceId)
            dropTrackState(previous)
        }
    }

    private fun dropTrackState(key: String) {
        realLyricsByPackage.remove(key)
        nativeLyricsByTrack.remove(key)
        externalLyricsByTrack.remove(key)
        trackPayloads.remove(key)
        lookupIdentityByKey.remove(key)
        fetchBackoffUntil.remove(key)
    }

    private fun usesExternalSubscriber() = providerMode == PROVIDER_MODE_SUPER_LYRIC || providerMode == PROVIDER_MODE_LYRICON

    private fun ensureExternalReceiver(context: Context) {
        if (!usesExternalSubscriber()) {
            stopExternalReceiver()
            return
        }
        synchronized(externalProviderLock) {
            if (externalReceiverActive) return
            val selectedMode = providerMode
            val source = if (selectedMode == PROVIDER_MODE_LYRICON) {
                ExternalLyricsRelay.lyriconReceiver { it !in disabledLyriconProviders }
            } else SuperLyricServiceSource(moduleApplicationInfo.packageName, metadataReplayExecutor::execute) { status ->
                if (providerMode == PROVIDER_MODE_SUPER_LYRIC && superLyricConnection != status) {
                    superLyricConnection = status
                    publishSnapshot(null)
                }
            }
            val ticket = providerModeGeneration.incrementAndGet()
            val callbacks = object : ExternalLyricsCallbacks {
                override fun onActivePlayerChanged(packageName: String?) {
                    if (ticket != providerModeGeneration.get() || providerMode != selectedMode) return
                    val previous = activeExternalPackage
                    if (previous != null && previous != packageName) clearExternalPublication(previous)
                    activeExternalPackage = packageName
                }

                override fun onPublication(publication: ExternalLyricsPublication) {
                    if (ticket != providerModeGeneration.get() || providerMode != selectedMode) return
                    latestExternalPublication = publication
                    applyExternalPublication(publication, ticket)
                }

                override fun onStopped(packageName: String?) {
                    if (ticket != providerModeGeneration.get() || providerMode != selectedMode) return
                    if (packageName == null || latestExternalPublication?.playerPackage == packageName) {
                        latestExternalPublication = null
                    }
                    if (selectedMode == PROVIDER_MODE_SUPER_LYRIC) superLyricDelivery = "stopped"
                    clearExternalPublication(packageName)
                }
            }
            runCatching {
                source.start(context, callbacks)
                externalSource = source
                externalReceiverActive = true
            }.onFailure {
                externalSource = null
                externalReceiverActive = false
                StructuredDiagnostics.logWarning(
                    DiagnosticEvent(
                        component = UniversalDiagnostics.COMPONENT,
                        area = "external",
                        event = "SUBSCRIBER_REGISTER_FAILED",
                        reason = it.javaClass.simpleName
                    )
                )
                if (selectedMode == PROVIDER_MODE_SUPER_LYRIC) {
                    superLyricConnection = "startup_failed"
                    publishSnapshot(context)
                }
                scheduleSuperLyricRetry(context)
            }
        }
    }

    private fun scheduleSuperLyricRetry(context: Context) {
        if (!superLyricRetryScheduled.compareAndSet(false, true)) return
        metadataReplayExecutor.schedule({
            superLyricRetryScheduled.set(false)
            if (!usesExternalSubscriber()) return@schedule
            if (externalSource == null) ensureExternalReceiver(context)
        }, 5_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun stopExternalReceiver() {
        synchronized(externalProviderLock) {
            providerModeGeneration.incrementAndGet()
            externalSource?.stop()
            externalSource = null
            externalReceiverActive = false
            activeExternalPackage = null
            latestExternalPublication = null
            superLyricConnection = "idle"
            superLyricDelivery = "waiting_for_lyrics"
            externalLyricsByTrack.clear()
            externalOverlays.keys.toList().forEach { restoreExternalOverlay(it) }
            externalOverlays.clear()
        }
    }

    private fun onProviderModeChanged(context: Context) {
        publishCapsuleLyricMode(context)
        stopExternalReceiver()
        if (providerMode != PROVIDER_MODE_BUILTIN) {
            clearBuiltinOverlays()
            realLyricsByPackage.clear()
        }
        if (usesExternalSubscriber()) {
            metadataReplayExecutor.execute { ensureExternalReceiver(context) }
        }
        repository.snapshot().sessions.forEach { session ->
            activeRecords[session.descriptor.sessionInstanceId]?.let { record ->
                syncSessionLyrics(record, session)
            }
        }
    }

    private fun publishCapsuleLyricMode(context: Context) {
        runCatching { CapsuleLyricModeSync.publish(context, providerMode == PROVIDER_MODE_SUPER_LYRIC) }
            .onFailure { log(Log.WARN, "Color lyric", "Could not publish capsule lyric mode", it) }
    }

    private fun reportSuperLyricDelivery(status: String) {
        if (providerMode != PROVIDER_MODE_SUPER_LYRIC || superLyricDelivery == status) return
        superLyricDelivery = status
        publishSnapshot(null)
    }

    private fun applyExternalPublication(
        publication: ExternalLyricsPublication,
        generation: Long = providerModeGeneration.get(),
    ) {
        val snapshot = repository.snapshot()
        val selected = snapshot.selected ?: run {
            reportSuperLyricDelivery("no_selected_session")
            return
        }
        if (!ExternalLyricsTrackMatcher.matches(
                selected.descriptor.ownerPackage,
                selected.descriptor.title,
                selected.descriptor.artist,
                selected.descriptor.durationMs,
                publication
            )) {
            reportSuperLyricDelivery("track_mismatch")
            return
        }
        val descriptor = selected.descriptor
        val previous = externalOverlays[descriptor.sessionInstanceId]
        if (previous != null && previous.trackKey != "${descriptor.ownerPackage}|${descriptor.trackGeneration}") {
            restoreExternalOverlay(descriptor.sessionInstanceId)
        }
        val hostTrack = TrackIdentity(
            id = publication.track.id,
            title = descriptor.title,
            artist = descriptor.artist?.takeIf(String::isNotBlank) ?: publication.track.artist,
            album = descriptor.album,
            durationMs = descriptor.durationMs ?: 0L,
        )
        val encoded = ColorOSLyricJsonEncoder.encode(
            hostTrack,
            publication.lines,
            descriptor.trackGeneration,
            descriptor.ownerPackage,
        ) ?: run {
            reportSuperLyricDelivery("encoding_rejected")
            return
        }
        val key = "${descriptor.ownerPackage}|${descriptor.trackGeneration}"
        val record = activeRecords[descriptor.sessionInstanceId] ?: run {
            reportSuperLyricDelivery("no_media_record")
            return
        }
        val payload = JSONObject(encoded.jsonValue).apply {
            put("source", if (providerMode == PROVIDER_MODE_LYRICON) "lyricon" else "superlyric")
            put("provider", publication.playerPackage)
            put("sessionGeneration", descriptor.trackGeneration)
            put("songId", "superlyric-${descriptor.trackGeneration}")
        }
        applyWordTimingPolicy(payload, descriptor.ownerPackage)
        val published = payload.toString()
        val metadataKey = LyricPayload.metadataKey(providerMode == PROVIDER_MODE_SUPER_LYRIC)
        val lock = cachedLockField?.get(record) ?: record
        var delivery = "metadata_unavailable"
        val changed = synchronized(lock) {
            if (providerModeGeneration.get() != generation || !usesExternalSubscriber() ||
                !isCurrentSession(record, selected)) return@synchronized false
            val metadata = cachedMetadataField?.get(record) as? MediaMetadata ?: return@synchronized false
            val current = metadata.getString(metadataKey)
            if (current == published) {
                delivery = "published"
                return@synchronized false
            }
            val original = if (previous?.metadataKey == metadataKey && previous.trackKey == key &&
                current == previous.publishedLyricInfo) {
                previous.originalLyricInfo
            } else current
            val candidate = MediaMetadata.Builder(metadata).putString(metadataKey, published).build()
            val parcelBytes = MetadataParcelGuard.measureParcelBytes(candidate)
            val assessment = MetadataParcelGuard.assessSizes(published.length, parcelBytes)
            if (assessment != MetadataParcelGuard.Result.SAFE) {
                delivery = assessment.name.lowercase()
                return@synchronized false
            }
            externalLyricsByTrack[key] = published
            realLyricsByPackage[key] = published
            cachedMetadataField?.set(record, candidate)
            externalOverlays[descriptor.sessionInstanceId] = ExternalOverlayState(
                descriptor.ownerPackage, descriptor.sessionInstanceId, key, metadataKey, original, published
            )
            true
        }
        if (!changed) {
            if (providerModeGeneration.get() == generation) reportSuperLyricDelivery(delivery)
            return
        }
        cachedPushMetadataUpdateMethod?.invoke(record)
        if (providerMode == PROVIDER_MODE_SUPER_LYRIC) superLyricDelivery = "published"
        publishSnapshot(null)
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = UniversalDiagnostics.COMPONENT,
                area = "external",
                event = "EXTERNAL_LYRIC_PUBLISHED",
                session = descriptor.sessionInstanceId,
                generation = descriptor.trackGeneration,
                reason = "source=$providerMode generation=$generation"
            )
        )
    }

    private fun clearExternalPublication(packageName: String?) {
        externalOverlays.values.filter { packageName.isNullOrBlank() || it.ownerPackage == packageName }
            .map { it.sessionId }
            .forEach { restoreExternalOverlay(it) }
        if (packageName.isNullOrBlank() || packageName == activeExternalPackage) activeExternalPackage = null
        publishSnapshot(null)
    }

    private fun restoreExternalOverlay(sessionId: String) {
        val state = externalOverlays.remove(sessionId) ?: return
        externalLyricsByTrack.remove(state.trackKey)
        realLyricsByPackage.remove(state.trackKey)
        val record = activeRecords[sessionId] ?: return
        val lock = cachedLockField?.get(record) ?: record
        val changed = synchronized(lock) {
            val current = cachedMetadataField?.get(record) as? MediaMetadata ?: return@synchronized false
            if (current.getString(state.metadataKey) != state.publishedLyricInfo) return@synchronized false
            cachedMetadataField?.set(
                record,
                MediaMetadata.Builder(current).putString(state.metadataKey, state.originalLyricInfo).build()
            )
            true
        }
        if (changed) cachedPushMetadataUpdateMethod?.invoke(record)
    }

    private fun clearBuiltinOverlays() {
        repository.snapshot().sessions.forEach { session ->
            val descriptor = session.descriptor
            val key = "${descriptor.ownerPackage}|${descriptor.trackGeneration}"
            val record = activeRecords[descriptor.sessionInstanceId] ?: return@forEach
            val current = (cachedMetadataField?.get(record) as? MediaMetadata) ?: return@forEach
            val lyric = current.getString("lyricInfo") ?: return@forEach
            val modulePayload = realLyricsByPackage[key]
            val suppressedNative = lyric.isEmpty() && nativeLyricsByTrack.containsKey(key)
            if (lyric != modulePayload && !suppressedNative) return@forEach
            val original = nativeLyricsByTrack[key]
            val builder = MediaMetadata.Builder(current)
            builder.putString("lyricInfo", original.orEmpty())
            cachedMetadataField?.set(record, builder.build())
            cachedPushMetadataUpdateMethod?.invoke(record)
        }
    }

    private fun logSelection(snapshot: UniversalMonitorSnapshot) {
        val selected = snapshot.selected ?: return
        if (lastSelectedInstanceId != selected.descriptor.sessionInstanceId) {
            UniversalDiagnostics.sessionSelected(selected, snapshot.selectionRevision)
            lastSelectedInstanceId = selected.descriptor.sessionInstanceId
            lastGeneration = selected.descriptor.trackGeneration
        } else if (lastGeneration != selected.descriptor.trackGeneration) {
            UniversalDiagnostics.trackChanged(selected)
            lastGeneration = selected.descriptor.trackGeneration
        }
    }

    private fun observationId(record: Any): String = System.identityHashCode(record).toString(16)

    private fun resolvedMetadataArtist(ownerPackage: String, metadata: MediaMetadata): String? {
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        return if (ownerPackage == "cmccwm.mobilemusic" && artist.isNullOrBlank())
            metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) else artist
    }

    private fun resolvedMetadataTitle(ownerPackage: String, sessionId: String, metadata: MediaMetadata): String? {
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (ownerPackage != "cmccwm.mobilemusic") return title
        return miguIdentities.computeIfAbsent(sessionId) { MiguMediaIdentity() }.resolve(
            ownerPackage, title, resolvedMetadataArtist(ownerPackage, metadata),
            metadata.getLong(MediaMetadata.METADATA_KEY_DURATION), metadata.getString("lyricInfo"),
            metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
        )
    }

    private fun resolvedMetadataDisplayTitle(ownerPackage: String, sessionId: String, metadata: MediaMetadata): String? {
        val raw = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val resolved = resolvedMetadataTitle(ownerPackage, sessionId, metadata)
        return if (ownerPackage == "cmccwm.mobilemusic" && resolved != raw) resolved
        else metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
    }

    /**
     * A held partial identity change (see [TrackIdentityResolver]) must still land when the player
     * sends no further callback, so re-observe the stored payload once its settle window ends.
     */
    private fun noteIdentityGate(session: ResolvedSession) {
        if (session.identityGate != TrackIdentityResolver.GATE_STANDARD) {
            UniversalDiagnostics.identityGate(session)
        }
        val settleAt = session.identitySettleAtElapsedMs ?: return
        val sessionId = session.descriptor.sessionInstanceId
        if (scheduledIdentitySettle.put(sessionId, settleAt) == settleAt) return
        val delayMs = (settleAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L) + 50L
        metadataReplayExecutor.schedule(
            { settleIdentity(session.descriptor.ownerPackage, sessionId, settleAt) },
            delayMs,
            java.util.concurrent.TimeUnit.MILLISECONDS
        )
    }

    private fun settleIdentity(ownerPackage: String, sessionId: String, settleAt: Long) {
        runCatching {
            scheduledIdentitySettle.remove(sessionId, settleAt)
            val latest = repository.get(sessionId) ?: return
            // A newer payload already resolved or restarted the hold.
            if (latest.identitySettleAtElapsedMs != settleAt) return
            repository.upsert(latest.observation.copy(observedAtElapsedMs = SystemClock.elapsedRealtime()), boundPackages)
            val settled = repository.get(sessionId) ?: return
            noteIdentityGate(settled)
            activeRecords[sessionId]?.let { syncSessionLyrics(it, settled) }
            logSelection(publishSnapshot(null))
        }.onFailure { error -> UniversalDiagnostics.identitySettleFailed(ownerPackage, error) }
    }

    private fun observeClockQuality(record: Any, session: ResolvedSession, playback: PlaybackState) {
        val ownerPackage = session.descriptor.ownerPackage
        val before = clockQuality.verdict(ownerPackage)
        val after = clockQuality.observe(
            ownerPackage = ownerPackage,
            sessionId = session.descriptor.sessionInstanceId,
            generation = session.descriptor.trackGeneration,
            state = playback.state,
            positionMs = playback.position,
            updateTimeMs = playback.lastPositionUpdateTime,
            speed = playback.playbackSpeed
        )
        if (after == before) return
        UniversalDiagnostics.clockQualityChanged(ownerPackage, before.name, clockQuality.stats(ownerPackage))
        // Downgrade the current track at once; a steadier clock returns to word timing from the
        // next track, so a verdict change cannot flip one track back and forth.
        if (after == PlaybackClockQuality.Verdict.COARSE && clockLineFallbackEnabled && wordTimingEnabled) {
            repository.snapshot().sessions.filter { it.descriptor.ownerPackage == ownerPackage }.forEach { active ->
                val key = "$ownerPackage|${active.descriptor.trackGeneration}"
                trackPayloads.refresh(key) { original -> realLyricsByPackage[key] = publishedPayload(original, ownerPackage) }
            }
            overlayMetadata(record, session, cachedMetadataField, cachedLockField, cachedPushMetadataUpdateMethod, force = true)
        }
        publishSnapshot(null)
    }

    private fun clockLineFallbackActive(ownerPackage: String): Boolean =
        clockLineFallbackEnabled && clockQuality.verdict(ownerPackage) == PlaybackClockQuality.Verdict.COARSE

    private fun enrichMetadataBeforeSet(
        record: Any,
        ownerPackage: String,
        metadata: MediaMetadata,
        metadataDescription: String?,
        userField: Field?,
        playbackField: Field?,
        contextField: Field?
    ): MediaMetadata? {
        val playback = playbackField?.get(record) as? PlaybackState
        val state = playback?.state
        val observation = SessionObservation(
            userId = (userField?.get(record) as? Int) ?: 0,
            ownerPackage = ownerPackage,
            sessionInstanceId = System.identityHashCode(record).toString(16),
            raw = RawSessionMetadata(
                mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                title = resolvedMetadataTitle(ownerPackage, observationId(record), metadata),
                displayTitle = resolvedMetadataDisplayTitle(ownerPackage, observationId(record), metadata),
                artist = resolvedMetadataArtist(ownerPackage, metadata),
                albumArtist = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                displaySubtitle = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
                durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
                ,playbackQueueItemId = playback?.activeQueueItemId?.takeIf { it >= 0L }
                ,playbackExtrasMediaId = playbackExtrasMediaId(playback)
                ,mediaUri = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
                ,positionMs = playback?.position
            ),
            playbackState = state,
            playing = PlaybackStates.isPlaying(state),
            observedAtElapsedMs = SystemClock.elapsedRealtime()
        )
        repository.setNotificationAccessGranted(true)
        repository.onListenerConnected()
        repository.upsert(observation, boundPackages)

        val context = (contextField?.get(record) as? Context) ?: currentSystemContext()
        context?.let(::ensureRequestReceiver)

        val recordSession = repository.get(observation.sessionInstanceId)
        if (recordSession == null) {
            publishSnapshot(context)
            return null
        }
        UniversalDiagnostics.metadataObserved(recordSession, "before-setMetadata")
        noteIdentityGate(recordSession)
        val currentGen = recordSession.descriptor.trackGeneration
        rememberArtwork(observation.sessionInstanceId, currentGen, metadata)

        rememberSessionTrack(recordSession)

        // During Salt cold start the resolver can temporarily reject the
        // stored title as Bluetooth lyric churn even though this incoming
        // metadata already contains the clean track identity. Use it as a
        // guarded lookup fallback so the first song does not require a skip.
        val descriptionParts = metadataDescription?.split(",").orEmpty().map { it.trim() }
        val descriptionTitle = descriptionParts.firstOrNull()
            ?.takeIf { it.isNotEmpty() && !BluetoothLyricDemux.looksLikeLyricTitle(it) }
        val descriptionArtist = descriptionParts.getOrNull(1)?.takeIf { it.isNotEmpty() }
        val incomingTitle = resolvedMetadataTitle(ownerPackage, observationId(record), metadata)
            ?.trim()?.takeIf { it.isNotEmpty() && !BluetoothLyricDemux.looksLikeLyricTitle(it) }
        val incomingArtist = resolvedMetadataArtist(ownerPackage, metadata)
            ?.trim()?.takeIf { it.isNotEmpty() }
        val recovered = if (recordSession.descriptor.title == null && incomingTitle == null) {
            recoverIdentityFromCache(
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                recordSession.descriptor.artist ?: incomingArtist,
                recordSession.descriptor.album ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
                recordSession.descriptor.durationMs
            )
        } else null
        val title = recordSession.descriptor.title ?: incomingTitle ?: recovered?.first ?: descriptionTitle
        val artist = recordSession.descriptor.artist ?: incomingArtist ?: descriptionArtist
        val album = recordSession.descriptor.album
        val durationMs = recordSession.descriptor.durationMs
        val cacheKey = "$ownerPackage|$currentGen"
        val hostLyric = metadata.getString("lyricInfo")
        val hasTimedHostLyric = isTimedHostLyric(hostLyric, title, artist)
        if (hasTimedHostLyric) {
            // Preserve the exact incoming value for restoring the host on a mode switch.
            nativeLyricsByTrack[cacheKey] = hostLyric!!
        } else if (hostLyric != null && UniversalMetadataOverlayPolicy.shouldPreserveExisting(hostLyric, false)) {
            nativeLyricsByTrack.remove(cacheKey)
        }
        if (providerMode != PROVIDER_MODE_BUILTIN) {
            val publication = latestExternalPublication
            val metadataKey = LyricPayload.metadataKey(providerMode == PROVIDER_MODE_SUPER_LYRIC)
            val previous = externalOverlays[observation.sessionInstanceId]
            val external = previous?.takeIf { it.metadataKey == metadataKey && it.trackKey == cacheKey }
                ?.publishedLyricInfo
            val isSelected = repository.snapshot().selected?.descriptor?.sessionInstanceId == observation.sessionInstanceId
            if (usesExternalSubscriber() && isSelected && publication != null && external != null &&
                ExternalLyricsTrackMatcher.matches(ownerPackage, incomingTitle, incomingArtist, durationMs, publication) &&
                ExternalLyricsTrackMatcher.matches(ownerPackage, recordSession.descriptor.title,
                    recordSession.descriptor.artist, durationMs, publication)) {
                val candidate = MediaMetadata.Builder(metadata).putString(metadataKey, external).build()
                if (MetadataParcelGuard.assessSizes(external.length, MetadataParcelGuard.measureParcelBytes(candidate)) !=
                    MetadataParcelGuard.Result.SAFE) return null
                val incoming = metadata.getString(metadataKey)
                val original = if (previous?.metadataKey == metadataKey && incoming == previous.publishedLyricInfo) {
                    previous.originalLyricInfo
                } else incoming
                externalOverlays[observation.sessionInstanceId] = ExternalOverlayState(
                    ownerPackage, observation.sessionInstanceId, cacheKey, metadataKey, original, external
                )
                return candidate
            }
            return null
        }
        val manualKey = if (title.isNullOrBlank()) "" else bindingKey(title, artist.orEmpty())
        val manual = trackPayloads.withLock {
            val saved = if (manualKey.isEmpty()) null else loadManualBinding(manualKey)
            if (!saved.isNullOrBlank()) {
                trackPayloads.accept(cacheKey, saved, isManual = true) {
                    realLyricsByPackage[cacheKey] = updateGeneration(it, currentGen, ownerPackage)
                }
            } else if (trackPayloads.isManual(cacheKey)) {
                trackPayloads.clearManual(cacheKey)
                realLyricsByPackage.remove(cacheKey)
            }
            saved
        }
        if (recordSession.descriptor.title == null && !title.isNullOrBlank()) {
            lookupIdentityByKey[cacheKey] = title to artist
        }

        var realJson = realLyricsByPackage[cacheKey]
        val retryUpgradedMiss = shouldRetryMiss(realJson, artist, durationMs)
        if ((realJson.isNullOrBlank() || retryUpgradedMiss) && !title.isNullOrBlank()) {
            if (retryUpgradedMiss) fetchBackoffUntil.remove(cacheKey)
            if (!manual.isNullOrBlank()) {
                trackPayloads.accept(cacheKey, manual, isManual = true) {
                    val published = updateGeneration(it, currentGen, ownerPackage)
                    realJson = published
                    realLyricsByPackage[cacheKey] = published
                }
            } else {
                val cached = loadCachedLyric(title, artist.orEmpty())
                // An automatically missed track is retried; only a real lyric or a user decision wins.
                if (!cached.isNullOrBlank() && UniversalCacheSourcePolicy.canUse(cached, directMode = false) &&
                    !NoLyricPolicy.isRetryableMiss(cached)) {
                    trackPayloads.accept(cacheKey, cached, isManual = false) {
                        val published = updateGeneration(it, currentGen, ownerPackage)
                        realJson = published
                        realLyricsByPackage[cacheKey] = published
                    }
                } else {
                    queueBackgroundFetch(ownerPackage, title, artist.orEmpty(), album, durationMs, currentGen)
                }
            }
        }
        // Publish after the lookup so a cache hit is not reported as pending.
        publishSnapshot(context)

        if (!realJson.isNullOrBlank() && !NoLyricPolicy.isRetryableMiss(realJson)) {
            UniversalDiagnostics.lyricInfoAttached(ownerPackage, currentGen, "metadata-rewrite")
            return MediaMetadata.Builder(metadata).putString("lyricInfo", realJson).build()
        }

        return if (hasTimedHostLyric) {
            MediaMetadata.Builder(metadata).putString("lyricInfo", "").build()
        } else null
    }

    private fun overlayMetadata(
        record: Any,
        session: ResolvedSession,
        metadataField: Field?,
        lockField: Field?,
        pushMetadataUpdateMethod: Method?,
        force: Boolean = false
    ) {
        if (isOverriding.get() == true || providerMode != PROVIDER_MODE_BUILTIN) return
        val ownerPackage = session.descriptor.ownerPackage
        if (!boundPackages.contains(ownerPackage)) {
            return
        }
        val gen = session.descriptor.trackGeneration
        try {
            isOverriding.set(true)
            val lock = lockField?.get(record) ?: record
            val bytes = synchronized(lock) {
                if (providerMode != PROVIDER_MODE_BUILTIN || !isCurrentSession(record, session)) return@synchronized 0
                val realJson = realLyricsByPackage["$ownerPackage|$gen"]
                if (realJson.isNullOrBlank() || NoLyricPolicy.isRetryableMiss(realJson)) return@synchronized 0
                val current = metadataField?.get(record) as? MediaMetadata ?: return@synchronized 0
                val existing = current.getString("lyricInfo")
                if (existing == realJson || UniversalMetadataOverlayPolicy.shouldPreserveExisting(existing, force) && existing != nativeLyricsByTrack["$ownerPackage|$gen"]) return@synchronized 0
                metadataField.set(record, MediaMetadata.Builder(current).putString("lyricInfo", realJson).build())
                realJson.length
            }
            if (bytes == 0) return
            pushMetadataUpdateMethod?.invoke(record)

            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = UniversalDiagnostics.COMPONENT,
                    area = "overlay",
                    event = "LYRIC_OVERLAY_INJECTED",
                    session = session.descriptor.sessionInstanceId,
                    generation = session.descriptor.trackGeneration,
                    reason = "bytes=$bytes"
                )
            )
        } catch (t: Throwable) {
            StructuredDiagnostics.logWarning(
                DiagnosticEvent(
                    component = UniversalDiagnostics.COMPONENT,
                    area = "overlay",
                    event = "LYRIC_OVERLAY_FAILED",
                    session = session.descriptor.sessionInstanceId,
                    generation = session.descriptor.trackGeneration,
                    reason = t.message
                )
            )
        } finally {
            isOverriding.set(false)
        }
    }


    private fun ensureRequestReceiver(context: Context) {
        io.github.andrealtb.coloroslyrics.provider.runtimediagnostics.ProviderRuntimeDiagnostics.register(context)
        if (receiverRegistered) return
        synchronized(this) {
            if (receiverRegistered) return
            try {
                val filter = IntentFilter().apply {
                    addAction(UniversalSnapshotStore.ACTION_REQUEST_SNAPSHOT)
                    addAction(UniversalSnapshotStore.ACTION_UPDATE_PLAYER_BINDINGS)
                    addAction(UniversalSnapshotStore.ACTION_INJECT_REAL_LYRIC)
                    addAction(UniversalSnapshotStore.ACTION_UPDATE_SOURCE_CONFIG)
                    addAction(UniversalSnapshotStore.ACTION_CLEAR_CACHE)
                    addAction(UniversalSnapshotStore.ACTION_CACHE_REQUEST)
                    addAction(UniversalFeatureConfig.ACTION_UPDATE)
                }
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        try {
                            when (intent?.action) {
                                UniversalSnapshotStore.ACTION_CACHE_REQUEST -> {
                                    val request = Intent(intent)
                                    configExecutor.execute { handleCacheRequest(context, request) }
                                }
                                UniversalSnapshotStore.ACTION_REQUEST_SNAPSHOT -> {
                                    publishSnapshot(context, force = true)
                                }
                                UniversalFeatureConfig.ACTION_UPDATE -> {
                                    if (UniversalFeatureConfig.consume(intent)) {
                                        searchConfigRevision.incrementAndGet()
                                        retryMissedTracks()
                                    }
                                    publishSnapshot(context, force = true)
                                }
                                UniversalSnapshotStore.ACTION_UPDATE_PLAYER_BINDINGS -> {
                                    val list = intent.getStringArrayListExtra(UniversalSnapshotStore.EXTRA_BOUND_PACKAGES)
                                    if (list != null) {
                                        boundPackages = PlayerBindingPolicy.sanitize(list.toSet())
                                        externalOverlays.values
                                            .filter { it.ownerPackage !in boundPackages }
                                            .forEach { restoreExternalOverlay(it.sessionId) }
                                        persistBoundPackages(boundPackages)
                                        repository.updateBoundFilter(boundPackages, SystemClock.elapsedRealtime())
                                        publishSnapshot(context)
                                        StructuredDiagnostics.logInfo(
                                            DiagnosticEvent(
                                                component = UniversalDiagnostics.COMPONENT,
                                                area = "bindings",
                                                event = "PLAYER_BINDINGS_UPDATED",
                                                reason = "count=${boundPackages.size}"
                                            )
                                        )
                                    } else {
                                        StructuredDiagnostics.logWarning(
                                            DiagnosticEvent(
                                                component = UniversalDiagnostics.COMPONENT,
                                                area = "bindings",
                                                event = "PLAYER_BINDINGS_UPDATE_EMPTY",
                                                reason = "missing extra_bound_packages"
                                            )
                                        )
                                    }
                                }
                                UniversalSnapshotStore.ACTION_INJECT_REAL_LYRIC -> {
                                    val pkg = intent.getStringExtra(UniversalSnapshotStore.EXTRA_OWNER_PACKAGE) ?: return
                                    val gen = intent.getLongExtra(UniversalSnapshotStore.EXTRA_GENERATION, -1L)
                                    val lyricInfo = intent.getStringExtra(UniversalSnapshotStore.EXTRA_LYRIC_INFO) ?: return
                                    val isManual = intent.getBooleanExtra("extra_is_manual", false)
                                    val trackKey = intent.getStringExtra("extra_track_key")
                                    if (isManual && !trackKey.isNullOrBlank()) {
                                        if (lyricInfo.length > 262_144) return
                                        val payload = runCatching { JSONObject(lyricInfo) }.getOrNull() ?: return
                                        val title = payload.optString("songName")
                                        val artist = payload.optString("artist")
                                        if (title.isBlank() || trackKey != bindingKey(title, artist) ||
                                            LyricPayload.inspect(lyricInfo, title, false, artist).timeline == null) return
                                        val saved = trackPayloads.withLock {
                                            saveManualBinding(trackKey, lyricInfo)
                                            if (loadManualBinding(trackKey) != lyricInfo) false else {
                                                saveCachedLyric(title, artist, lyricInfo)
                                                true
                                            }
                                        }
                                        if (saved) applyManualToActiveTrack(title, artist, lyricInfo)
                                        publishSnapshot(context)
                                        sendCacheResponse(context, Intent(UniversalSnapshotStore.ACTION_CACHE_RESPONSE).apply {
                                            setPackage(moduleApplicationInfo.packageName)
                                            putExtra(UniversalSnapshotStore.EXTRA_CACHE_OPERATION, "bind")
                                            putExtra(UniversalSnapshotStore.EXTRA_CACHE_OK, saved)
                                        })
                                        return
                                    }
                                    val cacheKey = if (gen > 0) "$pkg|$gen" else pkg
                                    trackPayloads.accept(cacheKey, lyricInfo, isManual = false) {
                                        realLyricsByPackage[cacheKey] = publishedPayload(it, pkg)
                                    }
                                    val activeSession = repository.snapshot().sessions.find {
                                        it.descriptor.ownerPackage == pkg && (gen <= 0 || it.descriptor.trackGeneration == gen)
                                    }
                                    val activeRecord = activeSession?.let { activeRecords[it.descriptor.sessionInstanceId] }
                                    if (activeRecord != null && activeSession != null) {
                                        overlayMetadata(activeRecord, activeSession, cachedMetadataField, cachedLockField, cachedPushMetadataUpdateMethod, force = true)
                                    }
                                    publishSnapshot(context)
                                }
                                UniversalSnapshotStore.ACTION_UPDATE_SOURCE_CONFIG -> {
                                    val encoded = intent.getStringExtra(UniversalSnapshotStore.EXTRA_SOURCE_PRIORITY) ?: return
                                    val priority = LyricsSourceConfigStore.decode(encoded).mapNotNull {
                                        runCatching { io.github.andrealtb.coloroslyrics.provider.universal.api.LyricsSourceAggregator.SourceId.valueOf(it) }.getOrNull()
                                    }
                                    val wordTiming = intent.getBooleanExtra(UniversalSnapshotStore.EXTRA_WORD_TIMING_ENABLED, wordTimingEnabled)
                                    val translation = intent.getBooleanExtra(UniversalSnapshotStore.EXTRA_TRANSLATION_ENABLED, translationEnabled)
                                    val autoTranslate = intent.getBooleanExtra(UniversalSnapshotStore.EXTRA_AUTO_TRANSLATE_ENABLED, autoTranslateEnabled)
                                    val requestedAutoTranslateLang = intent.getStringExtra(UniversalSnapshotStore.EXTRA_AUTO_TRANSLATE_LANG)
                                        ?.takeIf { it.isNotBlank() } ?: autoTranslateLang
                                    val rawLyric = intent.getBooleanExtra(UniversalSnapshotStore.EXTRA_RAW_LYRIC_ENABLED, rawLyricEnabled)
                                    val debug = intent.getBooleanExtra(UniversalSnapshotStore.EXTRA_DEBUG_ENABLED, debugEnabled)
                                    val requestedProviderMode = normalizeProviderMode(
                                        intent.getStringExtra(UniversalSnapshotStore.EXTRA_PROVIDER_MODE) ?: providerMode
                                    )
                                    val clockFallback = intent.getBooleanExtra(
                                        UniversalSnapshotStore.EXTRA_CLOCK_LINE_FALLBACK_ENABLED,
                                        clockLineFallbackEnabled
                                    )
                                    val priorityChanged = priority != sourcePriority
                                    val wordTimingChanged = wordTiming != wordTimingEnabled
                                    val translationChanged = translation != translationEnabled
                                    val autoTranslateChanged = autoTranslate != autoTranslateEnabled
                                    val autoTranslateLangChanged = requestedAutoTranslateLang != autoTranslateLang
                                    val rawLyricChanged = rawLyric != rawLyricEnabled
                                    val debugChanged = debug != debugEnabled
                                    val providerChanged = requestedProviderMode != providerMode
                                    val disabled = intent.getStringArrayListExtra("disabled_lyricon_providers")?.toSet()
                                        ?: disabledLyriconProviders
                                    val lyriconChanged = disabled != disabledLyriconProviders
                                    disabledLyriconProviders = disabled
                                    val clockFallbackChanged = clockFallback != clockLineFallbackEnabled
                                    sourcePriority = priority
                                    wordTimingEnabled = wordTiming
                                    translationEnabled = translation
                                    autoTranslateEnabled = autoTranslate
                                    autoTranslateLang = requestedAutoTranslateLang
                                    rawLyricEnabled = rawLyric
                                    debugEnabled = debug
                                    clockLineFallbackEnabled = clockFallback
                                    if (providerChanged) {
                                        synchronized(externalProviderLock) {
                                            providerModeGeneration.incrementAndGet()
                                            providerMode = requestedProviderMode
                                        }
                                    }
                                    if (priorityChanged || wordTimingChanged || providerChanged) searchConfigRevision.incrementAndGet()
                                    if (debugChanged) {
                                        StructuredDiagnostics.configure(debugEnabled = debug)
                                    }
                                    configExecutor.execute {
                                        if (priorityChanged) persistSourceConfig(priority)
                                        if (wordTimingChanged) persistWordTimingEnabled(wordTiming)
                                        if (translationChanged) persistTranslationEnabled(translation)
                                        if (autoTranslateChanged) persistAutoTranslateEnabled(autoTranslate)
                                        if (autoTranslateLangChanged) persistAutoTranslateLang(requestedAutoTranslateLang)
                                        if (rawLyricChanged) persistRawLyricEnabled(rawLyric)
                                        if (debugChanged) persistDebugEnabled(debug)
                                        if (providerChanged) persistProviderMode(requestedProviderMode)
                                        if (lyriconChanged) runCatching {
                                            java.io.File("/data/system/colorlyric_disabled_lyricon_providers.txt")
                                                .writeText(disabled.sorted().joinToString("\n"))
                                        }
                                        if (clockFallbackChanged) persistClockLineFallbackEnabled(clockFallback)
                                    }
                                    if (providerChanged || (lyriconChanged && providerMode == PROVIDER_MODE_LYRICON)) {
                                        onProviderModeChanged(context)
                                    }
                                    io.github.andrealtb.coloroslyrics.provider.runtimediagnostics.ProviderRuntimeDiagnostics.configConsumed(
                                        debugEnabled, runCatching { intent.getLongExtra(UniversalSnapshotStore.EXTRA_CONFIG_REVISION, -1L) }.getOrDefault(-1L), "CONSUMED")
                                    if (priorityChanged || providerChanged) retryMissedTracks()
                                    if (wordTimingChanged || rawLyricChanged || translationChanged || autoTranslateChanged || autoTranslateLangChanged || clockFallbackChanged) {
                                        // Reload the complete saved payload for upgrades as well as downgrades.
                                        backgroundExecutor.execute(::refreshProcessingSettings)
                                    }
                                    UniversalDiagnostics.sourceConfigUpdated(sourcePriority.map { it.name })
                                    // The home screen shows whether the current player is on the clock fallback.
                                    publishSnapshot(context)
                                }
                                UniversalSnapshotStore.ACTION_CLEAR_CACHE -> {
                                    val cleared = UniversalLyricCache.clear()
                                    // Drop negative and missed entries so they can retry, while keeping an
                                    // already-published lyric for the active generation from immediately
                                    // re-fetching on the next 1s playback tick and re-populating the cache.
                                    realLyricsByPackage.entries
                                        .filter { (_, value) ->
                                            runCatching {
                                                val obj = JSONObject(value)
                                                obj.optBoolean("noLyric", false) || obj.optString("lyric").isBlank()
                                            }.getOrDefault(true)
                                        }
                                        .forEach { entry -> realLyricsByPackage.remove(entry.key) }
                                    fetchBackoffUntil.clear()
                                    synchronized(snapshotLock) {
                                        val snap = latestSnapshotText ?: format(null, 0, 0)
                                        val updated = upsertCachedSongs(snap)
                                        latestSnapshotText = updated
                                        (c ?: context ?: currentSystemContext())?.let { ctx ->
                                            sendSnapshotBroadcast(ctx, updated, cacheCleared = cleared.songs, force = true)
                                        }
                                    }
                                }
                            }
                        } catch (error: Exception) {
                            StructuredDiagnostics.logWarning(DiagnosticEvent(
                                component = UniversalDiagnostics.COMPONENT,
                                area = "commands",
                                event = "COMMAND_FAILED",
                                reason = error.javaClass.simpleName,
                            ))
                        }
                    }
                }
                context.registerReceiver(receiver, filter,
                    moduleApplicationInfo.packageName + ".permission.CAPSULE_SETTINGS",
                    null, Context.RECEIVER_EXPORTED)
                receiverRegistered = true
                runCatching {
                    CapsuleLyricModeSync.registerSystem(context) { providerMode == PROVIDER_MODE_SUPER_LYRIC }
                }.onFailure { log(Log.WARN, "Color lyric", "Could not register capsule lyric mode sync", it) }
                UniversalDiagnostics.commandReceiverRegistered(true)
                requestPlayerBindingsSync(context)
                metadataReplayExecutor.execute { ensureExternalReceiver(context) }
            } catch (error: Throwable) {
                UniversalDiagnostics.commandReceiverRegistered(false, error.javaClass.simpleName)
            }
        }
    }

    private fun handleCacheRequest(context: Context, request: Intent) {
        val operation = request.getStringExtra(UniversalSnapshotStore.EXTRA_CACHE_OPERATION).orEmpty()
        val key = request.getStringExtra(UniversalSnapshotStore.EXTRA_CACHE_KEY).orEmpty()
        val offset = request.getIntExtra(UniversalSnapshotStore.EXTRA_CACHE_OFFSET, 0).coerceAtLeast(0)
        val response = Intent(UniversalSnapshotStore.ACTION_CACHE_RESPONSE).apply {
            setPackage(moduleApplicationInfo.packageName)
            putExtra(UniversalSnapshotStore.EXTRA_CACHE_OPERATION, operation)
            putExtra(UniversalSnapshotStore.EXTRA_CACHE_KEY, key)
            putExtra(UniversalSnapshotStore.EXTRA_CACHE_OFFSET, offset)
        }
        val ok = runCatching {
            when (operation) {
                "list" -> {
                    val page = UniversalLyricCache.page(offset)
                    val result = org.json.JSONArray()
                    page.rows.forEach { row ->
                        result.put(JSONObject().put("key", row.key).put("title", row.title)
                            .put("artist", row.artist).put("source", row.source))
                    }
                    response.putExtra(UniversalSnapshotStore.EXTRA_CACHE_TOTAL, page.total)
                    response.putExtra(UniversalSnapshotStore.EXTRA_CACHE_PAYLOAD, result.toString())
                    true
                }
                "get" -> UniversalLyricCache.getByKey(key)?.let {
                    response.putExtra(UniversalSnapshotStore.EXTRA_CACHE_PAYLOAD, it)
                    true
                } ?: false
                "find" -> {
                    val title = request.getStringExtra("title").orEmpty()
                    val artist = request.getStringExtra("artist").orEmpty()
                    UniversalLyricCache.find(title, artist)?.let { (foundKey, payload) ->
                        response.putExtra(UniversalSnapshotStore.EXTRA_CACHE_KEY, foundKey)
                        response.putExtra(UniversalSnapshotStore.EXTRA_CACHE_PAYLOAD, payload)
                        true
                    } ?: false
                }
                "save" -> {
                    val original = UniversalLyricCache.getByKey(key)?.let(::JSONObject)
                    val value = request.getStringExtra(UniversalSnapshotStore.EXTRA_CACHE_PAYLOAD)
                    if (original == null || value.isNullOrBlank() || value.length > 262_144) false
                    else {
                        val edited = JSONObject(value)
                        val title = original.optString("songName")
                        val artist = original.optString("artist")
                        edited.put("songName", title).put("artist", artist)
                        val encoded = edited.toString()
                        if (LyricPayload.inspect(encoded, title, false, artist).timeline == null) false
                        else {
                            val saved = trackPayloads.withLock {
                                if (!UniversalLyricCache.updateByKey(key, encoded)) false else {
                                    saveManualBinding(bindingKey(title, artist), encoded)
                                    loadManualBinding(bindingKey(title, artist)) == encoded
                                }
                            }
                            if (saved) applyManualToActiveTrack(title, artist, encoded)
                            saved
                        }
                    }
                }
                "clear" -> trackPayloads.withLock {
                    clearCache()
                    UniversalLyricCache.page(0).total == 0
                }
                "delete" -> {
                    val original = UniversalLyricCache.getByKey(key)?.let(::JSONObject)
                    val removed = UniversalLyricCache.removeByKey(key)
                    if (removed && original != null) {
                        val title = original.optString("songName")
                        val artist = original.optString("artist")
                        trackPayloads.withLock { removeManualBinding(bindingKey(title, artist)) }
                        val selected = repository.snapshot().selected?.descriptor
                        if (selected != null && sameIdentityText(selected.title, title) && sameIdentityText(selected.artist, artist)) {
                            val currentKey = "${selected.ownerPackage}|${selected.trackGeneration}"
                            trackPayloads.clearManual(currentKey)
                            realLyricsByPackage.remove(currentKey)
                            retryMissedTracks()
                        }
                    }
                    removed
                }
                else -> false
            }
        }.getOrDefault(false)
        response.putExtra(UniversalSnapshotStore.EXTRA_CACHE_OK, ok)
        sendCacheResponse(context, response)
    }

    private fun sendCacheResponse(context: Context, response: Intent) {
        val identity = Binder.clearCallingIdentity()
        try { context.sendBroadcast(response) } finally { Binder.restoreCallingIdentity(identity) }
    }

    private fun applyManualToActiveTrack(title: String, artist: String, payload: String) {
        val selected = repository.snapshot().selected?.descriptor ?: return
        if (!sameIdentityText(selected.title, title) || !sameIdentityText(selected.artist, artist)) return
        applyFetchedLyric(selected.ownerPackage, selected.trackGeneration, payload, title, artist, manual = true)
    }

    // FLAG_RECEIVER_INCLUDE_BACKGROUND is a framework flag not exposed by the SDK stubs.
    @SuppressLint("WrongConstant")
    private fun requestPlayerBindingsSync(context: Context) {
        val intent = Intent(UniversalSnapshotStore.ACTION_REQUEST_PLAYER_BINDINGS_SYNC).apply {
            setPackage(moduleApplicationInfo.packageName)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            addFlags(0x01000000)
        }
        val sent = UniversalBindingBroadcast.send(context, intent)
        UniversalDiagnostics.bindingBroadcastSent(
            boundPackages.size,
            "provider-bindings-request",
            sent.isSuccess,
            sent.exceptionOrNull()?.javaClass?.simpleName
        )
    }

    /**
     * Formats the live session and lyric state, so a finished fetch reaches the UI without waiting
     * for the player's next callback. Serialized: a slower thread cannot broadcast an older status.
     */
    private fun publishSnapshot(context: Context?, force: Boolean = false): UniversalMonitorSnapshot =
        synchronized(snapshotLock) {
            val snapshot = repository.snapshot()
            val descriptor = snapshot.selected?.descriptor
            val lyricPayload = descriptor?.let { lyricPayloadFor(it.ownerPackage, it.trackGeneration) }
            if (descriptor != null && boundPackages.contains(descriptor.ownerPackage)) {
                val (title, artist) = displayIdentity(descriptor)
                UniversalLyricHistory.row(
                    title,
                    artist,
                    lyricSourceOf(lyricPayload),
                    lyricStatusOf(lyricPayload),
                    descriptor.durationMs,
                )?.let { recentHistory = UniversalLyricHistory.moveToTop(recentHistory, it) }
            }
            val formatted = format(
                snapshot.selected,
                snapshot.sessions.size,
                snapshot.selectionRevision,
                lyricPayload
            )
            latestSnapshotText = formatted
            latestArtworkIdentity = descriptor?.let { it.sessionInstanceId to it.trackGeneration }
            (context ?: currentSystemContext())?.let { sendSnapshotBroadcast(it, formatted, force = force) }
            snapshot
        }

    /** The resolved identity, falling back to the title/artist the lyric lookup actually used. */
    private fun displayIdentity(descriptor: TrackDescriptor): Pair<String?, String?> {
        if (descriptor.title != null) return descriptor.title to descriptor.artist
        val lookup = lookupIdentityByKey["${descriptor.ownerPackage}|${descriptor.trackGeneration}"]
            ?: return null to descriptor.artist
        return lookup.first to (descriptor.artist ?: lookup.second)
    }

    // Called only from the system_server hook; the Binder identity is cleared below.
    @SuppressLint("MissingPermission", "WrongConstant")
    private fun sendSnapshotBroadcast(context: Context, text: String, cacheCleared: Int? = null, force: Boolean = false) {
        var sent = false
        runCatching {
            val identity = latestArtworkIdentity
            val artwork = identity?.first?.let(artworkBySession::get)
                ?.takeIf { it.trackGeneration == identity.second }
            val artworkRevision = artwork?.revision ?: 0L
            if (!force && cacheCleared == null && text == lastBroadcastSnapshotText &&
                artworkRevision == lastBroadcastArtworkRevision) {
                return@runCatching
            }
            val intent = Intent(UniversalSnapshotStore.ACTION_SNAPSHOT_UPDATE).apply {
                setPackage(moduleApplicationInfo.packageName)
                putExtra(UniversalSnapshotStore.EXTRA_SNAPSHOT, text)
                val count = text.lineSequence().firstOrNull { it.startsWith("cachedSongs=") }
                    ?.substringAfter('=')?.toIntOrNull() ?: 0
                putExtra(UniversalSnapshotStore.EXTRA_CACHED_SONGS, count)
                artwork?.let { putExtra(UniversalSnapshotStore.EXTRA_ARTWORK, it.bitmap) }
                putExtra(UniversalSnapshotStore.EXTRA_ARTWORK_REVISION, artworkRevision)
                if (cacheCleared != null) {
                    putExtra(UniversalSnapshotStore.EXTRA_CACHE_CLEARED, cacheCleared)
                }
                addFlags(0x00000020)
            }
            val allUser = runCatching {
                Class.forName("android.os.UserHandle").getDeclaredField("ALL").get(null) as? UserHandle
            }.getOrNull()
            // Snapshots are usually built inside a hooked binder call from the player
            // (setMetadata/setPlaybackState), so the calling identity is the player's uid and a
            // broadcast to UserHandle.ALL is rejected with SecurityException. Send as system_server.
            val callerIdentity = Binder.clearCallingIdentity()
            try {
                if (allUser != null) {
                    context.sendBroadcastAsUser(intent, allUser)
                } else {
                    context.sendBroadcast(intent)
                }
            } finally {
                Binder.restoreCallingIdentity(callerIdentity)
            }
            lastBroadcastSnapshotText = text
            lastBroadcastArtworkRevision = artworkRevision
            sent = true
        }.onFailure { error ->
            UniversalDiagnostics.snapshotSent(text, force, error)
        }
        if (sent) UniversalDiagnostics.snapshotSent(text, force, null)
    }

    private fun rememberArtwork(sessionId: String, trackGeneration: Long, metadata: MediaMetadata) {
        val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: run {
                artworkBySession.remove(sessionId)
                return
            }
        artworkBySession.compute(sessionId) { _, previous ->
            if (previous != null && previous.source.get() === bitmap &&
                previous.sourceGenerationId == bitmap.generationId) {
                return@compute previous.copy(trackGeneration = trackGeneration)
            }
            val longest = maxOf(bitmap.width, bitmap.height)
            val preview = if (longest > 256) {
                val scale = 256f / longest.toFloat()
                android.graphics.Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else bitmap
            // Re-parcelled metadata can carry identical pixels in a new Bitmap object.
            // Compare only the bounded preview here, never in the app's UI callback.
            if (previous != null && runCatching { previous.bitmap.sameAs(preview) }.getOrDefault(false)) {
                return@compute previous.copy(
                    source = java.lang.ref.WeakReference(bitmap),
                    sourceGenerationId = bitmap.generationId,
                    trackGeneration = trackGeneration,
                )
            }
            ArtworkSnapshot(
                java.lang.ref.WeakReference(bitmap),
                bitmap.generationId,
                preview,
                nextArtworkRevision.incrementAndGet(),
                trackGeneration,
            )
        }
    }

    private fun loadBoundPackages(): Set<String> {
        val appBindings = readBindingFile(
            "/data/user/0/${moduleApplicationInfo.packageName}/files/universal_bound_packages.json")
        if (appBindings != null) {
            persistBoundPackages(appBindings)
            return appBindings
        }
        val systemBindings = readBindingFile(BINDINGS_FILE_PATH)
        if (!systemBindings.isNullOrEmpty()) {
            return systemBindings
        }
        return PlayerBindingPolicy.sanitize(DEFAULT_BOUND_PACKAGES)
    }

    private fun readBindingFile(path: String): Set<String>? {
        val file = java.io.File(path)
        if (!file.isFile) return null
        return runCatching {
            val array = org.json.JSONArray(file.readText())
            val set = mutableSetOf<String>()
            for (i in 0 until array.length()) {
                set.add(array.getString(i))
            }
            PlayerBindingPolicy.sanitize(set)
        }.getOrNull()
    }

    private fun refreshBindingsFromDisk(): Boolean {
        val loaded = loadBoundPackages()
        if (loaded == boundPackages) return false
        boundPackages = loaded
        persistBoundPackages(loaded)
        repository.updateBoundFilter(loaded, SystemClock.elapsedRealtime())
        UniversalDiagnostics.playerBindingsReloaded(loaded.size, "disk")
        return true
    }

    private fun persistBoundPackages(packages: Set<String>) {
        runCatching {
            val array = org.json.JSONArray(PlayerBindingPolicy.sanitize(packages))
            java.io.File(BINDINGS_FILE_PATH).writeText(array.toString())
        }
    }

    private fun currentSystemContext(): Context? {
        return runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThread = activityThreadClass.getDeclaredMethod("currentActivityThread").invoke(null)
            activityThreadClass.getDeclaredMethod("getSystemContext").invoke(currentActivityThread) as? Context
        }.getOrNull()
    }

    private fun lyricPayloadFor(ownerPackage: String, generation: Long): JSONObject? =
        (("$ownerPackage|$generation").let { key ->
            realLyricsByPackage[key]
        } ?: realLyricsByPackage[ownerPackage])
            ?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun lyricSourceOf(payload: JSONObject?): String = UniversalLyricPayloadPolicy.sourceWithFormat(payload)

    private fun lyricStatusOf(payload: JSONObject?): String = when {
        payload == null -> "pending"
        payload.optString("lyricStatus") == NoLyricPolicy.STATUS_MISS -> "failed"
        // Legacy caches stored an automatic miss as a negative result; report it honestly.
        payload.optBoolean("noLyric") ->
            if (NoLyricPolicy.isRetryableMiss(payload.toString())) "failed" else "noLyric"
        payload.optString("lyric").isNotBlank() || payload.optString("rawLyric").isNotBlank() -> "success"
        else -> "failed"
    }

    private fun format(
        selected: ResolvedSession?,
        sessionCount: Int,
        selectionRevision: Long,
        lyricPayload: JSONObject? = selected?.descriptor?.let { lyricPayloadFor(it.ownerPackage, it.trackGeneration) }
    ): String {
        val descriptor = selected?.descriptor
        val isBound = boundPackages.contains(descriptor?.ownerPackage)
        val (displayTitle, displayArtist) = descriptor?.let(::displayIdentity) ?: (null to null)
        return buildString {
            append("source=system_server\n")
            append("writeBack=true mode=").append(providerMode).append('\n')
            if (providerMode == PROVIDER_MODE_SUPER_LYRIC) {
                append("superLyricConnection=").append(superLyricConnection).append('\n')
                append("superLyricDelivery=").append(superLyricDelivery).append('\n')
            }
            append("boundPlayer=").append(isBound).append('\n')
            append("boundCount=").append(boundPackages.size).append('\n')
            append("selectionRevision=").append(selectionRevision).append('\n')
            append("sessions=").append(sessionCount).append('\n')
            append("package=").append(descriptor?.ownerPackage ?: "(none)").append('\n')
            append("title=").append(displayTitle ?: "(missing)").append('\n')
            append("artist=").append(displayArtist ?: "(missing)").append('\n')
            // Diagnostic: "lookup" means the title came from the lyric lookup fallback.
            append("titleSource=").append(
                when {
                    descriptor?.title != null -> "resolved"
                    displayTitle != null -> "lookup"
                    else -> "none"
                }
            ).append('\n')
            append("album=").append(descriptor?.album ?: "(missing)").append('\n')
            append("durationMs=").append(descriptor?.durationMs?.toString() ?: "unknown").append('\n')
            append("generation=").append(descriptor?.trackGeneration ?: 0).append('\n')
            append("titlePolluted=").append(descriptor?.titlePolluted ?: false).append('\n')
            append("rawTitle=").append(descriptor?.rawTitle ?: "(missing)").append('\n')
            append("demux=").append(selected?.demuxSource ?: "none").append('\n')
            append("playbackState=").append(descriptor?.playbackState ?: -1).append('\n')
            append("lyricSource=").append(lyricSourceOf(lyricPayload)).append('\n')
            append("lyricStatus=").append(lyricStatusOf(lyricPayload)).append('\n')
            for (key in listOf("sourceUrl", "creditUrl", "creditName")) {
                val value = lyricPayload?.optString(key).orEmpty().lineSequence().firstOrNull().orEmpty()
                if (value.isNotBlank()) append(key).append('=').append(value).append('\n')
            }
            append("lyricClockFallback=").append(
                descriptor != null && wordTimingEnabled && rawLyricEnabled && clockLineFallbackActive(descriptor.ownerPackage)
            ).append('\n')
            append("cachedSongs=").append(UniversalLyricCache.getCacheCount()).append('\n')
            append(UniversalFeatureConfig.snapshotLines())
            append(UniversalLyricHistory.snapshotLines(recentHistory))
        }
    }

    private fun fieldOrNull(type: Class<*>, name: String): Field? =
        runCatching { type.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()

    /**
     * Re-resolves tracks that previously produced nothing. A miss is only a statement about the
     * source order that was in effect, so changing that order must retry instead of showing a
     * stale "failed" state until the app is restarted.
     */
    /**
     * True when a previous attempt missed with incomplete metadata (missing artist or duration)
     * and the player has now supplied the missing field for the same generation.
     */
    private fun shouldRetryMiss(existingJson: String?, artist: String?, durationMs: Long?): Boolean {
        if (existingJson.isNullOrBlank() || !NoLyricPolicy.isRetryableMiss(existingJson)) return false
        val obj = runCatching { JSONObject(existingJson) }.getOrNull() ?: return false
        val prevArtist = obj.optString("artist")
        val prevDuration = obj.optLong("durationMs", 0L)
        val artistArrived = prevArtist.isBlank() && !artist.isNullOrBlank()
        val durationArrived = prevDuration <= 0L && (durationMs ?: 0L) > 0L
        return artistArrived || durationArrived
    }

    private fun retryMissedTracks() {
        if (providerMode != PROVIDER_MODE_BUILTIN) return
        realLyricsByPackage.entries
            .filter { (_, value) ->
                runCatching { JSONObject(value).optString("lyricStatus") == NoLyricPolicy.STATUS_MISS }
                    .getOrDefault(false)
            }
            .forEach { entry -> realLyricsByPackage.remove(entry.key) }
        fetchBackoffUntil.clear()
        val descriptor = repository.snapshot().selected?.descriptor ?: return
        val title = descriptor.title?.takeIf { it.isNotBlank() } ?: return
        queueBackgroundFetch(
            descriptor.ownerPackage,
            title,
            descriptor.artist.orEmpty(),
            descriptor.album,
            descriptor.durationMs,
            descriptor.trackGeneration
        )
    }

    private fun isTimedHostLyric(payload: String?, title: String?, artist: String?): Boolean {
        if (payload.isNullOrBlank() || !UniversalMetadataOverlayPolicy.shouldPreserveExisting(payload, false)) return false
        return runCatching { LyricPayload.inspect(payload, title, false, artist).timeline != null }.getOrDefault(false)
    }

    private fun queueBackgroundFetch(
        ownerPackage: String, title: String, artist: String, album: String?, durationMs: Long?, generation: Long,
    ) {
        if (providerMode != PROVIDER_MODE_BUILTIN) return
        val key = "$ownerPackage|$generation"
        if (!trackPayloads.isManual(key)) triggerBackgroundFetch(ownerPackage, title, artist, album, durationMs, generation)
    }

    private fun triggerBackgroundFetch(
        ownerPackage: String,
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        generation: Long
    ) {
        if (providerMode != PROVIDER_MODE_BUILTIN) return
        if (ownerPackage !in boundPackages || providerMode != PROVIDER_MODE_BUILTIN) return
        // Bluetooth projection changes TITLE every few seconds. Fetches are
        // keyed by the resolved session generation instead of display text,
        // with a short retry backoff for an empty/failed response.
        val fetchKey = "$ownerPackage|$generation"
        // A lookup can outlive the callback backoff: claim the track before changing it.
        if (!inFlightFetches.add(fetchKey)) return
        val now = SystemClock.elapsedRealtime()
        var acquired = false
        fetchBackoffUntil.compute(fetchKey) { _, previous ->
            if (previous == null || now >= previous) {
                acquired = true
                now + 5_000L
            } else previous
        }
        if (!acquired) {
            inFlightFetches.remove(fetchKey)
            return
        }
        val fetchRevision = searchConfigRevision.get()
        fun lookupIsCurrent(): Boolean = fetchRevision == searchConfigRevision.get() &&
            providerMode == PROVIDER_MODE_BUILTIN && ownerPackage in boundPackages &&
            repository.snapshot().sessions.any {
                it.descriptor.ownerPackage == ownerPackage && it.descriptor.trackGeneration == generation
            }

        try {
            backgroundExecutor.execute {
                try {
                    runCatching {
                        if (!lookupIsCurrent()) return@execute
                        if (ownerPackage !in boundPackages || repository.snapshot().sessions.none {
                                it.descriptor.ownerPackage == ownerPackage && it.descriptor.trackGeneration == generation
                            }) {
                            fetchBackoffUntil.remove(fetchKey)
                            return@execute
                        }
                        android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.LAX)
                        val manualKey = bindingKey(title, artist)
                        val manual = loadManualBinding(manualKey)
                        if (!manual.isNullOrBlank()) {
                            val negative = runCatching { JSONObject(manual).optBoolean("noLyric", false) }.getOrDefault(false)
                            UniversalDiagnostics.cacheLookup("manual", generation, hit = true, noLyric = negative)
                            UniversalDiagnostics.fetchStarted(ownerPackage, generation, "manual-cache")
                            if (negative) {
                                applyFetchedLyric(ownerPackage, generation, manual, title, artist, manual = true)
                                fetchBackoffUntil.remove(fetchKey)
                                return@execute
                            }
                            val updated = updateGeneration(manual, generation, ownerPackage)
                            applyFetchedLyric(ownerPackage, generation, manual, title, artist, manual = true)
                            fetchBackoffUntil.remove(fetchKey)
                            UniversalDiagnostics.fetchFinished(ownerPackage, generation, "manual-cache", updated.length)
                            return@execute
                        }
                        UniversalDiagnostics.cacheLookup("manual", generation, hit = false, noLyric = false)

                        val cached = loadCachedLyric(title, artist)
                        // A legacy automatic miss is retried instead of being replayed forever.
                        if (!cached.isNullOrBlank() && UniversalCacheSourcePolicy.canUse(cached, directMode = false) &&
                            !NoLyricPolicy.isRetryableMiss(cached)) {
                            val negative = runCatching { JSONObject(cached).optBoolean("noLyric", false) }.getOrDefault(false)
                            UniversalDiagnostics.cacheLookup("auto", generation, hit = true, noLyric = negative)
                            UniversalDiagnostics.fetchStarted(ownerPackage, generation, "auto-cache")
                            if (negative) {
                                applyFetchedLyric(ownerPackage, generation, cached, title, artist, expectedRevision = fetchRevision)
                                fetchBackoffUntil.remove(fetchKey)
                                return@execute
                            }
                            val updated = updateGeneration(cached, generation, ownerPackage)
                            applyFetchedLyric(ownerPackage, generation, cached, title, artist, expectedRevision = fetchRevision)
                            fetchBackoffUntil.remove(fetchKey)
                            UniversalDiagnostics.fetchFinished(ownerPackage, generation, "auto-cache", updated.length)
                            return@execute
                        }
                        UniversalDiagnostics.cacheLookup("auto", generation, hit = false, noLyric = false)

                        UniversalDiagnostics.fetchStarted(
                            ownerPackage,
                            generation,
                            "network priority=" + sourcePriority.joinToString(",") { it.name } +
                                " titleHash=" + DiagnosticHasher.sha256(title).take(12) +
                                " artistHash=" + DiagnosticHasher.sha256(artist).take(12) +
                                " durationMs=" + (durationMs?.toString() ?: "unknown") +
                                " wordTiming=" + wordTimingEnabled
                        )
                        val query = io.github.andrealtb.coloroslyrics.provider.universal.api.TrackQuery(
                            title = title,
                            artist = artist,
                            album = album,
                            durationMs = durationMs,
                            generation = generation
                        )
                        if (!lookupIsCurrent()) return@execute
                        val report = aggregator.fetchFirst(query, sourcePriority, preferWordTiming = wordTimingEnabled,
                            preferTtml = UniversalFeatureConfig.ttmlFirst, shouldContinue = ::lookupIsCurrent)
                        if (!lookupIsCurrent()) return@execute
                        val result = report.result
                        val instrumental = result != null && io.github.andrealtb.coloroslyrics.provider.universal.api.SongMatchScorer.isInstrumentalOrCreditsOnly(result.lyric)
                        val usable = result != null && result.lyric.isNotBlank() && !instrumental
                        val outcome = NoLyricPolicy.decide(
                            hasUsableLyric = usable,
                            instrumental = instrumental,
                            transientFailure = report.transientFailure && !usable
                        )
                        if (outcome == NoLyricPolicy.Outcome.PUBLISH_LYRICS && result != null) {
                            val plainLyric = UniversalLyricPayloadPolicy.stripWordTiming(result.lyric)
                            val alignedTranslation = run {
                                val primary = io.github.andrealtb.coloroslyrics.provider.universal.api.StructuredLyrics.parseLrc(plainLyric)
                                val trans = io.github.andrealtb.coloroslyrics.provider.universal.api.StructuredLyrics.parseLrc(result.translationLyric)
                                io.github.andrealtb.coloroslyrics.provider.universal.api.StructuredLyrics.toTranslationLrc(
                                    io.github.andrealtb.coloroslyrics.provider.universal.api.StructuredLyrics.merge(primary, trans)
                                ).takeIf { it.isNotBlank() }
                            }
                            // Cache the complete payload; publishing applies the processing switches, so
                            // turning a switch back on does not need a cache clear.
                            val baseJson = JSONObject().apply {
                                put("songName", result.title)
                                put("artist", result.artist)
                                put("album", album.orEmpty())
                                put("durationMs", durationMs ?: 0L)
                                put("songId", "universal-$generation")
                                put("lyricType", 0)
                                put("id", "")
                                put("noLyric", false)
                                put("lyric", plainLyric)
                                if (!result.rawLyric.isNullOrBlank()) {
                                    put("rawLyric", result.rawLyric)
                                }
                                UniversalLyricPayloadPolicy.putTtmlAndFormat(this, result)
                                if (!alignedTranslation.isNullOrBlank()) {
                                    put("transLyric", alignedTranslation)
                                    put("translationLyric", alignedTranslation)
                                    put("translationLanguageTag", "zh-CN")
                                }
                                put("provider", "shop.terramoor.github.io.builtinlyrics")
                                put(UniversalCacheSourcePolicy.KIND_KEY, UniversalCacheSourcePolicy.NETWORK)
                                put("source", result.source)
                                result.creditName?.let { put("creditName", it) }
                                result.creditUrl?.let { put("creditUrl", it) }
                                result.sourceUrl?.let { put("sourceUrl", it) }
                                put("sessionGeneration", generation)
                                put("remark", "[ColorLyricBuiltin]")
                            }.toString()
                            val json = ensureAutoTranslatedPayload(baseJson)

                            if (!lookupIsCurrent()) return@execute
                            saveAutomaticLyric(fetchKey, title, artist, json)
                            val published = applyFetchedLyric(ownerPackage, generation, json, title, artist, expectedRevision = fetchRevision)
                            fetchBackoffUntil.remove(fetchKey)
                            UniversalDiagnostics.fetchFinished(ownerPackage, generation, result.source, json.length)
                            UniversalDiagnostics.lyricInfoComposed(
                                ownerPackage = ownerPackage,
                                generation = generation,
                                source = result.source,
                                lyricChars = result.lyric.length,
                                rawLyricAttached = published.rawAttached,
                                translationAttached = translationEnabled && !alignedTranslation.isNullOrBlank(),
                                wordTimingEnabled = wordTimingEnabled
                            )
                        } else {
                            val rejectReason = when {
                                report.transientFailure -> "transient:" + report.reason
                                instrumental -> "instrumentalOrCreditsOnly source=" + result.source
                                else -> "miss:" + report.reason
                            }
                            UniversalDiagnostics.fetchEmpty(ownerPackage, generation, rejectReason)
                            when (outcome) {
                                NoLyricPolicy.Outcome.AUTO_NEGATIVE -> {
                                    val reason = NoLyricPolicy.autoReason(instrumental)
                                    val noLyricJson = NoLyricPolicy.payloadJson(title, artist, reason)
                                    // Only a complete identity may be cached as a negative result.
                                    if (artist.isNotBlank() && lookupIsCurrent()) saveAutomaticLyric(fetchKey, title, artist, noLyricJson)
                                    applyFetchedLyric(ownerPackage, generation, noLyricJson, title, artist, expectedRevision = fetchRevision)
                                }
                                NoLyricPolicy.Outcome.AUTO_MISS -> {
                                    // A miss describes this attempt, not the track: keep it in memory so a
                                    // later callback with fuller metadata or a source-order change can retry.
                                    val missJson = NoLyricPolicy.missPayloadJson(title, artist, durationMs)
                                    applyFetchedLyric(ownerPackage, generation, missJson, title, artist, replay = false, expectedRevision = fetchRevision)
                                }
                                else -> fetchBackoffUntil[fetchKey] = SystemClock.elapsedRealtime() + 30_000L
                            }
                        }
                    }.onFailure { error ->
                        UniversalDiagnostics.fetchEmpty(
                            ownerPackage,
                            generation,
                            "exception=${error.javaClass.simpleName}"
                        )
                        fetchBackoffUntil[fetchKey] = SystemClock.elapsedRealtime() + 30_000L
                    }
                } finally {
                    inFlightFetches.remove(fetchKey)
                    if (fetchRevision != searchConfigRevision.get() && ownerPackage in boundPackages &&
                        repository.snapshot().sessions.any {
                            it.descriptor.ownerPackage == ownerPackage && it.descriptor.trackGeneration == generation
                        }) {
                        fetchBackoffUntil.remove(fetchKey)
                        queueBackgroundFetch(ownerPackage, title, artist, album, durationMs, generation)
                    }
                }
            }
        } catch (error: RuntimeException) {
            inFlightFetches.remove(fetchKey)
            throw error
        }
    }

    private fun playbackExtrasMediaId(state: PlaybackState?): String? {
        val extras = state?.extras ?: return null
        val keys = arrayOf(
            "androidx.media.PlaybackStateCompat.Extras.KEY_MEDIA_ID",
            "android.media.playback.extra.MEDIA_ID",
            "media_id"
        )
        return keys.firstNotNullOfOrNull { key ->
            extras.getString(key)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun updateGeneration(jsonStr: String, generation: Long, ownerPackage: String): String {
        return runCatching {
            val json = JSONObject(jsonStr)
            json.put("sessionGeneration", generation)
            json.put("songId", "universal-$generation")
            applyWordTimingPolicy(json, ownerPackage)
            json.toString()
        }.getOrDefault(jsonStr)
    }

    private fun applyFetchedLyric(
        ownerPackage: String,
        generation: Long,
        json: String,
        title: String,
        artist: String,
        replay: Boolean = true,
        manual: Boolean = false,
        expectedRevision: Long? = null,
    ): UniversalLyricPayloadPolicy.Result {
        val original = JSONObject(json).put("sessionGeneration", generation)
            .put("songId", "universal-$generation").toString()
        val payload = JSONObject(original)
        val published = applyWordTimingPolicy(payload, ownerPackage)
        if (providerMode != PROVIDER_MODE_BUILTIN) return published
        if (ownerPackage !in boundPackages) return published
        if (repository.snapshot().sessions.none {
                it.descriptor.ownerPackage == ownerPackage && it.descriptor.trackGeneration == generation
            }) return published
        val trackKey = "$ownerPackage|$generation"
        val accepted = trackPayloads.withLock {
            if (expectedRevision != null && (expectedRevision != searchConfigRevision.get() ||
                    providerMode != PROVIDER_MODE_BUILTIN)) return@withLock false
            val bound = loadManualBinding(bindingKey(title, artist))
            if ((!manual && !bound.isNullOrBlank()) || (manual && bound != json)) false
            else trackPayloads.accept(trackKey, original, manual) {
                realLyricsByPackage[trackKey] = payload.toString()
            }
        }
        if (!accepted) return published
        // A miss only updates the status; it must not overwrite the host's current lyric payload.
        if (replay) replayMetadataForGeneration(ownerPackage, generation)
        // The track may already have been skipped, so its row takes the result where it stands.
        UniversalLyricHistory.row(title, artist, lyricSourceOf(payload), lyricStatusOf(payload), payload.optLong("durationMs"))?.let { row ->
            synchronized(snapshotLock) { recentHistory = UniversalLyricHistory.replaceInPlace(recentHistory, row) }
        }
        publishSnapshot(null)
        // The first callback can race SystemUI's notification/card creation.
        // Replay after the normal load window as a fail-open compatibility
        // measure for ColorOS builds that ignore pushMetadataUpdate early.
        if (replay) {
            listOf(250L, 750L, 1500L).forEach { delayMs ->
                metadataReplayExecutor.schedule(
                    { replayMetadataForGeneration(ownerPackage, generation) },
                    delayMs,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
            }
        }
        return published
    }

    private fun replayMetadataForGeneration(ownerPackage: String, generation: Long) {
        val session = repository.snapshot().sessions.firstOrNull {
            it.descriptor.ownerPackage == ownerPackage && it.descriptor.trackGeneration == generation
        } ?: return
        val record = activeRecords[session.descriptor.sessionInstanceId] ?: return
        if (realLyricsByPackage["$ownerPackage|$generation"].isNullOrBlank()) return
        overlayMetadata(
            record,
            session,
            cachedMetadataField,
            cachedLockField,
            cachedPushMetadataUpdateMethod,
            force = true
        )
    }

    private fun loadSourceConfig(): String = runCatching {
        java.io.File("/data/system/colorlyric_universal_source_config.json").takeIf { it.isFile }?.readText()
    }.getOrNull() ?: LyricsSourceConfigStore.encode(LyricsSourceConfigStore.defaults)

    private fun persistSourceConfig(priority: List<io.github.andrealtb.coloroslyrics.provider.universal.api.LyricsSourceAggregator.SourceId>) {
        runCatching { java.io.File("/data/system/colorlyric_universal_source_config.json").writeText(LyricsSourceConfigStore.encode(priority.map { it.name })) }
    }

    private fun loadWordTimingEnabled(): Boolean = runCatching {
        java.io.File("/data/system/colorlyric_universal_word_timing.enabled").readText().trim() == "1"
    }.getOrDefault(true)

    private fun persistWordTimingEnabled(enabled: Boolean) {
        runCatching { java.io.File("/data/system/colorlyric_universal_word_timing.enabled").writeText(if (enabled) "1" else "0") }
    }

    private fun loadRawLyricEnabled(): Boolean = runCatching {
        java.io.File("/data/system/colorlyric_universal_raw_lyric.enabled").readText().trim() != "0"
    }.getOrDefault(true)

    private fun persistRawLyricEnabled(enabled: Boolean) {
        runCatching { java.io.File("/data/system/colorlyric_universal_raw_lyric.enabled").writeText(if (enabled) "1" else "0") }
    }

    private fun loadDebugEnabled(): Boolean = runCatching {
        java.io.File("/data/system/colorlyric_universal_debug.enabled").readText().trim() == "1"
    }.getOrDefault(false)

    private fun persistDebugEnabled(enabled: Boolean) {
        runCatching { java.io.File("/data/system/colorlyric_universal_debug.enabled").writeText(if (enabled) "1" else "0") }
    }

    private fun providerModeFile() = android.util.AtomicFile(java.io.File(PROVIDER_MODE_FILE_PATH))

    private fun loadProviderMode(): String = runCatching {
        normalizeProviderMode(providerModeFile().readFully().decodeToString().trim())
    }.getOrDefault(PROVIDER_MODE_BUILTIN)

    private fun persistProviderMode(mode: String) {
        val file = providerModeFile()
        runCatching {
            val stream = file.startWrite()
            try {
                stream.write(normalizeProviderMode(mode).encodeToByteArray())
                file.finishWrite(stream)
            } catch (failure: Exception) {
                file.failWrite(stream)
                throw failure
            }
        }.onFailure {
            android.util.Log.w("ColorLyricRelay", "Could not persist lyrics source", it)
        }
    }

    private fun normalizeProviderMode(mode: String?): String = when (mode) {
        PROVIDER_MODE_SUPER_LYRIC -> PROVIDER_MODE_SUPER_LYRIC
        PROVIDER_MODE_LYRICON -> PROVIDER_MODE_LYRICON
        else -> PROVIDER_MODE_BUILTIN
    }

    private fun isCurrentSession(record: Any, session: ResolvedSession): Boolean {
        val descriptor = session.descriptor
        return descriptor.ownerPackage in boundPackages &&
            activeRecords[descriptor.sessionInstanceId] === record &&
            repository.get(descriptor.sessionInstanceId)?.descriptor?.trackGeneration == descriptor.trackGeneration
    }

    private fun refreshProcessingSettings() {
        if (providerMode != PROVIDER_MODE_BUILTIN) {
            if (usesExternalSubscriber()) latestExternalPublication?.let(::applyExternalPublication)
            return
        }
        repository.snapshot().sessions.forEach { session ->
            val descriptor = session.descriptor
            if (descriptor.ownerPackage !in boundPackages) return@forEach
            val key = "${descriptor.ownerPackage}|${descriptor.trackGeneration}"
            val record = activeRecords[descriptor.sessionInstanceId] ?: return@forEach
            val title = descriptor.title ?: return@forEach
            val artist = descriptor.artist.orEmpty()
            if (repository.get(descriptor.sessionInstanceId)?.descriptor?.trackGeneration != descriptor.trackGeneration) return@forEach
            var restored = false
            trackPayloads.refresh(key) { original ->
                if (trackPayloads.isManual(key) || (realLyricsByPackage.containsKey(key) &&
                        UniversalCacheSourcePolicy.canUse(original, directMode = false))) {
                    val prepared = ensureAutoTranslatedPayload(original)
                    if (prepared !== original) {
                        saveAutomaticLyric(bindingKey(title, artist), title, artist, prepared)
                    }
                    realLyricsByPackage[key] = updateGeneration(prepared, descriptor.trackGeneration, descriptor.ownerPackage)
                    restored = true
                }
            }
            if (!restored) {
                val manual = loadManualBinding(bindingKey(title, artist))
                val saved = manual ?: loadCachedLyric(title, artist)
                if (!saved.isNullOrBlank() && (manual != null || UniversalCacheSourcePolicy.canUse(saved, directMode = false))) {
                    val prepared = if (manual != null) saved else ensureAutoTranslatedPayload(saved)
                    if (manual == null && prepared !== saved) {
                        saveAutomaticLyric(bindingKey(title, artist), title, artist, prepared)
                    }
                    trackPayloads.accept(key, prepared, isManual = manual != null) {
                        realLyricsByPackage[key] = updateGeneration(it, descriptor.trackGeneration, descriptor.ownerPackage)
                        restored = true
                    }
                }
            }
            if (restored && repository.get(descriptor.sessionInstanceId)?.descriptor?.trackGeneration == descriptor.trackGeneration) {
                overlayMetadata(record, session, cachedMetadataField, cachedLockField, cachedPushMetadataUpdateMethod, force = true)
            }
        }
        publishSnapshot(null)
    }

    private fun loadTranslationEnabled(): Boolean = runCatching {
        java.io.File("/data/system/colorlyric_universal_translation.enabled").readText().trim() != "0"
    }.getOrDefault(true)

    private fun persistTranslationEnabled(enabled: Boolean) {
        runCatching { java.io.File("/data/system/colorlyric_universal_translation.enabled").writeText(if (enabled) "1" else "0") }
    }

    private fun loadAutoTranslateEnabled(): Boolean = runCatching {
        java.io.File("/data/system/colorlyric_universal_auto_translate.enabled").readText().trim() == "1"
    }.getOrDefault(false)

    private fun persistAutoTranslateEnabled(enabled: Boolean) {
        runCatching { java.io.File("/data/system/colorlyric_universal_auto_translate.enabled").writeText(if (enabled) "1" else "0") }
    }

    private fun loadAutoTranslateLang(): String = runCatching {
        java.io.File("/data/system/colorlyric_universal_auto_translate.lang").readText().trim().ifEmpty { "auto" }
    }.getOrDefault("auto")

    private fun persistAutoTranslateLang(lang: String) {
        runCatching { java.io.File("/data/system/colorlyric_universal_auto_translate.lang").writeText(lang.ifBlank { "auto" }) }
    }

    private fun ensureAutoTranslatedPayload(jsonStr: String): String {
        if (!translationEnabled || !autoTranslateEnabled) return jsonStr
        return runCatching {
            val json = JSONObject(jsonStr)
            val lyric = json.optString("lyric")
            if (lyric.isBlank()) return jsonStr
            val targetLang = io.github.andrealtb.coloroslyrics.provider.universal.api.GoogleLyricsTranslator
                .resolveTargetLang(autoTranslateLang)
            val existingTrans = json.optString("translationLyric").ifBlank { json.optString("transLyric") }
            val existingTag = json.optString("translationLanguageTag")
            val alreadyMatches = existingTrans.isNotBlank() && (
                existingTag.equals(targetLang, ignoreCase = true) ||
                    (existingTag.isBlank() && targetLang.startsWith("zh", ignoreCase = true))
                )
            if (alreadyMatches) return jsonStr
            val plainLyric = UniversalLyricPayloadPolicy.stripWordTiming(lyric)
            val translated = io.github.andrealtb.coloroslyrics.provider.universal.api.GoogleLyricsTranslator
                .translateLrc(plainLyric, targetLang) ?: return jsonStr
            json.put("transLyric", translated)
            json.put("translationLyric", translated)
            json.put("translationLanguageTag", targetLang)
            json.toString()
        }.getOrDefault(jsonStr)
    }

    private fun loadClockLineFallbackEnabled(): Boolean = runCatching {
        java.io.File("/data/system/colorlyric_universal_clock_line_fallback.enabled").readText().trim() != "0"
    }.getOrDefault(true)

    private fun persistClockLineFallbackEnabled(enabled: Boolean) {
        runCatching { java.io.File("/data/system/colorlyric_universal_clock_line_fallback.enabled").writeText(if (enabled) "1" else "0") }
    }

    private fun bindingKey(title: String, artist: String): String =
        "${title.trim()}|${artist.trim()}".lowercase()

    private fun applyWordTimingPolicy(json: JSONObject, ownerPackage: String): UniversalLyricPayloadPolicy.Result {
        // A coarse player clock turns word fill into twitching; publish line timing for it instead.
        val clockFallback = wordTimingEnabled && clockLineFallbackActive(ownerPackage)
        val result = UniversalLyricPayloadPolicy.apply(
            json,
            wordTiming = wordTimingEnabled && !clockFallback,
            rawLyric = rawLyricEnabled,
            translation = translationEnabled
        )
        UniversalDiagnostics.rawLyricPolicy(
            generation = json.optLong("sessionGeneration"),
            enabled = wordTimingEnabled,
            hadRaw = result.hadRaw,
            hadWordTiming = result.hadWordTiming,
            attached = result.rawAttached,
            wordTimed = result.wordTimed,
            clockFallback = clockFallback
        )
        return result
    }

    private fun publishedPayload(value: String, ownerPackage: String): String = runCatching {
        JSONObject(value).also { applyWordTimingPolicy(it, ownerPackage) }.toString()
    }.getOrDefault(value)

    private fun recoverIdentityFromCache(
        rawTitle: String?,
        artist: String?,
        album: String?,
        durationMs: Long?
    ): Pair<String, String>? {
        val needle = normalizeLyricText(rawTitle)
        if (needle.length < 8) return null
        if (artist == null) return null
        return runCatching {
            UniversalLyricCache.withArtistPayloads(artist) { rows ->
                rows.mapNotNull { raw ->
                    val payload = JSONObject(raw)
                    val candidateArtist = payload.optString("artist")
                    val candidateAlbum = payload.optString("album")
                    if (!sameIdentityText(artist, candidateArtist)) return@mapNotNull null
                    if (!album.isNullOrBlank() && candidateAlbum.isNotBlank() && !sameIdentityText(album, candidateAlbum)) return@mapNotNull null
                    val candidateDuration = payload.optLong("durationMs").takeIf { it > 0L }
                    if (durationMs != null && candidateDuration != null && kotlin.math.abs(durationMs - candidateDuration) > 2_500L) return@mapNotNull null
                    val songName = payload.optString("songName")
                    if (songName.isNotBlank() && normalizeLyricText(payload.optString("lyric")).contains(needle)) {
                        songName to candidateArtist
                    } else null
                }.distinct().take(2).toList().singleOrNull()
            }
        }.getOrNull()
    }

    private fun normalizeLyricText(value: String?): String = value.orEmpty().lowercase()
        .replace(Regex("[\\[<]\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?[\\]>]"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), "")

    private fun sameIdentityText(left: String?, right: String?): Boolean =
        left?.trim()?.equals(right?.trim(), ignoreCase = true) == true

    /** Callers publish the snapshot once the payload is applied, which also carries the new count. */
    private fun saveCachedLyric(title: String, artist: String, jsonStr: String) {
        UniversalLyricCache.put(title, artist, jsonStr)
    }

    private fun saveAutomaticLyric(trackKey: String, title: String, artist: String, jsonStr: String) {
        trackPayloads.withLock {
            if (!trackPayloads.isManual(trackKey) && loadManualBinding(bindingKey(title, artist)).isNullOrBlank()) {
                saveCachedLyric(title, artist, jsonStr)
            }
        }
    }

    private fun loadCachedLyric(title: String, artist: String): String? =
        UniversalLyricCache.get(title, artist)

    private fun upsertCachedSongs(text: String): String {
        val count = UniversalLyricCache.getCacheCount()
        val lines = text.lines().filterNot { it.startsWith("cachedSongs=") }
        return (lines + ("cachedSongs=" + count)).joinToString("\n").trimEnd() + "\n"
    }

    private fun loadManualBinding(key: String): String? {
        val file = java.io.File("/data/system/colorlyric_universal_manual_bindings.json")
        if (!file.isFile) return null
        return runCatching {
            JSONObject(file.readText()).optString(key).takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun saveManualBinding(key: String, jsonStr: String) {
        runCatching {
            val file = java.io.File("/data/system/colorlyric_universal_manual_bindings.json")
            val obj = if (file.isFile) JSONObject(file.readText()) else JSONObject()
            obj.put(key, jsonStr)
            file.writeText(obj.toString())
        }
    }

    private fun removeManualBinding(key: String) {
        runCatching {
            val file = java.io.File("/data/system/colorlyric_universal_manual_bindings.json")
            if (file.isFile) {
                val obj = JSONObject(file.readText())
                obj.remove(key)
                file.writeText(obj.toString())
            }
        }
    }

    private fun clearCache(): Int {
        return UniversalLyricCache.clear().songs
    }
}
