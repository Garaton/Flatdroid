package com.garathon.flatdroid.audio

/** 16-bit PCM arithmetic for the player and the microphone stream. */
object Pcm {
    private const val BYTES_PER_SAMPLE = 2

    /** Whole frames covering [ms] milliseconds of [channels]-channel audio at [rate]. */
    fun bytesFor(rate: Int, channels: Int, ms: Int): Int {
        val frames = (rate.toLong() * ms / 1000).toInt().coerceAtLeast(1)
        return frames * channels * BYTES_PER_SAMPLE
    }

    /** Whether the first [length] bytes of [buffer] are all zero (digital silence). */
    fun isSilent(buffer: ByteArray, length: Int): Boolean {
        for (i in 0 until length) if (buffer[i].toInt() != 0) return false
        return true
    }
}
