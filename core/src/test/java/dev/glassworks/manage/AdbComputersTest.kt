/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random

/** Made-up adb keys in adb's format: a 2048-bit RSAPublicKey (len 64 words first), base64, then `user@host`. */
object TestAdbKeys {
    fun line(seed: Int, name: String? = "alex@laptop"): String {
        val b = Random(seed).nextBytes(524)
        b[0] = 64; b[1] = 0; b[2] = 0; b[3] = 0
        return Base64.getEncoder().encodeToString(b) + (name?.let { " $it" } ?: "")
    }

    fun key(seed: Int, name: String? = "alex@laptop") = AdbKey.parse(line(seed, name))!!

    /** Android's getFingerprints (UsbDebuggingManager.java:291 at android-6.0.1_r81): MD5 of the decoded key. */
    fun androidFingerprint(line: String): String =
        MessageDigest.getInstance("MD5").digest(Base64.getDecoder().decode(line.split(Regex("\\s+"))[0])).joinToString(":") { "%02X".format(it) }
}

class AdbKeyTest {
    @Test fun `the fingerprint is Android's own`() {
        for (seed in 1..20) {
            val line = TestAdbKeys.line(seed)
            assertEquals(TestAdbKeys.androidFingerprint(line), AdbKey.parse(line)!!.fingerprint)
        }
    }

    @Test fun `the key alone goes to Android, the name is the computer's own words`() {
        val k = TestAdbKeys.key(1, "alex@laptop")
        assertEquals(TestAdbKeys.line(1, null), k.base64)
        assertEquals("alex@laptop", k.name)
        assertNull("no name", TestAdbKeys.key(1, null).name)
        assertNull("a name that isn't printable text is dropped", AdbKey.parse(TestAdbKeys.line(1, "a\u0007b"))!!.name)
    }

    @Test fun `only an adb key parses`() {
        assertNull(AdbKey.parse(null))
        assertNull(AdbKey.parse(""))
        assertNull("not base64", AdbKey.parse("not*base64== x@y"))
        assertNull("too short", AdbKey.parse(Base64.getEncoder().encodeToString(ByteArray(100)) + " x@y"))
        val wrongLen = Random(3).nextBytes(524).also { it[0] = 32; it[1] = 0; it[2] = 0; it[3] = 0 }
        assertNull("not a 2048-bit key", AdbKey.parse(Base64.getEncoder().encodeToString(wrongLen)))
    }
}

class AdbComputersTest {
    private val dir: File = Files.createTempDirectory("adb").toFile()
    private val file = File(dir, "adb-computers.json")
    private var now = 1_763_000_000_000L
    private val allowed = ArrayList<String>()
    private var refuse: String? = null
    private val events = ArrayList<String>()

    private fun computers() = AdbComputers(file, allow = { k -> allowed += k.base64; refuse }, event = { events += it }, clock = { now })

    private val a = TestAdbKeys.key(1, "alex@laptop")
    private val b = TestAdbKeys.key(2, "sam@desktop")

    @Test fun `a new computer waits for someone to allow it`() {
        val c = computers()
        c.asked(a)
        assertEquals(a, c.asking())
        assertTrue("nothing let in by itself", allowed.isEmpty())
    }

    @Test fun `allowed from now on, it gets in each time it connects, after a restart too`() {
        val c = computers()
        c.asked(a)
        assertEquals(AdbComputers.Allowed.FROM_NOW_ON, c.allowAsking(a.fingerprint, remember = true, who = "Alex"))
        assertEquals(listOf(a.base64), allowed)
        assertNull("answered", c.asking())
        val again = computers()
        assertEquals(listOf(a), again.list().map { it.key })
        assertEquals("Alex", again.list().single().by)
        again.asked(a)
        assertEquals(listOf(a.base64, a.base64), allowed)
        assertNull(again.asking())
        assertTrue(events.last(), events.last().startsWith("Let in adb key ${a.fingerprint} (alex@laptop)"))
    }

    @Test fun `allowed once, it isn't listed and asks again next time`() {
        val c = computers()
        c.asked(a)
        assertEquals(AdbComputers.Allowed.ONCE, c.allowAsking(a.fingerprint, remember = false, who = "Alex"))
        assertTrue(c.list().isEmpty())
        c.asked(a)
        assertEquals(listOf(a.base64), allowed)
        assertEquals(a, c.asking())
    }

    @Test fun `a different computer that asked since the page showed is refused`() {
        val c = computers()
        c.asked(a)
        c.asked(b)
        assertEquals(AdbComputers.Allowed.CHANGED, c.allowAsking(a.fingerprint, remember = true, who = "Alex"))
        assertTrue("Android got nothing", allowed.isEmpty())
        assertTrue(c.list().isEmpty())
        assertTrue(events.last(), events.last().contains("refused") && events.last().contains(a.fingerprint) && events.last().contains(b.fingerprint))
    }

    @Test fun `no computer asking, or one that asked too long ago, is refused`() {
        val c = computers()
        assertEquals(AdbComputers.Allowed.NONE, c.allowAsking(a.fingerprint, remember = true, who = "Alex"))
        c.asked(a)
        now += 11 * 60_000L
        assertNull(c.asking())
        assertEquals(AdbComputers.Allowed.NONE, c.allowAsking(a.fingerprint, remember = true, who = "Alex"))
        assertTrue(allowed.isEmpty())
    }

    @Test fun `the owner's computer, which the image lets in, isn't shown as asking`() {
        val c = computers()
        c.asked(a)
        c.letInByImage()
        assertNull(c.asking())
    }

