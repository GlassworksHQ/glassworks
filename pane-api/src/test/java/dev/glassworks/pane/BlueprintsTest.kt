package dev.glassworks.pane

import android.app.Application
import android.content.Context
import dev.glassworks.frame.Capability
import dev.glassworks.platform.AppBuild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The panes' blueprints: the links (raw GitHub pinned to the app's ref, Home Assistant's import dialog on
 * the instance and through My Home Assistant), what each button does for an administrator, someone who
 * isn't one and a panel that isn't connected, and where the card sits on a pane's page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BlueprintsTest {
    private val doorbell = Blueprint("notifications", "panes/notifications/blueprints/doorbell-panels.yaml", "Doorbell on Glassworks panels", "Rings the panels.")
    private val ask = Blueprint("notifications", "panes/notifications/blueprints/ask-panels.yaml", "Ask on the panels", "A question.")
    private val ha = "http://homeassistant.local:8123/lovelace/0"
    private val raw = "https://raw.githubusercontent.com/GlassworksHQ/glassworks/v0.5.0/panes/notifications/blueprints/doorbell-panels.yaml"
    private val rawEnc = "https%3A%2F%2Fraw.githubusercontent.com%2FGlassworksHQ%2Fglassworks%2Fv0.5.0%2Fpanes%2Fnotifications%2Fblueprints%2Fdoorbell-panels.yaml"

    // ------------------------------------------------------------------ links

    @Test fun theRefIsTheReleaseTagTheTestBuildsCommitOrMain() {
        assertEquals("v0.5.0", BlueprintLinks.ref(AppBuild("0.5.0", 500, debug = false)))
        assertEquals("28c157d", BlueprintLinks.ref(AppBuild("0.4.3", 403, debug = false, testBuild = "28c157d")))
        // a development build: the build's default version, no release set
        assertEquals("main", BlueprintLinks.ref(AppBuild("0.1.0", 1, debug = true)))
        assertEquals("main", BlueprintLinks.ref(AppBuild("0.1.0", 1, debug = false)))
        // a release's code always matches its name, also the smallest one
        assertEquals("v0.0.1", BlueprintLinks.ref(AppBuild("0.0.1", 1, debug = false)))
    }

    @Test fun theRawUrlIsPinnedToTheRef() {
        assertEquals(raw, BlueprintLinks.raw("v0.5.0", doorbell.path))
    }

    @Test fun theInstanceImportLinkOpensHomeAssistantsDialogFilledIn() {
        assertEquals("http://homeassistant.local:8123/_my_redirect/blueprint_import?blueprint_url=$rawEnc", BlueprintLinks.importOn(ha, raw))
        assertEquals("https://192.0.2.10:8123/_my_redirect/blueprint_import?blueprint_url=$rawEnc", BlueprintLinks.importOn("HTTPS://192.0.2.10:8123", raw))
        assertNull(BlueprintLinks.importOn("homeassistant.local:8123", raw))
        assertNull(BlueprintLinks.importOn("ftp://homeassistant.local", raw))
    }

    @Test fun theMyHomeAssistantLinkIsForAPhone() {
        assertEquals("https://my.home-assistant.io/redirect/blueprint_import/?blueprint_url=$rawEnc", BlueprintLinks.importMy(raw))
    }

    @Test fun createAutomationIsHomeAssistantsListFilteredToTheBlueprint() {
        assertEquals("http://homeassistant.local:8123/_my_redirect/blueprints?search=Doorbell+on+Glassworks+panels", BlueprintLinks.blueprintsOn(ha, doorbell.name))
    }

    @Test fun aBlueprintIsInHomeAssistantByItsSourceAtAnyRefOrItsImportName() {
        assertTrue(doorbell.isIn(listOf(HaBlueprint("mine/door.yaml", raw.replace("v0.5.0", "v0.4.9")))))
        assertTrue(doorbell.isIn(listOf(HaBlueprint("GlassworksHQ/doorbell-panels.yaml", null))))
        assertFalse(doorbell.isIn(listOf(HaBlueprint("someone/doorbell.yaml", "https://raw.githubusercontent.com/someone/else/main/doorbell-panels.yaml"))))
        assertFalse(doorbell.isIn(listOf(HaBlueprint("GlassworksHQ/ask-panels.yaml", null))))
    }

    // ------------------------------------------------------------------ the buttons

    private fun card(surface: BlueprintSurface, access: BlueprintAccess, base: String? = ha) = blueprintCard(listOf(doorbell, ask), surface, access, base, "v0.5.0")

    @Test fun anAdministratorOnThePanelAddsInTheDashboard() {
        val c = card(BlueprintSurface.PANEL, BlueprintAccess.Admin(emptyList()))
        assertNull(c.note)
        assertEquals(BlueprintAction.Add("http://homeassistant.local:8123/_my_redirect/blueprint_import?blueprint_url=$rawEnc"), c.rows[0].second)
        assertTrue(c.rows[1].second is BlueprintAction.Add)
    }

    @Test fun oneAlreadyInHomeAssistantOffersCreateAutomation() {
        val c = card(BlueprintSurface.PANEL, BlueprintAccess.Admin(listOf(HaBlueprint("GlassworksHQ/doorbell-panels.yaml", raw))))
        assertEquals(BlueprintAction.CreateAutomation("http://homeassistant.local:8123/_my_redirect/blueprints?search=Doorbell+on+Glassworks+panels"), c.rows[0].second)
        assertTrue("the other isn't in it", c.rows[1].second is BlueprintAction.Add)
    }

    @Test fun someoneWhoIsntAnAdministratorGetsAQrCodeAndOneLineWhy() {
        val c = card(BlueprintSurface.PANEL, BlueprintAccess.NotAdmin("Kitchen Panel"))
        assertEquals(BlueprintAction.Phone("https://my.home-assistant.io/redirect/blueprint_import/?blueprint_url=$rawEnc"), c.rows[0].second)
        assertEquals("Signed in as Kitchen Panel, not an administrator. Add them from a phone signed in as one.", c.note)
    }

    @Test fun notConnectedTheButtonsAreOffWithTheReason() {
        val off = card(BlueprintSurface.PANEL, BlueprintAccess.Off("The panel isn't signed in to Home Assistant."))
        assertEquals(listOf(BlueprintAction.Off, BlueprintAction.Off), off.rows.map { it.second })
        assertEquals("The panel isn't signed in to Home Assistant.", off.note)
        val checking = card(BlueprintSurface.PANEL, BlueprintAccess.Checking)
        assertEquals(listOf(BlueprintAction.Off, BlueprintAction.Off), checking.rows.map { it.second })
        assertEquals(CHECKING, checking.note)
        for (surface in BlueprintSurface.entries) {
            val none = card(surface, BlueprintAccess.Admin(emptyList()), base = null)
            assertEquals(listOf(BlueprintAction.Off, BlueprintAction.Off), none.rows.map { it.second })
            assertEquals(NO_ADDRESS, none.note)
        }
    }

    @Test fun theManagePageLinksHomeAssistantsDialogForEveryoneWithAnAddress() {
        for (access in listOf(BlueprintAccess.NotAdmin("Kitchen Panel"), BlueprintAccess.Off("x"), BlueprintAccess.Checking, BlueprintAccess.Admin(emptyList()))) {
            val c = card(BlueprintSurface.MANAGE, access)
            assertNull("$access", c.note)
            assertEquals("$access", BlueprintAction.Add("http://homeassistant.local:8123/_my_redirect/blueprint_import?blueprint_url=$rawEnc"), c.rows[0].second)
        }
        val inHa = card(BlueprintSurface.MANAGE, BlueprintAccess.Admin(listOf(HaBlueprint("GlassworksHQ/doorbell-panels.yaml", raw))))
        assertTrue(inHa.rows[0].second is BlueprintAction.CreateAutomation)
    }

    @Test fun theCatalogAssetParses() {
        val list = Blueprint.parse("""[{"pane": "camera", "path": "panes/camera/blueprints/x.yaml", "name": "X", "summary": "Does x."}]""")
        assertEquals(listOf(Blueprint("camera", "panes/camera/blueprints/x.yaml", "X", "Does x.")), list)
        assertEquals("x.yaml", list.single().file)
    }

    // ------------------------------------------------------------------ on a pane's page

    private val file = RuntimeEnvironment.getApplication().getSharedPreferences("blueprints-test", Context.MODE_PRIVATE)
        .also { it.edit().clear().commit() }
    private val switch = PaneSwitch(file, "notifications_enabled", default = true, title = "Notifications", detail = "", objectId = "notifications", offNote = "Off.")
    private val item = SettingsItem.Blueprints(listOf(doorbell), { s -> card(s, BlueprintAccess.Checking) }, {})

    private fun definition(own: List<SettingsItem>?, requires: Set<Capability> = emptySet()) = object : PaneDefinition {
        override val id = "notifications"
        override val switch = this@BlueprintsTest.switch
        override val requires = requires
        override fun settings() = own?.let { listOf(SettingsSection(id, "Notifications", it)) } ?: emptyList()
    }

    private fun titles(s: SettingsSection) = s.items.map { SettingsChange.titleOf(it) }

    @Test fun theCardSitsBetweenThePanesSettingsAndItsInHomeAssistantList() {
        val own = listOf(
            SettingsItem.Switch("Do not disturb", "", { false }) { _, d -> d(null) },
            SettingsItem.Group(IN_HOME_ASSISTANT, listOf(SettingsItem.Note { "esphome.x_notify" })),
        )
        val s = paneSections(listOf(definition(own)), blueprints = { if (it == "notifications") item else null }).single()
        assertEquals(listOf("Do not disturb", BLUEPRINTS, IN_HOME_ASSISTANT), titles(s))
    }

    @Test fun withoutAnInHomeAssistantListItEndsThePage() {
        val s = paneSections(listOf(definition(listOf(SettingsItem.Note { "Ready" }))), blueprints = { item }).single()
        assertEquals(BLUEPRINTS, titles(s).last())
    }

    @Test fun aPaneWithoutAPageOfItsOwnShowsItToo() {
        val s = paneSections(listOf(definition(null)), blueprints = { item }).single()
        assertTrue(titles(s).toString(), BLUEPRINTS in titles(s))
    }

    @Test fun aPaneThatIsOffOrCantRunShowsNone() {
        switch.on = false
        assertFalse(BLUEPRINTS in titles(paneSections(listOf(definition(listOf(SettingsItem.Note { "Ready" }))), blueprints = { item }).single()))
        switch.on = true
        // A pane the frame can't run has no page at all (#288), so no card either.
        val cantRun = CantRun.unless(definition(listOf(SettingsItem.Note { "Ready" }), requires = setOf(Capability.CAMERA)), emptySet())
        assertEquals(emptyList<SettingsSection>(), paneSections(listOf(cantRun), blueprints = { item }))
    }

    @Test fun aPaneWithoutBlueprintsIsAsBefore() {
        val own = listOf(SettingsItem.Note { "Ready" })
        assertEquals(titles(paneSections(listOf(definition(own))).single()), titles(paneSections(listOf(definition(own)), blueprints = { null }).single()))
    }
}
