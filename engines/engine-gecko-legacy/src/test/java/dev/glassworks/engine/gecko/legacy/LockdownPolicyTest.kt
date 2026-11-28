/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.gecko.legacy

import dev.glassworks.engine.gecko.PageGuard.Load
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The legacy engine's lockdown (#198): pages, frames, new windows and downloads. Addresses are RFC 5737. */
class LockdownPolicyTest {
    private val ha = "http://192.0.2.10:8123"
    private val policy = LockdownPolicy().also { it.start("$ha/lovelace/0?external_auth=1") }

    @Test fun `the configured dashboard sets the origin`() {
        assertEquals(ha, policy.origin)
    }

    /** #198 part 2: the origin is set once, when the engine starts, and nothing in the session moves or widens it. */
    @Test fun `the origin is set once, and a later start or an app load elsewhere changes nothing`() {
        assertFalse("a second start is ignored", policy.start("https://homeassistant.local/"))
        assertFalse(policy.start(null))
        assertEquals(ha, policy.origin)
        assertFalse("the app's own load elsewhere is a page like any other", policy.allows(Load.PAGE, "https://homeassistant.local/"))
        assertFalse(policy.allows(Load.WINDOW, "https://homeassistant.local/"))
        assertEquals(ha, policy.origin)
    }

    @Test fun `a first start without a dashboard stays empty, so nothing loads and nothing can set it later`() {
        val fresh = LockdownPolicy()
        assertTrue(fresh.start(null))
        assertNull(fresh.origin)
        assertFalse(fresh.start("$ha/"))
        assertNull(fresh.origin)
        assertFalse(fresh.allows(Load.PAGE, "$ha/"))
    }

    @Test fun `before the engine starts nothing from the network loads`() {
        val fresh = LockdownPolicy()
        assertNull(fresh.origin)
        assertFalse(fresh.allows(Load.PAGE, "$ha/"))
        assertFalse(fresh.allows(Load.FRAME, "$ha/"))
        assertTrue(fresh.allows(Load.PAGE, "about:blank"))
    }

    /**
     * Gecko writes faß and fa%C3%9F as xn--fa-hia, which Android's IDN can't reproduce: such a dashboard URL sets no
     * origin, so the app's own load of it, and every page from Gecko's spelling of that host, is refused (#346 review).
     */
    @Test fun `a dashboard host Gecko would write differently allows nothing`() {
        for (configured in listOf("http://faß.example.org:8123/", "http://fa%C3%9F.example.org:8123/")) {
            val fresh = LockdownPolicy()
            assertTrue(configured, fresh.start(configured))
            assertNull(configured, fresh.origin)
            assertFalse(configured, fresh.allows(Load.PAGE, configured))
            assertFalse(configured, fresh.allows(Load.PAGE, "http://xn--fa-hia.example.org:8123/"))
            assertFalse(configured, fresh.allows(Load.FRAME, "http://xn--fa-hia.example.org:8123/frame"))
        }
    }

    @Test fun `a dashboard that isn't a network URL sets no origin`() {
        for (dashboard in listOf("about:blank", "data:text/html,hi", "", "auto")) {
            val fresh = LockdownPolicy()
            fresh.start(dashboard)
            assertNull(dashboard, fresh.origin)
        }
    }

    @Test fun `top-level pages load only from Home Assistant`() {
        for (ok in listOf("$ha/", "$ha/auth/authorize?client_id=x&redirect_uri=y", "HTTP://192.0.2.10:8123/config", "$ha/history?x={a|b}", "about:blank")) {
            assertTrue(ok, policy.allows(Load.PAGE, ok))
        }
        for (no in listOf(
            "http://192.0.2.10/", "https://192.0.2.10:8123/", "http://192.0.2.11:8123/", "http://192.0.2.10:8124/",
            "https://www.example.com/", "http://192.0.2.10:8123.example.com/", "http://192.0.2.10:8123@example.com/",
            "http://192.0.2.10:8123\\@example.com/", "http://example.com\\@192.0.2.10:8123/", "http://exa\tmple.com@192.0.2.10:8123/",
            "about:config", "about:addons", "data:text/html,<h1>x</h1>", "file:///sdcard/x.html", "javascript:alert(1)",
            "resource://android/assets/extension/content.js", "moz-extension://abc/blocked.html", "", "not a url",
        )) {
            assertFalse(no, policy.allows(Load.PAGE, no))
        }
    }

