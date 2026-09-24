package com.jarvis

import com.jarvis.ai.AssistantIntent
import com.jarvis.ai.IntentResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CommandFunctionTriggerTest {

    @Test
    fun testFlashlightCommandsTriggerCorrectIntent() {
        val onCases = listOf("turn on flashlight", "torch on karo", "flashlight on", "torch chalu karo", "light on karo")
        for (cmd in onCases) {
            val resolved = IntentResolver.resolve(cmd)
            assertNotNull("Should resolve for '$cmd'", resolved)
            assertEquals("AUTONOMOUS_CONTROL", resolved!!.intent.name)
            assertEquals("on", resolved.params["mode"])
        }

        val offCases = listOf("turn off flashlight", "torch band karo", "flashlight off", "torch off", "light band karo")
        for (cmd in offCases) {
            val resolved = IntentResolver.resolve(cmd)
            assertNotNull("Should resolve for '$cmd'", resolved)
            assertEquals("AUTONOMOUS_CONTROL", resolved!!.intent.name)
            assertEquals("off", resolved.params["mode"])
        }
    }

    @Test
    fun testVolumeCommandsTriggerCorrectIntent() {
        val upCases = listOf("volume up", "increase volume", "volume badhao", "awaaz badhao", "awaz badhao")
        for (cmd in upCases) {
            val resolved = IntentResolver.resolve(cmd)
            assertNotNull("Should resolve for '$cmd'", resolved)
            assertEquals(AssistantIntent.MEDIA_VOLUME, resolved!!.intent)
            assertEquals("volume_up", resolved.params["action"])
        }

        val downCases = listOf("volume down", "decrease volume", "volume kam karo", "awaaz kam karo", "awaz kam karo")
        for (cmd in downCases) {
            val resolved = IntentResolver.resolve(cmd)
            assertNotNull("Should resolve for '$cmd'", resolved)
            assertEquals(AssistantIntent.MEDIA_VOLUME, resolved!!.intent)
            assertEquals("volume_down", resolved.params["action"])
        }

        val mute = IntentResolver.resolve("mute karo")
        assertNotNull(mute)
        assertEquals(AssistantIntent.DEVICE_SETTINGS, mute!!.intent)
        assertEquals("mute", mute.params["action"])

        val unmute = IntentResolver.resolve("unmute karo")
        assertNotNull(unmute)
        assertEquals(AssistantIntent.DEVICE_SETTINGS, unmute!!.intent)
        assertEquals("unmute", unmute.params["action"])

        val pct80 = IntentResolver.resolve("volume 80%")
        assertNotNull(pct80)
        assertEquals(AssistantIntent.MEDIA_VOLUME, pct80!!.intent)
        assertEquals("set_volume", pct80.params["action"])
        assertEquals("80", pct80.params["volume_percent"])

        val pct50 = IntentResolver.resolve("volume 50 percent karo")
        assertNotNull(pct50)
        assertEquals(AssistantIntent.MEDIA_VOLUME, pct50!!.intent)
        assertEquals("set_volume", pct50.params["action"])
        assertEquals("50", pct50.params["volume_percent"])

        val full = IntentResolver.resolve("full volume")
        assertNotNull(full)
        assertEquals(AssistantIntent.MEDIA_VOLUME, full!!.intent)
        assertEquals("set_volume", full.params["action"])
        assertEquals("100", full.params["volume_percent"])
    }

    @Test
    fun testScreenLockAndUnlockCommands() {
        val lock = IntentResolver.resolve("phone lock karo")
        assertNotNull(lock)
        assertEquals(AssistantIntent.SCREEN_LOCK, lock!!.intent)

        val unlock = IntentResolver.resolve("phone unlock karo")
        assertNotNull(unlock)
        assertEquals(AssistantIntent.SCREEN_UNLOCK, unlock!!.intent)
    }

    @Test
    fun testCloseAllApps() {
        val closeAll = IntentResolver.resolve("close all apps")
        assertNotNull(closeAll)
        assertEquals(AssistantIntent.APPS_CLOSE_ALL, closeAll!!.intent)
    }

    @Test
    fun testSystemSwitchboardWiFiAndBluetooth() {
        val wifiOn = IntentResolver.resolve("wifi on karo")
        assertNotNull(wifiOn)
        assertEquals("set_wifi", wifiOn!!.params["action"])
        assertEquals("on", wifiOn.params["state"])

        val btOff = IntentResolver.resolve("bluetooth off karo")
        assertNotNull(btOff)
        assertEquals("set_bluetooth", btOff!!.params["action"])
        assertEquals("off", btOff.params["state"])
    }

    @Test
    fun testCalendarAndRagCommands() {
        val agenda = IntentResolver.resolve("today's agenda")
        assertNotNull(agenda)
        assertEquals(AssistantIntent.CALENDAR_AGENDA, agenda!!.intent)

        val rag = IntentResolver.resolve("search documents for ai research")
        assertNotNull(rag)
        assertEquals(AssistantIntent.RAG_QUERY, rag!!.intent)
    }

    @Test
    fun testAgentKernelFallbackRules() {
        val volUp = com.jarvis.agent.AgentKernel.matchFallbackRules("awaaz badhao")
        assertEquals("MEDIA_CONTROL", volUp.action?.type)
        assertEquals("volume_up", volUp.action?.params?.get("action"))

        val lock = com.jarvis.agent.AgentKernel.matchFallbackRules("phone lock karo")
        assertEquals("SCREEN_LOCK", lock.action?.type)
        assertEquals("lock", lock.action?.params?.get("action"))

        val unlock = com.jarvis.agent.AgentKernel.matchFallbackRules("screen on karo")
        assertEquals("SCREEN_UNLOCK", unlock.action?.type)
        assertEquals("unlock", unlock.action?.params?.get("action"))

        val wifi = com.jarvis.agent.AgentKernel.matchFallbackRules("wifi off karo")
        assertEquals("SYSTEM_SWITCHBOARD", wifi.action?.type)
        assertEquals("off", wifi.action?.params?.get("state"))

        val battery = com.jarvis.agent.AgentKernel.matchFallbackRules("battery kitni hai")
        assertEquals("BATTERY_CHECK", battery.action?.type)

        val clock = com.jarvis.agent.AgentKernel.matchFallbackRules("aaj ki date kya hai")
        assertEquals("CLOCK", clock.action?.type)
        assertEquals("current_date", clock.action?.params?.get("action"))

        val calc = com.jarvis.agent.AgentKernel.matchFallbackRules("calculate 5 * 20")
        assertEquals("CALCULATE", calc.action?.type)

        val location = com.jarvis.agent.AgentKernel.matchFallbackRules("where am i")
        assertEquals("LOCATION", location.action?.type)
    }
}
