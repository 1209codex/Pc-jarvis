package com.jarvis

import android.content.ComponentCallbacks2
import com.jarvis.agent.MultiTaskDecomposer
import com.jarvis.autonomous.SelfHealingSupervisor
import com.jarvis.calendar.CalendarManager
import com.jarvis.context.ProactiveContextEngine
import com.jarvis.device.BatteryState
import com.jarvis.memory.IMemoryStore
import com.jarvis.memory.MemoryConflictResolver
import com.jarvis.memory.MemoryGarbageCollector
import com.jarvis.memory.MemoryItem
import com.jarvis.memory.MemoryType
import com.jarvis.runtime.SubsystemManager
import com.jarvis.tools.CalculatorTool
import com.jarvis.tools.FlashlightTool
import com.jarvis.tools.LazyTool
import com.jarvis.tools.NoteTool
import com.jarvis.tools.SearchMemoryTool
import com.jarvis.tools.ToolPolicy
import com.jarvis.tools.ToolRegistry
import com.jarvis.tools.ToolResult
import com.jarvis.ui.model.AppSettings
import com.jarvis.wakeword.VoiceEnrollmentCalibrator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-End Autonomous Workflow and System Verification Suite.
 *
 * Verifies the complete operational lifecycle of J.A.R.V.I.S. autonomous systems:
 * 1. Multi-Step Compound Goal Decomposition & Sequential Tool Execution.
 * 2. Proactive Alert Cooldown & Calendar Event Pre-Briefing Intelligence.
 * 3. Dynamic User Voice Calibration & Acoustic Thresholding.
 * 4. Persistent Note & Fact Storage, Conflict Superseding, and Garbage Collection.
 * 5. Deferred Subsystem Container Allocation, Lazy Tool Resolution, and OS Memory Pressure Eviction.
 * 6. Self-Healing Supervisor Deferred Task Resiliency.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EndToEndAutonomousWorkflowTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    // =========================================================================
    // 1. MULTI-STEP COMPOUND DECOMPOSITION & SEQUENTIAL EXECUTION
    // =========================================================================

    @Test
    fun testCompoundCommandDecompositionEnglish() {
        val compoundUtterance = "turn on flashlight and calculate 45*2"
        val subGoals = MultiTaskDecomposer.decompose(compoundUtterance)

        assertEquals(2, subGoals.size)
        assertEquals("turn on flashlight", subGoals[0])
        assertEquals("calculate 45*2", subGoals[1])
    }

    @Test
    fun testCompoundCommandDecompositionHinglish() {
        val compoundUtterance = "torch on karo aur 50 + 25 calculate karo"
        val subGoals = MultiTaskDecomposer.decompose(compoundUtterance)

        assertEquals(2, subGoals.size)
        assertEquals("torch on karo", subGoals[0])
        assertEquals("50 + 25 calculate karo", subGoals[1])
    }

    @Test
    fun testCompoundCommandDecompositionChainedConjunctions() {
        val chainedUtterance = "open settings then stop music and then flashlight on"
        val subGoals = MultiTaskDecomposer.decompose(chainedUtterance)

        assertEquals(3, subGoals.size)
        assertEquals("open settings", subGoals[0])
        assertEquals("stop music", subGoals[1])
        assertEquals("flashlight on", subGoals[2])
    }

    @Test
    fun testNonCompoundFalseSplitProtection() {
        // Noun phrases with "and" should never be split into separate sub-goals
        val nounPhrases = listOf(
            "rock and roll",
            "fast and furious",
            "tom and jerry",
            "salt and pepper",
            "bread and butter"
        )

        for (phrase in nounPhrases) {
            val result = MultiTaskDecomposer.decompose(phrase)
            assertEquals("Should not split noun phrase: '$phrase'", 1, result.size)
            assertEquals(phrase, result[0])
        }
    }

    @Test
    fun testAutonomousMultiStepToolExecutionFlow() = runBlocking {
        // Simulates the end-to-end execution of decomposed compound commands:
        // "turn on flashlight and calculate 45*2"
        val flashlightTool = FlashlightTool(context = null)
        val calculatorTool = CalculatorTool()

        val subGoals = MultiTaskDecomposer.decompose("turn on flashlight and calculate 45*2")
        val stepResults = mutableListOf<ToolResult>()

        for (goal in subGoals) {
            if (goal.contains("flashlight", ignoreCase = true) || goal.contains("torch", ignoreCase = true)) {
                val mode = if (goal.contains("off", ignoreCase = true)) "off" else "on"
                val res = flashlightTool.execute(mapOf("mode" to mode))
                stepResults.add(res)
            } else if (goal.contains("calculate", ignoreCase = true)) {
                val expr = goal.substringAfter("calculate").trim()
                val res = calculatorTool.execute(mapOf("expression" to expr))
                stepResults.add(res)
            }
        }

        assertEquals(2, stepResults.size)
        // Step 1: Flashlight (context=null -> handled safely in test mode)
        assertTrue(stepResults[0].success)
        assertTrue(stepResults[0].message.contains("Flashlight switched to on"))

        // Step 2: Calculator (45 * 2 = 90.0)
        assertTrue(stepResults[1].success)
        assertEquals("Result: 90.0", stepResults[1].message)
    }

    // =========================================================================
    // 2. PROACTIVE ALERT COOLDOWN & CALENDAR PRE-BRIEFING
    // =========================================================================

    @Test
    fun testProactiveBatteryAlertCriticalAndCooldown() {
        val proactiveEngine = ProactiveContextEngine(context = null)
        val t0 = 100_000_000L

        // Discharging at 10% battery -> Critical alert should trigger
        val criticalDischarging = BatteryState(percentage = 10, isCharging = false)
        val alert1 = proactiveEngine.evaluateBatteryAlert(
            state = criticalDischarging,
            now = t0,
            settingsOverride = AppSettings(proactiveBatteryAlertsEnabled = true)
        )
        assertNotNull(alert1)
        assertTrue(alert1!!.contains("critically low at 10%"))

        // Discharging at 9% battery only 5 minutes later -> Cooldown should SUPPRESS duplicate alert
        val fiveMinsLater = t0 + (5 * 60_000L)
        val alert2 = proactiveEngine.evaluateBatteryAlert(
            state = BatteryState(percentage = 9, isCharging = false),
            now = fiveMinsLater,
            settingsOverride = AppSettings(proactiveBatteryAlertsEnabled = true)
        )
        assertNull("Alert should be suppressed during 30-min cooldown window", alert2)

        // Discharging at 8% battery 31 minutes later -> Cooldown expired, alert should trigger again
        val thirtyOneMinsLater = t0 + (31 * 60_000L)
        val alert3 = proactiveEngine.evaluateBatteryAlert(
            state = BatteryState(percentage = 8, isCharging = false),
            now = thirtyOneMinsLater,
            settingsOverride = AppSettings(proactiveBatteryAlertsEnabled = true)
        )
        assertNotNull("Alert should trigger after 30-min cooldown expiration", alert3)
        assertTrue(alert3!!.contains("critically low at 8%"))

        // Plugged in charging -> Low battery alert should NOT trigger
        val chargingLow = BatteryState(percentage = 10, isCharging = true)
        val alertCharging = proactiveEngine.evaluateBatteryAlert(
            state = chargingLow,
            now = thirtyOneMinsLater + (35 * 60_000L),
            settingsOverride = AppSettings(proactiveBatteryAlertsEnabled = true)
        )
        assertNull("Low battery alert should not trigger while charging", alertCharging)
    }

    @Test
    fun testProactiveCalendarPreBriefingDeduplication() {
        val now = 10_000_000L
        val eventStart = now + (10 * 60_000L) // 10 minutes in future

        val calendarManager = CalendarManager(context = null)
        calendarManager.addEvent(
            title = "Sprint Planning Architecture Sync",
            startTimeMillis = eventStart,
            durationMinutes = 60,
            location = "Room 401"
        )

        val proactiveEngine = ProactiveContextEngine(
            context = null,
            calendarManager = calendarManager
        )

        // First evaluation should announce the event
        val announcements1 = proactiveEngine.evaluateCalendarEvents(now, preBriefingMinutes = 15)
        assertEquals(1, announcements1.size)
        assertTrue(announcements1[0].contains("Sprint Planning Architecture Sync"))
        assertTrue(announcements1[0].contains("in 10 minutes"))
        assertTrue(announcements1[0].contains("Room 401"))

        // Second evaluation 1 minute later should NOT duplicate announcement
        val announcements2 = proactiveEngine.evaluateCalendarEvents(now + 60_000L, preBriefingMinutes = 15)
        assertEquals(0, announcements2.size)
    }

    // =========================================================================
    // 3. DYNAMIC USER VOICE CALIBRATION & ACOUSTIC THRESHOLDING
    // =========================================================================

    @Test
    fun testVoiceEnrollmentValidationAndCalibration() {
        val calibrator = VoiceEnrollmentCalibrator()

        // 1. Empty audio should be rejected
        val emptyResult = calibrator.validateSample(ShortArray(0))
        assertTrue(emptyResult is VoiceEnrollmentCalibrator.ValidationResult.Rejected)
        assertEquals(
            VoiceEnrollmentCalibrator.RejectionReason.EMPTY_AUDIO,
            (emptyResult as VoiceEnrollmentCalibrator.ValidationResult.Rejected).reason
        )

        // 2. Synthesize valid speech sample: 16000 Hz, 800ms duration, sine wave (frequency 440Hz), RMS ~1400
        val sampleRate = 16000
        val durationSamples = (sampleRate * 0.8).toInt() // 800ms
        val validPcm = ShortArray(durationSamples) { i ->
            (2000.0 * Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate)).toInt().toShort()
        }

        val validResult = calibrator.validateSample(validPcm)
        assertTrue(validResult is VoiceEnrollmentCalibrator.ValidationResult.Valid)
        val validSample = validResult as VoiceEnrollmentCalibrator.ValidationResult.Valid
        assertTrue(validSample.rmsEnergy > 500.0)
        assertTrue(validSample.speechDurationMs >= 400L)
        assertEquals(0.0, validSample.clippingRatio, 0.001)

        // 3. Calibrate personalized profile from 3 valid samples
        val samples = listOf(
            VoiceEnrollmentCalibrator.ValidatedSample(validPcm, validSample.rmsEnergy, 0.85f, validSample.speechDurationMs),
            VoiceEnrollmentCalibrator.ValidatedSample(validPcm, validSample.rmsEnergy * 1.1, 0.90f, validSample.speechDurationMs),
            VoiceEnrollmentCalibrator.ValidatedSample(validPcm, validSample.rmsEnergy * 0.95, 0.88f, validSample.speechDurationMs)
        )

        val profile = calibrator.calibrate(samples, wakeWordPhrase = "Jarvis")
        assertTrue(profile.isEnrolled)
        assertEquals(3, profile.sampleCount)
        assertEquals("Jarvis", profile.wakeWordPhrase)
        assertTrue("Calibrated threshold should be clamped in [0.55, 0.75]", profile.calibratedThreshold in 0.55f..0.75f)
        assertTrue("Calibrated min energy should be clamped in [12.0, 45.0]", profile.calibratedMinEnergyRms in 12.0..45.0)
        assertTrue("Ambient noise floor should be less than calibrated min energy", profile.ambientNoiseRms < profile.calibratedMinEnergyRms)
    }

    // =========================================================================
    // 4. NOTE & FACT MEMORY PERSISTENCE, SUPERSEDING, AND RETRIEVAL
    // =========================================================================

    private class InMemTestStore : IMemoryStore {
        private val items = mutableListOf<MemoryItem>()
        private var nextId = 1L

        override fun queryByKey(key: String, namespace: String): List<MemoryItem> {
            return items.filter { it.key == key && it.namespace == namespace && !it.archived }
                .sortedByDescending { it.version }
        }

        override fun getAllActiveMemories(namespace: String): List<MemoryItem> {
            return items.filter { it.namespace == namespace && !it.archived }
                .sortedByDescending { it.createdAt }
        }

        override fun archiveMemory(id: Long) {
            val idx = items.indexOfFirst { it.id == id }
            if (idx != -1) {
                items[idx] = items[idx].copy(archived = true)
            }
        }

        override fun saveMemoryItem(item: MemoryItem): Long {
            val id = if (item.id <= 0) nextId++ else item.id
            val saved = item.copy(id = id)
            items.add(saved)
            return id
        }

        override fun rebuildFtsIndex() {}
    }

    @Test
    fun testMemoryConflictResolutionAndSuperseding() {
        val store = InMemTestStore()
        val resolver = MemoryConflictResolver(store)

        // Store initial preference
        val item1 = MemoryItem(
            id = 1L,
            type = MemoryType.USER_PREFERENCE,
            key = "coffee_preference",
            content = "Latte with oat milk",
            provenance = "user_input",
            createdAt = 1000L,
            version = 1
        )
        val savedId1 = store.saveMemoryItem(item1)

        // Store conflicting new preference
        val item2 = MemoryItem(
            type = MemoryType.USER_PREFERENCE,
            key = "coffee_preference",
            content = "Espresso with no milk",
            provenance = "user_input",
            createdAt = 2000L,
            version = 1
        )

        resolver.resolveAndStore(item2)

        val active = store.getAllActiveMemories()
        assertEquals(1, active.size)
        assertEquals("Espresso with no milk", active[0].content)
        assertEquals(2, active[0].version)
        assertEquals(savedId1.toString(), active[0].supersedesId)
    }

    @Test
    fun testMemoryGarbageCollectorTtlAndHalfLife() {
        val store = InMemTestStore()
        val gc = MemoryGarbageCollector(store)
        val now = System.currentTimeMillis()

        // Expired transient item (e.g. temporary context snippet)
        val transientItem = MemoryItem(
            type = MemoryType.DOCUMENT_SNIPPET,
            key = "temp_url",
            content = "https://example.com/token",
            provenance = "browser",
            importance = 0.2,
            createdAt = now - TimeUnit.HOURS.toMillis(4),
            expiresAt = now - TimeUnit.HOURS.toMillis(1) // expired 1 hr ago
        )
        store.saveMemoryItem(transientItem)

        // Permanent user preference (importance = 1.0, no expiration)
        val permItem = MemoryItem(
            type = MemoryType.USER_PREFERENCE,
            key = "user_name",
            content = "Stark",
            provenance = "settings",
            importance = 1.0,
            createdAt = now - TimeUnit.DAYS.toMillis(100) // 100 days old
        )
        store.saveMemoryItem(permItem)

        // Run GC sweep
        val archivedCount = gc.runGarbageCollection(now)
        assertEquals(1, archivedCount)

        val active = store.getAllActiveMemories()
        assertEquals(1, active.size)
        assertEquals("user_name", active[0].key)
    }

    // =========================================================================
    // 5. SUBSYSTEM CONTAINER ALLOCATION & OS MEMORY PRESSURE EVICTION
    // =========================================================================

    @Test
    fun testSubsystemManagerDeferredAllocationAndEviction() {
        val manager = SubsystemManager(context = null)

        // Core subsystems must be immediately accessible
        assertNotNull(manager.eventBus)
        assertNotNull(manager.worldStore)
        assertNotNull(manager.goalManager)
        assertNotNull(manager.deviceGuardian)

        // Heavy subsystems must start unallocated
        assertFalse("Vision subsystem should not be allocated at boot", manager.isVisionInitialized())
        assertFalse("RAG subsystem should not be allocated at boot", manager.isRagInitialized())
        assertFalse("Telephony subsystem should not be allocated at boot", manager.isTelephonyInitialized())
        assertFalse("TTS subsystem should not be allocated at boot", manager.isTtsInitialized())

        // Accessing cameraDetector triggers on-demand allocation
        val camera = manager.cameraDetector
        assertNotNull(camera)
        assertTrue("Vision subsystem should be allocated after access", manager.isVisionInitialized())

        // OS Memory Pressure: TRIM_MEMORY_RUNNING_CRITICAL evicts heavy subsystem instances
        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertFalse("Vision subsystem should be evicted on critical memory trim", manager.isVisionInitialized())
    }

    @Test
    fun testLazyToolDeferredResolution() = runBlocking {
        val registry = ToolRegistry()
        val initCount = AtomicInteger(0)

        registry.registerLazy(
            name = "DEEP_FILE_ANALYZER",
            description = "Analyzes filesystem structure lazily",
            policy = ToolPolicy()
        ) {
            initCount.incrementAndGet()
            object : com.jarvis.tools.Tool {
                override val name = "DEEP_FILE_ANALYZER"
                override val description = "Analyzes filesystem structure lazily"
                override suspend fun execute(params: Map<String, String>): ToolResult {
                    return ToolResult(true, "Analysis complete")
                }
            }
        }

        // Discovery in registry does NOT instantiate tool
        val registeredTool = registry.get("DEEP_FILE_ANALYZER")
        assertNotNull(registeredTool)
        assertTrue(registeredTool is LazyTool)
        assertFalse((registeredTool as LazyTool).isInitialized)
        assertEquals(0, initCount.get())

        // First execution initializes instance
        val res1 = registeredTool.execute(emptyMap())
        assertTrue(res1.success)
        assertEquals("Analysis complete", res1.message)
        assertEquals(1, initCount.get())
        assertTrue(registeredTool.isInitialized)

        // Evict idle lazy tools
        val evicted = registry.evictIdleLazyTools()
        assertEquals(1, evicted)
        assertFalse(registeredTool.isInitialized)

        // Re-executes on subsequent call
        val res2 = registeredTool.execute(emptyMap())
        assertTrue(res2.success)
        assertEquals(2, initCount.get())
    }

    // =========================================================================
    // 6. SELF-HEALING SUPERVISOR RESILIENCY
    // =========================================================================

    @Test
    fun testSelfHealingSupervisorDeferredTaskResiliency() = runBlocking {
        val replayed = mutableListOf<String>()
        val supervisor = SelfHealingSupervisor(
            scope = testScope,
            onDeferredTaskDue = { task -> replayed.add(task.goal) }
        )

        supervisor.queueDeferredTask("SYNC_NOTES", "Network offline")
        assertEquals(1, supervisor.getDeferredTasks().size)
        assertEquals("SYNC_NOTES", supervisor.getDeferredTasks()[0].goal)

        val replayedTasks = supervisor.replayDeferredTasks("WiFi Restored")
        assertEquals(1, replayedTasks.size)
        assertEquals("SYNC_NOTES", replayedTasks[0].goal)
        assertTrue(supervisor.getDeferredTasks().isEmpty())
    }
}
