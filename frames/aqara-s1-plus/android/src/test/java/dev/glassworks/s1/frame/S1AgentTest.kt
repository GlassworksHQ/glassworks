package dev.glassworks.s1.frame

import android.app.Application
import dev.glassworks.frame.AgentCapability
import dev.glassworks.frame.AgentCapability.APP_UPDATES
import dev.glassworks.frame.AgentCapability.BLUETOOTH_ADDRESS
import dev.glassworks.frame.AgentCapability.FRAME_BUNDLE
import dev.glassworks.frame.AgentCapability.RADIO_FIREWALL
import dev.glassworks.frame.AgentCapability.RADIO_FIRMWARE
import dev.glassworks.frame.AgentCapability.RADIO_MODE
import dev.glassworks.frame.AgentCapability.TIME_ZONE
import dev.glassworks.radio.PropsBundle
import dev.glassworks.radio.RadioController
import dev.glassworks.radio.RadioFirmware
import dev.glassworks.radio.RadioHelper
import dev.glassworks.radio.RadioReports
import dev.glassworks.radio.SysProps
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The S1's agent answers exactly as the checks it replaced (#641): the same properties, the same values. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class S1AgentTest {
    private class Props : SysProps {
        val p = HashMap<String, String>()
        override fun get(name: String) = p[name] ?: ""
        override fun set(name: String, value: String) { p[name] = value }
    }

    @Test fun appUpdatesOnlyWithServiceVersionOneOrMore() {
        val props = Props()
        assertFalse(S1Agent(props).offers(APP_UPDATES))
        props.p["sys.s1kiosk.update.version"] = "0"
        assertFalse(S1Agent(props).offers(APP_UPDATES))
        props.p["sys.s1kiosk.update.version"] = "1"
        assertTrue(S1Agent(props).offers(APP_UPDATES))
    }

    @Test fun anUpdateVersionThatIsntANumberMeansNoService() {
        val props = Props()
        for (v in listOf("-1", "yes", "1.0", " v2 ", "1-rc")) {
            props.p["sys.s1kiosk.update.version"] = v
            assertFalse("'$v'", S1Agent(props).offers(APP_UPDATES))
        }
        props.p["sys.s1kiosk.update.version"] = " 3\n" // getprop's own whitespace
        assertTrue(S1Agent(props).offers(APP_UPDATES))
    }

    /**
     * Every service state and version the helper can show, against the checks the radio and time zone panes made on
     * the raw properties before they asked the agent (main before #641): installed when init knows the service, the
     * version 1 when it says none, the firewall from 3, firmware and the Zigbee stack from 4. The panes now ask the
     * agent, so they give the same answers.
     */
    @Test fun theRadioCapabilitiesAreTheChecksThePanesMadeBefore() {
        for (svc in listOf("", "running", "stopped", "restarting")) for (v in listOf("", "1", "2", "3", "4", "5", " 4\n", "x")) {
            val props = Props().apply { p["init.svc.s1kiosk_radio"] = svc; p["sys.s1kiosk.radio.version"] = v }
            val installed = svc.isNotEmpty()
            val version = v.trim().toIntOrNull() ?: 1
            val agent = S1Agent(props)
            val radio = RadioController(S1Agent(props), load = { null }, store = {}, inline = true)
            val at = "svc '$svc', version '$v'"
            assertEquals(at, installed, agent.offers(RADIO_MODE))
            assertEquals(at, if (installed) version else null, agent.version())
            assertEquals(at, installed && version >= 3, agent.offers(RADIO_FIREWALL))
            assertEquals(at, installed && version >= 4, agent.offers(RADIO_FIRMWARE))
            assertEquals(at, installed && version >= 4, agent.offers(TIME_ZONE))
            assertEquals(at, installed, radio.helperInstalled())
            assertEquals(at, version, radio.helperVersion())
            assertEquals(at, installed && version >= 4, radio.helperV4())
            assertEquals(at, installed && version >= 4, RadioFirmware(S1Agent(props), null).supported())
            assertEquals(at, v.trim().toIntOrNull(), agent.timeZone.version())
        }
    }

    /** An undo restores the boot image and keeps /data, so the bundle's state file outlives the loader. */
    @Test fun theBundleNeedsThisBootsLoaderMarkerAsWellAsTheFile() {
        val props = Props()
        assertFalse("the file alone", S1Agent(props, bundle = bundleWith(props, "ok 5 5\n")).offers(FRAME_BUNDLE))
        props.p[RadioHelper.PROP_BUNDLE_LOADER] = "1"
        assertFalse("the marker alone", S1Agent(props, bundle = bundleWith(props, null)).offers(FRAME_BUNDLE))
        assertTrue("both", S1Agent(props, bundle = bundleWith(props, "ok 5 5\n")).offers(FRAME_BUNDLE))
    }

    /** After an undo (the stock boot image, /data kept): only the adapter's own address, which stays true, is offered. */
    @Test fun afterAnUndoOnlyTheBluetoothAddressIsOffered() {
        val bt = File(Files.createTempDirectory("s1-undo").toFile(), "bluetooth-address").apply { writeText("00:00:5E:00:53:01\n") }
        val props = Props()
        val agent = S1Agent(props, bundle = bundleWith(props, "ok 5 5\n"), bluetoothFile = bt)
        assertNull(agent.version())
        assertEquals(setOf(BLUETOOTH_ADDRESS), AgentCapability.entries.filter(agent::offers).toSet())
    }

    @Test fun noHelperNoVersion() {
        assertNull(S1Agent(Props()).version())
    }

    /** The S1's bundle install with its state file holding [state] (none when null), owned by root. */
    private fun bundleWith(props: SysProps, state: String?): PropsBundle {
        val f = File(Files.createTempDirectory("s1-bundle").toFile(), "radio-bundle").apply { if (state != null) writeText(state) }
        return PropsBundle(props, RadioHelper.BUNDLE_SERVICE, RadioHelper.PROP_BUNDLE_STATUS, RadioHelper.BUNDLE_STAGE_DIR, f) { if (it.exists()) 0 else null }
    }

}
