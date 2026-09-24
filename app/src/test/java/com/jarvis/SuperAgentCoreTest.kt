package com.jarvis

import com.jarvis.agent.AgentWorkingMemory
import com.jarvis.agent.skills.*
import com.jarvis.ai.AssistantIntent
import com.jarvis.ai.IntentResolver
import com.jarvis.calendar.CalendarManager
import com.jarvis.files.FileManager
import com.jarvis.security.SecurityAuditor
import com.jarvis.security.FakeSecurityAuditor
import com.jarvis.telecom.ContactInfo
import com.jarvis.telecom.ContactResolver
import com.jarvis.tools.Tool
import com.jarvis.tools.ToolRegistry
import com.jarvis.tools.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

private class SuperStubTool(
    override val name: String,
    override val description: String = ""
) : Tool {
    override suspend fun execute(params: Map<String, String>): ToolResult = ToolResult(true, "ok")
}

class SuperAgentCoreTest {

    @Test
    fun testToolRegistrySuperAgentAliases() {
        val registry = ToolRegistry()
        registry.register(SuperStubTool("TELEPHONY_CONTROL"))
        registry.register(SuperStubTool("CALENDAR_MANAGE"))
        registry.register(SuperStubTool("SECURITY_AUDIT"))
        registry.register(SuperStubTool("FILE_MANAGER"))

        // Telephony
        assertEquals("TELEPHONY_CONTROL", registry.resolveCanonicalToolName("CALL"))
        assertEquals("TELEPHONY_CONTROL", registry.resolveCanonicalToolName("DIAL"))
        assertEquals("TELEPHONY_CONTROL", registry.resolveCanonicalToolName("PHONE"))
        assertEquals("TELEPHONY_CONTROL", registry.resolveCanonicalToolName("SMS"))
        assertEquals("TELEPHONY_CONTROL", registry.resolveCanonicalToolName("SEND_SMS"))
        assertEquals("TELEPHONY_CONTROL", registry.resolveCanonicalToolName("FIND_CONTACT"))

        // Calendar
        assertEquals("CALENDAR_MANAGE", registry.resolveCanonicalToolName("CALENDAR"))
        assertEquals("CALENDAR_MANAGE", registry.resolveCanonicalToolName("SCHEDULE"))
        assertEquals("CALENDAR_MANAGE", registry.resolveCanonicalToolName("AGENDA"))
        assertEquals("CALENDAR_MANAGE", registry.resolveCanonicalToolName("MEETING"))
        assertEquals("CALENDAR_MANAGE", registry.resolveCanonicalToolName("CHECK_CONFLICT"))

        // Security
        assertEquals("SECURITY_AUDIT", registry.resolveCanonicalToolName("SECURITY"))
        assertEquals("SECURITY_AUDIT", registry.resolveCanonicalToolName("PRIVACY_AUDIT"))
        assertEquals("SECURITY_AUDIT", registry.resolveCanonicalToolName("PERMISSIONS_CHECK"))
        assertEquals("SECURITY_AUDIT", registry.resolveCanonicalToolName("SCAN_SECURITY"))
        assertEquals("SECURITY_AUDIT", registry.resolveCanonicalToolName("PRIVACY_SCORE"))

        // Files
        assertEquals("FILE_MANAGER", registry.resolveCanonicalToolName("FILES"))
        assertEquals("FILE_MANAGER", registry.resolveCanonicalToolName("FILE_SEARCH"))
        assertEquals("FILE_MANAGER", registry.resolveCanonicalToolName("STORAGE_CHECK"))
        assertEquals("FILE_MANAGER", registry.resolveCanonicalToolName("CLEANUP_STORAGE"))
    }

    @Test
    fun testContactResolverDirectAndPhoneticMatching() {
        val resolver = ContactResolver()

        // 1. Direct phone number
        val directNum = resolver.resolveContact("+18005550199")
        assertNotNull(directNum)
        assertEquals("+18005550199", directNum?.phoneNumber)

        // 2. Exact match
        val mom = resolver.resolveContact("Mom")
        assertNotNull(mom)
        assertEquals("+18005550101", mom?.phoneNumber)

        // 3. Custom contact addition
        resolver.addCachedContact(ContactInfo("c_rohan", "Rohan Sharma", "+919876543210"))
        val rohan = resolver.resolveContact("rohan")
        assertNotNull(rohan)
        assertEquals("+919876543210", rohan?.phoneNumber)

        // 4. Fuzzy Levenshtein match ("Rohann" -> "Rohan Sharma")
        val rohann = resolver.resolveContact("rohann")
        assertNotNull(rohann)
        assertEquals("+919876543210", rohann?.phoneNumber)

        // 5. Search contacts
        val search = resolver.searchContacts("Emerg")
        assertTrue(search.any { it.name.contains("Emergency") })
    }

