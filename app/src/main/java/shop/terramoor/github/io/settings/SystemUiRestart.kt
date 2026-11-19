package shop.terramoor.github.io.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

internal object SystemUiRestart {
    enum class Result { SENT, ROOT_REQUIRED, FAILED }

    // Android restarts SystemUI after its process exits.
    private val command = """
        [ "${'$'}(id -u)" = 0 ] || exit 77
        pid=${'$'}(pidof -s com.android.systemui) || exit 78
        [ -n "${'$'}pid" ] && kill -TERM "${'$'}pid" || exit 78
    """.trimIndent()

    suspend fun request(): Result = runInterruptible(Dispatchers.IO) {
        val process = try {
            val discard = File("/dev/null")
            ProcessBuilder("su", "-c", command)
                .redirectOutput(discard)
                .redirectError(discard)
                .start()
        } catch (_: IOException) {
            return@runInterruptible Result.ROOT_REQUIRED
        } catch (_: SecurityException) {
            return@runInterruptible Result.ROOT_REQUIRED
        }
        try {
            process.outputStream.close()
            if (!process.waitFor(20, TimeUnit.SECONDS)) return@runInterruptible Result.ROOT_REQUIRED
            when (process.exitValue()) {
                0 -> Result.SENT
                78 -> Result.FAILED
                else -> Result.ROOT_REQUIRED
            }
        } catch (_: IOException) {
            Result.FAILED
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
