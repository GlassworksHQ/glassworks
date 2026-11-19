/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.device

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** #523: the identity Home Assistant keys the device by survives a reflash and a reinstall wherever the device allows. */
class PickMacTest {
    private val wifi = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)
    private val noRandom: () -> ByteArray = { fail("no random MAC while a stable source exists"); ByteArray(6) }

    private fun pick(saved: String = "", image: String = "", wlan0: ByteArray? = null, serial: String = "", androidId: String? = null,
                     random: () -> ByteArray = noRandom) = PanelDevice.pickMac(saved, image, wlan0, serial, androidId, random)

    @Test fun theSavedMacNeverChanges() {
        assertEquals("02:00:00:A1:B2:C3" to "saved", pick(saved = "02:00:00:A1:B2:C3", image = "06:00:00:00:00:01", wlan0 = wifi, serial = "0123456789abcdef"))
    }

    @Test fun aReflashedImageGivesItsMacToFreshData() {
        assertEquals("06:00:00:00:00:01" to "build.prop", pick(image = "06:00:00:00:00:01", wlan0 = wifi, serial = "0123456789abcdef", androidId = "a1b2c3d4e5f60718"))
    }

    @Test fun anImageWithoutAMacKeepsTheOldOrder() {
        assertEquals("00:11:22:33:44:55" to "wlan0", pick(image = "", wlan0 = wifi, serial = "0123456789abcdef"))
        assertEquals("E6:79:E6:E0:96:A3" to "serial", pick(image = "not a mac", serial = "0123456789abcdef"))
    }

    @Test fun androidIdKeepsTheIdentityWhereWifiAndSerialAreHidden() {
        val first = pick(wlan0 = byteArrayOf(2, 0, 0, 0, 0, 0), serial = "", androidId = "a1b2c3d4e5f60718")
        assertEquals("the same ANDROID_ID gives the same MAC after a reinstall", first, pick(serial = "unknown", androidId = "a1b2c3d4e5f60718"))
        assertEquals("android_id", first.second)
        // pinned: a changed salt or hash would give every such device a new HA entry
        assertEquals("0A:06:EB:D9:C2:5C", first.first)
    }

    @Test fun randomOnlyWithNothingElse() {
        val (mac, source) = pick(androidId = "", random = { byteArrayOf(0xFF.toByte(), 1, 2, 3, 4, 5) })
        assertEquals("FE:01:02:03:04:05" to "random", mac to source)
    }

    /** #574: every start logs the identity and its source; before, only the start that chose it did. */
    @Test fun everyStartSaysWhereTheMacCameFrom() {
        assertEquals("Device MAC 06:00:00:00:00:01 (build.prop)", PanelDevice.macLine("06:00:00:00:00:01", "build.prop", "06:00:00:00:00:01"))
        assertEquals("Device MAC 06:00:00:00:00:01 (saved, the same as build.prop)", PanelDevice.macLine("06:00:00:00:00:01", "saved", "06:00:00:00:00:01"))
        assertEquals("Device MAC 02:00:00:A1:B2:C3 (saved; build.prop has another, the saved one wins)", PanelDevice.macLine("02:00:00:A1:B2:C3", "saved", "06:00:00:00:00:01"))
        assertEquals("Device MAC 02:00:00:A1:B2:C3 (saved)", PanelDevice.macLine("02:00:00:A1:B2:C3", "saved", ""))
    }
}
