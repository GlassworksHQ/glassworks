package dev.glassworks.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A path the app is asked to open (`--es open /history`, a card's `navigate`) goes on Home Assistant's origin before any
 * engine gets it: the legacy engine's lockdown refused the bare path on the lululemon Mirror. Addresses are RFC 5737.
 */
@RunWith(RobolectricTestRunner::class) // android.net.Uri, for the hosts java.net.URI reads none from
@Config(sdk = [34])
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

    /**
     * What a browser-style resolver would read as another host: each resolves to nothing, or to HA's origin and then `/`,
     * so nothing in the ref ends the authority. A later switch to `URI.resolve` or `Uri.withAppendedPath` fails here
     * (`//evil.example` and, for WHATWG, `\\evil.example` become a host there).
     */
    @Test fun `no hostile path leaves Home Assistant's origin`() {
        val origin = "http://192.0.2.10:8123"
        for (ref in HOSTILE) {
            val url = FrontendUrls.resolve(ref, dash) ?: continue
            assertTrue("${escape(ref)} -> ${escape(url)}", url.startsWith("$origin/"))
        }
        for (ref in HOSTILE_NOTHING) assertNull(escape(ref), FrontendUrls.resolve(ref, dash))
    }

    @Test fun `a ref with a scheme stays as it is, for the engine to refuse`() {
        for (ref in SCHEMED) assertEquals(ref, FrontendUrls.resolve(ref, dash))
    }

    /** Hosts java.net.URI reads none from, which the lockdown and the engines' start accept ([KioskPrefs.engineStart]). */
    @Test fun `a path goes on a host java net URI reads none from`() {
        assertNull("URI reads no host", OAuth.origin("http://home_assistant:8123/lovelace/0"))
        assertEquals("http://home_assistant:8123/history", FrontendUrls.resolve("/history", "http://home_assistant:8123/lovelace/0"))
        assertEquals("http://home_assistant/history?x=1#y", FrontendUrls.resolve("/history?x=1#y", "http://Home_Assistant:80/"))
        assertEquals("https://home_assistant/history", FrontendUrls.resolve("history", "https://home_assistant:443"))
        assertEquals("http://ält.example:8123/history", FrontendUrls.resolve("/history", "http://ält.example:8123/lovelace/0"))
        // Nothing that could end the authority or name another host comes through the fallback.
        assertNull(FrontendUrls.resolve("/history", "http://user@home_assistant:8123/"))
        assertNull(FrontendUrls.resolve("/history", "ftp://home_assistant/"))
        assertNull(FrontendUrls.resolve("/history", "http://home_assistant%2Fevil:8123/"))
    }

    companion object {
        /** Refs a browser-style resolver could read as another host. */
        val HOSTILE = listOf(
            "/\\evil.example", "\\\\evil.example", "\\/evil.example", "/\\/evil.example", "@evil.example",
            "/@evil.example", ":@evil.example", "%2F%2Fevil.example", "/%2F%2Fevil.example", "%5C%5Cevil.example",
            "\uFF0F\uFF0Fevil.example", "/\uFF0Fevil.example", "\u200B//evil.example", "\uFEFF//evil.example",
            "\u2060//evil.example", "/\u0005/evil.example", "?//evil.example", "#//evil.example", "/.//evil.example",
            "./evil.example", "../../evil.example", "/:evil.example@x", "/history@evil.example",
            "\t//evil.example", "\u3000//evil.example", "/\t/evil.example", "\u0000http://evil.example",
        )

        /** Hostile refs that open nothing: a `//` once trimmed, or a control character. */
        val HOSTILE_NOTHING = listOf(
            "//evil.example/x", "\t//evil.example", "\u3000//evil.example", "/\t/evil.example", "\u0000http://evil.example",
            "/\u0005/evil.example",
        )

        /** Refs with a scheme: [FrontendUrls.resolve] leaves them to the engine, and the legacy lockdown refuses them. */
        val SCHEMED = listOf(
            "JAVASCRIPT:alert(1)", "javascript:alert(1)", "HTTP://evil.example/", "http://ha@evil.example/",
            "http://192.0.2.10:8123@evil.example/", "data:text/html,<p>x</p>", "intent://x#Intent;scheme=http;end",
            "file:///sdcard/x.html", "http:evil.example", "http:\\\\evil.example",
        )

        fun escape(s: String): String = s.map { c -> if (c.code < 0x20 || c.code > 0x7e) "\\u%04X".format(c.code) else "$c" }.joinToString("")
    }
}
