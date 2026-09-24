package com.jarvis.voice.audio

/**
 * Thread-safe circular PCM ring buffer.
 * Retains 1-2 seconds of trailing audio (pre-roll) so that when a wake word
 * is detected, any speech spoken in the same breath (e.g., "Jarvis open YouTube")
 * is preserved and immediately passed to ASR without dropping syllables.
 */
class RollingPcmBuffer(
    val capacitySamples: Int = 16000 * 2 // 2.0 seconds at 16kHz
) {
    private val buffer = ShortArray(capacitySamples)
    private var writeHead = 0
    private var isFull = false
    private val lock = Any()

    /**
     * Writes new audio frames into the ring buffer.
     */
    fun write(samples: ShortArray, offset: Int = 0, length: Int = samples.size) {
        if (length <= 0) return
        synchronized(lock) {
            var srcIdx = offset
            var remaining = length
            while (remaining > 0) {
                val chunkSize = minOf(remaining, capacitySamples - writeHead)
                System.arraycopy(samples, srcIdx, buffer, writeHead, chunkSize)
                writeHead += chunkSize
                if (writeHead >= capacitySamples) {
                    writeHead = 0
                    isFull = true
                }
                srcIdx += chunkSize
                remaining -= chunkSize
            }
        }
    }

    /**
     * Reads up to [requestedSamples] of the most recent pre-roll audio.
     * Guaranteed to return chronological samples ending with the latest written audio frame.
     */
    fun getRecentAudio(requestedSamples: Int = capacitySamples): ShortArray {
        synchronized(lock) {
            val available = if (isFull) capacitySamples else writeHead
            if (available == 0) return ShortArray(0)

            val count = minOf(requestedSamples, available)
            val output = ShortArray(count)

            val startPos = if (isFull) {
                (writeHead - count + capacitySamples) % capacitySamples
            } else {
                writeHead - count
            }

            if (startPos + count <= capacitySamples) {
                System.arraycopy(buffer, startPos, output, 0, count)
            } else {
                val firstChunk = capacitySamples - startPos
                val secondChunk = count - firstChunk
                System.arraycopy(buffer, startPos, output, 0, firstChunk)
                System.arraycopy(buffer, 0, output, firstChunk, secondChunk)
            }
            return output
        }
    }

    /**
     * Resets the buffer state.
     */
    fun clear() {
        synchronized(lock) {
            writeHead = 0
            isFull = false
        }
    }
}
