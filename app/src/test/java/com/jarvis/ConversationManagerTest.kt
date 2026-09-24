package com.jarvis

import com.jarvis.conversation.ConversationManager
import com.jarvis.voice.VoiceState
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ConversationManagerTest {

    @Test
    fun testInitialState() {
        val manager = ConversationManager()
        assertEquals(VoiceState.IDLE, manager.currentStateValue)
        assertEquals(VoiceState.IDLE, manager.stateFlow.value)
    }

    @Test
    fun testValidStateTransitions() {
        val manager = ConversationManager()
        assertTrue(manager.transitionTo(VoiceState.WAITING_FOR_WAKE))
        assertEquals(VoiceState.WAITING_FOR_WAKE, manager.currentStateValue)

        assertTrue(manager.transitionTo(VoiceState.WAKE_DETECTED))
        assertTrue(manager.transitionTo(VoiceState.LISTENING))
        assertTrue(manager.transitionTo(VoiceState.THINKING))
        assertTrue(manager.transitionTo(VoiceState.EXECUTING))
        assertTrue(manager.transitionTo(VoiceState.SPEAKING))
        assertTrue(manager.transitionTo(VoiceState.WAITING_FOR_WAKE))
        // Self-transition WAITING_FOR_WAKE -> WAITING_FOR_WAKE allowed
        assertTrue(manager.transitionTo(VoiceState.WAITING_FOR_WAKE))
    }

    @Test
    fun testInvalidStateTransitionRejected() {
        val manager = ConversationManager()
        // IDLE cannot jump directly to THINKING
        assertFalse(manager.transitionTo(VoiceState.THINKING))
        assertEquals(VoiceState.IDLE, manager.currentStateValue)
    }

    @Test
    fun testWakeOnlyUtteranceFiltered() {
        val manager = ConversationManager()
        val result = manager.processUtterance("Jarvis")
        assertNull(result)
    }

    @Test
    fun testCommandUtterancePreserved() {
        val manager = ConversationManager()
        val result = manager.processUtterance("Jarvis open Chrome")
        assertEquals("open Chrome", result)
    }

    @Test
    fun testConcurrentTransitionsDoNotCorruptState() {
        val manager = ConversationManager()
        manager.transitionTo(VoiceState.WAITING_FOR_WAKE)
        val latch = CountDownLatch(10)

        for (i in 0 until 10) {
            Thread {
                manager.transitionTo(VoiceState.WAKE_DETECTED)
                latch.countDown()
            }.start()
        }

        assertTrue(latch.await(3, TimeUnit.SECONDS))
        assertEquals(VoiceState.WAKE_DETECTED, manager.currentStateValue)
    }
}
