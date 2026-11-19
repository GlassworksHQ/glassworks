package dev.glassworks.manage

import android.app.Application
import dev.glassworks.manage.ManageFixture
import dev.glassworks.manage.paneRow
import dev.glassworks.manage.text
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import dev.glassworks.s1.base.BaseLink
import dev.glassworks.s1.base.BaseRes
import dev.glassworks.s1.base.WallBasePage
import dev.glassworks.settings.PaneFixtures
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * #269 (CodeRabbit on #336): a relay switched from the Manage page sends the base the write for that
 * channel and value, through the wall base's own link, and the page shows the new state only once the
 * base reports it, as the panel's toggle does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WallBaseRelayManageTest {
    private val app = RuntimeEnvironment.getApplication()
    private val panes = PaneFixtures(app)

    @After fun shutdown() = panes.shutdown()

    /** A connected link that records each write: (resource, value). */
    private val writes = LinkedBlockingQueue<Pair<String, Int>>()

    private inner class RecordingLink : BaseLink {
        override val describe = "test link"
        override val isConnected = true
        override val pollRes = emptyList<String>()
        override val pollIntervalMs = 60_000L
        override val confirmWrites = false
        override fun start() {}
        override fun tick(now: Long) {}
        override fun read(res: List<String>) {}
        override fun write(res: String, value: Int) { writes += res to value }
        override fun stop() {}
    }

    @Test fun theOppositeValueReachesTheBaseAndShowsOnceTheBaseReportsIt() {
        // A three-channel switch base, relay 1 on, relays 2 and 3 off.
        val p = panes.wallBase(newLink = { _, _, _ -> RecordingLink() })
        p.start()
        val model = panes.model(listOf(p))
        val f = ManageFixture()
        f.statusFn = { model().let { m -> f.status.copy(panes = m.sections.filter { it.id in m.panes }.map { paneRow(it.id, it) }, sections = m.sections) } }
        val b = f.Browser().also { it.signInWithHa() }
        val key = "${WallBasePage.RELAYS}/${WallBasePage.relayTitle(2)}"
        fun shown(): String = Regex("""name="key" value="${Regex.escape(key)}"><button[^>]*aria-checked="(\w+)"""")
            .find(b.get("/wall-base").text())!!.groupValues[1]
        assertEquals("relay 2 starts off", "false", shown())

        val r = b.post("/settings", mapOf("csrf" to b.csrf(), "section" to "wall-base", "key" to key, "value" to "on"))
        assertEquals(303, r.status)
        assertEquals("the base got relay 2's write, on", BaseRes.relay(2) to 1, writes.poll(5, TimeUnit.SECONDS))
        assertNull("and nothing else", writes.poll(300, TimeUnit.MILLISECONDS))
        assertEquals("not shown on until the base says so", "false", shown())

        // The base reports relay 2 on.
        p.tracker.apply(mapOf(BaseRes.relay(2) to 1), 0)
        assertEquals("now it shows on", "true", shown())
        p.stop()
    }
}
