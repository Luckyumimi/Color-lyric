package com.juren233.hyperlyricsenhanced.root.utils

import android.util.Log

internal object HookLogger {
    fun i(tag: String, message: String) { Log.i(tag, message) }
    fun w(tag: String, message: String) { Log.w(tag, message) }
    fun e(tag: String, message: String, error: Throwable? = null) { Log.e(tag, message, error) }
}

internal class RuntimeResourceCleanupException(resource: String, cause: Throwable) :
    IllegalStateException("Could not release $resource", cause)

internal class RuntimeResourceCleanup {
    private var failure: RuntimeResourceCleanupException? = null

    fun attempt(resource: String, release: () -> Unit) {
        try {
            release()
        } catch (error: Exception) {
            val first = failure
            if (first == null) failure = RuntimeResourceCleanupException(resource, error)
            else first.addSuppressed(RuntimeResourceCleanupException(resource, error))
        }
    }

    fun failureOrNull() = failure
    fun throwIfFailed() { failure?.let { throw it } }
}
