package dev.glassworks.improv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A failed attempt removes only an entry it provably made: a network saved before is never removed. */
class SavedNetworksTest {

    @Test fun anIdThatWasntSavedBeforeIsOurs() = assertEquals(true, SavedNetworks.madeHere(7, setOf(1, 2), listTrusted = true))

    @Test fun anIdSavedBeforeIsNot() = assertEquals(false, SavedNetworks.madeHere(2, setOf(1, 2), listTrusted = true))

    @Test fun withoutLocationAccessFromAndroid10ItIsUnknown() {
        // Android 10 hands an app without location access an empty list: the reused id looks new, but isn't trusted
        val listTrusted = CompatApi(29).listsSavedNetworks(deviceOwner = true, locationAccess = false)
        assertNull(SavedNetworks.madeHere(2, emptySet(), listTrusted))
    }

    @Test fun anUnreadableListIsUnknown() = assertNull(SavedNetworks.madeHere(2, null, listTrusted = true))

    @Test fun onAndroid6TheListCountsWithoutLocation() {
        val listTrusted = CompatApi(23).listsSavedNetworks(deviceOwner = false, locationAccess = false)
        assertEquals(false, SavedNetworks.madeHere(2, setOf(2), listTrusted))
        assertEquals(true, SavedNetworks.madeHere(3, setOf(2), listTrusted))
    }
}
