package io.github.andrealtb.coloroslyrics.provider.universal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import shop.terramoor.github.io.settings.BuiltinLyricsConfig
import shop.terramoor.github.io.settings.BuiltinLyricsEvents

class SnapshotReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != UniversalSnapshotStore.ACTION_SNAPSHOT_UPDATE &&
            action != UniversalSnapshotStore.ACTION_REQUEST_PLAYER_BINDINGS_SYNC &&
            action != UniversalSnapshotStore.ACTION_CACHE_RESPONSE) return
        if (action == UniversalSnapshotStore.ACTION_CACHE_RESPONSE) {
            BuiltinLyricsEvents.acceptCacheResponse(intent)
            return
        }
        val text = intent.getStringExtra(UniversalSnapshotStore.EXTRA_SNAPSHOT)
        val artwork = intent.getParcelableExtra(UniversalSnapshotStore.EXTRA_ARTWORK, Bitmap::class.java)
        if (action == UniversalSnapshotStore.ACTION_SNAPSHOT_UPDATE && text != null) {
            UniversalDiagnostics.snapshotReceived("manifest", text)
        }
        val appContext = context.applicationContext
        val pending = goAsync()
        UniversalAppIo.execute {
            try {
                runCatching {
                    if (action == UniversalSnapshotStore.ACTION_SNAPSHOT_UPDATE) {
                        text?.let { UniversalSnapshotStore.write(appContext, it) }
                    } else {
                        BuiltinLyricsConfig.syncAll(appContext)
                    }
                }
                if (action == UniversalSnapshotStore.ACTION_SNAPSHOT_UPDATE && text != null) {
                    // The home screen is paused while stopped; record here so rows reach their final status.
                    val snapshot = UniversalSnapshotUi.parse(text)
                    runCatching { UniversalLyricHistory.record(appContext, snapshot) }
                    UniversalFeaturePrefs.onSnapshotStatus(snapshot[UniversalFeatureConfig.SNAPSHOT_AM_STATUS])
                    BuiltinLyricsEvents.acceptSnapshot(appContext, text, artwork)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
