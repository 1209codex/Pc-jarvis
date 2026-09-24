package com.jarvis

import com.jarvis.agent.AgentWorkingMemory
import com.jarvis.agent.skills.CameraVisionSkill
import com.jarvis.agent.skills.SkillContext
import com.jarvis.agent.skills.VisionMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CameraVisionLiveOcrTest {

    private val skill = CameraVisionSkill()
    private fun createContext(goal: String) = SkillContext(goal, AgentWorkingMemory(goal))

    @Test
    fun testSnapIdentifyMode() {
        val mode = CameraVisionSkill.determineVisionMode("what is this object in front of me")
        assertEquals(VisionMode.SNAP_IDENTIFY, mode)
    }

    @Test
    fun testLiveOcrMode() {
        val mode1 = CameraVisionSkill.determineVisionMode("camera se live text padho")
        assertEquals(VisionMode.LIVE_OCR, mode1)

        val mode2 = CameraVisionSkill.determineVisionMode("scan medicine label in ocr mode")
        assertEquals(VisionMode.LIVE_OCR, mode2)
    }

    @Test
    fun testLivePerceptionMode() {
        val mode = CameraVisionSkill.determineVisionMode("live camera stream look around")
        assertEquals(VisionMode.LIVE_PERCEPTION, mode)
    }

    @Test
    fun testFacingAndFlashDetection() = runBlocking {
        val goal = "take a selfie photo in the dark with flash"
        val context = createContext(goal)

        assertTrue(skill.canHandle(goal, context))
        val result = skill.execute(goal, context)
        assertTrue(result.handled)
        assertNotNull(result.proposedAction)

        val action = result.proposedAction!!
        assertEquals("CAMERA_VISION", action.type)
        assertEquals("front", action.params["facing"])
        assertEquals("on", action.params["flash"])
    }

    @Test
    fun testLiveOcrExecution() = runBlocking {
        val goal = "camera se live paper padho"
        val context = createContext(goal)

        assertTrue(skill.canHandle(goal, context))
        val result = skill.execute(goal, context)
        assertTrue(result.handled)
        assertNotNull(result.proposedAction)

        val action = result.proposedAction!!
        assertEquals("CAMERA_VISION", action.type)
        assertEquals("LIVE_OCR", action.params["mode"])
    }
}
