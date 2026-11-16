/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.frame

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import dev.glassworks.platform.Platform
import dev.glassworks.screen.LightSource
import dev.glassworks.screen.PresenceSource
import dev.glassworks.update.InstallRights
import java.util.concurrent.ScheduledExecutorService

/**
 * Any Android device that has no frame of its own (frames/generic-android): a tablet, an old phone.
 * It goes by its own maker and model, has what its features say, and has no panes of its own. Its sensors
 * are the app's choice: the generic app (frames/generic-android/android) reads Android's standard proximity
 * and light sensors (dev.glassworks.screen.ProximityPresence, AmbientLight); the S1 app on a tablet reads none.
 */
class GenericAndroid(
    private val context: Context,
    override val presence: PresenceSource? = null,
    override val light: LightSource? = null,
) : Frame {
    override val board = "generic-android"

    override val namedBy = NamedBy.MODEL

    override val hardware = Hardware(Platform.manufacturer, Platform.model, Platform.hardware)

    override val provides: Set<Capability> by lazy { Capability.ofDevice(context.packageManager::hasSystemFeature) }

    override fun systemSettingsIntents() = listOf(Intent(Settings.ACTION_SETTINGS))

    override fun panes(ctx: PaneContext, device: ScheduledExecutorService) = emptyList<Pane>()

    /** The Device page, named after the device: whether updates install without a tap, device owner, model and Android. */
    override fun deviceSection(context: Context, running: (String) -> Pane?): SettingsSection =
        SettingsSection(DEVICE, friendlyName().ifBlank { "Device" }, listOf(
            SettingsItem.Info({ UPDATES_TITLE }, { updates(context) }, fixable = { updates(context) == ALLOW_INSTALLS }),
            SettingsItem.Info({ OWNER_TITLE }, { if (deviceOwner(context)) "Yes: the app can reboot the device and keep its screen" else "No" }),
            SettingsItem.Info({ MODEL_TITLE }, { "${hardware.manufacturer} ${hardware.model}".trim() }),
            SettingsItem.Info({ ANDROID_TITLE }, { Build.VERSION.RELEASE.orEmpty() }),
        ))

    companion object {
        /** The Device page's id on every frame. */
        const val DEVICE = "device"
        const val UPDATES_TITLE = "Updates without a tap"
        const val OWNER_TITLE = "Device owner"
        const val MODEL_TITLE = "Model"
        const val ANDROID_TITLE = "Android"
        const val ALLOW_INSTALLS = "Allow “Install unknown apps” for the app in Android's settings"

        /**
         * Whether app updates install with nobody at the screen (#277): as device owner, as a privileged system app
         * (the Mirror's, #198), or on Android 12 or newer with the install permission.
         */
        fun updates(context: Context): String = when {
            // A device owner installs without a tap on any Android the app runs on (Android 6 and newer).
            deviceOwner(context) -> "Available: the app is the device owner"
            // So does a privileged system app (INSTALL_PACKAGES), which Kiln makes of the legacy build on the Mirror.
            InstallRights.installsPackages(context) -> "Available: the app is a system app with the install permission"
            !Platform.canInstallWithoutUserAction -> "Not on this Android: it needs Android 12 or newer, the app as device owner, or the app as a system app"
            !runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false) -> ALLOW_INSTALLS
            else -> "Available"
        }

        fun deviceOwner(context: Context): Boolean =
            runCatching { context.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(context.packageName) == true }.getOrDefault(false)
    }
}
