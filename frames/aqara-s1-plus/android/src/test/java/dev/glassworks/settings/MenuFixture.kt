package dev.glassworks.settings

import android.content.Context
import dev.glassworks.pane.PaneChoice
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.HaLink
import dev.glassworks.pane.paneSections
import dev.glassworks.pane.withHaLink
import dev.glassworks.pane.settingsOrder
import dev.glassworks.diagnostics.DiagnosticsDefinition
import dev.glassworks.screen.ScreenHardware
import dev.glassworks.KioskPrefs
import dev.glassworks.s1.PanePrefs
import dev.glassworks.s1.base.WallBaseDefinition
import dev.glassworks.s1.frame.AqaraS1Plus
import dev.glassworks.s1.frame.TimeZoneDefinition
import dev.glassworks.radio.RadioDefinition

/**
 * The S1's settings menu as MainActivity builds it, with illustrative values, for the mock-ups and view
 * tests: the core's sections, then the panes' with their on/off ([paneSections]), and the panes' ids
 * (#63 D6: the rail's Panes group and "Panes ›").
 */
object MenuFixture {
    /** A master volume on a made-up 15-step stream, at [index], for the Sound page. */
    fun volume(index: Int = 9, muted: Boolean = false) = dev.glassworks.audio.MasterVolume(
        object : dev.glassworks.audio.MediaStream {
            override val max = 15
            override var index = index
            override val fixed = false
        },
        object : dev.glassworks.audio.VolumeStore {
            override var level = -1f
            override var muted = muted
            override var raised = false
            override fun takeOldVolume(): Float? = null
        },
    ) { _, _ -> }

    /** The S1: its own ranged presence sensor, a light sensor and touch. */
    val S1_HARDWARE = ScreenHardware(ScreenHardware.Presence.Sensor(ranged = true), light = true, touch = true)

    /** Voice and Music as the app has them: a card on setup's Panes step and no settings of their own. */
    fun cardOnly(id: String, title: String, detail: String, on: () -> Boolean = { true }, apply: (Boolean) -> String? = { null }) =
        object : PaneDefinition {
            override val id = id
            override val setupChoice = PaneChoice(title, detail, on = on, apply = apply)
        }

    /** The S1's panes after the radio, as the app has them (#67): the wall base, the time zone and diagnostics, each with only its on/off. */
    fun switchOnly(context: Context): List<PaneDefinition> {
        val panePrefs = PanePrefs(context)
        return listOf(
            WallBaseDefinition(panePrefs.paneFile("wall-base")), TimeZoneDefinition(panePrefs.paneFile("time-zone")),
            DiagnosticsDefinition(panePrefs.paneFile("diagnostics")),
        )
    }

    fun model(
        context: Context, radio: RadioDefinition, deviceSettings: Boolean = true, version: String = "0.4.3", status: String = "screen on",
        panes: List<PaneDefinition> = listOf(cardOnly("voice", "Voice", "Assist and timers"), cardOnly("sendspin", "Music", "A Sendspin speaker"), radio),
        /** The app always has a factory reset (S1Settings.model); here it does nothing. */
        live: CoreSettings.Live = CoreSettings.Live(factoryReset = { done -> done(null) }),
        running: (String) -> Pane? = { null },
        blueprints: (String) -> SettingsItem.Blueprints? = { null },
        account: String? = "Signed in as Wall panel",
        /** Whether Home Assistant has the panel, as the app reads it ([S1Settings.model]). */
        link: () -> HaLink = { HaLink.CONNECTED },
        /** The panel's Wi-Fi address, as "Allow setup over the network" says it; none by default. */
        panelIp: () -> String? = { null },
        /** What the device has for the Screen page; the S1's by default. */
        hardware: ScreenHardware = S1_HARDWARE,
        /** The master volume, for the Sound page; none leaves the page out. */
        volume: dev.glassworks.audio.MasterVolume? = null,
        /** How Vitro draws the dashboard, for Labs; null while Vitro is off. */
        drawing: dev.glassworks.engine.Drawing? = null,
    ): KioskSettingsOverlay.Model {
        val core = CoreSettings(
            KioskPrefs(context), version, status = { status },
            deviceSettings = if (deviceSettings) SettingsItem.Link(AqaraS1Plus.SETTINGS_TITLE, SettingsMenu.DEVICE_SETTINGS_DETAIL) {} else null,
            licences = SettingsItem.Link(CoreSettings.LICENCES_TITLE, CoreSettings.LICENCES_DETAIL) {},
            live = live,
            hardware = hardware,
            screenBlueprints = blueprints(CoreSettings.SCREEN),
            account = { account },
            panelIp = panelIp,
            volume = volume,
            drawing = { drawing },
        )
        val sections = paneSections(panes, blueprints, running)
        return KioskSettingsOverlay.Model(withHaLink(settingsOrder(core.sections(), sections), link), version, sections.map { it.id }.toSet())
    }
}
