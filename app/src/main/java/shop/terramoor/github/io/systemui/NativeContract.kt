package shop.terramoor.github.io.systemui

import android.animation.Animator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
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
    const val MEDIA_COS16 = "com.oplus.systemui.media.seedling.MediaSeedling"
    private const val MEDIA_CONTROLLER_COS16 = "com.oplus.systemui.media.seedling.OplusMediaSeedlingController"
    private const val DATA = "com.oplus.systemui.seedlingservice.mediaControl.SeedlingMediaData"
    private const val PLUGIN_INTERFACE = "com.android.systemui.plugins.statusbar.SeedlingPlugin"
    private const val CONTAINER = "com.oplus.systemui.plugins.seedling.capsule.ui.view.CapsuleContainer"
    private const val CAPSULE = "com.oplus.systemui.plugins.seedling.capsule.ui.view.CapsuleView"
    private const val SWITCH = "com.oplus.systemui.plugins.shared.template.component.view.ContentSwitchView"
    internal const val COS16_KEY_PREFIX = "seedling_media:"

    // The lookup label is assembled from hardcoded class, member, or resource names only.
    class Mismatch(val lookup: String, cause: Throwable? = null) : IllegalStateException(lookup, cause)

    data class Media(
        val key: String,
        val token: MediaSession.Token,
        val owner: String,
        val artwork: Icon?,
    )

    data class PauseOwner(val controller: Any, val key: String, val token: MediaSession.Token, val packageName: String)

    class Host(loader: ClassLoader, optionalMissing: (String, Mismatch) -> Unit) {
        private val host = loader.requiredClass(HOST)
        private val liveAlertMedia = try {
            loader.loadClass(MEDIA)
        } catch (_: ClassNotFoundException) {
            null
        }
        private val seedlingMedia = if (liveAlertMedia == null) loader.requiredClass(MEDIA_COS16) else null
        private val media = liveAlertMedia ?: seedlingMedia!!
        val isSeedlingMediaMode: Boolean = liveAlertMedia == null
        val mediaClassName: String = media.name
        private val data = loader.requiredClass(DATA)
        private val container = host.field("capsulePluginContainer", ViewGroup::class.java)
        private val list = if (liveAlertMedia != null) media.field("mediaDataList", List::class.java) else null
        private val lastPlayerId = if (liveAlertMedia != null) optional("active player ID", optionalMissing) {
            media.field("lastStatusBarPlayerId", String::class.java)
        } else null
        private val sbn = if (liveAlertMedia != null) optional("stored media notification", optionalMissing) {
            media.field("sbn", StatusBarNotification::class.java)
        } else null
        private val cos16ControllerField = if (seedlingMedia != null) optional("ColorOS 16 media controller", optionalMissing) {
            seedlingMedia.declaredFields.firstOrNull { it.name == "oplusMediaSeedlingController" }
                ?.apply { isAccessible = true }
                ?: throw Mismatch("missing field ${seedlingMedia.name}#oplusMediaSeedlingController")
        } else null
        private val cos16SortedEntries = if (seedlingMedia != null) optional("ColorOS 16 sorted entries", optionalMissing) {
            loader.requiredClass(MEDIA_CONTROLLER_COS16).method("getSortedEntries", List::class.java)
        } else null
        private val playerId = data.method("getPlayerId", String::class.java)
        private val token = data.method("getToken", MediaSession.Token::class.java)
        private val pkg = data.method("getPackageName", String::class.java)
        private val stateMethod = if (seedlingMedia != null) optional("media playback state", optionalMissing) {
            data.method("getState", Int::class.javaPrimitiveType!!)
        } else null
        private val activeMethod = if (seedlingMedia != null) optional("media active flag", optionalMissing) {
            data.method("getActive", Boolean::class.javaPrimitiveType!!)
        } else null
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
        val send = if (liveAlertMedia == null || sbn == null) null else optional("media send callback", optionalMissing) {
            media.method("sendLiveAlertData", Boolean::class.javaPrimitiveType!!, String::class.java)
        }
        fun dispatchMethod() = media.method("sendDataToPlugin", Boolean::class.javaPrimitiveType!!,
            String::class.java, StatusBarNotification::class.java)
        val cos16MediaLoaded = if (seedlingMedia != null) optional("ColorOS 16 onMediaDataLoaded", optionalMissing) {
            seedlingMedia.method("onMediaDataLoaded", Void.TYPE, String::class.java, data, Int::class.javaPrimitiveType!!)
        } else null
        val cos16MediaRemoved = if (seedlingMedia != null) optional("ColorOS 16 onMediaDataRemoved", optionalMissing) {
            seedlingMedia.method("onMediaDataRemoved", Void.TYPE, String::class.java)
        } else null
        val cos16UpdateSeedlings = if (seedlingMedia != null) optional("ColorOS 16 updateSeedlings", optionalMissing) {
            seedlingMedia.method("updateSeedlings", Void.TYPE,
                Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
        } else null
        val release = optional("media release callback", optionalMissing) { media.method("release", Void.TYPE) }
        val pauseTimeout = if (liveAlertMedia == null || lastPlayerId == null || sbn == null || list == null) null else optional("media pause timeout", optionalMissing) {
            PauseTimeout(loader, lastPlayerId, sbn, list)
        }

        inner class PauseTimeout(
            loader: ClassLoader,
            private val activeId: Field,
            private val notification: Field,
            private val mediaList: Field,
        ) {
            // On the verified host, R8 shares this Runnable class with the release task.
            private val callback = loader.requiredClass("$MEDIA\$release\$1")
            val run = callback.method("run", Void.TYPE)
            private val receiver = callback.field("this\$0", media)
            private val task = media.field("statusBarTimeoutRunnable", Runnable::class.java)
            private val handler = media.field("myHandler", Handler::class.java)
            private val showsOnStatusBar = media.field("showOnStatusBar", Boolean::class.javaPrimitiveType!!)
            private val state = data.method("getState", Int::class.javaPrimitiveType!!)

            fun matches(owner: PauseOwner, runnable: Any): Boolean =
                receiver.get(runnable) === owner.controller && task.get(owner.controller) === runnable && isPaused(owner)

            fun restore(owner: PauseOwner, onFailure: (Throwable) -> Unit) {
                val nativeHandler = handler.get(owner.controller) as Handler
                nativeHandler.post {
                    try {
                        if (isPaused(owner)) (task.get(owner.controller) as Runnable).run()
                    } catch (failure: Throwable) {
                        if (!isExpectedFailure(failure)) throw failure
                        onFailure(failure)
                    }
                }
            }

            // Check first row (the OEM's status-bar player), not just the shared notification key.
            private fun isPaused(owner: PauseOwner): Boolean {
                val controller = owner.controller
                if (!showsOnStatusBar.getBoolean(controller)) return false
                if ((notification.get(controller) as? StatusBarNotification)?.key != owner.key) return false
                val row = (mediaList.get(controller) as? List<*>)?.iterator()?.let { if (it.hasNext()) it.next() else null }
                return data.isInstance(row) && playerId.invoke(row) == activeId.get(controller) &&
                    token.invoke(row) == owner.token && pkg.invoke(row) == owner.packageName &&
                    state.invoke(row) == PlaybackState.STATE_PAUSED
            }
        }

        fun view(controller: Any): ViewGroup = container.get(controller) as ViewGroup

        fun activeMedia(controller: Any, dispatched: StatusBarNotification?, report: (String) -> Unit): Media? {
            if (isSeedlingMediaMode) return activeSeedlingMedia(controller, report)
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
            val rows = list?.get(controller) as? List<*> ?: return missing("NO_PLAYER_ROWS")
            val matches = rows.filter { data.isInstance(it) && playerId.invoke(it) == id }
            val row = matches.singleOrNull() ?: return missing("PLAYER_ROWS_MATCHED=${matches.size}/${rows.size}")
            if (pkg.invoke(row) != owner) return missing("CARD_PLAYER_OWNER_MISMATCH")
            val mediaToken = token.invoke(row) as? MediaSession.Token ?: return missing("NO_MEDIA_TOKEN")
            val icon = artwork?.invoke(row) as? Icon
            report("VERIFIED_${selection.route}, cards=${capsuleCards.size}/${cards.size}, artwork=${icon != null}")
            return Media(notification.key, mediaToken, owner, icon)
        }

        private fun activeSeedlingMedia(controller: Any, report: (String) -> Unit): Media? {
            fun missing(reason: String): Media? = null.also { report(reason) }

            val lazyRef = cos16ControllerField?.get(controller) ?: return missing("COS16_NO_LAZY_CONTROLLER")
            val mediaCtrl = try {
                lazyRef.javaClass.getMethod("get").invoke(lazyRef)
            } catch (_: Throwable) {
                lazyRef
            } ?: return missing("COS16_NO_MEDIA_CONTROLLER")
            val rows = (cos16SortedEntries?.invoke(mediaCtrl) as? List<*>)
                ?.filter { it != null && data.isInstance(it) }
                ?: return missing("COS16_NO_SORTED_ENTRIES")
            if (rows.isEmpty()) return missing("COS16_EMPTY_MEDIA_LIST")

            val withToken = rows.filter { token.invoke(it) is MediaSession.Token }
            if (withToken.isEmpty()) return missing("COS16_NO_MEDIA_TOKEN")

            val chosen = withToken.firstOrNull { stateMethod?.invoke(it) == PlaybackState.STATE_PLAYING }
                ?: withToken.firstOrNull { activeMethod?.invoke(it) == true }
                ?: withToken.first()
            val id = (playerId.invoke(chosen) as? String)?.takeIf { it.isNotBlank() } ?: "default"
            val owner = (pkg.invoke(chosen) as? String)?.takeIf { it.isNotBlank() } ?: SYSTEM_UI
            val mediaToken = token.invoke(chosen) as MediaSession.Token
            val icon = artwork?.invoke(chosen) as? Icon
            report("COS16_SEEDLING_VERIFIED, rows=${rows.size}, state=${stateMethod?.invoke(chosen)}, artwork=${icon != null}")
            return Media("$COS16_KEY_PREFIX$id", mediaToken, owner, icon)
        }
    }

    data class Slot(val frame: ViewGroup, val stock: View)
    data class Target(val capsule: ViewGroup, val title: Slot, val translation: Slot,
                      val artwork: View, val waveform: View,
                      val leftSection: View? = null, val rightSection: View? = null) {
        fun isContentPage(view: ViewGroup): Boolean = view.isInside(capsule) &&
            artwork.isInside(view) && waveform.isInside(view)
    }

    class Plugin(loader: ClassLoader, resources: Resources, optionalMissing: (String, Mismatch) -> Unit) {
        private val sideIconId = resources.optionalPluginId("img_primary")
        private val leftSectionId = resources.pluginId("left_section")
        private val rightSectionId = resources.pluginId("right_section")
        private val titleFrameId = resources.optionalPluginId("fl_title")
        private val titleId = resources.optionalPluginId("title")
        private val contentFrameId = resources.optionalPluginId("fl_content")
        private val contentId = resources.optionalPluginId("content")
        // ColorOS 16 capsule resource IDs (res/2s.xml in com.oplus.systemui.plugins)
        private val cos16ArtworkId = resources.optionalPluginId("img_capsule")
        private val cos16TextId = resources.optionalPluginId("capsule_text")
        private val cos16TextNextId = resources.optionalPluginId("capsule_text_next")
        private val cos16LottieId = resources.optionalPluginId("lottie_view")
        private val capsule = loader.requiredClass(CAPSULE)
        private val container = loader.requiredClass(CONTAINER)
        private val contentSwitch = try {
            loader.loadClass(SWITCH)
        } catch (_: ClassNotFoundException) {
            null
        }
        private val isCos17Layout = sideIconId != 0 && titleFrameId != 0 && titleId != 0 &&
            contentFrameId != 0 && contentId != 0 && contentSwitch != null
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
        private val cos16DummyWaveforms = java.util.WeakHashMap<ViewGroup, View>()
        private val cos16BoundCapsules = java.util.WeakHashMap<ViewGroup, String>()
        val draw = capsule.method("onDraw", Void.TYPE, Canvas::class.java)
        val measure = if (isCos17Layout) null else optional("capsule measure callback", optionalMissing) {
            capsule.method("onMeasure", Void.TYPE, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
        }
        val contentChanged = optional("early content update", optionalMissing) {
            try {
                container.method("h", Void.TYPE,
                    loader.requiredClass("com.oplus.systemui.plugins.seedling.capsule.ui.model.f"),
                    Boolean::class.javaPrimitiveType!!)
            } catch (_: Mismatch) {
                container.method("i", Void.TYPE,
                    loader.requiredClass("g4.f"),
                    Boolean::class.javaPrimitiveType!!)
            }
        }
        private val animatedWidth = optional("native animation width", optionalMissing) {
            capsule.method("getWidthChanging", Boolean::class.javaPrimitiveType!!) to
                capsule.method("getCapsuleDrawableWidth", Int::class.javaPrimitiveType!!)
        }
        val drawableWidth = optional("native drawable width writer", optionalMissing) {
            capsule.method("setCapsuleDrawableWidth", Void.TYPE, Int::class.javaPrimitiveType!!)
        }
        val widthChange = optional("native width animation start", optionalMissing) {
            capsule.method("setCapsuleWidthChanged", Void.TYPE, Boolean::class.javaPrimitiveType!!)
        }
        val animation = optional("native content animation stabilizer", optionalMissing) { Animation(loader) }
        val closeAnimation = optional("native media close geometry", optionalMissing) {
            CloseAnimation(loader, container, capsule)
        }
        val mediaCardScene = optional("media card viewport handoff", optionalMissing) {
            MediaCardScene(loader, container)
        }
        val statusBarSpace = optional("status bar placeholder width", optionalMissing) {
            StatusBarSpace(loader)
        }

        init {
            if (keys.isEmpty()) throw Mismatch("no instance String fields on ${item.returnType.name}")
        }

        class MediaCardScene(loader: ClassLoader, container: Class<*>) {
            private val base = "com.oplus.systemui.plugins.seedling"
            private val controller = loader.requiredClass("$base.capsule.h")
            private val sceneType = loader.requiredClass("$base.animatorscenes.AnimatorSceneType")
            private val resolveContainer = controller.method("l", container)
            val begin = controller.method("v", Void.TYPE, sceneType)
            val end = controller.method("u", Void.TYPE, Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)

            init {
                val recognized = sceneType.enumConstants.orEmpty().mapNotNull { (it as? Enum<*>)?.name }
                if ("CAPSULE_TO_CARD" !in recognized || "CARD_TO_CAPSULE" !in recognized) {
                    throw Mismatch("media scene types ${sceneType.name}")
                }
            }

            fun container(instance: Any): ViewGroup? = resolveContainer.invoke(instance) as? ViewGroup
        }

        class StatusBarSpace(loader: ClassLoader) {
            private val type = loader.requiredClass("com.oplus.systemui.plugins.seedling.plugin.process.ChangeListenerManager")
            private val companionType = loader.requiredClass(type.name + "\$Companion")
            private val companion = type.field("Companion", companionType)
            private val getInstance = companionType.method("getInstance", type)
            private val integer = Int::class.javaPrimitiveType!!
            private val currentWidth = type.method("getPlaceHolderRawWidth", integer, integer)
            private val notifyWidth = type.method("notifyPlaceHolderWidth", Void.TYPE, integer, integer, String::class.java)
            private val manager by lazy { getInstance.invoke(companion.get(null)) }

            // Slot 0 is the regular status bar. The native API still combines other owners' widths.
            fun width(): Int = currentWidth.invoke(manager, 0) as Int

            fun publish(pixels: Int, reason: String) {
                notifyWidth.invoke(manager, 0, pixels, reason)
            }
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

        class CloseAnimation(loader: ClassLoader, container: Class<*>, capsule: Class<*>) {
            private val fragment = loader.requiredClass("com.oplus.systemui.plugins.seedling.capsule.animation.r")
            private val geometry = loader.requiredClass("com.heytap.log.collect.auto.d")
            private val bounds = loader.requiredClass("com.oplus.systemui.plugins.seedling.capsule.animation.q")
            private val timing = loader.requiredClass("com.oplus.systemui.plugins.seedling.capsule.animation.b")
            private val context = loader.requiredClass("com.oplus.systemui.plugins.seedling.engine.animatorengine.o")
            val build = fragment.method("g", Animator::class.java, context)
            val buildGeometry = geometry.method("C", ValueAnimator::class.java, bounds, bounds, timing)
            private val closeContainer = fragment.field("d", container)
            private val closeCapsule = fragment.field("e", capsule)
            private val closeGeometry = fragment.field("i", geometry)

            fun shape(fragment: Any) = Shape(
                checkNotNull(closeGeometry.get(fragment)),
                closeContainer.get(fragment) as ViewGroup,
                closeCapsule.get(fragment) as ViewGroup,
            )

            data class Shape(val helper: Any, val container: ViewGroup, val capsule: ViewGroup)
        }

        fun hasOwner(active: ViewGroup, notificationKey: String): Boolean {
            if (notificationKey.startsWith(COS16_KEY_PREFIX) && cos16BoundCapsules[active] == notificationKey) {
                return true
            }
            val model = item.invoke(active) ?: return false
            val capsuleKeys = keys.mapNotNull { it.get(model) as? String }
            if (notificationKey in capsuleKeys) return true
            if (notificationKey.startsWith(COS16_KEY_PREFIX)) {
                val playerId = notificationKey.removePrefix(COS16_KEY_PREFIX)
                return capsuleKeys.any { k ->
                    k == playerId ||
                        k.startsWith("268452006") ||
                        k.contains("media", ignoreCase = true) ||
                        k.contains("music", ignoreCase = true) ||
                        k.contains("seedling", ignoreCase = true) ||
                        k.contains(SYSTEM_UI, ignoreCase = true)
                }
            }
            return false
        }

        fun isAnimatingWidth(active: ViewGroup): Boolean = animatedWidth?.first?.invoke(active) == true

        fun drawableWidth(active: ViewGroup): Int? =
            animatedWidth?.second?.invoke(active) as? Int

        fun setDrawableWidth(active: ViewGroup, width: Int): Boolean =
            drawableWidth?.let { it.invoke(active, width); true } == true

        fun isSingleLane(host: ViewGroup): Boolean = (showing?.invoke(host) as? List<*>)?.size?.let { it <= 1 } == true

        fun supportsInterludeViewport(active: ViewGroup): Boolean {
            val parent = active.parent as? ViewGroup ?: return false
            return animatedWidth != null && container.isInstance(parent) && isSingleLane(parent)
        }

        fun animatedDrawingWidth(active: ViewGroup): Int? = if (isAnimatingWidth(active)) {
            drawableWidth(active)?.takeIf { it > 0 }
        } else null

        fun findTarget(root: ViewGroup, notificationKey: String, retained: Target? = null,
                       report: ((String) -> Unit)? = null): Target? {
            fun missing(reason: String): Target? = null.also { report?.invoke(reason) }

            if (!root.isAttachedToWindow) return missing("ROOT_DETACHED")
            val hosts = root.descendants(4).filter { container.isInstance(it) }.toList()
            val host = hosts.singleOrNull() as? ViewGroup
                ?: return missing("CAPSULE_CONTAINERS=${hosts.size}")
            val cos16Mode = notificationKey.startsWith(COS16_KEY_PREFIX)
            val showingKeys = (showing?.invoke(host) as? List<*>)?.mapNotNull { it as? String }
            val listed = if (cos16Mode) null else showingKeys?.contains(notificationKey)
            val direct = if (cos16Mode) null else find?.invoke(host, notificationKey) as? ViewGroup
            fun owns(view: ViewGroup): Boolean = view.isAttachedToWindow &&
                view.isInside(host) && hasOwner(view, notificationKey)

            val active = if (listed == false) {
                retained?.capsule?.takeIf(::owns) ?: return missing("MEDIA_KEY_NOT_VISIBLE")
            } else if (direct != null && owns(direct)) {
                direct
            } else {
                val visibleCapsules = host.descendants(4).filterIsInstance<ViewGroup>()
                    .filter { it.isShown && capsule.isInstance(it) && it.isAttachedToWindow }.toList()
                val owned = visibleCapsules.filter(::owns)
                when {
                    owned.size == 1 -> owned.single()
                    cos16Mode && visibleCapsules.size == 1 -> visibleCapsules.single()
                    else -> return missing("VISIBLE_OWNED_CAPSULES=${owned.size}/${visibleCapsules.size}, keys=$showingKeys")
                }
            }
            if (!cos16Mode && showing == null && direct === active) {
                val sameOwner = host.descendants(4).filterIsInstance<ViewGroup>()
                    .filter { it.isShown && capsule.isInstance(it) && owns(it) }.take(2).count()
                if (sameOwner != 1) return missing("VISIBLE_OWNED_CAPSULES=$sameOwner")
            }
            if (isCos17Layout) {
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
                val target = Target(active, title, translation, artwork, waveform, leftSection, rightSection)
                if (listed == false && target != retained) return missing("RETAINED_CAPSULE_CHANGED")
                if (report != null) {
                    val route = if (active === direct) "DIRECT" else if (listed == false) "RETAINED" else "VIEW_TREE"
                    report("VERIFIED_$route, cutout=${active.display?.cutout != null}")
                }
                return target
            }

            // ColorOS 16 capsule binding: never filter linearSections/artwork/rightWaveform by isShown.
            val maxIconBound = maxOf(active.height, 90) * 2
            val linearSections = active.descendants(6).filterIsInstance<android.widget.LinearLayout>()
                .filter { it.visibility != View.GONE && it.childCount > 0 }.toList()
            val leftSection = (active.views(leftSectionId).firstOrNull() as? ViewGroup)
                ?: linearSections.minByOrNull { it.left }
            val rightSection = (active.views(rightSectionId).firstOrNull() as? ViewGroup)
                ?: linearSections.filter { it !== leftSection }.maxByOrNull { it.left }

            val artwork = (if (cos16ArtworkId != 0) leftSection?.views(cos16ArtworkId)?.firstOrNull() else null)
                ?: leftSection?.descendants(5)?.firstOrNull {
                    it.visibility != View.GONE && it is android.widget.ImageView && it.width <= maxIconBound
                }
                ?: active.descendants(7).firstOrNull {
                    it.visibility != View.GONE && it is android.widget.ImageView && it.width <= maxIconBound
                }
                ?: leftSection
                ?: active

            val rightWaveform = (if (cos16LottieId != 0) rightSection?.views(cos16LottieId)?.firstOrNull() else null)
                ?: rightSection?.descendants(5)?.firstOrNull {
                    it.visibility != View.GONE && it !== artwork && it.width <= maxIconBound &&
                        (it is android.widget.ImageView || it.javaClass.simpleName.contains("Lottie", ignoreCase = true))
                }
                ?: cos16DummyWaveforms.getOrPut(active) { View(active.context) }

            val stockBg = active.descendants(6).firstOrNull {
                it !== artwork && it !== rightWaveform &&
                    it.javaClass.simpleName == "ImageFilterView" && it.width >= active.height * 2
            }
            val textContainer = rightSection?.takeIf { it is android.widget.FrameLayout }
                ?: active.descendants(4).filterIsInstance<android.widget.FrameLayout>().lastOrNull()
                ?: active
            val stockTitle = (if (cos16TextId != 0) textContainer.views(cos16TextId).firstOrNull() else null)
                ?: textContainer.descendants(4).firstOrNull { it is android.widget.TextView }
                ?: stockBg
                ?: textContainer
            val stockTranslation = (if (cos16TextNextId != 0) textContainer.views(cos16TextNextId).firstOrNull() else null)
                ?: textContainer.descendants(4).filterIsInstance<android.widget.TextView>().drop(1).firstOrNull()
                ?: stockTitle
            val titleSlot = Slot(textContainer, stockTitle)
            val translationSlot = Slot(textContainer, stockTranslation)
            val target = Target(active, titleSlot, translationSlot, artwork, rightWaveform, leftSection, rightSection)
            if (cos16Mode) cos16BoundCapsules[active] = notificationKey
            if (report != null) {
                val msg = "COS16_VERIFIED, keys=$showingKeys, cap=${active.width}x${active.height}, " +
                    "left=${leftSection?.left}..${leftSection?.right}, right=${rightSection?.left}..${rightSection?.right}, " +
                    "art=${artwork.javaClass.simpleName}(${artwork.width}x${artwork.height}), " +
                    "wave=${rightWaveform.javaClass.simpleName}(${rightWaveform.width}x${rightWaveform.height})"
                report(msg)
            }
            return target
        }

        fun belongs(container: Any?, root: ViewGroup): Boolean = (container as? View)?.isInside(root) == true

        private fun slot(section: ViewGroup, frameId: Int, stockId: Int): Slot? {
            val switcher = contentSwitch ?: return null
            val frame = section.views(frameId).singleOrNull() as? android.widget.FrameLayout ?: return null
            val stock = frame.children().singleOrNull { it.id == stockId && switcher.isInstance(it) }
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

    @SuppressLint("DiscouragedApi")
    private fun Resources.optionalPluginId(name: String): Int = getIdentifier(name, "id", PLUGIN_PACKAGE)

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
