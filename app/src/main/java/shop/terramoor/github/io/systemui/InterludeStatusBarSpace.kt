package shop.terramoor.github.io.systemui

/** Retains the native reservation only while this capsule owns the compact interlude. */
internal class InterludeStatusBarSpace {
    private var originalWidth: Int? = null
    private var publishedWidth: Int? = null

    fun update(compact: Boolean, targetWidth: Int?, readWidth: () -> Int,
               publish: (Int, String) -> Unit): Int? {
        val previous = originalWidth
        if (!compact && previous == null) return null
        val current = readWidth()
        if (!compact) {
            val restored = previous.takeIf { current == publishedWidth }
            if (restored != null) publish(restored, "terramoor_interlude_end")
            originalWidth = null
            publishedWidth = null
            return restored
        }

        val target = targetWidth?.takeIf { it > 0 } ?: return null
        if (current == target) {
            if (originalWidth != null) publishedWidth = target
            return null
        }
        // Remember the newest native width. Zero means the system released the reservation.
        if (current <= 0) return null
        if (current != publishedWidth && current < target) return null
        if (originalWidth == null) originalWidth = current.takeIf { it > target } ?: return null
        if (current != publishedWidth && current > target) originalWidth = current
        publish(target, "terramoor_interlude")
        publishedWidth = target
        return target
    }
}
