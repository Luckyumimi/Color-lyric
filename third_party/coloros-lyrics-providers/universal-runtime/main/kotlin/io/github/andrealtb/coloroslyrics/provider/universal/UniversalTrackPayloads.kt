package io.github.andrealtb.coloroslyrics.provider.universal

/** Keeps the complete payload so changing display policies never destroys the source data. */
internal class UniversalTrackPayloads {
    private val source = mutableMapOf<String, String>()
    private val manual = mutableSetOf<String>()

    @Synchronized
    fun isManual(key: String): Boolean = key in manual

    /** The callback and the priority check are one transaction, including against late lookups. */
    @Synchronized
    fun accept(key: String, value: String, isManual: Boolean, publish: (String) -> Unit): Boolean {
        if (!isManual && key in manual) return false
        if (isManual) manual.add(key)
        source[key] = value
        publish(value)
        return true
    }

    @Synchronized
    fun refresh(key: String, publish: (String) -> Unit): Boolean {
        val value = source[key] ?: return false
        publish(value)
        return true
    }

    @Synchronized
    fun clearManual(key: String) {
        manual.remove(key)
        source.remove(key)
    }

    @Synchronized
    fun remove(key: String) {
        source.remove(key)
        manual.remove(key)
    }

    @Synchronized
    fun <T> withLock(block: () -> T): T = block()
}
