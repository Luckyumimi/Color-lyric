package shop.terramoor.github.io.lyrics.provider

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json

private const val SUBSCRIBE = 1
private const val PUBLICATION = 2
private const val STOPPED = 3
private const val CONNECTION_STATUS = 4
private const val PAYLOAD = "publication"
private const val MAX_PAYLOAD_CHARS = 350_000
private const val TAG = "ColorLyricSuperLyric"

/** SuperLyric exposes its Binder through the authorized application's startup service cache. */
class SuperLyricReceiverService : Service() {
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val source by lazy { SuperLyricExternalLyricsSource() }
    private var retry: ScheduledFuture<*>? = null
    private var client: Messenger? = null
    private var registrationError: String? = null
    private val endpoint by lazy {
        Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.sendingUid == Process.SYSTEM_UID && message.what == SUBSCRIBE) {
                message.replyTo?.let { peer -> worker.execute { subscribe(peer) } }
            }
            true
        })
    }

    override fun onBind(intent: Intent): IBinder = endpoint.binder

    override fun onUnbind(intent: Intent): Boolean {
        worker.execute(::disconnect)
        return false
    }

    override fun onDestroy() {
        worker.execute(::disconnect)
        worker.shutdown()
        super.onDestroy()
    }

    private fun subscribe(peer: Messenger) {
        disconnect()
        client = peer
        connect(peer)
    }

    private fun connect(peer: Messenger) {
        try {
            source.start(this, object : ExternalLyricsCallbacks {
                override fun onActivePlayerChanged(packageName: String?) = Unit
                override fun onPublication(publication: ExternalLyricsPublication) {
                    val payload = Json.encodeToString(publication)
                    if (payload.length <= MAX_PAYLOAD_CHARS) send(peer, PUBLICATION, payload)
                }
                override fun onStopped(packageName: String?) = send(peer, STOPPED, packageName)
            })
            registrationError = null
            send(peer, CONNECTION_STATUS, "registered")
            Log.i(TAG, "SuperLyric registered in receiver process")
        } catch (failure: Exception) {
            retryRegistration(peer, failure)
        } catch (failure: LinkageError) {
            retryRegistration(peer, failure)
        }
    }

    private fun retryRegistration(peer: Messenger, failure: Throwable) {
        source.stop()
        val reason = failure.message ?: failure.javaClass.simpleName
        if (registrationError != reason) Log.w(TAG, "SuperLyric registration failed", failure)
        registrationError = reason
        send(peer, CONNECTION_STATUS, "registration_failed")
        if (!worker.isShutdown) {
            retry = worker.schedule({ if (client === peer) connect(peer) }, 5, TimeUnit.SECONDS)
        }
    }

    private fun send(peer: Messenger, code: Int, value: String?) {
        try {
            peer.send(Message.obtain(null, code).apply { data = Bundle().apply { putString(PAYLOAD, value) } })
        } catch (_: RemoteException) {
            // The system process died or the payload exceeded the Binder budget.
        }
    }

    private fun disconnect() {
        retry?.cancel(false)
        retry = null
        client = null
        source.stop()
    }
}

internal class SuperLyricServiceSource(
    private val appPackage: String,
    private val dispatch: (Runnable) -> Unit,
    private val onStatus: (String) -> Unit,
) : ExternalLyricsSource {
    override val provider = ExternalLyricsProvider.SUPER_LYRIC
    @Volatile private var subscription: Subscription? = null

    override fun start(context: Context, callbacks: ExternalLyricsCallbacks) {
        stop()
        val uid = context.packageManager.getApplicationInfo(appPackage, 0).uid
        val next = Subscription(context, uid, callbacks)
        subscription = next
        next.bind()
    }

    override fun stop() {
        val previous = subscription
        subscription = null
        previous?.close()
    }

    private inner class Subscription(
        private val context: Context,
        private val receiverUid: Int,
        private val callbacks: ExternalLyricsCallbacks,
    ) : ServiceConnection {
        private val handler = Handler(context.mainLooper)
        private val endpoint = Messenger(Handler(context.mainLooper) { message ->
            if (subscription === this && message.sendingUid == receiverUid) {
                val code = message.what
                val value = message.data.getString(PAYLOAD)
                dispatch(Runnable {
                    if (subscription !== this) return@Runnable
                    when (code) {
                        PUBLICATION -> {
                            val publication = value?.takeIf { it.length <= MAX_PAYLOAD_CHARS }?.let {
                                runCatching { Json.decodeFromString<ExternalLyricsPublication>(it) }.getOrNull()
                            } ?: return@Runnable
                            callbacks.onActivePlayerChanged(publication.playerPackage)
                            callbacks.onPublication(publication)
                        }
                        STOPPED -> clear(value)
                        CONNECTION_STATUS -> when (value) {
                            "registered", "registration_failed" -> onStatus(value)
                        }
                    }
                })
            }
            true
        })
        private val rebind = Runnable { bindOnMain() }
        private var bound = false

        fun bind() { handler.post(rebind) }

        private fun reportStatus(value: String) = dispatch(Runnable {
            if (subscription === this) onStatus(value)
        })

        private fun bindOnMain() {
            if (subscription !== this || bound) return
            val intent = Intent().setClassName(appPackage, SuperLyricReceiverService::class.java.name)
            bound = runCatching { context.bindService(intent, this, Context.BIND_AUTO_CREATE) }
                .onFailure { Log.w(TAG, "Could not bind SuperLyric receiver", it) }
                .getOrDefault(false)
            reportStatus(if (bound) "binding" else "bind_failed")
            if (!bound) {
                runCatching { context.unbindService(this) }
                handler.postDelayed(rebind, 5_000)
            }
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (subscription !== this) return
            reportStatus("connected")
            try {
                Messenger(service).send(Message.obtain(null, SUBSCRIBE).apply { replyTo = endpoint })
            } catch (_: RemoteException) {
                restartBinding()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            reportStatus("disconnected")
            dispatch(Runnable { clear(null) })
        }
        override fun onBindingDied(name: ComponentName) = restartBinding()
        override fun onNullBinding(name: ComponentName) = restartBinding()

        private fun restartBinding() {
            if (subscription !== this) return
            reportStatus("disconnected")
            dispatch(Runnable { clear(null) })
            unbind()
            handler.removeCallbacks(rebind)
            handler.postDelayed(rebind, 5_000)
        }

        private fun clear(packageName: String?) {
            if (subscription !== this) return
            callbacks.onStopped(packageName)
            callbacks.onActivePlayerChanged(null)
        }

        fun close() {
            handler.removeCallbacks(rebind)
            handler.post { unbind() }
        }

        private fun unbind() {
            if (!bound) return
            bound = false
            runCatching { context.unbindService(this) }
        }
    }
}
