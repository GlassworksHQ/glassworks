package dev.glassworks.voice

import kotlin.math.log10
import kotlin.math.sqrt

/** A microphone frame's level for [VoiceInput.onMicLevel]. Pure. */
internal object MicLevel {
    /** RMS of s16 samples, on the 0..32768 scale. */
    fun rms(frame: ShortArray): Double {
        if (frame.isEmpty()) return 0.0
        var acc = 0.0
        for (v in frame) acc += v.toDouble() * v
        return sqrt(acc / frame.size)
    }

    /** `db = 20·log10(rms/32768)`, then −55 dBFS → 0 and −15 dBFS → 1, clamped. */
    fun fromRms(rms: Double): Float {
        if (rms <= 0.0) return 0f
        val db = 20 * log10(rms / 32768.0)
        return ((db + 55) / 40).toFloat().coerceIn(0f, 1f)
    }
}
