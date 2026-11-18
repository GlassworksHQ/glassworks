/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.device

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The ESPHome device's MAC from the chip's serial is the panel's identity in Home Assistant. A changed salt or hash
 * gives every panel a new device with new entities, so this pins the bytes for a made-up serial (#527).
 */
class DeviceMacPinTest {
    @Test fun theMacForASerialNeverChanges() {
        val mac = PanelDevice.macFromSerial("0123456789abcdef").joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
        assertEquals("E6:79:E6:E0:96:A3", mac)
    }
}
