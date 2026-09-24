package com.jarvis

import com.jarvis.tools.MediaPlaybackControlTool
import com.jarvis.tools.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HardwareSwitchboardAndMediaTest {

    @Test
    fun testMediaPlaybackControlInvalidAction() = runBlocking {
        val tool = MediaPlaybackControlTool(null)
        val res = tool.execute(mapOf("action" to "fly_to_moon"))
        assertTrue(res is ToolResult.Failed)
        assertTrue(res.message.contains("Unknown media control action"))
    }

    @Test
    fun testMediaVolumeActionWithoutAudioManager() = runBlocking {
        val tool = MediaPlaybackControlTool(null)
        val res = tool.execute(mapOf("action" to "volume_up"))
        assertTrue(res is ToolResult.Failed)
        assertEquals("AudioManager not available", res.message)
    }
}
