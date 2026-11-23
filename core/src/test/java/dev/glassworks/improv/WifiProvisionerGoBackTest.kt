/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.improv

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.net.InetAddress
import java.time.Duration
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWifiInfo

/**
 * The provisioner's way back for a Wi-Fi change after setup (#660): it can tell when it may switch networks
 * ([WifiProvisioner.switchBlock]), reads the network the device is on, and [WifiProvisioner.goBack] forgets only what
 * the change saved, selects the old network again and reports once it is back, or that it isn't. Illustrative names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class WifiProvisionerGoBackTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val wm = ctx.getSystemService(WifiManager::class.java)
    private val p by lazy { WifiProvisioner(ctx, Handler(Looper.getMainLooper())) }
    private var home = -1
    private var shed = -1

    @Suppress("DEPRECATION")
    private fun save(name: String): Int = wm.addNetwork(WifiConfiguration().apply {
        SSID = "\"$name\""
        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
        preSharedKey = "\"correct horse\""
    })

    private fun on(name: String?, id: Int) {
        val info = Shadow.newInstanceOf(WifiInfo::class.java)
        val s = Shadow.extract<ShadowWifiInfo>(info)
        s.setSSID(name ?: "<unknown ssid>") // the shadow quotes it, as Android does
        s.setNetworkId(id)
        s.setSupplicantState(if (name == null) SupplicantState.DISCONNECTED else SupplicantState.COMPLETED)
        s.setInetAddress(InetAddress.getByName(if (name == null) "0.0.0.0" else "192.0.2.10"))
        shadowOf(wm).setConnectionInfo(info)
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Before fun setUp() {
        @Suppress("DEPRECATION") wm.setWifiEnabled(true)
        shadowOf(ctx as Application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(ctx.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        ShadowSettings.setCanDrawOverlays(true)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = ShadowNetworkCapabilities.newInstance().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) }
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
        home = save("Brightside Home")
        shed = save("Garden Shed")
        on("Garden Shed", shed)
    }

    @Suppress("DEPRECATION")
    private fun saved(): List<String> = wm.configuredNetworks.map { it.SSID.removeSurrounding("\"") }

    @Test fun itMaySwitchOnlyWithTheLegacyCallsAndLocation() {
        assertNull(p.switchBlock())
        ShadowSettings.setCanDrawOverlays(false)
        assertEquals("a suggestion can't move it off a network that works", WifiProvisioner.SwitchBlock.SUGGESTIONS_ONLY, p.switchBlock())
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(ctx.getSystemService(LocationManager::class.java)).setLocationEnabled(false)
        assertEquals(WifiProvisioner.SwitchBlock.NO_LOCATION, p.switchBlock())
    }

    @Test fun itReadsTheNetworkItIsOnAndWhatIsSaved() {
        assertEquals(WifiProvisioner.OnNetwork("Garden Shed", shed), p.onNetwork())
        assertFalse("the log line has no name", p.onNetwork().toString().contains("Garden"))
        assertTrue(p.isSaved("Brightside Home"))
        assertFalse(p.isSaved("Attic"))
        on(null, -1)
        assertNull(p.onNetwork())
    }

    @Test fun goingBackForgetsWhatTheChangeSavedAndSelectsTheOldNetwork() {
        var got: WifiProvisioner.Outcome? = null
        p.goBack(WifiProvisioner.OnNetwork("Brightside Home", home), "Garden Shed", forget = true) { got = it }
        assertEquals(listOf("Brightside Home"), saved())
        assertEquals(home to true, shadowOf(wm).lastEnabledNetwork.let { it.first to it.second })
        on("Garden Shed", shed) // still on the new one for a moment
        idle(2_000)
        assertNull("not back yet", got)
        on("Brightside Home", home)
        idle(1_000)
        assertTrue(got.toString(), got is WifiProvisioner.Outcome.Connected && (got as WifiProvisioner.Outcome.Connected).ssid == "Brightside Home")
    }

    @Test fun aNetworkSavedBeforeTheChangeStaysSaved() {
        p.goBack(WifiProvisioner.OnNetwork("Brightside Home", home), "Garden Shed", forget = false) {}
        assertEquals(listOf("Brightside Home", "Garden Shed"), saved().sorted())
    }

    @Test fun alreadyOnTheOldNetworkOnlyForgets() {
        on("Brightside Home", home)
        var got: WifiProvisioner.Outcome? = null
        p.goBack(WifiProvisioner.OnNetwork("Brightside Home", home), "Garden Shed", forget = true) { got = it }
        assertTrue(got is WifiProvisioner.Outcome.Connected)
        assertEquals(listOf("Brightside Home"), saved())
    }

    @Test fun anOldNetworkThatDoesntComeBackFailsOnce() {
        val got = ArrayList<WifiProvisioner.Outcome>()
        p.goBack(WifiProvisioner.OnNetwork("Brightside Home", home), "Garden Shed", forget = false) { got += it }
        on("Garden Shed", shed) // the old network never comes back
        idle(29_000)
        assertTrue(got.isEmpty())
        idle(2_000)
        assertEquals(1, got.size)
        assertTrue(got.single() is WifiProvisioner.Outcome.Failed)
        idle(60_000)
        assertEquals(1, got.size)
    }

    @Test fun aNewJoinSupersedesTheWayBack() {
        val got = ArrayList<WifiProvisioner.Outcome>()
        p.goBack(WifiProvisioner.OnNetwork("Brightside Home", home), "Garden Shed", forget = false) { got += it }
        p.connect("Attic", "battery staple") {}
        assertEquals("superseded", (got.single() as WifiProvisioner.Outcome.Failed).reason)
        idle(1_000)
        assertEquals(1, got.size)
    }
}
