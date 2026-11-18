package dev.glassworks.device

import dev.glassworks.platform.AppBuild
import sh.edm.s1kiosk.KioskApp
import sh.edm.s1kiosk.frame.AqaraS1Plus
import android.app.Application
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import dev.glassworks.radio.runningRadio

/** start() queues the startup on esphome-main, so it can still run after the service has stopped the device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PanelDeviceLifecycleTest {

    @Test fun aStartupThatRunsAfterStopStartsNothing() {
        val runtime = KioskApp.runtime(AqaraS1Plus(), build = AppBuild("1.0.0", 10000, debug = false))
        val device = PanelDevice(RuntimeEnvironment.getApplication(), runtime, runtime::devicePanes)
        device.stop()

        device.startInternal()

        assertTrue("no panes were built", device.paneList().isEmpty())
        assertTrue("the radio pane never published its controller", runtime.runningRadio(RuntimeEnvironment.getApplication()).controller == null)
    }
}
