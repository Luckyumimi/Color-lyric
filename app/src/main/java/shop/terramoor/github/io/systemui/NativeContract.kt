package shop.terramoor.github.io.systemui

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import java.lang.reflect.Field
import java.lang.reflect.Method

internal object NativeContract {
    const val ROOT = "com.oplus.systemui.plugins.seedling.capsule.ui.view.CapsuleContainerRoot"
    const val HOST = "com.oplus.systemui.statusbar.seeding.CapsulePluginContainerController"
    const val MEDIA = "com.oplus.systemui.media.seedling.livealert.MediaLiveAlertController"
    private const val DATA = "com.oplus.systemui.seedlingservice.mediaControl.SeedlingMediaData"
    private const val PLUGIN_INTERFACE = "com.android.systemui.plugins.statusbar.SeedlingPlugin"
    private const val CONTAINER = "com.oplus.systemui.plugins.seedling.capsule.ui.view.CapsuleContainer"
    private const val CAPSULE = "com.oplus.systemui.plugins.seedling.capsule.ui.view.CapsuleView"
    private const val SWITCH = "com.oplus.systemui.plugins.shared.template.component.view.ContentSwitchView"
    private const val SIDE_ICON = 0x7f0900ec
    private const val LEFT_SECTION = 0x7f090107
    private const val RIGHT_SECTION = 0x7f090153
    private const val TITLE_FRAME = 0x7f0900bf
    private const val TITLE = 0x7f0901cb
    private const val CONTENT_FRAME = 0x7f0900be
    private const val CONTENT = 0x7f09007d

    data class Media(
        val key: String,
        val token: MediaSession.Token,
        val owner: String,
        val artwork: Icon?,
    )

    class Host(loader: ClassLoader) {
        private val host = loader.loadClass(HOST)
        private val iface = loader.loadClass(PLUGIN_INTERFACE)
        private val media = loader.loadClass(MEDIA)
        private val data = loader.loadClass(DATA)
        private val container = host.field("capsulePluginContainer", ViewGroup::class.java)
        private val context = media.method("getSysContext", Context::class.java)
        private val list = media.field("mediaDataList", List::class.java)
        private val lastPlayerId = media.field("lastStatusBarPlayerId", String::class.java)
        private val sbn = media.field("sbn", StatusBarNotification::class.java)
        private val playerId = data.method("getPlayerId", String::class.java)
        private val token = data.method("getToken", MediaSession.Token::class.java)
        private val pkg = data.method("getPackageName", String::class.java)
        private val artwork = data.method("getArtworkIcon", Icon::class.java)
        val attach = host.method("onViewAttached", Void.TYPE)
        val detach = host.method("onViewDetached", Void.TYPE)
        val pluginAdded = host.method("onPluginAdded", Void.TYPE, iface, Context::class.java)
        val pluginRemoved = host.method("onPluginRemove", Void.TYPE, iface)
        val send = media.method("sendLiveAlertData", Boolean::class.javaPrimitiveType!!, String::class.java)
        val release = media.method("release", Void.TYPE)

        fun view(controller: Any): ViewGroup = container.get(controller) as ViewGroup
        fun context(controller: Any): Context = context.invoke(controller) as Context

        fun activeMedia(controller: Any): Media? {
            val notification = sbn.get(controller) as? StatusBarNotification ?: return null
            if (notification.packageName != TargetFirmware.SYSTEM_UI || notification.id != 268452006) return null
            val id = lastPlayerId.get(controller) as? String ?: return null
            val row = (list.get(controller) as? List<*>)?.singleOrNull {
                data.isInstance(it) && playerId.invoke(it) == id
            } ?: return null
            val entries = notification.notification.extras?.getBundle("oplus.mediaData") ?: return null
            val cards = entries.getParcelableArrayList("playerArr", Bundle::class.java) ?: return null
            val matched = cards.singleOrNull { entry ->
                entry.getBundle("oplus.livealert.capsule") != null &&
                    entry.getBundle("oplus.livealert.card")?.getString("uniqueId") == id
            } ?: return null
            val packageName = pkg.invoke(row) as? String ?: return null
            val cardPackage = matched.getBundle("oplus.livealert.card")
                ?.getBundle("primaryData")?.getString("pkg")
            if (packageName != cardPackage) return null
            return Media(notification.key, token.invoke(row) as? MediaSession.Token ?: return null,
                packageName, artwork.invoke(row) as? Icon)
        }
    }

    data class Slot(val frame: ViewGroup, val stock: View)
    data class Target(val capsule: ViewGroup, val title: Slot, val translation: Slot,
                      val artwork: View, val waveform: View)

