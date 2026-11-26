package shop.terramoor.github.io.lyrics

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Handler
import android.os.Process
import android.os.SystemClock

/** The media-session owner announces its live mode to the capsule, including after either process starts. */
internal object CapsuleLyricModeSync {
    private const val REQUEST = "shop.terramoor.github.io.action.REQUEST_CAPSULE_LYRIC_MODE"
    private const val UPDATE = "shop.terramoor.github.io.action.CAPSULE_LYRIC_MODE"
    private const val SYSTEM_UI = "com.android.systemui"
    private const val ONLY_SUPER_LYRIC = "super_lyric_only"
    private const val SENT_AT = "sent_at"

    fun registerSystem(context: Context, currentMode: () -> Boolean) = withSystemIdentity {
        registerSystemReceiver(context, currentMode)
    }

    private fun registerSystemReceiver(context: Context, currentMode: () -> Boolean) {
        val systemUiUid = context.packageManager.getPackageUid(SYSTEM_UI, 0)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == REQUEST && sentFromUid == systemUiUid && sentFromPackage == SYSTEM_UI) {
                    publish(context, currentMode())
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(REQUEST), "android.permission.STATUS_BAR_SERVICE", null,
            Context.RECEIVER_EXPORTED)
        publish(context, currentMode())
    }

    fun registerSystemUi(context: Context, handler: Handler, apply: (Boolean) -> Unit) {
        val receiver = object : BroadcastReceiver() {
            private var lastUpdate = 0L

            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != UPDATE || sentFromUid != Process.SYSTEM_UID) return
                val sentAt = intent.getLongExtra(SENT_AT, 0L)
                if (sentAt <= lastUpdate || !intent.hasExtra(ONLY_SUPER_LYRIC)) return
                lastUpdate = sentAt
                apply(intent.getBooleanExtra(ONLY_SUPER_LYRIC, false))
            }
        }
        context.registerReceiver(receiver, IntentFilter(UPDATE), "android.permission.MANAGE_USERS", handler,
            Context.RECEIVER_EXPORTED)
        try {
            send(context, Intent(REQUEST).setPackage("android"))
        } catch (failure: Exception) {
            context.unregisterReceiver(receiver)
            throw failure
        }
    }

    fun publish(context: Context, superLyricOnly: Boolean) = withSystemIdentity {
        send(context, Intent(UPDATE).setPackage(SYSTEM_UI)
            .putExtra(ONLY_SUPER_LYRIC, superLyricOnly)
            .putExtra(SENT_AT, SystemClock.elapsedRealtimeNanos()))
    }

    private inline fun withSystemIdentity(action: () -> Unit) {
        val identity = Binder.clearCallingIdentity()
        try {
            action()
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun send(context: Context, intent: Intent) {
        val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
        context.sendBroadcast(intent, null, options)
    }
}
