/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.web

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The panel's copy of Home Assistant's `localStorage` (#881): kept across app starts, one origin, no credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HaPageStorageTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val ha = "http://homeassistant.local:8123"

    @Test
    fun theCopyOutlivesTheAppAndIsOnlyForItsOrigin() {
        assertTrue(HaPageStorage.of(app).put("$ha/lovelace/0?external_auth=1", """{"thc_snooze":"{\"h_1\":99}","dockedSidebar":"\"always_hidden\""}"""))
        // The next app start: a new instance over the same preferences.
        val again = HaPageStorage(app.getSharedPreferences("ha_page_storage", Context.MODE_PRIVATE))
        val items = again.items("$ha/")!!
        assertEquals("""{"h_1":99}""", items.getString("thc_snooze"))
        assertEquals("\"always_hidden\"", items.getString("dockedSidebar"))
        assertNull("another Home Assistant has no copy", again.items("http://192.0.2.10:8123"))
        assertNull(again.items(null))
    }

    @Test
    fun anotherOriginReplacesTheCopy() {
        val s = HaPageStorage.of(app)
        s.put(ha, """{"a":"1"}""")
        s.put("http://192.0.2.10:8123/lovelace", """{"b":"2"}""")
        assertNull(s.items(ha))
        assertEquals("2", s.items("http://192.0.2.10:8123")!!.getString("b"))
    }

    @Test
    fun credentialsAreNeverKept() {
        val items = HaPageStorage.clean(
            JSONObject()
                .put("hassTokens", """{"access_token":"made-up"}""")
                .put("my_card_password", "x").put("apiSecret", "x").put("oauth_state", "x").put("SessionId", "x")
                .put("thc_kiosk", "1")
                .toString(),
        )!!
        assertEquals(setOf("thc_kiosk"), items.keys().asSequence().toSet())
    }

    @Test
    fun onlyStringsWithinTheLimitsAreKept() {
        val raw = JSONObject().put("n", 5).put("big", "x".repeat(HaPageStorage.MAX_VALUE + 1)).put("ok", "y")
        assertEquals(setOf("ok"), HaPageStorage.clean(raw.toString())!!.keys().asSequence().toSet())
        val many = JSONObject().apply { repeat(HaPageStorage.MAX_KEYS + 10) { put("k%04d".format(it), "v") } }
        assertEquals(HaPageStorage.MAX_KEYS, HaPageStorage.clean(many.toString())!!.length())
        val heavy = JSONObject().apply { repeat(20) { put("k$it", "x".repeat(HaPageStorage.MAX_VALUE - 10)) } }
        val kept = HaPageStorage.clean(heavy.toString())!!
        assertTrue(kept.keys().asSequence().sumOf { it.length + kept.getString(it).length } <= HaPageStorage.MAX_TOTAL)
        assertNull(HaPageStorage.clean("[1,2]"))
        assertFalse(HaPageStorage.of(app).put(ha, "not json"))
    }
}
