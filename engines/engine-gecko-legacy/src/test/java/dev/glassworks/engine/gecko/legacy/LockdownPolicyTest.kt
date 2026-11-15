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
    private val policy = LockdownPolicy().also { it.appLoads("$ha/lovelace/0?external_auth=1") }

    @Test fun `the app's load sets the origin`() {
        assertEquals(ha, policy.origin)
        assertFalse("the same origin again is no change", policy.appLoads("$ha/energy"))
        assertTrue(policy.appLoads("https://homeassistant.local/"))
        assertEquals("https://homeassistant.local", policy.origin)
    }

    @Test fun `before the app loads anything nothing from the network loads`() {
        val fresh = LockdownPolicy()
        assertNull(fresh.origin)
        assertFalse(fresh.allows(Load.PAGE, "$ha/"))
        assertFalse(fresh.allows(Load.FRAME, "$ha/"))
        assertTrue(fresh.allows(Load.PAGE, "about:blank"))
    }

    @Test fun `an app load that isn't a network URL leaves the origin alone`() {
        assertFalse(policy.appLoads("about:blank"))
        assertFalse(policy.appLoads("data:text/html,hi"))
        assertEquals(ha, policy.origin)
    }

    @Test fun `top-level pages load only from Home Assistant`() {
        for (ok in listOf("$ha/", "$ha/auth/authorize?client_id=x&redirect_uri=y", "HTTP://192.0.2.10:8123/config", "$ha/history?x={a|b}", "about:blank")) {
            assertTrue(ok, policy.allows(Load.PAGE, ok))
        }
        for (no in listOf(
            "http://192.0.2.10/", "https://192.0.2.10:8123/", "http://192.0.2.11:8123/", "http://192.0.2.10:8124/",
            "https://www.example.com/", "http://192.0.2.10:8123.example.com/", "http://192.0.2.10:8123@example.com/",
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

    @Test fun `new windows and downloads are always refused`() {
        assertFalse(policy.allows(Load.WINDOW, "$ha/"))
        assertFalse(policy.allows(Load.WINDOW, "https://www.example.com/"))
        assertFalse(policy.allows(Load.DOWNLOAD, "$ha/api/backup/download"))
        assertFalse(policy.allows(Load.DOWNLOAD, "blob:$ha/1f2e"))
    }

    @Test fun `a new dashboard origin moves the lockdown with it`() {
        policy.appLoads("https://homeassistant.local/dashboard-wall/0")
        assertTrue(policy.allows(Load.PAGE, "https://homeassistant.local/"))
        assertFalse(policy.allows(Load.PAGE, "$ha/"))
    }
}
