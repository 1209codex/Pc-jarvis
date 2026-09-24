package com.jarvis

import com.jarvis.controlplane.*
import com.jarvis.execution.VerificationResult
import com.jarvis.foundation.*
import com.jarvis.security.FakeSecurityAuditor
import com.jarvis.security.SecurityAuditor
import com.jarvis.tools.Tool
import com.jarvis.tools.ToolPolicy
import com.jarvis.tools.ToolRegistry
import com.jarvis.tools.ToolResult
import com.jarvis.wakeword.HybridWakeWordDetector
import com.jarvis.wakeword.WakeDetectorMode
import com.jarvis.wakeword.WakeWordConfig
import com.jarvis.wakeword.WakeWordDetector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ControlPlaneAndAutonomyAuditTest {

    // -------------------------------------------------------------
    // 1. 4-Dimensional Autonomy Policy Tests
    // -------------------------------------------------------------
    @Test
    fun test4DAutonomyPolicyClassification() {
        val policy = AutonomyPolicyEngine(autonomousFullAuto = false, drivingModeActive = false)

        // Destructive -> BLOCK_OR_STRICT
        val resetRisk = policy.classifyAction("FACTORY_RESET", emptyMap())
        assertEquals(AutonomyTier.BLOCK_OR_STRICT, resetRisk.tier)
        assertEquals(Destructiveness.CRITICAL, resetRisk.destructiveness)
        val resetEval = policy.evaluate("FACTORY_RESET", emptyMap())
        assertFalse(resetEval.allowed)

        // External communication in standard mode -> CONFIRM_EXTERNAL
        val waSendRisk = policy.classifyAction("WHATSAPP_SEND", mapOf("action" to "send_message", "recipient" to "Mom", "message" to "Hi"))
        assertEquals(AutonomyTier.CONFIRM_EXTERNAL, waSendRisk.tier)
        assertEquals(Reversibility.IRREVERSIBLE, waSendRisk.reversibility)
        assertEquals(SideEffectScope.EXTERNAL_COMMUNICATION, waSendRisk.scope)
        val waEval = policy.evaluate("WHATSAPP_SEND", mapOf("action" to "send_message", "recipient" to "Mom", "message" to "Hi"))
        assertTrue(waEval.allowed)
        assertTrue(waEval.requiresApproval)

        // External communication in Driving Mode -> AUTO_GUARDED
        policy.drivingModeActive = true
        val drivingWaRisk = policy.classifyAction("WHATSAPP_SEND", mapOf("action" to "send_message", "recipient" to "Mom", "message" to "Driving"))
        assertEquals(AutonomyTier.AUTO_GUARDED, drivingWaRisk.tier)
        val drivingEval = policy.evaluate("WHATSAPP_SEND", mapOf("action" to "send_message", "recipient" to "Mom", "message" to "Driving"))
        assertTrue(drivingEval.allowed)
        assertFalse(drivingEval.requiresApproval)

        // Hardware Controls -> AUTO
        val flashRisk = policy.classifyAction("FLASHLIGHT", mapOf("action" to "on"))
        assertEquals(AutonomyTier.AUTO, flashRisk.tier)
        assertEquals(Reversibility.HIGH, flashRisk.reversibility)
        assertEquals(SideEffectScope.DEVICE_HARDWARE, flashRisk.scope)

        // Read-only inspection -> AUTO
        val readRisk = policy.classifyAction("BATTERY_CHECK", emptyMap())
        assertEquals(AutonomyTier.AUTO, readRisk.tier)
        assertEquals(SideEffectScope.INTERNAL_READ, readRisk.scope)
    }

    // -------------------------------------------------------------
    // 2. JarvisEventBus Reactive Routing Tests
    // -------------------------------------------------------------
    @Test
    fun testJarvisEventBusBroadcastAndSubscription() = runBlocking {
        val bus = JarvisEventBus(bufferCapacity = 16)

        bus.post(JarvisEvent.BatteryStateChanged(level = 42, isCharging = true))
        bus.post(JarvisEvent.IncomingCall(phoneNumber = "+1234567890", callerName = "Sarah"))
        bus.post(JarvisEvent.MediaStateChanged(isPlaying = true, title = "Resonance", artist = "HOME"))

        val batteryEvent = bus.subscribe<JarvisEvent.BatteryStateChanged>().first()
        assertEquals(42, batteryEvent.level)
        assertTrue(batteryEvent.isCharging)

        val callEvent = bus.subscribe<JarvisEvent.IncomingCall>().first()
        assertEquals("Sarah", callEvent.callerName)
        assertEquals("+1234567890", callEvent.phoneNumber)

        val mediaEvent = bus.subscribe<JarvisEvent.MediaStateChanged>().first()
        assertTrue(mediaEvent.isPlaying)
        assertEquals("Resonance", mediaEvent.title)
    }

    // -------------------------------------------------------------
    // 3. WorldStateStore Atomic Updates & History Ring Buffer Tests
    // -------------------------------------------------------------
    @Test
    fun testWorldStateStoreUpdatesAndHistory() {
        val store = WorldStateStore(WorldState(batteryPercent = 80, isCharging = false))

        assertEquals(80, store.current.batteryPercent)
        assertFalse(store.current.isCharging)

        // Update state
        store.update { it.copy(batteryPercent = 15, ambientMode = "DRIVING") }
        assertEquals(15, store.current.batteryPercent)
        assertEquals("DRIVING", store.current.ambientMode)

        // Check history
        val prior = store.getPriorSnapshot()
        assertNotNull(prior)
        assertEquals(80, prior?.batteryPercent)

        val snapshots = store.getRecentSnapshots(10)
        assertTrue(snapshots.size >= 2)
    }

    // -------------------------------------------------------------
    // 4. DeviceGuardian Invariant Enforcement Tests
    // -------------------------------------------------------------
    @Test
    fun testDeviceGuardianInvariants() {
        val store = WorldStateStore(WorldState(batteryPercent = 8, isCharging = false, isNetworkOnline = true))
        val guardian = DeviceGuardian(store)

        // Low battery (<10% and not charging) blocks proactive tasks
        val lowBatVerdict = guardian.canExecuteAutonomousTask()
        assertFalse(lowBatVerdict.allowed)
        assertTrue(lowBatVerdict.reason.contains("battery critically low"))

        // When charging, low battery does NOT block
        store.update { it.copy(isCharging = true) }
        assertTrue(guardian.canExecuteAutonomousTask().allowed)

        // Active phone call blocks proactive audio execution
        store.update { it.copy(activeCaller = "Boss") }
        val callVerdict = guardian.canExecuteAutonomousTask(requiresAudio = true)
        assertFalse(callVerdict.allowed)
        assertTrue(callVerdict.reason.contains("phone call currently active"))

        // Offline state blocks network-dependent tasks
        store.update { it.copy(activeCaller = null, isNetworkOnline = false) }
        val offlineVerdict = guardian.canExecuteAutonomousTask(requiresNetwork = true)
        assertFalse(offlineVerdict.allowed)
        assertTrue(offlineVerdict.reason.contains("offline"))
    }

    // -------------------------------------------------------------
    // 5. GoalManager & ExecutionPipeline with Rollback Tests
    // -------------------------------------------------------------
    private class MockTool(
        override val name: String,
        private val executeSuccess: Boolean = true,
        private val message: String = "Mock success"
    ) : Tool {
        override val description: String = "Mock tool for testing"
        override val policy: ToolPolicy = ToolPolicy(idempotent = true, retryable = true, riskLevel = RiskLevel.LOW)

        override suspend fun execute(params: Map<String, String>): ToolResult {
            return if (executeSuccess) ToolResult.Success(message) else ToolResult.Failed(message)
        }
    }

    @Test
    fun testGoalManagerLifecycleAndRollback() = runBlocking {
        val store = WorldStateStore(WorldState(batteryPercent = 90, isCharging = true, isMediaPlaying = true))
        val goalManager = GoalManager()
        val pipeline = ExecutionPipeline(
            worldStore = store,
            guardian = DeviceGuardian(store),
            verifier = OutcomeVerifier(),
            eventBus = JarvisEventBus(),
            goalManager = goalManager
        )

        val registry = ToolRegistry()
        registry.register(MockTool("MEDIA_STOP", executeSuccess = true, message = "Playback stopped"))
        val executor = com.jarvis.tools.ToolExecutor(registry)

        var rollbackExecuted = false
        val goal = Goal(
            objective = "Stop media playback",
            actionType = "MEDIA_STOP",
            rollbackAction = { rollbackExecuted = true }
        )

        goalManager.submitGoal(goal)
        assertEquals(GoalStatus.PENDING, goal.status)

        // Update state to simulate real media stopped
        store.update { it.copy(isMediaPlaying = false) }

        val res = pipeline.executeGoal(goal, executor)
        assertTrue(res.success)
        assertEquals(GoalStatus.COMPLETED, goal.status)
        assertFalse(rollbackExecuted)

        // Test failed execution with rollback
        registry.register(MockTool("FAILING_ACTION", executeSuccess = false, message = "Hardware error"))
        var rollbackFired = false
        val failingGoal = Goal(
            objective = "Perform risky action",
            actionType = "FAILING_ACTION",
            rollbackAction = { rollbackFired = true }
        )
        goalManager.submitGoal(failingGoal)

        val failRes = pipeline.executeGoal(failingGoal, executor)
        assertFalse(failRes.success)
        assertTrue(rollbackFired)
        assertEquals(GoalStatus.ROLLED_BACK, failingGoal.status)
    }

    // -------------------------------------------------------------
    // 6. OutcomeVerifier State Diff Tests
    // -------------------------------------------------------------
    @Test
    fun testOutcomeVerifierBeforeAfterDiff() {
        val verifier = OutcomeVerifier()

        val beforePlaying = WorldState(isMediaPlaying = true)
        val afterPaused = WorldState(isMediaPlaying = false)

        val verifyStop = verifier.verifyStateTransition(
            actionType = "MEDIA_STOP",
            params = emptyMap(),
            result = ToolResult.Success("Stopped"),
            before = beforePlaying,
            after = afterPaused
        )
        assertTrue(verifyStop.verified)

        val afterStillPlaying = WorldState(isMediaPlaying = true)
        val verifyFail = verifier.verifyStateTransition(
            actionType = "MEDIA_STOP",
            params = emptyMap(),
            result = ToolResult.Success("Sent stop intent"),
            before = beforePlaying,
            after = afterStillPlaying
        )
        assertFalse(verifyFail.verified)
    }

    // -------------------------------------------------------------
    // 7. FailureJournal & StrategyRegistry Tests
    // -------------------------------------------------------------
    @Test
    fun testFailureJournalAndStrategyAdaptation() {
        val journal = FailureJournal()
        journal.recordFailure("WHATSAPP_SEND", "NodeNotFound", mapOf("target" to "Send"))
        journal.recordFailure("WHATSAPP_SEND", "NodeNotFound", mapOf("target" to "Send"))
        assertFalse(journal.hasRepeatedFailure("WHATSAPP_SEND", threshold = 3))

        journal.recordFailure("WHATSAPP_SEND", "NodeNotFound", mapOf("target" to "Send"))
        assertTrue(journal.hasRepeatedFailure("WHATSAPP_SEND", threshold = 3))

        val reg = StrategyRegistry()
        val best = reg.getBestStrategy("WHATSAPP")
        assertNotNull(best)
        assertEquals("strat_wa_remote_input", best?.id)

        // Penalize top strategy on failure
        val initialWeight = best!!.weight
        reg.recordOutcome("strat_wa_remote_input", success = false)
        assertTrue(best.weight < initialWeight)

        // Reinforce secondary strategy on success
        reg.recordOutcome("strat_wa_accessibility_send", success = true)
        val reinforced = reg.getBestStrategy("WHATSAPP")
        assertNotNull(reinforced)
    }

    // -------------------------------------------------------------
    // 8. WakeDetector 3-State Transparency Tests
    // -------------------------------------------------------------
    private class FakePrimary(private val available: Boolean) : WakeWordDetector {
        override fun isModelAvailable(): Boolean = available
        override fun getModelLoadError(): String? = if (available) null else "Model missing"
        override fun pushAudio(samples: ShortArray): Float = if (available) 0.95f else 0.0f
        override fun resetDetectorState() {}
        override fun close() {}
    }

    @Test
    fun testWakeDetector3States() {
        // 1. Neural Ready
        val ready = HybridWakeWordDetector(customPrimary = FakePrimary(available = true))
        assertEquals(WakeDetectorMode.NEURAL_READY, ready.getDetectorMode())
        assertTrue(ready.isModelAvailable())

        // 2. Degraded Acoustic (when primary unavailable and fallback explicitly enabled)
        val degraded = HybridWakeWordDetector(
            customPrimary = FakePrimary(available = false),
            allowDegradedFallback = true
        )
        assertEquals(WakeDetectorMode.DEGRADED_ACOUSTIC, degraded.getDetectorMode())
        assertTrue(degraded.isModelAvailable())

        // 3. Unavailable (when primary unavailable and fallback disabled)
        val unavailable = HybridWakeWordDetector(
            customPrimary = FakePrimary(available = false),
            allowDegradedFallback = false
        )
        assertEquals(WakeDetectorMode.UNAVAILABLE, unavailable.getDetectorMode())
        assertFalse(unavailable.isModelAvailable())
        assertEquals(0.0f, unavailable.pushAudio(shortArrayOf(100, 200, 300)), 0.001f)
    }

    // -------------------------------------------------------------
    // 9. ToolRegistry Semantic Alias Adaptation Tests
    // -------------------------------------------------------------
    @Test
    fun testToolRegistrySemanticMediaPauseAlias() {
        val registry = ToolRegistry()
        registry.register(MockTool("MEDIA_CONTROL"))
        val canonical = registry.resolveCanonicalToolName("MEDIA_PAUSE")
        // Resolves to MEDIA_CONTROL, NOT MEDIA_STOP
        assertEquals("MEDIA_CONTROL", canonical)

        val adaptedParams = registry.adaptParamsForAlias("MEDIA_PAUSE", emptyMap())
        assertEquals("pause", adaptedParams["action"])

        val adaptedNext = registry.adaptParamsForAlias("NEXT_TRACK", emptyMap())
        assertEquals("next", adaptedNext["action"])

        val adaptedPrev = registry.adaptParamsForAlias("PREVIOUS_TRACK", emptyMap())
        assertEquals("previous", adaptedPrev["action"])
    }

    // -------------------------------------------------------------
    // 10. SecurityAuditor Production Sanitization Test
    // -------------------------------------------------------------
    @Test
    fun testSecurityAuditorCleanContextHandling() {
        // Production auditor with null context must return empty list (no fake mock packages)
        val prodAuditor = SecurityAuditor(context = null)
        assertTrue(prodAuditor.auditInstalledApps().isEmpty())
        assertTrue(prodAuditor.getCameraAndMicApps().isEmpty())

        // Dedicated test fixture provides deterministic fake packages
        val fakeAuditor = FakeSecurityAuditor()
        assertEquals(3, fakeAuditor.auditInstalledApps().size)
    }
}
