/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package sh.edm.s1kiosk.conformance

import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.glassworks.platform.HiddenApHotspot
import dev.glassworks.platform.Hotspot
import dev.glassworks.platform.HotspotKind
import dev.glassworks.platform.LocalOnlyHotspot
import dev.glassworks.platform.Platform
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The setup hotspot's way in on a real runtime (#197): Android 6 and 7 must resolve [Hotspot.of] to the hidden-API
 * hotspot without touching [LocalOnlyHotspot]'s Android 8 classes, and find the hidden calls it reflects on. Nothing
 * here starts a hotspot. CI runs it with the conformance test at API 23, 26, 29 and 34 (core-emulators.yml).
 */
@RunWith(AndroidJUnit4::class)
class HotspotConformanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext

    @Test fun thisAndroidGetsTheHotspotItAllows() {
        lateinit var h: Hotspot
        instrumentation.runOnMainSync {
            h = Hotspot.of(app, Handler(Looper.getMainLooper()))
            h.stop() // off: changes nothing, and must not throw on this runtime
        }
        when (Platform.hotspotKind) {
            HotspotKind.LOCAL_ONLY -> assertTrue("API ${Platform.apiLevel}: $h", h is LocalOnlyHotspot)
            HotspotKind.HIDDEN_AP -> assertTrue("API ${Platform.apiLevel}: $h", h is HiddenApHotspot<*>)
        }
    }

    @Test fun android6And7HaveTheHiddenCalls() {
        assumeTrue(Platform.hotspotKind == HotspotKind.HIDDEN_AP)
        val w = WifiManager::class.java
        w.getMethod("setWifiApEnabled", WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
        w.getMethod("getWifiApConfiguration")
        w.getMethod("setWifiApConfiguration", WifiConfiguration::class.java)
    }
}