    @Test
    fun testTelecomSkillBilingualQueries() = runBlocking {
        val skill = TelecomSkill()
        val context = SkillContext(goal = "", workingMemory = AgentWorkingMemory(goal = ""))

        // Call English
        assertTrue(skill.canHandle("call mom", context))
        val callMom = skill.execute("call mom", context)
        assertEquals("TELEPHONY_CONTROL", callMom.proposedAction?.type)
        assertEquals("call", callMom.proposedAction?.params?.get("action"))
        assertEquals("mom", callMom.proposedAction?.params?.get("recipient"))

        // Dial number
        assertTrue(skill.canHandle("dial 9876543210", context))
        val dialNum = skill.execute("dial 9876543210", context)
        assertEquals("TELEPHONY_CONTROL", dialNum.proposedAction?.type)
        assertEquals("dial", dialNum.proposedAction?.params?.get("action"))

        // Hindi call
        assertTrue(skill.canHandle("papa ko phone lagao", context))
        val callPapa = skill.execute("papa ko phone lagao", context)
        assertEquals("TELEPHONY_CONTROL", callPapa.proposedAction?.type)
        assertEquals("call", callPapa.proposedAction?.params?.get("action"))

        // SMS Send
        assertTrue(skill.canHandle("send sms to alex saying I am on my way", context))
        val smsAlex = skill.execute("send sms to alex saying I am on my way", context)
        assertEquals("TELEPHONY_CONTROL", smsAlex.proposedAction?.type)
        assertEquals("sms_send", smsAlex.proposedAction?.params?.get("action"))
        assertEquals("alex", smsAlex.proposedAction?.params?.get("recipient"))
        assertEquals("i am on my way", smsAlex.proposedAction?.params?.get("message"))

        val incompleteSms = skill.execute("send message to alex", context)
        assertTrue(incompleteSms.handled)
        assertEquals("Incomplete SMS sends must be clarified, not drafted as if sent", null, incompleteSms.proposedAction)

        // Contact search
        assertTrue(skill.canHandle("search contact priya", context))
        val searchPriya = skill.execute("search contact priya", context)
        assertEquals("TELEPHONY_CONTROL", searchPriya.proposedAction?.type)
        assertEquals("search_contact", searchPriya.proposedAction?.params?.get("action"))
    }

    @Test
    fun testCalendarManagerEventCreationAndConflicts() {
        val manager = CalendarManager()

        // Natural time parsing (tomorrow at 4 PM)
        val timeTomorrow = manager.parseNaturalDateTime("tomorrow at 4 pm")
        val cal = Calendar.getInstance().apply { timeInMillis = timeTomorrow }
        assertEquals(16, cal.get(Calendar.HOUR_OF_DAY))

        // Create an event
        val event = manager.addEvent("Design Review", timeTomorrow, durationMinutes = 60, location = "Room 3B")
        assertEquals("Design Review", event.title)
        assertEquals("Room 3B", event.location)

        // Detect conflict
        val conflicts = manager.checkConflicts(timeTomorrow + 15 * 60_000L, timeTomorrow + 45 * 60_000L)
        assertEquals(1, conflicts.size)
        assertEquals("Design Review", conflicts.first().title)

        // No conflict outside window
        val noConflicts = manager.checkConflicts(timeTomorrow + 120 * 60_000L, timeTomorrow + 180 * 60_000L)
        assertTrue(noConflicts.isEmpty())

        // Today agenda has default sample standup
        val todayAgenda = manager.getTodayAgenda()
        assertNotNull(todayAgenda)
    }

