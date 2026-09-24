package com.jarvis.voice.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RollingPcmBufferTest {

    @Test
    fun testEmptyBufferReturnsEmpty() {
        val buffer = RollingPcmBuffer(capacitySamples = 100)
        val result = buffer.getRecentAudio(50)
        assertEquals(0, result.size)
    }

    @Test
    fun testWriteAndReadPartialBuffer() {
        val buffer = RollingPcmBuffer(capacitySamples = 10)
        val input = shortArrayOf(1, 2, 3, 4, 5)
        buffer.write(input)

        val result = buffer.getRecentAudio(5)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 5), result)

        val partial = buffer.getRecentAudio(3)
        assertArrayEquals(shortArrayOf(3, 4, 5), partial)
    }

    @Test
    fun testWrapAroundMaintainsChronologicalOrder() {
        val buffer = RollingPcmBuffer(capacitySamples = 5)
        buffer.write(shortArrayOf(1, 2, 3, 4))
        buffer.write(shortArrayOf(5, 6, 7)) // Total 7 written into size 5 -> holds [3, 4, 5, 6, 7]

        val allRecent = buffer.getRecentAudio(5)
        assertArrayEquals(shortArrayOf(3, 4, 5, 6, 7), allRecent)

        val lastTwo = buffer.getRecentAudio(2)
        assertArrayEquals(shortArrayOf(6, 7), lastTwo)
    }

    @Test
    fun testClearResetsState() {
        val buffer = RollingPcmBuffer(capacitySamples = 10)
        buffer.write(shortArrayOf(1, 2, 3))
        buffer.clear()
        assertEquals(0, buffer.getRecentAudio().size)
    }
}
