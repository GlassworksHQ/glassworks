package dev.glassworks.radio

import dev.glassworks.frame.RadioFile
import dev.glassworks.frame.RadioSetting
import dev.glassworks.frame.RadioStatus
import dev.glassworks.s1.frame.S1Agent
import dev.glassworks.s1.frame.S1TimeZone
import dev.glassworks.testing.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The radio and time zone panes ask the frame agent now (#641), and the S1's agent answers with the names the panes
 * used before: every request and read lands on the same property, file or service as on main before #641.
 */
class S1RadioNamesTest {
    private class Recording : SysProps {
        val p = HashMap<String, String>()
        val ops = ArrayList<String>()
        override fun get(name: String) = (p[name] ?: "").also { ops += "get $name" }
        override fun set(name: String, value: String) { ops += "set $name=$value"; p[name] = value }
        override fun peek(name: String) = (p[name] ?: "").also { ops += "peek $name" }
        override fun sleep(ms: Long) {}
    }

    @Test fun theRadioPanesTestsRunOnTheseNames() {
        val app = RepoFiles.source("dev/glassworks/radio/RadioHelper.kt")
        val pane = RepoFiles.source("dev/glassworks/radio/RadioHelper.kt", sourceSet = "test")
        assertEquals("panes/zigbee-thread's test copy of the S1's names", app.readText(), pane.readText())
    }

    @Test fun eachReadIsTheSamePropertyAsBefore() {
        val before = mapOf(
            RadioStatus.MODE to "sys.s1kiosk.radio.status",
            RadioStatus.ALLOWED to "sys.s1kiosk.radio.allowed",
            RadioStatus.PEER to "sys.s1kiosk.radio.peer",
            RadioStatus.THREAD_STORED to "sys.s1kiosk.radio.thread_stored",
            RadioStatus.THREAD_LEAVE to "sys.s1kiosk.radio.thread_leave",
            RadioStatus.ZIGBEE to "sys.s1kiosk.radio.zigbee",
            RadioStatus.FIRMWARE to "sys.s1kiosk.radio.fw",
            RadioStatus.FIRMWARE_TARGETS to "sys.s1kiosk.radio.fw_targets",
            RadioStatus.SOURCE to "sys.s1kiosk.radio.source",
            RadioStatus.BUNDLE to "sys.s1kiosk.radio.bundle",
        )
        for ((s, name) in before) {
            val r = Recording()
            val radio = S1Agent(r).radio
            radio.status(s); radio.peek(s)
            assertEquals("$s", listOf("get $name", "peek $name"), r.ops)
        }
    }

    @Test fun eachRequestIsTheSamePropertyAsBefore() {
        val before = mapOf(
            RadioSetting.MODE to "persist.s1kiosk.radio_mode",
            RadioSetting.ZIGBEE_STACK to "persist.s1kiosk.radio_zigbee",
            RadioSetting.ALLOW to "persist.s1kiosk.radio_allow",
        )
        for ((s, name) in before) {
            val r = Recording()
            val radio = S1Agent(r).radio
            radio.setting(s); radio.set(s, "x")
            assertEquals("$s", listOf("get $name", "set $name=x"), r.ops)
        }
    }

    @Test fun theServiceVersionAndFilesAreTheSameAsBefore() {
        val r = Recording()
        val agent = S1Agent(r)
        agent.radio.state(); agent.radio.version(); agent.radio.restart(); agent.radio.vendorState("zigbee_cpcd")
        assertEquals(
            listOf("get init.svc.s1kiosk_radio", "get sys.s1kiosk.radio.version", "set ctl.restart=s1kiosk_radio", "get init.svc.zigbee_cpcd"),
            r.ops,
        )
        assertEquals("s1kiosk_radio", agent.radio.name)
        assertEquals(
            mapOf(
                RadioFile.THREAD_DATASET to "radio/thread-dataset",
                RadioFile.THREAD_LEAVE to "radio/thread-leave",
                RadioFile.FIRMWARE_REQUEST to "radio/fw-request",
                RadioFile.ZIGBEED to "radio/zigbeed-8.2.2",
            ),
            RadioFile.entries.associateWith(agent.radio::file),
        )
    }

    /** Off without the helper: Aqara's services stopped consumers first, as the radio pane did itself before. */
    @Test fun offWithoutTheHelperStopsAqarasServicesInTheSameOrder() {
        val r = Recording()
        for (s in AqaraRadio.ALL) r.p["init.svc.$s"] = "running"
        val stopped = object : SysProps by r {
            override fun set(name: String, value: String) {
                r.set(name, value)
                if (name == "ctl.stop") r.p["init.svc.$value"] = "stopped"
            }
        }
        assertEquals(emptyList<String>(), S1Agent(stopped).radio.applyVendor(RadioMode.OFF.id) {})
        assertEquals(
            listOf("ha_matter", "zigbee_agent", "zigbee_host", "zigbee_zigbeed", "zigbee_socat", "zigbee_cpcd").map { "set ctl.stop=$it" },
            r.ops.filter { it.startsWith("set ") },
        )
    }

    @Test fun theBundleAndTheTimeZoneAreTheSameAsBefore() {
        val r = Recording()
        val agent = S1Agent(r)
        agent.bundle.request(); agent.bundle.running(); agent.bundle.status()
        val tz = S1TimeZone(r)
        tz.wanted(); tz.want("Etc/UTC"); tz.status(); tz.current(); tz.version()
        assertEquals(
            listOf(
                "set sys.s1kiosk.bundle.status=requested", "set ctl.start=s1kiosk_bundle", "get init.svc.s1kiosk_bundle", "get sys.s1kiosk.bundle.status",
                "get persist.s1kiosk.timezone", "set persist.s1kiosk.timezone=Etc/UTC", "get sys.s1kiosk.timezone.status", "get persist.sys.timezone",
                "get sys.s1kiosk.radio.version",
            ),
            r.ops,
        )
        assertEquals("radio/bundle-in", agent.bundle.stageDir)
        assertEquals("/data/s1kiosk-pub/radio-bundle", RadioHelper.BUNDLE_STATE_FILE)
    }
}
