package com.jarvis.telecom

import com.jarvis.agent.AgentWorkingMemory
import com.jarvis.agent.skills.SkillContext
import com.jarvis.agent.skills.TelecomSkill
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallScreeningAndTelecomTest {

    private val skill = TelecomSkill()
    private val context = SkillContext(
        goal = "answer call",
        workingMemory = AgentWorkingMemory(goal = "answer call")
    )

    @Test
    fun testTelecomSkill_answerCallTriggers() = runBlocking {
        assertTrue(skill.canHandle("answer call", context))
        assertTrue(skill.canHandle("phone uthao", context))
        assertTrue(skill.canHandle("call receive karo", context))

        val result = skill.execute("answer call", context)
        assertTrue(result.handled)
        assertEquals("TELEPHONY_CONTROL", result.proposedAction!!.type)
        assertEquals("answer_call", result.proposedAction!!.params["action"])
    }

    @Test
    fun testTelecomSkill_rejectCallWithSms() = runBlocking {
        val result = skill.execute("reject call saying driving right now", context)
        assertTrue(result.handled)
        assertEquals("TELEPHONY_CONTROL", result.proposedAction!!.type)
        assertEquals("reject_call", result.proposedAction!!.params["action"])
        assertTrue(result.proposedAction!!.params["message"]?.contains("driving") == true)
    }

    @Test
    fun testTelecomSkill_whoIsCalling() = runBlocking {
        val result = skill.execute("who is calling", context)
        assertTrue(result.handled)
        assertEquals("TELEPHONY_CONTROL", result.proposedAction!!.type)
        assertEquals("caller_info", result.proposedAction!!.params["action"])
    }

    @Test
    fun testTelecomSkill_readOtp() = runBlocking {
        val result = skill.execute("what is my otp", context)
        assertTrue(result.handled)
        assertEquals("TELEPHONY_CONTROL", result.proposedAction!!.type)
        assertEquals("sms_read", result.proposedAction!!.params["action"])
        assertEquals("true", result.proposedAction!!.params["otp_only"])
    }

    @Test
    fun testTelecomSkill_autoReplyModeConfig() = runBlocking {
        val result = skill.execute("driving mode auto reply chalu karo", context)
        assertTrue(result.handled)
        assertEquals("TELEPHONY_CONTROL", result.proposedAction!!.type)
        assertEquals("auto_reply", result.proposedAction!!.params["action"])
        assertEquals("driving", result.proposedAction!!.params["mode"])
    }
}
