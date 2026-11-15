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
import java.util.concurrent.ScheduledExecutorService

/**
 * Any Android device that has no frame of its own (frames/generic-android): a tablet, an old phone.
 * It goes by its own maker and model, has what its features say, and has no panes of its own.
 */
class GenericAndroid(private val context: Context) : Frame {
    override val board = "generic-android"

    override val namedBy = NamedBy.MODEL

    override val hardware = Hardware(Platform.manufacturer, Platform.model, Platform.hardware)

    override val provides: Set<Capability> by lazy { Capability.ofDevice(context.packageManager::hasSystemFeature) }

    override val presence = null

    override val light = null

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

        /** Whether app updates install with nobody at the screen (#277): Android 12 or newer, and the app may install apps. */
        fun updates(context: Context): String = when {
            !Platform.canInstallWithoutUserAction -> "Not on this Android: it needs Android 12 or newer"
            !runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false) -> ALLOW_INSTALLS
            else -> "Available"
        }

        fun deviceOwner(context: Context): Boolean =
            runCatching { context.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(context.packageName) == true }.getOrDefault(false)
    }
}
