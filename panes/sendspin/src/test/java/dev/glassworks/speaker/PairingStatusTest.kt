package dev.glassworks.speaker

import org.junit.Assert.assertEquals
import org.junit.Test
import dev.glassworks.esphome.SendspinApi

class PairingStatusTest {

    @Test
    fun switchedOffAfterStartingIsDisabled() {
        assertEquals(SendspinApi.STATUS_DISABLED, pairingStatus(enabled = false, started = true))
    }

    @Test
    fun switchedOffNeverStartedIsDisabled() {
        assertEquals(SendspinApi.STATUS_DISABLED, pairingStatus(enabled = false, started = false))
    }

    @Test
    fun onButNotStartedIsNotReady() {
        assertEquals(SendspinApi.STATUS_NOT_READY, pairingStatus(enabled = true, started = false))
    }

    @Test
    fun onAndStartedIsOk() {
        assertEquals(SendspinApi.STATUS_OK, pairingStatus(enabled = true, started = true))
    }

    /** The key store can't read the identity or the pairing key now: a token would pair a stand-in. */
    @Test
    fun unreadableSecretsAreNotReady() {
        assertEquals(SendspinApi.STATUS_NOT_READY, pairingStatus(enabled = true, started = true, secretsReadable = false))
        assertEquals(SendspinApi.STATUS_DISABLED, pairingStatus(enabled = false, started = true, secretsReadable = false))
    }
}
