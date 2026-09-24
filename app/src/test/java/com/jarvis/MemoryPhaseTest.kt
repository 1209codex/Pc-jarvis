package com.jarvis

import com.jarvis.conversation.ConversationManager
import com.jarvis.memory.*
import org.junit.Assert.*
import org.junit.Test

class MemoryPhaseTest {

    @Test
    fun testContextAssemblyAndProvenance() {
        val conversationManager = ConversationManager()
        conversationManager.addMessage("user", "Hello Jarvis")
        conversationManager.addMessage("assistant", "Hello! How can I assist you?")

        val prefItem = MemoryItem(
            id = 1,
            type = MemoryType.USER_PREFERENCE,
            key = "preferred_music_player",
            content = "YMusic",
            provenance = "user_settings"
        )

        val evidenceItem = MemoryItem(
            id = 2,
            type = MemoryType.TASK_ARTIFACT,
            key = "financial_report_2026",
            content = "Q1 profit up 15%",
            provenance = "task_artifact_101"
        )

        val pkg = ContextPackage(
            currentRequest = "Analyze our recent financials",
            workingMemory = mapOf("chart_format" to "bar"),
            conversationHistorySummary = "user: Hello\nassistant: Hi",
            relevantPreferences = listOf(prefItem),
            retrievedEvidence = listOf(evidenceItem),
            activeAppContext = "com.sec.android.app.myfiles"
        )

        assertEquals("Analyze our recent financials", pkg.currentRequest)
        assertEquals(1, pkg.relevantPreferences.size)
        assertEquals("user_settings", pkg.relevantPreferences[0].provenance)
        assertEquals("task_artifact_101", pkg.retrievedEvidence[0].provenance)
        assertEquals("com.sec.android.app.myfiles", pkg.activeAppContext)
    }
}
