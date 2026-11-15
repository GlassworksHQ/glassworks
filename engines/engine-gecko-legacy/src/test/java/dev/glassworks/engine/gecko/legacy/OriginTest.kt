/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.gecko.legacy

import org.junit.Assert.assertEquals
import org.junit.Test

/** Origin.kt, and the same cases as policy.test.mjs runs on policy.js, so the two stay one rule. */
class OriginTest {
    @Test fun origins() {
        val cases = mapOf(
            "http://192.0.2.10:8123/lovelace/0" to "http://192.0.2.10:8123",
            "HTTP://HomeAssistant.Local:8123" to "http://homeassistant.local:8123",
            "http://homeassistant.local./" to "http://homeassistant.local",
            "http://192.0.2.10:80/" to "http://192.0.2.10",
            "https://192.0.2.10:443/x" to "https://192.0.2.10",
            "ws://192.0.2.10:8123/api/websocket" to "http://192.0.2.10:8123",
            "wss://homeassistant.local/api/websocket" to "https://homeassistant.local",
            "blob:http://192.0.2.10:8123/1f2e-33" to "http://192.0.2.10:8123",
            "http://user:pw@192.0.2.10:8123/" to "http://192.0.2.10:8123",
            "http://[2001:db8::1]:8123/" to "http://[2001:db8::1]:8123",
            "http://192.0.2.10:8123?x" to "http://192.0.2.10:8123",
            "http://192.0.2.10:8123#x" to "http://192.0.2.10:8123",
            "http://192.0.2.10:8123@example.com/" to "http://example.com",
            // Unicode hosts in punycode, as Gecko reports the requests (#346 review).
            "http://ält.example.org:8123/lovelace" to "http://xn--lt-uia.example.org:8123",
            "http://xn--lt-uia.example.org:8123/api/websocket" to "http://xn--lt-uia.example.org:8123",
            "https://ÄLT.example.org/" to "https://xn--lt-uia.example.org",
        )
        for ((url, origin) in cases) assertEquals(url, origin, Origin.of(url))
        for (none in listOf(null, "", "about:blank", "data:text/html,x", "moz-extension://abc/x", "javascript:x", "file:///x",
            "ftp://192.0.2.10/", "http://", "http://:8123/", "http://192.0.2.10:0/", "http://192.0.2.10:99999/", "http://192.0.2.10:12a/",
            "http://[2001:db8::1/", "blob:about:blank",
            // A browser reads these as another host than the text after the last @ says, or the port isn't digits.
            "http://example.com\\@192.0.2.10:8123/", "http://192.0.2.10:8123\\@example.com/", "http://exa\tmple.com@192.0.2.10:8123/",
            "http://exa\nmple.com@192.0.2.10:8123/", "http://exa mple.com@192.0.2.10:8123/", "http://exa\u0001mple.com@192.0.2.10:8123/",
            "http://192.0.2.10:+80/", "http://192.0.2.10:-1/", "http://192.0.2.10: 80/", "http://[2001:db8::1]8123/")) {
            assertEquals(none, null, Origin.of(none))
        }
    }

    @Test fun hosts() {
        assertEquals("www.example.com", Origin.host("https://www.example.com/embed"))
        assertEquals("192.0.2.10:8124", Origin.host("http://192.0.2.10:8124/"))
        assertEquals("", Origin.host("data:text/html,x"))
    }
}
