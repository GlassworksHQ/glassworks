/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.web

import dev.glassworks.platform.PlatformApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #449: a `.local` dashboard on Android 6 (the Mirror) and 10 (the S1) loads through Home Assistant's address. */
class LocalNameTest {
    private val found = "http://192.0.2.10:8123"
    private val dash = "http://homeassistant.local:8123/lovelace/0?kiosk#top"

    private fun on(api: Int, url: String = dash, at: String? = found) = LocalName.route(url, at, PlatformApi(api).resolvesLocalNames)

    @Test fun `Android 12 resolves local names, 11 and below don't`() {
        assertFalse(PlatformApi(23).resolvesLocalNames)
        assertFalse(PlatformApi(30).resolvesLocalNames)
        assertTrue(PlatformApi(31).resolvesLocalNames)
    }

    @Test fun `on Android 6 the address stands in for the name, and the rest stays as set`() {
        assertEquals(LocalRoute.ByAddress("http://192.0.2.10:8123/lovelace/0?kiosk#top", "homeassistant.local"), on(23))
        assertEquals(LocalRoute.ByAddress("http://192.0.2.10:8123/lovelace/0?kiosk#top", "homeassistant.local"), on(29))
    }

    @Test fun `on Android 12 and later the URL loads as set`() {
        assertEquals(LocalRoute.AsSet(dash), on(31))
        assertEquals(LocalRoute.AsSet(dash), on(35))
    }

    @Test fun `the port is the setting's, not discovery's`() {
        assertEquals("http://192.0.2.10/", on(23, "http://HomeAssistant.local./", "http://192.0.2.10:8123").url)
        assertEquals("http://192.0.2.10:8124", on(23, "http://homeassistant.local:8124", found).url)
    }

    @Test fun `an IPv6 address stays in brackets`() {
        assertEquals("http://[2001:db8::10]:8123/", on(23, "http://homeassistant.local:8123/", "http://[2001:db8::10]:8123").url)
    }

    @Test fun `https keeps the name its certificate is for, and says why`() {
        val r = on(23, "https://homeassistant.local:8123/")
        assertEquals(LocalRoute.Unresolved("https://homeassistant.local:8123/", "https, and its certificate names homeassistant.local"), r)
    }

    @Test fun `before discovery has an address the URL loads as set, and says why`() {
        for (at in listOf(null, "", "http://other.local:8123")) {
            assertEquals(at.toString(), LocalRoute.Unresolved(dash, "Home Assistant's address isn't known yet"), on(23, at = at))
        }
    }

    @Test fun `a host that isn't a local name is left alone`() {
        for (url in listOf("http://192.0.2.20:8123/", "http://ha.example.com/", "http://localhost:8123/", "not a url", "")) {
            assertEquals(url, LocalRoute.AsSet(url), on(23, url))
        }
    }
}
