package shop.terramoor.github.io.lyrics.provider

import android.app.Application
import android.app.Instrumentation
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import com.juren233.hyperlyricsenhanced.common.UIConstants
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderCatalog as Catalog
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderHookHost
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlugin
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPreferencePolicy
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderSystemMediaRuntime
import com.juren233.hyperlyricsenhanced.provider.ProviderPackVerifier
import dalvik.system.InMemoryDexClassLoader
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.proify.lyricon.central.BridgeCentral
import io.github.proify.lyricon.central.CentralRuntime
import java.nio.ByteBuffer

class HleProviderModule : XposedModule() {
    private lateinit var processName: String
    private var centralListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) { processName = param.processName }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return
        val pkg = param.packageName
        if (pkg == "com.android.systemui" && processName == pkg) {
            guard { attachCentral() }
            return
        }
        val definition = Catalog.definitionForPackage(pkg) ?: return
        if (definition.systemMediaRuntime || !Catalog.shouldLoadIntoProcess(pkg, processName)) return
        guard {
            val prefs = getRemotePreferences(UIConstants.PREF_NAME)
            if (!prefs.getBoolean(Catalog.enabledKey(definition.id), false)) return@guard
            val version = prefs.getInt(Catalog.installedVersionKey(definition.id), 0)
            if (version <= 0) return@guard
            val name = Catalog.remoteFileName(definition.id, version)
            if (prefs.getString(Catalog.activeFileKey(pkg), null) != name || name !in listRemoteFiles()) return@guard
            val bytes = ParcelFileDescriptor.AutoCloseInputStream(openRemoteFile(name)).use { it.readNBytes(16 * 1024 * 1024 + 1) }
            val pack = ProviderPackVerifier.verify(bytes, expectedTargetPackage = pkg)
            check(pack.manifest.pluginId == definition.id && pack.manifest.versionCode == version)
            val parent = object : ClassLoader(javaClass.classLoader) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    if (name.startsWith("com.juren233.hle.providers.${definition.id}.")) throw ClassNotFoundException(name)
                    return super.loadClass(name, resolve)
                }
            }
            val loader = InMemoryDexClassLoader(pack.dexFiles.map { ByteBuffer.wrap(it) }.toTypedArray(), parent)
            val entry = loader.loadClass(pack.manifest.entryClass)
            val plugin = runCatching { entry.getField("INSTANCE").get(null) }
                .getOrElse { entry.getDeclaredConstructor().newInstance() } as OfficialProviderPlugin
            plugin.install(OfficialProviderHookHost(this, param.classLoader, pkg, processName, version))
            log(Log.INFO, null, "[Color lyric] Loaded ${definition.id} $version in $processName")
        }
    }

    private fun attachCentral() {
        val method = Instrumentation::class.java.getDeclaredMethod("callApplicationOnCreate", Application::class.java)
        hook(method).intercept { chain ->
            val result = chain.proceed()
            val app = chain.args.firstOrNull() as? Application
            if (app?.packageName == "com.android.systemui") guard { bindCentral(app) }
            result
        }
    }

    private fun bindCentral(app: Application) {
        if (centralListener != null) return
        val prefs = getRemotePreferences(UIConstants.PREF_NAME)
        OfficialProviderPreferencePolicy.configure(prefs)
        val main = Handler(Looper.getMainLooper())
        BridgeCentral.initialize(app)
        val relay = ExternalLyricsRelay.SystemUi(app)
        relay.startCentral()
        OfficialProviderSystemMediaRuntime.installIfAvailable(this, app)
        BridgeCentral.sendBootCompleted()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            val affected = OfficialProviderPreferencePolicy.affectedPlayerPackages(key)
            if (affected.isNotEmpty()) main.post {
                guard {
                    CentralRuntime.activePlayers.onOfficialProviderPreferencesChanged(affected)
                    if (Catalog.definitions.any { it.systemMediaRuntime && it.targetPackages.any(affected::contains) }) {
                        OfficialProviderSystemMediaRuntime.releaseAll()
                        OfficialProviderSystemMediaRuntime.installIfAvailable(this, app)
                    }
                }
            }
        }
        centralListener = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    private inline fun guard(block: () -> Unit) {
        try { block() } catch (failure: Exception) {
            log(Log.WARN, null, "[Color lyric] Lyricon host failed in $processName", failure)
        } catch (failure: LinkageError) {
            log(Log.WARN, null, "[Color lyric] Lyricon ABI mismatch in $processName", failure)
        }
    }
}
