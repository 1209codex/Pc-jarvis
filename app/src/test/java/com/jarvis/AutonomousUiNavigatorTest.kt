package com.jarvis

import com.jarvis.accessibility.AutonomousUiNavigator
import org.junit.Assert.*
import org.junit.Test

class AutonomousUiNavigatorTest {

    @Test
    fun testStringSimilarityIdentical() {
        val score = AutonomousUiNavigator.calculateSimilarity("Submit Order", "submit order")
        assertEquals(1.0f, score, 0.001f)
    }

    @Test
    fun testStringSimilarityTokenOverlap() {
        val score = AutonomousUiNavigator.calculateSimilarity("Send message to John", "send")
        assertTrue(score > 0.4f)
    }

    @Test
    fun testStringSimilarityCompletelyDifferent() {
        val score = AutonomousUiNavigator.calculateSimilarity("Settings", "Bluetooth")
        assertTrue(score < 0.3f)
    }

    @Test
    fun testPopupDismissTargetsContainsStandardKeywords() {
        val targets = AutonomousUiNavigator.POPUP_DISMISS_TARGETS
        assertTrue(targets.contains("dismiss"))
        assertTrue(targets.contains("close"))
        assertTrue(targets.contains("later"))
        assertTrue(targets.contains("cancel"))
        assertTrue(targets.contains("got it"))
        assertTrue(targets.contains("allow"))
    }
}
