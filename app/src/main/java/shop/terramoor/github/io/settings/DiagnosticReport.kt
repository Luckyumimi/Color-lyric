package shop.terramoor.github.io.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import io.github.libxposed.service.XposedService
import shop.terramoor.github.io.diagnostics.diagnosticFailure
import shop.terramoor.github.io.diagnostics.DiagnosticStore
import shop.terramoor.github.io.diagnostics.diagnosticLine
import shop.terramoor.github.io.systemui.NativeContract
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.security.MessageDigest
import java.io.File

internal object DiagnosticReport {
    data class SettingsUi(val lockScreenConfirmed: Boolean, val restartReminder: Boolean, val translationHelpHidden: Boolean)

    private val fileDate = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss'Z'").withZone(ZoneOffset.UTC)

    fun fileName() = "TerraMoor-diagnostics-${fileDate.format(Instant.now())}.txt"

    fun collect(context: Context, state: TerraMoorApp.State, providers: Map<LyricsProvider, Boolean>,
                service: XposedService?, ui: SettingsUi): String {
        val pm = context.packageManager
        val self = pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        val events = DiagnosticStore.snapshot(context).sorted()
        val framework = if (service == null) "service not connected" else runCatching {
            "${diagnosticLine(service.frameworkName)} ${diagnosticLine(service.frameworkVersion)} " +
                "(${service.frameworkVersionCode}), API ${service.apiVersion}"
        }.getOrElse { diagnosticFailure("Framework query unavailable", it) }
        val targets = if (service == null) "service not connected" else runCatching {
            val systemUi = service.runningTargets.filter { it.processName == NativeContract.SYSTEM_UI }
            systemUi.joinToString { "pid=${it.pid}, state=${it.state}, loadedVersionCode=${it.loadedVersionCode}" }
                .ifBlank { "no SystemUI target reported" }
        }.getOrElse { diagnosticFailure("Target query unavailable", it) }
        return buildString {
            appendLine("TerraMoor diagnostics (schema 5; bounded to ${DiagnosticStore.MAX_LINES} events)")
            appendLine("Exported UTC = ${Instant.now()}")
            appendLine("App = ${context.packageName} ${diagnosticLine(self.versionName.orEmpty())} (${self.longVersionCode})")
            appendLine("Installed base APK SHA-256 = ${apkHash(self.applicationInfo?.sourceDir)}")
            appendLine("Install source = ${runCatching { pm.getInstallSourceInfo(context.packageName).installingPackageName ?: "unknown or sideload" }.getOrDefault("unavailable")}")
            appendLine("Approximate boot UTC = ${Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())}")
            appendLine("Device = ${diagnosticLine(Build.MANUFACTURER)} ${diagnosticLine(Build.MODEL)}")
            appendLine("Android = ${diagnosticLine(Build.VERSION.RELEASE)} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Firmware = ${diagnosticLine(Build.DISPLAY)}, Android incremental=${diagnosticLine(Build.VERSION.INCREMENTAL)}")
            appendLine("Device codename = ${diagnosticLine(Build.DEVICE)}")
            appendLine("SystemUI = ${pm.versionOf(NativeContract.SYSTEM_UI)}")
            appendLine("SystemUI plugin = ${pm.versionOf(NativeContract.PLUGIN_PACKAGE)}")
            appendLine("LSPosed connection/scope = ${state.connection}")
            appendLine("LSPosed framework = $framework")
            appendLine("LSPosed SystemUI target = $targets")
            appendLine("Enabled module state is not independently exposed when the LSPosed service is disconnected.")
            LyricsProvider.entries.forEach { provider ->
                val status = when (providers[provider]) {
                    true -> pm.versionOf(provider.packageId)
                    false -> "not installed"
                    null -> "not checked"
                }
                appendLine("Provider ${provider.name} = $status")
            }
            appendLine()
            appendLine("[Settings snapshot (${if (state.connection == TerraMoorApp.Connection.READY) "connected" else "app snapshot"})]")
            appendLine(CapsuleBackup.encode(state.settings, ui.translationHelpHidden))
            appendLine("Texture rendering: brightness is configurable, contrast=100%, saturation=200%, blur=100%; legacy saved values for these three parameters are ignored.")
            appendLine("Local UI: lockScreenConfirmed=${ui.lockScreenConfirmed}, restartReminder=${ui.restartReminder}")
            appendLine()
            appendLine("[Lyric tint: last observed SystemUI stages in retained history]")
            appendLine("Stages are independent observations; compare pid, session, track, calculation and timestamps before correlating them.")
            for (stage in listOf("settings", "metadata", "source", "cache", "extraction", "contrast", "palette", "frame", "draw")) {
                val marker = "[tint/$stage]"
                appendLine(events.lastOrNull { " [SystemUI/" in it && " $marker " in it }
                    ?: "$marker not observed (old build, no injection, no media, or aged out)")
            }
            appendLine()
            appendLine("[Recent TerraMoor events, UTC]")
            val lastSystemUi = events.lastOrNull { " [SystemUI/" in it }
            if (lastSystemUi == null) {
                appendLine("No SystemUI events received in the retained history. Check the LSPosed target above.")
            } else {
                appendLine("Most recent SystemUI event = ${lastSystemUi.substringBefore(' ')}")
            }
            events.forEach { appendLine(it) }
            appendLine()
            appendLine("[Additional information to provide separately]")
            appendLine("LSPosed logs after reboot, including module loading, SystemUI injection and full exceptions.")
            appendLine("Reboot and playback times, player and version, track, Provider result, screenshot source and other SystemUI modules.")
            appendLine("The base APK hash may differ from a distributed APK when the app uses split APKs.")
        }
    }

    private fun apkHash(path: String?): String {
        if (path == null) return "unavailable"
        return try {
        val digest = MessageDigest.getInstance("SHA-256")
        File(path).inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
        } catch (error: Exception) {
            "unavailable (${error.javaClass.simpleName})"
        }
    }

    private fun PackageManager.versionOf(packageName: String): String = try {
        val info = getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        "${diagnosticLine(info.versionName.orEmpty())} (${info.longVersionCode})"
    } catch (_: PackageManager.NameNotFoundException) {
        "unavailable"
    }
}
