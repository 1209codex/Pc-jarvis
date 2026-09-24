package com.jarvis

import com.jarvis.wakeword.OwnerVoiceProfile
import com.jarvis.wakeword.TfliteOwnerVoiceVerifier
import java.nio.ByteBuffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerVoiceProfileTest {
    @Test fun speakerOutputMapUsesTensorOrdinals() {
        val vector = ByteBuffer.allocateDirect(4)
        val states = List(88) { ByteBuffer.allocateDirect(4) }

        val outputs = TfliteOwnerVoiceVerifier.speakerOutputMap(vector, states)

        assertEquals((0..88).toSet(), outputs.keys)
        assertSame(vector, outputs[0])
        assertSame(states.last(), outputs[88])
    }

    @Test fun vadNormalizationRepeatsEachStatisticAcrossFourStackedFrames() {
        val mean = FloatArray(128) { it.toFloat() }
        val stddev = FloatArray(128) { (it + 1).toFloat() }

        assertEquals(10f, TfliteOwnerVoiceVerifier.normalizeVadValue(10f, 0, mean, stddev), 0f)
        assertEquals(10f, TfliteOwnerVoiceVerifier.normalizeVadValue(10f, 3, mean, stddev), 0f)
        assertEquals(4.5f, TfliteOwnerVoiceVerifier.normalizeVadValue(10f, 4, mean, stddev), 0f)
        assertEquals(4.5f, TfliteOwnerVoiceVerifier.normalizeVadValue(10f, 7, mean, stddev), 0f)
        assertEquals(-0.9140625f, TfliteOwnerVoiceVerifier.normalizeVadValue(10f, 511, mean, stddev), 0f)
    }

    @Test fun speakerSimilarityDecisionRejectsMissingUncertainAndInvalidScores() {
        assertTrue(TfliteOwnerVoiceVerifier.accepts(0.86f, 0.80f))
        assertFalse(TfliteOwnerVoiceVerifier.accepts(0.79f, 0.80f))
        assertFalse(TfliteOwnerVoiceVerifier.accepts(null, 0.80f))
        assertFalse(TfliteOwnerVoiceVerifier.accepts(Float.NaN, 0.80f))
        assertFalse(TfliteOwnerVoiceVerifier.accepts(Float.POSITIVE_INFINITY, 0.80f))
        assertTrue(TfliteOwnerVoiceVerifier.shouldWake(0.9f, 0.82f, 0.86f, 0.80f, true, true))
        assertFalse(TfliteOwnerVoiceVerifier.shouldWake(0.9f, 0.82f, 0.86f, 0.80f, false, true))
        assertFalse(TfliteOwnerVoiceVerifier.shouldWake(0.9f, 0.82f, 0.86f, 0.80f, true, false))
        assertFalse(TfliteOwnerVoiceVerifier.shouldWake(0.7f, 0.82f, 0.95f, 0.80f, true, true))
        assertFalse(TfliteOwnerVoiceVerifier.shouldWake(0.9f, 0.82f, 0.7f, 0.80f, true, true))
    }

    @Test fun normalizedEnrollmentTemplateMatchesOnlyLikeEmbeddingsAndFailsClosed() {
        val first = FloatArray(256).also { it[0] = 1f }
        val near = FloatArray(256).also { it[0] = 0.98f; it[1] = 0.2f }
        val profile = OwnerVoiceProfile.fromEmbeddings(listOf(first, near), 0.80f)
        assertTrue(profile.matches(FloatArray(256).also { it[0] = 0.99f; it[1] = 0.1f }))
        assertFalse(profile.matches(FloatArray(256).also { it[1] = 1f }))
        assertFalse(profile.matches(FloatArray(256).also { it[0] = Float.NaN }))
        assertFalse(profile.matches(floatArrayOf(1f)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyEnrollmentIsRejected() { OwnerVoiceProfile.fromEmbeddings(emptyList(), 0.80f) }
}
