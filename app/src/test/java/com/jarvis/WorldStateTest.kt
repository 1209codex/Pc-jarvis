package com.jarvis

import com.jarvis.agent.AgentContextRouter
import com.jarvis.agent.AgentState
import com.jarvis.agent.AgentWorkingMemory
import com.jarvis.foundation.worldstate.StateProvider
import com.jarvis.foundation.worldstate.WorldStateService
import com.jarvis.foundation.worldstate.WorldStateSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeStateProvider(var snapshot: WorldStateSnapshot) : StateProvider {
    var reads = 0
    override fun snapshot(): WorldStateSnapshot {
        reads++
        return snapshot
    }
}

class WorldStateTest {

    @Test
    fun freshLatestAndPriorTrackBeforeAndAfter() {
        val provider = FakeStateProvider(WorldStateSnapshot(batteryPercent = 60, isCharging = false))
        val service = WorldStateService(provider)

        val before = service.fresh()
        provider.snapshot = WorldStateSnapshot(batteryPercent = 61, isCharging = true)
        val after = service.fresh()

        assertEquals(60, before.batteryPercent)
        assertEquals(61, after.batteryPercent)
        assertEquals(before, service.prior())
        assertEquals(after, service.latest())
        assertEquals(2, provider.reads)
    }

    @Test
    fun latestFallsBackToProviderBeforeAnyFresh() {
        val provider = FakeStateProvider(WorldStateSnapshot(batteryPercent = 42))
        val service = WorldStateService(provider)

        assertEquals(42, service.latest().batteryPercent)
    }

    @Test
    fun historyIsBounded() {
        val provider = FakeStateProvider(WorldStateSnapshot(batteryPercent = 1))
        val service = WorldStateService(provider)

        repeat(WorldStateService.MAX_HISTORY + 5) {
            provider.snapshot = WorldStateSnapshot(batteryPercent = it)
            service.fresh()
        }

        assertEquals(WorldStateService.MAX_HISTORY, service.historySize)
        assertEquals((WorldStateService.MAX_HISTORY + 4).toLong(), service.latest().batteryPercent.toLong())
        assertEquals((WorldStateService.MAX_HISTORY + 3).toLong(), service.prior()?.batteryPercent?.toLong())
    }

    @Test
    fun overviewRendersKeyFields() {
        val snapshot = WorldStateSnapshot(
            batteryPercent = 82,
            isCharging = true,
            ambientMode = "In Meeting",
            activeMeetingTitle = "Executive Sync",
            notificationCount = 3,
            mediaPlaying = true,
            mediaTitle = "Lofi"
        )
        val text = snapshot.overview()
        assertTrue(text.contains("82%"))
        assertTrue(text.contains("charging"))
        assertTrue(text.contains("Executive Sync"))
        assertTrue(text.contains("3 unread notifications"))
        assertTrue(text.contains("Lofi"))
    }

    @Test
    fun routerInjectsDeviceStateOnlyForStateQuestions() {
        val router = AgentContextRouter(
            stateProvider = FakeStateProvider(WorldStateSnapshot(batteryPercent = 7))
        )
        val memory = AgentWorkingMemory(goal = "")
        val state = AgentState(taskId = 1, goal = "battery status")

        val plain = router.buildContext("play some music", state, memory)
        val stateful = router.buildContext("kya battery status hai", state, memory)

        assertEquals("", plain)
        assertTrue(stateful.contains("DEVICE STATE"))
        assertTrue(stateful.contains("7%"))
    }
}