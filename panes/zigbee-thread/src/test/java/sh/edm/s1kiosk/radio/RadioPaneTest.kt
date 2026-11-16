package sh.edm.s1kiosk.radio

import android.content.Context
import dev.glassworks.platform.AppBuild
import android.app.Application
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PanelEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import dev.glassworks.esphome.ApiEntity
import dev.glassworks.esphome.DeviceIdentity
import dev.glassworks.esphome.ProtoMessage
import dev.glassworks.esphome.ProtoWriter
import sh.edm.s1kiosk.auth.HaAuth
import sh.edm.s1kiosk.auth.ThreadFetch
import java.net.InetAddress

/** A panel with a v4 root helper; `setprop` is stored, nothing runs. Documentation addresses only (RFC 5737). */
private class HelperProps : SysProps {
    val props = HashMap<String, String>().apply {
        this[RadioHelper.PROP_SVC_STATE] = "running"
        this[RadioHelper.PROP_VERSION] = RadioHelper.BUNDLE_VERSION.toString()
    }
    override fun get(name: String) = props[name] ?: ""
    override fun set(name: String, value: String) { props[name] = value }
    override fun sleep(ms: Long) {}
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RadioPaneTest {
    @get:Rule val tmp = TemporaryFolder()

    private val app = RuntimeEnvironment.getApplication()
    private val running = RunningRadio()
    private val props = HelperProps()
    private val allow = MemoryAllowStore()
    private var stored: String? = "thread_router"
    private var published = 0
    /** The controller's onChange: what makes the app publish its states to Home Assistant. */
    private var changed = 0
    /** 2026-11-15 14:40 UTC. */
    private var now = 1_794_753_600_000L

    private val pane by lazy {
        val ctx = PaneContext(
            app, { app.getSharedPreferences("radio-test", Context.MODE_PRIVATE) },
            DeviceIdentity(
                name = "test", friendlyName = "Test", macAddress = "02:00:00:00:00:01", esphomeVersion = "x",
                compilationTime = "x", manufacturer = "x", model = "x", voiceFeatures = 0,
            ),
            publishStates = { published++ }, emit = {}, entitiesChanged = {}, sendEvent = { _, _ -> },
            build = AppBuild("1.0.0", 10000, debug = false),
        )
        val controller = RadioController(
            props, load = { stored }, store = { stored = it }, inline = true, log = {},
            filesDir = tmp.root, allow = allow, clock = { now }, onChange = { changed++ },
        )
        RadioPane(ctx, running, panelIp = { null }, radio = controller)
    }

    private fun entity(objectId: String): ApiEntity = pane.entities().first { it.objectId == objectId }

    private fun command(objectId: String, value: String) =
        entity(objectId).command(ProtoMessage(ProtoWriter().string(2, value).toByteArray()))

    @After fun tearDown() = pane.stop()

    @Test fun onlyAnAuthenticatedHomeAssistantSetsWhoMayReachTheRadio() {
        pane.onEvent(PanelEvent.HomeAssistantSubscribed(InetAddress.getByName("192.0.2.10"), authenticated = false))
        assertNull(allow.haV4)

        pane.onEvent(PanelEvent.HomeAssistantSubscribed(InetAddress.getByName("192.0.2.11"), authenticated = true))
        assertEquals("192.0.2.11", allow.haV4)
        assertEquals("192.0.2.11", props.get(RadioHelper.PROP_ALLOW))
    }

    @Test fun theRadioModeSelectGoesToTheController() {
        assertEquals(RadioMode.THREAD_ROUTER, pane.radio.mode)

        command("radio_mode", RadioMode.OFF.label)

        assertEquals(RadioMode.OFF, pane.radio.mode)
        assertEquals("off", stored)
    }

    @Test fun allowedClientsGoToTheControllerAndArePublished() {
        command("radio_allowed_clients", "198.51.100.23")

        assertEquals("198.51.100.23", pane.radio.allowedClients())
        assertEquals("198.51.100.23", allow.explicit)
        assertEquals(1, published)
    }

    /** The coordinator, Home Assistant last seen at 192.0.2.20, the panel's clock in UTC. */
    private fun connectedNow(peer: String?, mode: String = "zigbee_tcp"): String {
        stored = mode
        allow.haV4 = "192.0.2.20"
        peer?.let { props.props[RadioHelper.PROP_PEER] = it }
        val zone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        try { return entity("radio_connected").shownState().orEmpty() } finally { java.util.TimeZone.setDefault(zone) }
    }

    @Test fun connectedNowNamesHomeAssistantAndSaysSinceWhen() {
        // 2026-11-15 14:32:10 UTC
        assertEquals("Home Assistant · 192.0.2.20 · since 14:32", connectedNow("192.0.2.20 1794753130"))
    }

    @Test fun connectedNowShowsAnotherClientByItsAddressAndTheDayWhenNotToday() {
        // 2026-11-14 09:05 UTC, an IPv6 client as the helper writes it
        assertEquals("2001:db8::20 · since 14 Nov 09:05", connectedNow("2001:db8:0:0:0:0:0:20 1794647100"))
        assertEquals("The panel itself · 127.0.0.1 · since 14 Nov 09:05", connectedNow("127.0.0.1 1794647100"))
    }

    @Test fun connectedNowSaysNobodyAndWhenItDoesNotKnow() {
        assertEquals("Nobody", connectedNow("none"))
        props.props.remove(RadioHelper.PROP_PEER)
        assertEquals("a helper from before #217", "Not known (the root helper doesn't report it yet)", connectedNow(null))
        assertEquals("Not known (the root helper doesn't report it yet)", connectedNow("192.0.2.20 soon"))
    }

    @Test fun onlyTheCoordinatorHasAClient() {
        assertEquals("Only the Zigbee coordinator takes a client", connectedNow("192.0.2.20 1794753130", mode = "thread_router"))
    }

    @Test fun aNewClientIsPublishedToHomeAssistantOnTheNextRefresh() {
        connectedNow("none")
        pane.radio.refresh()
        val before = changed
        props.props[RadioHelper.PROP_PEER] = "198.51.100.7 1794753130"
        pane.radio.refresh()
        assertEquals(before + 1, changed)
        pane.radio.refresh()
        assertEquals("nothing new, nothing published", before + 1, changed)
    }

    @Test fun whileItRunsTheAppReachesTheControllerAndThreadJoinDeliversToIt() {
        pane.start()
        assertSame(pane.radio, running.controller)
        assertNotNull(HaAuth.threadJoin.deliver)

        val before = published
        HaAuth.threadJoin.arm()
        HaAuth.threadJoin.disarm()
        assertEquals("a Thread join change is published", before + 2, published)

        pane.stop()
        assertNull(running.controller)
        assertNull(HaAuth.threadJoin.deliver)
    }

    @Test fun stopLeavesAThreadJoinDeliveryItDidNotSet() {
        val other: (ThreadFetch.Ok) -> String? = { null }
        HaAuth.threadJoin.deliver = other
        try {
            pane.stop()
            assertSame(other, HaAuth.threadJoin.deliver)
        } finally {
            HaAuth.threadJoin.deliver = null
        }
    }
}
