package shop.terramoor.github.io.settings

import android.content.Context
import android.content.Intent
import io.github.andrealtb.coloroslyrics.provider.universal.LyricsSourceConfigStore
import io.github.andrealtb.coloroslyrics.provider.universal.UniversalAppIo
import io.github.andrealtb.coloroslyrics.provider.universal.UniversalBindingStore
import io.github.andrealtb.coloroslyrics.provider.universal.UniversalFeaturePrefs
import io.github.andrealtb.coloroslyrics.provider.universal.UniversalSettingsConstants
import io.github.andrealtb.coloroslyrics.provider.universal.UniversalSnapshotStore
import io.github.andrealtb.coloroslyrics.provider.universal.api.AppleMusicAuthState

/** App-side settings for the built-in lyrics provider. Only the system service receives updates. */
internal object BuiltinLyricsConfig {
    data class State(
        val translation: Boolean,
        val wordTiming: Boolean,
        val coarseClockFallback: Boolean,
        val rawLyric: Boolean,
        val debug: Boolean,
        val ttmlFirst: Boolean,
        val officialAppleMusic: Boolean,
        val appleStorefront: String,
        val appleLoggedIn: Boolean,
        val appleStatus: String,
        val sources: List<String>,
        val players: Set<String>,
        val providerMode: LyricsProviderMode,
    )

    fun install(context: Context) {
        val app = context.applicationContext
        UniversalFeaturePrefs.install(app)
        UniversalAppIo.execute { syncAll(app) }
    }

    fun read(context: Context): State {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(UniversalSettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val apple = AppleMusicAuthState.current
        val provider = LyricsProviderMode.fromId(prefs.getString(UniversalSettingsConstants.KEY_PROVIDER_MODE, null))
        return State(
            translation = prefs.getBoolean(UniversalSettingsConstants.KEY_TRANSLATION, true),
            wordTiming = prefs.getBoolean(UniversalSettingsConstants.KEY_WORD_TIMING, true),
            coarseClockFallback = prefs.getBoolean(UniversalSettingsConstants.KEY_CLOCK_LINE_FALLBACK, true),
            rawLyric = prefs.getBoolean(UniversalSettingsConstants.KEY_RAW_LYRIC, true),
            debug = prefs.getBoolean(UniversalSettingsConstants.KEY_DEBUG, false),
            ttmlFirst = UniversalFeaturePrefs.ttmlFirst(app),
            officialAppleMusic = apple.official,
            appleStorefront = apple.storefrontOverride,
            appleLoggedIn = apple.mediaUserToken.isNotBlank(),
            appleStatus = apple.status,
            sources = LyricsSourceConfigStore.read(app),
            players = UniversalBindingStore.getBoundPackages(app),
            providerMode = provider,
        )
    }

    fun setBoolean(context: Context, key: String, value: Boolean) {
        val app = context.applicationContext
        app.getSharedPreferences(UniversalSettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(key, value).apply()
        UniversalAppIo.execute { pushSourceSettings(app) }
    }

    fun setSources(context: Context, values: List<String>) {
        val app = context.applicationContext
        LyricsSourceConfigStore.write(app, values)
        UniversalAppIo.execute { pushSourceSettings(app) }
    }

    fun setPlayers(context: Context, values: Set<String>) {
        val app = context.applicationContext
        UniversalBindingStore.saveBoundPackages(app, values)
    }

    fun setProviderMode(context: Context, mode: LyricsProviderMode) {
        if (!mode.selectable) return
        val app = context.applicationContext
        app.getSharedPreferences(UniversalSettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(UniversalSettingsConstants.KEY_PROVIDER_MODE, mode.id).apply()
        UniversalAppIo.execute { pushSourceSettings(app) }
    }

    fun disabledLyriconProviders(context: Context): Set<String> = context
        .getSharedPreferences(UniversalSettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        .getStringSet("disabled_lyricon_providers", emptySet()).orEmpty().toSet()

    fun setDisabledLyriconProviders(context: Context, disabled: Set<String>) {
        val app = context.applicationContext
        app.getSharedPreferences(UniversalSettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet("disabled_lyricon_providers", disabled).apply()
        UniversalAppIo.execute { pushSourceSettings(app) }
    }

    fun setTtmlFirst(context: Context, value: Boolean) = UniversalFeaturePrefs.setTtmlFirst(context, value)

    fun setOfficialAppleMusic(context: Context, value: Boolean) =
        UniversalFeaturePrefs.updateAppleMusic(context) { it.copy(official = value) }

    fun setAppleStorefront(context: Context, value: String) =
        UniversalFeaturePrefs.updateAppleMusic(context) {
            it.copy(storefrontOverride = io.github.andrealtb.coloroslyrics.provider.universal.api.AppleMusicAuth.normalizeStorefront(value))
        }

    fun syncAll(context: Context) {
        val app = context.applicationContext
        val players = UniversalBindingStore.getBoundPackages(app)
        UniversalBindingStore.syncToSystem(app, players)
        pushSourceSettings(app)
        UniversalFeaturePrefs.pushToSystem(app)
        app.sendBroadcast(Intent(UniversalSnapshotStore.ACTION_REQUEST_SNAPSHOT).setPackage("android"))
    }

    private fun pushSourceSettings(context: Context) {
        val prefs = context.getSharedPreferences(UniversalSettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val settings = read(context)
        context.sendBroadcast(Intent(UniversalSnapshotStore.ACTION_UPDATE_SOURCE_CONFIG).apply {
            setPackage("android")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            putExtra(UniversalSnapshotStore.EXTRA_SOURCE_PRIORITY, LyricsSourceConfigStore.encode(settings.sources))
            putExtra(UniversalSnapshotStore.EXTRA_PROVIDER_MODE, settings.providerMode.id)
            putStringArrayListExtra("disabled_lyricon_providers", ArrayList(disabledLyriconProviders(context)))
            putExtra(UniversalSnapshotStore.EXTRA_TRANSLATION_ENABLED, prefs.getBoolean(UniversalSettingsConstants.KEY_TRANSLATION, true))
            putExtra(UniversalSnapshotStore.EXTRA_WORD_TIMING_ENABLED, prefs.getBoolean(UniversalSettingsConstants.KEY_WORD_TIMING, true))
            putExtra(UniversalSnapshotStore.EXTRA_CLOCK_LINE_FALLBACK_ENABLED, prefs.getBoolean(UniversalSettingsConstants.KEY_CLOCK_LINE_FALLBACK, true))
            putExtra(UniversalSnapshotStore.EXTRA_RAW_LYRIC_ENABLED, prefs.getBoolean(UniversalSettingsConstants.KEY_RAW_LYRIC, true))
            putExtra(UniversalSnapshotStore.EXTRA_DEBUG_ENABLED, prefs.getBoolean(UniversalSettingsConstants.KEY_DEBUG, false))
        })
    }

}
