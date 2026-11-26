package dev.glassworks.sendspin

import org.junit.Assert.assertEquals
import org.junit.Test

/** The output's gain: the player's volume on the spec's curve, under the host's master volume. */
class GainTest {
    @Test fun theVolumeIsOnTheSpecsCurve() {
        assertEquals(1f, gain(100, muted = false, ducked = false, outputGain = 1f), 0f)
        assertEquals(0.125f, gain(25, muted = false, ducked = false, outputGain = 1f), 0.0001f)
    }

    @Test fun theHostsGainScalesIt() {
        assertEquals(0.0625f, gain(25, muted = false, ducked = false, outputGain = 0.5f), 0.0001f)
        assertEquals("out of range is clamped", 1f, gain(100, muted = false, ducked = false, outputGain = 3f), 0f)
    }

    @Test fun mutedOrDuckedIsSilent() {
        assertEquals(0f, gain(100, muted = true, ducked = false, outputGain = 1f), 0f)
        assertEquals(0f, gain(100, muted = false, ducked = true, outputGain = 1f), 0f)
    }
}
