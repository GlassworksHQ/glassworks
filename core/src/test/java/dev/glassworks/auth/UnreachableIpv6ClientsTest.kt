package dev.glassworks.auth

import dev.glassworks.net.HappyEyeballs
import dev.glassworks.testing.UnreachableIpv6
import dev.glassworks.web.HttpResponse
import dev.glassworks.web.HttpServer
import dev.glassworks.web.WebSocketStreams
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Every native client the app talks to Home Assistant with, on [UnreachableIpv6]'s network: the name resolves to an
 * IPv6 address that never answers ahead of the IPv4 one. Each must answer as fast as on a plain IPv4 network.
 */
class UnreachableIpv6ClientsTest {
    private val ha = HttpServer(0, ::handle, bindAddress = InetAddress.getByName("127.0.0.1")).apply { start() }
    private val base = "http://${UnreachableIpv6.HOST}:${ha.localPort}"
    private val connector = HappyEyeballs(UnreachableIpv6.resolve, connectOne = UnreachableIpv6.connectOne)
    private val tokens = HaTokenClient(UrlConnectionPoster(connector = connector))

    @After fun stop() = ha.stop()

    private fun handle(req: dev.glassworks.web.HttpRequest): HttpResponse = when (req.path) {
        "/auth/token" -> HttpResponse(200, mapOf("Content-Type" to "application/json"),
            """{"access_token":"AT","refresh_token":"RT","expires_in":1800,"token_type":"Bearer"}""".toByteArray())
        "/manifest.json" -> HttpResponse(200, mapOf("Content-Type" to "application/json"), """{"name":"Home Assistant"}""".toByteArray())
        "/api/websocket" -> HttpResponse.webSocket(WebSocketStreams.key(req)!!, 5_000) { ws ->
            fun say(json: String) = ws.output.run { write(json.toByteArray()); flush() }
            fun next(): JSONObject = ByteArray(4096).let { b -> JSONObject(String(b, 0, ws.input.read(b))) }
            say("""{"type":"auth_required","ha_version":"2026.11.0"}""")
            if (next().optString("access_token") != "AT") return@webSocket
            say("""{"type":"auth_ok"}""")
            val id = next().getInt("id")
            say("""{"id":$id,"type":"result","success":true,"result":{"name":"Alex","is_admin":true}}""")
        }
        else -> HttpResponse.text(404, "no")
    }

    private fun <T> fast(what: String, block: () -> T): T {
        val t0 = System.nanoTime()
        val r = block()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("$what took $ms ms", ms < UnreachableIpv6.FAST_MS)
        return r
    }

    @Test fun `the token exchange of a sign-in`() {
        val r = fast("exchangeCode") { tokens.exchangeCode(base, "http://panel/", "code", OAuth.newCodeVerifier()) }
        assertEquals("AT", (r as TokenResult.Ok).accessToken)
    }

    @Test fun `a token refresh`() {
        val r = fast("refresh") { tokens.refresh(StoredAuth(base, "http://panel/", "RT")) }
        assertEquals("AT", (r as TokenResult.Ok).accessToken)
    }

    @Test fun `revoke`() {
        assertTrue(fast("revoke") { tokens.revoke(StoredAuth(base, "http://panel/", "RT")) })
    }

    @Test fun `the discovery probe`() {
        val (status, body) = fast("get") { UrlConnectionPoster(timeoutMs = 5_000, connector = connector).get("$base/manifest.json") }
        assertEquals(200, status)
        assertTrue(body, body.contains("Home Assistant"))
    }

    @Test fun `who signed in, over the websocket`() {
        val gateway = HaGatewayClient(tokens) { url -> WsHaSocket.connect(url, connector) }
        val user = fast("currentUser") { gateway.currentUser(base, "AT") }
        assertEquals("Alex", user?.name)
    }
}
