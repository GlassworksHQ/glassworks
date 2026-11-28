package dev.glassworks.s1.manage

import dev.glassworks.manage.AdbComputers.Allowed
import dev.glassworks.manage.AdbComputers.Removed
import dev.glassworks.manage.AdbKey
import dev.glassworks.manage.ManageFixture
import dev.glassworks.manage.ManageUsbDebugging
import dev.glassworks.manage.text
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.Collections
import kotlin.random.Random

/** The S1's computers over USB adb, against a fake frame agent that answers as glassworks_agent.sh does. Made-up keys only. */
class S1AdbComputersTest {
    private fun key(seed: Int, name: String): AdbKey {
        val b = Random(seed).nextBytes(524).also { it[0] = 64; it[1] = 0; it[2] = 0; it[3] = 0 }
        return AdbKey.parse(Base64.getEncoder().encodeToString(b) + " " + name)!!
    }

    private val installer = key(1, "kiln@glassworks")
    private val laptop = key(2, "me@laptop")
    private val desk = key(3, "old@desk")
    private val root = Files.createTempDirectory("s1adb").toFile()
    private val pub = File(root, "pub").apply { mkdirs() }
    private val request = File(root, "files/adb/request")
    private val events = Collections.synchronizedList(ArrayList<String>())
    private var now = 1_800_000_000_000L
    private val computers = S1AdbComputers(pub, request, File(root, "manage/adb-notes.json"), { events += it }, clock = { now },
        answerMs = 2_000, pollMs = 10)

    /** What the fake agent answers, by operation; null: it never takes the request. */
    @Volatile private var answer: ((String, String) -> String)? = { _, _ -> "ok allowed" }
    @Volatile private var running = true
    private val requests = Collections.synchronizedList(ArrayList<String>())
    private val agent = Thread {
        while (running) {
            if (request.isFile && answer != null) {
                val (id, op, fp) = request.readText().trim().split(' ')
                request.delete()
                requests += "$op $fp"
                File(pub, "adb-result").writeText("$id ${answer!!(op, fp)}\n")
            }
            Thread.sleep(5)
        }
    }.apply { isDaemon = true; start() }

    @After fun stop() {
        running = false
        agent.join(1_000)
    }

    private fun asks(k: AdbKey, atMs: Long = now) = File(pub, "adb-asking").writeText("${atMs / 1000} ${k.fingerprint} ${k.base64} ${k.name}\n")
    private fun lists(vararg ks: Pair<String, AdbKey>) =
        File(pub, "adb-keys").writeText(ks.joinToString("") { (kind, k) -> "$kind ${k.fingerprint} ${k.base64} ${k.name}\n" })

    @Test fun `the computer asking is the agent's, while it's recent and its fingerprint matches its key`() {
        assertNull(computers.asking())
        asks(laptop)
        assertEquals(laptop, computers.asking())
        asks(laptop, now - 11 * 60_000)
        assertNull("older than the agent's 10 minutes", computers.asking())
        File(pub, "adb-asking").writeText("${now / 1000} ${desk.fingerprint} ${laptop.base64} ${laptop.name}\n")
        assertNull("a fingerprint that isn't the key's", computers.asking())
        File(pub, "adb-asking").writeText("")
        assertNull("nobody asking", computers.asking())
    }

    @Test fun `the list holds the installer's key first, marked, and shows who added the rest here`() {
        lists("added" to desk, "installer" to installer)
        assertEquals(listOf(installer to true, desk to false), computers.list().map { it.key to it.installer })
        assertEquals("allowed on the panel itself: nobody recorded it", 0L, computers.list().single { it.key == desk }.addedMs)
        asks(laptop)
        assertEquals(Allowed.FROM_NOW_ON, computers.allowAsking(laptop.fingerprint, remember = false, who = "Alex"))
        lists("installer" to installer, "added" to desk, "added" to laptop)
        val l = computers.list().single { it.key == laptop }
        assertEquals(now to "Alex", l.addedMs to l.by)
    }

    @Test fun `allow sends the agent only the fingerprint of the computer asking`() {
        asks(laptop)
        assertEquals(Allowed.CHANGED, computers.allowAsking(desk.fingerprint, true, "Alex"))
        assertTrue("nothing sent for another computer", requests.isEmpty())
        assertEquals(Allowed.FROM_NOW_ON, computers.allowAsking(laptop.fingerprint, true, "Alex"))
        assertEquals(listOf("allow ${laptop.fingerprint}"), requests.toList())
        assertTrue(events.last().contains("allowed adb key ${laptop.fingerprint} (me@laptop) over USB"))
        assertFalse("never the key in the Log", events.any { it.contains(laptop.base64.take(40)) })
    }

    @Test fun `the agent's refusals come back as the card's results`() {
        asks(laptop)
        answer = { _, _ -> "error changed" }
        assertEquals(Allowed.CHANGED, computers.allowAsking(laptop.fingerprint, true, "Alex"))
        answer = { _, _ -> "error none-asking" }
        assertEquals(Allowed.NONE, computers.allowAsking(laptop.fingerprint, true, "Alex"))
        answer = { _, _ -> "error couldn't write the adb key list" }
        assertEquals(Allowed.FAILED, computers.allowAsking(laptop.fingerprint, true, "Alex"))
        assertTrue(events.last().contains("couldn't write the adb key list"))
    }

    @Test fun `no answer from the agent says so and takes the request back`() {
        asks(laptop)
        answer = null
        assertEquals(Allowed.NO_ANSWER, computers.allowAsking(laptop.fingerprint, true, "Alex"))
        assertFalse("not left to act later than the page says", request.exists())
    }

    @Test fun `remove takes any listed computer off, the installer's too`() {
        lists("installer" to installer, "added" to desk)
        answer = { _, _ -> "ok removed" }
        assertEquals(Removed.NOT_LISTED, computers.remove(laptop.fingerprint, "Alex"))
        assertTrue("one not on the list never reaches the agent", requests.isEmpty())
        assertEquals(Removed.DONE, computers.remove(desk.fingerprint, "Alex"))
        assertEquals(Removed.DONE, computers.remove(installer.fingerprint, "Alex"))
        assertEquals(listOf("remove ${desk.fingerprint}", "remove ${installer.fingerprint}"), requests.toList())
        answer = { _, _ -> "error couldn't write the adb key list" }
        assertEquals(Removed.NOT_SAVED, computers.remove(desk.fingerprint, "Alex"))
    }

    @Test fun `the card on the S1 has no each-time switch, since every computer allowed stays`() {
        val f = ManageFixture(usb = ManageUsbDebugging(computers, { "09:41" }, { events += it }))
        asks(laptop)
        lists("installer" to installer, "added" to desk)
        val page = f.Browser().apply { signInWithHa() }.get("/security").text()
        assertTrue(page.contains(">Allow this computer</button>"))
        assertFalse(page.contains("Let it in each time it connects"))
        assertTrue(page.contains(ManageUsbDebugging.ALLOWED_ELSEWHERE))
        assertTrue("Kiln's key, named and removable", page.contains("<b>Kiln</b><span>${ManageUsbDebugging.INSTALLED}</span>"))
        assertTrue(page.contains("""name="fingerprint" value="${installer.fingerprint}"><button class="pill sm" type="submit">Remove</button>"""))
        lists()
        val empty = f.Browser().apply { signInWithHa() }.get("/security").text()
        assertTrue("nothing listed: no promise that the installer always gets in", empty.contains(ManageUsbDebugging.NONE_ALLOWED))
    }
}
