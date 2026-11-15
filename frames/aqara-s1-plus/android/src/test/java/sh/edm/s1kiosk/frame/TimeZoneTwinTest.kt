package sh.edm.s1kiosk.frame

import android.app.Application
import dev.glassworks.manage.ManageFixture
import dev.glassworks.manage.paneRow
import dev.glassworks.pane.SettingsChange
import dev.glassworks.pane.SettingsItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.settings.PaneFixtures
import java.util.TimeZone

/** #269: the time zone page's twin on the Manage page (CodeRabbit on #331: UTC, and a match that was dropped). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TimeZoneTwinTest {
    private val app = RuntimeEnvironment.getApplication()
    private val panes = PaneFixtures(app)

    @After fun shutdown() = panes.shutdown()

    private val choices = TimeZoneChoices(TimeZone.getAvailableIDs().toList()) { id -> android.icu.util.TimeZone.getCanonicalID(id) ?: id }

    private fun zone(p: TimeZonePane) = TimeZoneDefinition.twin(p, choices, HomeAssistantZoneMatch(choices, { it.run(); true }))
        .filterIsInstance<SettingsItem.Choice>().single()

    /** UTC's option is `UTC`, its current name `Etc/UTC`: saved and read again, the list still has it picked. */
    @Test fun utcSavedIsUtcPicked() {
        val p = panes.timeZone()
        var answer: String? = "none"
        SettingsChange.change(zone(p), "UTC") { answer = it }
        assertNull(answer)
        val z = zone(p)
        assertEquals("UTC", z.value())
        assertTrue(z.options().any { it.first == "UTC" })
        assertTrue("the row says the zone as the panel's page does", SettingsChange.shown(z).startsWith(choices.name("UTC") + " · UTC+00:00"))

        val model = panes.model(listOf(p))
        val f = ManageFixture()
        f.statusFn = { model().let { m -> f.status.copy(panes = m.sections.filter { it.id in m.panes }.map { paneRow(it.id, it) }, sections = m.sections) } }
        val b = f.Browser().also { it.signInWithHa() }
        assertTrue("the Manage list has UTC selected", b.get("/time-zone?edit=Time+zone").body.decodeToString().contains("<option value=\"UTC\" selected>UTC</option>"))
    }

    /** A match the pane's thread took but never ran (the pane stopped) is tried again, not waited on for ever. */
    @Test fun aDroppedMatchIsTriedAgain() {
        var now = 0L
        val taken = ArrayList<Runnable>()
        val m = HomeAssistantZoneMatch(choices, { taken += it; true }, clock = { now })
        assertNull(m.get("CET-1CEST,M3.5.0,M10.5.0/3", "Europe/Madrid"))
        assertNull(m.get("CET-1CEST,M3.5.0,M10.5.0/3", "Europe/Madrid"))
        assertEquals("one match while it may still run", 1, taken.size)
        now += HomeAssistantZoneMatch.LOST_MS
        assertNull(m.get("CET-1CEST,M3.5.0,M10.5.0/3", "Europe/Madrid"))
        assertEquals("dropped: queued again", 2, taken.size)
        taken.last().run()
        assertTrue(m.get("CET-1CEST,M3.5.0,M10.5.0/3", "Europe/Madrid") is TimeZoneChoices.HomeAssistantZone.Same)
        taken.first().run() // the old one, late: changes nothing
        assertEquals(2, taken.size)
    }
}
