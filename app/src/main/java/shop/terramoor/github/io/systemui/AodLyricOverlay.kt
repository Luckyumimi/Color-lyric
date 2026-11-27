package shop.terramoor.github.io.systemui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import shop.terramoor.github.io.lyrics.LyricCountdown
import java.lang.ref.WeakReference
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Borderless multi-line AOD lyric overlay (HyperLyrics Enhanced / Fluid Cloud style) injected into
 * ColorOS 16's `AodMediaViewHolder.player` (`AodMediaPanelView`) or `AodMediaLayout`.
 *
 * Features:
 * - Pure borderless OLED layout centered at 90% screen width (hides native card border/children).
 * - Compact header row: rounded album cover (`30dp`) beside 2 stacked rows (Title `12.5sp`, Artist `10.5sp`).
 * - Smooth upward slide + fade-in transition (`280ms`) on every lyric line / countdown switch.
 * - Gentle wave-bobbing 3-dot animation for intro & instrumental interludes.
 * - Holds `PowerManager.DRAW_WAKE_LOCK` (`0x80`) steadily across active playback ticks (with a
 *   rolling safety timeout and immediate release on pause/detach) to prevent LTPO
 *   `STATE_DOZE <-> STATE_DOZE_SUSPEND` screen flashes while keeping OLED frames updating smoothly.
 */
