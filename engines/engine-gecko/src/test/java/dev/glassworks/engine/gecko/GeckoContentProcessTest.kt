/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.gecko

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The process names are the S1's under GeckoView 159 (`ps -A`, 2026-11-12), and the legacy engine's under
 * GeckoView 144 on Android 6 (an API 23 emulator, `ps`, 2026-11-15).
 */
class GeckoContentProcessTest {
    private val engine = GeckoEngine(
        settings = { error("not opened") }, extension = GeckoEngine.Extension("", "", ""), logTag = "test", generatedBy = "test",
    )

    @Test fun onlyTabProcessesAreContent() {
        val names = mapOf(
            "sh.edm.s1kiosk" to false,
            "sh.edm.s1kiosk:boot" to false,
            "sh.edm.s1kiosk:gpu_disable_art_image_" to false,
            "sh.edm.s1kiosk:crashhelper_disable_art_image_" to false,
            "sh.edm.s1kiosk:sandboxed_tab_disable_art_image_38" to true,
            "sh.edm.s1kiosk:sandboxed_tab_disable_art_image_0" to true,
            "dev.glassworks.s1.legacy:tab7" to true,
            "dev.glassworks.s1.legacy:tab0" to true,
            "dev.glassworks.s1.legacy:gpu" to false,
            "dev.glassworks.s1.legacy:crashhelper" to false,
            "dev.glassworks.s1.legacy:tablet" to false,
        )
        assertEquals(names, names.keys.associateWith(engine::isContentProcess))
    }
}
