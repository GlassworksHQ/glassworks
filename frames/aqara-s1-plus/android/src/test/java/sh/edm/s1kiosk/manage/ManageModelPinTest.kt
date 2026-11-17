/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package sh.edm.s1kiosk.manage

import dev.glassworks.testing.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #466: All panels sends a token only to a panel whose hello names the model Home Assistant lists for it. The
 * Manage page's model and the one [sh.edm.s1kiosk.esphome.PanelDevice] gives Home Assistant must stay the same.
 */
class ManageModelPinTest {
    @Test fun theManagePageNamesTheModelThePanelGivesHomeAssistant() {
        assertEquals("Panel Hub S1 Plus (S1 Kiosk)", AppManage.deviceModel("Panel Hub S1 Plus", "S1 Kiosk"))
        val device = RepoFiles.source("sh/edm/s1kiosk/esphome/PanelDevice.kt").readText()
        assertTrue("PanelDevice's model changed: change AppManage.deviceModel with it",
            device.contains("model = \"\${frame.hardware.model} (\${runtime.build.product})\","))
    }
}
