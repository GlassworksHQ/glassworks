package dev.glassworks.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** [MicLevel], what [VoiceInput.onMicLevel] reports (moved from the pane's VoiceUiTest, #152). */
class MicLevelTest {
    private fun rmsAt(db: Double) = 32768.0 * 10.0.pow(db / 20)

    @Test
    fun levelMapsMinus55To0AndMinus15To1() {
        assertEquals(0f, MicLevel.fromRms(0.0), 0f)
        assertEquals(0f, MicLevel.fromRms(rmsAt(-70.0)), 0f)
        assertEquals(0f, MicLevel.fromRms(rmsAt(-55.0)), 1e-4f)
        assertEquals(0.5f, MicLevel.fromRms(rmsAt(-35.0)), 1e-4f)
        assertEquals(1f, MicLevel.fromRms(rmsAt(-15.0)), 1e-4f)
        assertEquals(1f, MicLevel.fromRms(32768.0), 0f)
    }

    @Test
    fun rmsOfASine() {
        val amp = 10_000.0
        val frame = ShortArray(1280) { (amp * sin(2 * PI * 440 * it / 16_000.0)).toInt().toShort() }
        assertEquals(amp / sqrt(2.0), MicLevel.rms(frame), amp * 0.01)
        assertEquals(0.0, MicLevel.rms(ShortArray(0)), 0.0)
    }
}
