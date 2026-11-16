package dev.glassworks.bluetoothproxy

import android.app.Application
import android.content.Context
import dev.glassworks.esphome.bluetoothproxy.BleScanner
import dev.glassworks.esphome.bluetoothproxy.GattRadio
import dev.glassworks.esphome.bluetoothproxy.ScanFailure
import dev.glassworks.esphome.bluetoothproxy.ScanPower
import dev.glassworks.pane.PaneState
import dev.glassworks.pane.paneState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The definition's section, as the panel's rail and the Manage page read it (#246's verifier note on #247). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BluetoothProxyDefinitionTest {
    private val file = RuntimeEnvironment.getApplication().getSharedPreferences("bt-def-test", Context.MODE_PRIVATE)

    private class Radio(var why: ScanFailure?) : ProxyRadio {
        override fun newScanner() = object : BleScanner {
            override fun start(power: ScanPower, listener: BleScanner.Listener) {}
            override fun stop() {}
        }
        override fun newGattRadio() = GattRadio { _, _ -> null }
        override fun unavailable() = why
        override fun turnBluetoothOn() = false
        override fun turnLocationOn() = false
    }

    private fun state(why: ScanFailure?, on: Boolean = true): PaneState {
        file.edit().putBoolean(BluetoothProxyPrefs.KEY_ENABLED, on).commit()
        val section = BluetoothProxyDefinition(file, Radio(why)).settings().single()
        return paneState(section)
    }

    @Test fun onButStoppedReadsCantRunWithWhy() {
        assertEquals(PaneState(PaneState.Kind.CANT_RUN, "Bluetooth is off"), state(ScanFailure.BLUETOOTH_OFF))
        assertEquals(PaneState(PaneState.Kind.CANT_RUN, "Location is off"), state(ScanFailure.LOCATION_OFF))
        assertEquals(PaneState(PaneState.Kind.CANT_RUN, "A permission is missing"), state(ScanFailure.NO_PERMISSION))
    }

    @Test fun theSwitchAndTheTurnOnCardStayWhileItCantRun() {
        file.edit().putBoolean(BluetoothProxyPrefs.KEY_ENABLED, true).commit()
        val section = BluetoothProxyDefinition(file, Radio(ScanFailure.BLUETOOTH_OFF)).settings().single()
        assertNull("not \"can't run on this panel\": that hides the switch", section.unavailable())
    }

    @Test fun runningOrOffSaysNothingOfItsOwn() {
        assertTrue("running: the core's On, from the switch", state(null).kind != PaneState.Kind.CANT_RUN)
        assertTrue("off: the core's Off, and Bluetooth off doesn't matter", state(ScanFailure.BLUETOOTH_OFF, on = false).kind != PaneState.Kind.CANT_RUN)
    }

    @Test fun noBluetoothLeStillCantRunOnThisPanel() {
        assertEquals("it has no Bluetooth LE", BluetoothProxyDefinition(file, Radio(ScanFailure.NO_BLE)).settings().single().unavailable())
    }

    @Test fun theModeIsKept() {
        val def = BluetoothProxyDefinition(file, Radio(null))
        def.connect = true
        assertTrue(BluetoothProxyPrefs(file).connect)
        assertTrue(BluetoothProxyDefinition(file, Radio(null)).connect)
    }
}
