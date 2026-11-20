package dev.glassworks.s1.frame

import android.app.Application
import dev.glassworks.frame.AgentCapability
import dev.glassworks.frame.FrameRelease
import dev.glassworks.frame.RadioFile
import dev.glassworks.frame.RadioSetting
import dev.glassworks.frame.RadioStatus
import dev.glassworks.radio.SysProps
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The S1 app picks its root side's names from the boot image (#549, #139): the frame loader's Glassworks names when
 * `ro.glassworks.frame.id` is `aqara-s1-plus`, else 0.4.3's `s1kiosk` names. One choice, in [S1Names]; the agent's
 * parts all follow it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class S1NamesTest {
    private class Recording(frameId: String) : SysProps {
        val p = HashMap<String, String>().apply { if (frameId.isNotEmpty()) put(FrameRelease.ID_PROP, frameId) }
        val ops = ArrayList<String>()
        override fun get(name: String) = (p[name] ?: "").also { ops += "get $name" }
        override fun set(name: String, value: String) { ops += "set $name=$value"; p[name] = value }
        override fun peek(name: String) = p[name] ?: ""
        override fun sleep(ms: Long) {}
    }

    private val loader get() = Recording("aqara-s1-plus")

    @Test fun theSetFollowsTheFrameIdTheLoaderSets() {
        assertSame(S1Names.OLD, S1Names.of { "" })
        assertSame(S1Names.LOADER, S1Names.of { if (it == FrameRelease.ID_PROP) "aqara-s1-plus" else "" })
        assertSame(S1Names.LOADER, S1Names.of { if (it == FrameRelease.ID_PROP) " aqara-s1-plus\n" else "" })
        assertSame("another frame's id is not the S1's loader", S1Names.OLD, S1Names.of { if (it == FrameRelease.ID_PROP) "lululemon-mirror" else "" })
        assertSame(S1Names.LOADER, S1Agent(loader).names)
        assertSame(S1Names.OLD, S1Agent(Recording("")).names)
    }

    @Test fun onTheLoaderTheRadioUsesTheAgentsNames() {
        val r = loader
        val radio = S1Agent(r).radio
        radio.state(); radio.version(); radio.restart()
        for (s in RadioStatus.entries) radio.status(s)
        for (s in RadioSetting.entries) radio.set(s, "x")
        assertEquals(
            listOf("get init.svc.glassworks_agent", "get sys.glassworks.agent.version", "set ctl.restart=glassworks_agent") +
                listOf("status", "allowed", "peer", "thread_stored", "thread_leave", "zigbee", "fw", "fw_targets").map { "get sys.glassworks.radio.$it" } +
                listOf("get sys.glassworks.agent.source", "get sys.glassworks.bundle.state") +
                listOf("radio_mode", "radio_zigbee", "radio_allow").map { "set persist.glassworks.$it=x" },
            r.ops,
        )
        assertEquals("the app's own files keep their paths", "radio/thread-dataset", radio.file(RadioFile.THREAD_DATASET))
    }

    @Test fun onTheLoaderUpdatesTimeZoneAndBundleUseTheAgentsNames() {
        val r = loader
        r.p["sys.glassworks.update.status"] = "error: x"
        val agent = S1Agent(r)
        agent.appUpdates.install("1.2.3", installing = {}, over = { true })
        agent.timeZone.want("Etc/UTC"); agent.timeZone.status(); agent.timeZone.version()
        agent.bundle.request()
        assertEquals(
            listOf(
                "set sys.glassworks.update.status=requested", "set ctl.start=glassworks_update",
                "set persist.glassworks.timezone=Etc/UTC", "get sys.glassworks.timezone.status", "get sys.glassworks.agent.version",
                "set sys.glassworks.bundle.status=requested", "set ctl.start=glassworks_bundle",
            ),
            r.ops,
        )
        assertEquals("frame/bundle-in", agent.bundle.stageDir)
        assertEquals("GLASSWORKS-BUNDLE 1", agent.bundle.format)
        assertTrue(S1Names.LOADER.diagnostics.containsAll(listOf("sys.glassworks.agent.version", "sys.glassworks.net.status")))
    }

    @Test fun onTheLoaderTheCapabilitiesReadTheAgentsNames() {
        val r = loader
        r.p["init.svc.glassworks_agent"] = "running"
        r.p["sys.glassworks.agent.version"] = "5"
        r.p["sys.glassworks.update.version"] = "1"
        r.p["sys.glassworks.portal.status"] = "off"
        r.p["sys.glassworks.hotspot_name.status"] = "glassworks"
        val dir = Files.createTempDirectory("s1-loader").toFile()
        val state = File(dir, "frame-bundle").apply { writeText("ok 1.0.0 1000000 -\n") }
        val bt = File(dir, "bluetooth-address").apply { writeText("00:00:5E:00:53:01\n") }
        val bundle = dev.glassworks.radio.PropsBundle(r, "glassworks_bundle", "sys.glassworks.bundle.status", "frame/bundle-in", state, { 0 }, "GLASSWORKS-BUNDLE 1")
        val agent = S1Agent(r, bundle = bundle, bluetoothFile = bt)
        assertEquals(5, agent.version())
        for (c in S1Agent.DECLARED) assertTrue("$c on the loader image", agent.offers(c))
        assertFalse(agent.offers(AgentCapability.LOADER_INSTALL))
        assertEquals("the loader's own Bluetooth file", "/data/glassworks-pub/bluetooth-address", S1Names.LOADER.bluetoothFile)
    }

    @Test fun onTheLoaderNothingFromTheOldNamesCounts() {
        val r = loader
        for (k in S1Names.OLD.diagnostics + listOf("init.svc.s1kiosk_radio", "sys.s1kiosk.update.version", "sys.s1kiosk.bundle.loader")) r.p[k] = "1"
        r.p["init.svc.s1kiosk_radio"] = "running"
        val agent = S1Agent(r, bluetoothFile = File("/nonexistent"))
        assertNull(agent.version())
        assertEquals(emptyList<AgentCapability>(), AgentCapability.entries.filter(agent::offers))
    }

    /** The 0.4.3 set is the names the app used before the loader (main before #641). */
    @Test fun theOldSetIsTheNamesBefore() {
        val o = S1Names.OLD
        assertEquals(
            listOf(
                "s1kiosk_bundle", "sys.s1kiosk.bundle.status", "radio/bundle-in", "/data/s1kiosk-pub/radio-bundle", "S1RADIO-BUNDLE 1",
                "sys.s1kiosk.bundle.loader=1", "s1kiosk_update", "sys.s1kiosk.update.status", "sys.s1kiosk.update.version",
                "persist.s1kiosk.timezone", "sys.s1kiosk.timezone.status", "sys.s1kiosk.portal.status", "sys.s1kiosk.hotspot_name.status",
                "/data/s1kiosk-pub/bluetooth-address",
            ),
            listOf(
                o.bundleService, o.bundleStatus, o.bundleStage, o.bundleState, o.bundleFormat, "${o.bundleMarker.first}=${o.bundleMarker.second}",
                o.updateService, o.updateStatus, o.updateVersion, o.timeZone, o.timeZoneStatus, o.portalStatus, o.hotspotNameStatus, o.bluetoothFile,
            ),
        )
        assertEquals(
            listOf("sys.s1kiosk.radio.status", "sys.s1kiosk.radio.version", "sys.s1kiosk.radio.fw", "sys.s1kiosk.net.status",
                "sys.s1kiosk.update.version", "sys.s1kiosk.timezone.status", "sys.s1kiosk.portal.status", "persist.s1kiosk.radio_mode",
                "persist.s1kiosk.radio_zigbee"),
            o.diagnostics,
        )
    }
}
