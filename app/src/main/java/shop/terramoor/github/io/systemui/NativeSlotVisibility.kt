package shop.terramoor.github.io.systemui

import android.view.View

/** Retains the native slot's measured space and restores only visibility changed by TerraMoor. */
internal class NativeSlotVisibility(private val view: View) {
    private var hiddenByModule = false

    fun apply(hidden: Boolean) {
        if (!hidden) {
            restore()
        } else if (view.visibility == View.VISIBLE) {
            view.visibility = View.INVISIBLE
            hiddenByModule = true
        } else if (view.visibility != View.INVISIBLE) {
            hiddenByModule = false
        }
    }

    fun restore() {
        if (hiddenByModule && view.visibility == View.INVISIBLE) view.visibility = View.VISIBLE
        hiddenByModule = false
    }
}
