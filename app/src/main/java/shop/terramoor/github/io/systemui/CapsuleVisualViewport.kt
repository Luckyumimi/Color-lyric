package shop.terramoor.github.io.systemui

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider

/** A rendering-only viewport. The OEM keeps the measured size, hit bounds and drawable width. */
internal class CapsuleVisualViewport(private val target: NativeContract.Target) : View.OnAttachStateChangeListener {
    private val capsule = target.capsule
    private val leading = SideOffset(target.leftSection, target.artwork)
    private val trailing = SideOffset(target.rightSection, target.waveform)
    private var nativeOutline: ViewOutlineProvider? = null
    private var nativeClip = false
    private var ownsOutline = false
    private var observingDetach = false
    var inset = 0
        private set
    val coverAnchorX: Float? get() = leading.anchorX

    private val outline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, result: Outline) {
            nativeOutline?.getOutline(view, result)
            val edge = inset.coerceAtMost(((view.width - 1) / 2).coerceAtLeast(0))
            result.setRoundRect(edge, 0, view.width - edge, view.height, view.height / 2f)
        }
    }

    fun apply(inset: Int, moveLeading: Boolean, moveTrailing: Boolean, followEdge: Boolean = false): Boolean {
        if (inset == 0) {
            restoreOutline()
            trailing.restore()
            if (moveLeading && followEdge && leading.anchorToLeftEdge(capsule, inset)) {
                observeDetach()
            } else {
                leading.restore()
                stopObservingDetach()
            }
            return true
        }
        if (ownsOutline && (capsule.outlineProvider !== outline || !capsule.clipToOutline)) {
            restore()
            if (!followEdge) return false
        }
        if (!followEdge && (!leading.isAvailable(moveLeading) || !trailing.isAvailable(moveTrailing))) {
            restore()
            return false
        }
        val changed = this.inset != inset
        this.inset = inset
        if (moveLeading && followEdge) {
            leading.anchorToLeftEdge(capsule, inset)
        } else {
            leading.setOffset(if (moveLeading) inset else 0, followEdge = false)
        }
        trailing.setOffset(if (moveTrailing) -inset else 0, followEdge)
        if (!ownsOutline) {
            nativeOutline = capsule.outlineProvider
            nativeClip = capsule.clipToOutline
            ownsOutline = true
            capsule.outlineProvider = outline
            capsule.clipToOutline = true
            observeDetach()
        } else if (changed) {
            capsule.invalidateOutline()
        }
        if (changed) capsule.invalidate() // Rerecord the lyrics with these same edges.
        return true
    }

    fun restore() {
        leading.restore()
        trailing.restore()
        restoreOutline()
        stopObservingDetach()
    }

    private fun restoreOutline() {
        if (!ownsOutline) return
        inset = 0
        if (capsule.outlineProvider === outline) {
            capsule.clipToOutline = nativeClip
            capsule.outlineProvider = nativeOutline
        }
        nativeOutline = null
        ownsOutline = false
        capsule.invalidate()
    }

    private fun observeDetach() {
        if (observingDetach) return
        observingDetach = true
        capsule.addOnAttachStateChangeListener(this)
    }

    private fun stopObservingDetach() {
        if (!observingDetach) return
        capsule.removeOnAttachStateChangeListener(this)
        observingDetach = false
    }

    override fun onViewAttachedToWindow(view: View) = Unit
    override fun onViewDetachedFromWindow(view: View) = restore()

    private class SideOffset(private val view: View?, private val icon: View) {
        private var base = 0f
        private var applied: Float? = null
        var anchorX: Float? = null
            private set

        fun isAvailable(needed: Boolean): Boolean {
            if (!needed) return true
            val slot = view ?: return false
            return slot.isLaidOut && slot.width > 0 && slot.scaleX == 1f && slot.rotation == 0f &&
                (applied?.let { slot.translationX == it } ?: (slot.translationX == 0f))
        }

        fun setOffset(offset: Int, followEdge: Boolean) {
            anchorX = null
            val slot = view ?: return
            if (offset == 0) {
                restore()
            } else {
                val translation = if (followEdge) {
                    val drift = nativeIconDrift(slot)
                    if (drift == null) {
                        restore()
                        return
                    }
                    offset - drift
                } else (if (applied == slot.translationX) base else slot.translationX) + offset
                applyTranslation(slot, translation)
            }
        }

        fun anchorToLeftEdge(capsule: View, inset: Int): Boolean {
            val slot = view ?: return false
            // The OEM also animates the content page above the slot. Solve in capsule
            // coordinates, including the scale that the slot translation passes through.
            var child = icon
            var layoutCenter = icon.width / 2f
            var centerWithoutSlotTranslation = layoutCenter
            var slotTranslationScale = 1f
            var passedSlot = false
            while (child !== capsule) {
                val parent = child.parent as? View
                if (parent == null || child.rotation != 0f || child.rotationX != 0f ||
                    child.rotationY != 0f || child.scaleX <= 0f) {
                    restore()
                    return false
                }
                val left = child.left - parent.scrollX
                layoutCenter += left
                centerWithoutSlotTranslation = left + child.pivotX +
                    (centerWithoutSlotTranslation - child.pivotX) * child.scaleX
                if (child === slot) {
                    passedSlot = true
                } else {
                    centerWithoutSlotTranslation += child.translationX
                    if (passedSlot) slotTranslationScale *= child.scaleX
                }
                child = parent
            }
            // At the camera-sized end of the transition, keep the center in the leading half.
            val position = (inset + layoutCenter.coerceAtLeast(0f)).coerceAtMost(capsule.width / 2f)
            val translation = (position - centerWithoutSlotTranslation) / slotTranslationScale
            if (!passedSlot || !translation.isFinite()) {
                restore()
                return false
            }
            applyTranslation(slot, translation)
            anchorX = position
            return true
        }

        private fun applyTranslation(slot: View, translation: Float) {
            if (applied != slot.translationX) base = slot.translationX
            if (slot.translationX != translation) slot.translationX = translation
            applied = translation
        }

        private fun nativeIconDrift(slot: View): Float? {
            var child = icon
            var laidOutCenter = icon.width / 2f
            var drawnCenter = laidOutCenter
            while (true) {
                if (child.rotation != 0f || child.rotationX != 0f || child.rotationY != 0f || child.scaleX <= 0f) return null
                drawnCenter = child.pivotX + (drawnCenter - child.pivotX) * child.scaleX
                if (child === slot) return (drawnCenter - laidOutCenter).takeIf { it.isFinite() }
                val parent = child.parent as? View ?: return null
                val left = child.left - parent.scrollX
                laidOutCenter += left
                drawnCenter += left + child.translationX
                child = parent
            }
        }

        fun restore() {
            anchorX = null
            val slot = view ?: return
            if (slot.translationX == applied) slot.translationX = base
            applied = null
        }
    }
}
