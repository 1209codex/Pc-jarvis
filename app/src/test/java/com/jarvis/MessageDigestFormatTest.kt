package com.jarvis

import com.jarvis.tools.MessageDigestFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDigestFormatTest {

    @Test
    fun emptyDigestReturnsNothingMessage() {
        val d = MessageDigestFormat.buildDigest(emptyList(), emptyList())
        assertTrue(d.contains("no unread"))
    }

    @Test
    fun smsOnlyDigest() {
        val d = MessageDigestFormat.buildDigest(listOf("Mummy: Call me."), emptyList())
        assertEquals("SMS:\nMummy: Call me.", d)
    }

    @Test
    fun combinesSmsAndWhatsAppWithSeparators() {
        val d = MessageDigestFormat.buildDigest(
            listOf("Rahul: Tennis at 6"),
            listOf("Dollar: hello!")
        )
        assertTrue(d.contains("SMS:"))
        assertTrue(d.contains("WhatsApp:"))
        assertTrue(d.startsWith("SMS:"))
        assertTrue(d.contains("Dollar: hello!"))
    }

    @Test
    fun whatsAppOnlySkipsSmsHeading() {
        val d = MessageDigestFormat.buildDigest(emptyList(), listOf("A: hi"))
        assertEquals("WhatsApp:\nA: hi", d)
    }

    @Test
    fun truncatesOverlongBodies() {
        val long = "x".repeat(300)
        val line = MessageDigestFormat.smsLine("Sender", long)
        assertTrue(line.length <= 150)
        assertTrue(line.endsWith("…"))
    }
}