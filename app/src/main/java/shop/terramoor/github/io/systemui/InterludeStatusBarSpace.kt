package shop.terramoor.github.io.systemui

internal const val INTERLUDE_STATUS_BAR_WIDTH_PX = 400

/** Retains the native reservation only while this capsule owns the compact interlude. */
internal class InterludeStatusBarSpace {
    private var originalWidth: Int? = null

    fun update(compact: Boolean, readWidth: () -> Int, publish: (Int, String) -> Unit): Int? {
        val previous = originalWidth
        if (!compact && previous == null) return null
        val current = readWidth()
        if (!compact) {
            val restored = previous.takeIf { current == INTERLUDE_STATUS_BAR_WIDTH_PX }
            if (restored != null) publish(restored, "terramoor_interlude_end")
            originalWidth = null
            return restored
        }

        if (current == INTERLUDE_STATUS_BAR_WIDTH_PX) return null
        // Remember the newest native width. Zero means the system released the reservation.
        originalWidth = current.takeIf { it > INTERLUDE_STATUS_BAR_WIDTH_PX } ?: return null
        publish(INTERLUDE_STATUS_BAR_WIDTH_PX, "terramoor_interlude")
        return INTERLUDE_STATUS_BAR_WIDTH_PX
    }
}
