/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.pane

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Wi-Fi row's checks (#660), the same on the panel's sheet and the Manage page: a name Wi-Fi can take, a password
 * within Wi-Fi's length, nothing while a change is under way, and never the password in an answer. Illustrative names.
 */
class WifiChangeChecksTest {
    private val joins = ArrayList<Pair<String, String>>()
    private var open = true

    private fun item(join: (String, String, (String?) -> Unit) -> Unit = { n, p, d -> joins += n to p; d(null) }) =
        SettingsItem.Wifi("Wi-Fi", { "Brightside Home" }, "hint", "Change", open = { open }, join = join)

    private fun wifi(network: String, password: String, i: SettingsItem.Wifi = item()): String? {
        var answer: String? = "no answer"
        SettingsChange.wifi(i, network, password) { answer = it }
        return answer
    }

    @Test fun aGoodNameAndPasswordJoinTrimmed() {
        assertNull(wifi("  Garden Shed ", "correct horse"))
        assertNull("an open network", wifi("Attic", ""))
        assertEquals(listOf("Garden Shed" to "correct horse", "Attic" to ""), joins)
    }

    @Test fun badNamesAndPasswordsNeverJoin() {
        assertEquals("Pick a network, or type its name.", wifi("  ", "x"))
        assertTrue(wifi("x".repeat(33), "x")!!.contains("at most 32"))
        assertTrue("32 bytes, not characters", wifi("é".repeat(17), "x")!!.contains("at most 32"))
        assertTrue(wifi("Garden\nShed", "x")!!.contains("can't take"))
        assertTrue(wifi("Garden Shed", "p".repeat(65))!!.contains("at most 64"))
        assertTrue(wifi("Garden Shed", "pass\u0000word")!!.contains("can't take"))
        assertTrue(joins.isEmpty())
    }

    @Test fun nothingWhileAChangeIsUnderWay() {
        open = false
        assertEquals(SettingsChange.WIFI_BUSY, wifi("Garden Shed", "x"))
        assertTrue(joins.isEmpty())
    }

    @Test fun aJoinThatThrowsNeverEchoesThePassword() {
        val answer = wifi("Garden Shed", "correct horse", item { _, p, _ -> error("bad $p") })
        assertEquals(SettingsChange.COULD_NOT_JOIN, answer)
        assertFalse(answer!!.contains("correct horse"))
    }

    @Test fun itIsAChangeableItemByItsTitle() {
        val s = SettingsSection("network", "Network", listOf(item()))
        assertEquals(listOf("Wi-Fi"), SettingsChange.changeable(s).map { it.first })
        assertEquals("a value-only change can't run it", SettingsChange.CANT_HERE, run { var a: String? = null; SettingsChange.change(item(), "x") { a = it }; a })
    }
}