    @Test fun `frames load from Home Assistant, inherit its origin or are the lockdown's note`() {
        for (ok in listOf("$ha/local/card.html", "about:blank", "about:srcdoc", "moz-extension://4b1d/blocked.html#example.com", "blob:$ha/1f2e")) {
            assertTrue(ok, policy.allows(Load.FRAME, ok))
        }
        for (no in listOf("https://www.youtube.com/embed/x", "https://tile.openstreetmap.org/1/1/1.png", "blob:https://example.com/1f2e", "data:text/html,x")) {
            assertFalse(no, policy.allows(Load.FRAME, no))
        }
    }

    @Test fun `a new window loads in place only if it is Home Assistant's`() {
        assertTrue(policy.allows(Load.WINDOW, "$ha/history"))
        assertFalse(policy.allows(Load.WINDOW, "https://www.example.com/"))
        assertFalse("an empty window would blank the dashboard", policy.allows(Load.WINDOW, "about:blank"))
        assertFalse(policy.allows(Load.WINDOW, "http://192.0.2.10:8123\\@example.com/"))
    }

    @Test fun `downloads are always refused`() {
        assertFalse(policy.allows(Load.DOWNLOAD, "$ha/api/backup/download"))
        assertFalse(policy.allows(Load.DOWNLOAD, "blob:$ha/1f2e"))
    }

    /**
     * Vitro on the legacy engine: its island server, on this process's loopback port, is the one other origin a page or
     * frame may come from. Home Assistant's pages still come from Home Assistant (the Mirror, 2026-11-25).
     */
    @Test fun `the app's own loopback server is the one other origin, its port only`() {
        val local = "http://127.0.0.1:39123"
        val both = LockdownPolicy().also { it.start("$ha/lovelace/0", "$local/") }
        assertEquals(ha, both.origin)
        assertEquals(local, both.local)
        for (ok in listOf("$ha/history", "$ha/profile/general", "$local/__vitro/island/s0123", "$local/")) {
            assertTrue(ok, both.allows(Load.PAGE, ok))
            assertTrue(ok, both.allows(Load.FRAME, ok))
        }
        for (no in listOf("http://127.0.0.1:39124/", "http://localhost:39123/", "https://127.0.0.1:39123/", "http://127.0.0.2:39123/",
            "http://[::1]:39123/", "http://127.0.0.1/", "http://127.0.0.1:39123.example.com/", "http://127.0.0.1:39123@example.com/")) {
            assertFalse(no, both.allows(Load.PAGE, no))
            assertFalse(no, both.allows(Load.FRAME, no))
        }
        assertFalse("a new window goes only to Home Assistant", both.allows(Load.WINDOW, "$local/__vitro/island/s0123"))
        assertFalse(both.allows(Load.DOWNLOAD, "$local/x"))

        assertFalse("set once, with the dashboard", both.start("$ha/", "http://127.0.0.1:40000"))
        assertEquals(local, both.local)
        assertFalse(both.allows(Load.PAGE, "http://127.0.0.1:40000/"))
    }

    @Test fun `a local origin that isn't 127_0_0_1 with a port opens nothing`() {
        for (bad in listOf("http://localhost:39123", "https://127.0.0.1:39123", "http://127.0.0.1", "http://127.0.0.1:80",
            "http://192.0.2.10:8124", "https://www.example.com", "http://[::1]:39123", "about:blank", "", "http://127.0.0.1:39123\\@example.com")) {
            val p = LockdownPolicy().also { it.start("$ha/", bad) }
            assertNull(bad, p.local)
            assertEquals(bad, ha, p.origin)
            assertFalse(bad, p.allows(Load.PAGE, "http://127.0.0.1:39123/"))
        }
        val none = LockdownPolicy().also { it.start("$ha/") }
        assertNull(none.local)
        assertFalse(none.allows(Load.PAGE, "http://127.0.0.1:39123/"))
    }

    @Test fun `a new dashboard origin takes a new engine (a process restart)`() {
        val next = LockdownPolicy().also { it.start("https://homeassistant.local/dashboard-wall/0") }
        assertTrue(next.allows(Load.PAGE, "https://homeassistant.local/"))
        assertFalse(next.allows(Load.PAGE, "$ha/"))
    }
}
