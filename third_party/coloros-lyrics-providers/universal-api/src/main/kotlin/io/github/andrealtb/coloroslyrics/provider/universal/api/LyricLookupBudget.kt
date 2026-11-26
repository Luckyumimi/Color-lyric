package io.github.andrealtb.coloroslyrics.provider.universal.api

import java.util.concurrent.TimeUnit

/** Bounded automatic search, with an independent allowance for every selected source. */
internal class LyricLookupBudget(startNanos: Long) {
    companion object {
        const val MAX_CALLS_PER_SOURCE = 6
        const val MAX_TOTAL_CALLS = 24
        val TOTAL_NANOS: Long = TimeUnit.SECONDS.toNanos(16)
        val SOURCE_NANOS: Long = TimeUnit.SECONDS.toNanos(4)
    }

    private val deadline = startNanos + TOTAL_NANOS
    private var sourceDeadline = minOf(deadline, startNanos + SOURCE_NANOS)
    private var sourceCalls = 0
    private var totalCalls = 0

    fun beginSource(nowNanos: Long) {
        sourceCalls = 0
        sourceDeadline = minOf(deadline, nowNanos + SOURCE_NANOS)
    }

    fun acquire(nowNanos: Long): Long? {
        val remaining = minOf(deadline, sourceDeadline) - nowNanos
        if (remaining <= 0L || sourceCalls >= MAX_CALLS_PER_SOURCE || totalCalls >= MAX_TOTAL_CALLS) return null
        sourceCalls++
        totalCalls++
        return remaining
    }
}
