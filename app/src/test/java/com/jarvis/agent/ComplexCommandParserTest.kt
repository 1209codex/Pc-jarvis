package com.jarvis.agent

import org.junit.Assert.*
import org.junit.Test

class ComplexCommandParserTest {

    @Test
    fun testLongCommandParsingMoreThanTenWords() {
        val longUtterance = "Please check battery level, if battery is low then turn on battery saver and play relaxing lo-fi beats on spotify"
        val parsed = ComplexCommandParser.parse(longUtterance)

        assertTrue(parsed.isLongCommand)
        assertTrue(parsed.wordCount >= 10)
        assertTrue(parsed.hasConditionals)
        assertTrue(parsed.segments.isNotEmpty())

        val condSeg = parsed.segments.find { it.condition != null }
        assertNotNull(condSeg)
        assertEquals("battery", condSeg?.condition?.variable)
        assertEquals(ConditionOperator.IS_LOW, condSeg?.condition?.operator)
    }

    @Test
    fun testHinglishCompoundCommand() {
        val utterance = "torch on karo aur uske baad spotify kholo aur kesariya gana bajao"
        val parsed = ComplexCommandParser.parse(utterance)

        assertTrue(parsed.segments.size >= 2)
        val flashSegment = parsed.segments.find { it.text.contains("torch") || it.actionTypeGuess == "FLASHLIGHT" }
        assertNotNull(flashSegment)
    }

    @Test
    fun testConditionalIfThenElseParsing() {
        val utterance = "if wifi is off then turn on wifi else open youtube"
        val parsed = ComplexCommandParser.parse(utterance)

        assertTrue(parsed.hasConditionals)
        val condSeg = parsed.segments.find { it.condition != null }
        assertNotNull(condSeg)
        assertEquals("wifi", condSeg?.condition?.variable)
        assertEquals(ConditionOperator.IS_OFF, condSeg?.condition?.operator)

        val elseSeg = parsed.segments.find { it.isElseBranch }
        assertNotNull(elseSeg)
        assertTrue(elseSeg!!.text.contains("youtube"))
    }

    @Test
    fun testCoreferenceAndEntityPropagation() {
        val utterance = "open camera then take a photo with it and send it to Rahul on whatsapp"
        val parsed = ComplexCommandParser.parse(utterance)

        assertTrue(parsed.segments.size >= 2)
        val whatsappSeg = parsed.segments.find { it.text.contains("whatsapp") }
        assertNotNull(whatsappSeg)
        assertEquals("Rahul", whatsappSeg?.entityRefs?.get("contact"))
        assertEquals("photo", whatsappSeg?.entityRefs?.get("target_coreference"))
    }

    @Test
    fun testSkillTeachingIntentDetection() {
        val utterance = "teach jarvis a new skill called Morning Routine to turn off alarm and play news"
        val parsed = ComplexCommandParser.parse(utterance)

        assertTrue(parsed.isSkillTeachingIntent)
        assertNotNull(parsed.proposedSkillName)
        assertTrue(parsed.proposedSkillName!!.contains("Morning Routine", ignoreCase = true))
    }

    @Test
    fun testDoNotSplitDescriptiveNounPhrases() {
        val utterance = "Ek website bnao jismein maine YouTube ki trending song ko dekh sakun aur uske views aur likes bhi ho"
        val parsed = ComplexCommandParser.parse(utterance)

        assertEquals(1, parsed.segments.size)
        assertEquals(utterance, parsed.segments[0].text)
    }
}
