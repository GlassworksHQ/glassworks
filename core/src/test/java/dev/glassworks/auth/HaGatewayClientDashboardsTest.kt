/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.auth

import dev.glassworks.dashboard.Dashboard
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Direct Lovelace dashboard fetch over Home Assistant's websocket API (`auth/current_user` +
 * `lovelace/dashboards/list`), matching the admin filtering in the kiosk web extension.
 */
class HaGatewayClientDashboardsTest {
    private class FakeHa(
        val token: String = "TOKEN",
        val isAdmin: Boolean = true,
        val listSuccess: Boolean = true,
    ) {
        val sent = ArrayList<JSONObject>()
        fun socket(): HaSocket = object : HaSocket {
            val out = ArrayDeque(listOf(JSONObject().put("type", "auth_required").toString()))
            override fun send(text: String) {
                val m = JSONObject(text).also { sent += it }
                when (m.optString("type")) {
                    "auth" -> out += JSONObject().put("type", if (m.optString("access_token") == token) "auth_ok" else "auth_invalid").toString()
                    "auth/current_user" -> out += result(m, true, JSONObject().put("name", "Alex").put("is_admin", isAdmin))
                    "lovelace/dashboards/list" -> out += result(
                        m,
                        listSuccess,
                        JSONArray()
                            .put(JSONObject().put("url_path", "tablet-home").put("title", "Tablet").put("require_admin", false))
                            .put(JSONObject().put("url_path", "admin-only").put("title", "Admin").put("require_admin", true))
                            .put(JSONObject().put("url_path", "energy").put("title", "Energy")),
                    )
                }
            }
            override fun receive(): String? = out.removeFirstOrNull()
            override fun close() {}
        }

        private fun result(m: JSONObject, ok: Boolean, value: Any?) =
            JSONObject().put("id", m.getInt("id")).put("type", "result").put("success", ok).put("result", value ?: JSONObject.NULL).toString()
    }

    private fun client(connect: (String) -> HaSocket) = HaGatewayClient(HaTokenClient(object : HttpPoster {
        override fun postForm(url: String, body: String) = error("not used")
        override fun get(url: String, maxBytes: Int) = error("not used")
    }), connect)

    @Test fun adminsSeeAllDashboardsIncludingAdminOnly() {
        val ha = FakeHa(isAdmin = true)
        val got = client { ha.socket() }.dashboards("http://homeassistant.local:8123", "TOKEN")
        assertEquals(
            listOf(Dashboard("tablet-home", "Tablet"), Dashboard("admin-only", "Admin"), Dashboard("energy", "Energy")),
            got,
        )
    }

    @Test fun nonAdminsDoNotSeeRequireAdminDashboards() {
        val ha = FakeHa(isAdmin = false)
        val got = client { ha.socket() }.dashboards("http://homeassistant.local:8123", "TOKEN")
        assertEquals(
            listOf(Dashboard("tablet-home", "Tablet"), Dashboard("energy", "Energy")),
            got,
        )
    }

    @Test fun failuresReturnNullWithoutThrowing() {
        assertNull(client { FakeHa(token = "OTHER").socket() }.dashboards("http://homeassistant.local:8123", "TOKEN"))
        assertNull(client { FakeHa(listSuccess = false).socket() }.dashboards("http://homeassistant.local:8123", "TOKEN"))
        assertNull(client { throw java.io.IOException("offline") }.dashboards("http://homeassistant.local:8123", "TOKEN"))
        assertNull(client { FakeHa().socket() }.dashboards("not-a-url", "TOKEN"))
    }
}
