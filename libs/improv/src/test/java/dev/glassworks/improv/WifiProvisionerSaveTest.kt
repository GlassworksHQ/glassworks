package dev.glassworks.improv

import android.Manifest
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWifiInfo
import java.net.InetAddress
import java.time.Duration

/**
 * Greptile on #902: when a join through [WifiProvisioner.connect] saves the Wi-Fi networks. Android 6 and 7 save only a
 * join of the network the attempt set up, and never while another attempt's credential is untested; Android 8 and
 * later save nothing and record nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], application = Application::class)
class WifiProvisionerSaveTest {

    private val app: Application = RuntimeEnvironment.getApplication()
    private val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val main = Handler(Looper.getMainLooper())
    private val outcomes = mutableListOf<WifiProvisioner.Outcome>()

    @Before fun freshGate() {
        SaveGate.shared = null // the sandbox keeps the process-wide gate from one test to the next
        wm.isWifiEnabled = true
        off()
    }

    /** Off any network: no Wi-Fi at the start, so whatever comes up is the attempt's. */
    private fun off() = shadowOf(wm).setConnectionInfo(ShadowWifiInfo.newInstance().also {
        shadowOf(it).setSupplicantState(SupplicantState.DISCONNECTED)
        shadowOf(it).setNetworkId(-1)
    })

    /** Wi-Fi comes up on [netId] (named [ssid]) with an address. */
    private fun joined(netId: Int, ssid: String) {
        shadowOf(wm).setConnectionInfo(ShadowWifiInfo.newInstance().also {
            shadowOf(it).setSupplicantState(SupplicantState.COMPLETED)
            shadowOf(it).setNetworkId(netId)
            shadowOf(it).setSSID(ssid)
            shadowOf(it).setInetAddress(InetAddress.getByName("192.0.2.10"))
        })
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val network = ShadowNetwork.newInstance(100 + netId)
        shadowOf(cm).addNetwork(network, ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, NetworkInfo.State.CONNECTED,
        ))
        shadowOf(cm).setNetworkCapabilities(network, ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        })
    }

    private fun locationOn() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        @Suppress("DEPRECATION")
        Settings.Secure.putInt(app.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_HIGH_ACCURACY)
    }

    private fun idle(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    /** Starts a join and returns the id addNetwork gave its entry. */
    private fun WifiProvisioner.start(ssid: String): Int {
        connect(ssid, "made-up-pass") { outcomes += it }
        shadowOf(Looper.getMainLooper()).idle()
        return shadowOf(wm).lastEnabledNetwork.first
    }

    @Test fun aConfirmedJoinSaves() {
        val p = WifiProvisioner(app, main)
        val id = p.start("Example Wi-Fi")
        joined(id, "Example Wi-Fi")
        idle(2)
        assertTrue(outcomes.single() is WifiProvisioner.Outcome.Connected)
        assertTrue("the joined network is saved", shadowOf(wm).wasConfigurationSaved())
    }

    @Test fun aJoinThatTimesOutDoesntSave() {
        val p = WifiProvisioner(app, main)
        p.start("Example Wi-Fi")
        idle(WifiProvisioner.CONNECT_TIMEOUT_MS / 1000 + 2)
        assertEquals("timed out", (outcomes.single() as WifiProvisioner.Outcome.Failed).reason)
        assertFalse(shadowOf(wm).wasConfigurationSaved())
    }

    @Test fun aCancelledJoinDoesntSave() {
        val p = WifiProvisioner(app, main)
        val id = p.start("Example Wi-Fi")
        p.cancel()
        joined(id, "Example Wi-Fi") // too late: the attempt is over
        idle(2)
        assertEquals("cancelled", (outcomes.single() as WifiProvisioner.Outcome.Failed).reason)
        assertFalse(shadowOf(wm).wasConfigurationSaved())
    }

    @Test fun anotherNetworkComingUpLeavesTheEntryUnconfirmedAndBlocksTheNextJoinsSave() {
        locationOn()
        val p = WifiProvisioner(app, main)
        // the first attempt's network comes up under another saved entry with the same name: its own entry is untested
        val first = p.start("Example Wi-Fi")
        joined(first + 40, "Example Wi-Fi")
        idle(2)
        assertTrue(outcomes.last() is WifiProvisioner.Outcome.Connected)
        assertFalse("not the entry this attempt set up: no save", shadowOf(wm).wasConfigurationSaved())

        // a later join of another network is confirmed, but the first attempt's entry still holds every save
        off()
        val second = p.start("Other Wi-Fi")
        joined(second, "Other Wi-Fi")
        idle(2)
        assertTrue(outcomes.last() is WifiProvisioner.Outcome.Connected)
        assertFalse("an untested credential is never saved", shadowOf(wm).wasConfigurationSaved())

        // once the first network is up through its own entry, its credential is tested, and the save goes ahead
        joined(first, "Example Wi-Fi")
        p.connect("Example Wi-Fi", "made-up-pass") { outcomes += it }
        assertEquals("already-connected", (outcomes.last() as WifiProvisioner.Outcome.Connected).method)
        assertTrue(shadowOf(wm).wasConfigurationSaved())
    }

    @Config(sdk = [29])
    @Test fun android10SavesNothingAndRecordsNothing() {
        ShadowSettings.setCanDrawOverlays(true) // addNetwork is allowed on Android 10 with the overlay app-op
        val p = WifiProvisioner(app, main)
        val id = p.start("Example Wi-Fi")
        joined(id, "Example Wi-Fi")
        idle(2)
        assertTrue(outcomes.single() is WifiProvisioner.Outcome.Connected)
        assertFalse("Android saves the network itself", shadowOf(wm).wasConfigurationSaved())
        assertNull("no gate is made", SaveGate.shared)
        val prefs = app.getSharedPreferences("glassworks_improv_save_gate", Context.MODE_PRIVATE)
        assertTrue("nothing is written", prefs.all.isEmpty())
    }
}
