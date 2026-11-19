package shop.terramoor.github.io.systemui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

internal object TargetFirmware {
    const val SYSTEM_UI = "com.android.systemui"
    const val PLUGIN = "com.oplus.systemui.plugins"

    fun supportedBuild(): Boolean = Build.MODEL == "PKX110" && Build.DISPLAY == "PKX110_17.0.0.104(CN01)"

    fun supported(context: Context): Boolean {
        if (!supportedBuild()) return false
        return context.version(SYSTEM_UI) == "17.99.02" && context.version(PLUGIN) == "17.000.002"
    }

    private fun Context.version(pkg: String): String? = try {
        packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0)).versionName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
