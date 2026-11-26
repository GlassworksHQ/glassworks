/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.frame

import android.content.Context
import android.content.Intent
import android.provider.Settings
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.SettingsSection
import dev.glassworks.platform.Platform
import dev.glassworks.screen.LightSource
import dev.glassworks.screen.PresenceSource
import java.util.concurrent.ScheduledExecutorService

/**
 * One device the core runs on. The app makes one frame at start and hands it to what needs it.
 * It holds only what the core asks a device today. The rest of the planned shape (screen off,
 * installer, panes the user can turn off) comes in later migration steps.
 */
interface Frame {
    /** The `board` TXT record in the device's mDNS advert. Home Assistant shows it. Never change it for a frame. */
    val board: String

    /** How its devices name themselves to Home Assistant ([DeviceName]). */
    val namedBy: NamedBy

    /**
     * The frame's own name, as `name` in frames/<id>/design/frame.json ("Aqara S1 Plus"). Home Assistant
     * shows it for a frame [NamedBy.FRAME]; null or blank there, the device's model stands in.
     */
    val displayName: String? get() = null

    /** Who makes the device and what they call it, for Home Assistant's device page, Music Assistant and Improv. */
    val hardware: Hardware

    /** The hardware panes can use, as frame.json's `provides`. A pane that [dev.glassworks.pane.PaneDefinition.requires] more can't run. */
    val provides: Set<Capability>

    /** Whether someone is near, for the screen policy. Null when the device has no sensor for it. */
    val presence: PresenceSource?

    /** Ambient light, for the screen policy's brightness curve. Null when the device has no light sensor. */
    val light: LightSource?

    /**
     * The `type` of the thermal zone that measures the CPU, as in `/sys/class/thermal/thermal_zoneN/type`.
     * Null when the device has none, and then the diagnostics pane has no temperature entity.
     */
    val cpuThermalZone: String? get() = null

    /**
     * The most pixels the camera and its stream may run at (frame.json's `camera.maxPixels`), for a device whose memory
     * can't hold more next to the page; null (the default) for the camera pane's own limit, 1080p.
     */
    val cameraMaxPixels: Int? get() = null

    /**
     * How the camera's sensor really sits (frame.json's `camera.sensorDegrees`), for a device whose camera reports its
     * mounting wrong; null (the default) to go by Android's `SENSOR_ORIENTATION`. The lululemon Mirror's says 0, and
     * its stills came out upside down.
     */
    val cameraSensorDegrees: Int? get() = null

    /** The device's own settings screens, best first. [systemSettings] picks the first that opens. */
    /**
     * The boot animation's last frame and where it sits on the screen (px), so the app's first screen
     * continues it without a cut (#63); null when the frame has none.
     */
    val bootRest: BootRest? get() = null

    /**
     * The frame's agent (docs/frame-agent.md): what it asks of the root side, by capability, never by frame id.
     * [NoAgent] (the default) for a frame without one.
     */
    val agent: FrameAgent get() = NoAgent

    /**
     * The frame's own version and releases (#139), or null on a device its vendor updates (any generic Android device):
     * then it has no Frame version row and no Frame update entity.
     */
    val frameRelease: FrameRelease? get() = null

    /** A short name for the panel on its own pages (the S1's "S1 Plus"); null to use its friendly name. */
    val shortName: String? get() = null

    /** What the settings call the device's own settings app when it isn't Android's (the S1's: Aqara's); null for Android's. */
    val systemSettingsTitle: String? get() = null

    /**
     * Whether the panel's screen takes a tap. [Input.NONE] (a screen whose touch doesn't work, like the
     * lululemon Mirror's): the panel only displays. Setup shows its address and a QR code for the setup page
     * over the network, and the pairing code without a Close button, and never waits for a tap (#34).
     */
    val input: Input get() = Input.TOUCH

    /**
     * How the app's screen stands on this device: [Orientation.LANDSCAPE] or [Orientation.PORTRAIT], either way up, for
     * a device that is always held one way (the S1, a Pixel Tablet on its dock), [Orientation.PORTRAIT_FIXED] for a
     * screen that only one way up is right for (the lululemon Mirror), or [Orientation.ANY] to turn as Android's
     * auto-rotate or the person's choice says. Read when the activity starts.
     */
    val orientation: Orientation get() = Orientation.LANDSCAPE

    /**
     * Whether the device can sit on a dock, and so whether Home Assistant gets the *Docked* sensor
     * (`dev.glassworks.dock.Docked`). [Dock.NO] by default: a panel on a wall has none.
     */
    val dock: Dock get() = Dock.NO

    /**
     * How the app puts the screen to sleep: [SleepMode.LOCK] by default, Android's own screen off. A frame whose
     * display breaks when Android turns it off says [SleepMode.BACKLIGHT_OFF] (the lululemon Mirror's LVDS bridge,
     * frames/lululemon-mirror/research/display-sleep-lines.md).
     */
    val sleepMode: SleepMode get() = SleepMode.LOCK

