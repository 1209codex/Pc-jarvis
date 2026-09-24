package com.jarvis.foundation

/** Thread-safe in-memory metrics collector. Tracks wall time, call counts and token estimates per (component, operation). */
class MetricsCollector {
    data class Key(val component: String, val operation: String)

    data class MetricPoint(
        val calls: Long = 0L,
        val okCalls: Long = 0L,
        val totalMs: Long = 0L,
        val tokensIn: Long = 0L,
        val tokensOut: Long = 0L
    ) {
        val failureCalls: Long get() = calls - okCalls
        val successRate: Float get() = if (calls == 0L) 0f else okCalls.toFloat() / calls
        val avgMs: Double get() = if (calls == 0L) 0.0 else totalMs.toDouble() / calls
    }

    private val lock = Any()
    private val points = mutableMapOf<Key, MetricPoint>()

    fun record(
        component: String,
        operation: String,
        ok: Boolean,
        wallMs: Long,
        tokensIn: Long = 0L,
        tokensOut: Long = 0L
    ) {
        val key = Key(component.uppercase(), operation.uppercase())
        synchronized(lock) {
            val prev = points[key] ?: MetricPoint()
            points[key] = prev.copy(
                calls = prev.calls + 1,
                okCalls = prev.okCalls + if (ok) 1L else 0L,
                totalMs = prev.totalMs + wallMs,
                tokensIn = prev.tokensIn + tokensIn,
                tokensOut = prev.tokensOut + tokensOut
            )
        }
    }

    fun point(component: String, operation: String): MetricPoint =
        synchronized(lock) { points[Key(component.uppercase(), operation.uppercase())] ?: MetricPoint() }

    fun snapshot(): Map<Key, MetricPoint> = synchronized(lock) { points.toMap() }

    fun reset() {
        synchronized(lock) { points.clear() }
    }

    companion object {
        val shared = MetricsCollector()
        fun estimateTokens(text: String): Long = ((text.length + 3) / 4L).coerceAtLeast(0L)
    }
}