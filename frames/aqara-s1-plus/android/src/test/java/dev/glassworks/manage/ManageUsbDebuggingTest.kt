/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.Collections
import kotlin.random.Random

/** "Allow a new computer" on the Security page (#435), through the real server. Made-up keys only. */
class ManageUsbDebuggingTest {
    private fun key(seed: Int, name: String): AdbKey {
        val b = Random(seed).nextBytes(524).also { it[0] = 64; it[1] = 0; it[2] = 0; it[3] = 0 }
        return AdbKey.parse(Base64.getEncoder().encodeToString(b) + " " + name)!!
    }

    private val a = key(1, "alex@laptop")
    private val b = key(2, "sam@desktop")
    private val allowed = Collections.synchronizedList(ArrayList<String>())
    private val events = Collections.synchronizedList(ArrayList<String>())
    private val file = File(Files.createTempDirectory("adb").toFile(), "adb-computers.json")
    /** Android's answer once the approval is sent: a refusal comes back through the log. */
    private var refuseFor: String? = null
    private val computers: AdbComputers = AdbComputers(file, allow = { k ->
        allowed += k.base64
        refuseFor?.let { computers.refused(k.fingerprint, it.takeIf { w -> w != "null" }) }
        null
    }, event = { events += it }, confirmMs = 20)
    private val f = ManageFixture(usb = ManageUsbDebugging(computers, { "09:41" }, { events += it }))

    private fun ha() = f.Browser().apply { signInWithHa() }

    private fun withCode() = f.Browser().apply {
        get("/")
        post("/manage/code", mapOf("next" to "/"))
        post("/manage/code", mapOf("next" to "/", "code" to f.shown.last()!!.code))
    }

    private fun ManageFixture.Browser.allow(fingerprint: String, remember: Boolean = true) =
        post(ManageUsbDebugging.ALLOW, mapOf("csrf" to csrf(), "fingerprint" to fingerprint) + if (remember) mapOf("remember" to "1") else emptyMap())

    private fun ManageFixture.Browser.remove(fingerprint: String) =
        post(ManageUsbDebugging.REMOVE, mapOf("csrf" to csrf(), "fingerprint" to fingerprint))