    /**
     * Work the frame runs while the app's service runs, or null for none (the S1's: Wi-Fi scans while the
     * screen is off, #3). [onForegroundOnly] asks the service to take the types it may have now, when the
     * work found it needs one it didn't have (the location type for a scan).
     */
    fun serviceWork(context: Context, onForegroundOnly: () -> Unit): ServiceWork? = null

    fun systemSettingsIntents(): List<Intent>

    /** Where to choose a Wi-Fi network, best first (the setup screen's "Choose network"). */
    fun wifiSettingsIntents(): List<Intent> = listOfNotNull(
        if (Platform.hasSettingsPanel) Intent(Settings.Panel.ACTION_WIFI) else null,
        Intent(Settings.ACTION_WIFI_SETTINGS),
    )

    /**
     * The definitions of the panes in [panes]: their setup steps, settings sections and adb extras,
     * for the whole process. The core puts them after its own, in this order.
     */
    fun paneDefinitions(context: Context): List<PaneDefinition> = emptyList()

    /**
     * The panes only this device has, for its own hardware and settings. The app builds them once per
     * device run, among its own, and the device runs them like any other. [device] is the device's thread.
     */
    fun panes(ctx: PaneContext, device: ScheduledExecutorService): List<Pane>

    /**
     * The frame's Device page: settings and facts about the device that belong to no pane. Every settings
     * surface lists it under Panel, named after the frame (its title). [running] gives a running pane by
     * id, to read what it knows. Null for no page. Read again whenever the settings are.
     */
    fun deviceSection(context: Context, running: (String) -> Pane? = { null }): SettingsSection? = null
}

/** The first of [Frame.systemSettingsIntents] that resolves on this device, ready to start from any context. Null when none does. */
fun Frame.systemSettings(context: Context): Intent? {
    val pm = context.packageManager
    return systemSettingsIntents().firstOrNull { it.resolveActivity(pm) != null }
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** Opens [systemSettings]. False when the device has no settings screen or it did not start. */
fun Frame.openSystemSettings(context: Context): Boolean {
    val i = systemSettings(context) ?: return false
    return runCatching { context.startActivity(i) }.isSuccess
}

/**
 * [FRAME]: every device on the frame is the same product, so it goes by the frame: `aqara-s1-plus-a1b2c3`,
 * "Aqara S1 Plus". [MODEL]: the frame stands for many devices (generic-android), so each goes by its own
 * model: `pixel-tablet-a1b2c3`, "Pixel Tablet". Explicit, so a display name added to such a frame later
 * doesn't rename every device on it.
 */
enum class NamedBy { FRAME, MODEL }

/** Whether a frame's screen takes a tap ([Frame.input]). */
enum class Input { TOUCH, NONE }

/** Whether a frame's device can dock ([Frame.dock]). */
enum class Dock {
    /** It never docks (the S1): no *Docked* sensor, and nothing listens for a dock. */
    NO,

    /** It might (any Android device): the sensor comes once the device has reported a dock, and stays. */
    IF_SEEN,

    /** It does (a Pixel Tablet): the sensor is there from the start. */
    YES,
}

/**
 * How a frame's screen stands ([Frame.orientation]). [LANDSCAPE] and [PORTRAIT]: that way, either way up, as the sensor
 * says. [ANY]: as the device stands, which Android decides. [PORTRAIT_FIXED]: portrait one way up only, Android's own
 * portrait, which auto-rotate and the sensor don't turn: for a screen fixed to a wall whose Android can turn
 * auto-rotate back on by itself (the lululemon Mirror's does on every app update, #686).
 */
enum class Orientation { LANDSCAPE, PORTRAIT, ANY, PORTRAIT_FIXED }

/** How a frame's screen sleeps ([Frame.sleepMode]). */
enum class SleepMode {
    /**
     * Android turns the display off: `DevicePolicyManager.lockNow()` with device admin, else the system's screen-off
     * timeout. Every frame but one.
     */
    LOCK,

    /**
     * The display stays on in Android's eyes and only goes dark: the backlight at 0 and a black screen over the page,
     * which a tap, a key or any wake takes away. For hardware that can't come back from Android's display off (the
     * lululemon Mirror draws lines across the screen after it).
     */
    BACKLIGHT_OFF,
}

/** [manufacturer] ("Aqara"), [model] ("Panel Hub S1 Plus") and [chip] ("Allwinner A133", or the board's name where Android gives no chip). */
class Hardware(val manufacturer: String, val model: String, val chip: String)

/**
 * A boot animation's last frame: a drawable resource, drawn at [left], [top] screen px on flat night,
 * and the line under the wordmark it shows ([line], "On Aqara S1 Plus").
 */
class BootRest(val drawable: Int, val left: Int, val top: Int, val line: String)
