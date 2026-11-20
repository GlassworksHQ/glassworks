/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.auth

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The long-lived token a handoff's session gets (2026-11-19 panel run), over HA's websocket as HA 2026.11 has it
 * (`components/auth/__init__.py`): `auth/long_lived_access_token` makes it; signed in with it, `auth/refresh_tokens`
 * names it as the current one and `auth/delete_refresh_token` ends it.
 */
class HaGatewayClientLongLivedTest {
    /** A fake HA websocket: takes [token] only, and answers the three calls as HA does. */
    private class FakeHa(val token: String, val makes: String? = "LLAT-1", val closesOnDelete: Boolean = false) {
        val sent = ArrayList<JSONObject>()
        var deleted: String? = null
        fun socket(): HaSocket = object : HaSocket {
            val out = ArrayDeque<String>(listOf(JSONObject().put("type", "auth_required").toString()))
            override fun send(text: String) {
                val m = JSONObject(text).also { sent += it }
                when (m.optString("type")) {
                    "auth" -> out += JSONObject().put("type", if (m.optString("access_token") == token && deleted == null) "auth_ok" else "auth_invalid").toString()
                    "auth/long_lived_access_token" -> out += result(m, makes != null, makes)
                    "auth/refresh_tokens" -> out += result(m, true, JSONArray()
                        .put(JSONObject().put("id", "other").put("is_current", false))
                        .put(JSONObject().put("id", "mine").put("is_current", true)))
                    // HA deletes it, then closes this connection (it was signed in with that token): maybe before the reply.
                    "auth/delete_refresh_token" -> { deleted = m.optString("refresh_token_id"); if (!closesOnDelete) out += result(m, true, JSONObject()) }
                }
            }
            override fun receive(): String? = out.removeFirstOrNull()
            override fun close() {}
        }
        private fun result(m: JSONObject, ok: Boolean, value: Any?) =
            JSONObject().put("id", m.getInt("id")).put("type", "result").put("success", ok).put("result", value ?: JSONObject.NULL).toString()
    }

    private fun client(ha: FakeHa) = HaGatewayClient(HaTokenClient(object : HttpPoster {
        override fun postForm(url: String, body: String) = error("not used")
        override fun get(url: String, maxBytes: Int) = error("not used")
    })) { ha.socket() }

    @Test fun itMakesOneSignedInWithTheHandedToken() {
        val ha = FakeHa("HANDED")
        assertEquals("LLAT-1", client(ha).createLongLived("http://homeassistant.local:8123", "HANDED", "Glassworks Manage on x", 1))
        val ask = ha.sent.single { it.optString("type") == "auth/long_lived_access_token" }
        assertEquals(1, ask.getInt("lifespan"))
        assertEquals("Glassworks Manage on x", ask.getString("client_name"))
        assertNull("HA refuses the handed token: none", client(FakeHa("OTHER")).createLongLived("http://homeassistant.local:8123", "HANDED", "n", 1))
        assertNull("HA won't make one: none", client(FakeHa("HANDED", makes = null)).createLongLived("http://homeassistant.local:8123", "HANDED", "n", 1))
    }

    @Test fun itEndsItselfSignedInWithItself() {
        val ha = FakeHa("LLAT-1")
        assertTrue(client(ha).endLongLived("http://homeassistant.local:8123", "LLAT-1"))
        assertEquals("the current one, no other", "mine", ha.deleted)
        assertTrue("one HA no longer takes is as good as ended", client(FakeHa("SOMETHING-ELSE")).endLongLived("http://homeassistant.local:8123", "LLAT-1"))
    }

    @Test fun aConnectionHaClosesRightAfterTheDeleteIsntConfirmedThere() {
        val ha = FakeHa("LLAT-1", closesOnDelete = true)
        assertFalse("no reply on that connection: not confirmed yet", client(ha).endLongLived("http://homeassistant.local:8123", "LLAT-1"))
        assertEquals("mine", ha.deleted)
    }

    @Test fun theSecondTryThenFindsItGoneAndCountsItEnded() {
        val ha = FakeHa("LLAT-1", closesOnDelete = true)
        val signIn = dev.glassworks.manage.HaSignIn(client(ha))
        assertTrue(signIn.end(dev.glassworks.manage.HaSignIn.Kept("http://homeassistant.local:8123", "", "LLAT-1", longLived = true)))
        assertEquals("deleted on the first try", "mine", ha.deleted)
        assertEquals("then a second sign-in, which HA refuses: ended", 2, ha.sent.count { it.optString("type") == "auth" })
    }

    @Test fun noAnswerIsNotEnded() {
        val silent = HaGatewayClient(HaTokenClient(object : HttpPoster {
            override fun postForm(url: String, body: String) = error("not used")
            override fun get(url: String, maxBytes: Int) = error("not used")
        })) { throw java.io.IOException("unreachable") }
        assertFalse(silent.endLongLived("http://homeassistant.local:8123", "LLAT-1"))
        assertNull(silent.createLongLived("http://homeassistant.local:8123", "HANDED", "n", 1))
    }
}
