/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.update

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.glassworks.platform.Platform
import dev.glassworks.platform.PlatformApi

/**
 * Whether the app's updates install with nobody at the screen on this device, and why (#422): the one answer the
 * Device page, the Manage page and Home Assistant's *Silent updates* show. Where they can't, [fix] says how to
 * make them.
 *
 * The routes, in the order the app takes them (UpdateManager):
 * 1. The frame's root update service, when the boot image has it (the S1's, tools/root): `pm install` as root.
 * 2. Otherwise a PackageInstaller session. Android installs it with no prompt when the installer holds
 *    `INSTALL_PACKAGES` (a privileged system app: Kiln's Mirror image, frames/lululemon-mirror/research/privileged-app.md)
 *    or is the device owner. Read in AOSP's PackageInstallerSession: android-6.0.1_r1 (constructor) and
 *    android-15.0.0_r1 (`computeUserActionRequirement`).
 * 3. On Android 12+, a session that asks for `USER_ACTION_NOT_REQUIRED` from an app that declares
 *    `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, updates itself, may "Install unknown apps", and whose new APK targets at
 *    least [PlatformApi.silentInstallMinTarget]. `SessionParams.setRequireUserAction`'s docs, and the same AOSP code.
 *
 * Not modelled: an Android 14+ update owner other than the app (a store that installed it with update ownership;
 * Kiln and adb set none), Android's 30-second throttle between two silent updates of one app, and a device policy
 * that forbids unknown sources (Android then says "Install unknown apps" is off, which this reports).
 */
sealed class SilentUpdates(
    /** True when updates install with nobody at the screen. */
    val silent: Boolean,
    /** Why, in a few plain words: the route, or what stops it. */
    val reason: String,
    /** How to make them silent on this kind of device; null when they are, or when there is no way. */
    val fix: String? = null,
) {
    /** "Yes: …" or "No: …": Home Assistant's state. */
    val state: String get() = (if (silent) "Yes: " else "No: ") + reason

    /** The settings row: the state, and how to change it when there's a way. */
    val detail: String get() = fix?.let { "$state. $it" } ?: state

    data object RootService : SilentUpdates(true, "the boot image's root update service installs them")
    data object SystemApp : SilentUpdates(true, "the app is a system app that may install apps")
    data object DeviceOwner : SilentUpdates(true, "the app is the device owner")
    data object SelfUpdate : SilentUpdates(true, "Android 12 or newer lets the app update itself")

    /** The S1 with a boot image from before the root update service. Its Android (10) has no self-update. */
    data object NoRootService : SilentUpdates(false, "this boot image has no root update service", "To change it: update the boot image with Kiln")

    data class OldAndroid(val android: String) : SilentUpdates(false, "Android $android asks for a tap on each update", DEVICE_OWNER_FIX)

    /** Android needs the new APK to target [needed]; the next release is assumed to target what this one does ([has]). */
    data class TargetTooLow(val android: String, val needed: Int, val has: Int) : SilentUpdates(
        false, "Android $android needs an app that targets API $needed, and this one targets $has",
        "To change it: wait for a release that targets $needed, or make the app device owner on a reset device with no accounts",
    )

    data object UnknownAppsOff : SilentUpdates(
        false, "Android needs “Install unknown apps” allowed for the app", "To change it: allow it in Android's settings, under Apps",
    )

    /** The frame's root update service: [NONE] when the frame has none (every device but the S1). */
    enum class Root { NONE, MISSING, PRESENT }

    /** What decides the answer, read from the device by [read]. */
    data class Facts(
        val root: Root,
        /** `INSTALL_PACKAGES` granted. */
        val systemApp: Boolean,
        val deviceOwner: Boolean,
        /** The Android version's name ("12"), for the words. */
        val android: String,
        /** [PlatformApi.silentInstallMinTarget]: null where Android has no self-update. */
        val minTarget: Int?,
        /** This app's targetSdk, which the next release of the same build keeps. */
        val target: Int,
        /** "Install unknown apps" is allowed for the app. */
        val unknownApps: Boolean,
    )

    companion object {
        const val DEVICE_OWNER_FIX = "To change it: make the app device owner, on a reset device with no accounts"

        fun of(f: Facts): SilentUpdates = when {
            f.root == Root.PRESENT -> RootService
            f.systemApp -> SystemApp
            f.deviceOwner -> DeviceOwner
            f.root == Root.MISSING -> NoRootService
            f.minTarget == null -> OldAndroid(f.android)
            f.target < f.minTarget -> TargetTooLow(f.android, f.minTarget, f.target)
            !f.unknownApps -> UnknownAppsOff
            else -> SelfUpdate
        }

        /** The answer on this device. Binder calls only: [root] comes from the caller, which reads it off the main thread. */
        fun read(context: Context, root: Root, platform: PlatformApi = Platform): SilentUpdates = of(Facts(
            root = root,
            systemApp = context.checkSelfPermission(Manifest.permission.INSTALL_PACKAGES) == PackageManager.PERMISSION_GRANTED,
            deviceOwner = deviceOwner(context),
            android = Build.VERSION.RELEASE.orEmpty(),
            minTarget = platform.silentInstallMinTarget,
            target = context.applicationInfo.targetSdkVersion,
            unknownApps = platform.canInstallWithoutUserAction &&
                runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false),
        ))

        fun deviceOwner(context: Context): Boolean =
            runCatching { context.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(context.packageName) == true }.getOrDefault(false)
    }
}
