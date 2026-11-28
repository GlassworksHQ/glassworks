package dev.glassworks.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class HaUrlsTest {
    private fun txt(vararg kv: Pair<String, String>): Map<String, ByteArray?> = kv.associate { it.first to it.second.toByteArray() }
    private val ip = InetAddress.getByName("192.168.1.100")

    @Test
    fun txtFromThisHa() {
        // What HA 2026.11.0 on the LAN advertises (dns-sd -L Home _home-assistant._tcp local.)
        val t = txt("location_name" to "Home", "uuid" to "x", "version" to "2026.11.0", "external_url" to "",
            "internal_url" to "http://192.168.1.100:8123", "base_url" to "http://192.168.1.100:8123")
        assertEquals("http://192.168.1.100:8123", HaUrls.fromTxt(t, ip, 8123))
        assertEquals(listOf("http://192.168.1.100:8123"), HaUrls.candidates(t, ip, 8123))
    }

    @Test
    fun txtPreferenceOrder() {
        val t = txt("internal_url" to "http://homeassistant.local:8123/", "base_url" to "https://ha.example.com")
        assertEquals(
            listOf("http://homeassistant.local:8123", "https://ha.example.com", "http://192.168.1.100:8123"),
            HaUrls.candidates(t, ip, 8123),
        )
        assertEquals("https://ha.example.com", HaUrls.fromTxt(txt("internal_url" to "", "base_url" to "https://ha.example.com"), null, 0))
        assertEquals("http://192.168.1.100:8124", HaUrls.fromTxt(txt("internal_url" to ""), ip, 8124))
        assertNull(HaUrls.fromTxt(emptyMap(), null, 0))
        assertEquals("http://192.168.1.100:8123", HaUrls.fromTxt(mapOf("internal_url" to null), ip, 0))
    }

    @Test
    fun addresses() {
        assertEquals("http://192.168.1.100:8123", HaUrls.fromAddress(ip))
        assertNull(HaUrls.fromAddress(InetAddress.getByName("127.0.0.1")))
        assertNull(HaUrls.fromAddress(InetAddress.getByName("169.254.1.2")))
        assertEquals("http://[fd00:0:0:0:0:0:0:5]:8123", HaUrls.fromAddress(InetAddress.getByName("fd00::5")))
    }

    @Test
    fun normalize() {
        assertEquals("http://192.168.1.100:8123", HaUrls.normalize(" 192.168.1.100:8123/ "))
        assertEquals("http://192.0.2.10:8123", HaUrls.normalize("192.0.2.10"))
        assertEquals("http://homeassistant.local:8123", HaUrls.normalize("homeassistant.local"))
        assertEquals("http://[fd00::5]:8123", HaUrls.normalize("[fd00::5]"))
        assertEquals("http://192.0.2.10:80", HaUrls.normalize("192.0.2.10:80"))
        assertEquals("https://ha.example.com", HaUrls.normalize("https://ha.example.com"))
        assertEquals("http://ha.example.com", HaUrls.normalize("http://ha.example.com"))
        assertEquals("http://homeassistant.local:8123", HaUrls.normalize("http://homeassistant.local:8123/lovelace/0"))
        assertNull(HaUrls.normalize(""))
        assertNull(HaUrls.normalize("ftp://x"))
    }

    @Test
    fun normalizeDashboardUrl() {
        // Missing scheme: prepend http:// and default port to 8123 when none was specified.
        assertEquals("http://192.0.2.10:8123", HaUrls.normalizeDashboardUrl("192.0.2.10"))
        assertEquals("http://192.0.2.10:8123", HaUrls.normalizeDashboardUrl(" 192.0.2.10 "))
        assertEquals("http://homeassistant.local:8123", HaUrls.normalizeDashboardUrl("homeassistant.local"))
        assertEquals("http://homeassistant.local:8123", HaUrls.normalizeDashboardUrl("HomeAssistant.Local"))
        assertEquals("http://192.0.2.10:8123/lovelace/0?kiosk=0#top", HaUrls.normalizeDashboardUrl("192.0.2.10/lovelace/0?kiosk=0#top"))
        assertEquals("http://[fd00::5]:8123", HaUrls.normalizeDashboardUrl("[fd00::5]"))
        assertEquals("http://[fd00::5]:8123/lovelace/0", HaUrls.normalizeDashboardUrl("[fd00::5]/lovelace/0"))

        // Missing scheme with an explicit port: keep that port.
        assertEquals("http://192.0.2.10:80", HaUrls.normalizeDashboardUrl("192.0.2.10:80"))
        assertEquals("http://homeassistant.local:8123/lovelace/0", HaUrls.normalizeDashboardUrl("homeassistant.local:8123/lovelace/0"))
        assertEquals("http://[fd00::5]:8124/lovelace/0", HaUrls.normalizeDashboardUrl("[fd00::5]:8124/lovelace/0"))

        // Explicit http:// or https://: keep scheme, host, port (never force :8123), and path/query/fragment.
        assertEquals("https://ha.example.com", HaUrls.normalizeDashboardUrl("https://ha.example.com"))
        assertEquals("http://ha.example.com", HaUrls.normalizeDashboardUrl("http://ha.example.com"))
        assertEquals("https://ha.example.com/lovelace/0?a=1#b", HaUrls.normalizeDashboardUrl("HTTPS://HA.Example.Com/lovelace/0?a=1#b"))
        assertEquals("http://192.0.2.10:8123/lovelace/0", HaUrls.normalizeDashboardUrl("http://192.0.2.10:8123/lovelace/0"))
        assertEquals("https://[fd00::5]/lovelace/0", HaUrls.normalizeDashboardUrl("https://[fd00::5]/lovelace/0"))

        // Invalid inputs are rejected.
        val invalid = listOf(
            null, "", "   ",
            "ftp://192.0.2.10:8123", "ws://192.0.2.10:8123", "file:///etc/hosts",
            "javascript:alert(1)", "mailto:user@example.com", "about:blank",
            "http://", "https://", "://192.0.2.10", "http://:8123", "/lovelace/0", "http:///lovelace/0",
            "http:/192.0.2.10", "192.0.2.10:", "192.0.2.10:0", "192.0.2.10:65536", "192.0.2.10:abc",
            "http://192.0.2.10:abc", "192.0.2.10:80:81",
            "not a url", "192.0.2.10 8123", "http://exa mple.com", "http://192.0.2.10\\evil.example",
            "[fd00::5", ".", "http://fa\u00df.example", "http://a%20b.example",
        )
        for (bad in invalid) assertNull("expected null for <$bad>", HaUrls.normalizeDashboardUrl(bad))
    }

    @Test
    fun manifest() {
        assertTrue(HaUrls.looksLikeHaManifest(200, """{"background_color":"#FFFFFF","name":"Home Assistant","short_name":"Assistant"}"""))
        assertFalse(HaUrls.looksLikeHaManifest(200, """{"name":"Grafana"}"""))
        assertFalse(HaUrls.looksLikeHaManifest(404, "{}"))
        assertFalse(HaUrls.looksLikeHaManifest(200, "<html>"))
    }
}
