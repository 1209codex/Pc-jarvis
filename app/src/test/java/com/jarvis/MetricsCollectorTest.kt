package com.jarvis

import com.jarvis.foundation.MetricsCollector
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MetricsCollectorTest {

    private val metrics = MetricsCollector()

    @Before
    fun setUp() = metrics.reset()

    @Test
    fun aggregatesAndDerivesRates() {
        metrics.record("llm", "chat", ok = true, wallMs = 100, tokensIn = 10, tokensOut = 20)
        metrics.record("llm", "chat", ok = true, wallMs = 200)
        metrics.record("llm", "chat", ok = false, wallMs = 50)

        val point = metrics.point("llm", "chat")
        assertEquals(3L, point.calls)
        assertEquals(2L, point.okCalls)
        assertEquals(1L, point.failureCalls)
        assertEquals(350L, point.totalMs)
        assertEquals(10L, point.tokensIn)
        assertEquals(20L, point.tokensOut)
        assertEquals(2f / 3f, point.successRate, 0.0001f)
        assertEquals(350.0 / 3.0, point.avgMs, 0.0001)
    }

    @Test
    fun keysAreCaseInsensitiveAndOperationsIndependent() {
        metrics.record("TOOL", "WHATSAPP", ok = false, wallMs = 5)
        metrics.record("Tool", "whatsapp", ok = true, wallMs = 5)
        metrics.record("tool", "SEARCH_WEB", ok = true, wallMs = 9)

        val whatsapp = metrics.point("tool", "whatsapp")
        assertEquals(2L, whatsapp.calls)
        assertEquals(1L, whatsapp.okCalls)
        assertEquals(1L, metrics.point("tool", "search_web").calls)
    }

    @Test
    fun missingPointIsZeroed() {
        assertEquals(MetricsCollector.MetricPoint(), metrics.point("nope", "nada"))
    }

    @Test
    fun snapshotIsAnIndependentCopy() {
        metrics.record("llm", "chat", ok = true, wallMs = 1)
        val snapshot = metrics.snapshot()
        metrics.record("llm", "chat", ok = true, wallMs = 2)
        assertEquals(1L, snapshot.values.first().calls)
        assertEquals(2L, metrics.point("llm", "chat").calls)
    }

    @Test
    fun estimateTokensScalesWithLength() {
        assertEquals(0L, MetricsCollector.estimateTokens(""))
        assertEquals(1L, MetricsCollector.estimateTokens("ab"))
        assertEquals(2L, MetricsCollector.estimateTokens("abcdefgh"))
    }
}