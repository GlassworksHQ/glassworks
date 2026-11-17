package sh.edm.s1kiosk.improv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.edm.s1kiosk.improv.ImprovDone.Mark
import sh.edm.s1kiosk.improv.ImprovDone.Via

/** The panel's done card follows how the Wi-Fi came (#460): Bluetooth only for Improv over BLE. */
class ImprovDoneTest {
    @Test
    fun `Improv over BLE keeps its Bluetooth mark and its words`() {
        assertEquals(
            ImprovDone.Card("Wi-Fi setup · done", "Connected to Example-Home", "Continue setup at http://192.0.2.10:8099/", Mark.BLUETOOTH),
            ImprovDone.card(Via.BLUETOOTH, "Example-Home", "http://192.0.2.10:8099/"),
        )
        assertEquals("Wi-Fi is set up.", ImprovDone.card(Via.BLUETOOTH, "", null).body)
    }

    @Test
    fun `every other way shows the Wi-Fi mark, and never mentions Bluetooth`() {
        for (via in listOf(Via.SETUP_PAGE, Via.COMPUTER, Via.OTHER)) {
            val c = ImprovDone.card(via, "Example-Home", null)
            assertEquals(via.name, Mark.WIFI, c.mark)
            assertFalse(via.name, listOf(c.label, c.title, c.body).any { "Bluetooth" in it || "Improv" in it })
        }
    }

    @Test
    fun `the setup page and Kiln say where the Wi-Fi came from`() {
        assertTrue(ImprovDone.card(Via.SETUP_PAGE, "Example-Home", null).body.contains("setup page on your phone"))
        assertTrue(ImprovDone.card(Via.COMPUTER, "Example-Home", null).body.contains("Kiln"))
        assertEquals("Panel is online", ImprovDone.card(Via.OTHER, null, null).title)
    }

    @Test
    fun `the done card follows the way the Wi-Fi was last sent in the session`() {
        val p = ImprovDone.JoinPath()
        p.sessionStarted()
        p.sent(Via.SETUP_PAGE, "Example-Home")
        assertEquals(ImprovDone.card(Via.SETUP_PAGE, "Example-Home", null), p.card(null))
        p.sent(Via.BLUETOOTH, "Example-Upstairs")
        assertEquals("the last one sent wins", ImprovDone.card(Via.BLUETOOTH, "Example-Upstairs", "http://192.0.2.10:8099/"), p.card("http://192.0.2.10:8099/"))
    }

    @Test
    fun `a new session forgets the last way, and a network from elsewhere is named by nobody`() {
        val p = ImprovDone.JoinPath()
        p.sent(Via.COMPUTER, "Example-Home")
        p.sessionStarted()
        val c = p.card(null)
        assertEquals(Mark.WIFI, c.mark)
        assertEquals("no stale name from an earlier join", "Panel is online", c.title)
        assertEquals(ImprovDone.card(Via.OTHER, null, null), c)
    }
}
