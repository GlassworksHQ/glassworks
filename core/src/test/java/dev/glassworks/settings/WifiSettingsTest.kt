/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.settings

import dev.glassworks.net.WifiChange
import dev.glassworks.net.WifiChange.Last
import dev.glassworks.net.WifiChange.State
import dev.glassworks.net.WifiChange.Why
import dev.glassworks.provisioning.SettingsWifiRadio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Wi-Fi row says (#660), on both surfaces: the network now, where a change goes and the way back, and how it
 * ended. And how the provisioner's failure reasons read as a change's. Illustrative names.
 */
class WifiSettingsTest {
    private val home = WifiChange.Network("Brightside Home", 3)

    @Test fun theRowSaysWhereItIsWhereItGoesAndHowItEnded() {
        assertEquals("Brightside Home", WifiSettings.detail(State.Idle(), "Brightside Home", connected = true))
        assertEquals("Not connected", WifiSettings.detail(State.Idle(), null, connected = false))
        assertEquals("Joining Garden Shed. If it doesn't work, the panel goes back to Brightside Home within 2 minutes",
            WifiSettings.detail(State.Joining("Garden Shed", home, 0), "Brightside Home", true))
        assertEquals("Back on Brightside Home. Garden Shed didn't work: Home Assistant didn't answer there",
            WifiSettings.detail(State.Idle(Last.Back("Garden Shed", "Brightside Home", Why.NO_HOME_ASSISTANT)), "Brightside Home", true))
        assertTrue(WifiSettings.detail(State.Idle(Last.Stuck("Garden Shed", "Brightside Home", Why.NO_CONNECTION)), null, false).contains("setup hotspot"))
        assertEquals("Garden Shed", WifiSettings.detail(State.Idle(Last.Joined("Garden Shed")), "Garden Shed", true))
    }

    @Test fun theProvisionersReasonsReadAsAChanges() {
        assertEquals(WifiChange.Joined.Failed(Why.PASSWORD), SettingsWifiRadio.joined("wrong password (authentication failed)"))
        assertEquals(WifiChange.Joined.Failed(Why.PASSWORD), SettingsWifiRadio.joined("WPA/WPA2 passwords are 8-63 printable ASCII characters (or 64 hex digits)"))
        assertEquals(WifiChange.Joined.Failed(Why.NO_CONNECTION), SettingsWifiRadio.joined("timed out"))
        assertEquals(WifiChange.Joined.Superseded, SettingsWifiRadio.joined("superseded"))
        assertEquals(WifiChange.Joined.Failed(Why.TIMED_OUT), SettingsWifiRadio.joined("cancelled"))
    }
}
