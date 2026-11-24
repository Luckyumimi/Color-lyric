package shop.terramoor.github.io.settings

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.SystemClock
import shop.terramoor.github.io.systemui.NativeContract

internal object CapsuleSettingsUpdate {
    private const val SNAPSHOT = "snapshot"
    private const val COMMITTED_AT = "committed_at"

    private fun action(packageName: String) = "$packageName.CAPSULE_SETTINGS_COMMITTED"
    private fun permission(packageName: String) = "$packageName.permission.CAPSULE_SETTINGS"

    fun publish(context: Context, settings: CapsuleSettings, targetPackage: String = NativeContract.SYSTEM_UI) {
        val intent = Intent(action(context.packageName)).setPackage(targetPackage)
            .putExtra(COMMITTED_AT, SystemClock.elapsedRealtimeNanos())
            .putExtra(SNAPSHOT, CapsuleBackup.encode(settings))
        context.sendBroadcast(intent, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
    }

    fun register(
        context: Context, modulePackage: String, handler: Handler,
        apply: (CapsuleSettings) -> Unit,
    ): BroadcastReceiver {
        val moduleUid = context.packageManager.getPackageUid(modulePackage, 0)
        val updateAction = action(modulePackage)
        val receiver = object : BroadcastReceiver() {
            private var lastCommit = 0L

            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != updateAction || sentFromUid != moduleUid || sentFromPackage != modulePackage) return
                val committedAt = intent.getLongExtra(COMMITTED_AT, 0L)
                if (committedAt <= lastCommit) return
                val snapshot = intent.getStringExtra(SNAPSHOT) ?: return
                val settings = runCatching { CapsuleBackup.decode(snapshot.byteInputStream()) }.getOrNull() ?: return
                lastCommit = committedAt
                apply(settings)
            }
        }
        context.registerReceiver(receiver, IntentFilter(updateAction), permission(modulePackage), handler, Context.RECEIVER_EXPORTED)
        return receiver
    }
}
