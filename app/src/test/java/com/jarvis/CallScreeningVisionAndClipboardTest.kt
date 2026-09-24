package com.jarvis

import com.jarvis.ai.AssistantIntent
import com.jarvis.ai.IntentResolver
import com.jarvis.bluetooth.BluetoothHeadsetManager
import com.jarvis.clipboard.ClipboardIntelligenceEngine
import com.jarvis.earbuds.EarbudsAssistantEngine
import com.jarvis.tools.ScreenVisionTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CallScreeningVisionAndClipboardTest {

    @Test
    fun testCallScreeningVoiceResponseParsing() {
        val btMgr = BluetoothHeadsetManager(null, null)
        val engine = EarbudsAssistantEngine(
            context = object : android.content.ContextWrapper(null) {},
            bluetoothHeadsetManager = btMgr,
            voiceEngine = null
        )

        // Test Answer responses
        assertTrue(engine.isAnswerResponse("answer"))
        assertTrue(engine.isAnswerResponse("pick up the call"))
        assertTrue(engine.isAnswerResponse("pickup"))
        assertTrue(engine.isAnswerResponse("phone uthao"))
        assertTrue(engine.isAnswerResponse("haan uthao"))
        assertTrue(engine.isAnswerResponse("accept"))

        // Test Reject responses
        assertTrue(engine.isRejectResponse("reject"))
        assertTrue(engine.isRejectResponse("decline call"))
        assertTrue(engine.isRejectResponse("cut the call"))
        assertTrue(engine.isRejectResponse("kat do"))
        assertTrue(engine.isRejectResponse("hang up"))
        assertTrue(engine.isRejectResponse("no"))

        // Test Reject with SMS
        assertTrue(engine.isRejectWithSms("send busy sms"))
        assertTrue(engine.isRejectWithSms("reject with busy message"))
        assertTrue(engine.isRejectWithSms("say I am in a meeting"))
        assertTrue(engine.isRejectWithSms("reject and tell I am driving"))
        assertTrue(engine.isRejectWithSms("busy bol do"))

        // Test custom SMS message templates
        val drivingMsg = engine.extractCustomSmsMessage("reject and say I am driving")
        assertTrue(drivingMsg.contains("driving", ignoreCase = true))

        val meetingMsg = engine.extractCustomSmsMessage("send meeting sms")
        assertTrue(meetingMsg.contains("meeting", ignoreCase = true))

        val defaultBusyMsg = engine.extractCustomSmsMessage("busy bol do")
        assertTrue(defaultBusyMsg.contains("busy", ignoreCase = true))
    }

    @Test
    fun testIntentModelScreenVisionAndTelephonyClassification() {
        // Screen Vision Intents & Modes
        val explainVision = IntentResolver.resolve("look at my screen and explain this")
        assertNotNull(explainVision)
        assertEquals(AssistantIntent.SCREEN_VISION, explainVision?.intent)
        assertEquals("explain", explainVision?.params?.get("mode"))

        val summarizeVision = IntentResolver.resolve("summarize this page on my screen")
        assertNotNull(summarizeVision)
        assertEquals(AssistantIntent.SCREEN_VISION, summarizeVision?.intent)
        assertEquals("summarize", summarizeVision?.params?.get("mode"))

        val errorVision = IntentResolver.resolve("read error on screen")
        assertNotNull(errorVision)
        assertEquals(AssistantIntent.SCREEN_VISION, errorVision?.intent)
        assertEquals("error_check", errorVision?.params?.get("mode"))

        val extractVision = IntentResolver.resolve("extract otp code from screen")
        assertNotNull(extractVision)
        assertEquals(AssistantIntent.SCREEN_VISION, extractVision?.intent)
        assertEquals("extract", extractVision?.params?.get("mode"))

        val hinglishVision = IntentResolver.resolve("ye screen dekho padh ke batao")
        assertNotNull(hinglishVision)
        assertEquals(AssistantIntent.SCREEN_VISION, hinglishVision?.intent)

        // Telephony Intents
        val callerInfo = IntentResolver.resolve("who is calling me")
        assertNotNull(callerInfo)
        assertEquals(AssistantIntent.TELEPHONY_CALLER_INFO, callerInfo?.intent)
        assertEquals("caller_info", callerInfo?.params?.get("action"))

        val answerCall = IntentResolver.resolve("pick up the call")
        assertNotNull(answerCall)
        assertEquals(AssistantIntent.TELEPHONY_ANSWER, answerCall?.intent)
        assertEquals("answer_call", answerCall?.params?.get("action"))

        val rejectCall = IntentResolver.resolve("decline call")
        assertNotNull(rejectCall)
        assertEquals(AssistantIntent.TELEPHONY_REJECT, rejectCall?.intent)
        assertEquals("reject_call", rejectCall?.params?.get("action"))

        val rejectWithSms = IntentResolver.resolve("reject with busy message")
        assertNotNull(rejectWithSms)
        assertEquals(AssistantIntent.TELEPHONY_REJECT, rejectWithSms?.intent)
        assertEquals("reject_call", rejectWithSms?.params?.get("action"))
        assertNotNull(rejectWithSms?.params?.get("message"))
    }

    @Test
    fun testClipboardIntelligenceClassification() {
        val engine = ClipboardIntelligenceEngine(
            context = object : android.content.ContextWrapper(null) {},
            bluetoothHeadsetManager = null,
            ttsEngine = null
        )

        // 1. YouTube link
        val ytResult = engine.analyzeText("Check this out https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertNotNull(ytResult)
        assertEquals("YOUTUBE_URL", ytResult?.type)
        assertEquals("play_youtube", ytResult?.suggestedAction)

        // 2. Spotify link
        val spotifyResult = engine.analyzeText("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT")
        assertNotNull(spotifyResult)
        assertEquals("SPOTIFY_URL", spotifyResult?.type)
        assertEquals("play_spotify", spotifyResult?.suggestedAction)

        // 3. Web URL
        val webResult = engine.analyzeText("https://developer.android.com/reference")
        assertNotNull(webResult)
        assertEquals("WEB_URL", webResult?.type)
        assertEquals("open_browser", webResult?.suggestedAction)

        // 4. OTP / Verification code
        val otpResult = engine.analyzeText("Your verification code is 849201 for login")
        assertNotNull(otpResult)
        assertEquals("OTP", otpResult?.type)
        assertEquals("849201", otpResult?.extractedValue)
        assertEquals("copy_otp", otpResult?.suggestedAction)

        val standaloneOtp = engine.analyzeText("592014")
        assertNotNull(standaloneOtp)
        assertEquals("OTP", standaloneOtp?.type)
        assertEquals("592014", standaloneOtp?.extractedValue)

        // 5. Phone number
        val phoneResult = engine.analyzeText("+919876543210")
        assertNotNull(phoneResult)
        assertEquals("PHONE_NUMBER", phoneResult?.type)
        assertEquals("+919876543210", phoneResult?.extractedValue)
        assertEquals("dial_or_message", phoneResult?.suggestedAction)

        // 6. UPS Tracking number
        val upsResult = engine.analyzeText("Tracking: 1Z9999999999999999")
        assertNotNull(upsResult)
        assertEquals("TRACKING_NUMBER", upsResult?.type)
        assertEquals("1Z9999999999999999", upsResult?.extractedValue)
        assertEquals("track_package", upsResult?.suggestedAction)

        // 7. Physical Address
        val addressResult = engine.analyzeText("Flat 402, Lotus Apartment, MG Road, Sector 14, Pincode 122001")
        assertNotNull(addressResult)
        assertEquals("ADDRESS", addressResult?.type)
        assertEquals("navigate_maps", addressResult?.suggestedAction)

        // 8. Sensitive text should be suppressed
        val sensitiveResult1 = engine.analyzeText("password=SuperSecretPassword123!")
        assertNull(sensitiveResult1)

        val sensitiveResult2 = engine.analyzeText("Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9")
        assertNull(sensitiveResult2)
    }

    @Test
    fun testScreenVisionToolExecution() = runBlocking {
        val tool = ScreenVisionTool(context = null, llmClient = null)
        val result = tool.execute(mapOf("query" to "check error", "mode" to "error_check"))
        assertTrue(result is com.jarvis.tools.ToolResult.Success)
        val success = result as com.jarvis.tools.ToolResult.Success
        assertNotNull(success.message)
        assertEquals("error_check", success.data["mode"])
    }
}
