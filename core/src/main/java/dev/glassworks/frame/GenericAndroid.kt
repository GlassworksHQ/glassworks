/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.frame

import android.content.Context
import android.content.Intent
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
import dev.glassworks.update.SilentUpdates
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
    prop: (String) -> String = DeviceFrame::systemProperty,
) : Frame {
    private val deviceFrame = DeviceFrame.of(prop)

    override val board = deviceFrame?.id ?: "generic-android"

    override val namedBy = if (deviceFrame != null) NamedBy.FRAME else NamedBy.MODEL

    override val displayName = deviceFrame?.name

    override val hardware = Hardware(deviceFrame?.brand ?: Platform.manufacturer, deviceFrame?.modelName ?: Platform.model, Platform.hardware)

    override val provides: Set<Capability> by lazy { Capability.ofDevice(context.packageManager::hasSystemFeature) }

    /**
     * A device with a frame of its own stands as that frame says ([KNOWN]: a Pixel Tablet is landscape on its dock, though
     * its screen is portrait at rotation 0). Any other turns as Android says: Kiln's first boot locks the lululemon
     * Mirror's Android in portrait (`user_rotation=3`, auto-rotate off), and a tablet follows auto-rotate or the person's choice.
     */
    override val orientation: Orientation get() = KNOWN[Platform.model] ?: Orientation.ANY

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
     * The Device page, named after the device: silent updates, the home screen and turning the screen off (setup's This
     * tablet step, #403), device owner, model and Android.
     */
    override fun deviceSection(context: Context, running: (String) -> Pane?): SettingsSection =
        SettingsSection(DEVICE, friendlyName().ifBlank { "Device" }, listOf(
            silentUpdatesItem { SilentUpdates.read(context, SilentUpdates.Root.NONE) },
            SettingsItem.Info({ HOME_TITLE }, { if (Platform.isHomeApp(context)) HOME_YES else HOME_NO }, fixable = { !Platform.isHomeApp(context) }),
            SettingsItem.Info({ SCREEN_OFF_TITLE }, { if (adminActive(context)) SCREEN_OFF_YES else SCREEN_OFF_NO }, fixable = { !adminActive(context) }),
            SettingsItem.Info({ OWNER_TITLE }, { if (SilentUpdates.deviceOwner(context)) "Yes: the app can reboot the device and keep its screen" else "No" }),
            SettingsItem.Info({ MODEL_TITLE }, { "${hardware.manufacturer} ${hardware.model}".trim() }),
            SettingsItem.Info({ ANDROID_TITLE }, { Build.VERSION.RELEASE.orEmpty() }),
        ))

    companion object {
        /**
         * The devices with a frame, by `Build.MODEL` matched whole (as Kiln's `devices.ts` MODELS), and how each stands:
         * its frame.json's `display`. `GenericAndroidKnownTest` checks both.
         */
        val KNOWN: Map<String, Orientation> = mapOf(
            "Pixel Tablet" to Orientation.LANDSCAPE, // google-pixel-tablet
        )

        /** [Input.NONE] when there are input devices ([ids]) and none [isTouchscreen]; else [Input.TOUCH]. */
        fun inputOf(ids: IntArray, isTouchscreen: (Int) -> Boolean): Input =
            if (ids.isNotEmpty() && ids.none(isTouchscreen)) Input.NONE else Input.TOUCH

        /** The Device page's id on every frame. */
        const val DEVICE = "device"
        const val SILENT_UPDATES_TITLE = "Silent updates"
        const val HOME_TITLE = "Home screen"
        const val HOME_YES = "Yes: Glassworks opens when the device starts"
        const val HOME_NO = "No: another app opens when the device starts. To change it: Android's Settings, Apps, Default apps, Home app"
        const val SCREEN_OFF_TITLE = "Turn the screen off"
        const val SCREEN_OFF_YES = "Yes: Glassworks is a device admin, so it can turn the screen off when idle. " +
            "Turn that off in Android's Settings, Security, Device admin apps before you uninstall it"
        const val SCREEN_OFF_NO = "No: the screen goes off on Android's own timer. To change it: setup's This device step, " +
            "or Android's Settings, Security, Device admin apps"

        /** One of this app's components is an active device admin (the receiver Kiln or setup's This device step activates). */
        fun adminActive(context: Context): Boolean = runCatching {
            context.getSystemService(android.app.admin.DevicePolicyManager::class.java)?.activeAdmins.orEmpty()
                .any { it.packageName == context.packageName }
        }.getOrDefault(false)
        const val OWNER_TITLE = "Device owner"
        const val MODEL_TITLE = "Model"
        const val ANDROID_TITLE = "Android"

        /**
         * Every frame's Device page row (#422): whether updates install with nobody at the screen, and how to make
         * them where they don't. Amber when there's a way. [read] gives null while the frame is still reading it.
         */
        fun silentUpdatesItem(read: () -> SilentUpdates?): SettingsItem.Info {
            val answer = SilentUpdates.Fresh(read = read)
            return SettingsItem.Info(
                { SILENT_UPDATES_TITLE },
                { answer()?.detail ?: "Checking" },
                fixable = { answer()?.let { !it.silent && it.fix != null } == true },
            )
        }
    }
}
