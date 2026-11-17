package dev.glassworks.bluetoothproxy

import android.Manifest
import android.app.Application
import android.content.Context
import dev.glassworks.esphome.bluetoothproxy.BleScanner
import dev.glassworks.esphome.bluetoothproxy.GattRadio
import dev.glassworks.esphome.bluetoothproxy.ScanFailure
import dev.glassworks.esphome.bluetoothproxy.ScanPower
import dev.glassworks.pane.permissionsToAsk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #396 review: on Android 12+ a GATT connection needs BLUETOOTH_CONNECT, and the app only asks for the
 * permissions panes declare. The proxy declares what scanning and connecting need, by Android version.
 */
@RunWith(RobolectricTestRunner::class)
class BluetoothProxyPermissionsTest {
    private val file = RuntimeEnvironment.getApplication().getSharedPreferences("bt-permissions-test", Context.MODE_PRIVATE)
    private val radio = object : ProxyRadio {
        override fun newScanner() = object : BleScanner {
            override fun start(power: ScanPower, listener: BleScanner.Listener) {}
            override fun stop() {}
        }
        override fun newGattRadio() = GattRadio { _, _ -> null }
        override fun unavailable(): ScanFailure? = null
        override fun turnBluetoothOn() = false
        override fun turnLocationOn() = false
    }
    private val android12 = setOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    private fun definition(on: Boolean): BluetoothProxyDefinition {
        file.edit().putBoolean(BluetoothProxyPrefs.KEY_ENABLED, on).commit()
        return BluetoothProxyDefinition(file, radio)
    }

    @Test @Config(sdk = [34], application = Application::class)
    fun onAndroid12AndLaterItAsksToScanAndToConnectWhileOn() {
        assertEquals(android12, definition(on = true).permissions)
        assertEquals(android12, listOf(definition(on = true)).permissionsToAsk(touch = true, declared = android12) { false }.toSet())
        assertTrue("off: nothing asked", listOf(definition(on = false)).permissionsToAsk(touch = true, declared = android12) { false }.isEmpty())
    }

    @Test @Config(sdk = [29], application = Application::class)
    fun onAndroid10ItNeedsOnlyFineLocation() {
        assertEquals(setOf(Manifest.permission.ACCESS_FINE_LOCATION), definition(on = true).permissions)
    }
}
