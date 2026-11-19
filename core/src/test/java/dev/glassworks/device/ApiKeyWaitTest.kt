/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #574: on an image whose installer hands the API key over, the fresh app opens no API server until the key comes,
 * so Home Assistant never meets the device without its key (which opens a re-authentication).
 */
class ApiKeyWaitTest {
    @Test fun waitsWhileTheImagePromisesAKeyAndNoneHasCome() {
        assertTrue(PanelDevice.waitsForKey("1", hasKey = false, waitedMs = 0))
        assertTrue(PanelDevice.waitsForKey("1", hasKey = false, waitedMs = PanelDevice.API_KEY_WAIT_MS - 1))
    }

    @Test fun startsOnceTheKeyCame() = assertFalse(PanelDevice.waitsForKey("1", hasKey = true, waitedMs = 5_000))

    @Test fun neverWaitsOnAnImageThatPromisesNoKey() {
        assertFalse(PanelDevice.waitsForKey("", hasKey = false, waitedMs = 0))
        assertFalse(PanelDevice.waitsForKey("0", hasKey = false, waitedMs = 0))
    }

    @Test fun givesUpAfterTheWaitAndStartsAsBefore() =
        assertFalse(PanelDevice.waitsForKey("1", hasKey = false, waitedMs = PanelDevice.API_KEY_WAIT_MS))

    @Test fun theKeyNamesArePinned() {
        // Kiln's image patch writes this property (frames/lululemon-mirror/installer/src/image.ts API_KEY_HANDOVER_PROP)
        assertEquals("ro.glassworks.api_key.handover", PanelDevice.API_KEY_HANDOVER_PROP)
        // and the installers open this socket (kiln/packages/app-adb API_KEY_SOCKET)
        assertEquals("glassworks_api_key", ApiKeyHandoverServer.SOCKET)
    }
}
