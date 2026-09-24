package com.jarvis.voice

import kotlin.math.sqrt

/**
 * Calculates Root Mean Square (RMS) energy level of a 16-bit PCM audio frame.
 */
fun ShortArray.rms(): Float {
    if (isEmpty()) return 0f
    var sum = 0.0
    for (i in indices) {
        val s = this[i].toDouble()
        sum += s * s
    }
    return sqrt(sum / size).toFloat()
}
