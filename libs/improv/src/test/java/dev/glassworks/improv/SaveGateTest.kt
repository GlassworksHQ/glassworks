package dev.glassworks.improv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A failed attempt's credential on a saved network is never saved by a later join. */
class SaveGateTest {

    @Test fun nothingFailedSaves() = assertTrue(SaveGate().maySave(4))

    @Test fun aFailedAttemptOnASavedNetworkStopsTheSaveOfAnotherJoin() {
        val gate = SaveGate()
        gate.failed(2)
        assertFalse(gate.maySave(4))
        assertFalse(gate.maySave(null))
    }

    @Test fun joiningTheSameNetworkLaterConfirmsItsCredential() {
        val gate = SaveGate()
        gate.failed(2)
        assertTrue(gate.maySave(2))
        assertTrue(gate.maySave(4))
    }

    @Test fun anotherNetworkComingUpFirstLeavesTheAttemptsEntryUnsaved() {
        // the attempt set up network 7, but network 3 came up: 7 is untested, so neither this join nor a later one saves
        val gate = SaveGate()
        gate.failed(7)
        assertFalse(gate.maySave(3))
        assertFalse(gate.maySave(null))
        // 7 connecting itself confirms it
        assertTrue(gate.maySave(7))
    }
}