    @Test
    fun testCalendarSkillNaturalLanguageScheduling() = runBlocking {
        val skill = CalendarSkill()
        val context = SkillContext(goal = "", workingMemory = AgentWorkingMemory(goal = ""))

        // Schedule event
        assertTrue(skill.canHandle("schedule meeting with team tomorrow at 3 pm", context))
        val scheduleRes = skill.execute("schedule meeting with team tomorrow at 3 pm", context)
        assertEquals("CALENDAR_MANAGE", scheduleRes.proposedAction?.type)
        assertEquals("add_event", scheduleRes.proposedAction?.params?.get("action"))

        // Hindi schedule
        assertTrue(skill.canHandle("kal subah 10 baje meeting rakho", context))
        val scheduleHin = skill.execute("kal subah 10 baje meeting rakho", context)
        assertEquals("CALENDAR_MANAGE", scheduleHin.proposedAction?.type)
        assertEquals("add_event", scheduleHin.proposedAction?.params?.get("action"))

        // Today agenda
        assertTrue(skill.canHandle("what is my agenda today", context))
        val agendaRes = skill.execute("what is my agenda today", context)
        assertEquals("CALENDAR_MANAGE", agendaRes.proposedAction?.type)
        assertEquals("today_agenda", agendaRes.proposedAction?.params?.get("action"))

        // Check conflict
        assertTrue(skill.canHandle("check schedule conflict for 4 pm", context))
        val conflictRes = skill.execute("check schedule conflict for 4 pm", context)
        assertEquals("CALENDAR_MANAGE", conflictRes.proposedAction?.type)
        assertEquals("check_conflict", conflictRes.proposedAction?.params?.get("action"))
    }

    @Test
    fun testSecurityAuditorPermissionsAndPhishingDetection() {
        // Production auditor with null context returns empty list (no fake data leakage)
        val prodAuditor = SecurityAuditor(context = null)
        assertTrue(prodAuditor.auditInstalledApps().isEmpty())

        // Test auditor with simulated packages
        val auditor = FakeSecurityAuditor()

        // 1. Audit apps
        val audits = auditor.auditInstalledApps()
        assertTrue(audits.isNotEmpty())

        val cameraMic = auditor.getCameraAndMicApps()
        assertTrue(cameraMic.all { it.hasCamera || it.hasMic })

        val privacyScore = auditor.calculatePrivacyScore()
        assertTrue(privacyScore in 40..100)

        // 2. Safe message
        val safeRes = auditor.analyzeMessageSecurity("Hey, see you at lunch tomorrow!")
        assertFalse(safeRes.isSuspicious)
        assertEquals("SAFE", safeRes.threatLevel)

        // 3. Phishing / Scam detection: urgent + OTP
        val scamRes = auditor.analyzeMessageSecurity("URGENT: Your account is blocked. Enter your OTP immediately to verify.")
        assertTrue(scamRes.isSuspicious)
        assertEquals("HIGH_RISK", scamRes.threatLevel)
        assertTrue(scamRes.riskFactors.size >= 2)

        // 4. IP link detection
        val ipLinkRes = auditor.analyzeMessageSecurity("Click here http://192.168.1.1/login to claim prize")
        assertTrue(ipLinkRes.isSuspicious)
        assertTrue(ipLinkRes.riskFactors.any { it.contains("IP address") })
    }

    @Test
    fun testSecuritySkillTriggers() = runBlocking {
        val skill = SecuritySkill()
        val context = SkillContext(goal = "", workingMemory = AgentWorkingMemory(goal = ""))

        // Permission check
        assertTrue(skill.canHandle("check app permissions", context))
        val permRes = skill.execute("check app permissions", context)
        assertEquals("SECURITY_AUDIT", permRes.proposedAction?.type)
        assertEquals("audit_permissions", permRes.proposedAction?.params?.get("action"))

        // Camera/mic access
        assertTrue(skill.canHandle("who has access to my camera", context))
        val camRes = skill.execute("who has access to my camera", context)
        assertEquals("SECURITY_AUDIT", camRes.proposedAction?.type)
        assertEquals("scan_camera_mic_apps", camRes.proposedAction?.params?.get("action"))

        // Phishing message scan
        assertTrue(skill.canHandle("check if this message is a scam: your bank is blocked", context))
        val scamRes = skill.execute("check if this message is a scam: your bank is blocked", context)
        assertEquals("SECURITY_AUDIT", scamRes.proposedAction?.type)
        assertEquals("analyze_message", scamRes.proposedAction?.params?.get("action"))

        // Privacy score
        assertTrue(skill.canHandle("what is my privacy score", context))
        val scoreRes = skill.execute("what is my privacy score", context)
        assertEquals("SECURITY_AUDIT", scoreRes.proposedAction?.type)
        assertEquals("privacy_score", scoreRes.proposedAction?.params?.get("action"))
    }

