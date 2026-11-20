package dev.glassworks.s1.frame

import android.app.Application
import dev.glassworks.frame.FrameRelease
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import dev.glassworks.radio.SysProps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Device page (the panel's and Manage's) on the S1's frame loader image (#549): its rows read the loader's
 * names. Seen on a real loader image 2026-11-19: Silent updates said "No" while `glassworks_update` was there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class S1DeviceLoaderTest {
    private val app = RuntimeEnvironment.getApplication()

    @Before fun theAppsTarget() {
        app.applicationInfo.targetSdkVersion = 35
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
    }

    private val loader = object : SysProps {
        val values = mutableMapOf(FrameRelease.ID_PROP to "aqara-s1-plus")
        override fun get(name: String) = values[name].orEmpty()
        override fun set(name: String, value: String) { values[name] = value }
    }

    private fun page(): SettingsSection = S1Device(AqaraCloud(run = { "" }), loader, reader = { it.run() }).section(app) { null }

    private fun info(s: SettingsSection, title: String) = s.items.filterIsInstance<SettingsItem.Info>().single { it.title() == title }

    @Test fun silentUpdatesSayYesWithTheLoadersUpdateService() {
        loader.values["sys.glassworks.update.version"] = "1"
        assertEquals("Yes: the boot image's root update service installs them", info(page(), "Silent updates").detail())
    }

    @Test fun onTheLoaderTheOldServicesVersionDoesntCount() {
        loader.values["sys.s1kiosk.update.version"] = "1"
        val row = info(page(), "Silent updates").detail()
        assertTrue(row, row.startsWith("No: this boot image has no root update service"))
    }

    @Test fun theRadioModeRowReadsTheLoadersSetting() {
        loader.values["persist.glassworks.radio_mode"] = "thread_router"
        loader.values["persist.s1kiosk.radio_mode"] = "zigbee_tcp"
        val radio = page().items.filterIsInstance<SettingsItem.Link>().single { it.title == "Radio mode" }
        assertEquals("Thread router (repeater)", radio.detail)
    }
}
