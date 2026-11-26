package shop.terramoor.github.io.settings

import android.os.ParcelFileDescriptor
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderCatalog as Catalog
import com.juren233.hyperlyricsenhanced.provider.ProviderCatalogDocument
import com.juren233.hyperlyricsenhanced.provider.ProviderCatalogRelease
import com.juren233.hyperlyricsenhanced.provider.ProviderPackVerifier
import com.juren233.hyperlyricsenhanced.provider.ProviderReleaseSelection
import io.github.libxposed.service.XposedService
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

internal object HleProviderStore {
    private const val r2 = "https://hleplugins.juren233.top/"
    private const val github = "https://github.com/juren233/HLE-Providers/"
    internal val mirrorHosts = listOf(
        "gh-proxy.com", "gh-proxy.org", "cdn.gh-proxy.org", "ghproxy.monkeyray.net",
        "edgeone.gh-proxy.org", "ghfast.top", "ghproxy.net", "gh.llkk.cc",
    )
    private val upstreamCatalogs = listOf(
        "${r2}catalog/",
        "https://raw.githubusercontent.com/juren233/HLE-Providers/main/catalog/",
        "https://cdn.jsdelivr.net/gh/juren233/HLE-Providers@main/catalog/",
    )
    private val client = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val probeClient = client.newBuilder().callTimeout(4, TimeUnit.SECONDS).build()
    private val format = Json { ignoreUnknownKeys = true }

    data class Listing(val definition: Catalog.Definition, val release: ProviderCatalogRelease)
    data class Installed(val definition: Catalog.Definition, val version: String, val enabled: Boolean)

    fun installed(service: XposedService): List<Installed> {
        val prefs = service.getRemotePreferences(UIConstants.PREF_NAME)
        return Catalog.definitions.mapNotNull { entry ->
            if (prefs.getInt(Catalog.installedVersionKey(entry.id), 0) <= 0) return@mapNotNull null
            Installed(entry, prefs.getString(Catalog.installedVersionNameKey(entry.id), "").orEmpty(),
                prefs.getBoolean(Catalog.enabledKey(entry.id), false))
        }
    }

    suspend fun catalog(): List<Listing> = withContext(Dispatchers.IO) {
        for (base in upstreamCatalogs) {
            ensureActive()
            val catalog = runCatching {
                val bytes = fetch("${base}catalog.json", 512 * 1024L)
                val sig = Base64.getDecoder().decode(fetch("${base}catalog.sig", 4096).decodeToString().trim())
                check(ProviderPackVerifier.verifyDetachedSignature(bytes, sig))
                format.decodeFromString<ProviderCatalogDocument>(bytes.decodeToString()).also { check(it.schemaVersion == 1) }
            }.getOrNull() ?: continue
            return@withContext catalog.providers.mapNotNull { entry ->
                val definition = Catalog.definitionForId(entry.id) ?: return@mapNotNull null
                if (!definition.showInDownloadList || !entry.targetPackages.containsAll(definition.targetPackages)) return@mapNotNull null
                val release = ProviderReleaseSelection.selectLatestCompatible(entry, BuildConfig.VERSION_CODE) ?: return@mapNotNull null
                Listing(definition, release)
            }
        }
        throw IOException("Could not read signed HLE catalog")
    }

    suspend fun install(service: XposedService, item: Listing) = withContext(Dispatchers.IO) {
        val release = item.release
        val suffix = release.assetUrl.removePrefix(github)
        require(suffix.matches(Regex("releases/download/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+\\.hlp")))
        require(release.sha256.matches(Regex("[a-f0-9]{64}")))
        fun verifiedDownload(url: String) = runCatching {
            val bytes = fetch(url, 16 * 1024 * 1024L)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            require(hash == release.sha256)
            ProviderPackVerifier.verify(bytes).manifest.let { manifest ->
                check(manifest.pluginId == item.definition.id && manifest.versionCode == release.versionCode)
                check(manifest.targetPackages.containsAll(item.definition.targetPackages))
            }
            bytes
        }.getOrNull()
        val bytes = verifiedDownload(r2 + suffix) ?: run {
            val mirrors = coroutineScope {
                mirrorHosts.map { host ->
                    async {
                        val url = "https://$host/${release.assetUrl}"
                        val start = System.nanoTime()
                        if (probe(url)) url to (System.nanoTime() - start) else null
                    }
                }.awaitAll()
            }
            mirrors.filterNotNull().sortedBy { it.second }.take(3).firstNotNullOfOrNull { (url, _) ->
                ensureActive()
                verifiedDownload(url)
            } ?: verifiedDownload(release.assetUrl)
        } ?: throw IOException("Could not download verified HLE pack")
        ensureActive()
        synchronized(this@HleProviderStore) {
            val prefs = service.getRemotePreferences(UIConstants.PREF_NAME)
            val id = item.definition.id
            check(release.versionCode >= prefs.getInt(Catalog.installedVersionKey(id), 0))
            val filename = Catalog.remoteFileName(id, release.versionCode)
            if (filename in service.listRemoteFiles()) {
                check(readRemote(service, filename).contentEquals(bytes))
            } else {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(service.openRemoteFile(filename)).use { it.write(bytes) }
                    check(readRemote(service, filename).contentEquals(bytes))
                } catch (failure: Exception) {
                    runCatching { service.deleteRemoteFile(filename) }
                    throw failure
                }
            }
            val updated = prefs.edit()
                .putBoolean(Catalog.enabledKey(id), true)
                .putInt(Catalog.installedVersionKey(id), release.versionCode)
                .putString(Catalog.installedVersionNameKey(id), release.versionName.orEmpty())
            item.definition.targetPackages.forEach { updated.putString(Catalog.activeFileKey(it), filename) }
            check(updated.commit())
        }
    }

    fun setEnabled(service: XposedService, id: String, enabled: Boolean) {
        check(service.getRemotePreferences(UIConstants.PREF_NAME).edit().putBoolean(Catalog.enabledKey(id), enabled).commit())
    }

    fun requestScope(context: android.content.Context, service: XposedService, definition: Catalog.Definition) {
        if (definition.systemMediaRuntime) return
        val missing = definition.targetPackages.filter { name ->
            name !in service.scope && runCatching { context.packageManager.getApplicationInfo(name, 0) }.isSuccess
        }
        if (missing.isEmpty()) return
        service.requestScope(missing, object : XposedService.OnScopeEventListener {
            override fun onScopeRequestApproved(approved: List<String>) = Unit
            override fun onScopeRequestFailed(message: String) = Unit
        })
    }

    private fun readRemote(service: XposedService, name: String): ByteArray =
        ParcelFileDescriptor.AutoCloseInputStream(service.openRemoteFile(name)).use { it.readNBytes(16 * 1024 * 1024 + 1) }

    internal fun probe(url: String): Boolean = runCatching {
        val request = Request.Builder().url(url).header("Range", "bytes=0-3").build()
        probeClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val source = response.body.source()
            source.request(4) && source.readByteArray(4).contentEquals(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        }
    }.getOrDefault(false)

    internal fun fetch(url: String, limit: Long): ByteArray {
        check(url.startsWith("https://"))
        return client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful && response.body.contentLength() <= limit)
            val source = response.body.source()
            check(!source.request(limit + 1))
            source.readByteArray()
        }
    }
}
