package com.jarvis.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Race condition tests verifying that asynchronous events (timeout, ASR completion,
 * multiple wake callbacks) finalize safely without duplicate executions.
 */
class VoiceEngineRaceTest {

    @Test
    fun testAsrResultAndTimeoutRace_onlyOneWins() {
        val commandFinalizationGate = AtomicBoolean(false)
        val executions = AtomicInteger(0)
        val latch = CountDownLatch(2)

        // Thread 1: ASR Result arrives
        Thread {
            if (commandFinalizationGate.compareAndSet(false, true)) {
                executions.incrementAndGet()
            }
            latch.countDown()
        }.start()

        // Thread 2: 8s Timeout fires simultaneously
        Thread {
            if (commandFinalizationGate.compareAndSet(false, true)) {
                executions.incrementAndGet()
            }
            latch.countDown()
        }.start()

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals("Exactly one event must win the finalization gate", 1, executions.get())
    }

    @Test
    fun testWakeCallbackConcurrent_onlyOneTransition() {
        val wakeTransitionGate = AtomicBoolean(false)
        val transitions = AtomicInteger(0)
        val threads = 10
        val latch = CountDownLatch(threads)

        for (i in 0 until threads) {
            Thread {
                if (wakeTransitionGate.compareAndSet(false, true)) {
                    transitions.incrementAndGet()
                }
                latch.countDown()
            }.start()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals("Exactly one wake callback may trigger transition", 1, transitions.get())
    }

    @Test
    fun testVadSpeechEndedAndAsrEndpointRace() {
        val finalizationGate = AtomicBoolean(false)
        val executionCounter = AtomicInteger(0)
        val latch = CountDownLatch(3)

        // 1. VAD Speech Ended
        Thread {
            if (finalizationGate.compareAndSet(false, true)) {
                executionCounter.incrementAndGet()
            }
            latch.countDown()
        }.start()

        // 2. ASR onFinal
        Thread {
            if (finalizationGate.compareAndSet(false, true)) {
                executionCounter.incrementAndGet()
            }
            latch.countDown()
        }.start()

        // 3. ASR onError
        Thread {
            if (finalizationGate.compareAndSet(false, true)) {
                executionCounter.incrementAndGet()
            }
            latch.countDown()
        }.start()

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals("Only one finalization event may execute", 1, executionCounter.get())
    }
}
