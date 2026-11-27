/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.frame

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.os.Build
import android.provider.Settings
import android.view.InputDevice
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import dev.glassworks.platform.Platform
import dev.glassworks.screen.LightSource
import dev.glassworks.screen.PresenceSource
import dev.glassworks.GlassworksApp
import dev.glassworks.update.FrameUpdateModel
import dev.glassworks.update.SilentUpdates
import dev.glassworks.update.UpdateChannelRow
import java.util.concurrent.ScheduledExecutorService

/**
 * Any Android device that has no frame of its own (frames/generic-android): a tablet, an old phone.
 * It goes by its own maker and model, or by its frame's on a [DeviceFrame] it recognises from the device's system
 * properties ([prop]): the lululemon Mirror's ROM calls it "MSM8916 for arm64" (#454). It has what its features say,
 * and has no panes of its own. Its sensors are the app's choice: the generic app (frames/generic-android/android)
 * reads Android's standard proximity and light sensors (dev.glassworks.screen.ProximityPresence, AmbientLight); the
 * S1 app on a tablet reads none.
 */
class GenericAndroid(
    private val context: Context,
    override val presence: PresenceSource? = null,
    override val light: LightSource? = null,
    private val prop: (String) -> String = DeviceFrame::systemProperty,
) : Frame {
    private val deviceFrame = DeviceFrame.of(prop)

    override val board = deviceFrame?.id ?: "generic-android"

    override val namedBy = if (deviceFrame != null) NamedBy.FRAME else NamedBy.MODEL

    override val displayName = deviceFrame?.name

    /** A device frame's own release line (the Mirror's); none on any other device, which its vendor updates. */
    override val frameRelease: FrameRelease? by lazy { deviceFrame?.release?.invoke(prop) }

    override val hardware = Hardware(deviceFrame?.brand ?: Platform.manufacturer, deviceFrame?.modelName ?: Platform.model, Platform.hardware)

    override val cameraMaxPixels = deviceFrame?.cameraMaxPixels

    override val cameraSensorDegrees = deviceFrame?.cameraSensorDegrees

    /** A device frame's own ([SleepMode.BACKLIGHT_OFF] on the lululemon Mirror); Android's screen off on any other device. */
    override val sleepMode: SleepMode = deviceFrame?.sleepMode ?: SleepMode.LOCK

    override val provides: Set<Capability> by lazy { Capability.ofDevice(context.packageManager::hasSystemFeature) }

    /**
     * A device with a frame of its own stands as that frame says: a [DeviceFrame]'s own (the lululemon Mirror is
     * [Orientation.PORTRAIT_FIXED]: Kiln's first boot locks its Android at `user_rotation=3`, but Android 6 turns
     * auto-rotate back on with every update of the app, #686), else [KNOWN]'s (a Pixel Tablet is landscape on its dock,
     * though its screen is portrait at rotation 0). Any other turns as Android says: a tablet follows auto-rotate or the
     * person's choice.
     */
    override val orientation: Orientation get() = deviceFrame?.orientation ?: KNOWN[Platform.model]?.orientation ?: Orientation.ANY

    /**
     * [Dock.YES] for a device whose frame docks ([KNOWN]) or that has Google's dock manager ([DOCK_MANAGER], listed in
     * the manifest's `<queries>`); [Dock.IF_SEEN] for any other, which gets the sensor once it reports a dock.
     */
    override val dock: Dock by lazy { dockOf(KNOWN[Platform.model]?.docks == true, installed(context.packageManager, DOCK_MANAGER)) }

    /**
     * [Input.NONE] when Android lists input devices and none is a touchscreen (the Mirror's, #198): setup then works
     * from a computer (#34). [Input.TOUCH] otherwise, and when it can't tell.
     */
    override val input: Input get() = runCatching {
        val im = context.getSystemService(InputManager::class.java) ?: return@runCatching null
        inputOf(im.inputDeviceIds) { id -> im.getInputDevice(id)?.supportsSource(InputDevice.SOURCE_TOUCHSCREEN) == true }
    }.getOrNull() ?: Input.TOUCH

    override fun systemSettingsIntents() = listOf(Intent(Settings.ACTION_SETTINGS))

    override fun panes(ctx: PaneContext, device: ScheduledExecutorService) = emptyList<Pane>()

    /**
     * The Device page, named after the device: silent updates, the update channel (#605), the home screen and turning the
     * screen off (setup's This device step, #403), device owner, model, the frame version on a device frame, and Android.
     */
    override fun deviceSection(context: Context, running: (String) -> Pane?): SettingsSection =
        SettingsSection(DEVICE, friendlyName().ifBlank { "Device" }, listOfNotNull(
            silentUpdatesItem(context) { SilentUpdates.read(context, SilentUpdates.Root.NONE) },
            UpdateChannelRow.item(context),
            SettingsItem.Info({ HOME_TITLE }, { if (Platform.isHomeApp(context)) HOME_YES else HOME_NO }, fixable = { !Platform.isHomeApp(context) }),
            SettingsItem.Info({ SCREEN_OFF_TITLE }, {
                when {
                    sleepMode == SleepMode.BACKLIGHT_OFF -> SCREEN_OFF_BACKLIGHT
                    !adminActive(context) -> SCREEN_OFF_NO
                    SilentUpdates.deviceOwner(context) -> SCREEN_OFF_YES_OWNER
                    else -> SCREEN_OFF_YES
                }
            }, fixable = { sleepMode == SleepMode.LOCK && !adminActive(context) }),
            SettingsItem.Info({ OWNER_TITLE }, { if (SilentUpdates.deviceOwner(context)) "Yes: Glassworks can restart the device and turn the screen off when idle" else "No" }),
            SettingsItem.Info({ MODEL_TITLE }, { "${hardware.manufacturer} ${hardware.model}".trim() }),
            *frameRelease?.let { frameVersionItems(context, it, FrameUpdateModel.installs(it, agent)) }.orEmpty().toTypedArray(),
            SettingsItem.Info({ ANDROID_TITLE }, { Build.VERSION.RELEASE.orEmpty() }),
        ))

    companion object {
        /**
         * The devices with a frame, by `Build.MODEL` matched whole (as Kiln's `devices.ts` MODELS): how each stands (its
         * frame.json's `display`) and whether it docks (its `dock`). `GenericAndroidKnownTest` checks all three.
         */
        val KNOWN: Map<String, Known> = mapOf(
            "Pixel Tablet" to Known(Orientation.LANDSCAPE, docks = true), // google-pixel-tablet
            "SM-T580" to Known(Orientation.LANDSCAPE, docks = false), // samsung-galaxy-tab-a-10-1-2016
        )

        /** Google's dock manager, on the Pixel Tablet and its charging speaker dock (read on one, 2026-11-17). */
        const val DOCK_MANAGER = "com.google.android.apps.nest.dockmanager.app"

        /** [Dock.YES] when the frame says it docks ([frameDocks]) or a dock manager is installed ([dockManager]); else [Dock.IF_SEEN]. */
        fun dockOf(frameDocks: Boolean, dockManager: Boolean): Dock = if (frameDocks || dockManager) Dock.YES else Dock.IF_SEEN

        /** Whether [pm] has [pkg]. From Android 11 the app sees only the packages its manifest's `<queries>` names. */
        @Suppress("DEPRECATION") // the flags overload that replaces it needs Android 13
        fun installed(pm: PackageManager, pkg: String): Boolean = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess

        /** [Input.NONE] when there are input devices ([ids]) and none [isTouchscreen]; else [Input.TOUCH]. */
        fun inputOf(ids: IntArray, isTouchscreen: (Int) -> Boolean): Input =
            if (ids.isNotEmpty() && ids.none(isTouchscreen)) Input.NONE else Input.TOUCH

        /** The Device page's id on every frame. */
        const val DEVICE = "device"
        const val SILENT_UPDATES_TITLE = "Silent updates"
        const val HOME_TITLE = "Home screen"
        const val HOME_YES = "Yes: Glassworks is the default Home app"
        /** Settings paths differ by Android version and maker, so the rows say what to search Android's Settings for. */
        const val HOME_NO = "No: Glassworks isn't the default Home app. To change this, search Android's settings for “Home”"
        const val SCREEN_OFF_TITLE = "Turn the screen off"
        const val SCREEN_OFF_YES = "Yes: Glassworks has device administrator permission, so it can turn the screen off when idle. " +
            "Turn this off in Android's settings (“Device admin”) before uninstalling."
        /** A device owner is also a device admin, and Android won't let Settings turn that off: undo device owner first. */
        const val SCREEN_OFF_YES_OWNER = "Yes: Glassworks is set as device owner, so it can turn the screen off when idle. " +
            "Undo device owner in Kiln before uninstalling."
        /** A [SleepMode.BACKLIGHT_OFF] frame sleeps without Android's screen off, so without device admin too. */
        const val SCREEN_OFF_BACKLIGHT = "Yes: when idle, Glassworks turns the backlight off and leaves the screen black. " +
            "Anything that wakes the screen turns it back on."
        const val SCREEN_OFF_NO = "No: Glassworks can't turn the screen off automatically. Enable it in setup's This device " +
            "step, or search Android's settings for “Device admin”"

        /** One of this app's components is an active device admin (the receiver Kiln or setup's This device step activates). */
        fun adminActive(context: Context): Boolean = runCatching {
            context.getSystemService(android.app.admin.DevicePolicyManager::class.java)?.activeAdmins.orEmpty()
                .any { it.packageName == context.packageName }
        }.getOrDefault(false)
        const val OWNER_TITLE = "Device owner"
        const val MODEL_TITLE = "Model"
        const val ANDROID_TITLE = "Android"
        const val FRAME_VERSION_TITLE = "Frame version"
        const val FRAME_UPDATE_TITLE = "Frame update"
        const val FRAME_INSTALL = "Install"
        val FRAME_INSTALL_CONFIRM = SettingsItem.Confirm(
            "Install the frame update?",
            "The panel downloads it, checks it, and restarts once to finish. That takes a few minutes.",
            FRAME_INSTALL,
        )

        /**
         * The Device page's frame rows on a frame with releases of its own (#139). Frame version: the version, and when a
         * newer one is out, which and how to install it, in amber. On a frame whose bundles install from here
         * ([installs], [FrameUpdateModel.installs]) also Frame update: what Install does now or how it went, and the
         * Install button, the same install as Home Assistant's card. The panel's menu and the Manage page draw the same
         * rows; from the Manage page Install needs the code the panel shows, as Reboot does: it restarts the panel.
         * They read the app's update check while one runs.
         */
        fun frameVersionItems(context: Context, release: FrameRelease, installs: Boolean): List<SettingsItem> {
            val own = FrameUpdateModel(release, "", installs)
            fun updates() = (context.applicationContext as? GlassworksApp)?.glassworks?.updates
            fun model() = updates()?.frame ?: own
            val version = SettingsItem.Info({ FRAME_VERSION_TITLE }, { model().detail() }, fixable = { model().updateAvailable })
            if (!installs) return listOf(version)
            return listOf(
                version,
                SettingsItem.Action(
                    FRAME_UPDATE_TITLE, { if (updates() == null) "Checking" else model().installLine() }, FRAME_INSTALL,
                    confirm = FRAME_INSTALL_CONFIRM, needsCode = true,
                ) { done -> updates()?.installFrame(done) ?: done("The update service isn't running.") },
            )
        }

        /**
         * Every frame's Device page row (#422): whether updates install with nobody at the screen, and how to make
         * them where they don't. Amber when there's a way. [read] gives null while the frame is still reading it. A new
         * answer goes to Home Assistant's sensor at once (#666).
         */
        fun silentUpdatesItem(context: Context, read: () -> SilentUpdates?): SettingsItem.Info {
            val answer = SilentUpdates.Fresh {
                read().also { (context.applicationContext as? GlassworksApp)?.glassworks?.updates?.noticeSilent(it) }
            }
            return SettingsItem.Info(
                { SILENT_UPDATES_TITLE },
                { answer()?.detail ?: "Checking" },
                fixable = { answer()?.let { !it.silent && it.fix != null } == true },
            )
        }
    }
}

/** What the app knows of a device with a frame ([GenericAndroid.KNOWN]): how it stands, and whether it docks. */
class Known(val orientation: Orientation, val docks: Boolean)