internal class AodLyricOverlay(
    private val onHostAttached: () -> Unit,
) {
    private var hostRef = WeakReference<ViewGroup>(null)
    private var nativeRootRef = WeakReference<View>(null)
    private var nativeAlbumRef = WeakReference<ImageView>(null)
    private var overlayRoot: LinearLayout? = null
    private var headerRow: LinearLayout? = null
    private var coverView: ImageView? = null
    private var titleView: TextView? = null
    private var artistView: TextView? = null
    private var lyricStage: LinearLayout? = null
    private var dotsView: AodCountdownDotsView? = null
    private var mainView: TextView? = null
    private var translationView: TextView? = null
    private var nextView: TextView? = null
    private var drawWakeLock: PowerManager.WakeLock? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var alarmManager: AlarmManager? = null
    private var activeAlarmListener: AlarmManager.OnAlarmListener? = null

    private var savedHostWidth: Int? = null
    private var savedHostHeight: Int? = null
    private var savedHostMinHeight: Int? = null
    private var savedHostTranslationX: Float? = null
    private var savedHostBackground: Drawable? = null
    private var savedHostForeground: Drawable? = null
    private var savedHostWillNotDraw: Boolean? = null
    private var savedParentRef = WeakReference<ViewGroup>(null)
    private var savedParentWidth: Int? = null
    private var savedParentBackground: Drawable? = null
    private var savedParentForeground: Drawable? = null
    private val hiddenNativeChildren = java.util.WeakHashMap<View, Int>()

    private var lastTitle: String? = null
    private var lastArtist: String? = null
    private var lastMain: String? = null
    private var lastTranslation: String? = null
    private var lastNext: String? = null
    private var lastCountdown: LyricCountdown? = null
    private var lastArtworkId: Int = 0
    private var lastShowingOverlay: Boolean = false
    @Volatile
    var isActivelyPlaying: Boolean = false
        private set

    val isActive: Boolean
        get() {
            val host = hostRef.get() ?: return false
            return host.isAttachedToWindow && host.isShown
        }

    fun bind(host: ViewGroup, nativeRoot: View?, nativeAlbum: ImageView? = null) {
        val currentHost = hostRef.get()
        if (currentHost === host && overlayRoot?.parent === host) {
            if (nativeRoot != null) nativeRootRef = WeakReference(nativeRoot)
            if (nativeAlbum != null) nativeAlbumRef = WeakReference(nativeAlbum)
            return
        }
        hostRef = WeakReference(host)
        nativeRootRef = WeakReference(nativeRoot)
        nativeAlbumRef = WeakReference(nativeAlbum)
        savedHostWidth = null
        savedHostHeight = null
        savedHostMinHeight = null
        savedHostTranslationX = null
        savedHostBackground = null
        savedHostForeground = null
        savedHostWillNotDraw = null
        savedParentRef = WeakReference(null)
        savedParentWidth = null
        savedParentBackground = null
        savedParentForeground = null
        hiddenNativeChildren.clear()
        host.addOnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            if (hostRef.get() === v && lastShowingOverlay) {
                centerHostHorizontally(v as ViewGroup, right - left, left)
                clearCardChrome(v)
            }
        }
        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (hostRef.get() === v) onHostAttached()
            }

            override fun onViewDetachedFromWindow(v: View) {
                if (hostRef.get() === v) {
                    isActivelyPlaying = false
                    dotsView?.stopWave()
                    cancelDozeWakeup()
                    releaseDozeWakeLocks()
                }
            }
        })
        ensureOverlay(host)
    }

    @SuppressLint("WakelockTimeout")
    private fun ensureOverlay(host: ViewGroup): LinearLayout? {
        val existing = host.findViewWithTag<LinearLayout>(OVERLAY_TAG)
        if (existing != null && overlayRoot === existing) {
            return existing
        }
        if (existing != null) {
            (existing.parent as? ViewGroup)?.removeView(existing)
        }
        val context = host.context
        val density = context.resources.displayMetrics.density
        val padH = (4f * density).toInt()
        val padTop = (6f * density).toInt()
        val padBottom = (8f * density).toInt()
        val coverSize = (28f * density).toInt()
        val coverRadius = 7f * density

        val mediumTypeface = runCatching {
            Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }.getOrDefault(Typeface.DEFAULT_BOLD)
        val regularTypeface = runCatching {
            Typeface.create("sans-serif", Typeface.NORMAL)
        }.getOrDefault(Typeface.DEFAULT)

        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, coverRadius)
                }
            }
            visibility = View.GONE
        }
        val titleTv = TextView(context).apply {
            gravity = Gravity.START
            includeFontPadding = false
            typeface = mediumTypeface
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(0xCCFFFFFF.toInt())
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        val artistTv = TextView(context).apply {
            gravity = Gravity.START
            includeFontPadding = false
            typeface = regularTypeface
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(0x80FFFFFF.toInt())
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        val metaColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            addView(
                titleTv,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                artistTv,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = (2f * density).toInt()
                },
            )
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(
                cover,
                LinearLayout.LayoutParams(coverSize, coverSize).apply {
                    marginEnd = (10f * density).toInt()
                },
            )
            addView(
                metaColumn,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            visibility = View.GONE
        }

        val dots = AodCountdownDotsView(context).apply {
            visibility = View.GONE
        }
        val main = TextView(context).apply {
            gravity = Gravity.START
            includeFontPadding = false
            typeface = mediumTypeface
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.15f)
        }
        val translation = TextView(context).apply {
            gravity = Gravity.START
            includeFontPadding = false
            typeface = regularTypeface
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xCCFFFFFF.toInt())
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.12f)
            visibility = View.GONE
        }
        val next = TextView(context).apply {
            gravity = Gravity.START
            includeFontPadding = false
            typeface = regularTypeface
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0x7AFFFFFF.toInt())
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.1f)
            visibility = View.GONE
        }

        val stage = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            addView(
                dots,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (32f * density).toInt(),
                ),
            )
            addView(
                main,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                translation,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = (4f * density).toInt()
                },
            )
            addView(
                next,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = (6f * density).toInt()
                },
            )
        }

        val root = LinearLayout(context).apply {
            tag = OVERLAY_TAG
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padH, padTop, padH, padBottom)
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            visibility = View.GONE

            addView(
                header,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = (8f * density).toInt()
                },
            )
            addView(
                stage,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        host.clipChildren = false
        host.clipToPadding = false
        host.addView(
            root,
            ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        if (drawWakeLock == null || cpuWakeLock == null) {
            runCatching {
                val pm = context.getSystemService(PowerManager::class.java)
                if (drawWakeLock == null) {
                    drawWakeLock = pm?.newWakeLock(DRAW_WAKE_LOCK_LEVEL, "${context.packageName}:ColorLyricAodDraw")?.apply {
                        setReferenceCounted(false)
                    }
                }
                if (cpuWakeLock == null) {
                    cpuWakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:ColorLyricAodCpu")?.apply {
                        setReferenceCounted(false)
                    }
                }
            }
        }
        if (alarmManager == null) {
            alarmManager = runCatching { context.getSystemService(AlarmManager::class.java) }.getOrNull()
        }

        overlayRoot = root
        headerRow = header
        coverView = cover
        titleView = titleTv
        artistView = artistTv
        lyricStage = stage
        dotsView = dots
        mainView = main
        translationView = translation
        nextView = next
        lastTitle = null
        lastArtist = null
        lastMain = null
        lastTranslation = null
        lastNext = null
        lastCountdown = null
        lastArtworkId = 0
        lastShowingOverlay = false
        return root
    }

    fun update(
        enabled: Boolean,
        playing: Boolean,
        songTitle: String?,
        songArtist: String?,
        artwork: Bitmap?,
        main: String?,
        translation: String?,
        next: String?,
        countdown: LyricCountdown? = null,
        positionMs: Long = 0L,
        nextLineDelayMs: Long? = null,
    ) {
        val host = hostRef.get() ?: return
        if (!host.isAttachedToWindow) return
        val root = ensureOverlay(host) ?: return
        val nativeRoot = nativeRootRef.get()

        val cleanMain = if (countdown != null) null else main?.trim()?.takeIf { it.isNotEmpty() }
        val cleanTranslation = if (countdown != null) null else translation?.trim()?.takeIf { it.isNotEmpty() && it != cleanMain }
        val cleanNext = if (countdown != null || cleanTranslation != null) null else next?.trim()?.takeIf { it.isNotEmpty() && it != cleanMain }
        val cleanTitle = songTitle?.trim()?.takeIf { it.isNotEmpty() }
        val cleanArtist = songArtist?.trim()?.takeIf { it.isNotEmpty() }
        val showOverlay = enabled && (cleanMain != null || countdown != null)

        if (!showOverlay) {
            isActivelyPlaying = false
            dotsView?.stopWave()
            val changed = lastShowingOverlay || root.visibility != View.GONE
            if (changed) {
                acquireBriefDrawWakeLock()
                root.visibility = View.GONE
                restoreHostDimensions(host)
                if (nativeRoot != null && nativeRoot.visibility != View.VISIBLE) {
                    nativeRoot.visibility = View.VISIBLE
                }
                host.requestLayout()
                host.invalidate()
            }
            lastShowingOverlay = false
            lastCountdown = null
            releaseDozeWakeLocks()
            return
        }

        val validBmp = artwork?.takeUnless { it.isRecycled }
        val fallbackDrawable: Drawable? = if (validBmp == null) nativeAlbumRef.get()?.drawable else null
        val artworkId = when {
            validBmp != null -> validBmp.generationId xor System.identityHashCode(validBmp)
            fallbackDrawable != null -> System.identityHashCode(fallbackDrawable)
            else -> 0
        }

        val enteringOverlay = !lastShowingOverlay
        val stageSwitched = lastShowingOverlay && (
            lastMain != cleanMain ||
                (lastCountdown == null) != (countdown == null)
            )
        val changed = !lastShowingOverlay ||
            lastTitle != cleanTitle ||
            lastArtist != cleanArtist ||
            lastMain != cleanMain ||
            lastTranslation != cleanTranslation ||
            lastNext != cleanNext ||
            lastCountdown != countdown ||
            lastArtworkId != artworkId ||
            root.visibility != View.VISIBLE ||
            nativeRoot?.visibility == View.VISIBLE

        // Hold DRAW_WAKE_LOCK steadily across active playback lines so the LTPO display stays
        // smoothly in STATE_DOZE without toggling STATE_DOZE_SUSPEND <-> STATE_DOZE (which flashes).
        val activePlaying = playing && isActive
        isActivelyPlaying = activePlaying
        if (activePlaying) {
            val holdMs = ((nextLineDelayMs ?: 12_000L) + 3_000L).coerceIn(5_000L, STEADY_DOZE_HOLD_MAX_MS)
            acquireSteadyDrawWakeLock(holdMs)
        } else if (changed) {
            acquireBriefDrawWakeLock()
        } else if (!playing) {
            releaseDozeWakeLocks()
        }

        expandHostForOverlay(host)
        clearCardChrome(host)
        hideNativeChildren(host, root)
        if (nativeRoot != null && nativeRoot.visibility != View.GONE) {
            nativeRoot.visibility = View.GONE
        }

        val hasArt = validBmp != null || fallbackDrawable != null
        coverView?.let { iv ->
            if (lastArtworkId != artworkId || !lastShowingOverlay) {
                when {
                    validBmp != null -> iv.setImageBitmap(validBmp)
                    fallbackDrawable != null -> iv.setImageDrawable(fallbackDrawable)
                    else -> iv.setImageDrawable(null)
                }
            }
            val targetVis = if (hasArt) View.VISIBLE else View.GONE
            if (iv.visibility != targetVis) iv.visibility = targetVis
        }
        applyOptionalText(titleView, cleanTitle)
        applyOptionalText(artistView, cleanArtist)
        headerRow?.let { row ->
            val targetVis = if (hasArt || cleanTitle != null || cleanArtist != null) View.VISIBLE else View.GONE
            if (row.visibility != targetVis) row.visibility = targetVis
        }

        if (countdown != null) {
            dotsView?.let { dv ->
                dv.updateCountdown(
                    elapsedMs = (positionMs - countdown.startMs).coerceAtLeast(0L),
                    durationMs = (countdown.endMs - countdown.startMs).coerceAtLeast(1L),
                    moving = playing,
                )
                if (dv.visibility != View.VISIBLE) dv.visibility = View.VISIBLE
                if (playing) dv.startWave() else dv.stopWave()
            }
            if (mainView?.visibility != View.GONE) mainView?.visibility = View.GONE
            if (translationView?.visibility != View.GONE) translationView?.visibility = View.GONE
            if (nextView?.visibility != View.GONE) nextView?.visibility = View.GONE
        } else {
            dotsView?.let { dv ->
                dv.stopWave()
                if (dv.visibility != View.GONE) dv.visibility = View.GONE
            }
            mainView?.let { tv ->
                if (tv.text?.toString() != cleanMain) tv.text = cleanMain
                if (tv.visibility != View.VISIBLE) tv.visibility = View.VISIBLE
            }
            applyOptionalText(translationView, cleanTranslation)
            applyOptionalText(nextView, cleanNext)
        }

        if (root.visibility != View.VISIBLE) {
            root.visibility = View.VISIBLE
        }
        root.bringToFront()

        if (enteringOverlay) {
            animateOverlayEntry(root, host.resources.displayMetrics.density)
        } else if (stageSwitched) {
            animateLyricStageUp(host.resources.displayMetrics.density)
        }

        lastTitle = cleanTitle
        lastArtist = cleanArtist
        lastMain = cleanMain
        lastTranslation = cleanTranslation
        lastNext = cleanNext
        lastCountdown = countdown
        lastArtworkId = artworkId
        lastShowingOverlay = true

        if (changed) {
            if (enteringOverlay) {
                root.requestLayout()
                host.requestLayout()
            }
            root.invalidate()
            host.invalidate()
        }
    }

    private fun animateOverlayEntry(root: View, density: Float) {
        root.animate().cancel()
        root.alpha = 0f
        root.translationY = 10f * density
        root.scaleX = 0.96f
        root.scaleY = 0.96f
        root.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(OVERLAY_ENTRY_DURATION_MS)
            .setInterpolator(SLIDE_INTERPOLATOR)
            .start()
    }

    private fun animateLyricStageUp(density: Float) {
        val stage = lyricStage ?: return
        stage.animate().cancel()
        stage.alpha = 0f
        stage.translationY = 14f * density
        stage.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(LINE_SLIDE_DURATION_MS)
            .setInterpolator(SLIDE_INTERPOLATOR)
            .start()
    }

    fun onAodHidden() {
        isActivelyPlaying = false
        dotsView?.stopWave()
        cancelDozeWakeup()
        releaseDozeWakeLocks()
    }

    fun restoreNative() {
        isActivelyPlaying = false
        dotsView?.stopWave()
        cancelDozeWakeup()
        releaseDozeWakeLocks()
        val host = hostRef.get() ?: return
        lyricStage?.animate()?.cancel()
        lyricStage?.alpha = 1f
        lyricStage?.translationY = 0f
        overlayRoot?.visibility = View.GONE
        restoreHostDimensions(host)
        nativeRootRef.get()?.visibility = View.VISIBLE
        lastShowingOverlay = false
        lastCountdown = null
        host.requestLayout()
        host.invalidate()
    }

    fun scheduleDozeWakeup(delayMs: Long, handler: Handler, onWake: () -> Unit) {
        cancelDozeWakeup()
        val clamped = delayMs.coerceIn(50L, 60_000L)
        val am = alarmManager
        if (am != null) {
            val listener = AlarmManager.OnAlarmListener {
                activeAlarmListener = null
                acquireBriefCpuWakeLock()
                onWake()
            }
            activeAlarmListener = listener
            val triggerAt = SystemClock.elapsedRealtime() + clamped
            val scheduled = runCatching {
                am.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    ALARM_TAG,
                    listener,
                    handler,
                )
            }.isSuccess
            if (scheduled) return
            activeAlarmListener = null
        }
        handler.postDelayed(onWake, clamped)
    }

    fun cancelDozeWakeup() {
        val listener = activeAlarmListener ?: return
        activeAlarmListener = null
        runCatching { alarmManager?.cancel(listener) }
    }

    private fun hasVisibleSibling(host: ViewGroup): Boolean {
        val parent = host.parent as? ViewGroup ?: return false
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child === host) continue
            if (child.visibility == View.VISIBLE && (child.width > 0 || child.height > 0)) {
                return true
            }
        }
        return false
    }

    private fun expandHostForOverlay(host: ViewGroup) {
        val lp = host.layoutParams ?: return
        val screenWidth = host.resources.displayMetrics.widthPixels
        val density = host.resources.displayMetrics.density
        if (savedHostHeight == null) {
            savedHostWidth = lp.width
            savedHostHeight = lp.height
            savedHostMinHeight = host.minimumHeight
            savedHostTranslationX = host.translationX
            savedHostBackground = host.background
            savedHostForeground = host.foreground
            savedHostWillNotDraw = host.willNotDraw()
        }

        // Never widen host.parent; only size host safely within its parent's available width, and
        // keep a constant fixed height (172dp) so lyric line changes never trigger AodClockLayout
        // re-layout (which causes sibling AOD cards like Google Maps to flicker/reset) while still
        // giving full room for header + 2-line main + 2-line translation/next.
        val siblingPresent = hasVisibleSibling(host)
        var layoutChanged = false
        if (siblingPresent) {
            val origW = savedHostWidth
            if (origW != null && lp.width != origW) {
                lp.width = origW
                layoutChanged = true
            }
            val origTx = savedHostTranslationX ?: 0f
            if (kotlin.math.abs(host.translationX - origTx) > 0.5f) {
                host.translationX = origTx
            }
        } else {
            val parent = host.parent as? ViewGroup
            val targetWidth = (screenWidth * CARD_WIDTH_FRACTION).toInt()
            if (parent != null) {
                val pLp = parent.layoutParams
                if (savedParentRef.get() == null) {
                    savedParentRef = WeakReference(parent)
                    savedParentWidth = pLp?.width
                    savedParentBackground = parent.background
                    savedParentForeground = parent.foreground
                }
                if (pLp != null && parent.width in 1 until targetWidth &&
                    pLp.width != ViewGroup.LayoutParams.MATCH_PARENT && pLp.width < screenWidth
                ) {
                    pLp.width = ViewGroup.LayoutParams.MATCH_PARENT
                    parent.layoutParams = pLp
                }
            }
            if (lp.width != targetWidth && targetWidth > 0) {
                lp.width = targetWidth
                layoutChanged = true
            }
        }
        val fixedHeight = (196f * density).toInt()
        if (lp.height != fixedHeight) {
            lp.height = fixedHeight
            layoutChanged = true
        }
        if (layoutChanged) {
            host.layoutParams = lp
        }
        if (host.minimumHeight != 0) {
            host.minimumHeight = 0
        }
        if (!siblingPresent && host.width > 0) {
            centerHostHorizontally(host, host.width, host.left)
        }
    }

    private fun centerHostHorizontally(host: ViewGroup, hostWidth: Int, hostLeft: Int) {
        if (hostWidth <= 0) return
        if (hasVisibleSibling(host)) {
            val origTx = savedHostTranslationX ?: 0f
            if (kotlin.math.abs(host.translationX - origTx) > 0.5f) {
                host.translationX = origTx
            }
            return
        }
        val screenWidth = host.resources.displayMetrics.widthPixels
        if (screenWidth <= 0 || hostWidth > screenWidth) return
        val parent = host.parent as? View
        val parentLoc = IntArray(2)
        parent?.getLocationOnScreen(parentLoc)
        val parentScreenLeft = if (parent != null) parentLoc[0] else 0
        val desiredScreenLeft = (screenWidth - hostWidth) / 2f
        val tx = desiredScreenLeft - (parentScreenLeft + hostLeft)
        if (kotlin.math.abs(host.translationX - tx) > 0.5f) {
            host.translationX = tx
        }
    }

    private fun clearCardChrome(host: ViewGroup) {
        if (host.background != null) {
            if (savedHostBackground == null) savedHostBackground = host.background
            host.background = null
        }
        if (host.foreground != null) {
            if (savedHostForeground == null) savedHostForeground = host.foreground
            host.foreground = null
        }
        if (!host.willNotDraw()) host.setWillNotDraw(true)
        // Clear the dedicated media card wrapper's border/background only when it has no visible
        // sibling cards (so Google Maps / other AOD Live Alerts are never touched).
        val parent = host.parent as? ViewGroup
        if (parent != null && !hasVisibleSibling(host)) {
            if (savedParentRef.get() == null) savedParentRef = WeakReference(parent)
            if (parent.background != null) {
                if (savedParentBackground == null) savedParentBackground = parent.background
                parent.background = null
            }
            if (parent.foreground != null) {
                if (savedParentForeground == null) savedParentForeground = parent.foreground
                parent.foreground = null
            }
        }
    }

    private fun hideNativeChildren(host: ViewGroup, overlay: View) {
        for (i in 0 until host.childCount) {
            val child = host.getChildAt(i)
            if (child === overlay) continue
            if (child.visibility != View.GONE) {
                if (child !in hiddenNativeChildren) {
                    hiddenNativeChildren[child] = child.visibility
                }
                child.visibility = View.GONE
            }
        }
    }

    private fun restoreHostDimensions(host: ViewGroup) {
        for ((child, vis) in hiddenNativeChildren) {
            if (child.parent === host) child.visibility = vis
        }
        hiddenNativeChildren.clear()
        val origH = savedHostHeight ?: return
        val origW = savedHostWidth ?: ViewGroup.LayoutParams.WRAP_CONTENT
        val origMin = savedHostMinHeight ?: 0
        val origTx = savedHostTranslationX ?: 0f
        val origBg = savedHostBackground
        val origFg = savedHostForeground
        val origWillNotDraw = savedHostWillNotDraw ?: false
        savedHostWidth = null
        savedHostHeight = null
        savedHostMinHeight = null
        savedHostTranslationX = null
        savedHostBackground = null
        savedHostForeground = null
        savedHostWillNotDraw = null
        host.setWillNotDraw(origWillNotDraw)
        if (origBg != null) host.background = origBg
        if (origFg != null) host.foreground = origFg
        host.minimumHeight = origMin
        host.translationX = origTx
        val lp = host.layoutParams
        if (lp != null && (lp.width != origW || lp.height != origH)) {
            lp.width = origW
            lp.height = origH
            host.layoutParams = lp
        }
        val parent = savedParentRef.get()
        val origParentW = savedParentWidth
        val origParentBg = savedParentBackground
        val origParentFg = savedParentForeground
        savedParentRef = WeakReference(null)
        savedParentWidth = null
        savedParentBackground = null
        savedParentForeground = null
        if (parent != null) {
            if (origParentBg != null) parent.background = origParentBg
            if (origParentFg != null) parent.foreground = origParentFg
            if (origParentW != null) {
                val pLp = parent.layoutParams
                if (pLp != null && pLp.width != origParentW) {
                    pLp.width = origParentW
                    parent.layoutParams = pLp
                }
            }
        }
    }

    private fun applyOptionalText(tv: TextView?, value: String?) {
        tv ?: return
        if (value.isNullOrEmpty()) {
            if (tv.visibility != View.GONE) tv.visibility = View.GONE
        } else {
            if (tv.text?.toString() != value) tv.text = value
            if (tv.visibility != View.VISIBLE) tv.visibility = View.VISIBLE
        }
    }

    private fun acquireSteadyDrawWakeLock(timeoutMs: Long) {
        runCatching { cpuWakeLock?.acquire(BRIEF_CPU_LOCK_MS) }
        runCatching { drawWakeLock?.acquire(timeoutMs) }
            .onFailure { Log.w(TAG, "AOD drawWakeLock acquire failed: ${it.message}") }
    }

    private fun acquireBriefDrawWakeLock() {
        runCatching { cpuWakeLock?.acquire(BRIEF_CPU_LOCK_MS) }
        runCatching { drawWakeLock?.acquire(BRIEF_DRAW_LOCK_MS) }
    }

    private fun acquireBriefCpuWakeLock() {
        runCatching { cpuWakeLock?.acquire(BRIEF_CPU_LOCK_MS) }
    }

    private fun releaseDozeWakeLocks() {
        runCatching { if (drawWakeLock?.isHeld == true) drawWakeLock?.release() }
        runCatching { if (cpuWakeLock?.isHeld == true) cpuWakeLock?.release() }
    }

    /** Gentle wave-bobbing 3 dots combined with left-to-right countdown brightening on AOD. */
    private class AodCountdownDotsView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        private val density = resources.displayMetrics.density
        private var phaseRad: Float = 0f
        private var baseElapsedMs: Long = 0L
        private var baseRealtimeMs: Long = 0L
        private var durationMs: Long = 1L
        private var moving: Boolean = false
        private var animator: ValueAnimator? = null

        fun updateCountdown(elapsedMs: Long, durationMs: Long, moving: Boolean) {
            this.baseElapsedMs = elapsedMs
            this.baseRealtimeMs = SystemClock.elapsedRealtime()
            this.durationMs = durationMs.coerceAtLeast(1L)
            this.moving = moving
            invalidate()
        }

        fun startWave() {
            if (animator?.isRunning == true) return
            animator = ValueAnimator.ofFloat(0f, (2.0 * PI).toFloat()).apply {
                duration = WAVE_CYCLE_MS
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    phaseRad = anim.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun stopWave() {
            animator?.cancel()
            animator = null
        }

        override fun onDetachedFromWindow() {
            stopWave()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val liveElapsed = if (moving && baseRealtimeMs > 0L) {
                (baseElapsedMs + (SystemClock.elapsedRealtime() - baseRealtimeMs)).coerceIn(0L, durationMs)
            } else baseElapsedMs
            val baseRadius = 3.7f * density
            val step = 15.5f * density
            val amplitude = 2.0f * density
            val cx = w * 0.5f
            val cy = h * 0.5f
            repeat(3) { index ->
                val wave = sin((phaseRad - index * 0.9f).toDouble()).toFloat()
                val dy = -wave * amplitude
                val radius = baseRadius * (1f + 0.08f * wave)
                val countdownAlpha = introDotFill(index, liveElapsed, durationMs)
                val alphaFactor = (countdownAlpha + 0.06f * wave).coerceIn(0.22f, 1f)
                paint.alpha = (255 * alphaFactor).roundToInt().coerceIn(56, 255)
                canvas.drawCircle(cx + (index - 1) * step, cy + dy, radius, paint)
            }
        }
    }

    internal companion object {
        const val TAG = "TerraMoor"
        const val OVERLAY_TAG = "colorlyric_aod_overlay"
        const val ALARM_TAG = "ColorLyricAodTick"
        const val CARD_WIDTH_FRACTION = 0.88f
        const val WAVE_CYCLE_MS = 1_700L
        const val OVERLAY_ENTRY_DURATION_MS = 340L
        const val LINE_SLIDE_DURATION_MS = 280L
        val SLIDE_INTERPOLATOR = DecelerateInterpolator(2.0f)
        // Hidden PowerManager.DRAW_WAKE_LOCK level that permits frame submission while dozing.
        const val DRAW_WAKE_LOCK_LEVEL = 0x80
        const val STEADY_DOZE_HOLD_MAX_MS = 30_000L
        const val BRIEF_DRAW_LOCK_MS = 800L
        const val BRIEF_CPU_LOCK_MS = 600L
    }
}
