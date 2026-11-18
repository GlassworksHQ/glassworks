package dev.glassworks.provisioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.glassworks.provisioning.ImprovDone.Mark
import dev.glassworks.provisioning.ImprovDone.Via

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
        p.joined()
        p.sessionStarted()
        val c = p.card(null)
        assertEquals(Mark.WIFI, c.mark)
        assertEquals("no stale name from an earlier join", "Panel is online", c.title)
        assertEquals(ImprovDone.card(Via.OTHER, null, null), c)
    }

    @Test
    fun `a session that starts while an installer join is under way keeps that join's record`() {
        val p = ImprovDone.JoinPath()
        p.sent(Via.SETUP_PAGE, "Example-Home")
        p.sessionStarted() // Improv's boot check, mid-join
        assertEquals(ImprovDone.card(Via.SETUP_PAGE, "Example-Home", null), p.card(null))
        p.joined()
        p.sessionStarted() // a later session, once the join is over
        assertEquals(ImprovDone.card(Via.OTHER, null, null), p.card(null))
    }

    @Test
    fun `a failed join names no network, so Android going back to the old one isn't credited to it`() {
        val p = ImprovDone.JoinPath()
        p.sent(Via.BLUETOOTH, "Example-Wrong")
        p.failed(Via.BLUETOOTH)
        assertEquals(ImprovDone.card(Via.OTHER, null, null), p.card(null))
    }

    @Test
    fun `a Bluetooth join superseded by an installer join fails without wiping the installer's record`() {
        val p = ImprovDone.JoinPath()
        p.sent(Via.BLUETOOTH, "Example-Wrong")
        p.sent(Via.COMPUTER, "Example-Home")
        p.failed(Via.BLUETOOTH) // "superseded", just after
        assertEquals(ImprovDone.card(Via.COMPUTER, "Example-Home", null), p.card(null))
    }
}
