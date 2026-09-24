package com.jarvis

import com.jarvis.agent.AgentWorkingMemory
import com.jarvis.agent.skills.SkillContext
import com.jarvis.agent.skills.TelecomSkill
import com.jarvis.ai.AssistantIntent
import com.jarvis.ai.IntentResolver
import com.jarvis.telecom.CallAndSmsAgent
import com.jarvis.telecom.ContactInfo
import com.jarvis.telecom.ContactResolver
import com.jarvis.tools.TelephonyTool
import com.jarvis.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CallAndSmsAgentTest {

    private lateinit var contactResolver: ContactResolver
    private lateinit var callAndSmsAgent: CallAndSmsAgent
    private lateinit var telephonyTool: TelephonyTool
    private lateinit var telecomSkill: TelecomSkill
    private lateinit var toolRegistry: ToolRegistry

    private fun createContext(goal: String = ""): SkillContext =
        SkillContext(goal = goal, workingMemory = AgentWorkingMemory(goal = goal))

    @Before
    fun setUp() {
        contactResolver = ContactResolver(null)
        contactResolver.addCachedContact(ContactInfo("c_rahul", "Rahul", "+919876543210", "Mobile"))
        contactResolver.addCachedContact(ContactInfo("c_boss", "Boss", "+919876543212", "Mobile"))

        callAndSmsAgent = CallAndSmsAgent(context = null, contactResolver = contactResolver)
        telephonyTool = TelephonyTool(context = null, contactResolver = contactResolver, callAndSmsAgent = callAndSmsAgent)
        telecomSkill = TelecomSkill()
        toolRegistry = ToolRegistry().apply {
            register(telephonyTool)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Call Session & Announcement
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testIncomingCallRingingSessionAndAnnouncement() {
        var announced = ""
        callAndSmsAgent.onIncomingCallRinging("+919876543210") { announced = it }

        assertNotNull("Session must exist while ringing", callAndSmsAgent.activeCallSession)
        assertEquals("+919876543210", callAndSmsAgent.activeCallSession?.number)
        assertEquals("Rahul", callAndSmsAgent.activeCallSession?.contactName)
        assertTrue(callAndSmsAgent.activeCallSession?.isRinging == true)
        assertTrue("Announcement must mention Rahul", announced.contains("Rahul"))

        // Unknown number
        callAndSmsAgent.onIncomingCallRinging("+910000000000") { announced = it }
        assertTrue(announced.contains("+910000000000"))

        // Call ends
        callAndSmsAgent.onCallEnded()
        assertNull("Session must clear after call ends", callAndSmsAgent.activeCallSession)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Hands-free Answer & Reject (context = null → simulation path)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testHandsFreeAnswerAndReject() {
        callAndSmsAgent.onIncomingCallRinging("+919876543212")

        // Session should know caller name
        val session = callAndSmsAgent.activeCallSession
        assertNotNull(session)
        assertTrue(session!!.contactName.contains("Boss"))

        // Answer
        val answered = callAndSmsAgent.answerCall()
        assertTrue("answerCall() must return true in simulation", answered)
        // isRinging should flip to false
        assertFalse(callAndSmsAgent.activeCallSession?.isRinging ?: true)

        // Reject with auto-SMS
        callAndSmsAgent.onIncomingCallRinging("+919876543212")
        val rejected = callAndSmsAgent.rejectCall(autoReplySms = "In a meeting")
        assertTrue("rejectCall() must return true in simulation", rejected)
        assertNull("Session must clear after rejection", callAndSmsAgent.activeCallSession)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. OTP Extraction & SMS Buffering
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testOtpExtractionAndSmsBuffering() {
        // Pattern 1: keyword-preceding digits
        val bankSms = "Your HDFC Bank OTP is 849201 for INR 500.00 transaction at Amazon. Do not share."
        assertEquals("849201", callAndSmsAgent.extractOtp(bankSms))

        // Pattern 2: digits preceding keyword (G- prefix stripped by pattern 3 if needed)
        val googleSms = "G-492810 is your Google verification code."
        assertNotNull(callAndSmsAgent.extractOtp(googleSms))

        // Pattern 3: standalone 4-6 digit code
        val swiggy = "Use code 9182 to login to your Swiggy account."
        assertEquals("9182", callAndSmsAgent.extractOtp(swiggy))

        // Non-OTP message → null
        val casual = "Hey, are we still meeting for lunch today?"
        assertNull("Non-OTP SMS should return null", callAndSmsAgent.extractOtp(casual))

        // SMS buffering
        callAndSmsAgent.onSmsReceived("+919876543210", bankSms)
        val summary = callAndSmsAgent.formatSmsSummary(limit = 5, otpOnly = true)
        assertTrue("OTP summary must mention 849201", summary.contains("849201"))

        val recent = callAndSmsAgent.readRecentSms(5)
        assertTrue(recent.isNotEmpty())
        assertEquals("Rahul", recent.first().contactName)
        assertEquals("849201", recent.first().otpCode)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. TelephonyTool actions (suspend execute)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testTelephonyToolActions() = runBlocking {
        // Idle → no active call
        val idle = telephonyTool.execute(mapOf("action" to "caller_info"))
        assertTrue(idle.message.contains("no active", ignoreCase = true).or(idle.message.contains("There are no")))

        // Ringing → caller info
        callAndSmsAgent.onIncomingCallRinging("+919876543210")
        val ringing = telephonyTool.execute(mapOf("action" to "caller_info"))
        assertTrue(ringing.message.contains("Rahul"))

        // Answer
        val answered = telephonyTool.execute(mapOf("action" to "answer_call"))
        assertTrue(answered.message.contains("answered", ignoreCase = true))

        // Reject with reason
        callAndSmsAgent.onIncomingCallRinging("+919876543210")
        val rejected = telephonyTool.execute(mapOf("action" to "reject_call", "message" to "busy"))
        assertTrue(rejected.message.contains("rejected", ignoreCase = true))

        // OTP read
        callAndSmsAgent.onSmsReceived("+919876543210", "Your OTP is 736194")
        val otp = telephonyTool.execute(mapOf("action" to "get_otp"))
        assertTrue(otp.message.contains("736194"))

        // SMS read
        val sms = telephonyTool.execute(mapOf("action" to "sms_read"))
        assertTrue(sms.message.contains("Rahul"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. TelecomSkill trigger matching (execute is suspend)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testTelecomSkillTriggersAndProposedAction() = runBlocking {
        val answer = telecomSkill.execute("phone uthao", createContext("phone uthao"))
        assertTrue(answer.handled)
        assertEquals("TELEPHONY_CONTROL", answer.proposedAction?.type)
        assertEquals("answer_call", answer.proposedAction?.params?.get("action"))

        val reject = telecomSkill.execute("call kaat do", createContext("call kaat do"))
        assertTrue(reject.handled)
        assertEquals("TELEPHONY_CONTROL", reject.proposedAction?.type)
        assertEquals("reject_call", reject.proposedAction?.params?.get("action"))

        val who = telecomSkill.execute("kiska call hai", createContext("kiska call hai"))
        assertTrue(who.handled)
        assertEquals("TELEPHONY_CONTROL", who.proposedAction?.type)
        assertEquals("caller_info", who.proposedAction?.params?.get("action"))

        val smsRead = telecomSkill.execute("sms padho", createContext("sms padho"))
        assertTrue(smsRead.handled)
        assertEquals("TELEPHONY_CONTROL", smsRead.proposedAction?.type)
        assertEquals("sms_read", smsRead.proposedAction?.params?.get("action"))

        val otpRead = telecomSkill.execute("what is my otp", createContext("what is my otp"))
        assertTrue(otpRead.handled)
        assertEquals("TELEPHONY_CONTROL", otpRead.proposedAction?.type)
        // otp_only flag should be true
        assertEquals("true", otpRead.proposedAction?.params?.get("otp_only"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. IntentResolver fast-path rules 33-35
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testIntentResolverFastPaths() {
        val a1 = IntentResolver.resolve("answer call")
        assertNotNull(a1); assertEquals(AssistantIntent.TELEPHONY_ANSWER, a1!!.intent)
        assertEquals("TELEPHONY_CONTROL", a1.directPlan?.actions?.firstOrNull()?.type)

        val a2 = IntentResolver.resolve("phone uthao")
        assertNotNull(a2); assertEquals(AssistantIntent.TELEPHONY_ANSWER, a2!!.intent)

        val r1 = IntentResolver.resolve("call kaat do")
        assertNotNull(r1); assertEquals(AssistantIntent.TELEPHONY_REJECT, r1!!.intent)

        val r2 = IntentResolver.resolve("reject call")
        assertNotNull(r2); assertEquals(AssistantIntent.TELEPHONY_REJECT, r2!!.intent)

        val s1 = IntentResolver.resolve("read sms")
        assertNotNull(s1); assertEquals(AssistantIntent.TELEPHONY_SMS_READ, s1!!.intent)

        val s2 = IntentResolver.resolve("what is my otp")
        assertNotNull(s2); assertEquals(AssistantIntent.TELEPHONY_SMS_READ, s2!!.intent)
        assertEquals("get_otp", s2.params["action"])
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. ToolRegistry alias resolution
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun testToolRegistryAliases() {
        listOf(
            "ANSWER_CALL", "PICK_UP_CALL", "ACCEPT_CALL",
            "REJECT_CALL", "DECLINE_CALL", "END_CALL",
            "READ_SMS", "SMS_READ", "GET_OTP", "READ_OTP",
            "WHO_IS_CALLING", "CALLER_INFO"
        ).forEach { alias ->
            val resolved = toolRegistry.resolveCanonicalToolName(alias)
            assertEquals("$alias must resolve to TELEPHONY_CONTROL", "TELEPHONY_CONTROL", resolved)
        }
    }
}
