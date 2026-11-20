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
import dev.glassworks.radio.RadioBundle
import dev.glassworks.radio.RadioController
import dev.glassworks.radio.RadioFirmware
import dev.glassworks.radio.RadioHelper
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

    /** Every service state and version the helper can show, against the radio pane's own checks. */
    @Test fun theRadioCapabilitiesAreTheRadioPanesOwnChecks() {
        for (svc in listOf("", "running", "stopped", "restarting")) for (v in listOf("", "1", "2", "3", "4", "5", " 4\n", "x")) {
            val props = Props().apply { p[RadioHelper.PROP_SVC_STATE] = svc; p[RadioHelper.PROP_VERSION] = v }
            val agent = S1Agent(props)
            val radio = RadioController(props, load = { null }, store = {}, inline = true)
            val at = "svc '$svc', version '$v'"
            assertEquals(at, radio.helperInstalled(), agent.offers(RADIO_MODE))
            assertEquals(at, if (radio.helperInstalled()) radio.helperVersion() else null, agent.version())
            assertEquals(at, radio.helperInstalled() && radio.helperVersion() >= RadioHelper.FIREWALL_VERSION, agent.offers(RADIO_FIREWALL))
            assertEquals(at, RadioFirmware(props, null).supported(), agent.offers(RADIO_FIRMWARE))
            assertEquals(at, radio.helperV4(), agent.offers(TIME_ZONE))
        }
    }

    /** An undo restores the boot image and keeps /data, so the bundle's state file outlives the loader. */
    @Test fun theBundleNeedsThisBootsLoaderMarkerAsWellAsTheFile() {
        val ok = RadioBundle.Installed("ok", 5, 5)
        val props = Props()
        assertFalse("the file alone", S1Agent(props, bundle = { ok }).offers(FRAME_BUNDLE))
        props.p[RadioHelper.PROP_BUNDLE_LOADER] = "1"
        assertFalse("the marker alone", S1Agent(props, bundle = { null }).offers(FRAME_BUNDLE))
        assertTrue("both", S1Agent(props, bundle = { ok }).offers(FRAME_BUNDLE))
    }

    /** After an undo (the stock boot image, /data kept): only the adapter's own address, which stays true, is offered. */
    @Test fun afterAnUndoOnlyTheBluetoothAddressIsOffered() {
        val bt = File(Files.createTempDirectory("s1-undo").toFile(), "bluetooth-address").apply { writeText("00:00:5E:00:53:01\n") }
        val agent = S1Agent(Props(), bundle = { RadioBundle.Installed("ok", 5, 5) }, bluetoothFile = bt)
        assertNull(agent.version())
        assertEquals(setOf(BLUETOOTH_ADDRESS), AgentCapability.entries.filter(agent::offers).toSet())
    }

    @Test fun noHelperNoVersion() {
        assertNull(S1Agent(Props()).version())
    }

}
