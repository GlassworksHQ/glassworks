package sh.edm.s1kiosk.auth

import dev.glassworks.pane.BlueprintAccess
import dev.glassworks.pane.HaBlueprint
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * [HaBlueprintClient] against a scripted Home Assistant websocket that answers as Home Assistant 2026.11.1
 * does (`auth/current_user`, `blueprint/list`). It only reads: nothing but those two commands is sent.
 */
class HaBlueprintClientTest {

    private class FakeHa(private val admin: Boolean, private val list: String = "{}") : HaSocket {
        val sent = ArrayList<JSONObject>()
        private val inbox = ArrayDeque<String>().apply { add("""{"type":"auth_required","ha_version":"2026.11.1"}""") }
        var closed = false

        override fun send(text: String) {
            val m = JSONObject(text)
            sent += m
            if (m.optString("type") == "auth") {
                inbox.add(if (m.optString("access_token") == "AT") """{"type":"auth_ok"}""" else """{"type":"auth_invalid"}""")
                return
            }
            val id = m.getInt("id")
            inbox.add("""{"id":99,"type":"event","event":{}}""") // the client skips what isn't its answer
            inbox.add(when (m.getString("type")) {
                "auth/current_user" -> """{"id":$id,"type":"result","success":true,"result":{"id":"u1","name":"Kitchen Panel","is_owner":false,"is_admin":$admin}}"""
                "blueprint/list" -> if (admin) """{"id":$id,"type":"result","success":true,"result":$list}"""
                    else """{"id":$id,"type":"result","success":false,"error":{"code":"unauthorized"}}"""
                else -> """{"id":$id,"type":"result","success":false,"error":{"code":"unknown_command"}}"""
            })
        }

        override fun receive(): String? = inbox.removeFirstOrNull()
        override fun close() { closed = true }
    }

    @Test fun anAdministratorGetsTheBlueprintsHomeAssistantHas() {
        val ha = FakeHa(admin = true, list = """{
            "GlassworksHQ/doorbell-panels.yaml": {"metadata": {"name": "Doorbell on Glassworks panels", "domain": "automation",
              "source_url": "https://raw.githubusercontent.com/GlassworksHQ/glassworks/v0.5.0/panes/notifications/blueprints/doorbell-panels.yaml"}},
            "homeassistant/motion_light.yaml": {"metadata": {"name": "Motion-activated Light", "domain": "automation"}},
            "GlassworksHQ/ask-panels.yaml": {"error": "Invalid blueprint"}}""") // HA can't load it: not offered as there
        val urls = ArrayList<String>()
        val a = HaBlueprintClient { url -> urls += url; ha }.check("http://homeassistant.local:8123/lovelace/0", "AT")
        assertEquals(listOf("ws://homeassistant.local:8123/api/websocket"), urls)
        assertEquals(BlueprintAccess.Admin(listOf(
            HaBlueprint("GlassworksHQ/doorbell-panels.yaml", "https://raw.githubusercontent.com/GlassworksHQ/glassworks/v0.5.0/panes/notifications/blueprints/doorbell-panels.yaml"),
            HaBlueprint("homeassistant/motion_light.yaml", null),
        )).inHa.toSet(), (a as BlueprintAccess.Admin).inHa.toSet())
        assertEquals("reads only", listOf("auth", "auth/current_user", "blueprint/list"), ha.sent.map { it.getString("type") })
        assertEquals("automation", ha.sent.last().getString("domain"))
        assertTrue(ha.closed)
    }

    @Test fun someoneWhoIsntAnAdministratorIsNotAskedForTheList() {
        val ha = FakeHa(admin = false)
        assertEquals(BlueprintAccess.NotAdmin("Kitchen Panel"), HaBlueprintClient { ha }.check("http://homeassistant.local:8123", "AT"))
        assertEquals(listOf("auth", "auth/current_user"), ha.sent.map { it.getString("type") })
    }

    @Test fun aRefusedTokenOrNoHomeAssistantIsOffWithTheReason() {
        assertEquals(BlueprintAccess.Off(HaBlueprintClient.REFUSED), HaBlueprintClient { FakeHa(admin = true) }.check("http://homeassistant.local:8123", "old"))
        assertEquals(BlueprintAccess.Off(HaBlueprintClient.UNREACHABLE), HaBlueprintClient { throw IOException("no route") }.check("http://homeassistant.local:8123", "AT"))
        assertEquals(BlueprintAccess.Off(HaBlueprintClient.NOT_HTTP), HaBlueprintClient { FakeHa(admin = true) }.check("homeassistant.local", "AT"))
    }
}
