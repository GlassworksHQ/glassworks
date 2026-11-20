/* SPDX-License-Identifier: MIT
 *
 * The HEAD tests follow upstream DigitallyRefined/android-ip-camera ca0085d1 (OnvifServerTest). Copyright (c) 2025
 * DigitallyRefined and contributors. The licence text is in THIRD-PARTY-NOTICES.md and LICENSES/MIT.txt.
 * Changes: package; signed in as our server needs; one keep-alive connection read byte for byte, so a body
 * that the headers don't announce shows up as a broken next answer. */

package dev.glassworks.camera.onvif

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

/** Every answer the ONVIF server sends over a socket is framed by its Content-Length, never chunked or cut by a close. */
class OnvifHttpTest {
    private val user = "admin"
    private val pass = "secret"
    private var server: OnvifServer? = null
    private val basic = "Basic " + OnvifBase64.encode("$user:$pass".toByteArray())

    private fun newServer(snapshot: (() -> ByteArray?)? = { byteArrayOf(9, 8, 7) }) = OnvifServer(
        port = 0,
        host = { "192.0.2.41" },
        info = { OnvifDeviceInfo("Google", "Pixel Tablet", "0.14.0", "SER123", "tangorpro", "a1b2c3d4e5f6", "Living Room Tablet") },
        stream = { OnvifStreamInfo("rtsp://192.0.2.41:8554/live", 1920, 1080, 30, 6000) },
        credentials = { user to pass },
        snapshot = snapshot,
        enableDiscovery = false,
    ).also { server = it; it.start() }

    @After fun tearDown() { server?.stop() }

    private fun soap(body: String) =
        "<s:Envelope xmlns:s=\"${OnvifNs.SOAP}\" xmlns:tds=\"${OnvifNs.TDS}\" xmlns:tev=\"${OnvifNs.TEV}\" xmlns:wsnt=\"${OnvifNs.WSNT}\">" +
            "<s:Body>$body</s:Body></s:Envelope>"

    private class Answer(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            check(c >= 0) { "connection closed mid-head: $sb" }
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    /** Reads one answer: its head, then exactly Content-Length bytes, which must be there. */
    private fun readAnswer(input: InputStream, head: Boolean = false): Answer {
        val status = readLine(input)
        assertTrue("an HTTP status line: $status", status.startsWith("HTTP/1.1 "))
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input)
            if (h.isEmpty()) break
            headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim()
        }
        assertFalse("never chunked", headers.containsKey("transfer-encoding"))
        val len = headers["content-length"]?.toIntOrNull()
        assertTrue("every answer has a Content-Length: $status $headers", len != null && len >= 0)
        val body = ByteArray(if (head) 0 else len!!)
        var off = 0
        while (off < body.size) {
            val n = input.read(body, off, body.size - off)
            check(n > 0) { "body shorter than its Content-Length" }
            off += n
        }
        return Answer(status.split(' ')[1].toInt(), headers, body)
    }

    private fun request(method: String, path: String, body: String? = null, auth: Boolean = true): String {
        val bytes = body?.toByteArray(Charsets.UTF_8)
        return "$method $path HTTP/1.1\r\nHost: 192.0.2.41\r\n" +
            (if (auth) "Authorization: $basic\r\n" else "") +
            (if (bytes != null) "Content-Type: application/soap+xml\r\nContent-Length: ${bytes.size}\r\n" else "") +
            "\r\n" + (body ?: "")
    }

    @Test fun `every answer on one keep-alive connection carries its own Content-Length`() {
        val s = newServer()
        Socket("127.0.0.1", s.localPort).use { sock ->
            sock.soTimeout = 5_000
            val out = sock.getOutputStream()
            val input = sock.getInputStream()
            fun ask(raw: String, head: Boolean = false): Answer {
                out.write(raw.toByteArray(Charsets.UTF_8)); out.flush()
                return readAnswer(input, head)
            }
            val ok = ask(request("POST", "/onvif/device_service", soap("<tds:GetDeviceInformation/>")))
            assertEquals(200, ok.status)
            assertTrue(String(ok.body, Charsets.UTF_8).contains("Pixel Tablet"))
            val unsupported = ask(request("POST", "/onvif/device_service", soap("<tds:NoSuchThing/>")))
            assertEquals(400, unsupported.status)
            assertTrue(String(unsupported.body, Charsets.UTF_8).contains("ActionNotSupported"))
            val refused = ask(request("POST", "/onvif/events_service", soap("<tev:GetEventProperties/>"), auth = false))
            assertEquals(401, refused.status)
            val wrongMethod = ask(request("GET", "/onvif/device_service"))
            assertEquals(405, wrongMethod.status)
            // HEAD sends no body: the next answer on the connection must still start where it should.
            val head = ask(request("HEAD", "/onvif/snapshot.jpg"), head = true)
            assertEquals(200, head.status)
            assertEquals("3", head.headers["content-length"])
            val headRefused = ask(request("HEAD", "/onvif/snapshot.jpg", auth = false), head = true)
            assertEquals(401, headRefused.status)
            assertEquals("12", headRefused.headers["content-length"]) // "Unauthorized", as GET would get
            val snap = ask(request("GET", "/onvif/snapshot.jpg"))
            assertEquals(200, snap.status)
            assertTrue(byteArrayOf(9, 8, 7).contentEquals(snap.body))
        }
    }

    @Test fun `HEAD on the snapshot reports the GET content length without a body`() {
        val s = newServer(snapshot = { byteArrayOf(9, 8, 7) })
        val head = URL("http://127.0.0.1:${s.localPort}/onvif/snapshot.jpg").openConnection() as HttpURLConnection
        head.requestMethod = "HEAD"
        head.setRequestProperty("Authorization", basic)
        assertEquals(200, head.responseCode)
        assertEquals("image/jpeg", head.contentType)
        assertEquals(3, head.contentLength)
        assertEquals(0, head.inputStream.readBytes().size)
    }

    @Test fun `HEAD on the snapshot mirrors the error body length`() {
        val s = newServer(snapshot = { null })
        val head = URL("http://127.0.0.1:${s.localPort}/onvif/snapshot.jpg").openConnection() as HttpURLConnection
        head.requestMethod = "HEAD"
        head.setRequestProperty("Authorization", basic)
        assertEquals(503, head.responseCode)
        assertEquals("No snapshot available".length, head.contentLength)
        assertEquals(0, head.errorStream?.readBytes()?.size ?: 0)
    }

    @Test fun `HEAD resolves the same representation as GET`() {
        val s = newServer(snapshot = { byteArrayOf(4, 5) })
        val r = s.handle("HEAD", "/onvif/snapshot.jpg", mapOf("authorization" to basic), ByteArray(0))
        assertEquals(200, r.status)
        assertEquals(2, r.body.size)
    }
}
