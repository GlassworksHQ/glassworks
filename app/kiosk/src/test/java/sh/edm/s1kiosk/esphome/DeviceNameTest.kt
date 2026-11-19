package sh.edm.s1kiosk.esphome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Each panel announces its own node name (#260, #503): two panels never share `s1-panel` again. */
class DeviceNameTest {
    @Test fun theNodeNameIsTheBoardAndTheMacsLastThreeBytes() {
        assertEquals("aqara-s1-plus-a1b2c3", DeviceName.node("02:00:00:A1:B2:C3"))
        assertEquals("aqara-s1-plus-a1b2c3", DeviceName.node("02:00:00:a1:b2:c3"))
    }

    /** #503's two panels: MACs ending 6a:49 and 35:2a both announced `s1-panel` on 0.4.3. */
    @Test fun twoPanelsGetTwoNames() {
        val first = DeviceName.node("02:11:22:33:6A:49")
        val second = DeviceName.node("02:11:22:44:35:2A")
        assertNotEquals(first, second)
        assertNotEquals("s1-panel", first)
    }

    @Test fun theNameIsAValidMdnsLabel() {
        val name = DeviceName.node("FE:DC:BA:98:76:54")!!
        assert(Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$").matches(name)) { name }
    }

    @Test fun notAMac() {
        for (bad in listOf("", "s1-panel", "02:00:00:A1:B2", "02-00-00-A1-B2-C3", "02:00:00:A1:B2:G3")) assertNull(bad, DeviceName.node(bad))
    }

    @Test fun theFriendlyNameIsTheFramesName() {
        assertEquals("Aqara S1 Plus", DeviceName.FRIENDLY)
        assertEquals("aqara-s1-plus", DeviceName.BOARD)
    }
}
