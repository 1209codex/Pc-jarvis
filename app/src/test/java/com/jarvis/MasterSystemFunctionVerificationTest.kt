package com.jarvis

import com.jarvis.calendar.CalendarManager
import com.jarvis.execution.VerificationStatus
import com.jarvis.interaction.BrowserDomTool
import com.jarvis.interaction.OsSystemInspectionTool
import com.jarvis.memory.MemoryStore
import com.jarvis.security.FakeSecurityAuditor
import com.jarvis.tools.AppAutopilotTool
import com.jarvis.tools.AppsCloseAllTool
import com.jarvis.tools.BatteryStatusTool
import com.jarvis.tools.CalculatorTool
import com.jarvis.tools.CalendarTool
import com.jarvis.tools.CloseAppTool
import com.jarvis.tools.DeviceSettingsTool
import com.jarvis.tools.FileManagerTool
import com.jarvis.tools.FlashlightTool
import com.jarvis.tools.LocationTool
import com.jarvis.tools.MediaPlaybackControlTool
import com.jarvis.tools.MusicPlayTool
import com.jarvis.tools.NoteTool
import com.jarvis.tools.OpenAppTool
import com.jarvis.tools.SearchMemoryTool
import com.jarvis.tools.SecurityAuditorTool
import com.jarvis.tools.SpeakTool
import com.jarvis.tools.ToolResult
import com.jarvis.tools.UiClickTool
import com.jarvis.tools.UiScrollTool
import com.jarvis.tools.UiTypeTool
import com.jarvis.tools.YouTubePlayTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MasterSystemFunctionVerificationTest {

    // =========================================================================
    // 1. CALCULATOR TOOL (Mathematical & Computational Functions)
    // =========================================================================

    @Test
    fun testCalculatorBasicArithmetic() = runBlocking {
        val calc = CalculatorTool(context = null)

        val resAdd = calc.execute(mapOf("expression" to "12 + 8"))
        assertTrue(resAdd.success)
        assertTrue(resAdd.message.contains("20"))

        val resDiv = calc.execute(mapOf("expression" to "100 / 4"))
        assertTrue(resDiv.success)
        assertTrue(resDiv.message.contains("25"))

        val resMul = calc.execute(mapOf("expression" to "5 * 6"))
        assertTrue(resMul.success)
        assertTrue(resMul.message.contains("30"))
    }

    @Test
    fun testCalculatorOperatorPrecedenceAndParentheses() = runBlocking {
        val calc = CalculatorTool(context = null)

        val resNoParens = calc.execute(mapOf("expression" to "2 + 3 * 4"))
        assertTrue(resNoParens.success)
        assertTrue(resNoParens.message.contains("14"))

        val resParens = calc.execute(mapOf("expression" to "(2 + 3) * 4"))
        assertTrue(resParens.success)
        assertTrue(resParens.message.contains("20"))
    }

    @Test
    fun testCalculatorNegativeNumbers() = runBlocking {
        val calc = CalculatorTool(context = null)

        val resNeg1 = calc.execute(mapOf("expression" to "-5 + 3"))
        assertTrue(resNeg1.success)
        assertTrue(resNeg1.message.contains("-2"))

        val resNeg2 = calc.execute(mapOf("expression" to "10 * -2"))
        assertTrue(resNeg2.success)
        assertTrue(resNeg2.message.contains("-20"))
    }

    @Test
    fun testCalculatorDivisionByZeroAndErrors() = runBlocking {
        val calc = CalculatorTool(context = null)

        val resDivZero = calc.execute(mapOf("expression" to "10 / 0"))
        assertFalse("Division by zero must return failure", resDivZero.success)

        val resMissing = calc.execute(emptyMap())
        assertFalse("Missing expression must return failure", resMissing.success)
        assertEquals("Expression missing", resMissing.message)
    }

    // =========================================================================
    // 2. HARDWARE & DEVICE TOOLS (Flashlight, Battery, Settings, Location)
    // =========================================================================

    @Test
    fun testFlashlightToolValidation() = runBlocking {
        val flashlight = FlashlightTool(context = null)

        // Missing parameter
        val resMissing = flashlight.execute(emptyMap())
        assertFalse(resMissing.success)
        assertTrue(resMissing.message.contains("mode=on or mode=off"))

        // Invalid parameter
        val resInvalid = flashlight.execute(mapOf("mode" to "dim"))
        assertFalse(resInvalid.success)
        assertTrue(resInvalid.message.contains("mode=on or mode=off"))

        // Valid mode in test mode
        val resValid = flashlight.execute(mapOf("mode" to "on"))
        assertTrue(resValid.success)
    }

    @Test
    fun testBatteryStatusToolMetadataAndVerification() = runBlocking {
        val battery = BatteryStatusTool(context = null)
        assertEquals("BATTERY_CHECK", battery.name)
        assertTrue(battery.policy.idempotent)

        val res = battery.execute(emptyMap())
        assertTrue(res.success)

        // Test outcome verification contracts
        val successResult = ToolResult.Success("Battery 85%", mapOf("percentage" to 85, "is_charging" to false))
        val verified = battery.verify(emptyMap(), successResult)
        assertEquals(VerificationStatus.VERIFIED, verified.status)

        val failResult = ToolResult.Failed("Battery sensor unavailable")
        val verifiedFail = battery.verify(emptyMap(), failResult)
        assertEquals(VerificationStatus.FAILED, verifiedFail.status)
    }

    @Test
    fun testDeviceSettingsToolWithoutContext() = runBlocking {
        val settings = DeviceSettingsTool(context = null)
        val res = settings.execute(mapOf("action" to "set_volume", "percent" to "70"))
        assertFalse("AudioManager missing in null context", res.success)
        assertTrue(res.message.contains("AudioManager not available"))
    }

    @Test
    fun testLocationToolActionRouting() = runBlocking {
        val location = LocationTool(context = null)
        assertEquals("LOCATION", location.name)

        // Invalid action
        val resInvalid = location.execute(mapOf("action" to "invalid_action"))
        assertFalse(resInvalid.success)
        assertTrue(resInvalid.message.contains("Unknown location action"))

        // SSID with null context returns empty string
        val resSsid = location.execute(mapOf("action" to "ssid"))
        assertTrue(resSsid.success)
    }

    // =========================================================================
    // 3. APP LIFECYCLE TOOLS (OpenApp, CloseApp, AppsCloseAll)
    // =========================================================================

    @Test
    fun testOpenAppToolValidation() = runBlocking {
        val openApp = OpenAppTool(context = null)

        // Missing app name
        val resMissing = openApp.execute(emptyMap())
        assertFalse(resMissing.success)
        assertEquals("App name missing", resMissing.message)

        // App not in allowlist
        val resDisallowed = openApp.execute(mapOf("app" to "malicious_trojan_app"))
        assertFalse(resDisallowed.success)
        assertTrue(resDisallowed.message.contains("not in allowlist"))

        // Allowed app with null context
        val resAllowed = openApp.execute(mapOf("app" to "youtube"))
        assertTrue(resAllowed.success)
        assertTrue(resAllowed.message.contains("Opened youtube successfully"))
    }

    @Test
    fun testCloseAppToolValidation() = runBlocking {
        val closeApp = CloseAppTool(context = null)

        val resMissing = closeApp.execute(emptyMap())
        assertFalse(resMissing.success)
        assertEquals("App name missing", resMissing.message)

        val resDisallowed = closeApp.execute(mapOf("app" to "unknown_evil_process"))
        assertFalse(resDisallowed.success)
        assertTrue(resDisallowed.message.contains("not in allowlist"))

        val resAllowed = closeApp.execute(mapOf("app" to "whatsapp"))
        assertTrue(resAllowed.success)
        assertTrue(resAllowed.message.contains("Closed whatsapp successfully"))
    }

    @Test
    fun testAppsCloseAllToolExecution() = runBlocking {
        val closeAll = AppsCloseAllTool(context = null)
        val res = closeAll.execute(emptyMap())
        assertTrue(res.success)
        assertEquals("All apps closed", res.message)
    }

    // =========================================================================
    // 4. MEDIA & PLAYBACK TOOLS
    // =========================================================================

    @Test
    fun testMediaPlaybackControlActions() = runBlocking {
        val mediaControl = MediaPlaybackControlTool(context = null)

        // Unknown action rejected
        val resUnknown = mediaControl.execute(mapOf("action" to "disintegrate"))
        assertFalse(resUnknown.success)
        assertTrue(resUnknown.message.contains("Unknown media control action"))

        // Valid action with null context fails gracefully with AudioManager missing
        val resValid = mediaControl.execute(mapOf("action" to "next"))
        assertFalse(resValid.success)
        assertTrue(resValid.message.contains("AudioManager not available"))
    }

    @Test
    fun testYouTubePlayToolQueryValidation() = runBlocking {
        val yt = YouTubePlayTool(context = null)

        // Missing query, directUrl, and videoId
        val resMissing = yt.execute(emptyMap())
        assertFalse(resMissing.success)
        assertEquals("Search query or video ID missing", resMissing.message)

        val resValid = yt.execute(mapOf("query" to "Coldplay live"))
        assertTrue(resValid.success)
    }

    @Test
    fun testYouTubeScrollToolDirection() = runBlocking {
        val ytScroll = AppAutopilotTool(context = null)
        val res = ytScroll.execute(mapOf("direction" to "down"))
        assertFalse(res.success)
        // Requires Accessibility service
        assertTrue(res.message.contains("Accessibility Service"))
    }

    @Test
    fun testYMusicPlayToolQueryValidation() = runBlocking {
        val ymusic = MusicPlayTool(context = null)
        val resMissing = ymusic.execute(emptyMap())
        assertFalse(resMissing.success)
        assertEquals("Music query missing", resMissing.message)

        val resValid = ymusic.execute(mapOf("query" to "Interstellar Theme"))
        assertTrue(resValid.success)
    }

    // =========================================================================
    // 5. STORAGE, FILES & WEB LOOKUP TOOLS
    // =========================================================================

    @Test
    fun testFileManagerToolStorageAndSearch() = runBlocking {
        val fm = FileManagerTool(context = null)

        // Storage breakdown action
        val resStorage = fm.execute(mapOf("action" to "storage_breakdown"))
        assertTrue(resStorage.success)
        assertTrue(resStorage.message.contains("Storage Breakdown"))

        // Missing query for file search
        val resSearchMissing = fm.execute(mapOf("action" to "search_file"))
        assertFalse(resSearchMissing.success)
        assertTrue(resSearchMissing.message.contains("Please specify a filename"))
    }

    @Test
    fun testBrowserDomToolValidation() = runBlocking {
        val browser = BrowserDomTool(context = null)

        // Missing URL or Query
        val resMissing = browser.execute(emptyMap())
        assertFalse(resMissing.success)
        assertEquals("URL or Query missing", resMissing.message)

        val resValid = browser.execute(mapOf("url" to "https://google.com"))
        assertTrue(resValid.success)
    }

    @Test
    fun testOsSystemInspectionToolRouting() = runBlocking {
        val osInspect = OsSystemInspectionTool(context = null)
        val res = osInspect.execute(mapOf("target" to "all"))
        assertTrue(res.success)
        assertTrue(res.message.contains("System healthy"))
    }

    // =========================================================================
    // 6. SECURITY & CALENDAR TOOLS
    // =========================================================================

    @Test
    fun testSecurityAuditorToolWithFakeAuditor() = runBlocking {
        val auditor = FakeSecurityAuditor()
        val secTool = SecurityAuditorTool(context = null, auditor = auditor)

        // 1. Audit permissions action
        val resAudit = secTool.execute(mapOf("action" to "audit_permissions"))
        assertTrue(resAudit.success)
        assertTrue(resAudit.message.contains("Device Privacy Audit"))
        assertTrue(resAudit.data.containsKey("score"))

        // 2. Scan camera & mic apps
        val resSensors = secTool.execute(mapOf("action" to "scan_camera_mic_apps"))
        assertTrue(resSensors.success)
        assertTrue(resSensors.message.contains("WhatsApp") || resSensors.message.contains("apps found"))

        // 3. Phishing message analysis
        val resPhish = secTool.execute(mapOf(
            "action" to "analyze_message",
            "message" to "URGENT: Your bank account is locked! Click http://bit.ly/fake-bank to verify your OTP immediately!"
        ))
        assertTrue(resPhish.success)
        assertTrue(resPhish.message.contains("HIGH_RISK") || resPhish.message.contains("SUSPICIOUS"))
    }

    @Test
    fun testCalendarToolOperations() = runBlocking {
        val calManager = CalendarManager(context = null)
        val calTool = CalendarTool(context = null, calendarManager = calManager)

        // 1. Add event
        val resAdd = calTool.execute(mapOf(
            "action" to "add_event",
            "title" to "Design Review",
            "duration" to "30"
        ))
        assertTrue(resAdd.success)
        assertTrue(resAdd.message.contains("Scheduled 'Design Review'"))

        // 2. List agenda
        val resAgenda = calTool.execute(mapOf("action" to "today_agenda"))
        assertTrue(resAgenda.success)
        assertTrue(resAgenda.message.contains("Design Review") || resAgenda.message.contains("agenda"))

        // 3. Missing title
        val resMissing = calTool.execute(mapOf("action" to "add_event", "title" to ""))
        assertFalse(resMissing.success)
    }

    // =========================================================================
    // 7. MEMORY & SPEECH OUTPUT TOOLS
    // =========================================================================

    @Test
    fun testSpeakToolEmptyAndMissingText() = runBlocking {
        val speak = SpeakTool(ttsEngine = null)

        // Missing text
        val resMissing = speak.execute(emptyMap())
        assertFalse(resMissing.success)
        assertEquals("Speech text missing", resMissing.message)

        // Empty text skipped
        val resEmpty = speak.execute(mapOf("text" to "   "))
        assertTrue(resEmpty.success)
        assertEquals("Empty speech text skipped", resEmpty.message)

        val resSpoke = speak.execute(mapOf("text" to "Hello World"))
        assertTrue(resSpoke.success)
    }

    @Test
    fun testNoteToolValidation() = runBlocking {
        val note = NoteTool(memoryStore = null)

        // Missing key
        val resMissingKey = note.execute(mapOf("value" to "data"))
        assertFalse(resMissingKey.success)
        assertEquals("Note key missing", resMissingKey.message)

        // Missing value
        val resMissingVal = note.execute(mapOf("key" to "topic"))
        assertFalse(resMissingVal.success)
        assertEquals("Note value missing", resMissingVal.message)

        // Sensitive terms rejected
        val resSensitive = note.execute(mapOf("key" to "my password", "value" to "secret123"))
        assertFalse(resSensitive.success)
        assertTrue(resSensitive.message.contains("sensitive content"))

        val resValid = note.execute(mapOf("key" to "favorite_color", "value" to "cyan"))
        assertTrue(resValid.success)
    }

    @Test
    fun testSearchMemoryToolMissingQuery() = runBlocking {
        val search = SearchMemoryTool(memoryStore = null)
        val resMissing = search.execute(emptyMap())
        assertFalse(resMissing.success)
        assertEquals("Search query missing", resMissing.message)

        val resFound = search.execute(mapOf("query" to "color"))
        assertTrue(resFound.success)
    }

    // =========================================================================
    // 8. OS ACCESSIBILITY & UI TOOLS
    // =========================================================================

    @Test
    fun testUiClickToolTargetValidation() = runBlocking {
        val clickTool = UiClickTool(context = null)
        val resMissing = clickTool.execute(emptyMap())
        assertFalse(resMissing.success)
        assertEquals("Target element or coordinates required", resMissing.message)

        // With target when accessibility service is not running
        val resNoService = clickTool.execute(mapOf("target" to "Submit"))
        assertFalse(resNoService.success)
        assertTrue(resNoService.message.contains("Accessibility Service is not enabled"))
    }

    @Test
    fun testUiScrollToolValidation() = runBlocking {
        val scrollTool = UiScrollTool(context = null)
        val res = scrollTool.execute(mapOf("direction" to "down"))
        assertFalse(res.success)
        assertTrue(res.message.contains("Accessibility Service is not enabled"))
    }

    @Test
    fun testUiTypeToolValidation() = runBlocking {
        val typeTool = UiTypeTool(context = null)
        val resMissing = typeTool.execute(emptyMap())
        assertFalse(resMissing.success)
        assertEquals("Text parameter required", resMissing.message)
    }
}
