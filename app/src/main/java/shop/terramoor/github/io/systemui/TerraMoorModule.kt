package shop.terramoor.github.io.systemui

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
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
            journal.note("Required host contract resolved: classes=${NativeContract.HOST},${host.mediaClassName}; cos16=${host.isSeedlingMediaMode}")
            val runtime = CapsuleRuntime(host, journal, moduleApplicationInfo.packageName,
                { getRemotePreferences(CapsuleSettings.GROUP) }, ::installPlugin)
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
            if (host.isSeedlingMediaMode) {
                installCos16AodHooks(param.classLoader, runtime, hooks)
            }
            journal.note("Required media publication hook installed: ${publication.declaringClass.name}#${publication.name}")
            Log.i(TAG, "SystemUI hooks ready")
            journal.note("SystemUI host hooks installed")
        } catch (failure: Throwable) {
            if (!isExpectedFailure(failure)) throw failure
            hooks.forEach { it.unhook() }
            report("Host contract", failure)
        }
    }

    private fun installCos16AodHooks(loader: ClassLoader, runtime: CapsuleRuntime, hooks: MutableList<HookHandle>) {
        // ColorOS 16-only AOD hooks: kept isolated behind host.isSeedlingMediaMode so ColorOS 17 is untouched.
        installOptional(hooks, "ColorOS 16 AOD all-player whitelist unlock") {
            val aodCls = loader.loadClass("com.oplusos.systemui.aod.mediapanel.AodMediaDataListener")
            val allowAllList = object : java.util.AbstractList<String>() {
                override val size: Int get() = 1
                override fun get(index: Int): String = "com.spotify.music"
                override fun contains(element: String?): Boolean = true
            }
            for (fieldName in listOf("aodMediaWhiteList", "aodMediaWhiteListExp")) {
                runCatching {
                    val f = aodCls.getDeclaredField(fieldName).apply { isAccessible = true }
                    f.set(null, allowAllList)
                }
            }
            val getter = aodCls.getDeclaredMethod("access\$getAodMediaWhiteListExp\$cp")
            hook(getter).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                if (runtime.isCos16AodWhitelistUnlocked()) allowAllList else chain.proceed()
            }.also {
                runCatching {
                    val getterCn = aodCls.getDeclaredMethod("access\$getAodMediaWhiteList\$cp")
                    hooks += hook(getterCn).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                        if (runtime.isCos16AodWhitelistUnlocked()) allowAllList else chain.proceed()
                    }
                }
            }
        }
        installOptional(hooks, "ColorOS 16 AOD AodMediaLayout overlay hook") {
            val layoutCls = loader.loadClass("com.oplus.systemui.aod.aodclock.off.media.AodMediaLayout")
            val ctor = layoutCls.getDeclaredConstructor(
                android.content.Context::class.java,
                android.util.AttributeSet::class.java,
                Int::class.javaPrimitiveType,
            )
            val rootField = runCatching {
                layoutCls.getDeclaredField("mRootView").apply { isAccessible = true }
            }.getOrNull()
            hook(ctor).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val res = chain.proceed()
                val layout = chain.thisObject as? android.view.ViewGroup
                if (layout != null) {
                    val nativeRoot = runCatching { rootField?.get(layout) as? android.view.View }.getOrNull()
                    runtime.bindAodMediaLayout(layout, nativeRoot)
                }
                res
            }.also {
                runCatching {
                    val setVis = layoutCls.getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType)
                    hooks += hook(setVis).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                        val res = chain.proceed()
                        val layout = chain.thisObject as? android.view.ViewGroup
                        val vis = chain.args.firstOrNull() as? Int
                        if (layout != null && vis == android.view.View.VISIBLE) {
                            val nativeRoot = runCatching { rootField?.get(layout) as? android.view.View }.getOrNull()
                            runtime.onAodMediaLayoutVisible(layout, nativeRoot)
                        }
                        res
                    }
                }
                runCatching {
                    val onMeta = layoutCls.getDeclaredMethod(
                        "onPrimaryMetadataOrStateChanged",
                        android.media.MediaMetadata::class.java,
                        Int::class.javaPrimitiveType,
                    )
                    hooks += hook(onMeta).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                        val res = chain.proceed()
                        val layout = chain.thisObject as? android.view.ViewGroup
                        if (layout != null) {
                            val nativeRoot = runCatching { rootField?.get(layout) as? android.view.View }.getOrNull()
                            runtime.onAodMediaLayoutVisible(layout, nativeRoot)
                        }
                        res
                    }
                }
            }
        }
        installOptional(hooks, "ColorOS 16 AOD DozeService steady-doze anti-flicker hook") {
            val dozeCls = loader.loadClass("com.android.systemui.doze.DozeService")
            val setState = dozeCls.getMethod("setDozeScreenState", Int::class.javaPrimitiveType)
            hook(setState).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val state = chain.args.firstOrNull() as? Int
                if (state == android.view.Display.STATE_DOZE_SUSPEND && runtime.shouldKeepAodDozeActive()) {
                    null
                } else {
                    chain.proceed()
                }
            }
        }
        installOptional(hooks, "ColorOS 16 AOD AodRootLayout visibility lifecycle hook") {
            val rootCls = loader.loadClass("com.oplus.systemui.aod.aodclock.off.AodRootLayout")
            val ctor = rootCls.getDeclaredConstructor(
                android.content.Context::class.java,
                android.util.AttributeSet::class.java,
                Int::class.javaPrimitiveType,
            )
            hook(ctor).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val res = chain.proceed()
                val v = chain.thisObject as? android.view.ViewGroup
                if (v != null) {
                    var lastVisible = false
                    v.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                        val nowVisible = view.visibility == android.view.View.VISIBLE && view.isShown
                        if (nowVisible) {
                            lastVisible = true
                            runtime.onAodRootVisibilityChanged(true)
                        } else if (lastVisible) {
                            lastVisible = false
                            runtime.onAodRootVisibilityChanged(false)
                        }
                    }
                }
                res
            }
        }
        installOptional(hooks, "ColorOS 16 AOD AodMediaViewHolder live lyrics hook") {
            val vhCls = loader.loadClass("com.oplusos.systemui.aod.mediapanel.AodMediaViewHolder")
            val ctor = vhCls.declaredConstructors.firstOrNull { it.parameterCount == 2 }
                ?: throw NativeContract.Mismatch("missing AodMediaViewHolder 2-arg ctor")
            val titleField = vhCls.getDeclaredField("titleText").apply { isAccessible = true }
            val artistField = vhCls.getDeclaredField("artistText").apply { isAccessible = true }
            val playerField = runCatching {
                vhCls.getDeclaredField("player").apply { isAccessible = true }
            }.getOrNull()
            val containerField = runCatching {
                vhCls.getDeclaredField("playerContainer").apply { isAccessible = true }
            }.getOrNull()
            val albumField = runCatching {
                vhCls.getDeclaredField("albumView").apply { isAccessible = true }
            }.getOrNull()
            hook(ctor).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val res = chain.proceed()
                val holder = chain.thisObject
                if (holder != null) {
                    val titleTv = titleField.get(holder) as? android.widget.TextView
                    val artistTv = artistField.get(holder) as? android.widget.TextView
                    val playerVg = runCatching { playerField?.get(holder) as? android.view.ViewGroup }.getOrNull()
                    val containerV = runCatching { containerField?.get(holder) as? android.view.View }.getOrNull()
                    val albumIv = runCatching { albumField?.get(holder) as? android.widget.ImageView }.getOrNull()
                    runtime.bindAodMediaViews(playerVg, containerV, albumIv, titleTv, artistTv)
                }
                res
            }
        }
    }

    private fun installMediaPublication(host: NativeContract.Host, runtime: CapsuleRuntime,
                                        hooks: MutableList<HookHandle>): Method {
        if (host.isSeedlingMediaMode) {
            val loaded = host.cos16MediaLoaded ?: throw NativeContract.Mismatch("missing ColorOS 16 onMediaDataLoaded")
            hooks += after(loaded) { instance, _, _ -> runtime.mediaChanged(instance, true, null) }
            host.cos16MediaRemoved?.let { removed ->
                installOptional(hooks, "ColorOS 16 onMediaDataRemoved hook") {
                    after(removed) { instance, _, _ -> runtime.mediaChanged(instance, true, null) }
                }
            }
            host.cos16UpdateSeedlings?.let { updated ->
                installOptional(hooks, "ColorOS 16 updateSeedlings hook") {
                    after(updated) { instance, _, _ -> runtime.mediaChanged(instance, true, null) }
                }
            }
            return loaded
        }
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
            if (runtime.isCos16Mode) {
                plugin.measure?.let { method -> installOptional(hooks, "ColorOS 16 capsule measure width hook") {
                    before(method, "ColorOS 16 capsule measure width") { receiver, _ ->
                        runtime.prepareCapsuleMeasure(receiver)
                    }
                } }
            }
            plugin.widthChange?.let { method -> installOptional(hooks, "Capsule width handoff hook") {
                after(method) { receiver, args, _ ->
                    if (args.firstOrNull() == true) runtime.nativeWidthAnimationStarted(receiver)
                }.also { runtime.viewportHookInstalled() }
            } }
            plugin.drawableWidth?.let { installOptional(hooks, "Native capsule foreground hook") {
                val draw = GradientDrawable::class.java.getDeclaredMethod("draw", Canvas::class.java)
                before(draw, "Native capsule foreground") { receiver, _ ->
                    runtime.beforeNativeForegroundDraw(receiver)
                }
            } }
            plugin.closeAnimation?.let { installMediaClose(hooks, it, runtime) }
            plugin.mediaCardScene?.let { installMediaCardScene(hooks, it, runtime) }
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

    private fun installMediaCardScene(hooks: MutableList<HookHandle>, scene: NativeContract.Plugin.MediaCardScene,
                                      runtime: CapsuleRuntime) {
        val installed = mutableListOf<HookHandle>()
        try {
            installed += before(scene.begin, "Media card viewport begin") { receiver, args ->
                val name = (args.firstOrNull() as? Enum<*>)?.name ?: return@before
                scene.container(receiver)?.let { runtime.beginMediaCardScene(it, name) }
            }
            installed += before(scene.end, "Media card viewport end") { receiver, _ ->
                scene.container(receiver)?.let(runtime::endMediaCardScene)
            }
            hooks += installed
            diagnostics?.note("Optional media card viewport handoff hooks installed")
        } catch (failure: Throwable) {
            installed.forEach { it.unhook() }
            if (!isExpectedFailure(failure)) throw failure
            report("Optional media card viewport handoff hooks", failure)
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

    private fun before(method: Method, step: String, action: (Any, List<Any?>) -> Unit): HookHandle =
        hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            try {
                chain.thisObject?.let { action(it, chain.args) }
            } catch (failure: Throwable) {
                if (!isExpectedFailure(failure)) throw failure
                report(step, failure)
            }
            chain.proceed()
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
