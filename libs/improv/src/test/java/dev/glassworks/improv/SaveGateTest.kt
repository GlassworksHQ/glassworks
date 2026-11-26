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

    @Test fun aNewEntryRemovedCleanlyLeavesSavingOn() {
        val gate = SaveGate()
        gate.removed(3, ok = true)
        assertTrue(gate.maySave(4))
    }

    @Test fun aNewEntryThatWouldNotGoStopsTheSaveUntilItIsJoined() {
        val gate = SaveGate()
        gate.removed(3, ok = false)
        assertFalse(gate.maySave(4))
        assertTrue(gate.maySave(3))
    }

    @Test fun joiningTheSameNetworkLaterConfirmsItsCredential() {
        val gate = SaveGate()
        gate.failed(2)
        assertTrue(gate.maySave(2))
        assertTrue(gate.maySave(4))
    }
}
