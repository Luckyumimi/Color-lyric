package io.github.andrealtb.coloroslyrics.provider.universal

import android.util.AtomicFile
import android.content.Context
import java.io.File

internal object UniversalSnapshotStore {
    const val ACTION_SNAPSHOT_UPDATE = "shop.terramoor.github.io.builtinlyrics.ACTION_SNAPSHOT_UPDATE"
    const val ACTION_REQUEST_SNAPSHOT = "shop.terramoor.github.io.builtinlyrics.ACTION_REQUEST_SNAPSHOT"
    const val ACTION_REQUEST_PLAYER_BINDINGS_SYNC = "shop.terramoor.github.io.builtinlyrics.ACTION_REQUEST_PLAYER_BINDINGS_SYNC"
    const val ACTION_UPDATE_PLAYER_BINDINGS = "shop.terramoor.github.io.builtinlyrics.ACTION_UPDATE_PLAYER_BINDINGS"
    const val ACTION_INJECT_REAL_LYRIC = "shop.terramoor.github.io.builtinlyrics.ACTION_INJECT_REAL_LYRIC"
    const val ACTION_CLEAR_CACHE = "shop.terramoor.github.io.builtinlyrics.ACTION_CLEAR_CACHE"
    const val ACTION_UPDATE_SOURCE_CONFIG = "shop.terramoor.github.io.builtinlyrics.ACTION_UPDATE_SOURCE_CONFIG"
    const val ACTION_CACHE_REQUEST = "shop.terramoor.github.io.builtinlyrics.ACTION_CACHE_REQUEST"
    const val ACTION_CACHE_RESPONSE = "shop.terramoor.github.io.builtinlyrics.ACTION_CACHE_RESPONSE"
    const val EXTRA_CACHED_SONGS = "extra_cached_songs"
    const val EXTRA_CACHE_CLEARED = "extra_cache_cleared"
    const val EXTRA_SOURCE_PRIORITY = "extra_source_priority"
    const val EXTRA_PROVIDER_MODE = "extra_provider_mode"
    const val EXTRA_CONFIG_REVISION = "runtime_diagnostic_config_revision"
    const val EXTRA_WORD_TIMING_ENABLED = "extra_word_timing_enabled"
    const val EXTRA_TRANSLATION_ENABLED = "extra_translation_enabled"
    const val EXTRA_AUTO_TRANSLATE_ENABLED = "extra_auto_translate_enabled"
    const val EXTRA_AUTO_TRANSLATE_LANG = "extra_auto_translate_lang"
    const val EXTRA_RAW_LYRIC_ENABLED = "extra_raw_lyric_enabled"
    const val EXTRA_DEBUG_ENABLED = "extra_debug_enabled"
    const val EXTRA_DIRECT_MODE = "extra_direct_mode"
    const val EXTRA_CLOCK_LINE_FALLBACK_ENABLED = "extra_clock_line_fallback_enabled"
    const val EXTRA_BOUND_PACKAGES = "extra_bound_packages"
    const val EXTRA_OWNER_PACKAGE = "extra_owner_package"
    const val EXTRA_GENERATION = "extra_generation"
    const val EXTRA_LYRIC_INFO = "extra_lyric_info"
    const val EXTRA_SNAPSHOT = "extra_snapshot"
    const val EXTRA_ARTWORK = "extra_artwork"
    const val EXTRA_ARTWORK_REVISION = "extra_artwork_revision"
    const val EXTRA_CACHE_OPERATION = "extra_cache_operation"
    const val EXTRA_CACHE_KEY = "extra_cache_key"
    const val EXTRA_CACHE_PAYLOAD = "extra_cache_payload"
    const val EXTRA_CACHE_OFFSET = "extra_cache_offset"
    const val EXTRA_CACHE_TOTAL = "extra_cache_total"
    const val EXTRA_CACHE_OK = "extra_cache_ok"
    fun file(context: Context): File = File(context.filesDir, "builtin-lyric-snapshot.txt")

    private var lastWrittenText: String? = null

    fun write(context: Context, text: String) {
        val target = file(context)
        if (text == lastWrittenText && target.isFile) return
        target.parentFile?.mkdirs()
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
            lastWrittenText = text
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }

    fun read(context: Context): String? = runCatching {
        AtomicFile(file(context)).readFully().toString(Charsets.UTF_8)
    }.getOrNull()
}
