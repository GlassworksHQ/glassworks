package dev.glassworks.esphome.bluetoothproxy

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.location.LocationManager
import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.UUID

/** [AndroidGattRadio] on Android 10, the S1's version: which device it connects to, and the handles it gives. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class AndroidGattRadioTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val adapter: BluetoothAdapter = app.getSystemService(BluetoothManager::class.java).adapter
    private val quiet = object : GattLink.Listener {
        override fun onConnectionState(connected: Boolean, status: Int) = Unit
        override fun onMtu(mtu: Int, status: Int) = Unit
        override fun onServicesDiscovered(status: Int) = Unit
        override fun onRead(handle: Int, data: ByteArray, status: Int) = Unit
        override fun onWritten(handle: Int, status: Int) = Unit
        override fun onDescriptorRead(handle: Int, data: ByteArray, status: Int) = Unit
        override fun onDescriptorWritten(handle: Int, status: Int) = Unit
        override fun onNotification(handle: Int, data: ByteArray) = Unit
    }

    /** The hidden `setInstanceId`, as Android's own discovery sets it. */
    private fun <T : Any> withId(o: T, id: Int): T = o.also {
        ReflectionHelpers.callInstanceMethod<Unit>(it, "setInstanceId", ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, id))
    }

    @Test fun addressesAsAndroidWritesThem() {
        assertEquals("02:C0:FF:EE:00:01", formatAddress(0x02C0FFEE0001L))
        assertEquals(0x02C0FFEE0001L, parseAddress(formatAddress(0x02C0FFEE0001L)))
        val was = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
            assertEquals("Western digits in any locale, as getRemoteDevice wants", "02:C0:FF:EE:00:01", formatAddress(0x02C0FFEE0001L))
        } finally { java.util.Locale.setDefault(was) }
    }

    @Test fun handlesAreAndroidsInstanceIds() {
        val service = withId(BluetoothGattService(UUID(1, 1), BluetoothGattService.SERVICE_TYPE_PRIMARY), 10)
        val c = withId(BluetoothGattCharacteristic(UUID(1, 2), 0x12, 0), 12)
        val cccd = BluetoothGattDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"), 0)
        val other = BluetoothGattDescriptor(UUID(1, 4), 0)
        c.addDescriptor(cccd); c.addDescriptor(other)
        service.addCharacteristic(c)

        val t = GattTable(listOf(service))
        val s = t.services.single()
        assertEquals(10, s.handle)
        assertEquals(12, s.characteristics.single().handle)
        assertEquals(0x12, s.characteristics.single().properties)
        assertEquals("the ATT layout: right after the value handle, in order",
            listOf(13, 14), s.characteristics.single().descriptors.map { it.handle })
        assertSame(c, t.characteristics[12])
        assertSame(cccd, t.descriptors[13])
        assertEquals(14, t.descriptorHandles[other])
    }

    /** Android 6 (the Mirror): every instance id is 0, so the table numbers the attributes itself. */
    @Test fun withoutHandlesItNumbersTheAttributes() {
        val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val access = BluetoothGattService(UUID(1, 1), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val name = BluetoothGattCharacteristic(UUID(1, 2), 0x02, 0)
        access.addCharacteristic(name)
        val heart = BluetoothGattService(UUID(2, 1), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rate = BluetoothGattCharacteristic(UUID(2, 2), 0x10, 0)
        val cccd = BluetoothGattDescriptor(cccdUuid, 0)
        rate.addDescriptor(cccd)
        val place = BluetoothGattCharacteristic(UUID(2, 3), 0x02, 0)
        heart.addCharacteristic(rate); heart.addCharacteristic(place)

        val t = GattTable(listOf(access, heart), numbered = true)
        assertEquals("service, then declaration and value per characteristic, then its descriptors",
            listOf(1, 4), t.services.map { it.handle })
        assertEquals(listOf(3), t.services[0].characteristics.map { it.handle })
        assertEquals(listOf(6, 9), t.services[1].characteristics.map { it.handle })
        assertEquals(listOf(7), t.services[1].characteristics[0].descriptors.map { it.handle })
        assertSame(name, t.characteristics[3])
        assertSame(rate, t.characteristics[6])
        assertSame(place, t.characteristics[9])
        assertSame(cccd, t.descriptors[7])
        assertEquals("a callback finds its handle by the object Android hands back", 6, t.characteristicHandles[rate])
    }

    /** What Home Assistant is told: a callback reports the table's number, not Android 6's instance id (0). */
    @Suppress("DEPRECATION") // the pre-Android 13 callbacks, the ones the link overrides
    @Test fun callbacksReportTheNumberedHandle() {
        val service = BluetoothGattService(UUID(2, 1), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val place = BluetoothGattCharacteristic(UUID(2, 2), 0x0a, 0)
        val rate = BluetoothGattCharacteristic(UUID(2, 3), 0x10, 0)
        service.addCharacteristic(place); service.addCharacteristic(rate)
        val seen = ArrayList<String>()
        val link = AndroidGattLink(object : GattLink.Listener by quiet {
            override fun onRead(handle: Int, data: ByteArray, status: Int) { seen += "read $handle" }
            override fun onWritten(handle: Int, status: Int) { seen += "written $handle" }
            override fun onNotification(handle: Int, data: ByteArray) { seen += "notified $handle" }
        })
        link.table = GattTable(listOf(service), numbered = true)
        val gatt = adapter.getRemoteDevice("02:C0:FF:EE:00:01").connectGatt(app, false, link.callback, BluetoothDevice.TRANSPORT_LE)

        link.callback.onCharacteristicChanged(gatt, rate)
        link.callback.onCharacteristicRead(gatt, rate, 0)
        link.callback.onCharacteristicWrite(gatt, place, 0)
        assertEquals("both instance ids are 0; the numbers are the table's", listOf("notified 5", "read 5", "written 3"), seen)
    }

    @Test fun itConnectsToTheDeviceTheScannerHeard() {
        shadowOf(adapter).setEnabled(true)
        val recent = RecentDevices()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        val scanner = AndroidBleScanner(app, recent)
        scanner.start(ScanPower.LOW_LATENCY, object : BleScanner.Listener {
            override fun onAdvertisement(address: Long, rssi: Int, data: ByteArray) = Unit
            override fun onFailed(failure: ScanFailure) = Unit
        })
        val device = adapter.getRemoteDevice("02:C0:FF:EE:00:01")
        val record = ScanRecord::class.java.getDeclaredMethod("parseFromBytes", ByteArray::class.java).invoke(null, byteArrayOf(2, 1, 6)) as ScanRecord
        shadowOf(adapter.bluetoothLeScanner).activeScans.single().scanCallback()!!
            .onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, ScanResult(device, record, -61, 0))
        assertSame("remembered from the scan result", device, recent[0x02C0FFEE0001L])

        assertNotNull(AndroidGattRadio(app, recent).connect(0x02C0FFEE0001L, quiet))
        scanner.stop(); scanner.release()
    }

    @Test fun noLinkWhileBluetoothIsOff() {
        shadowOf(adapter).setEnabled(false)
        assertNull(AndroidGattRadio(app, RecentDevices()).connect(0x02C0FFEE0001L, quiet))
    }
}
