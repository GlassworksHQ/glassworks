package dev.glassworks.improv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An untested credential is never saved: not while its attempt runs, not after it failed or another network came up. */
class SaveGateTest {

    /** The app's preferences, in memory: what outlives a provisioner and the app's process, but not a reboot. */
    private class MemStore : SaveGate.Store {
        var kept: Pair<String, Set<Int>>? = null
        override fun read() = kept
        override fun write(bootId: String, ids: Set<Int>) { kept = bootId to ids }
    }

    private fun SaveGate() = SaveGate(MemStore()) { "boot-1" }

    @Test fun aNewProvisionerInTheSameBootStillHoldsAnUnconfirmedEntry() {
        val store = MemStore()
        SaveGate(store) { "boot-1" }.unconfirmed(6)
        // ImprovController restarted, or the app's process did: a new gate reads what the old one kept
        val again = SaveGate(store) { "boot-1" }
        assertFalse(again.maySave(4))
        assertTrue(again.maySave(6))
    }

    @Test fun aRebootForgetsThem() {
        // the restart put every saved credential back, so nothing untested is left to keep out of a save
        val store = MemStore()
        SaveGate(store) { "boot-1" }.unconfirmed(6)
        assertTrue(SaveGate(store) { "boot-2" }.maySave(4))
    }

    @Test fun nothingUnconfirmedSaves() = assertTrue(SaveGate().maySave(4))

    @Test fun anAttemptStillRunningHoldsEverySave() {
        // attempt C set up 9 and is still undecided while B's network 4 connects
        val gate = SaveGate()
        gate.unconfirmed(9)
        assertFalse(gate.maySave(4))
    }

    @Test fun aFailedAttemptOnASavedNetworkStopsTheSaveOfAnotherJoin() {
        val gate = SaveGate()
        gate.unconfirmed(2)
        assertFalse(gate.maySave(4))
        assertFalse(gate.maySave(null))
    }

    @Test fun joiningTheSameNetworkLaterConfirmsItsCredential() {
        val gate = SaveGate()
        gate.unconfirmed(2)
        assertTrue(gate.maySave(2))
        assertTrue(gate.maySave(4))
    }

    @Test fun anotherNetworkComingUpFirstLeavesTheAttemptsEntryUnsaved() {
        // the attempt set up network 7, but network 3 came up: 7 is untested, so neither this join nor a later one saves
        val gate = SaveGate()
        gate.unconfirmed(7)
        assertFalse(gate.maySave(3))
        assertFalse(gate.maySave(null))
        assertTrue(gate.maySave(7))
    }

    @Test fun onlyAConfirmedRemovalClearsAnEntryTheAttemptMade() {
        val gate = SaveGate()
        gate.unconfirmed(5)
        // removeNetwork refused: nothing calls removed(5), so the leftover still holds saves
        assertFalse(gate.maySave(3))
        gate.removed(5)
        assertTrue(gate.maySave(3))
    }
}
