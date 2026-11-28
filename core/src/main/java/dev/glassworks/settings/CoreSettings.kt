package dev.glassworks.settings

import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsItem.TextKind
import dev.glassworks.pane.SettingsPlace
import dev.glassworks.pane.SettingsSection
import dev.glassworks.screen.ScreenHardware
import dev.glassworks.theme.Themes
import dev.glassworks.KioskPrefs
import dev.glassworks.audio.MasterVolume
import dev.glassworks.discovery.HaUrls
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The settings sections the app has itself, before and after the panes': Home Assistant & dashboard, Screen, Sound, Appearance, Log,
 * Security and About (#63). Network has nothing yet. Durations are picked, the
 * presence range is a slider: nothing on the Screen page is typed (#63 round 2). Values are read when the menu opens; each change
 * applies on its own. The ones Gecko reads at start (the dashboard's address, zoom, secure hosts) ask
 * for a restart, as their adb extras always did.
 */
class CoreSettings(
    private val prefs: KioskPrefs,
    private val version: String,
    /** The screen and sensor status line, on the Log page (diagnostics, not About's). */
    private val status: () -> String,
    /** The device's own settings app, when it has one (the frame's systemSettings). */
    private val deviceSettings: SettingsItem.Link?,
    private val licences: SettingsItem.Link,
    /** What the running app does when a setting it follows live changes. */
    private val live: Live = Live(),
    /** What the device has for the Screen page: rows it can't back are left out ([ScreenHardware.of]). */
    private val hardware: ScreenHardware,
    /** The Screen page's Blueprints card (Panel night mode, #473), or null for none. */
    private val screenBlueprints: SettingsItem.Blueprints? = null,
    /** Who the dashboard is signed in as in Home Assistant ("Signed in as Alex"); null leaves the row out. */
    private val account: () -> String? = { null },
    /** The app's name, before [version] in About ([dev.glassworks.platform.AppBuild.product]). */
    private val product: String = dev.glassworks.platform.AppBuild.PRODUCT,
    /** The panel's address on the LAN ([dev.glassworks.platform.PanelAddress]), for the setup page's (null while it has none). */
    private val panelIp: () -> String? = { null },
    private val now: () -> Long = System::currentTimeMillis,
    /** The device has a PIN, pattern or password ([dev.glassworks.platform.ScreenLock]): the Screen page says what that means. */
    private val screenLock: () -> Boolean = { false },
    /** The master volume, for the Sound page; null leaves the page out. */
    private val volume: MasterVolume? = null,
) {
    class Live(
        /** Full screen: the page hides or shows HA's header and sidebar. */
        val fullScreen: () -> Unit = {},
        /** The presence sensor takes the new range: null, or why it couldn't. */
        val presenceRange: () -> String? = { null },
        /** Brightness follows the light sensor, or stops. */
        val brightness: () -> Unit = {},
        /** Ends the paired browsers of setup over the network and the setup hotspot (dev.glassworks.setup.LanSetup.close). */
        val closeSetup: () -> Unit = {},
        /** The overlays' theme changed ([KioskPrefs.overlayTheme]): they draw with it from now on. */
        val theme: () -> Unit = {},
        /** A factory reset ([FactoryReset.run]); null leaves the row out. */
        val factoryReset: ((done: (String?) -> Unit) -> Unit)? = null,
    )

    fun sections(): Map<SettingsPlace, SettingsSection> = mapOf(
        SettingsPlace.DASHBOARD to SettingsSection("dashboard", DASHBOARD_TITLE, listOfNotNull(
            SettingsItem.Text(
                "Home Assistant", HA_ADDRESS_HINT, TextKind.ADDRESS,
                value = { prefs.urlSetting.takeIf { !it.equals(KioskPrefs.AUTO, ignoreCase = true) }.orEmpty() },
                shown = { it.ifBlank { found() } }, restartsApp = true,
                // The panel's own connection: copying another panel's would sign this one's dashboard out (#466 panel run).
                perPanel = true,
            ) { raw, done ->
                val v = raw.trim()
                if (v.isEmpty() || v.equals(KioskPrefs.AUTO, ignoreCase = true)) {
                    prefs.urlSetting = KioskPrefs.AUTO
                    done(null)
                } else {
                    val normalized = HaUrls.normalizeDashboardUrl(v)
                    if (normalized == null) done(HA_ADDRESS_ERROR)
                    else {
                        prefs.urlSetting = normalized
                        done(null)
                    }
                }
            },
            account()?.let { SettingsItem.Info(title = { ACCOUNT_TITLE }, detail = { account().orEmpty() }) },
            switch("Full screen", "Hide Home Assistant's header and sidebar", { prefs.hideHeader }) {
                prefs.hideHeader = it
                prefs.hideSidebar = it
                live.fullScreen()
            },
            text(
                "Zoom", "Auto", TextKind.DECIMAL, value = { if (prefs.zoom > 0f) number(prefs.zoom) else "" },
                shown = { v -> v.toFloatOrNull()?.let { "${number(it)}×" } ?: "Auto" }, restartsApp = true,
            ) { prefs.zoom = it.toFloatOrNull() ?: 0f },
            text(
                "Secure hosts", "Other addresses the dashboard loads over HTTP, like a camera", TextKind.ADDRESS,
                value = { prefs.extraSecureHosts }, restartsApp = true,
            ) { prefs.extraSecureHosts = it },
        )),
        SettingsPlace.SCREEN to SettingsSection(SCREEN, "Screen", presence() + listOfNotNull(
            idle(),
            lock(),
            if (hardware.light) switch("Brightness", "Follows the room's light", { prefs.autoBrightness }) { prefs.autoBrightness = it; live.brightness() } else null,
            // Android's settings need a finger on the panel's own screen.
            deviceSettings?.takeIf { hardware.touch },
            // Night is Home Assistant's to decide (#473): the blueprint turns the screen off sooner and dims it.
            screenBlueprints,
        )),
        SettingsPlace.SOUND to SettingsSection(SOUND, SOUND_TITLE, volume?.let(::sound).orEmpty()),
        // The panel's own screens over the dashboard (#691): voice, timers, notifications and setup.
        SettingsPlace.APPEARANCE to SettingsSection(APPEARANCE, APPEARANCE_TITLE, listOf(
            SettingsItem.Group(THEME_TITLE, listOf(SettingsItem.Note { THEME_NOTE }, theme(), followHaDark())),
            SettingsItem.Group(CUSTOM_DARK_TITLE, listOf(SettingsItem.Note { CUSTOM_NOTE }) + customColours(dark = true)),
            SettingsItem.Group(CUSTOM_LIGHT_TITLE, customColours(dark = false)),
            assistLook(),
        )),
        SettingsPlace.LOG to SettingsSection("log", "Log", listOf(
            SettingsItem.Info(title = { "Screen and sensors" }, detail = status),
        )),
        // What needs someone at the panel (#33's drawing 07): the Manage page asks for the code the panel shows first.
        SettingsPlace.SECURITY to SettingsSection(SECURITY, "Security", listOfNotNull(
            setupOverNetwork(),
            if (prefs.lanSetupArmed) closeSetup() else null,
            live.factoryReset?.let(::factoryReset),
        )),
        SettingsPlace.ABOUT to SettingsSection("about", "About", listOf(
            SettingsItem.Info(title = { "$product $version" }, detail = { "" }),
            licences,
        )),
    )

    /**
     * The presence rows, as [hardware] has it (#471): the switch says what senses it, the range shows for a sensor
     * whose range can change, and a pane that could sense it but is off says so and opens its page. None without.
     */
    private fun presence(): List<SettingsItem> = when (val p = hardware.presence) {
        is ScreenHardware.Presence.Sensor -> listOfNotNull(
            wake(if (p.ranged) "Uses the presence sensor${range()}" else "Uses the proximity sensor"),
            if (p.ranged) rangeSlider() else null,
        )
        is ScreenHardware.Presence.Pane ->
            listOf(if (p.on) wake("Uses the ${p.title} pane") else SettingsItem.Link(WAKE_TITLE, "Turn on the ${p.title} pane to use it", section = p.id))
        ScreenHardware.Presence.None -> emptyList()
    }

    // 0.4.3's "Screen follows presence" (#63's design names it this): on, the screen wakes when
    // someone comes close and goes off when idle; off, it stays on. On means both: the screen mode
    // (HA's `Screen mode` select) and waking on approach (`wake_on_approach`, no other control). It reads what
    // ScreenPolicy wakes on (#601), on the panel and on the Manage page.
    private fun wake(detail: String) = switch(WAKE_TITLE, detail, { prefs.approachWakes }) {
        prefs.screenMode = if (it) "presence" else "always"
        if (it) prefs.wakeOnApproach = true
    }

    private fun rangeSlider() = SettingsItem.Slider(
        "Presence range", value = { prefs.presenceRangeM }, min = 0f, max = RANGE_MAX_M, step = 0.1f,
        // 0 leaves the sensor's threshold as it is (Vi5300Presence.setRange): no distance to promise.
        shown = { v -> if (v <= 0f) "Sensor's current setting" else "Up to ${number(v)} m" },
    ) { v, done ->
        val old = prefs.presenceRangeM
        prefs.presenceRangeM = v
        val why = live.presenceRange()
        if (why != null) prefs.presenceRangeM = old
        done(why)
    }

    /**
     * The master volume: everything the panel plays, whichever panes are on. Music Assistant's player volume (the Music
     * page) sits under it. The panel's switch is Home Assistant's *Speaker* mute.
     */
    private fun sound(v: MasterVolume): List<SettingsItem> = listOf(
        SettingsItem.Note { SOUND_NOTE },
        SettingsItem.Slider(VOLUME_TITLE, { (v.level * 100).roundToInt().toFloat() }, 0f, 100f, 1f, { "${it.roundToInt()} %" }) { x, done ->
            v.set(x / 100f)
            done(null)
        },
        switch(MUTE_TITLE, MUTE_DETAIL, { v.muted }) { v.mute(it) },
    )

    /**
     * "Turn off when idle", the same on every frame: Home Assistant's `Screen mode` "always" (the default since #522, and
     * what the wake switch sets when off) reads as Never, and a time here puts the mode back to presence, as Home
     * Assistant's *Idle timeout* does, so the row says what the screen does.
     */
    private fun idle() = SettingsItem.Duration(
        "Turn off when idle", value = { if (prefs.screenMode == "always") 0 else prefs.idleOffSec }, choices = IDLE_CHOICES, shown = ::afterIdle,
    ) { v, done ->
        prefs.idleOffSec = v
        // Never leaves the mode as it is, so an "always" screen stays on.
        if (v > 0) prefs.screenMode = "presence"
        done(null)
    }

    /**
     * A screen lock (#522): when the screen turns off, Android locks the device and someone has to unlock it by hand. Amber,
     * with the two ways out; while the screen stays on by itself, only the one that's left. None without a lock.
     */
    private fun lock(): SettingsItem.Info? = if (!screenLock()) null else SettingsItem.Info(
        title = { LOCK_TITLE },
        detail = { if (prefs.screenMode == "always" || prefs.idleOffSec <= 0) LOCK_ALWAYS_ON else LOCK_TURNS_OFF },
        fixable = { true },
    )

    /**
     * "Allow setup over the network" (#34): pairing for the setup page at `/setup` is open until Home Assistant
     * adds the panel; after that this opens it for 30 minutes, as the Home Assistant button and Kiln over USB do.
     */
    private fun setupOverNetwork() = SettingsItem.Action(
        title = SETUP_OVER_NETWORK,
        detail = {
            val page = panelIp()?.let { "http://$it:${dev.glassworks.auth.OAuth.SIGNIN_PORT}${dev.glassworks.setup.LanSetup.PAGE}" }
            val where = page?.let { "Open $it on a computer on this network" }
            val until = prefs.lanSetupUntil
            when {
                !prefs.haSeen -> (where ?: "On a computer on this network, open the panel's setup page") + ". It pairs until Home Assistant adds the panel."
                now() < until -> "On until ${java.text.SimpleDateFormat("HH:mm", Locale.ENGLISH).format(java.util.Date(until))}." + (where?.let { " $it." } ?: "")
                else -> "Lets a computer on this network set the panel up, for 30 minutes."
            }
        },
        label = "Allow",
        needsCode = true,
    ) { done -> prefs.armLanSetup(); done(null) }

    /** "Close setup over the network": ends a re-arm before its 30 minutes, and any setup a computer started meanwhile. */
    private fun closeSetup() = SettingsItem.Action(
        title = CLOSE_SETUP,
        detail = { "Open until ${java.text.SimpleDateFormat("HH:mm", Locale.ENGLISH).format(java.util.Date(prefs.lanSetupUntil))}. Closing it ends setup on any computer that paired." },
        label = "Close",
        needsCode = true,
    ) { done ->
        prefs.closeLanSetup()
        live.closeSetup()
        done(null)
    }

    private fun factoryReset(reset: (done: (String?) -> Unit) -> Unit) = SettingsItem.Action(
        title = FactoryReset.TITLE,
        detail = { FactoryReset.DETAIL },
        label = FactoryReset.LABEL,
        confirm = SettingsItem.Confirm("${FactoryReset.TITLE}?", FactoryReset.CONFIRM_DETAIL, FactoryReset.CONFIRM_ACTION),
        needsCode = true,
        run = reset,
    )

    /** Where the dashboard loads from when its address is automatic. */
    private fun found(): String = prefs.haBase?.let { b ->
        "${b.substringAfter("://").substringBefore("/").substringBefore(":")} · found automatically"
    } ?: "Found automatically"

    /** " · up to 1.8 m", or nothing while the sensor keeps its own setting. */
    private fun range(): String = if (prefs.presenceRangeM > 0f) " · up to ${number(prefs.presenceRangeM)} m" else ""

    private fun text(
        title: String, hint: String, kind: TextKind, value: () -> String,
        shown: (String) -> String = { it }, restartsApp: Boolean = false, perPanel: Boolean = false, save: (String) -> Unit,
    ) = SettingsItem.Text(title, hint, kind, value, shown = shown, restartsApp = restartsApp, perPanel = perPanel, save = { v, done -> save(v.trim()); done(null) })

    /**
     * The theme picker (#691): one card per theme with its light and its dark orb; a tap on an orb picks that theme in
     * that variant. Glassworks, dark is the default, today's look.
     */
    private fun theme() = SettingsItem.Choice(
        THEME_TITLE,
        // A saved custom theme (#691's Custom themes) is one more card, and shows as picked when it is.
        value = { prefs.overlayPick.id },
        // Custom is always a card: Glassworks' colors until some are picked below. Home Assistant's is one once the
        // dashboard has shown its colors: one orb, light or dark as HA is.
        options = { Themes.options(custom(), prefs.haTheme) },
        swatch = { id ->
            val p = Themes.pick(id, custom(), prefs.haTheme)
            val ha = p.theme.id == Themes.HA
            val note = when { p.theme === Themes.GLASSWORKS -> "Default"; ha -> HA_THEME_NOTE; else -> "" }
            SettingsItem.Swatch(p.theme.name, note, p.dark, p.palette.a, p.palette.b, p.palette.ground, auto = ha)
        },
    ) { v, done ->
        // Custom picked before any color: its starting colors become the saved ones, so the pick holds.
        if (v.startsWith(Themes.CUSTOM + "/") && prefs.customTheme == null) prefs.customTheme = Themes.CUSTOM_START
        prefs.overlayTheme = v
        live.theme()
        done(null)
    }

    /** How a voice run shows (#691): the orb, or Spark's edge glow. Two cards on the Manage page, a choice on the panel. */
    private fun assistLook() = SettingsItem.Choice(
        ASSIST_TITLE,
        value = { prefs.assistLook },
        options = { listOf(KioskPrefs.ASSIST_ORB to "Orb", KioskPrefs.ASSIST_GLOW to "Edge glow") },
        cards = { id -> if (id == KioskPrefs.ASSIST_GLOW) ASSIST_GLOW_LINE else ASSIST_ORB_LINE },
    ) { v, done ->
        prefs.assistLook = v
        done(null)
    }

    /**
     * The picked theme turns light or dark with Home Assistant (#691): the variant follows the dashboard's dark mode, as
     * the kiosk extension reads it. The picker then rings the variant shown now.
     */
    private fun followHaDark() = switch(FOLLOW_HA_DARK, FOLLOW_HA_DARK_DETAIL, { prefs.followHaDark }) {
        prefs.followHaDark = it
        live.theme()
    }

    /** The custom theme as saved, or the one it starts as. */
    private fun custom() = prefs.customTheme ?: Themes.CUSTOM_START

    /**
     * The custom theme's three colors in one variant (#691's Custom themes): its two lights and its ground. Changing
     * one saves the theme and picks Custom in that variant, so the change shows at once. Text on the overlays comes
     * from the ground's contrast ([Themes.Palette.ink]), so any ground stays readable.
     */
    private fun customColours(dark: Boolean): List<SettingsItem> {
        fun palette() = custom().let { if (dark) it.dark else it.light }
        fun row(title: String, ground: Boolean, get: (Themes.Palette) -> Int, with: (Themes.Palette, Int) -> Themes.Palette) =
            SettingsItem.Text(title, "#rrggbb", SettingsItem.TextKind.COLOR, value = { Themes.hex(get(palette())) },
                suggestions = { Themes.suggestions(ground) }) { v, done ->
                val c = Themes.colour(v) ?: return@Text done("Use a color like #38bdf8.")
                val t = custom()
                prefs.customTheme = if (dark) Themes.custom(with(t.dark, c), t.light) else Themes.custom(t.dark, with(t.light, c))
                prefs.overlayTheme = Themes.Pick(Themes.CUSTOM_START, dark).id
                live.theme()
                done(null)
            }
        return listOf(
            row(CUSTOM_LIGHT_A, ground = false, { it.a }) { p, c -> Themes.Palette(c, p.b, p.ground) },
            row(CUSTOM_LIGHT_B, ground = false, { it.b }) { p, c -> Themes.Palette(p.a, c, p.ground) },
            row(CUSTOM_GROUND, ground = true, { it.ground }) { p, c -> Themes.Palette(p.a, p.b, c) },
        )
    }

    private fun switch(title: String, detail: String, value: () -> Boolean, save: (Boolean) -> Unit) =
        SettingsItem.Switch(title, detail, value) { v, done -> save(v); done(null) }

    companion object {
        /** The dashboard's page: where Home Assistant is and how its dashboard shows. */
        const val DASHBOARD_TITLE = "Home Assistant & dashboard"
        const val HA_ADDRESS_HINT = "Found automatically (e.g. http://homeassistant.local:8123)"
        const val HA_ADDRESS_ERROR = "Enter a Home Assistant address like http://homeassistant.local:8123 or 192.0.2.10:8123, or leave blank to find it automatically."
        /** The Screen page's id, and its blueprints' `pane` in the app's blueprint list (core/src/main/java/dev/glassworks/screen/page.json). */
        const val SCREEN = "screen"
        const val ACCOUNT_TITLE = "Dashboard sign-in"
        /** The Sound page's id: the master volume. */
        const val SOUND = "sound"
        const val SOUND_TITLE = "Sound"
        const val SOUND_NOTE = "Sets the volume of everything the panel plays: voice replies, timers, notifications, music, and the dashboard."
        const val VOLUME_TITLE = "Volume"
        const val MUTE_TITLE = "Mute"
        const val MUTE_DETAIL = "Critical notifications still play at full volume"
        /** The Appearance page's id (#691). */
        const val APPEARANCE = "appearance"
        const val APPEARANCE_TITLE = "Appearance"
        const val THEME_TITLE = "Theme"
        const val THEME_NOTE = "Applies to voice, timers, notifications, and setup. Your dashboard keeps its own theme."
        const val FOLLOW_HA_DARK = "Follow Home Assistant's dark mode"
        /** Under Home Assistant's theme card: where its colors come from. */
        const val HA_THEME_NOTE = "From your dashboard"
        const val FOLLOW_HA_DARK_DETAIL = "Automatically switch between light and dark when your dashboard does"
        const val CUSTOM_DARK_TITLE = "Custom theme, dark"
        const val CUSTOM_LIGHT_TITLE = "Custom theme, light"
        const val CUSTOM_NOTE = "Pick your own colors. Changing one switches to Custom, and text adjusts automatically to stay readable."
        const val CUSTOM_LIGHT_A = "Accent"
        const val CUSTOM_LIGHT_B = "Second accent"
        const val CUSTOM_GROUND = "Background"
        const val ASSIST_TITLE = "Assist"
        const val ASSIST_ORB_LINE = "A glass orb with what you said and the reply, over a dimmed dashboard."
        const val ASSIST_GLOW_LINE = "Light runs along the screen's edge. The dashboard stays in view."
        const val WAKE_TITLE = "Wake when someone's near"
        const val SETUP_OVER_NETWORK = "Allow setup over the network"
        const val CLOSE_SETUP = "Close setup over the network"
        /** The Security page's id: a System page on both surfaces ([dev.glassworks.manage.SettingsNav.SYSTEM]). */
        const val SECURITY = "security"
        const val LOCK_TITLE = "Screen lock"
        const val LOCK_TURNS_OFF = "This panel has a screen lock, so turning the screen off locks the device. " +
            "Remove the lock in Android's settings, or keep the screen always on."
        const val LOCK_ALWAYS_ON = "This panel has a screen lock. If Home Assistant turns the screen off, someone will " +
            "need to unlock it. Remove the lock in Android's settings."
        const val LICENCES_TITLE = "Open source licenses"
        const val LICENCES_DETAIL = "Third-party software included with Glassworks"

        /** The presence sensor's furthest (Home Assistant's `Presence range` number goes as far). */
        const val RANGE_MAX_M = 2.5f
        val IDLE_CHOICES = listOf(30, 60, 120, 300, 600, 1800, 0)

        /** "After 2 minutes", "After 45 s", "Never" (0). */
        fun afterIdle(sec: Int): String = when {
            sec <= 0 -> "Never"
            sec % 60 == 0 -> "After ${sec / 60} minute${if (sec == 60) "" else "s"}"
            else -> "After $sec s"
        }

        /** 1.25, 1.5, 2: no trailing zeros. */
        fun number(v: Float): String = String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')
    }
}