    @Test fun `removed, it no longer gets in`() {
        val c = computers()
        c.asked(a)
        c.allowAsking(a.fingerprint, remember = true, who = "Alex")
        assertEquals(AdbComputers.Removed.DONE, c.remove(a.fingerprint, "Alex"))
        assertEquals("not on it twice", AdbComputers.Removed.NOT_LISTED, c.remove(a.fingerprint, "Alex"))
        assertTrue(computers().list().isEmpty())
        allowed.clear()
        c.asked(a)
        assertTrue(allowed.isEmpty())
        assertEquals(a, c.asking())
        assertTrue(events.any { it == "Alex removed adb key ${a.fingerprint} (alex@laptop) from the allowed list" })
    }

    @Test fun `Android refusing leaves the list as it was`() {
        val c = computers()
        c.asked(a)
        refuse = "SecurityException: MANAGE_USB"
        assertEquals(AdbComputers.Allowed.FAILED, c.allowAsking(a.fingerprint, remember = true, who = "Alex"))
        assertTrue(c.list().isEmpty())
        assertEquals("still asking", a, c.asking())
        assertTrue(events.last().contains("MANAGE_USB"))
    }

    @Test fun `the list has a limit`() {
        val c = AdbComputers(file, allow = { null }, event = { events += it }, clock = { now }, max = 2)
        for (seed in 10..11) { val k = TestAdbKeys.key(seed); c.asked(k); c.allowAsking(k.fingerprint, true, "Alex") }
        c.asked(a)
        assertEquals(AdbComputers.Allowed.FULL, c.allowAsking(a.fingerprint, remember = true, who = "Alex"))
        assertEquals("once still works", AdbComputers.Allowed.ONCE, c.allowAsking(a.fingerprint, remember = false, who = "Alex"))
    }

    /** Makes the list's file unwritable: a folder with something in it where the file goes. */
    private fun breakTheFile() {
        file.delete()
        File(file, "x").apply { parentFile.mkdirs(); writeText("x") }
    }

    @Test fun `let in but not saved, it says so and the computer is not listed`() {
        val c = computers()
        c.asked(a)
        breakTheFile()
        assertEquals(AdbComputers.Allowed.NOT_REMEMBERED, c.allowAsking(a.fingerprint, remember = true, who = "Alex"))
        assertEquals("Android let it in", listOf(a.base64), allowed)
        assertTrue(c.list().isEmpty())
    }

    @Test fun `a listed computer allowed again from the page keeps its place, also when the list can't be saved`() {
        val c = computers()
        c.asked(a)
        c.allowAsking(a.fingerprint, remember = true, who = "Alex")
        refuse = "SecurityException: busy"
        c.asked(a)
        assertEquals("its automatic approval failed", a, c.asking())
        refuse = null
        breakTheFile()
        assertEquals(AdbComputers.Allowed.FROM_NOW_ON, c.allowAsking(a.fingerprint, remember = true, who = "Sam"))
        assertEquals(listOf("Alex"), c.list().map { it.by })
    }

    @Test fun `a removal that can't be saved leaves the computer on the list`() {
        val c = computers()
        c.asked(a)
        c.allowAsking(a.fingerprint, remember = true, who = "Alex")
        breakTheFile()
        assertEquals(AdbComputers.Removed.NOT_SAVED, c.remove(a.fingerprint, "Alex"))
        assertEquals(listOf(a), c.list().map { it.key })
    }

    @Test fun `a damaged list lets no extra computer in`() {
        file.writeText("{not json")
        val c = computers()
        assertTrue(c.list().isEmpty())
        c.asked(a)
        assertTrue(allowed.isEmpty())
    }

    @Test fun `the Log gets fingerprints, never a key`() {
        val c = computers()
        c.asked(a)
        c.allowAsking(a.fingerprint, remember = true, who = "Alex")
        c.asked(a)
        c.remove(a.fingerprint, "Alex")
        c.asked(b)
        c.allowAsking(a.fingerprint, remember = true, who = "Alex")
        assertTrue(events.size >= 4)
        for (e in events) {
            assertFalse(e, e.contains(a.base64.take(40)) || e.contains(b.base64.take(40)))
            assertTrue(e, e.contains("adb key "))
        }
    }
}

class RedactAdbKeyTest {
    private val fp = TestAdbKeys.key(1).fingerprint

    @Test fun `the panel's own log keeps a fingerprint after adb key, and still removes secrets`() {
        val line = "Alex allowed adb key $fp (alex@laptop) over USB; 00:11:22:33:44:55; token=abc"
        val kept = Redact.text(line, keepAdbKeys = true)
        assertTrue(kept, kept.contains("adb key $fp"))
        assertTrue(kept, kept.contains("<mac>") && !kept.contains("abc"))
    }

    @Test fun `the shared text drops it`() {
        val shared = Redact.text("Alex allowed adb key $fp over USB")
        assertEquals("Alex allowed adb key <fingerprint> over USB", shared)
        assertFalse("a bare run of pairs stays redacted as before", Redact.text(fp, keepAdbKeys = true).contains(fp))
    }

    @Test fun `the Log page shows it whole`() {
        val log = ManageLog(Files.createTempDirectory("log").toFile())
        log.append(LogEntry(System.currentTimeMillis(), LogEntry.Level.INFO, "Manage", "Alex allowed adb key $fp over USB"))
        assertNotNull(log.read().firstOrNull { it.text.contains(fp) })
    }
}
