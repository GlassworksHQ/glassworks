package dev.glassworks.assist

import org.junit.Assert.assertEquals
import org.junit.Test

/** #691: the timers hear one "voice is on screen" from both Assist looks, true while either shows. */
class VoiceShowingTest {
    @Test fun aSwitchOfLookMidRunKeepsItShowing() {
        val heard = ArrayList<Boolean>()
        val s = VoiceShowing { heard += it }
        s.glow(true)
        s.orb(true) // the look changed mid-run: the orb comes
        s.glow(false) // and the glow goes
        assertEquals(listOf(true, true, true), heard)
        s.orb(false)
        assertEquals(false, heard.last())
    }
}
