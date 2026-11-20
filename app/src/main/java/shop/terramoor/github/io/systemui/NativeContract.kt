package shop.terramoor.github.io.systemui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal object NativeContract {
    const val SYSTEM_UI = "com.android.systemui"
    const val PLUGIN_PACKAGE = "com.oplus.systemui.plugins"
    const val ROOT = "com.oplus.systemui.plugins.seedling.capsule.ui.view.CapsuleContainerRoot"
    const val HOST = "com.oplus.systemui.statusbar.seeding.CapsulePluginContainerController"
    const val MEDIA = "com.oplus.systemui.media.seedling.livealert.MediaLiveAlertController"
    private const val DATA = "com.oplus.systemui.seedlingservice.mediaControl.SeedlingMediaData"
    private const val PLUGIN_INTERFACE = "com.android.systemui.plugins.statusbar.SeedlingPlugin"
    private const val CONTAINER = "com.oplus.systemui.plugins.seedling.capsule.ui.view.CapsuleContainer"
    private const val CAPSULE = "com.oplus.systemui.plugins.seedling.capsule.ui.view.CapsuleView"
    private const val SWITCH = "com.oplus.systemui.plugins.shared.template.component.view.ContentSwitchView"

    // The lookup label is assembled from hardcoded class, member, or resource names only.
    class Mismatch(val lookup: String, cause: Throwable? = null) : IllegalStateException(lookup, cause)

    data class Media(
        val key: String,
        val token: MediaSession.Token,
        val owner: String,
        val artwork: Icon?,
    )

    class Host(loader: ClassLoader, optionalMissing: (String, Mismatch) -> Unit) {
        private val host = loader.requiredClass(HOST)
        private val media = loader.requiredClass(MEDIA)
        private val data = loader.requiredClass(DATA)
        private val container = host.field("capsulePluginContainer", ViewGroup::class.java)
        private val list = media.field("mediaDataList", List::class.java)
        private val lastPlayerId = optional("active player ID", optionalMissing) {
            media.field("lastStatusBarPlayerId", String::class.java)
        }
        private val sbn = optional("stored media notification", optionalMissing) {
            media.field("sbn", StatusBarNotification::class.java)
        }
        private val playerId = data.method("getPlayerId", String::class.java)
        private val token = data.method("getToken", MediaSession.Token::class.java)
        private val pkg = data.method("getPackageName", String::class.java)
        private val artwork = optional("notification artwork", optionalMissing) {
            data.method("getArtworkIcon", Icon::class.java)
        }
        val attach = host.method("onViewAttached", Void.TYPE)
        val detach = host.method("onViewDetached", Void.TYPE)
        val pluginAdded = optional("plugin-added callback", optionalMissing) {
            host.method("onPluginAdded", Void.TYPE, loader.requiredClass(PLUGIN_INTERFACE), Context::class.java)
        }
        val pluginRemoved = optional("plugin-removed callback", optionalMissing) {
            host.method("onPluginRemove", Void.TYPE, loader.requiredClass(PLUGIN_INTERFACE))
        }
        val send = if (sbn == null) null else optional("media send callback", optionalMissing) {
            media.method("sendLiveAlertData", Boolean::class.javaPrimitiveType!!, String::class.java)
        }
        fun dispatchMethod() = media.method("sendDataToPlugin", Boolean::class.javaPrimitiveType!!,
            String::class.java, StatusBarNotification::class.java)
        val release = optional("media release callback", optionalMissing) { media.method("release", Void.TYPE) }

        fun view(controller: Any): ViewGroup = container.get(controller) as ViewGroup

        fun activeMedia(controller: Any, dispatched: StatusBarNotification?, report: (String) -> Unit): Media? {
            fun missing(reason: String): Media? = null.also { report(reason) }

            val notification = dispatched ?: (sbn?.get(controller) as? StatusBarNotification)
                ?: return missing("NO_NOTIFICATION")
            if (notification.packageName != SYSTEM_UI) return missing("FOREIGN_NOTIFICATION")
            val entries = notification.notification.extras?.getBundle("oplus.mediaData")
                ?: return missing("NO_MEDIA_BUNDLE")
            val cards = entries.getParcelableArrayList("playerArr", Bundle::class.java)
                ?: return missing("NO_PLAYER_ARRAY")
            val preferredId = (lastPlayerId?.get(controller) as? String)?.takeIf { it.isNotBlank() }
            val capsuleCards = cards.filter { it.getBundle("oplus.livealert.capsule") != null }.map { entry ->
                val card = entry.getBundle("oplus.livealert.card")
                val id = card?.getString("uniqueId")?.takeIf { it.isNotBlank() }
                val owner = card?.getBundle("primaryData")?.getString("pkg")?.takeIf { it.isNotBlank() }
                MediaCard(id, owner)
            }
            val selection = selectMediaCard(preferredId, capsuleCards)
            val matched = selection.card ?: return missing("${selection.route}, cards=${capsuleCards.size}/${cards.size}")
            val id = matched.id ?: return missing("CAPSULE_HAS_NO_PLAYER_ID")
            val owner = matched.owner ?: return missing("CAPSULE_HAS_NO_PLAYER_PACKAGE")
            val rows = list.get(controller) as? List<*> ?: return missing("NO_PLAYER_ROWS")
            val matches = rows.filter { data.isInstance(it) && playerId.invoke(it) == id }
            val row = matches.singleOrNull() ?: return missing("PLAYER_ROWS_MATCHED=${matches.size}/${rows.size}")
            if (pkg.invoke(row) != owner) return missing("CARD_PLAYER_OWNER_MISMATCH")
            val mediaToken = token.invoke(row) as? MediaSession.Token ?: return missing("NO_MEDIA_TOKEN")
            val icon = artwork?.invoke(row) as? Icon
            report("VERIFIED_${selection.route}, cards=${capsuleCards.size}/${cards.size}, artwork=${icon != null}")
            return Media(notification.key, mediaToken, owner, icon)
        }
    }

    data class Slot(val frame: ViewGroup, val stock: View)
    data class Target(val capsule: ViewGroup, val title: Slot, val translation: Slot,
                      val artwork: View, val waveform: View)

    class Plugin(loader: ClassLoader, resources: Resources, optionalMissing: (String, Mismatch) -> Unit) {
        private val sideIconId = resources.pluginId("img_primary")
        private val leftSectionId = resources.pluginId("left_section")
        private val rightSectionId = resources.pluginId("right_section")
        private val titleFrameId = resources.pluginId("fl_title")
        private val titleId = resources.pluginId("title")
        private val contentFrameId = resources.pluginId("fl_content")
        private val contentId = resources.pluginId("content")
        private val capsule = loader.requiredClass(CAPSULE)
        private val container = loader.requiredClass(CONTAINER)
        private val contentSwitch = loader.requiredClass(SWITCH)
        private val item = capsule.declaredMethod("getViewItem")
        // Compare only exact notification keys on a proven CapsuleView. The field name can change in OEM builds.
        private val keys = item.returnType.declaredFields.filter {
            it.type == String::class.java && !Modifier.isStatic(it.modifiers)
        }.onEach { it.isAccessible = true }
        private val find = optional("direct capsule lookup", optionalMissing) {
            container.method("f", capsule, String::class.java)
        }
        private val showing = optional("visible capsule key list", optionalMissing) {
            container.method("getShowingKeyList", List::class.java)
        }
        val layout = optional("plugin layout callback", optionalMissing) {
            container.method("onLayout", Void.TYPE, Boolean::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
        }
        val draw = capsule.method("onDraw", Void.TYPE, Canvas::class.java)
        val contentChanged = optional("early content update", optionalMissing) {
            container.method("h", Void.TYPE,
                loader.requiredClass("com.oplus.systemui.plugins.seedling.capsule.ui.model.f"),
                Boolean::class.javaPrimitiveType!!)
        }
        private val animatedWidth = optional("native animation width", optionalMissing) {
            capsule.method("getWidthChanging", Boolean::class.javaPrimitiveType!!) to
                capsule.method("getCapsuleDrawableWidth", Int::class.javaPrimitiveType!!)
        }
        val animation = optional("native content animation stabilizer", optionalMissing) { Animation(loader) }

        init {
            if (keys.isEmpty()) throw Mismatch("no instance String fields on ${item.returnType.name}")
        }

        class Animation(loader: ClassLoader) {
            private val page = loader.requiredClass("com.oplus.systemui.plugins.shared.template.page.entity.f")
            private val frame = loader.requiredClass("com.oplus.systemui.plugins.shared.template.page.entity.e")
            val contentFrame = frame.method("doFrame", Void.TYPE, Long::class.javaPrimitiveType!!)
            private val framePage = frame.field("b", page)
            private val frameOldWidth = frame.field("c", Int::class.javaPrimitiveType!!)
            private val pageView = page.field("k", ViewGroup::class.java)
            private val pageMaxWidth = page.field("q", Int::class.javaPrimitiveType!!)

            fun view(callback: Any): ViewGroup = pageView.get(framePage.get(callback)) as ViewGroup

            fun stabilize(callback: Any, view: ViewGroup): (() -> Unit)? {
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
        }

        fun hasOwner(active: ViewGroup, notificationKey: String): Boolean {
            val model = item.invoke(active) ?: return false
            return keys.any { it.get(model) == notificationKey }
        }

        fun drawingWidth(active: ViewGroup): Int {
            val (changing, width) = animatedWidth ?: return active.width
            return if (changing.invoke(active) == true) {
                (width.invoke(active) as Int).takeIf { it > 0 } ?: active.width
            } else active.width
        }

        fun findTarget(root: ViewGroup, notificationKey: String, retained: Target? = null,
                       report: ((String) -> Unit)? = null): Target? {
            fun missing(reason: String): Target? = null.also { report?.invoke(reason) }

            if (!root.isAttachedToWindow) return missing("ROOT_DETACHED")
            val hosts = root.descendants(4).filter { container.isInstance(it) }.toList()
            val host = hosts.singleOrNull() as? ViewGroup
                ?: return missing("CAPSULE_CONTAINERS=${hosts.size}")
            val listed = (showing?.invoke(host) as? List<*>)?.contains(notificationKey)
            val direct = find?.invoke(host, notificationKey) as? ViewGroup
            fun owns(view: ViewGroup): Boolean = view.isAttachedToWindow &&
                view.isInside(host) && hasOwner(view, notificationKey)

            val active = if (listed == false) {
                retained?.capsule?.takeIf(::owns) ?: return missing("MEDIA_KEY_NOT_VISIBLE")
            } else if (direct != null && owns(direct)) {
                direct
            } else {
                val matches = host.descendants(4).filterIsInstance<ViewGroup>()
                    .filter { it.isShown && capsule.isInstance(it) && owns(it) }.take(2).toList()
                matches.singleOrNull() ?: return missing("VISIBLE_OWNED_CAPSULES=${matches.size}")
            }
            if (showing == null && direct === active) {
                val sameOwner = host.descendants(4).filterIsInstance<ViewGroup>()
                    .filter { it.isShown && capsule.isInstance(it) && owns(it) }.take(2).count()
                if (sameOwner != 1) return missing("VISIBLE_OWNED_CAPSULES=$sameOwner")
            }
            val leftSection = active.views(leftSectionId).singleOrNull() as? ViewGroup
                ?: return missing("LEFT_SECTION_NOT_UNIQUE")
            val rightSection = active.views(rightSectionId).singleOrNull() as? ViewGroup
                ?: return missing("RIGHT_SECTION_NOT_UNIQUE")
            val artwork = leftSection.views(sideIconId).singleOrNull() ?: return missing("ARTWORK_VIEW_NOT_UNIQUE")
            val waveform = rightSection.views(sideIconId).singleOrNull() ?: return missing("WAVEFORM_VIEW_NOT_UNIQUE")
            val title = slot(rightSection, titleFrameId, titleId) ?: return missing("TITLE_SLOT_NOT_VERIFIED")
            val translation = slot(rightSection, contentFrameId, contentId)
                ?: return missing("TRANSLATION_SLOT_NOT_VERIFIED")
            if (title.frame.parent !== translation.frame.parent) return missing("SLOTS_HAVE_DIFFERENT_PARENTS")
            val target = Target(active, title, translation, artwork, waveform)
            if (listed == false && target != retained) return missing("RETAINED_CAPSULE_CHANGED")
            if (report != null) {
                val route = if (active === direct) "DIRECT" else if (listed == false) "RETAINED" else "VIEW_TREE"
                report("VERIFIED_$route, cutout=${active.display?.cutout != null}")
            }
            return target
        }

        fun belongs(container: Any?, root: ViewGroup): Boolean = (container as? View)?.isInside(root) == true

        private fun slot(section: ViewGroup, frameId: Int, stockId: Int): Slot? {
            val frame = section.views(frameId).singleOrNull() as? android.widget.FrameLayout ?: return null
            val stock = frame.children().singleOrNull { it.id == stockId && contentSwitch.isInstance(it) }
                ?: return null
            return Slot(frame, stock)
        }
    }

    fun root(host: ViewGroup, report: (String) -> Unit): ViewGroup? {
        val matches = host.descendants(4).filterIsInstance<ViewGroup>().filter { it.javaClass.name == ROOT }
            .take(2).toList()
        val root = matches.singleOrNull()
        if (root != null) report("VERIFIED_${if (root.parent === host) "DIRECT" else "NESTED"}")
        else report("ROOT_MATCHES=${matches.size}, children=${host.childCount}")
        return root
    }

    private inline fun <T> optional(feature: String, missing: (String, Mismatch) -> Unit, lookup: () -> T): T? =
        try {
            lookup()
        } catch (failure: Mismatch) {
            missing(feature, failure)
            null
        }

    private fun View.isInside(ancestor: ViewGroup): Boolean {
        var current = parent
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent
        }
        return false
    }

    private fun ViewGroup.descendants(depth: Int): Sequence<View> = sequence {
        if (depth == 0) return@sequence
        for (child in children()) {
            yield(child)
            if (child is ViewGroup) yieldAll(child.descendants(depth - 1))
        }
    }

    // OEM resource IDs are assigned at build time and may change across plugin versions.
    @SuppressLint("DiscouragedApi")
    private fun Resources.pluginId(name: String): Int = getIdentifier(name, "id", PLUGIN_PACKAGE).also {
        if (it == 0) throw Mismatch("resource $PLUGIN_PACKAGE:id/$name")
    }

    private fun ViewGroup.children(): Sequence<View> = (0 until childCount).asSequence().map(::getChildAt)
    private fun ViewGroup.views(id: Int): Sequence<View> = sequence {
        for (child in children()) {
            if (child.id == id) yield(child)
            if (child is ViewGroup) yieldAll(child.views(id))
        }
    }

    private fun ClassLoader.requiredClass(name: String): Class<*> = try {
        loadClass(name)
    } catch (failure: ClassNotFoundException) {
        throw Mismatch("class $name", failure)
    }

    private fun Class<*>.field(name: String, expected: Class<*>): Field {
        val lookup = "$this#$name"
        val field = try {
            getDeclaredField(name)
        } catch (failure: NoSuchFieldException) {
            throw Mismatch("missing field $lookup", failure)
        }
        if (!expected.isAssignableFrom(field.type)) throw Mismatch("field type $lookup")
        return field.apply { isAccessible = true }
    }

    private fun Class<*>.declaredMethod(name: String, vararg args: Class<*>): Method = try {
        getDeclaredMethod(name, *args).apply { isAccessible = true }
    } catch (failure: NoSuchMethodException) {
        throw Mismatch("missing method $this#$name", failure)
    }

    private fun Class<*>.method(name: String, result: Class<*>, vararg args: Class<*>): Method =
        declaredMethod(name, *args).also {
            if (it.returnType != result) throw Mismatch("method return type $this#$name")
        }
}
