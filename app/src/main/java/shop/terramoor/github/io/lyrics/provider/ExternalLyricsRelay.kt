package shop.terramoor.github.io.lyrics.provider

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json

/** Relays Lyricon Central publications from SystemUI to the media-session owner. */
internal object ExternalLyricsRelay {
    private const val publicationAction = "shop.terramoor.github.io.action.SUPER_LYRIC_PUBLICATION"
    private const val requestAction = "shop.terramoor.github.io.action.SUPER_LYRIC_REQUEST"
    private const val readyAction = "shop.terramoor.github.io.action.EXTERNAL_LYRICS_READY"
    private const val permission = "android.permission.STATUS_BAR_SERVICE"
    private const val systemUi = "com.android.systemui"
    private const val maxChars = 350_000
    private const val tag = "ColorLyricRelay"

    fun lyriconReceiver(accepts: (String) -> Boolean): ExternalLyricsSource = object : ExternalLyricsSource {
        private val source = ExternalLyricsProvider.LYRICON
        override val provider = source
        @Volatile private var registered: BroadcastReceiver? = null
        private var receiverContext: Context? = null
        private var epoch = 0L

        override fun start(context: Context, callbacks: ExternalLyricsCallbacks) {
            val systemUiUid = context.packageManager.getApplicationInfo(systemUi, 0).uid
            val subscription = SystemClock.elapsedRealtimeNanos()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (sentFromUid != systemUiUid || registered !== this) return
                    if (intent.action == readyAction) {
                        request(context, source, subscription, active = true)
                        return
                    }
                    if (intent.getLongExtra("epoch", 0) != subscription || intent.getStringExtra("source") != source.id) return
                    val payload = intent.getStringExtra("publication")
                    if (payload == null) {
                        callbacks.onStopped(null)
                        callbacks.onActivePlayerChanged(null)
                    } else if (payload.length <= maxChars) {
                        val publication = runCatching { Json.decodeFromString<ExternalLyricsPublication>(payload) }.getOrNull() ?: return
                        if (!accepts(intent.getStringExtra("provider") ?: publication.playerPackage)) return
                        callbacks.onActivePlayerChanged(publication.playerPackage)
                        callbacks.onPublication(publication)
                    }
                }
            }
            registered = receiver
            receiverContext = context
            epoch = subscription
            try {
                val filter = IntentFilter(publicationAction).apply { addAction(readyAction) }
                context.registerReceiver(receiver, filter, permission, null, Context.RECEIVER_EXPORTED)
                request(context, source, subscription, active = true)
            } catch (failure: Exception) {
                stop()
                throw failure
            }
        }

        override fun stop() {
            val receiver = registered ?: return
            registered = null
            val context = receiverContext ?: return
            receiverContext = null
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { request(context, source, epoch, active = false) }
        }
    }

    class SystemUi(private val context: Context) {
        private val executor = Executors.newSingleThreadScheduledExecutor()
        private val selection = ExternalLyricsSelection()
        @Volatile private var centralListener: CentralLyricsListener? = null

        fun startCentral() {
            check(centralListener == null)
            val listener = CentralLyricsListener { listener, provider, publication ->
                executor.execute {
                    if (centralListener === listener) publish(publication, ExternalLyricsProvider.LYRICON, provider)
                }
            }
            centralListener = listener
            listener.start()
        }

        init {
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (sentFromUid != Process.SYSTEM_UID) return
                    val provider = ExternalLyricsProvider.fromId(intent.getStringExtra("source"))
                    val epoch = intent.getLongExtra("epoch", 0)
                    val requested = intent.getBooleanExtra("active", false)
                    executor.execute {
                        if (provider != ExternalLyricsProvider.LYRICON || !selection.update(provider, epoch, requested)) {
                            return@execute
                        }
                        if (selection.source == ExternalLyricsProvider.LYRICON) centralListener?.replay()
                    }
                }
            }, IntentFilter(requestAction), "android.permission.MANAGE_USERS", null, Context.RECEIVER_EXPORTED)
            // Whichever side starts second triggers synchronization after both receivers exist.
            send(context, Intent(readyAction).setPackage("android"))
        }

        private fun publish(
            publication: ExternalLyricsPublication?,
            provider: ExternalLyricsProvider,
            sourcePackage: String?,
        ) {
            if (selection.source != provider) return
            runCatching {
                val payload = publication?.let { Json.encodeToString(it) }
                if (payload != null && payload.length > maxChars) return
                send(context, Intent(publicationAction).setPackage("android")
                    .putExtra("source", provider.id).putExtra("epoch", selection.epoch)
                    .putExtra("provider", sourcePackage).putExtra("publication", payload))
                if (publication != null) Log.i(tag, "${provider.id} relayed ${publication.playerPackage}, lines=${publication.lines.size}")
            }.onFailure { Log.w(tag, "External lyrics relay failed", it) }
        }
    }

    private fun request(context: Context, source: ExternalLyricsProvider, epoch: Long, active: Boolean) {
        send(context, Intent(requestAction).setPackage(systemUi)
            .putExtra("source", source.id).putExtra("epoch", epoch).putExtra("active", active))
    }

    private fun send(context: Context, intent: Intent) {
        val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
        context.sendBroadcast(intent, null, options)
    }
}
