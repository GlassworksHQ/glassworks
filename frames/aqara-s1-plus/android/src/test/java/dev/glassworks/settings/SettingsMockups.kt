package dev.glassworks.settings

import android.app.Application
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RuntimeEnvironment
import dev.glassworks.KioskPrefs
import dev.glassworks.s1.PanePrefs
import dev.glassworks.radio.FixedReadout
import dev.glassworks.radio.HelperFacts
import dev.glassworks.radio.RadioMode
import dev.glassworks.radio.ZigbeeStack
import dev.glassworks.radio.radioDefinition
import dev.glassworks.radio.ui.RadioFollowUp
import dev.glassworks.ui.Mockup

/**
 * Mock-ups of the settings menu, one per section (the Radio section's states are in RadioMockups),
 * rendered at the panel's 1440x720 px. Illustrative values only. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*SettingsMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsMockups {

    @Before fun interFromSources() = Mockup.interFromSources()

    private val off = RadioFollowUp.Facts(
        RadioMode.OFF, "ok Off: Aqara radio services stopped, radio idle", HelperFacts(true, 4), ZigbeeStack.AQARA,
        null, null, "http://homeassistant.local:8123", "192.0.2.10",
    )

    private val coordinator = off.copy(mode = RadioMode.ZIGBEE_TCP, status = "ok Zigbee coordinator (TCP): listening on :6638 for Home Assistant (192.0.2.20)")

    private fun menu(
        radioRunning: Boolean, deviceSettings: Boolean = true, facts: RadioFollowUp.Facts = off,
        extra: List<dev.glassworks.pane.PaneDefinition> = emptyList(), panelIp: String? = null,
        volume: dev.glassworks.audio.MasterVolume = MenuFixture.volume(),
        dashboards: List<dev.glassworks.dashboard.Dashboard> = listOf(
            dev.glassworks.dashboard.Dashboard("tablet", "Tablet"),
            dev.glassworks.dashboard.Dashboard("energy", "Energy"),
        ),
    ): KioskSettingsOverlay {
        val o = Mockup.show(KioskSettingsOverlay(Mockup.activity()))
        val app = RuntimeEnvironment.getApplication()
        KioskPrefs(app).apply { urlSetting = "http://homeassistant.local:8123/lovelace/panel"; zoom = 1.25f; presenceRangeM = 1.8f }
        val radio = radioDefinition(app, FixedReadout(if (radioRunning) facts else null, running = radioRunning))
        val panes = listOf(
            MenuFixture.cardOnly("voice", "Voice", "Assist and timers"),
            MenuFixture.cardOnly("sendspin", "Music", "A Sendspin speaker"),
            dev.glassworks.notifications.NotificationsDefinition(
                PanePrefs(app).paneFile("notifications"), dev.glassworks.notifications.NotificationsHub(), { "aqara-s1-plus-a1b2c3" }, now = { 0L },
            ),
            radio,
        ) + MenuFixture.switchOnly(app) + extra
        o.show(MenuFixture.model(app, radio, deviceSettings, status = "screen on · presence near · voice=idle", panes = panes, panelIp = { panelIp }, volume = volume, dashboards = { dashboards }))
        Mockup.idle()
        return o
    }

    /**
     * Made up: a pane that can't run now for a reason found while it runs (the radio without its root helper), to
     * show how the menu says so. A pane the frame lacks the hardware for isn't shown at all (#23).
     */
    private val cantRun = object : dev.glassworks.pane.PaneDefinition {
        override val id = "example"
        override fun settings() = listOf(dev.glassworks.pane.SettingsSection(
            "example", "Example pane", listOf(dev.glassworks.pane.SettingsItem.Note { "An example pane's settings." }),
            master = dev.glassworks.pane.SettingsItem.Switch("Example pane", "", { false }) { _, d -> d(null) },
            unavailable = { "its helper isn't installed" },
        ))
    }

    /** The menu on the section [id], after [then]. */
    private fun shot(name: String, o: KioskSettingsOverlay, id: String, then: (KioskSettingsOverlay) -> Unit = {}) {
        o.previewSection(id)
        then(o)
        Mockup.idle()
        Mockup.layout(o)
        Mockup.save(name, o)
    }

    @Test
    fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)

        shot("settings-01-dashboard", menu(radioRunning = true), "dashboard")
        // The Security page (#33): "Allow setup over the network" (#34) and the factory reset, as when Home Assistant has the panel.
        KioskPrefs(RuntimeEnvironment.getApplication()).haSeen = true
        shot("settings-01b-security", menu(radioRunning = true, panelIp = "192.0.2.10"), CoreSettings.SECURITY)
        // Allow was pressed: Close shows until the 30 minutes end.
        KioskPrefs(RuntimeEnvironment.getApplication()).armLanSetup()
        shot("settings-01c-security-setup-allowed", menu(radioRunning = true, panelIp = "192.0.2.10"), CoreSettings.SECURITY)
        KioskPrefs(RuntimeEnvironment.getApplication()).apply { closeLanSetup(); haSeen = false }
        shot("settings-02-radio", menu(radioRunning = true), "radio")
        shot("settings-03-screen", menu(radioRunning = true), "screen")
        // The master volume: everything the panel plays, at 60 %, then muted.
        shot("settings-03a-sound", menu(radioRunning = true), CoreSettings.SOUND)
        shot("settings-03a2-sound-muted", menu(radioRunning = true, volume = MenuFixture.volume(muted = true)), CoreSettings.SOUND)
        // #691: the Appearance page, Glassworks dark (the default), then Dusk light picked.
        shot("settings-03b-appearance", menu(radioRunning = true), CoreSettings.APPEARANCE)
        KioskPrefs(RuntimeEnvironment.getApplication()).overlayTheme = "dusk/light"
        shot("settings-03c-appearance-dusk-light", menu(radioRunning = true), CoreSettings.APPEARANCE)
        // Following Home Assistant's dark mode, with the dashboard dark: Dusk's dark orb is ringed.
        KioskPrefs(RuntimeEnvironment.getApplication()).apply { followHaDark = true; haDark = true }
        shot("settings-03d-appearance-follow-ha", menu(radioRunning = true), CoreSettings.APPEARANCE)
        KioskPrefs(RuntimeEnvironment.getApplication()).apply { followHaDark = false; haDark = null }
        // Home Assistant's own theme (its default colors, dark), picked: one orb with a half sun, half moon.
        KioskPrefs(RuntimeEnvironment.getApplication()).apply {
            haColors = dev.glassworks.theme.Themes.Palette(0xff03a9f4.toInt(), 0xffff9800.toInt(), 0xff111111.toInt())
            overlayTheme = dev.glassworks.theme.Themes.HA_PICK
        }
        shot("settings-03e-appearance-ha", menu(radioRunning = true), CoreSettings.APPEARANCE)
        KioskPrefs(RuntimeEnvironment.getApplication()).apply { haColors = null }
        KioskPrefs(RuntimeEnvironment.getApplication()).overlayTheme = "glassworks/dark"
        shot("settings-04-about", menu(radioRunning = true), "about")
        shot("settings-05-radio-service-not-running", menu(radioRunning = false), "radio")
        shot("settings-06-screen-no-device-settings", menu(radioRunning = true, deviceSettings = false), "screen")
        shot("settings-07-edit-a-value", menu(radioRunning = true), "dashboard") { it.previewEdit("Home Assistant") }
        shot("settings-08-restart-needed", menu(radioRunning = true), "dashboard") { it.previewRestart() }
        shot("settings-09-voice", menu(radioRunning = true), "voice")
        shot("settings-10-log", menu(radioRunning = true), "log")
        shot("settings-11-idle-picker", menu(radioRunning = true), "screen") { it.previewEdit("Turn off when idle") }
        shot("settings-13-radio-on", menu(radioRunning = true, facts = coordinator), "radio")
        shot("settings-14-radio-off-asks", menu(radioRunning = true, facts = coordinator), "radio") { it.previewConfirm("radio") }
        shot("settings-15-pane-cant-run", menu(radioRunning = true, extra = listOf(cantRun)), "example")
        shot("settings-17-rail-panes-open", menu(radioRunning = true), "voice") // the Panes group open: six panes, Log and About below them (the rail scrolls)
        // #67: the panes that had no on/off: the switch at the top right, and #163's "no settings yet" page.
        shot("settings-18-wall-base", menu(radioRunning = true), "wall-base")
        shot("settings-19-time-zone", menu(radioRunning = true), "time-zone")
        shot("settings-20-diagnostics", menu(radioRunning = true), "diagnostics")
        // Notifications at their default, as on a panel that never touched the switch (on since 2026-11-20).
        shot("settings-21-notifications", menu(radioRunning = true), "notifications")
        shot("settings-16-screen-scrolled", menu(radioRunning = true), "screen") { o ->
            Mockup.layout(o)
            o.previewScrollTo(Mockup.label(o, "Brightness"), alignBottom = true)
        }
    }
}
