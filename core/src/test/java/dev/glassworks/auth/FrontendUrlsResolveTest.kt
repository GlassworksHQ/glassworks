package dev.glassworks.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A path the app is asked to open (`--es open /history`, a card's `navigate`) goes on Home Assistant's origin before any
 * engine gets it: the legacy engine's lockdown refused the bare path on the lululemon Mirror. Addresses are RFC 5737.
 */
class FrontendUrlsResolveTest {
    private val dash = "http://192.0.2.10:8123/lovelace/0?external_auth=1"

    @Test fun `a path goes on the dashboard's origin, its query and fragment kept`() {
        assertEquals("http://192.0.2.10:8123/history", FrontendUrls.resolve("/history", dash))
        assertEquals("http://192.0.2.10:8123/logbook", FrontendUrls.resolve("logbook", dash))
        assertEquals("http://192.0.2.10:8123/config/dashboard", FrontendUrls.resolve("  /config/dashboard  ", dash))
        assertEquals(
            "http://192.0.2.10:8123/history?entity_id=light.kitchen&start_date=2026-11-28T00%3A00%3A00Z#top",
            FrontendUrls.resolve("/history?entity_id=light.kitchen&start_date=2026-11-28T00%3A00%3A00Z#top", dash),
        )
        assertEquals("http://192.0.2.10:8123/?edit=1", FrontendUrls.resolve("?edit=1", dash))
        assertEquals("http://192.0.2.10:8123/#x", FrontendUrls.resolve("#x", dash))
    }

    @Test fun `the origin is written as the panel writes Home Assistant's address, default ports dropped`() {
        assertEquals("http://homeassistant.local/history", FrontendUrls.resolve("/history", "http://homeassistant.local:80/"))
        assertEquals("https://ha.example.com/history", FrontendUrls.resolve("/history", "https://ha.example.com:443/lovelace/0"))
        assertEquals("http://homeassistant.local/history", FrontendUrls.resolve("/history", "HTTP://HomeAssistant.Local/"))
        assertEquals("https://ha.example.com:8443/history", FrontendUrls.resolve("/history", "https://ha.example.com:8443"))
        assertEquals("http://[2001:db8::10]:8123/history", FrontendUrls.resolve("/history", "http://[2001:db8::10]:8123/"))
    }

    @Test fun `a path in the dashboard's address is no prefix, Home Assistant serves its frontend at the root`() {
        assertEquals("http://192.0.2.10:8123/history", FrontendUrls.resolve("/history", "http://192.0.2.10:8123/wall-panel/kitchen"))
    }

    @Test fun `a URL with a scheme stays as it is`() {
        for (u in listOf("http://192.0.2.10:8123/map", "https://192.0.2.30/x?y#z", "about:blank", "http://192.0.2.10:80/history")) {
            assertEquals(u, FrontendUrls.resolve(u, dash))
        }
    }

    @Test fun `nothing opens for a blank value, a scheme-relative URL, or a path with nowhere to go`() {
        assertNull(FrontendUrls.resolve("  ", dash))
        assertNull(FrontendUrls.resolve(null, dash))
        assertNull("a scheme-relative URL names a host", FrontendUrls.resolve("//192.0.2.30/history", dash))
        assertNull("no Home Assistant yet", FrontendUrls.resolve("/history", null))
        assertNull("no Home Assistant yet", FrontendUrls.resolve("/history", ""))
        assertNull("not an http(s) base", FrontendUrls.resolve("/history", "about:blank"))
        assertNull("a control character", FrontendUrls.resolve("/history\n/x", dash))
    }

    @Test fun `pathOn keeps another scheme a path on Home Assistant`() {
        assertEquals("http://192.0.2.10:8123/javascript:alert(1)", FrontendUrls.pathOn(dash, "javascript:alert(1)"))
        assertEquals("http://192.0.2.10:8123/history", FrontendUrls.pathOn("http://192.0.2.10:8123", "/history"))
        assertNull(FrontendUrls.pathOn(null, "/history"))
    }
}
