package com.jarvis.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousUiNavigatorTest {

    @Test
    fun testCalculateSimilarity_exactMatch() {
        val score = AutonomousUiNavigator.calculateSimilarity("Search", "Search")
        assertEquals(1.0f, score, 0.01f)
    }

    @Test
    fun testCalculateSimilarity_caseInsensitive() {
        val score = AutonomousUiNavigator.calculateSimilarity("Submit Order", "submit order")
        assertEquals(1.0f, score, 0.01f)
    }

    @Test
    fun testCalculateSimilarity_tokenOverlap() {
        val score = AutonomousUiNavigator.calculateSimilarity("Click on the blue submit button", "submit button")
        assertTrue("Expected similarity > 0.5f, got $score", score > 0.5f)
    }

    @Test
    fun testCalculateSimilarity_typoTolerance() {
        val score = AutonomousUiNavigator.calculateSimilarity("Setting", "Settings")
        assertTrue("Expected similarity > 0.8f, got $score", score > 0.8f)
    }

    @Test
    fun testPopupDismissTargetsList() {
        assertTrue(AutonomousUiNavigator.POPUP_DISMISS_TARGETS.contains("dismiss"))
        assertTrue(AutonomousUiNavigator.POPUP_DISMISS_TARGETS.contains("close"))
        assertTrue(AutonomousUiNavigator.POPUP_DISMISS_TARGETS.contains("skip"))
        assertTrue(AutonomousUiNavigator.POPUP_DISMISS_TARGETS.contains("cancel"))
    }
}
