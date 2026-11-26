package io.github.andrealtb.coloroslyrics.provider.universal

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.UserHandle

internal object UniversalBindingBroadcast {
    // Cross-user send is attempted only as system_server; app callers use sendBroadcast.
    @SuppressLint("MissingPermission")
    fun send(context: Context, intent: Intent): Result<Unit> {
        val allUser = runCatching {
            Class.forName("android.os.UserHandle").getDeclaredField("ALL").get(null) as? UserHandle
        }.getOrNull()
        val isSystemUid = android.os.Process.myUid() == android.os.Process.SYSTEM_UID
        if (isSystemUid && allUser != null) {
            val asUser = runCatching { context.sendBroadcastAsUser(intent, allUser) }
            if (asUser.isSuccess) {
                return asUser
            }
        }
        return runCatching { context.sendBroadcast(intent) }
    }
}
