package shop.terramoor.github.io.settings

import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import shop.terramoor.github.io.R

internal enum class LyricsProvider(
    val packageId: String,
    @StringRes val title: Int,
    @DrawableRes val icon: Int,
    val downloadPage: String,
) {
    BRIDGE(
        "io.github.andrealtb.lockscreenlyrics",
        R.string.provider_bridge,
        R.drawable.provider_bridge,
        "https://github.com/Andrea-lyz/ColorOS-Live-Lyrics-Bridge/releases",
    ),
    UNIVERSAL(
        "io.github.andrealtb.coloroslyrics.provider.universal",
        R.string.provider_universal,
        R.drawable.provider_universal,
        "https://github.com/Andrea-lyz/ColorOS-Live-Lyrics-Providers/releases?q=universal-provider&expanded=true",
    );

    fun isInstalled(manager: PackageManager): Boolean = try {
        manager.getPackageInfo(packageId, PackageManager.PackageInfoFlags.of(0))
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
