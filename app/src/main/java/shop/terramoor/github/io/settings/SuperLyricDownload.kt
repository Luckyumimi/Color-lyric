package shop.terramoor.github.io.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal object SuperLyricDownload {
    const val PACKAGE = "com.hchen.superlyric"
    private const val RELEASES = "https://github.com/HChenX/SuperLyric/releases/download/"
    private const val LATEST = "https://api.github.com/repos/HChenX/SuperLyric/releases/latest"
    private const val MAX_APK = 64L * 1024 * 1024
    private val RELEASE_NAME = Regex("SuperLyric-[A-Za-z0-9._-]+-release\\.apk", RegexOption.IGNORE_CASE)
    private val DIGEST = Regex("sha256:[a-f0-9]{64}")

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0)
    }.isSuccess

    private fun file(context: Context) = File(context.cacheDir, "provider-install/superlyric.apk")

    suspend fun download(context: Context) = withContext(Dispatchers.IO) {
        val metadata = JSONObject(HleProviderStore.fetch(LATEST, 256 * 1024).decodeToString())
        val assets = metadata.getJSONArray("assets")
        val asset = (0 until assets.length()).mapNotNull(assets::optJSONObject)
            .singleOrNull { RELEASE_NAME.matches(it.optString("name")) }
            ?: throw IOException("SuperLyric release APK missing")
        val name = asset.getString("name")
        val tag = metadata.getString("tag_name")
        val url = asset.getString("browser_download_url")
        val digest = asset.getString("digest")
        val size = asset.getLong("size")
        require(tag.matches(Regex("[A-Za-z0-9._-]+")) && url == "$RELEASES$tag/$name")
        require(digest.matches(DIGEST) && size in 1..MAX_APK)
        fun verified(location: String): ByteArray? = runCatching {
            val bytes = HleProviderStore.fetch(location, size)
            check(bytes.size.toLong() == size)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check("sha256:$hash" == digest)
            bytes
        }.getOrNull()
        val mirrors = coroutineScope {
            HleProviderStore.mirrorHosts.map { host ->
                async {
                    val location = "https://$host/$url"
                    val start = System.nanoTime()
                    if (HleProviderStore.probe(location)) location to System.nanoTime() - start else null
                }
            }.awaitAll()
        }
        val apk = mirrors.filterNotNull().sortedBy { it.second }.take(3).firstNotNullOfOrNull { (location, _) ->
            ensureActive()
            verified(location)
        } ?: verified(url) ?: throw IOException("Could not download verified SuperLyric APK")
        ensureActive()
        val output = file(context)
        output.parentFile?.mkdirs()
        output.writeBytes(apk)
        check(context.packageManager.getPackageArchiveInfo(output.path, 0)?.packageName == PACKAGE)
    }

    fun installIntent(context: Context): Intent {
        val apk = file(context)
        check(apk.isFile)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.ProviderInstall", apk)
        return Intent(Intent.ACTION_INSTALL_PACKAGE, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun permissionIntent(context: Context) = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.fromParts("package", context.packageName, null))
}
