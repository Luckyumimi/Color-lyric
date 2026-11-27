package shop.terramoor.github.io.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Helpers scoped specifically to ColorOS 16 compatibility so ColorOS 17 behavior and UI remain untouched.
 */
internal object ColorOsCompat {
    private const val LIVE_ALERTS_SETTINGS_ACTION = "oplus.fluid.seeding.settings.main"

    val isColorOs16: Boolean by lazy { detectColorOs16() }

    private fun detectColorOs16(): Boolean {
        val oplusRom = readSystemProperty("ro.build.version.oplusrom").orEmpty()
        if (oplusRom.isNotBlank()) {
            val normalized = oplusRom.uppercase()
            if (normalized.startsWith("V17") || normalized.startsWith("17")) return false
            if (normalized.startsWith("V16") || normalized.startsWith("16")) return true
        }
        return Build.VERSION.SDK_INT == 36
    }

    private fun readSystemProperty(key: String): String? = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java, String::class.java)
        (get.invoke(null, key, "") as? String)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * Navigates directly to ColorOS 16's native Live Alerts settings screen where users can disable
     * the 5-minute automatic capsule-to-bubble collapse without hooking SystemUI.
     */
    suspend fun openLiveAlertsSettings(context: Context): Boolean {
        val directOpened = runCatching {
            context.startActivity(
                Intent(LIVE_ALERTS_SETTINGS_ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
        if (directOpened) return true

        val rootOpened = withContext(Dispatchers.IO) {
            runCatching {
                val proc = ProcessBuilder("su", "-c", "am start -a $LIVE_ALERTS_SETTINGS_ACTION")
                    .redirectErrorStream(true)
                    .start()
                proc.waitFor() == 0
            }.getOrDefault(false)
        }
        if (rootOpened) return true

        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        return false
    }
}
