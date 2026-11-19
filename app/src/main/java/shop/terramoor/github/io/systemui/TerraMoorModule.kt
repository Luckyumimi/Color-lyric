package shop.terramoor.github.io.systemui

import android.graphics.Canvas
import android.util.Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.error.XposedFrameworkError
import shop.terramoor.github.io.settings.CapsuleSettings
import java.lang.reflect.Method

class TerraMoorModule : XposedModule() {
    private var targetProcess = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        targetProcess = param.processName == TargetFirmware.SYSTEM_UI
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!targetProcess || !param.isFirstPackage || param.packageName != TargetFirmware.SYSTEM_UI ||
            !TargetFirmware.supportedBuild()) return
        val hooks = ArrayList<HookHandle>()
        try {
            val host = NativeContract.Host(param.classLoader)
            val runtime = CapsuleRuntime(host, { getRemotePreferences(CapsuleSettings.GROUP) }, ::installPlugin)
            hooks += after(host.attach) { instance, _, _ -> runtime.hostChanged(instance) }
            hooks += after(host.detach) { instance, _, _ -> runtime.hostDetached(instance) }
            hooks += after(host.pluginAdded) { instance, _, _ -> runtime.hostChanged(instance) }
            hooks += after(host.pluginRemoved) { instance, _, _ -> runtime.pluginRemoved(instance) }
            hooks += after(host.send) { instance, _, result -> runtime.mediaChanged(instance, result == true) }
            hooks += after(host.release) { instance, _, _ -> runtime.mediaReleased(instance) }
            Log.i(TAG, "SystemUI hooks ready")
        } catch (failure: Throwable) {
            if (!isExpectedFailure(failure)) throw failure
            hooks.forEach { it.unhook() }
            reportFailure("Host contract", failure)
        }
    }

    private fun installPlugin(plugin: NativeContract.Plugin, runtime: CapsuleRuntime): List<HookHandle> {
        val hooks = ArrayList<HookHandle>()
        try {
            hooks += hook(plugin.contentFrame).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val restore = try {
                    chain.thisObject?.let(runtime::stabilizeMediaContentFrame)
                } catch (failure: Throwable) {
                    if (!isExpectedFailure(failure)) throw failure
                    reportFailure("Media content layout", failure)
                    null
                }
                try {
                    chain.proceed()
                } finally {
                    restore?.invoke()
                }
            }
            hooks += after(plugin.contentChanged) { receiver, _, _ -> runtime.contentChanged(receiver) }
            hooks += after(plugin.layout) { receiver, _, _ -> runtime.containerLaidOut(receiver) }
            hooks += after(plugin.draw) { receiver, args, _ ->
                (args.firstOrNull() as? Canvas)?.let { runtime.draw(receiver, it) }
            }
            return hooks
        } catch (failure: Throwable) {
            hooks.forEach { it.unhook() }
            throw failure
        }
    }

    private fun after(method: Method, action: (Any, List<Any?>, Any?) -> Unit): HookHandle =
        hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            val result = chain.proceed()
            try {
                chain.thisObject?.let { action(it, chain.args, result) }
            } catch (failure: Throwable) {
                if (!isExpectedFailure(failure)) throw failure
                reportFailure(method.name, failure)
            }
            result
        }
}

internal const val TAG = "TerraMoor"

internal fun isExpectedFailure(failure: Throwable): Boolean =
    failure is Exception || failure is LinkageError || failure is XposedFrameworkError

internal fun reportFailure(step: String, failure: Throwable) {
    Log.w(TAG, "$step unavailable (${failure.javaClass.simpleName})")
}
