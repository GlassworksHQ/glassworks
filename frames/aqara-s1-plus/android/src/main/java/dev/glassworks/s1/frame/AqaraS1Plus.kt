package dev.glassworks.s1.frame

import android.content.Context
import android.content.Intent
import android.provider.Settings
import dev.glassworks.frame.BootRest
import dev.glassworks.frame.Capability.BLUETOOTH_LE
import dev.glassworks.frame.Capability.MICROPHONE
import dev.glassworks.frame.Capability.S1_WALL_BASE
import dev.glassworks.frame.Capability.SILABS_RADIO
import dev.glassworks.frame.Capability.SPEAKER
import dev.glassworks.frame.DeviceFrame
import dev.glassworks.frame.Frame
import dev.glassworks.frame.FrameRelease
import dev.glassworks.frame.Hardware
import dev.glassworks.frame.NamedBy
import dev.glassworks.frame.RootUpdates
import dev.glassworks.frame.ServiceWork
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.s1.PanePrefs
import dev.glassworks.s1.base.WallBaseDefinition
import dev.glassworks.s1.base.WallBasePane
import dev.glassworks.s1.base.WallBasePrefs
import java.util.concurrent.ScheduledExecutorService
import dev.glassworks.s1.R

/**
 * The Aqara Panel Hub S1 Plus, the frame this app is built for. Chosen on its hardware only ([orElse]).
 * [aqaraCloud]: Aqara's cloud daemon, the one instance the wall base pane and the Device page share (a test passes a fake).
 */
class AqaraS1Plus(private val aqaraCloud: AqaraCloud = AqaraCloud()) : Frame {
    override val board = "aqara-s1-plus"

    override val namedBy = NamedBy.FRAME

    override val displayName = DISPLAY_NAME

    override val hardware = Hardware("Aqara", "Panel Hub S1 Plus", "Allwinner A133")

    override val provides = setOf(MICROPHONE, SPEAKER, SILABS_RADIO, S1_WALL_BASE, BLUETOOTH_LE)

    override val bootRest = BOOT_REST

    override val presence = Vi5300Presence()

    override val light = Mk6212Light()

    /** The A133's zones are `cpu_thermal_zone`, `gpu_thermal_zone` and `ddr_thermal_zone` (read 2026-11-12). */
    override val cpuThermalZone = "cpu_thermal_zone"

    /** The time zone that Aqara resets at every boot and the root helper puts back, and the wall base. */
    override fun panes(ctx: PaneContext, device: ScheduledExecutorService) = listOf(
        TimeZonePane(ctx, TimeZonePrefs(ctx.prefs("time-zone"))),
        WallBasePane(ctx, WallBasePane.wantedConfig(WallBasePrefs(ctx.prefs("wall-base")), aqaraCloud), device, aqaraCloud),
    )

    /** The wall base's on/off and adb extras, and the time zone's on/off. */
    override fun paneDefinitions(context: Context): List<PaneDefinition> {
        val prefs = PanePrefs(context)
        return listOf(WallBaseDefinition(prefs.paneFile("wall-base")), TimeZoneDefinition(prefs.paneFile("time-zone")))
    }

    private val device by lazy { S1Device(aqaraCloud) }

    override fun deviceSection(context: Context, running: (String) -> Pane?) = device.section(context, frameRelease, running)

    override val rootUpdates: RootUpdates = RootUpdateInstaller()

    /** The radio helper sends the setup hotspot's DNS and port 80 to the app's portal while it runs (#197). */
    override val hotspotPortal = true

    /**
     * The S1 frame's version (#139), which the loader in its boot image sets at boot. A panel without the loader has
     * none and needs one Kiln update for it; after that, frame updates come over the air in signed bundles.
     */
    override val frameRelease: FrameRelease by lazy {
        val stamp = FrameRelease.stamp(board, DeviceFrame::systemProperty)
        FrameRelease(board, stamp, install = if (stamp != null) null else NEEDS_LOADER)
    }

    /** Its Wi-Fi chip has no PNO scans, so the app scans while the screen is off (#3). One per service run. */
    override fun serviceWork(context: Context, onForegroundOnly: () -> Unit): ServiceWork =
        ScreenOffWifiScanner(context, onForegroundOnly = onForegroundOnly)

    override val shortName = "S1 Plus"

    override val systemSettingsTitle = SETTINGS_TITLE

    /**
     * The S1's firmware has no Android Settings app. Its own one (com.lumi.settings: Network, Sounds,
     * Display, Updates, Advanced settings incl. factory reset) is normally reached from Aqara's
     * launcher or from the shade's gear, and that gear only shows once the panel went through Aqara's
     * onboarding. So the kiosk offers its own ways in: a "Settings" action on its notification in the
     * shade, and the "Open device settings" button entity in Home Assistant.
     */
    override fun systemSettingsIntents() = listOf(
        Intent(Settings.ACTION_SETTINGS),
        LUMI_SETTINGS,
    )

    /** No Android Settings app on its firmware: Aqara's own one has Network -> WLAN. */
    override fun wifiSettingsIntents() = super.wifiSettingsIntents() + LUMI_SETTINGS

    companion object {
        /** frame.json's `name` (PanelNameTest checks it). */
        const val DISPLAY_NAME = "Aqara S1 Plus"

        /** What the settings menu calls the S1's own settings app (com.lumi.settings). */
        const val SETTINGS_TITLE = "Aqara's own settings"

        /** How a panel without the loader gets a frame update (#139). */
        const val NEEDS_LOADER = "To install it: update the panel once with Kiln, at ${FrameRelease.KILN}. Later frame updates install over the air"

        /**
         * `Build.HARDWARE` on the S1's A133 (read 2026-11-14), the S1 frame's only check. Its other build
         * properties don't tell it apart either: model "QUAD-CORE R818 EVB2", maker "Allwinner",
         * `ro.product.board` exdroid, device peony-perf1, name peony_evb2, `ro.board.platform` peony are all
         * Allwinner A133 SDK defaults. Accepted for v1: another A133 board on those defaults takes the S1 frame.
         */
        const val HARDWARE = "sun50iw10p1"

        /** The frame for the device the app runs on: the S1's on its hardware, else [generic]. */
        fun orElse(hardware: String, generic: () -> Frame): Frame = if (hardware == HARDWARE) AqaraS1Plus() else generic()

        private val LUMI_SETTINGS: Intent get() = Intent("lumi.settings.SETTINGS").setPackage("com.lumi.settings")

        /**
         * branding/glass-tile/bootanimation.zip's last frame (part2) and its trim offset (BootRestTest
         * checks both). The boot splash reads it before the user is unlocked, without the runtime.
         */
        val BOOT_REST = BootRest(dev.glassworks.s1.R.drawable.boot_rest, 554, 189, "On Aqara S1 Plus")
    }
}