    @Test fun `the Security page shows the computer asking, its fingerprint and Allow`() {
        computers.asked(a)
        val page = ha().get("/security").text()
        assertTrue(page.contains("Allow a new computer"))
        assertTrue(page.contains("Fingerprint ${a.fingerprint.replace(":", ":<wbr>")}"))
        assertTrue(page.contains("Calls itself alex@laptop"))
        assertTrue(page.contains("""name="fingerprint" value="${a.fingerprint}""""))
        assertTrue(page.contains(">Allow this computer</button>"))
        assertFalse("never the key", page.contains(a.base64.take(40)))
    }

    @Test fun `an administrator signed in with Home Assistant allows it, and the Log says so with the fingerprint`() {
        computers.asked(a)
        val br = ha()
        val r = br.allow(a.fingerprint)
        assertEquals("/security?usb=allowed", r.headers["Location"])
        assertEquals(listOf(a.base64), allowed)
        assertEquals(listOf(a), computers.list().map { it.key })
        assertTrue(events.contains("Alex allowed adb key ${a.fingerprint} (alex@laptop) over USB, from now on"))
        val page = br.get("/security?usb=allowed").text()
        assertTrue(page.contains(ManageUsbDebugging.Result.FROM_NOW_ON.text))
        assertTrue("listed with Remove", page.contains(">Remove</button>") && page.contains("Added 09:41 by Alex"))
    }

    @Test fun `allowed once, it isn't listed`() {
        computers.asked(a)
        assertEquals("/security?usb=once", ha().allow(a.fingerprint, remember = false).headers["Location"])
        assertEquals(listOf(a.base64), allowed)
        assertTrue(computers.list().isEmpty())
    }

    @Test fun `signed in with the panel code there are no buttons, and a forged form is refused`() {
        computers.asked(a)
        computers.asked(b)
        computers.allowAsking(b.fingerprint, remember = true, who = "Alex")
        allowed.clear()
        computers.asked(a)
        val br = withCode()
        val page = br.get("/security").text()
        assertTrue(page.contains(a.fingerprint.replace(":", ":<wbr>")))
        assertFalse(page.contains("Allow this computer</button>"))
        assertFalse(page.contains(">Remove</button>"))
        assertTrue(page.contains(ManageUsbDebugging.NEEDS_HA))
        assertEquals("/security?usb=signin", br.allow(a.fingerprint).headers["Location"])
        assertEquals("/security?usb=signin", br.remove(b.fingerprint).headers["Location"])
        assertTrue("Android got nothing", allowed.isEmpty())
        assertEquals(listOf(b), computers.list().map { it.key })
        assertTrue(events.any { it.startsWith("Code sign-in tried to allow adb key ${a.fingerprint}") && it.contains("refused") })
        assertTrue(events.any { it.startsWith("Code sign-in tried to remove adb key ${b.fingerprint}") && it.contains("refused") })
    }

    @Test fun `another computer asking between showing and pressing is refused`() {
        computers.asked(a)
        val br = ha()
        assertTrue(br.get("/security").text().contains(a.fingerprint))
        computers.asked(b)
        assertEquals("/security?usb=changed", br.allow(a.fingerprint).headers["Location"])
        assertTrue(allowed.isEmpty())
        assertTrue(computers.list().isEmpty())
        assertTrue(br.get("/security?usb=changed").text().contains("""value="${b.fingerprint}""""))
    }

    @Test fun `removed by an administrator, it doesn't get in again`() {
        computers.asked(a)
        val br = ha()
        br.allow(a.fingerprint)
        assertEquals("/security?usb=removed", br.remove(a.fingerprint).headers["Location"])
        assertTrue(computers.list().isEmpty())
        assertTrue(events.contains("Alex removed adb key ${a.fingerprint} (alex@laptop) from the allowed list"))
        allowed.clear()
        computers.asked(a)
        assertTrue(allowed.isEmpty())
        assertEquals("/security?usb=gone", br.remove(a.fingerprint).headers["Location"])
    }

    @Test fun `a removal or a remembering that can't be saved says so`() {
        computers.asked(a)
        val br = ha()
        file.delete()
        File(file, "x").apply { parentFile.mkdirs(); writeText("x") }
        assertEquals("/security?usb=unsaved", br.allow(a.fingerprint).headers["Location"])
        assertEquals(listOf(a.base64), allowed)
        assertTrue(br.get("/security?usb=unsaved").text().contains("Allowed for this connection only"))
        file.deleteRecursively()
        computers.asked(a)
        br.allow(a.fingerprint)
        file.delete()
        File(file, "x").apply { parentFile.mkdirs(); writeText("x") }
        assertEquals("/security?usb=notremoved", br.remove(a.fingerprint).headers["Location"])
        assertEquals(listOf(a), computers.list().map { it.key })
    }

    @Test fun `Android refusing the approval says so, and nothing is listed`() {
        computers.asked(a)
        refuseFor = b.fingerprint
        val br = ha()
        assertEquals("/security?usb=refused", br.allow(a.fingerprint).headers["Location"])
        assertTrue(computers.list().isEmpty())
        assertTrue(br.get("/security?usb=refused").text().contains(ManageUsbDebugging.Result.REFUSED.text))
        assertFalse(events.any { it.contains("allowed adb key") })
    }

    @Test fun `while the panel can't read Android's log, Allow says so`() {
        val quiet = AdbComputers(File(file.parentFile, "quiet.json"), allow = { allowed += it.base64; null }, event = { events += it }, listening = { false })
        quiet.asked(a)
        val g = ManageFixture(usb = ManageUsbDebugging(quiet, { "09:41" }, { events += it }))
        val br = g.Browser().apply { signInWithHa() }
        assertEquals("/security?usb=unconfirmed", br.post(ManageUsbDebugging.ALLOW, mapOf("csrf" to br.csrf(), "fingerprint" to a.fingerprint, "remember" to "1")).headers["Location"])
        assertTrue(allowed.isEmpty())
        assertTrue(br.get("/security?usb=unconfirmed").text().contains(ManageUsbDebugging.Result.UNCONFIRMED.text))
    }

    @Test fun `Android refusing with no computer waiting says that, as the Log does`() {
        computers.asked(a)
        refuseFor = "null"
        val br = ha()
        assertEquals("/security?usb=notwaiting", br.allow(a.fingerprint).headers["Location"])
        assertTrue(br.get("/security?usb=notwaiting").text().contains("no computer is waiting now"))
        assertTrue(events.last(), events.last().endsWith("Android refused it, no computer is waiting"))
        assertTrue(computers.list().isEmpty())
    }

    @Test fun `forms without the session's token, from another site, or without a fingerprint are refused`() {
        computers.asked(a)
        val br = ha()
        assertEquals(403, br.post(ManageUsbDebugging.ALLOW, mapOf("fingerprint" to a.fingerprint, "remember" to "1")).status)
        assertEquals(403, br.post(ManageUsbDebugging.ALLOW, mapOf("csrf" to br.csrf(), "fingerprint" to a.fingerprint),
            headers = mapOf("origin" to "http://evil.example")).status)
        assertEquals(400, br.post(ManageUsbDebugging.ALLOW, mapOf("csrf" to br.csrf(), "fingerprint" to "x\ny")).status)
        assertEquals("signed out", "/", f.Browser().post(ManageUsbDebugging.ALLOW, mapOf("fingerprint" to a.fingerprint)).headers["Location"])
        assertTrue(allowed.isEmpty())
    }

    @Test fun `the diagnostics file drops the fingerprints the Log page shows`() {
        computers.asked(a)
        val br = ha()
        br.allow(a.fingerprint)
        f.log.append(LogEntry(f.now, LogEntry.Level.INFO, "Manage", events.last()))
        assertTrue(br.get("/log").text().contains("adb key ${a.fingerprint}"))
        val file = br.get("/log/diagnostics.txt").text()
        assertTrue(file, file.contains("adb key <fingerprint>"))
        assertFalse(file.contains(a.fingerprint))
    }

    @Test fun `a panel whose app can't answer has no such card or route`() {
        val plain = ManageFixture()
        val br = plain.Browser().apply { signInWithHa() }
        assertFalse(br.get("/security").text().contains("Allow a new computer"))
        assertEquals(404, br.post(ManageUsbDebugging.ALLOW, mapOf("csrf" to br.csrf(), "fingerprint" to a.fingerprint)).status)
    }
}
