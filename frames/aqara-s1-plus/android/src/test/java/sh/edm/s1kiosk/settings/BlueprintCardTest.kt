package sh.edm.s1kiosk.settings

import android.app.Application
import android.view.View
import android.view.ViewGroup
import dev.glassworks.manage.ManageFixture
import dev.glassworks.manage.paneRow
import dev.glassworks.manage.text
import dev.glassworks.pane.BLUEPRINTS
import dev.glassworks.pane.Blueprint
import dev.glassworks.pane.BlueprintAccess
import dev.glassworks.pane.HaBlueprint
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import dev.glassworks.pane.blueprintCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.ui.Mockup
import java.util.concurrent.atomic.AtomicInteger

/**
 * A pane's Blueprints card on both surfaces: on the panel, Add and Create automation open Home
 * Assistant's page in the dashboard and close the menu, Add from your phone shows a QR code, and the
 * buttons are off while the panel isn't connected. On the Manage page every button is a link in a new
 * tab. [PanelBlueprints] answers at once and checks again when its answer is old.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class BlueprintCardTest {
    private val ha = "http://homeassistant.local:8123"
    private val doorbell = Blueprint("notifications", "panes/notifications/blueprints/doorbell-panels.yaml", "Doorbell on Glassworks panels", "Rings the panels.")
    private val ask = Blueprint("notifications", "panes/notifications/blueprints/ask-panels.yaml", "Ask on the panels", "A question with buttons.")
    private val opened = ArrayList<String>()

    private fun item(access: () -> BlueprintAccess) =
        SettingsItem.Blueprints(listOf(doorbell, ask), { s -> blueprintCard(listOf(doorbell, ask), s, access(), ha, "v0.5.0") }, { opened += it })

    private fun section(access: () -> BlueprintAccess) = SettingsSection(
        "notifications", "Notifications", listOf(SettingsItem.Note { "Ready" }, item(access)),
        master = SettingsItem.Switch("Notifications", "", { true }) { _, d -> d(null) },
    )

    private fun menu(access: () -> BlueprintAccess): KioskSettingsOverlay {
        val a = Mockup.activity()
        val o = KioskSettingsOverlay(a)
        a.setContentView(o, ViewGroup.LayoutParams(-1, -1))
        o.show(KioskSettingsOverlay.Model(listOf(section(access)), "0.4.3", setOf("notifications")))
        o.previewSection("notifications")
        Mockup.idle()
        return o
    }

    private fun shown(o: View, text: String) = runCatching { Mockup.label(o, text).isShown }.getOrDefault(false)

    @Test fun anAdministratorsAddOpensHomeAssistantsImportInTheDashboard() {
        val o = menu { BlueprintAccess.Admin(emptyList()) }
        assertTrue(shown(o, BLUEPRINTS.uppercase()) || shown(o, BLUEPRINTS))
        val add = Mockup.label(o, "Add to Home Assistant")
        assertTrue(add.isEnabled)
        add.performClick()
        Mockup.idle()
        assertEquals(1, opened.size)
        assertTrue(opened.single(), opened.single().startsWith("$ha/_my_redirect/blueprint_import?blueprint_url=https%3A%2F%2Fraw.githubusercontent.com%2FGlassworksHQ%2Fglassworks%2Fv0.5.0%2F"))
        assertFalse("the menu closes on the dashboard", o.isShowing)
    }

    @Test fun oneInHomeAssistantOffersCreateAutomation() {
        val o = menu { BlueprintAccess.Admin(listOf(HaBlueprint("GlassworksHQ/doorbell-panels.yaml", null))) }
        assertTrue(shown(o, "In Home Assistant"))
        Mockup.label(o, "Create automation").performClick()
        assertEquals(listOf("$ha/_my_redirect/blueprints?search=Doorbell+on+Glassworks+panels"), opened)
    }

    @Test fun someoneWhoIsntAnAdministratorGetsAQrCode() {
        val o = menu { BlueprintAccess.NotAdmin("Kitchen Panel") }
        assertTrue(shown(o, "Signed in as Kitchen Panel, not an administrator. Add them from a phone signed in as one."))
        assertFalse(shown(o, "Add it from your phone"))
        Mockup.label(o, "Add from your phone").performClick()
        Mockup.idle()
        assertTrue("the QR sheet", shown(o, "Add it from your phone"))
        assertTrue("it never opens anything on the panel", opened.isEmpty())
        assertTrue(o.isShowing)
    }

    @Test fun notConnectedTheButtonsAreOffAndTheCardFollowsTheCheck() {
        var access: BlueprintAccess = BlueprintAccess.Checking
        val o = menu { access }
        val off = Mockup.label(o, "Add to Home Assistant")
        assertFalse(off.isEnabled)
        off.performClick()
        assertTrue(opened.isEmpty())
        // The check answers while the page is open: the card is drawn again on the next refresh.
        access = BlueprintAccess.Admin(emptyList())
        o.refresh()
        Mockup.idle()
        assertTrue(Mockup.label(o, "Add to Home Assistant").isEnabled)
    }

    @Test fun theManagePageLinksHomeAssistantInANewTabForBothSignIns() {
        val f = ManageFixture()
        val sec = section { BlueprintAccess.NotAdmin("Kitchen Panel") }
        f.statusFn = { f.status.copy(panes = listOf(paneRow("notifications", sec)), sections = listOf(sec)) }
        val withHa = f.Browser().also { it.signInWithHa() }.get("/notifications").text()
        assertTrue(withHa, """<a class="pill sm" href="$ha/_my_redirect/blueprint_import?blueprint_url=https%3A%2F%2Fraw.githubusercontent.com%2FGlassworksHQ%2Fglassworks%2Fv0.5.0%2Fpanes%2Fnotifications%2Fblueprints%2Fdoorbell-panels.yaml" target="_blank" rel="noopener noreferrer">""" in withHa)
        assertFalse("no QR on the Manage page", "my.home-assistant.io" in withHa)
        assertFalse(withHa, "You signed in with the panel" in withHa)

        val b = f.Browser()
        b.post("/manage/code", mapOf("next" to "/"))
        b.post("/manage/code", mapOf("next" to "/", "code" to f.shown.last()!!.code))
        val withCode = b.get("/notifications").text()
        assertTrue(withCode, "target=\"_blank\"" in withCode)
        assertTrue(withCode, "You signed in with the panel" in withCode)
    }

    @Test fun theManagePageButtonIsOffWithoutAnAddress() {
        val f = ManageFixture()
        val sec = SettingsSection("notifications", "Notifications", listOf(
            SettingsItem.Blueprints(listOf(doorbell), { s -> blueprintCard(listOf(doorbell), s, BlueprintAccess.Checking, null, "v0.5.0") }, {}),
        ))
        f.statusFn = { f.status.copy(panes = listOf(paneRow("notifications", sec)), sections = listOf(sec)) }
        val page = f.Browser().also { it.signInWithHa() }.get("/notifications").text()
        assertTrue(page, """<button class="pill sm" type="button" disabled>Add in Home Assistant</button>""" in page)
        assertFalse("_my_redirect" in page)
    }

    @Test fun panelBlueprintsAnswersAtOnceAndChecksAgainWhenOld() {
        var now = 0L
        val checks = AtomicInteger()
        val gate = java.util.concurrent.Semaphore(0)
        val p = PanelBlueprints(
            catalog = { listOf(doorbell, ask) }, ref = "v0.5.0", haBase = { ha },
            check = { checks.incrementAndGet(); gate.acquire(); BlueprintAccess.NotAdmin("Kitchen Panel") }, clock = { now },
        )
        assertEquals(null, p.item("radio") {})
        assertEquals(BlueprintAccess.Checking, p.access())
        assertEquals("one check at a time", BlueprintAccess.Checking, p.access())
        gate.release()
        repeat(200) { if (p.access() is BlueprintAccess.NotAdmin) return@repeat; Thread.sleep(5) }
        assertEquals(BlueprintAccess.NotAdmin("Kitchen Panel"), p.access())
        assertEquals(1, checks.get())
        now += PanelBlueprints.FRESH_MS + 1
        p.access()
        gate.release()
        repeat(200) { if (checks.get() == 2) return@repeat; Thread.sleep(5) }
        assertEquals("checked again once old", 2, checks.get())
        assertEquals(2, p.item("notifications") {}!!.list.size)
    }
}
