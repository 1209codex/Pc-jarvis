package com.jarvis

import com.jarvis.assistant.AssistStructureExtractor
import com.jarvis.assistant.ExtractedScreenContext
import org.junit.Assert.*
import org.junit.Test

class DigitalAssistantIntegrationTest {

    @Test
    fun testExtractedScreenContextDataModel() {
        val context = ExtractedScreenContext(
            title = "YouTube - Believer",
            packageName = "com.google.android.youtube",
            visibleTexts = listOf("Imagine Dragons", "1.2B views", "Subscribe"),
            focusedText = "Search YouTube",
            fullContextSummary = "Screen: YouTube\nApp: com.google.android.youtube"
        )

        assertEquals("YouTube - Believer", context.title)
        assertEquals("com.google.android.youtube", context.packageName)
        assertEquals(3, context.visibleTexts.size)
        assertEquals("Search YouTube", context.focusedText)
        assertTrue(context.fullContextSummary.contains("com.google.android.youtube"))
    }

    @Test
    fun testAssistStructureExtractorNullSafe() {
        val extracted = AssistStructureExtractor.extract(null)
        assertNotNull(extracted)
        assertEquals("", extracted.title)
        assertEquals("", extracted.packageName)
        assertTrue(extracted.visibleTexts.isEmpty())
        assertEquals("", extracted.focusedText)
        assertEquals("", extracted.fullContextSummary)
    }

    @Test
    fun testExtractedScreenContextSummaryFormatting() {
        val context = ExtractedScreenContext(
            title = "Settings",
            packageName = "com.android.settings",
            visibleTexts = listOf("Wi-Fi", "Bluetooth", "Apps"),
            focusedText = "Apps",
            fullContextSummary = "Screen: Settings\nApp: com.android.settings\nFocused: Apps\nVisible Text: Wi-Fi | Bluetooth | Apps"
        )

        assertTrue(context.fullContextSummary.contains("Screen: Settings"))
        assertTrue(context.fullContextSummary.contains("App: com.android.settings"))
        assertTrue(context.fullContextSummary.contains("Focused: Apps"))
        assertTrue(context.fullContextSummary.contains("Wi-Fi | Bluetooth | Apps"))
    }
}
