/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.conformance

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A Home Assistant on the device's loopback, for the engine contract: every page path answers with a small HTML page
 * (what a browser engine shows), and `/api/websocket` speaks enough of HA's websocket API for Vitro to draw [DASHBOARD]
 * (auth, config, the dashboard, entity states). Nothing of anyone's real Home Assistant; addresses are loopback only.
 *
 * One thread per connection; [stop] closes them all.
 */
class FakeHomeAssistant {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val open = Collections.synchronizedSet(HashSet<Socket>())
    @Volatile private var stopped = false

    /** `http://127.0.0.1:<port>`. */
    val origin = "http://127.0.0.1:${server.localPort}"

    /** Every page path a browser asked for, in order. */
    val pages = CopyOnWriteArrayList<String>()

    /** Every websocket command type, in order, and the ones this fake doesn't know. */
    val commands = CopyOnWriteArrayList<String>()
    val unknown = CopyOnWriteArrayList<String>()

    init {
        Thread({
            while (!stopped) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                open += s
                Thread({ serve(s) }, "fake-ha-conn").apply { isDaemon = true; start() }
            }
        }, "fake-ha-accept").apply { isDaemon = true; start() }
    }

    fun stop() {
        stopped = true
        runCatching { server.close() }
        synchronized(open) { open.toList() }.forEach { runCatching { it.close() } }
    }

    private fun serve(s: Socket) {
        try {
            s.soTimeout = 0
            val input = BufferedInputStream(s.getInputStream())
            val out = s.getOutputStream()
            val requestLine = readLine(input) ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val path = requestLine.split(' ').getOrNull(1).orEmpty()
            val key = headers["sec-websocket-key"]
            if (path.substringBefore('?') == "/api/websocket" && key != null) {
                websocket(key, input, out)
            } else {
                http(path, out)
            }
        } catch (e: Exception) {
            if (!stopped) Log.d(TAG, "connection ended: $e")
        } finally {
            open -= s
            runCatching { s.close() }
        }
    }

    // ---- HTTP: a page for every page path, nothing for files

    private fun http(path: String, out: OutputStream) {
        val p = path.substringBefore('?').substringBefore('#')
        val isFile = p.substringAfterLast('/').contains('.')
        if (isFile) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            return
        }
        pages += p
        val body = PAGE.replace("PATH", p.replace("<", "&lt;")).toByteArray()
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(),
        )
        out.write(body)
        out.flush()
    }

    // ---- websocket (RFC 6455): text frames, server side

    private fun websocket(key: String, input: InputStream, out: OutputStream) {
        val accept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray()), Base64.NO_WRAP,
        )
        out.write(
            ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(),
        )
        out.flush()
        val send = { o: JSONObject -> synchronized(out) { writeFrame(out, 1, o.toString().toByteArray()) } }
        send(JSONObject().put("type", "auth_required").put("ha_version", HA_VERSION))
        var authed = false
        while (!stopped) {
            val (op, payload) = readFrame(input) ?: return
            when (op) {
                8 -> { synchronized(out) { writeFrame(out, 8, ByteArray(0)) }; return }
                9 -> synchronized(out) { writeFrame(out, 10, payload) }
                1 -> {
                    val m = JSONObject(String(payload))
                    if (!authed) {
                        authed = m.optString("type") == "auth" && m.optString("access_token") == TOKEN
                        send(
                            if (authed) JSONObject().put("type", "auth_ok").put("ha_version", HA_VERSION)
                            else JSONObject().put("type", "auth_invalid").put("message", "Invalid access token"),
                        )
                        if (!authed) return
                    } else {
                        command(m, send)
                    }
                }
            }
        }
    }

    private fun command(m: JSONObject, send: (JSONObject) -> Unit) {
        val id = m.optInt("id", -1)
        val type = m.optString("type")
        commands += type
        fun ok(result: Any) = send(JSONObject().put("id", id).put("type", "result").put("success", true).put("result", result))
        fun event(e: JSONObject) = send(JSONObject().put("id", id).put("type", "event").put("event", e))
        when (type) {
            "ping" -> send(JSONObject().put("id", id).put("type", "pong"))
            "get_config" -> ok(JSONObject().put("location_name", "Conformance Home").put("time_zone", "UTC").put("version", HA_VERSION)
                .put("unit_system", JSONObject().put("temperature", "°C").put("length", "km")))
            "auth/current_user" -> ok(JSONObject().put("id", "conformance").put("name", "Conformance").put("is_admin", true).put("is_owner", false))
            "frontend/get_themes" -> ok(JSONObject().put("default_theme", "default").put("default_dark_theme", JSONObject.NULL).put("themes", JSONObject()))
            "frontend/get_user_data" -> ok(JSONObject().put("value", JSONObject.NULL))
            "frontend/subscribe_user_data" -> { ok(JSONObject.NULL); event(JSONObject().put("value", JSONObject.NULL)) }
            "subscribe_events", "unsubscribe_events", "unsubscribe_entities" -> ok(JSONObject.NULL)
            "lovelace/dashboards/list", "lovelace/resources", "config/device_registry/list", "config/area_registry/list" -> ok(JSONArray())
            "config/entity_registry/list_for_display" -> ok(JSONObject().put("entity_categories", JSONObject()).put("entities", JSONArray()))
            "lovelace/config" -> ok(JSONObject(DASHBOARD))
            "subscribe_entities" -> {
                ok(JSONObject.NULL)
                val all = JSONObject(ENTITIES)
                val wanted = m.optJSONArray("entity_ids")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                val initial = JSONObject()
                all.keys().forEach { k -> if (wanted == null || k in wanted) initial.put(k, all.get(k)) }
                event(JSONObject().put("a", initial))
            }
            "render_template" -> { ok(JSONObject.NULL); event(JSONObject().put("result", "")) }
            "call_service" -> ok(JSONObject().put("context", JSONObject().put("id", "conformance")))
            else -> {
                unknown += type
                send(JSONObject().put("id", id).put("type", "result").put("success", false)
                    .put("error", JSONObject().put("code", "unknown_command").put("message", "Unknown command.")))
            }
        }
    }

    private fun readFrame(input: InputStream): Pair<Int, ByteArray>? {
        val data = ByteArrayOutputStream()
        var first = -1
        while (true) {
            val b0 = input.read()
            if (b0 < 0) return null
            val b1 = read(input)
            val fin = b0 and 0x80 != 0
            val op = b0 and 0x0F
            var n = (b1 and 0x7F).toLong()
            if (n == 126L) n = ((read(input) shl 8) or read(input)).toLong()
            else if (n == 127L) { n = 0; repeat(8) { n = (n shl 8) or read(input).toLong() } }
            val mask = if (b1 and 0x80 != 0) ByteArray(4) { read(input).toByte() } else null
            val payload = ByteArray(n.toInt()) { read(input).toByte() }
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            if (op >= 8) return op to payload // control frames come whole
            if (first < 0) first = op
            data.write(payload)
            if (fin) return first to data.toByteArray()
        }
    }

    private fun read(input: InputStream): Int = input.read().also { if (it < 0) throw EOFException() }

    private fun writeFrame(out: OutputStream, op: Int, payload: ByteArray) {
        out.write(0x80 or op)
        when {
            payload.size < 126 -> out.write(payload.size)
            payload.size < 65536 -> { out.write(126); out.write(payload.size shr 8); out.write(payload.size and 0xFF) }
            else -> { out.write(127); for (i in 7 downTo 0) out.write(((payload.size.toLong() shr (8 * i)) and 0xFF).toInt()) }
        }
        out.write(payload)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (b.size() == 0) null else String(b.toByteArray())
            if (c == '\n'.code) return String(b.toByteArray()).trimEnd('\r')
            b.write(c)
        }
    }

    companion object {
        private const val TAG = "FakeHomeAssistant"
        const val TOKEN = "conformance-token"
        const val HA_VERSION = "2026.11.0"
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /** The page a browser engine shows: a colored band and a line of text, so a capture isn't one flat color. */
        private const val PAGE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Fake Home Assistant</title></head>
<body style="margin:0;background:#111418;color:#fff;font:28px sans-serif">
<div style="background:#03a9f4;height:160px"></div><p style="padding:24px">Engine contract: PATH</p></body></html>"""

        /** Two views, so a page can move between them (navigate, back). Only core cards: no web islands. */
        const val DASHBOARD = """{"title": "Conformance", "views": [
          {"title": "Home", "path": "home", "cards": [
            {"type": "entities", "title": "Lights", "entities": ["light.lounge_lamp", "sensor.kitchen_temp"]},
            {"type": "tile", "entity": "light.lounge_lamp"}
          ]},
          {"title": "Second", "path": "second", "cards": [
            {"type": "entities", "title": "Doors", "entities": ["binary_sensor.front_door"]}
          ]}
        ]}"""

        /** `subscribe_entities`' first `a` payload: timestamps go through java.time (desugared on Android 6). */
        const val ENTITIES = """{
          "light.lounge_lamp": {"s": "on", "a": {"friendly_name": "Lounge Lamp", "brightness": 128}, "lc": 1700000000.5, "lu": 1700000001.0},
          "sensor.kitchen_temp": {"s": "21.5", "a": {"friendly_name": "Kitchen", "unit_of_measurement": "°C", "device_class": "temperature"}, "lc": 1700000000.0, "lu": 1700000002.0},
          "binary_sensor.front_door": {"s": "off", "a": {"friendly_name": "Front Door", "device_class": "door"}, "lc": 1700000000.0, "lu": 1700000000.0}
        }"""
    }
}
