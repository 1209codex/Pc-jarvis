package com.jarvis

import android.content.ComponentCallbacks2
import com.jarvis.runtime.SubsystemManager
import com.jarvis.telecom.CallAndSmsAgent
import com.jarvis.tools.LazyTool
import com.jarvis.tools.Tool
import com.jarvis.tools.ToolPolicy
import com.jarvis.tools.ToolRegistry
import com.jarvis.tools.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SubsystemContainerAndMemoryOptimizationTest {

    @Test
    fun testTelephonySubsystemRetainsSmsAgentCreatedBeforeRuntime() {
        CallAndSmsAgent.destroy()
        val inboundAgent = CallAndSmsAgent(context = null)

        val runtimeAgent = runCatching { SubsystemManager(context = null).callAndSmsAgent }.getOrNull()

        assertEquals("The runtime must retain SMS received before it initializes", inboundAgent, runtimeAgent)
    }

    private class HeavyMockTool(val initCounter: AtomicInteger) : Tool {
        override val name: String = "HEAVY_MOCK_TOOL"
        override val description: String = "A heavy mock tool for testing lazy loading"

        init {
            initCounter.incrementAndGet()
        }

        override suspend fun execute(params: Map<String, String>): ToolResult {
            return ToolResult.Success("mock_output")
        }
    }

    @Test
    fun testLazyToolMetadataDiscoveryWithoutInitialization() {
        val initCounter = AtomicInteger(0)
        val lazyTool = LazyTool(
            name = "HEAVY_MOCK_TOOL",
            description = "A heavy mock tool for testing lazy loading",
            policy = ToolPolicy(),
            toolProvider = { HeavyMockTool(initCounter) }
        )

        // Metadata discovery should NOT trigger initialization
        assertEquals("HEAVY_MOCK_TOOL", lazyTool.name)
        assertEquals("A heavy mock tool for testing lazy loading", lazyTool.description)
        assertFalse("Tool should not be initialized yet", lazyTool.isInitialized)
        assertEquals(0, initCounter.get())
    }

    @Test
    fun testLazyToolExecutionAndEviction() = runBlocking {
        val initCounter = AtomicInteger(0)
        val lazyTool = LazyTool(
            name = "HEAVY_MOCK_TOOL",
            description = "A heavy mock tool",
            policy = ToolPolicy(),
            toolProvider = { HeavyMockTool(initCounter) }
        )

        assertFalse(lazyTool.isInitialized)

        // Execute tool -> triggers initializer
        val result = lazyTool.execute(emptyMap())
        assertTrue(result.success)
        assertEquals("mock_output", result.message)
        assertTrue("Tool must be marked initialized", lazyTool.isInitialized)
        assertEquals(1, initCounter.get())

        // Second execution should reuse instance without re-initializing
        val result2 = lazyTool.execute(emptyMap())
        assertTrue(result2.success)
        assertEquals(1, initCounter.get())

        // Evict tool
        lazyTool.evict()
        assertFalse("Tool should be uninitialized after eviction", lazyTool.isInitialized)

        // Third execution re-initializes
        val result3 = lazyTool.execute(emptyMap())
        assertTrue(result3.success)
        assertEquals(2, initCounter.get())
    }

    @Test
    fun testToolRegistryLazyRegistrationAndEviction() = runBlocking {
        val registry = ToolRegistry()
        val initCounter = AtomicInteger(0)

        registry.registerLazy(
            name = "LAZY_VISION",
            description = "On-demand vision tool",
            policy = ToolPolicy()
        ) {
            HeavyMockTool(initCounter)
        }

        // Discovery works without instantiation
        val found = registry.get("LAZY_VISION")
        assertNotNull(found)
        assertTrue(found is LazyTool)
        assertEquals(0, initCounter.get())
        assertTrue(registry.getAvailableToolsDescription().contains("LAZY_VISION"))

        // Invoking executes tool
        val res = found!!.execute(emptyMap())
        assertTrue(res.success)
        assertEquals(1, initCounter.get())

        // Evict idle lazy tools
        val evictedCount = registry.evictIdleLazyTools()
        assertEquals(1, evictedCount)
        assertFalse((found as LazyTool).isInitialized)

        // Can still find and execute again
        val resAfter = found.execute(emptyMap())
        assertTrue(resAfter.success)
        assertEquals(2, initCounter.get())
    }

    @Test
    fun testCoreSubsystemsAvailableImmediately() {
        val manager = SubsystemManager(context = null)

        // Core systems must be non-null and immediately available
        assertNotNull(manager.worldStore)
        assertNotNull(manager.eventBus)
        assertNotNull(manager.deviceGuardian)
        assertNotNull(manager.goalManager)
        assertNotNull(manager.failureJournal)
        assertNotNull(manager.strategyRegistry)
        assertNotNull(manager.metrics)
        assertNotNull(manager.policyEngine)
        assertNotNull(manager.conversationManager)
        assertNotNull(manager.vadEngine)

        // Heavy subsystems must NOT be initialized at boot
        assertFalse("Vision should not be initialized at start", manager.isVisionInitialized())
        assertFalse("RAG should not be initialized at start", manager.isRagInitialized())
        assertFalse("Telephony should not be initialized at start", manager.isTelephonyInitialized())
        assertFalse("TTS should not be initialized at start", manager.isTtsInitialized())
    }

    @Test
    fun testVisionSubsystemDeferredAllocationAndEviction() {
        val manager = SubsystemManager(context = null)
        assertFalse(manager.isVisionInitialized())

        // Accessing cameraDetector triggers on-demand instantiation
        val detector = manager.cameraDetector
        assertNotNull(detector)
        assertTrue(manager.isVisionInitialized())

        // Evict heavy subsystems
        manager.evictHeavySubsystems(aggressive = false)
        assertFalse("Vision detector should be evicted", manager.isVisionInitialized())
    }

    @Test
    fun testMemoryTrimPressureHandling() {
        val manager = SubsystemManager(context = null)

        // Trigger vision detector allocation
        assertNotNull(manager.cameraDetector)
        assertTrue(manager.isVisionInitialized())

        // Simulate OS memory warning (UI Hidden / moderate)
        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertFalse("Vision should be evicted on TRIM_MEMORY_UI_HIDDEN", manager.isVisionInitialized())

        // Allocate again
        assertNotNull(manager.cameraDetector)
        assertTrue(manager.isVisionInitialized())

        // Simulate OS memory critical pressure
        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertFalse("Vision should be evicted on TRIM_MEMORY_RUNNING_CRITICAL", manager.isVisionInitialized())
    }

    @Test
    fun testSubsystemMemoryDiagnosticsReport() {
        val manager = SubsystemManager(context = null)
        val initialReport = manager.getMemoryReport()

        assertEquals(9, initialReport.totalSubsystems)
        assertEquals(4, initialReport.initializedSubsystems)
        assertEquals(0, initialReport.totalEvictions)
        assertTrue(initialReport.activeSubsystemNames.contains("Core"))
        assertTrue(initialReport.activeSubsystemNames.contains("WorldStore"))
        assertFalse(initialReport.activeSubsystemNames.contains("Vision"))

        // Initialize vision
        assertNotNull(manager.cameraDetector)
        val reportWithVision = manager.getMemoryReport()
        assertEquals(5, reportWithVision.initializedSubsystems)
        assertTrue(reportWithVision.activeSubsystemNames.contains("Vision"))

        // Evict
        manager.evictHeavySubsystems(aggressive = true)
        val reportAfterEvict = manager.getMemoryReport()
        assertTrue(reportAfterEvict.totalEvictions >= 1)
        assertFalse(reportAfterEvict.activeSubsystemNames.contains("Vision"))
    }

    @Test
    fun testSubsystemManagerReleaseAll() {
        val manager = SubsystemManager(context = null)
        assertNotNull(manager.cameraDetector)
        assertTrue(manager.isVisionInitialized())

        manager.releaseAll()
        assertFalse(manager.isVisionInitialized())
    }
}
