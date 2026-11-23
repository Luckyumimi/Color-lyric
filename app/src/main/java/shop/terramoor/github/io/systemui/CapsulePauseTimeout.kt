package shop.terramoor.github.io.systemui

/** Shares a verified binding between the UI thread and the OEM's timeout handler. */
internal class CapsulePauseTimeout<T : Any> {
    private var owner: T? = null
    private var deferred: T? = null

    @Synchronized
    fun update(next: T?, restore: Boolean = true): T? {
        if (next == owner) return null
        owner = next
        val pending = deferred
        deferred = null
        return pending.takeIf { restore }
    }

    @Synchronized
    fun retain(matches: (T) -> Boolean): Boolean {
        val current = owner?.takeIf(matches) ?: return false
        deferred = current
        return true
    }
}
