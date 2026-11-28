package dev.glassworks.esphome.bluetoothproxy

import org.junit.Assert.assertEquals
import org.junit.Test

class CompatTest {
    @Test fun scanPermissionsFollowAndroidsRules() {
        assertEquals(listOf("android.permission.ACCESS_COARSE_LOCATION"), CompatApi(23).scanPermissions)
        assertEquals(listOf("android.permission.ACCESS_COARSE_LOCATION"), CompatApi(28).scanPermissions)
        assertEquals("Android 10 wants FINE", listOf("android.permission.ACCESS_FINE_LOCATION"), CompatApi(29).scanPermissions)
        assertEquals(
            listOf("android.permission.BLUETOOTH_SCAN", "android.permission.ACCESS_FINE_LOCATION"),
            CompatApi(31).scanPermissions,
        )
    }

    @Test fun theProxyAsksForWhatScanningAndConnectingNeed() {
        assertEquals(setOf("android.permission.ACCESS_FINE_LOCATION"), CompatApi(29).runtimePermissions)
        assertEquals(
            setOf("android.permission.BLUETOOTH_SCAN", "android.permission.ACCESS_FINE_LOCATION", "android.permission.BLUETOOTH_CONNECT"),
            CompatApi(31).runtimePermissions,
        )
    }

    @Test fun instanceIdsAreHandlesFromAndroid7() {
        assertEquals("Android 6: an instance id only tells same-UUID attributes apart", false, CompatApi(23).gattInstanceIdsAreHandles)
        assertEquals(true, CompatApi(24).gattInstanceIdsAreHandles)
        assertEquals(true, CompatApi(29).gattInstanceIdsAreHandles)
    }

    @Test fun connectingNeedsBluetoothConnectOnlyFromAndroid12() {
        assertEquals(emptyList<String>(), CompatApi(29).connectPermissions)
        assertEquals(listOf("android.permission.BLUETOOTH_CONNECT"), CompatApi(31).connectPermissions)
    }
}
