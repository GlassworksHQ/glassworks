package sh.edm.s1kiosk.settings

import android.app.Application
import android.view.View
import dev.glassworks.bluetoothproxy.BluetoothProxyModel
import dev.glassworks.bluetoothproxy.ProxyConnections
import dev.glassworks.bluetoothproxy.ProxyPage
import dev.glassworks.bluetoothproxy.ProxyPhase
import dev.glassworks.pane.SettingsItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.glassworks.esphome.bluetoothproxy.ProxyStatus
import sh.edm.s1kiosk.ui.Mockup

/** The page says what the proxy does, and its buttons do what they say. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class BluetoothProxyPageTest {
    private class Model(var phase: ProxyPhase, var status: ProxyStatus? = null, var bluetoothOk: Boolean = true) : BluetoothProxyModel {
        val asked = ArrayList<String>()
        override fun phase() = phase
        override fun status() = status
        override fun turnBluetoothOn() = bluetoothOk.also { asked += "bluetooth" }
        override fun turnLocationOn() = true.also { asked += "location" }
        override var connect = false
        var links: ProxyConnections? = null
        override fun connections() = links
    }

    private fun page(m: Model): View {
        val a = Mockup.activity()
        val host = object : SettingsItem.NativeHost {
            override val context = a
            override fun addLayer(layer: View) {}
            override fun reveal(field: View) {}
            override fun hideKeyboard() {}
        }
        return BluetoothProxyPage(host, m).view.also { a.setContentView(it) }
    }

    private fun shown(root: View, text: String) = Mockup.label(root, text).let { it.isShown }

    @Test fun listeningShowsTheNumbersHomeAssistantGets() {
        val v = page(Model(ProxyPhase.LISTENING, ProxyStatus(true, true, null, 412, 37)))
        assertTrue(shown(v, "Listening for Home Assistant · 37 devices nearby"))
        assertTrue(shown(v, "412 a minute"))
        assertTrue(shown(v, "Listening"))
    }

    @Test fun withoutThePanelsKeyThePageSaysWhy() {
        val v = page(Model(ProxyPhase.NEEDS_KEY, ProxyStatus(false, false, null, 0, needsKey = true)))
        assertTrue(shown(v, "Waiting for Home Assistant: it needs this panel's encryption key. Home Assistant 2026.11 and later sets it when it adds the panel."))
        assertTrue(shown(v, "Waiting for the panel's key"))
    }

    @Test fun bluetoothOffOffersToTurnItOn() {
        val m = Model(ProxyPhase.BLUETOOTH_OFF)
        val v = page(m)
        Mockup.label(v, "Turn on Bluetooth").performClick()
        assertEquals(listOf("bluetooth"), m.asked)
    }

    @Test fun whenThePanelWontTurnItOnThePageSaysSo() {
        val m = Model(ProxyPhase.BLUETOOTH_OFF, bluetoothOk = false)
        val v = page(m)
        Mockup.label(v, "Turn on Bluetooth").performClick()
        assertTrue(shown(v, "This panel doesn't let apps do that. Turn it on in the panel's own settings."))
    }

    @Test fun locationOffOffersToTurnItOn() {
        val m = Model(ProxyPhase.LOCATION_OFF)
        Mockup.label(page(m), "Turn on location").performClick()
        assertEquals(listOf("location"), m.asked)
    }

    @Test fun offShowsTheEntitiesUnavailable() {
        val v = page(Model(ProxyPhase.OFF))
        assertTrue(shown(v, "The Bluetooth proxy is off. The panel doesn't scan, and Home Assistant no longer hears devices through it."))
        assertEquals(2, all(v).count { it.text == "Unavailable" && it.isShown })
    }

    /**
     * #269: the page's typed twin (the Manage page's) says what the panel's page shows, in every phase:
     * each of its words is on screen there, and each word the panel shows is in the twin.
     */
    @Test fun theTwinSaysWhatThePageSaysInEveryPhase() {
        for (phase in ProxyPhase.entries) {
            val m = Model(phase, ProxyStatus(phase == ProxyPhase.LISTENING, phase != ProxyPhase.OFF, null, 412, 37, lastHour = listOf(3, 9, 4), retryInMs = 4000))
            val v = page(m)
            val twin = ProxyPage.twin(m)
            val words = twin.flatMap { i ->
                when (i) {
                    is SettingsItem.Status -> listOf(i.text())
                    is SettingsItem.Info -> listOf(i.title(), i.detail())
                    is SettingsItem.Action -> listOf(i.title, i.detail(), i.label)
                    is SettingsItem.Tiles -> i.tiles().flatMap { listOf(it.name, it.value, it.unit, it.detail) }
                    is SettingsItem.Choice -> listOf(i.title) + i.options().flatMap { (id, name) -> listOf(name, i.line!!(id)) }
                    is SettingsItem.Note -> listOf(i.text())
                    is SettingsItem.States -> listOf(i.title) + i.rows().flatMap { listOf(it.name, it.value) }
                    else -> error("$phase: no ${i::class.simpleName} on this page")
                }
            }.filter { it.isNotEmpty() }
            for (w in words) assertTrue("$phase: the panel shows \"$w\"", all(v).any { it.text.toString() == w && it.isShown })
            val panel = all(v).filter { it.isShown && it.text.isNotEmpty() }.map { it.text.toString() } - setOf(ProxyPage.ADS_UNIT)
            for (w in panel) assertTrue("$phase: the twin has \"$w\"", w in words)
        }
    }

    @Test fun theTwinsButtonTurnsBluetoothOnOrSaysWhyNot() {
        val m = Model(ProxyPhase.BLUETOOTH_OFF, bluetoothOk = false)
        val button = ProxyPage.twin(m).filterIsInstance<SettingsItem.Action>().single()
        assertEquals("Turn on Bluetooth", button.label)
        var answer: String? = "none"
        button.run { answer = it }
        assertEquals(listOf("bluetooth"), m.asked)
        assertEquals(ProxyPage.REFUSED, answer)
        m.bluetoothOk = true
        button.run { answer = it }
        assertEquals(null, answer)
        val loc = Model(ProxyPhase.LOCATION_OFF)
        ProxyPage.twin(loc).filterIsInstance<SettingsItem.Action>().single().run { answer = it }
        assertEquals(listOf("location"), loc.asked)
        assertTrue("no button where the app can't fix it", ProxyPage.twin(Model(ProxyPhase.NO_PERMISSION)).none { it is SettingsItem.Action })
    }

    @Test fun listenOnlyIsTheDefaultAndItsTileSaysSo() {
        val v = page(Model(ProxyPhase.LISTENING, ProxyStatus(true, true, null, 412, 37)))
        assertTrue(shown(v, "Connections"))
        assertTrue(shown(v, "None in listen only"))
        assertTrue("the picked row", Mockup.label(v, "Listen only").let { (it.parent.parent as View).isSelected })
    }

    @Test fun pickingListenAndConnectTurnsTheConnectionsOn() {
        val m = Model(ProxyPhase.LISTENING, ProxyStatus(true, true, null, 412, 37))
        val v = page(m)
        (Mockup.label(v, "Listen and connect").parent.parent as View).performClick()
        assertTrue(m.connect)
        assertTrue("picked now", (Mockup.label(v, "Listen and connect").parent.parent as View).isSelected)
        assertTrue(shown(v, "of 3"))
        assertTrue(shown(v, "None in use"))
        (Mockup.label(v, "Listen only").parent.parent as View).performClick()
        assertFalse(m.connect)
    }

    @Test fun theConnectionsTileCountsTheLinksInUse() {
        val m = Model(ProxyPhase.LISTENING, ProxyStatus(true, true, null, 412, 37)).apply { connect = true; links = ProxyConnections(1, 3) }
        val v = page(m)
        assertTrue(shown(v, "1"))
        assertTrue(shown(v, "of 3"))
        assertTrue(shown(v, "In use now"))
    }

    @Test fun theModeIsOfferedOnlyWhileTheProxyIsOn() {
        assertFalse(shown(page(Model(ProxyPhase.OFF)), "Listen and connect"))
        assertFalse(shown(page(Model(ProxyPhase.NO_BLE)), "Listen and connect"))
        assertTrue(shown(page(Model(ProxyPhase.WAITING)), "Listen and connect"))
    }

    @Test fun theTwinsModeSavesThePick() {
        val m = Model(ProxyPhase.LISTENING)
        val mode = ProxyPage.twin(m).filterIsInstance<SettingsItem.Choice>().single()
        assertEquals(ProxyPage.LISTEN, mode.value())
        var answer: String? = "none"
        mode.save(ProxyPage.CONNECT) { answer = it }
        assertTrue(m.connect)
        assertEquals(null, answer)
        assertEquals(ProxyPage.CONNECT, mode.value())
    }

    private fun all(root: View): List<android.widget.TextView> = buildList {
        fun walk(x: View) {
            if (x is android.widget.TextView) add(x)
            if (x is android.view.ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        walk(root)
    }
}
