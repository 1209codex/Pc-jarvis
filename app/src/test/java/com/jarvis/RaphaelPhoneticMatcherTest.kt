package com.jarvis

import com.jarvis.wakeword.RaphaelPhoneticMatcher
import org.junit.Assert.*
import org.junit.Test

class RaphaelPhoneticMatcherTest {

    @Test
    fun testBlankDetectorPhraseUsesConfiguredWakeWord() {
        assertEquals("Jarvis", RaphaelPhoneticMatcher.resolveDetectedPhrase("", "Jarvis"))
        assertEquals("Friday", RaphaelPhoneticMatcher.resolveDetectedPhrase("  ", "Friday"))
        assertEquals("Computer", RaphaelPhoneticMatcher.resolveDetectedPhrase("Computer", "Jarvis"))
    }

    @Test
    fun testWakeWordVariants() {
        val variants = listOf("jarvis", "jarviz", "jarves", "garvis", "jarviss")
        for (v in variants) {
            assertTrue("Expected '$v' to be recognized as wake word", RaphaelPhoneticMatcher.isWakeWord(v))
        }
    }

    @Test
    fun testNonWakeWords() {
        val nonWakeWords = listOf("apple", "google", "music", "alexa", "siri", "weather")
        for (w in nonWakeWords) {
            assertFalse("Expected '$w' NOT to be wake word", RaphaelPhoneticMatcher.isWakeWord(w))
        }
    }

    @Test
    fun testContainsWakeWordInPhrase() {
        assertTrue(RaphaelPhoneticMatcher.containsWakeWord("Hey Jarvis play music"))
        assertTrue(RaphaelPhoneticMatcher.containsWakeWord("Jarvis search chrome"))
        assertTrue(RaphaelPhoneticMatcher.containsWakeWord("ok jarvis what is the time"))
        assertFalse(RaphaelPhoneticMatcher.containsWakeWord("play music on chrome"))
    }

    @Test
    fun testStripWakeWordPrefixAtStart() {
        val input1 = "Jarvis play believer on ymusic"
        assertEquals("play believer on ymusic", RaphaelPhoneticMatcher.stripWakeWordPrefix(input1))

        val input2 = "Hey Jarvis search python on chrome"
        assertEquals("search python on chrome", RaphaelPhoneticMatcher.stripWakeWordPrefix(input2))
    }

    @Test
    fun testDoNotStripWakeWordInMiddleOfSentence() {
        val input = "play the song Jarvis by artist"
        assertEquals("play the song Jarvis by artist", RaphaelPhoneticMatcher.stripWakeWordPrefix(input))
    }

    @Test
    fun testCustomWakeWordFriday() {
        val custom = "Friday"
        assertTrue(RaphaelPhoneticMatcher.isWakeWord("friday", custom))
        assertTrue(RaphaelPhoneticMatcher.isWakeWord("fryday", custom))
        assertFalse(RaphaelPhoneticMatcher.isWakeWord("jarvis", custom))
        assertFalse(RaphaelPhoneticMatcher.isWakeWord("alexa", custom))

        assertTrue(RaphaelPhoneticMatcher.containsWakeWord("Hey Friday what is the time", custom))
        assertEquals("what is the time", RaphaelPhoneticMatcher.stripWakeWordPrefix("Hey Friday what is the time", custom))
        assertEquals("open youtube", RaphaelPhoneticMatcher.stripWakeWordPrefix("Friday open youtube", custom))
    }

    @Test
    fun testCustomWakeWordComputer() {
        val custom = "Computer"
        assertTrue(RaphaelPhoneticMatcher.isWakeWord("computer", custom))
        assertTrue(RaphaelPhoneticMatcher.containsWakeWord("Ok computer play jazz", custom))
        assertEquals("play jazz", RaphaelPhoneticMatcher.stripWakeWordPrefix("Ok computer play jazz", custom))
    }

    @Test
    fun testDefaultFallbackToJarvisWhenCustomIsNull() {
        assertTrue(RaphaelPhoneticMatcher.isWakeWord("jarvis", null))
        assertTrue(RaphaelPhoneticMatcher.isWakeWord("jarvis", ""))
        assertTrue(RaphaelPhoneticMatcher.isWakeWord("jarvis", "   "))
        assertTrue(RaphaelPhoneticMatcher.isWakeWord("jarvis", "Jarvis"))
    }
}
