package shop.terramoor.github.io.diagnostics

import android.util.Log
import shop.terramoor.github.io.systemui.NativeContract
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Batches diagnostic writes off the caller's thread. Each process owns its own queue. */
internal class DiagnosticLog(private val source: String, private val deliver: (List<String>) -> Boolean) {
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "TerraMoorDiagnostics").apply { isDaemon = true }
    }
    private val pending = ArrayDeque<String>()
    private var scheduled = false
    private var failures = 0
    private var totalFailures = 0
    private var lastFailure = "none"
    private var deliveredOnce = false
    private var dropped = 0
    private val recent = LinkedHashMap<String, Long>()

    fun note(message: String) = enqueue("I", message)

    fun failure(step: String, failure: Throwable) {
        diagnosticStack(step, failure).forEach { enqueue("W", it) }
    }

    @Synchronized
    private fun enqueue(level: String, message: String) {
        val now = System.nanoTime()
        val last = recent[message]
        if (last != null && now - last in 0..REPEAT_WINDOW_NS) return
        recent.remove(message)
        recent[message] = now
        if (recent.size > 64) recent.remove(recent.keys.first())
        val line = diagnosticLine("${TIMESTAMP.format(Instant.now())} [$source/$level] $message")
        if (pending.size == DiagnosticStore.MAX_LINES) {
            pending.removeFirst()
            dropped++
        }
        pending.addLast(line)
        if (!scheduled) {
            if (failures >= MAX_ATTEMPTS) failures = 0
            schedule(500L)
        }
    }

    private fun schedule(delayMs: Long) {
        scheduled = true
        worker.schedule(::flush, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun flush() {
        val batch = synchronized(this) { pending.take(DiagnosticStore.BATCH_SIZE) }
        var reason = "provider rejected or unavailable"
        val delivered = try {
            deliver(batch)
        } catch (error: Exception) {
            reason = error.javaClass.name
            false
        } catch (error: LinkageError) {
            reason = error.javaClass.name
            false
        }
        synchronized(this) {
            if (delivered) {
                batch.forEach { if (pending.peekFirst() === it) pending.removeFirst() }
                if (!deliveredOnce || failures > 0 || dropped > 0) {
                    val receipt = "Diagnostic channel delivered, totalFailures=$totalFailures, lastFailure=$lastFailure, dropped=$dropped"
                    pending.addFirst(diagnosticLine("${TIMESTAMP.format(Instant.now())} [$source/I] $receipt"))
                    deliveredOnce = true
                    dropped = 0
                }
                failures = 0
            } else {
                failures++
                totalFailures++
                lastFailure = reason
                Log.w("TerraMoor", "Diagnostic storage unavailable for $source: attempt=$failures, total=$totalFailures, reason=$reason")
            }
            scheduled = false
            if (pending.isNotEmpty() && failures < MAX_ATTEMPTS) schedule(if (delivered) 250L else 2000L)
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
        const val REPEAT_WINDOW_NS = 3_000_000_000L
        val TIMESTAMP = DateTimeFormatterBuilder().appendInstant(3).toFormatter()
    }
}

// Only program-generated messages and exception types/locations enter the report.
internal fun diagnosticFailure(step: String, failure: Throwable): String = buildString {
    append(step)
    var cause: Throwable? = failure
    repeat(2) {
        val error = cause ?: return@buildString
        if (error is NativeContract.Mismatch) append(" | Native contract: ").append(error.lookup)
        else append(" | ").append(error.javaClass.simpleName)
        error.stackTrace.take(3).forEach { frame ->
            append(" @ ").append(frame.className).append('.').append(frame.methodName)
                .append('(').append(frame.lineNumber).append(')')
        }
        cause = error.cause
    }
}

// Keep full stack locations as separate bounded records; exception messages may contain media data.
internal fun diagnosticStack(step: String, failure: Throwable): List<String> = buildList {
    var error: Throwable? = failure
    val seen = mutableSetOf<Throwable>()
    var depth = 0
    while (error != null && depth < 8 && seen.add(error)) {
        val current = error
        val lookup = if (current is NativeContract.Mismatch) " lookup=${current.lookup}" else ""
        add("$step: cause=$depth type=${current.javaClass.name}$lookup frames=${current.stackTrace.size}")
        current.stackTrace.forEachIndexed { index, frame ->
            add("$step: cause=$depth frame=$index ${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})")
        }
        error = current.cause
        depth++
    }
    if (error != null) add("$step: further causes omitted (depth or cycle limit)")
}

internal fun diagnosticLine(text: String): String = text.take(DiagnosticStore.MAX_LINE_LENGTH)
    .map { if (it.isISOControl()) ' ' else it }.joinToString("")
