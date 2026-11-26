package shop.terramoor.github.io.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Artwork is fetched only for manual search result rows and cached in memory. */
internal object SearchResultArtwork {
    private const val MAX_BYTES = 1024L * 1024
    private const val THUMBNAIL_SIZE = 176
    private val cache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val locks = Array(16) { Mutex() }
    private val client by lazy {
        OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequests = 4 })
            .callTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }

    suspend fun load(rawUrl: String): Bitmap? {
        val url = rawUrl.trim().toHttpUrlOrNull()?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }
            ?: return null
        val key = url.toString()
        cache.get(key)?.let { return it }
        return locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            cache.get(key) ?: download(Request.Builder().url(url).build())?.also { cache.put(key, it) }
        }
    }

    private suspend fun download(request: Request): Bitmap? = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val bitmap = response.use {
                    runCatching {
                        if (!continuation.isActive || !it.isSuccessful || it.body.contentType()?.type != "image") {
                            return@runCatching null
                        }
                        val source = it.body.source()
                        if (it.body.contentLength() > MAX_BYTES || source.request(MAX_BYTES + 1)) return@runCatching null
                        decodeThumbnail(source.readByteArray())
                    }.getOrNull()
                }
                continuation.resume(bitmap)
            }
        })
    }

    private fun decodeThumbnail(bytes: ByteArray): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        options.inJustDecodeBounds = false
        val longestEdge = maxOf(options.outWidth, options.outHeight)
        options.inSampleSize = 1
        while (longestEdge / options.inSampleSize >= 2 * THUMBNAIL_SIZE) options.inSampleSize *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