    @Test
    fun testFileManagerFormattingAndFileSkill() = runBlocking {
        val fileManager = FileManager()

        // Formatting
        assertEquals("500.0 MB", fileManager.formatBytes(500 * 1024 * 1024L))
        assertEquals("2.00 GB", fileManager.formatBytes(2L * 1024 * 1024 * 1024L))

        val skill = FileSkill()
        val context = SkillContext(goal = "", workingMemory = AgentWorkingMemory(goal = ""))

        // Search file
        assertTrue(skill.canHandle("find my downloaded resume pdf", context))
        val searchRes = skill.execute("find my downloaded resume pdf", context)
        assertEquals("FILE_MANAGER", searchRes.proposedAction?.type)
        assertEquals("search_file", searchRes.proposedAction?.params?.get("action"))

        // Storage breakdown
        assertTrue(skill.canHandle("check storage space", context))
        val storageRes = skill.execute("check storage space", context)
        assertEquals("FILE_MANAGER", storageRes.proposedAction?.type)
        assertEquals("storage_breakdown", storageRes.proposedAction?.params?.get("action"))

        // Cleanup
        assertTrue(skill.canHandle("clean storage cleanup suggestions", context))
        val cleanRes = skill.execute("clean storage cleanup suggestions", context)
        assertEquals("FILE_MANAGER", cleanRes.proposedAction?.type)
        assertEquals("cleanup_suggestions", cleanRes.proposedAction?.params?.get("action"))
    }

    @Test
    fun testIntentResolverFastPathForSuperAgent() {
        // Telephony Call
        val callIntent = IntentResolver.resolve("call mom")
        assertNotNull(callIntent)
        assertEquals(AssistantIntent.TELEPHONY_CALL, callIntent?.intent)
        assertEquals("TELEPHONY_CONTROL", callIntent?.directPlan?.actions?.firstOrNull()?.type)
        assertEquals("call", callIntent?.directPlan?.actions?.firstOrNull()?.params?.get("action"))
        assertEquals("mom", callIntent?.directPlan?.actions?.firstOrNull()?.params?.get("recipient"))

        // Telephony Dial
        val dialIntent = IntentResolver.resolve("dial 911")
        assertNotNull(dialIntent)
        assertEquals(AssistantIntent.TELEPHONY_CALL, dialIntent?.intent)
        assertEquals("dial", dialIntent?.directPlan?.actions?.firstOrNull()?.params?.get("action"))
        assertEquals("911", dialIntent?.directPlan?.actions?.firstOrNull()?.params?.get("recipient"))

        // Telephony SMS
        val smsIntent = IntentResolver.resolve("send sms to alex")
        assertNotNull(smsIntent)
        assertEquals(AssistantIntent.TELEPHONY_SMS, smsIntent?.intent)
        assertEquals("TELEPHONY_CONTROL", smsIntent?.directPlan?.actions?.firstOrNull()?.type)
        assertEquals("alex", smsIntent?.directPlan?.actions?.firstOrNull()?.params?.get("recipient"))

        // Calendar Agenda
        val agendaIntent = IntentResolver.resolve("today's agenda")
        assertNotNull(agendaIntent)
        assertEquals(AssistantIntent.CALENDAR_AGENDA, agendaIntent?.intent)
        assertEquals("CALENDAR_MANAGE", agendaIntent?.directPlan?.actions?.firstOrNull()?.type)
        assertEquals("today_agenda", agendaIntent?.directPlan?.actions?.firstOrNull()?.params?.get("action"))

        // Security Audit
        val secIntent = IntentResolver.resolve("check app permissions")
        assertNotNull(secIntent)
        assertEquals(AssistantIntent.SECURITY_AUDIT, secIntent?.intent)
        assertEquals("SECURITY_AUDIT", secIntent?.directPlan?.actions?.firstOrNull()?.type)

        // File Search
        val fileIntent = IntentResolver.resolve("find file document.pdf")
        assertNotNull(fileIntent)
        assertEquals(AssistantIntent.FILE_SEARCH, fileIntent?.intent)
        assertEquals("FILE_MANAGER", fileIntent?.directPlan?.actions?.firstOrNull()?.type)
        assertEquals("document.pdf", fileIntent?.directPlan?.actions?.firstOrNull()?.params?.get("query"))
    }
}
