package com.jarvis

import com.jarvis.agent.AgentWorkingMemory
import com.jarvis.agent.skills.AttachmentType
import com.jarvis.agent.skills.CommunicationSkill
import com.jarvis.agent.skills.SkillContext
import com.jarvis.agent.skills.WhatsAppMessagingSkill
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WhatsAppAttachmentSkillTest {

    private val skill = WhatsAppMessagingSkill()
    private fun createContext(goal: String) = SkillContext(goal, AgentWorkingMemory(goal))

    @Test
    fun testPhotoAttachmentExtraction() = runBlocking {
        val goal = "Rahul ko recent photo whatsapp par bhejo"
        val context = createContext(goal)
        assertTrue(skill.canHandle(goal, context))

        val result = skill.execute(goal, context)
        assertTrue(result.handled)
        assertNotNull(result.proposedAction)
        val action = result.proposedAction!!
        assertEquals("WHATSAPP", action.type)
        assertEquals("send_media", action.params["action"])
        assertEquals("IMAGE", action.params["attachmentType"])
        assertEquals("Rahul", action.params["recipient"])
        assertEquals("latest_photo", action.params["fileFilter"])
    }

    @Test
    fun testDocumentAttachmentExtraction() = runBlocking {
        val goal = "Send resume.pdf to Dollar on WhatsApp"
        val context = createContext(goal)
        assertTrue(skill.canHandle(goal, context))

        val result = skill.execute(goal, context)
        assertTrue(result.handled)
        assertNotNull(result.proposedAction)
        val action = result.proposedAction!!
        assertEquals("WHATSAPP", action.type)
        assertEquals("send_media", action.params["action"])
        assertEquals("DOCUMENT", action.params["attachmentType"])
        assertEquals("Dollar", action.params["recipient"])
        assertEquals("resume.pdf", action.params["fileFilter"])
    }

    @Test
    fun testScreenshotAttachmentExtraction() = runBlocking {
        val goal = "Send screenshot to Mummy on WhatsApp"
        val context = createContext(goal)
        assertTrue(skill.canHandle(goal, context))

        val result = skill.execute(goal, context)
        assertTrue(result.handled)
        assertNotNull(result.proposedAction)
        val action = result.proposedAction!!
        assertEquals("WHATSAPP", action.type)
        assertEquals("send_media", action.params["action"])
        assertEquals("SCREENSHOT", action.params["attachmentType"])
        assertEquals("Mummy", action.params["recipient"])
        assertEquals("latest_screenshot", action.params["fileFilter"])
    }

    @Test
    fun testAudioAttachmentExtraction() = runBlocking {
        val info = CommunicationSkill.extractAttachmentInfo("Papa ko voice note bhejo")
        assertEquals(AttachmentType.AUDIO, info.attachmentType)
        assertEquals("Papa", info.recipient)
        assertEquals("latest_audio", info.fileFilter)
    }

    @Test
    fun testStandardTextMessageFallback() = runBlocking {
        val goal = "Rahul ko hello send karo"
        val context = createContext(goal)
        val result = skill.execute(goal, context)
        assertTrue(result.handled)
        assertNotNull(result.proposedAction)
        val action = result.proposedAction!!
        assertEquals("WHATSAPP", action.type)
        assertEquals("send_message", action.params["action"])
        assertEquals("Rahul", action.params["recipient"])
        assertEquals("Hello!", action.params["message"])
    }

    @Test
    fun testDollerWhatsAppCommand() = runBlocking {
        val goal = "Doller ko whatsapp pe Hello send karo"
        val context = createContext(goal)
        assertTrue(skill.canHandle(goal, context))

        val result = skill.execute(goal, context)
        assertTrue(result.handled)
        assertNotNull(result.proposedAction)
        val action = result.proposedAction!!
        assertEquals("WHATSAPP", action.type)
        assertEquals("send_message", action.params["action"])
        assertTrue(action.params["recipient"]!!.equals("Doller", ignoreCase = true) || action.params["recipient"]!!.equals("Dollar", ignoreCase = true))
        assertEquals("Hello!", action.params["message"])
    }
}
