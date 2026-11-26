package dev.glassworks.improv

import org.junit.Assert.assertEquals
import org.junit.Test

/** Every capability switches at exactly its API level, so a wrong boundary fails here. */
class CompatTest {

    private fun row(level: Int) = CompatApi(level).run {
        listOf(hasAdvertisingSets, hasIsLocationEnabled, hasNetworkSuggestions, isAndroid10, hasSecurityParams, hasBluetoothRuntimePermissions, appsCanEnableBluetooth, hasValueNotify, mustSaveNetworks)
    }

    @Test fun eachCapabilitySwitchesAtItsLevel() {
        val expected = mapOf(
            23 to listOf(false, false, false, false, false, false, true, false, true),
            25 to listOf(false, false, false, false, false, false, true, false, true),
            26 to listOf(true, false, false, false, false, false, true, false, false),
            27 to listOf(true, false, false, false, false, false, true, false, false),
            28 to listOf(true, true, false, false, false, false, true, false, false),
            29 to listOf(true, true, true, true, false, false, true, false, false),
            30 to listOf(true, true, true, false, true, false, true, false, false),
            31 to listOf(true, true, true, false, true, true, true, false, false),
            32 to listOf(true, true, true, false, true, true, true, false, false),
            33 to listOf(true, true, true, false, true, true, false, true, false),
            34 to listOf(true, true, true, false, true, true, false, true, false),
        )
        for ((level, want) in expected) assertEquals("API $level", want, row(level))
    }

    @Test fun theSavedNetworkListCountsInFullBeforeAndroid10OrForTheDeviceOwnerWithLocation() {
        for (level in listOf(23, 25, 28)) for (owner in listOf(true, false)) for (loc in listOf(true, false))
            assertEquals("API $level owner=$owner loc=$loc", true, CompatApi(level).listsSavedNetworks(owner, loc))
        for (level in listOf(29, 33)) {
            assertEquals(true, CompatApi(level).listsSavedNetworks(deviceOwner = true, locationAccess = true))
            assertEquals(false, CompatApi(level).listsSavedNetworks(deviceOwner = true, locationAccess = false))
            assertEquals(false, CompatApi(level).listsSavedNetworks(deviceOwner = false, locationAccess = true))
        }
    }

    @Test fun theBootCountExistsFromAndroid7() {
        assertEquals(false, CompatApi(23).hasBootCount)
        assertEquals(true, CompatApi(24).hasBootCount)
    }
}
