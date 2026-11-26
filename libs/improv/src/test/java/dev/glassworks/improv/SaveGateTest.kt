package dev.glassworks.improv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An untested credential is never saved: not while its attempt runs, not after it failed or another network came up. */
class SaveGateTest {

    /** The app's preferences, in memory: what outlives a provisioner and the app's process, but not a reboot. */
    private class MemStore(var writable: Boolean = true) : SaveGate.Store {
        var kept: SaveGate.Kept? = null
        override fun read() = kept
        override fun write(kept: SaveGate.Kept): Boolean { if (writable) this.kept = kept; return writable }
    }

    private var elapsed = 1_000L
    private fun gate(store: MemStore = MemStore(), boot: String? = "boot-1") = SaveGate(store, { boot }, { elapsed })

    @Test fun nothingUnconfirmedSaves() = assertTrue(gate().maySave(4))

    @Test fun anAttemptStillRunningHoldsEverySave() {
        val g = gate()
        assertTrue(g.beginChange())
        assertFalse(g.maySave(4)) // recorded before addNetwork returned an id
        g.unconfirmed(9)
        assertFalse(g.maySave(4))
    }

    @Test fun aRefusedAddNetworkChangesNothing() {
        val g = gate()
        g.beginChange()
        g.changeRefused()
        assertTrue(g.maySave(4))
    }

    @Test fun aChangeThatCantBeRecordedIsNotMade() {
        val g = gate(MemStore(writable = false))
        assertFalse(g.beginChange())
        assertTrue(g.maySave(4)) // nothing was changed, so nothing is held
    }

    @Test fun aFailedAttemptOnASavedNetworkStopsTheSaveOfAnotherJoin() {
        val g = gate()
        g.unconfirmed(2)
        assertFalse(g.maySave(4))
        assertFalse(g.maySave(null))
    }

    @Test fun joiningTheSameNetworkLaterConfirmsItsCredential() {
        val g = gate()
        g.unconfirmed(2)
        assertTrue(g.maySave(2))
        assertTrue(g.maySave(4))
    }

    @Test fun anotherNetworkComingUpFirstLeavesTheAttemptsEntryUnsaved() {
        val g = gate()
        g.unconfirmed(7)
        assertFalse(g.maySave(3))
        assertFalse(g.maySave(null))
        assertTrue(g.maySave(7))
    }

    @Test fun onlyAConfirmedRemovalClearsAnEntryTheAttemptMade() {
        val g = gate()
        g.unconfirmed(5)
        assertFalse(g.maySave(3))
        g.removed(5)
        assertTrue(g.maySave(3))
    }

    @Test fun aNewProvisionerInTheSameBootStillHoldsAnUnconfirmedEntry() {
        val store = MemStore()
        gate(store).unconfirmed(6)
        val again = gate(store)
        assertFalse(again.maySave(4))
        assertTrue(again.maySave(6))
    }

    @Test fun aChangeInProgressWhenTheAppDiedStillHoldsAfterARestart() {
        val store = MemStore()
        gate(store).beginChange()
        assertFalse(gate(store).maySave(4))
    }

    @Test fun aRebootForgetsThem() {
        val store = MemStore()
        gate(store).unconfirmed(6)
        assertTrue(gate(store, boot = "boot-2").maySave(4))
    }

    @Test fun withoutABootIdAWallClockCorrectionKeepsThem() {
        // no boot id: only the time since boot tells, and that never jumps with the wall clock
        val store = MemStore()
        gate(store, boot = null).unconfirmed(6)
        elapsed += 120_000
        assertFalse(gate(store, boot = null).maySave(4))
    }

    @Test fun withoutABootIdTimeSinceBootGoingBackMeansARestart() {
        val store = MemStore()
        elapsed = 500_000
        gate(store, boot = null).unconfirmed(6)
        elapsed = 30_000
        assertTrue(gate(store, boot = null).maySave(4))
    }
}
