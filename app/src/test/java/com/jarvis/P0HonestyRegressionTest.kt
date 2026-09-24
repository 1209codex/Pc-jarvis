package com.jarvis

import com.jarvis.agent.*
import com.jarvis.agent.skills.MediaSkill
import com.jarvis.agent.skills.SkillContext
import com.jarvis.ai.AssistantIntent
import com.jarvis.ai.IntentResolver
import com.jarvis.execution.VerificationResult
import com.jarvis.execution.VerificationStatus
import com.jarvis.foundation.*
import com.jarvis.tools.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for P0 honesty invariants:
 *  - UNKNOWN / FAILED verification can never claim success
 *  - canonical tool-name resolution
 *  - no hardcoded artist/comedy defaults in media skill
 *  - WAITING_FOR_USER is not a success
 *  - spec recovery strategies exist
 */
class P0HonestyRegressionTest {

    // ---- 1. VerificationResult can never derive verified from UNKNOWN/FAILED ----
    @Test
    fun testVerificationResultHonesty() {
        val verified = VerificationResult(VerificationStatus.VERIFIED, "ok")
        val unknown = VerificationResult(VerificationStatus.UNKNOWN, "could not confirm")
        val failed = VerificationResult(VerificationStatus.FAILED, "nope")

        assertTrue(verified.verified)
        assertFalse("UNKNOWN must never report verified=true", unknown.verified)
        assertFalse("FAILED must never report verified=true", failed.verified)

        // The only way to construct a VERIFIED result is an explicit VERIFIED status.
        assertNotEquals(VerificationStatus.VERIFIED, unknown.status)
        assertNotEquals(VerificationStatus.VERIFIED, failed.status)
    }

    // ---- 2. ToolRegistry resolves aliases to one canonical name ----
    @Test
    fun testCanonicalToolNameResolution() {
        val registry = ToolRegistry()
        registry.register(StubTool("WHATSAPP"))
        registry.register(StubTool("MUSIC_PLAY"))
        registry.register(StubTool("SEARCH_WEB"))

        assertEquals("WHATSAPP", registry.resolveCanonicalToolName("WHATSAPP_SEND"))
        assertEquals("WHATSAPP", registry.resolveCanonicalToolName("send_message"))
        assertEquals("MUSIC_PLAY", registry.resolveCanonicalToolName("play_music"))
        assertEquals("SEARCH_WEB", registry.resolveCanonicalToolName("search"))
        assertEquals("SEARCH_WEB", registry.resolveCanonicalToolName("WEB_SEARCH"))
        assertNull("Unregistered name must resolve to null", registry.resolveCanonicalToolName("TELEPORT"))
    }

    @Test
    fun testCapabilityAndVerificationRequirement() {
        val registry = ToolRegistry()
        registry.register(StubTool("WHATSAPP"))
        registry.register(StubTool("OPEN_APP"))

        assertTrue(registry.requiresOutcomeVerification("WHATSAPP"))
        assertTrue(registry.requiresOutcomeVerification("youtube_play"))
        assertFalse(registry.requiresOutcomeVerification("OPEN_APP"))
    }

    // ---- 2b. Irreversible side effects must route through verification ----
    @Test
    fun testIrreversibleActionsRequireVerification() {
        val registry = ToolRegistry()
        for (name in listOf("TELEPHONY_CONTROL", "FLASHLIGHT", "SPOTIFY_PLAY", "CALENDAR_MANAGE", "FILE_WRITE")) {
            assertTrue(
                "Irreversible action $name must NOT be ungoverned fast-pathed",
                registry.requiresOutcomeVerification(name)
            )
        }
    }

    // ---- 2c. NOTE memory key derives from content, never a constant ----
    @Test
    fun testNoteKeyDerivesFromContent() {
        val fact = "Mera favourite color blue hai"
        val key = IntentResolver.deriveNoteKey(fact)
        assertNotEquals("Hardcoded 'fact' key would archive-overwrite all memories", "fact", key)
        assertTrue(key.contains("blue"))
        // Distinct topics must not collide into a single archive slot.
        assertNotEquals(IntentResolver.deriveNoteKey("mera favourite color blue hai"), IntentResolver.deriveNoteKey("kal meeting hai"))
    }

    // ---- 2e. Cancel task resolves to a runtime control, never a tool ----
    @Test
    fun testCancelTaskResolution() {
        for (utterance in listOf("cancel", "cancel task", "task cancel karo", "ruk jao")) {
            val resolved = IntentResolver.resolve(utterance)
            assertEquals("'$utterance' must resolve to CANCEL_TASK", AssistantIntent.CANCEL_TASK, resolved?.intent)
            assertNull("CANCEL_TASK is a runtime control, must carry no tool action", resolved?.directPlan)
        }
    }

