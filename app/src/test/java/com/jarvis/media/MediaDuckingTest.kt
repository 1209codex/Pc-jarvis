package com.jarvis.media

import org.junit.Assert.*
import org.junit.Test

class MediaDuckingTest {

    @Test
    fun testCalculateTargetVolume() {
        // Normal volume at 10, duck at 40% -> target 4
        assertEquals(4, MediaDuckingManager.calculateTargetVolume(10, 40))

        // Normal volume at 15, duck at 50% -> target 8
        assertEquals(8, MediaDuckingManager.calculateTargetVolume(15, 50))

        // Zero volume remains zero
        assertEquals(0, MediaDuckingManager.calculateTargetVolume(0, 50))

        // Target volume never drops below 1 if original volume was positive
        assertEquals(1, MediaDuckingManager.calculateTargetVolume(1, 10))

        // Clamping percentages (e.g. 5% clamped to min 10%, 95% clamped to max 90%)
        val duckMin = MediaDuckingManager.calculateTargetVolume(10, 5)
        assertEquals(1, duckMin)

        val duckMax = MediaDuckingManager.calculateTargetVolume(10, 99)
        assertEquals(9, duckMax)
    }
}
