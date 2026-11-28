package dev.glassworks.settings

import android.app.Application
import dev.glassworks.pane.SettingsChange
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsPlace
import dev.glassworks.pane.SettingsSection
import dev.glassworks.pane.settingsOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import dev.glassworks.KioskPrefs
import dev.glassworks.s1.frame.AqaraS1Plus
import dev.glassworks.radio.FixedReadout
import dev.glassworks.radio.HelperFacts
import dev.glassworks.radio.RadioDefinition
import dev.glassworks.radio.RadioMode
import dev.glassworks.radio.ZigbeeStack
import dev.glassworks.radio.radioDefinition
import dev.glassworks.radio.ui.RadioFollowUp

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsMenuTest {

    private fun extras(vararg kv: Pair<String, Boolean>): (String) -> Boolean? = { k -> kv.toMap()[k] }

    private val app get() = RuntimeEnvironment.getApplication()

    private val off = RadioFollowUp.Facts(
        RadioMode.OFF, "ok Off: Aqara radio services stopped, radio idle", HelperFacts(true, 4), ZigbeeStack.AQARA,
        null, null, "http://homeassistant.local:8123", "192.0.2.10",
    )

    private fun menu(radioRunning: Boolean = true, deviceSettings: Boolean = true) =
        MenuFixture.model(app, radioDefinition(app, FixedReadout(if (radioRunning) off else null, running = radioRunning)), deviceSettings).sections

    private fun titles(items: List<SettingsItem>): List<String> = items.flatMap { i ->
        when (i) {
            is SettingsItem.Text -> listOf(i.title)
            is SettingsItem.Switch -> listOf(i.title)
            is SettingsItem.Slider -> listOf(i.title)
            is SettingsItem.Duration -> listOf(i.title)
            is SettingsItem.TimeRange -> listOf(i.title)
            is SettingsItem.Link -> listOf(i.title)
            is SettingsItem.Note -> listOf("note")
            is SettingsItem.Info -> listOf("info")
            is SettingsItem.Group -> listOf("[${i.title}]") + titles(i.items)
            is SettingsItem.Native -> listOf("native ${i.key}")
            is SettingsItem.Choice -> listOf(i.title)
            is SettingsItem.SignIn -> listOf(i.title)
            is SettingsItem.Action -> listOf("action ${i.title}")
            is SettingsItem.States -> listOf("states ${i.title}")
            is SettingsItem.Status -> listOf("status")
            is SettingsItem.Tiles -> listOf("tiles")
            is SettingsItem.Blueprints -> listOf("blueprints")
        }
    }

    @Test
    fun theNotificationExtraOpensTheMenu() {
        assertTrue(SettingsMenu.requested(extras("kiosk_settings" to true)))
        assertEquals("kiosk_settings", SettingsMenu.EXTRA)
    }

    @Test
    fun theOlderAdbExtraStillWorks() = assertTrue(SettingsMenu.requested(extras("settings" to true)))

    @Test
    fun absentOrFalseDoesNot() {
        assertFalse(SettingsMenu.requested(extras()))
        assertFalse(SettingsMenu.requested(extras("kiosk_settings" to false, "settings" to false)))
        assertFalse(SettingsMenu.requested(extras("open_settings" to true))) // that one opens Aqara's app
    }

    @Test
    fun sectionsFollowTheManagePagesNav() {
        // #33: Home Assistant & dashboard, Screen, Network, the panes, then Log, Security, About. Empty ones are left out.
        fun s(id: String, n: Int = 1) = SettingsSection(id, id, List(n) { SettingsItem.Note { "" } })
        val core = mapOf(
            SettingsPlace.ABOUT to s("about"), SettingsPlace.LOG to s("log"), SettingsPlace.DASHBOARD to s("dashboard"),
            SettingsPlace.SECURITY to s("security"), SettingsPlace.SCREEN to s("screen"), SettingsPlace.NETWORK to s("network", 0),
        )
        assertEquals(
            listOf("dashboard", "screen", "voice", "radio", "log", "security", "about"),
            settingsOrder(core, listOf(s("voice"), s("music", 0), s("radio"))).map { it.id },
        )
    }

    @Test
    fun theS1sSectionsInOrder() {
        assertEquals(listOf("dashboard", "screen", "appearance", "voice", "sendspin", "radio", "log", "security", "labs", "about"), menu().map { it.id })
        assertEquals(listOf("Home Assistant & dashboard", "Screen", "Appearance", "Voice", "Music", "Radio", "Log", "Security", "Labs", "About"), menu().map { it.title })
        assertEquals("Zigbee / Thread radio", menu().first { it.id == "radio" }.heading)
    }

    @Test
    fun everyPaneHasItsOnOff() {
        // #63 round 2: a pane's on/off is its section's, at the top right. With no device running, Voice and
        // Music can't show their state and say so (#212); the radio gives its own, which asks before turning off.
        val m = menu().associateBy { it.id }
        assertEquals("Voice", m.getValue("voice").master!!.title)
        assertFalse("voice turns on and off live (#67)", m.getValue("voice").master!!.restartsApp)
        assertEquals(listOf("note"), titles(m.getValue("voice").items))
        assertEquals(dev.glassworks.pane.NOT_RUNNING, (m.getValue("sendspin").items[0] as SettingsItem.Note).text())
        assertEquals(null, m.getValue("dashboard").master)
        val radio = m.getValue("radio").master!!
        assertFalse(radio.value()) // the fixture's radio is off
        assertEquals(null, radio.confirm(true)) // on only shows the roles
    }

    @Test
    fun radioSectionPickerThenWhoMayConnect() {
        val radio = menu().first { it.id == "radio" }.items
        assertEquals(listOf("native radio", "[Who may connect]", "IP addresses or ranges, or any", "note"), titles(radio))
        // kiosk service not running: no picker or card (nothing to drive), the allow-list stays, disabled
        val off = menu(radioRunning = false).first { it.id == "radio" }.items
        assertEquals(listOf("IP addresses or ranges, or any", "note"), titles(off))
        assertFalse((off[0] as SettingsItem.Text).enabled())
        assertEquals("The radio isn't running. Turn it on to see who may connect.", (off[1] as SettingsItem.Note).text())
    }

    @Test
    fun theSectionsAsDrawn() {
        val m = menu().associate { it.id to titles(it.items) }
        assertEquals(listOf("Home Assistant", "info", "Full screen", "Zoom", "Secure hosts"), m["dashboard"])
        // What needs someone at the panel (#33's drawing 07): "Allow setup over the network" (#34), the panel's own way to
        // open pairing for the setup page again, and the factory reset. "Close" shows only while pairing is re-armed.
        assertEquals(listOf("action ${CoreSettings.SETUP_OVER_NETWORK}", "action ${FactoryReset.TITLE}"), m["security"])
        assertEquals(
            listOf(
                "Wake when someone's near", "Presence range", "Turn off when idle", "Brightness", AqaraS1Plus.SETTINGS_TITLE,
            ),
            m["screen"],
        )
        // Experimental features, off by default: what Labs is, then Vitro.
        assertEquals(listOf("note", CoreSettings.VITRO_TITLE), m[CoreSettings.LABS])
        assertEquals(listOf("info", CoreSettings.LICENCES_TITLE), m["about"])
        assertEquals(listOf("info"), m["log"]) // the screen and sensor line moved here from About
    }

    @Test
    fun theDashboardPageSaysWhoItIsSignedInAs() {
        // Maintainer 2026-11-15: which Home Assistant account the dashboard uses, on the Home Assistant & dashboard page.
        val core = CoreSettings(
            KioskPrefs(RuntimeEnvironment.getApplication()), "0.4.3", status = { "" }, deviceSettings = null,
            licences = SettingsItem.Link(CoreSettings.LICENCES_TITLE, CoreSettings.LICENCES_DETAIL) {}, account = { "Signed in as Alex" }, hardware = MenuFixture.S1_HARDWARE,
        )
        val page = core.sections().getValue(SettingsPlace.DASHBOARD)
        assertEquals(CoreSettings.DASHBOARD_TITLE, page.title)
        val row = page.items.filterIsInstance<SettingsItem.Info>().single()
        assertEquals(CoreSettings.ACCOUNT_TITLE, row.title())
        assertEquals("Signed in as Alex", row.detail())
        // Without a stored sign-in (one made on the panel's own screen leaves none) the row is left out.
        val none = CoreSettings(
            KioskPrefs(RuntimeEnvironment.getApplication()), "0.4.3", status = { "" }, deviceSettings = null,
            licences = SettingsItem.Link(CoreSettings.LICENCES_TITLE, CoreSettings.LICENCES_DETAIL) {}, account = { null }, hardware = MenuFixture.S1_HARDWARE,
        )
        assertTrue(none.sections().getValue(SettingsPlace.DASHBOARD).items.none { it is SettingsItem.Info })
    }

    @Test
    fun aboutHasNoDebugLine() {
        val about = menu().first { it.id == "about" }.items[0] as SettingsItem.Info
        assertEquals("Glassworks 0.4.3", about.title())
        assertEquals("", about.detail())
        val log = menu().first { it.id == "log" }.items[0] as SettingsItem.Info
        assertEquals("screen on", log.detail())
    }

    @Test
    fun aqaraSettingsOnlyWhenItResolves() {
        val screen = menu(deviceSettings = false).first { it.id == "screen" }
        assertFalse(AqaraS1Plus.SETTINGS_TITLE in titles(screen.items))
        assertEquals(4, screen.items.size) // everything else stays
    }

    @Test
    fun aqaraEntryText() {
        assertEquals("Aqara settings", AqaraS1Plus.SETTINGS_TITLE)
        assertEquals("", SettingsMenu.DEVICE_SETTINGS_DETAIL)
    }

    @Test
    fun allowedNowLine() {
        assertEquals("The radio isn't running. Turn it on to see who may connect.", RadioDefinition.allowedNow(false, "192.0.2.20"))
        assertTrue(RadioDefinition.allowedNow(true, null).startsWith("Allowed now: no firewall"))
        assertTrue(RadioDefinition.allowedNow(true, "").contains("only the panel itself"))
        assertEquals("Allowed now: 192.0.2.20, 2001:db8::20", RadioDefinition.allowedNow(true, "192.0.2.20 2001:db8::20"))
    }

    @Test
    fun eachSettingWritesWhatItSays() {
        val prefs = KioskPrefs(app)
        val typed = mapOf("Home Assistant" to " ", "Zoom" to "1.5", "Secure hosts" to "ha.local")
        val problems = ArrayList<String?>()
        for (s in menu().filter { it.id == "dashboard" || it.id == "screen" }) for (i in s.items) when (i) {
            is SettingsItem.Text -> typed[i.title]?.let { v -> i.save(v) { problems += it } }
            is SettingsItem.Switch -> i.save(i.title == "Brightness") { problems += it }
            is SettingsItem.Slider -> i.save(2.1f) { problems += it }
            is SettingsItem.Duration -> i.save(300) { problems += it }
            else -> {}
        }
        assertEquals(List(8) { null }, problems)
        assertEquals(KioskPrefs.AUTO, prefs.urlSetting) // blank = auto
        assertEquals(1.5f, prefs.zoom)
        assertEquals("ha.local", prefs.extraSecureHosts)
        assertFalse(prefs.hideHeader); assertFalse(prefs.hideSidebar) // one switch for both
        assertEquals("presence", prefs.screenMode) // the wake switch said always, then the picked time put presence back (#522)
        assertEquals(300, prefs.idleOffSec)
        assertEquals(2.1f, prefs.presenceRangeM)
        assertTrue(prefs.autoBrightness)
    }

    @Test
    fun theScreenPageIsPickedNotTyped() {
        // #63 round 2: a slider for the presence range, durations from a list with their units. Nothing on
        // the page opens the keyboard.
        val screen = menu().first { it.id == "screen" }.items
        assertTrue(screen.none { it is SettingsItem.Text })
        val range = screen.filterIsInstance<SettingsItem.Slider>().single()
        assertEquals(0f, range.min); assertEquals(CoreSettings.RANGE_MAX_M, range.max); assertEquals(0.1f, range.step)
        assertEquals("Sensor's current setting", range.shown(0f))
        assertEquals("Up to 2.1 m", range.shown(2.1f))
        val idle = screen.filterIsInstance<SettingsItem.Duration>().first()
        assertEquals(listOf("After 30 s", "After 1 minute", "After 2 minutes", "After 5 minutes", "After 10 minutes", "After 30 minutes", "Never"), idle.choices.map(idle.shown))
    }

    private fun screen(live: CoreSettings.Live = CoreSettings.Live()) =
        MenuFixture.model(app, radioDefinition(app, FixedReadout(off)), live = live).sections.first { it.id == "screen" }.items

    @Test
    fun wakeWhenSomeonesNearIsTheWholePresencePolicy() {
        // CodeRabbit on #163: with wake_on_approach off, the switch read on and couldn't wake the screen.
        // On means 0.4.3's "Screen follows presence": the presence screen mode and waking on approach.
        val prefs = KioskPrefs(app)
        prefs.screenMode = "presence"; prefs.wakeOnApproach = false
        val wake = screen().filterIsInstance<SettingsItem.Switch>().first { it.title == "Wake when someone's near" }
        assertFalse(wake.value())
        wake.save(true) {}
        assertEquals("presence", prefs.screenMode); assertTrue(prefs.wakeOnApproach)
        assertTrue(wake.value())
        wake.save(false) {}
        assertEquals("always", prefs.screenMode) // stays on, as 0.4.3's switch did
        assertFalse(wake.value())
    }

    @Test
    fun settingScreenToAlwaysOnOrNeverWakesASleepingScreen() {
        val prefs = KioskPrefs(app)
        var wakes = 0
        val items = screen(live = CoreSettings.Live(screenAwake = { wakes++ }))
        val wake = items.filterIsInstance<SettingsItem.Switch>().first { it.title == CoreSettings.WAKE_TITLE }
        val idle = items.filterIsInstance<SettingsItem.Duration>().first { it.title == "Turn off when idle" }

        wake.save(true) {}
        assertEquals("turning presence wake on does not force a wake", 0, wakes)
        wake.save(false) {}
        assertEquals("switching to always-on wakes a sleeping screen", 1, wakes)
        assertEquals("always", prefs.screenMode)

        idle.save(300) {}
        assertEquals("picking a finite timeout does not wake", 1, wakes)
        idle.save(0) {}
        assertEquals("setting idle timeout to Never wakes a sleeping screen", 2, wakes)
        assertEquals(0, prefs.idleOffSec)
    }

    @Test
    fun rangeZeroPromisesNoDistance() {
        // CodeRabbit on #163: 0 leaves the sensor's threshold alone, which may not be 1.5 m.
        val prefs = KioskPrefs(app)
        prefs.presenceRangeM = 0f
        val items = screen()
        assertEquals("Sensor's current setting", items.filterIsInstance<SettingsItem.Slider>().single().shown(0f))
        assertEquals("Uses the presence sensor", items.filterIsInstance<SettingsItem.Switch>().first().detail)
        prefs.presenceRangeM = 2.1f
        assertEquals("Uses the presence sensor · up to 2.1 m", screen().filterIsInstance<SettingsItem.Switch>().first().detail)
    }

    @Test
    fun theScreenPageHasNoNightOfItsOwn() {
        // #473: Home Assistant decides when it is night. No row of the Screen page is about it.
        assertTrue(screen().none { SettingsChange.titleOf(it).contains("night", ignoreCase = true) })
        assertTrue(screen().none { it is SettingsItem.TimeRange })
    }

    @Test
    fun theScreenPageOffersPanelNightModeLast() {
        // #473: the app's blueprint list (BlueprintCatalogTest writes it) gives the Screen page Panel night mode.
        val asset = java.io.File(dev.glassworks.testing.RepoFiles.s1App, "src/main/assets/${dev.glassworks.pane.Blueprint.ASSET}").readText()
        val blueprints = PanelBlueprints({ dev.glassworks.pane.Blueprint.parse(asset) }, "main", { "http://homeassistant.local:8123" }, { dev.glassworks.pane.BlueprintAccess.Checking })
        val items = MenuFixture.model(app, radioDefinition(app, FixedReadout(off)), blueprints = { blueprints.item(it) {} }).sections.first { it.id == "screen" }.items
        val card = items.last() as SettingsItem.Blueprints
        assertEquals(listOf("Panel night mode"), card.list.map { it.name })
        assertEquals("core/blueprints/panel-night-mode.yaml", card.list.single().path)
    }

    @Test
    fun aRangeTheSensorRefusesIsSaidAndNotKept() {
        // F5: the sensor's range needs a permission; without it the slider says so and the old value stays.
        val prefs = KioskPrefs(app)
        prefs.presenceRangeM = 1.2f
        val radio = radioDefinition(app, FixedReadout(off))
        val screen = MenuFixture.model(app, radio, live = CoreSettings.Live(presenceRange = { "no permission" })).sections.first { it.id == "screen" }
        var why: String? = null
        screen.items.filterIsInstance<SettingsItem.Slider>().single().save(2f) { why = it }
        assertEquals("no permission", why)
        assertEquals(1.2f, prefs.presenceRangeM)
    }

    @Test
    fun theLabsSwitchTurnsVitroOnForTheNextStart() {
        val prefs = KioskPrefs(app)
        val vitro = menu().first { it.id == CoreSettings.LABS }.items.filterIsInstance<SettingsItem.Switch>().single()
        assertFalse("off by default", vitro.value())
        assertTrue("the engine is picked at start", vitro.restartsApp)
        var why: String? = "not saved"
        vitro.save(true) { why = it }
        assertEquals(null, why)
        assertTrue(prefs.vitroEnabled)
        assertTrue(vitro.value())
    }

    @Test
    fun theLabsPageSaysWhyTheAppTurnedVitroOffUntilItIsOnAgain() {
        val prefs = KioskPrefs(app)
        // VitroSentinel turned it off after the app crashed twice at start.
        prefs.vitroEnabled = true
        prefs.turnVitroOffAfterCrashes("ClassCastException in WeatherCardView.showMetric", 1_700_000_000_000L)
        val labs = menu().first { it.id == CoreSettings.LABS }.items
        assertEquals(listOf("note", "info", CoreSettings.VITRO_TITLE), titles(labs))
        val why = labs.filterIsInstance<SettingsItem.Info>().single()
        assertEquals(CoreSettings.VITRO_OFF_TITLE, why.title())
        assertEquals(CoreSettings.VITRO_OFF_DETAIL, why.detail())
        assertTrue("amber: something the person can act on", why.fixable())
        val vitro = labs.filterIsInstance<SettingsItem.Switch>().single()
        assertFalse(vitro.value())
        // Turned on by hand: a clean slate, and the note goes.
        vitro.save(true) { }
        assertTrue(prefs.vitroEnabled)
        assertEquals(null, prefs.vitroTurnedOff)
        assertEquals(listOf("note", CoreSettings.VITRO_TITLE), titles(menu().first { it.id == CoreSettings.LABS }.items))
        prefs.vitroEnabled = false
    }

    @Test
    fun onlyWhatGeckoReadsAtStartAsksForARestart() {
        // The same ones whose adb extras restart the app: the dashboard's address, zoom, secure hosts, and Vitro (the engine
        // is picked when the app starts).
        val restarts = menu().flatMap { it.items }.mapNotNull { i ->
            when (i) {
                is SettingsItem.Text -> i.title.takeIf { i.restartsApp }
                is SettingsItem.Switch -> i.title.takeIf { i.restartsApp }
                else -> null
            }
        }
        assertEquals(listOf("Home Assistant", "Zoom", "Secure hosts", CoreSettings.VITRO_TITLE), restarts)
    }

    @Test
    fun rowsSayTheValueInWords() {
        assertEquals("After 2 minutes", CoreSettings.afterIdle(120))
        assertEquals("After 1 minute", CoreSettings.afterIdle(60))
        assertEquals("After 45 s", CoreSettings.afterIdle(45))
        assertEquals("Never", CoreSettings.afterIdle(0))
        assertEquals("1.25", CoreSettings.number(1.25f))
        assertEquals("2", CoreSettings.number(2f))
    }
}
