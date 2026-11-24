package shop.terramoor.github.io.settings

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

internal object GitHubUpdates {
    const val REPOSITORY = "Luckyumimi/Color-lyric"
    const val REPOSITORY_URL = "https://github.com/$REPOSITORY"
    const val RELEASES_URL = "$REPOSITORY_URL/releases"
    private const val API_URL = "https://api.github.com/repos/$REPOSITORY"
    private const val TIMEOUT_MS = 5_000
    private val releaseTag = Regex("v?(?:([0-9]+)-)?([0-9]+(?:\\.[0-9]+)*)", RegexOption.IGNORE_CASE)

    enum class Comparison { NEWER, UP_TO_DATE, UNKNOWN }

    sealed interface Result {
        data object Idle : Result
        data object Checking : Result
        data object NoRelease : Result
        data object Failed : Result
        data class Latest(val tag: String, val comparison: Comparison) : Result
    }

    suspend fun check(versionCode: Long, versionName: String): Result = withContext(Dispatchers.IO) {
        try {
            val (status, body) = request("/releases/latest")
            when {
                status == HttpURLConnection.HTTP_OK -> {
                    val tag = JSONObject(body).getString("tag_name")
                    if (tag.isBlank()) Result.Failed else Result.Latest(tag, compare(tag, versionCode, versionName))
                }
                status == HttpURLConnection.HTTP_NOT_FOUND && request("").first == HttpURLConnection.HTTP_OK -> {
                    Result.NoRelease
                }
                else -> Result.Failed
            }
        } catch (_: IOException) {
            Result.Failed
        } catch (_: JSONException) {
            Result.Failed
        }
    }

    private fun request(path: String): Pair<Int, String> {
        val connection = URL("$API_URL$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Color-lyric")
            val status = connection.responseCode
            val body = if (status == HttpURLConnection.HTTP_OK && path.isNotEmpty()) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                ""
            }
            return status to body
        } finally {
            connection.disconnect()
        }
    }

    internal fun compare(tag: String, versionCode: Long, versionName: String): Comparison {
        val remote = releaseTag.matchEntire(tag) ?: return Comparison.UNKNOWN
        val code = remote.groupValues[1]
        if (code.isNotEmpty()) {
            val remoteCode = code.toLongOrNull() ?: return Comparison.UNKNOWN
            return if (remoteCode > versionCode) Comparison.NEWER else Comparison.UP_TO_DATE
        }
        val local = releaseTag.matchEntire(versionName) ?: return Comparison.UNKNOWN
        val remoteParts = remote.groupValues[2].split('.')
        val localParts = local.groupValues[2].split('.')
        for (index in 0 until maxOf(remoteParts.size, localParts.size)) {
            val remoteNumber = remoteParts.getOrElse(index) { "0" }.toLongOrNull() ?: return Comparison.UNKNOWN
            val localNumber = localParts.getOrElse(index) { "0" }.toLongOrNull() ?: return Comparison.UNKNOWN
            if (remoteNumber > localNumber) return Comparison.NEWER
            if (remoteNumber < localNumber) return Comparison.UP_TO_DATE
        }
        return Comparison.UP_TO_DATE
    }
}