    // ---- 3. MediaSkill never hardcodes an artist or named comedian ----
    @Test
    fun testMediaSkillNeutralWhenNoPreference() = runBlocking {
        val skill = MediaSkill()
        val context = SkillContext(
            goal = "mere liye koi badiya gana chalao",
            workingMemory = AgentWorkingMemory("mere liye koi badiya gana chalao"),
            userPreferences = emptyMap()
        )
        val result = skill.execute(context.goal, context)
        val query = result.proposedAction?.params?.get("query").orEmpty()
        assertTrue(result.handled)
        assertFalse("Neutral selection must NOT inject an artist nobody asked for", query.contains("Arijit Singh"))
        assertTrue(query.isNotBlank())
    }

    @Test
    fun testMediaSkillComedyNeverNamed() = runBlocking {
        val skill = MediaSkill()
        val context = SkillContext(
            goal = "ek comedy video chalao",
            workingMemory = AgentWorkingMemory("ek comedy video chalao"),
            userPreferences = emptyMap()
        )
        val result = skill.execute(context.goal, context)
        val allCandidates = result.candidateQueries.joinToString(" ") + " " + (result.proposedAction?.params?.get("query").orEmpty())
        for (named in listOf("Anubhav Singh Bassi", "Zakir Khan", "Abhishek Upmanyu")) {
            assertFalse("No hardcoded comedian may appear: $named", allCandidates.contains(named))
        }
    }

    // ---- 4. WAITING_FOR_USER is never a success ----
    @Test
    fun testWaitingForUserIsNotSuccess() {
        val waiting = AgentResult(
            status = AgentResultStatus.WAITING_FOR_USER,
            response = "Which one?",
            taskId = 1,
            iterations = 2
        )
        assertFalse("WAITING_FOR_USER must not count as success", waiting.success)
        val completed = AgentResult(AgentResultStatus.COMPLETED, "done", 1, 2)
        assertTrue(completed.success)
    }

    // ---- 5. Spec recovery strategy set is complete ----
    @Test
    fun testRecoveryStrategyEnumContainsSpecValues() {
        val expected = setOf(
            "RETRY_SAME", "CORRECT_PARAMETERS", "SELECT_ALTERNATIVE", "USE_ALTERNATE_TOOL",
            "REOPEN_APP", "RESEARCH_AGAIN", "RESET_CURRENT_STEP", "REPLAN", "ASK_USER", "ABORT"
        )
        val actual = RecoveryStrategy.values().map { it.name }.toSet()
        assertEquals(expected, actual)
    }

    // ---- 6. SEARCH_WEB returns honest result on no candidates ----
    @Test
    fun testSearchResultHonestOutput() = runBlocking {
        val registry = ToolRegistry()
        registry.register(NoResultSearchTool())
        val executor = ToolExecutor(registry)
        val result = executor.execute("SEARCH_WEB", mapOf("query" to "xyz unknown"))
        assertTrue(result.message.contains("No results found") || result.message.contains("xyz unknown"))
    }

    // ---- 7. WhatsApp send requires user confirmation via ToolExecutor ----
    @Test
    fun testWhatsAppSendRequiresConfirmationWithoutUserApproval() = runBlocking {
        val registry = ToolRegistry()
        registry.register(
            StubTool("WHATSAPP"),
            ToolMetadata(
                name = "WHATSAPP",
                description = "WhatsApp",
                parameters = emptyList(),
                riskLevel = RiskLevel.HIGH
            )
        )
        val executor = ToolExecutor(registry)

        val unconfirmed = executor.execute(
            actionType = "WHATSAPP_SEND",
            params = mapOf("recipient" to "Dollar", "message" to "Hello!"),
            userApprovalGranted = false
        )
        assertTrue("WHATSAPP_SEND without user approval must require confirmation", unconfirmed is ToolResult.NeedsConfirmation)

        val confirmed = executor.execute(
            actionType = "WHATSAPP_SEND",
            params = mapOf("recipient" to "Dollar", "message" to "Hello!"),
            userApprovalGranted = true
        )
        assertTrue("WHATSAPP_SEND with user approval must execute", confirmed is ToolResult.Success)
    }

    // ---- 8. AgentLoopGuard startingIteration and limit safety ----
    @Test
    fun testAgentLoopGuardReplaySafetyAtLimit() {
        val guard = AgentLoopGuard(startingIteration = 12)
        assertEquals(12, guard.currentIteration())
        val check = guard.checkNextIteration()
        assertTrue("Next iteration beyond cap must report Exceeded", check is AgentLoopGuard.GuardCheckResult.Exceeded)
    }

    private class StubTool(override val name: String) : Tool {
        override suspend fun execute(params: Map<String, String>): ToolResult =
            ToolResult.Success("stub $name")
    }

    private class NoResultSearchTool : Tool {
        override val name: String = "SEARCH_WEB"
        override suspend fun execute(params: Map<String, String>): ToolResult =
            ToolResult.Success("Could not find anything for '${params["query"]}'")
    }
}