    class Plugin(loader: ClassLoader) {
        private val capsule = loader.loadClass(CAPSULE)
        private val container = loader.loadClass(CONTAINER)
        private val contentSwitch = loader.loadClass(SWITCH)
        private val item = capsule.getDeclaredMethod("getViewItem").apply { isAccessible = true }
        // The item key is also used by CapsuleContainer.f(String) to look up its view.
        private val key = item.returnType.field("a", String::class.java)
        private val find = container.method("f", capsule, String::class.java)
        private val showing = container.method("getShowingKeyList", List::class.java)
        val layout = container.method("onLayout", Void.TYPE, Boolean::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
        val draw = capsule.method("onDraw", Void.TYPE, Canvas::class.java)
        val contentChanged = container.method("h", Void.TYPE,
            loader.loadClass("com.oplus.systemui.plugins.seedling.capsule.ui.model.f"), Boolean::class.javaPrimitiveType!!)
        private val widthChanging = capsule.method("getWidthChanging", Boolean::class.javaPrimitiveType!!)
        private val drawableWidth = capsule.method("getCapsuleDrawableWidth", Int::class.javaPrimitiveType!!)
        private val page = loader.loadClass("com.oplus.systemui.plugins.shared.template.page.entity.f")
        private val frame = loader.loadClass("com.oplus.systemui.plugins.shared.template.page.entity.e")
        val contentFrame = frame.method("doFrame", Void.TYPE, Long::class.javaPrimitiveType!!)
        private val framePage = frame.field("b", page)
        private val frameOldWidth = frame.field("c", Int::class.javaPrimitiveType!!)
        private val pageView = page.field("k", ViewGroup::class.java)
        private val pageMaxWidth = page.field("q", Int::class.javaPrimitiveType!!)

        fun contentFrameView(callback: Any): ViewGroup = pageView.get(framePage.get(callback)) as ViewGroup

        fun stabilizeContentFrame(callback: Any, view: ViewGroup): (() -> Unit)? {
            val oldWidth = frameOldWidth.getInt(callback)
            val maxWidth = pageMaxWidth.getInt(framePage.get(callback))
            if (oldWidth <= 0 || maxWidth <= 0) return null
            // A content update measures the template before creating its horizontal icon animator.
            // Use the new natural width on both sides of that comparison. Inner animations still run.
            view.measure(View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val width = view.measuredWidth
            if (width <= 0 || oldWidth == width) return null
            frameOldWidth.setInt(callback, width)
            return { frameOldWidth.setInt(callback, oldWidth) }
        }

        fun hasOwner(active: ViewGroup, notificationKey: String): Boolean =
            item.invoke(active)?.let { key.get(it) } == notificationKey

        fun drawingWidth(active: ViewGroup): Int = if (widthChanging.invoke(active) == true) {
            (drawableWidth.invoke(active) as Int).takeIf { it > 0 } ?: active.width
        } else active.width

        fun findTarget(root: ViewGroup, notificationKey: String, retained: Target? = null): Target? {
            if (root.javaClass.name != ROOT || !root.isAttachedToWindow) return null
            val host = root.children().filter { container.isInstance(it) }.singleOrNull() as? ViewGroup
                ?: return null
            val active = (find.invoke(host, notificationKey) as? ViewGroup) ?: retained?.capsule ?: return null
            if (active.parent !== host || !active.isAttachedToWindow) return null
            if (!hasOwner(active, notificationKey)) return null
            val listed = (showing.invoke(host) as? List<*>)?.contains(notificationKey) == true
            // An already verified view can drop out of the list during a native content swap.
            if (!listed && retained?.capsule !== active) return null
            val leftSection = active.views(LEFT_SECTION).singleOrNull() as? ViewGroup ?: return null
            val rightSection = active.views(RIGHT_SECTION).singleOrNull() as? ViewGroup ?: return null
            val artwork = leftSection.views(SIDE_ICON).singleOrNull() ?: return null
            val waveform = rightSection.views(SIDE_ICON).singleOrNull() ?: return null
            val title = slot(rightSection, TITLE_FRAME, TITLE) ?: return null
            val translation = slot(rightSection, CONTENT_FRAME, CONTENT) ?: return null
            if (title.frame.parent !== translation.frame.parent) return null
            val target = Target(active, title, translation, artwork, waveform)
            return if (listed || target == retained) target else null
        }

        fun belongs(container: Any?, root: ViewGroup): Boolean = (container as? View)?.parent === root

        private fun slot(section: ViewGroup, frameId: Int, stockId: Int): Slot? {
            val frame = section.views(frameId).singleOrNull() as? android.widget.FrameLayout ?: return null
            val stock = frame.children().singleOrNull { it.id == stockId && contentSwitch.isInstance(it) }
                ?: return null
            return Slot(frame, stock)
        }
    }

    fun root(host: ViewGroup): ViewGroup? = host.children()
        .singleOrNull { it.javaClass.name == ROOT } as? ViewGroup

    private fun ViewGroup.children(): Sequence<View> = (0 until childCount).asSequence().map(::getChildAt)
    private fun ViewGroup.views(id: Int): Sequence<View> = sequence {
        for (child in children()) {
            if (child.id == id) yield(child)
            if (child is ViewGroup) yieldAll(child.views(id))
        }
    }

    private fun Class<*>.field(name: String, expected: Class<*>): Field = getDeclaredField(name).apply {
        check(expected.isAssignableFrom(type)) { "Field signature: $name" }
        isAccessible = true
    }

    private fun Class<*>.method(name: String, result: Class<*>, vararg args: Class<*>): Method =
        getDeclaredMethod(name, *args).apply {
            check(returnType == result) { "Method signature: $name" }
            isAccessible = true
        }
}
