package shop.terramoor.github.io.systemui

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.error.XposedFrameworkError
import shop.terramoor.github.io.diagnostics.DiagnosticLog
import shop.terramoor.github.io.diagnostics.DiagnosticProvider
import shop.terramoor.github.io.diagnostics.diagnosticStack
import shop.terramoor.github.io.settings.CapsuleSettings
import java.lang.reflect.Method

class TerraMoorModule : XposedModule() {
    private var targetProcess = false
    private var diagnostics: DiagnosticLog? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        targetProcess = param.processName == NativeContract.SYSTEM_UI
        if (targetProcess) Log.i(TAG, "onModuleLoaded: SystemUI pid=${android.os.Process.myPid()}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!targetProcess) return
        if (!param.isFirstPackage || param.packageName != NativeContract.SYSTEM_UI) {
            Log.i(TAG, "onPackageReady filtered: first=${param.isFirstPackage}, systemUi=${param.packageName == NativeContract.SYSTEM_UI}")
            return
        }
        val hooks = ArrayList<HookHandle>()
        try {
            val journal = DiagnosticProvider.systemUiLog { moduleApplicationInfo.packageName }
            diagnostics = journal
            journal.note("onModuleLoaded reached, SystemUI pid=${android.os.Process.myPid()}; onPackageReady first=true; Xposed API $apiVersion")
            Log.i(TAG, "TerraMoor module entered SystemUI, pid=${android.os.Process.myPid()}")
            val host = NativeContract.Host(param.classLoader, ::optionalMissing)
            journal.note("Required host contract resolved: classes=${NativeContract.HOST},${NativeContract.MEDIA}; media fields and methods verified")
            val runtime = CapsuleRuntime(host, journal, { getRemotePreferences(CapsuleSettings.GROUP) }, ::installPlugin)
            hooks += after(host.attach) { instance, _, _ -> runtime.hostChanged(instance) }
            journal.note("Required hook installed: ${host.attach.declaringClass.name}#${host.attach.name}")
            hooks += after(host.detach) { instance, _, _ -> runtime.hostDetached(instance) }
            journal.note("Required hook installed: ${host.detach.declaringClass.name}#${host.detach.name}")
            host.pluginAdded?.let { method -> installOptional(hooks, "Plugin-added hook") {
                after(method) { instance, _, _ -> runtime.hostChanged(instance) }
            } }
            host.pluginRemoved?.let { method -> installOptional(hooks, "Plugin-removed hook") {
                after(method) { instance, _, _ -> runtime.pluginRemoved(instance) }
            } }
            val publication = installMediaPublication(host, runtime, hooks)
            host.pauseTimeout?.let { timeout -> installOptional(hooks, "Media pause timeout hook") {
                hook(timeout.run).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    val retained = try {
                        chain.thisObject?.let(runtime::retainPausedCapsule) == true
                    } catch (failure: Throwable) {
                        if (!isExpectedFailure(failure)) throw failure
                        report("Media pause timeout", failure)
                        false
                    }
                    if (retained) null else chain.proceed()
                }
            } }
            host.release?.let { method -> installOptional(hooks, "Media release hook") {
                after(method) { instance, _, _ -> runtime.mediaReleased(instance) }
            } }
            journal.note("Required media publication hook installed: ${publication.declaringClass.name}#${publication.name}")
            Log.i(TAG, "SystemUI hooks ready")
            journal.note("SystemUI host hooks installed")
        } catch (failure: Throwable) {
            if (!isExpectedFailure(failure)) throw failure
            hooks.forEach { it.unhook() }
            report("Host contract", failure)
        }
    }

    private fun installMediaPublication(host: NativeContract.Host, runtime: CapsuleRuntime,
                                        hooks: MutableList<HookHandle>): Method {
        host.send?.let { send ->
            try {
                hooks += after(send) { instance, _, result -> runtime.mediaChanged(instance, result == true, null) }
                return send
            } catch (failure: Throwable) {
                if (!isExpectedFailure(failure)) throw failure
                report("Primary media publication hook", failure)
            }
        }
        val dispatch = host.dispatchMethod()
        hooks += after(dispatch) { instance, args, result ->
            if (result == true) {
                if (args.firstOrNull() == "cancelLiveAlert") runtime.mediaReleased(instance)
                else (args.getOrNull(1) as? StatusBarNotification)?.let { runtime.mediaChanged(instance, true, it) }
            }
        }
        return dispatch
    }

    private fun installPlugin(plugin: NativeContract.Plugin, runtime: CapsuleRuntime): List<HookHandle> {
        val hooks = ArrayList<HookHandle>()
        try {
            hooks += after(plugin.draw) { receiver, args, _ ->
                (args.firstOrNull() as? Canvas)?.let { runtime.draw(receiver, it) }
            }
            diagnostics?.note("Required plugin hook installed: ${plugin.draw.declaringClass.name}#${plugin.draw.name}")
            plugin.closeAnimation?.let { installMediaClose(hooks, it, runtime) }
            plugin.animation?.let { animation -> installOptional(hooks, "Content animation hook") {
                hook(animation.contentFrame).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    val restore = try {
                        chain.thisObject?.let(runtime::stabilizeMediaContentFrame)
                    } catch (failure: Throwable) {
                        if (!isExpectedFailure(failure)) throw failure
                        report("Media content layout", failure)
                        null
                    }
                    try {
                        chain.proceed()
                    } finally {
                        restore?.invoke()
                    }
                }
            } }
            plugin.contentChanged?.let { method -> installOptional(hooks, "Early content update hook") {
                after(method) { receiver, _, _ -> runtime.contentChanged(receiver) }
            } }
            plugin.layout?.let { method -> installOptional(hooks, "Plugin layout hook") {
                after(method) { receiver, _, _ -> runtime.containerLaidOut(receiver) }
            } }
            return hooks
        } catch (failure: Throwable) {
            hooks.forEach { it.unhook() }
            throw failure
        }
    }

    private fun installMediaClose(hooks: MutableList<HookHandle>, contract: NativeContract.Plugin.CloseAnimation,
                                  runtime: CapsuleRuntime) {
        val building = ThreadLocal<NativeContract.Plugin.CloseAnimation.Shape>()
        var geometryHook: HookHandle? = null
        try {
            geometryHook = after(contract.buildGeometry) { helper, _, result ->
                val shape = building.get()?.takeIf { it.helper === helper } ?: return@after
                (result as? ValueAnimator)?.let { runtime.anchorMediaClose(shape, it) }
            }
            hooks += hook(contract.build).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val previous = building.get()
                val shape = try {
                    chain.thisObject?.let(contract::shape)
                } catch (failure: Throwable) {
                    if (!isExpectedFailure(failure)) throw failure
                    report("Media close geometry", failure)
                    null
                }
                building.set(shape)
                try {
                    chain.proceed()
                } finally {
                    if (previous == null) building.remove() else building.set(previous)
                }
            }
            hooks += geometryHook
            diagnostics?.note("Optional media close geometry hooks installed")
        } catch (failure: Throwable) {
            geometryHook?.unhook()
            if (!isExpectedFailure(failure)) throw failure
            report("Optional media close geometry hooks", failure)
        }
    }

    private inline fun installOptional(hooks: MutableList<HookHandle>, feature: String, install: () -> HookHandle) {
        try {
            hooks += install()
            diagnostics?.note("Optional $feature installed")
        } catch (failure: Throwable) {
            if (!isExpectedFailure(failure)) throw failure
            report("Optional $feature", failure)
        }
    }

    private fun optionalMissing(feature: String, failure: NativeContract.Mismatch) = report("Optional $feature", failure)

    private fun report(step: String, failure: Throwable) {
        reportFailure(step, failure)
        diagnostics?.failure(step, failure)
    }

    private fun after(method: Method, action: (Any, List<Any?>, Any?) -> Unit): HookHandle =
        hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            val result = chain.proceed()
            try {
                chain.thisObject?.let { action(it, chain.args, result) }
            } catch (failure: Throwable) {
                if (!isExpectedFailure(failure)) throw failure
                report(method.name, failure)
            }
            result
        }
}

internal const val TAG = "TerraMoor"

internal fun isExpectedFailure(failure: Throwable): Boolean =
    failure is Exception || failure is LinkageError || failure is XposedFrameworkError

internal fun reportFailure(step: String, failure: Throwable) {
    diagnosticStack(step, failure).forEach { Log.w(TAG, it) }
}
