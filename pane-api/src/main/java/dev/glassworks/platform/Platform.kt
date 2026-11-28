/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.system.Os
import android.system.OsConstants

/**
 * What the running Android can do. This is the only place in the app that reads
 * `Build.VERSION.SDK_INT` (`PlatformConfinementTest` fails the build otherwise). Every query names
 * a capability, and says why it has the boundary it has. Callers never see an API level.
 *
 * The queries live in an open class so a test can build one for any level without Robolectric:
 * [Platform] is the one for the device the app runs on.
 */
open class PlatformApi(
    /** The API level this instance answers for. Use it for logs, not for decisions. */
    val apiLevel: Int,
) {
    /**
     * `ConnectivityManager.registerDefaultNetworkCallback` exists (Android 7). Below it,
     * [registerDefaultNetworkCallback] asks for every network with internet instead.
     */
    val hasDefaultNetworkCallback: Boolean get() = apiLevel >= 24

    /** `LocaleList` and `Configuration.getLocales`/`setLocales` exist (Android 7). Below it, `Configuration.locale`. */
    val hasLocaleList: Boolean get() = apiLevel >= 24

    /** `AccessibilityAction.ACTION_SET_PROGRESS` exists (Android 7): a slider can be set to a value, not only stepped. */
    val hasSetProgressAction: Boolean get() = apiLevel >= 24

    /** `DevicePolicyManager.reboot` exists (Android 7): a device owner can restart the device. */
    val hasDeviceOwnerReboot: Boolean get() = apiLevel >= 24

    /** `Process.getStartElapsedRealtime` exists (Android 7). Below it, `/proc/self/stat` has the start time. */
    val hasProcessStartTime: Boolean get() = apiLevel >= 24

    /**
     * The system CA store has ISRG Root X1, Let's Encrypt's root (Android 7.1). Below it, HTTPS to a host on it
     * (raw.githubusercontent.com, GitHub's release assets) fails unless the app adds the root ([HttpsTrust]).
     * Android 7.0's Network Security Config could add it too, but only from XML for the whole app.
     */
    val hasIsrgRootX1: Boolean get() = apiLevel >= 25

    /**
     * The hidden `ActivityManager.getService()` exists (Android 8). Below it, the same service is
     * `ActivityManagerNative.getDefault()`. Only reflection reaches either.
     */
    val hasActivityManagerGetService: Boolean get() = apiLevel >= 26

    /** `NotificationChannel` exists, and a notification must name one (Android 8). */
    val hasNotificationChannels: Boolean get() = apiLevel >= 26

    /**
     * `Context.startForegroundService` exists, and a background app may only start a service this
     * way (Android 8). Below it, `startService` is the call.
     */
    val hasForegroundServiceStart: Boolean get() = apiLevel >= 26

    /**
     * Android enforces per-app "Install unknown apps" (`PackageManager.canRequestPackageInstalls`,
     * Android 8). Below it there is no per-app check, only the device-wide unknown-sources setting.
     */
    val hasPerAppUnknownSources: Boolean get() = apiLevel >= 26

    /** `layoutInDisplayCutoutMode` exists (Android 9). */
    val hasDisplayCutoutMode: Boolean get() = apiLevel >= 28

    /** `PackageInfo.signingInfo` exists (Android 9). Below it, only `signatures` is filled. */
    val hasSigningInfo: Boolean get() = apiLevel >= 28

    /** `PackageInfo.longVersionCode` exists (Android 9). Below it, `versionCode` is an Int. */
    val hasLongVersionCode: Boolean get() = apiLevel >= 28

    /**
     * `Service.startForeground` takes the service types (Android 10). Below it, the call has no
     * types and the manifest's types do not exist.
     */
    val hasForegroundServiceTypes: Boolean get() = apiLevel >= 29

    /**
     * `startForeground` throws a `SecurityException` for a type whose runtime permission is not
     * granted, such as microphone without `RECORD_AUDIO` (Android 14, for apps targeting it). Below
     * it the type is accepted, and only the access itself is refused.
     */
    val foregroundServiceTypesNeedTheirPermission: Boolean get() = apiLevel >= 34

    /** `Settings.Panel` (the Wi-Fi sheet over the app) exists (Android 10). */
    val hasSettingsPanel: Boolean get() = apiLevel >= 29

    /** `GradientDrawable.getShape`, `getCornerRadius` and `getCornerRadii` exist, to read a shape back (Android 7). */
    val hasGradientDrawableGetters: Boolean get() = apiLevel >= 24

    /** `TextView.setFallbackLineSpacing` exists, so text can keep CSS line boxes exact (Android 9). */
    val hasFallbackLineSpacing: Boolean get() = apiLevel >= 28

    /** `Font.Builder` can load a variable font from memory (Android 10). */
    val hasFontBuilder: Boolean get() = apiLevel >= 29

    /**
     * `Typeface.Builder` can open a font file at a variation (`'wght' 500`) (Android 8). Below it, a variable font
     * loads at its default instance only, and other weights are the nearest static face or a synthesized bold.
     */
    val hasTypefaceBuilder: Boolean get() = apiLevel >= 26

    /**
     * A clickable view takes keyboard focus by itself (`View.FOCUSABLE_AUTO`, Android 8), and `View.getFocusable`
     * exists. Below it, a control must be made focusable for keys to reach it.
     */
    val hasFocusableAuto: Boolean get() = apiLevel >= 26

    /**
     * Hardware drawing tells each parent which view below it changed (`ViewParent.onDescendantInvalidated`,
     * Android 8). Below it, the change climbs through `invalidateChildInParent` instead.
     */
    val hasDescendantInvalidated: Boolean get() = apiLevel >= 26

    /** `View.setAccessibilityPaneTitle`, which TalkBack announces a sheet by, exists (Android 9). */
    val hasAccessibilityPaneTitle: Boolean get() = apiLevel >= 28

    /**
     * `AccessibilityNodeInfo.setStateDescription` exists (Android 11): a slider can tell TalkBack what its value reads
     * as (`2,700 K`). Below it, TalkBack reads a slider's value as a percentage of its range.
     */
    val hasStateDescription: Boolean get() = apiLevel >= 30

    /** `WindowInsetsController` and `setDecorFitsSystemWindows` exist (Android 11). */
    val hasWindowInsetsController: Boolean get() = apiLevel >= 30

    /**
     * Android shows its own indicator while an app uses the camera (Android 12): the panel's own camera
     * dot would only repeat it. Below it, nothing says the camera is in use unless the app does.
     */
    val hasSystemCameraIndicator: Boolean get() = apiLevel >= 31

    /**
     * Android's resolver answers mDNS `.local` names (Android 12, its DnsResolver module). Below it, a lookup of
     * `homeassistant.local` fails in every app and in GeckoView, though the name answers on the network (#449).
     */
    val resolvesLocalNames: Boolean get() = apiLevel >= 31

    /**
     * An app may update itself without a tap (Android 12): a PackageInstaller session can ask for
     * `USER_ACTION_NOT_REQUIRED`. Android still asks when the app lacks
     * `UPDATE_PACKAGES_WITHOUT_USER_ACTION` or "Install unknown apps", or when the new APK targets
     * too old an API for the device. Below it the session cannot ask, and Android always asks.
     */
    val canInstallWithoutUserAction: Boolean get() = apiLevel >= 31

    /**
     * The lowest targetSdk the new APK needs for [canInstallWithoutUserAction] to install it with no tap, or null
     * below Android 12. From `SessionParams.setRequireUserAction`'s docs (read 2026-11-16): 29 on Android 12, 30 on
     * 13, 31 on 14, 33 on 15, 34 on 16, 35 on 17. AOSP's code agrees up to 15 (`SILENT_INSTALL_ALLOWED`); its public
     * main branch still says 33 after that, so this follows the stricter docs. Past 17 the docs say only that it goes
     * up: one level a year, as from 15 to 17, is a guess.
     */
    val silentInstallMinTarget: Int? get() = when {
        apiLevel < 31 -> null
        apiLevel < 33 -> 29
        apiLevel == 33 -> 30
        apiLevel == 34 -> 31
        else -> apiLevel - 2
    }

    /**
     * The battery broadcast's `EXTRA_PLUGGED` can say `BatteryManager.BATTERY_PLUGGED_DOCK` (Android 13): the device
     * charges from a dock. Below it no value means a dock, and only `ACTION_DOCK_EVENT` can say so.
     */
    val hasDockPowerSource: Boolean get() = apiLevel >= 33

    /** `ActivityManager.getHistoricalProcessExitReasons` exists (Android 11). Below it, the reason a process ended is not kept. */
    val hasApplicationExitInfo: Boolean get() = apiLevel >= 30

    /** `Settings.Global.BOOT_COUNT` exists (Android 7): one boot told from the next. Below it, [bootCount] is null. */
    val hasBootCount: Boolean get() = apiLevel >= 24

    /**
     * How an ordinary app opens a Wi-Fi hotspot for setup (#197). From Android 8, the public
     * `WifiManager.startLocalOnlyHotspot`. Below it there is none, and the hidden `setWifiApEnabled`
     * (reflection, no hidden-API blocks before Android 9) is the way.
     */
    val hotspotKind: HotspotKind get() = if (apiLevel >= 26) HotspotKind.LOCAL_ONLY else HotspotKind.HIDDEN_AP

    /**
     * The runtime permission a setup hotspot needs, or null for none. From Android 13 (for apps targeting
     * it), `NEARBY_WIFI_DEVICES`. Android 8 to 12 ask for fine location instead. The hidden API below
     * Android 8 needs only install-time permissions.
     */
    val hotspotPermission: String? get() = when {
        apiLevel >= 33 -> Manifest.permission.NEARBY_WIFI_DEVICES
        apiLevel >= 26 -> Manifest.permission.ACCESS_FINE_LOCATION
        else -> null
    }

    /** A local-only hotspot refuses to start while location is off (Android 8 to 12). From 13 the nearby permission replaces it. */
    val hotspotNeedsLocationOn: Boolean get() = apiLevel in 26..32

    /**
     * An app may turn the Wi-Fi radio on (`WifiManager.setWifiEnabled`). From Android 10, for apps targeting it,
     * only the device owner or a system app may; the call returns false.
     */
    val appCanEnableWifi: Boolean get() = apiLevel < 29

    /** A hotspot reservation describes its network as a `SoftApConfiguration` (Android 11). Below it, a `WifiConfiguration`. */
    val hasSoftApConfiguration: Boolean get() = apiLevel >= 30

    /**
     * The flag to add to a `PendingIntent` that the system must be able to fill in (the package
     * installer adds the session result). From Android 12 the mutability must be stated; below it
     * the flag does not exist and every PendingIntent is mutable.
     */
    val mutablePendingIntentFlag: Int get() = if (apiLevel >= 31) PendingIntent.FLAG_MUTABLE else 0

    /**
     * Options for an activity `PendingIntent` that must bring the app back from the background, such
     * as the restart alarm: the app lends it its own right to start activities (the overlay app-op,
     * or being HOME). From Android 15, for apps targeting 35, a PendingIntent no longer gets that by
     * default, and AlarmManager lends none of its own. The option exists from Android 14; below it
     * the default already lends it, so null.
     */
    @Suppress("DEPRECATION") // MODE_BACKGROUND_ACTIVITY_START_ALLOWED: ALLOW_IF_VISIBLE needs a visible window, and a restart has none
    fun backgroundStartOptions(): Bundle? =
        if (apiLevel >= 34) ActivityOptions.makeBasic()
            .setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            .toBundle()
        else null

    /**
     * Calls [callback] as the default network comes, goes and changes. Below Android 7 there is no
     * default-network callback, so it hears every network with internet: callers that only react to
     * "something changed" get the same signal.
     */
    fun registerDefaultNetworkCallback(cm: ConnectivityManager, callback: ConnectivityManager.NetworkCallback) {
        if (hasDefaultNetworkCallback) cm.registerDefaultNetworkCallback(callback)
        else cm.registerNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback,
        )
    }

    /**
     * Registers a receiver for a system broadcast that apps outside this one may also send. From
     * Android 13 the flag is required. Below it, a receiver registered without one is exported.
     */
    fun registerExportedReceiver(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        if (apiLevel >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
    }

    /**
     * Makes [service] a foreground service with exactly [types] (a subset of its manifest types).
     * Without types, Android 14 starts it with every manifest type and throws when one of them
     * lacks its permission. Below Android 10 the types are dropped.
     */
    fun startForeground(service: Service, id: Int, notification: Notification, types: Int) {
        if (hasForegroundServiceTypes) service.startForeground(id, notification, types)
        else service.startForeground(id, notification)
    }

    /**
     * Why this app's process [processName] last ended: an `ApplicationExitInfo.REASON_*` value. Null
     * when the platform keeps no reasons ([hasApplicationExitInfo]) or has none for that process yet.
     */
    fun lastExitReason(context: Context, processName: String): Int? {
        if (!hasApplicationExitInfo) return null
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        return am.getHistoricalProcessExitReasons(context.packageName, 0, 0)
            .firstOrNull { it.processName == processName }?.reason
    }

    /** How one of this app's processes ended, as Android kept it ([processExit]). */
    class ProcessExit(
        /** An `ApplicationExitInfo.REASON_*` value. */
        val reason: Int,
        /** Android's own words for it (the signal, the ANR's subject), if any. */
        val description: String?,
        /** When it ended, ms since the epoch. */
        val atWallMs: Long,
    )

    /**
     * How this app's process [pid] ended: null when the platform keeps no reasons ([hasApplicationExitInfo]) or has
     * none for that process. Never throws.
     */
    fun processExit(context: Context, pid: Int): ProcessExit? {
        if (!hasApplicationExitInfo) return null
        return runCatching {
            context.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(context.packageName, pid, 1)?.firstOrNull { it.pid == pid }
                ?.let { ProcessExit(it.reason, it.description, it.timestamp) }
        }.getOrNull()
    }

    /** How many times this device has booted ([hasBootCount]); null below Android 7, or where the device doesn't say. */
    fun bootCount(context: Context): Int? =
        if (!hasBootCount) null
        else runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull()

    /**
     * The Wi-Fi link's details: from the network's capabilities from Android 12, where `getConnectionInfo`
     * hides them from an app without the location permission, and from [wifi] below it, or where a
     * device leaves the capabilities without them.
     */
    @Suppress("DEPRECATION")
    fun wifiInfo(capabilities: NetworkCapabilities?, wifi: WifiManager?): WifiInfo? =
        (if (apiLevel >= 31) capabilities?.transportInfo as? WifiInfo else null)
            ?: runCatching { wifi?.connectionInfo }.getOrNull()

    /**
     * When this process started, in ms on the elapsed-realtime clock: from Android 7 Android says; below, the start
     * time in [procSelfStat] (`/proc/self/stat`), as the diagnostics pane reads it (#198). Null when that can't be
     * read: unknown, not a guess.
     */
    fun processStartElapsedMs(
        procSelfStat: () -> String? = { runCatching { java.io.File("/proc/self/stat").readText() }.getOrNull() },
        ticksPerSecond: () -> Long = { Os.sysconf(OsConstants._SC_CLK_TCK) },
    ): Long? =
        if (hasProcessStartTime) Process.getStartElapsedRealtime()
        else procSelfStat()?.let(::procStatStartTicks)?.let { ticks -> ticksPerSecond().takeIf { it > 0 }?.let { ticks * 1000 / it } }

    /** The home screen is a role from Android 10 (`RoleManager.ROLE_HOME`), which an app asks for with Android's own dialog. */
    val hasHomeRole: Boolean get() = apiLevel >= 29

    /** This app is the home screen: it holds the home role (Android 10+), or Android's HOME intent goes to it (older). */
    fun isHomeApp(context: Context): Boolean = runCatching {
        // The role where Android has it, as askHome asks for it; else the HOME intent, as askHome's settings page sets it.
        val roles = if (hasHomeRole) context.getSystemService(RoleManager::class.java)?.takeIf { it.isRoleAvailable(RoleManager.ROLE_HOME) } else null
        if (roles != null) roles.isRoleHeld(RoleManager.ROLE_HOME)
        else context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName == context.packageName
    }.getOrDefault(false)

    /**
     * Asks Android to make this app the home screen, from [activity]: the home role's dialog on Android 10+ (started for
     * a result, as the role dialog needs), else Android's home settings. False when Android had nothing to open.
     */
    fun askHome(activity: Activity, requestCode: Int): Boolean {
        if (hasHomeRole) runCatching {
            val roles = activity.getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
                activity.startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), requestCode)
                return true
            }
        }
        return runCatching { activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }.isSuccess
    }

    /** An [Intent] extra, read with the typed getter from Android 13 and the deprecated one below. */
    fun intentExtra(intent: Intent, name: String): Intent? =
        if (apiLevel >= 33) intent.getParcelableExtra(name, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(name)
}

/** The ways an ordinary app opens a Wi-Fi hotspot ([PlatformApi.hotspotKind]). */
enum class HotspotKind {
    /** `WifiManager.startLocalOnlyHotspot`: Android names the network and picks its key. */
    LOCAL_ONLY,

    /** The hidden `WifiManager.setWifiApEnabled`: the app names the network, and must put back what it changed. */
    HIDDEN_AP,
}

/** The queries for the Android this app runs on. */
object Platform : PlatformApi(Build.VERSION.SDK_INT) {
    /**
     * The ABIs this device runs, preferred first (`Build.SUPPORTED_ABIS`). The S1's 32-bit userspace
     * gives `armeabi-v7a, armeabi`; an arm64 device gives `arm64-v8a` first.
     */
    val supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList()

    /** The device's model as its maker names it (`Build.MODEL`): "Pixel Tablet". The S1 says "QUAD-CORE R818 EVB2". */
    val model: String get() = Build.MODEL.orEmpty()

    /** Who made it (`Build.MANUFACTURER`): "Google". The S1 says "Allwinner". */
    val manufacturer: String get() = Build.MANUFACTURER.orEmpty()

    /** The hardware platform (`Build.HARDWARE`, `ro.hardware`): the S1's A133 says "sun50iw10p1". */
    val hardware: String get() = Build.HARDWARE.orEmpty()
}

/**
 * `starttime` from `/proc/<pid>/stat` (field 22): when the process started, in clock ticks since boot. Counted from
 * the last `)`, since the command name in brackets may hold spaces or brackets of its own.
 */
fun procStatStartTicks(procPidStat: String): Long? {
    val f = procPidStat.substringAfterLast(')', "").trim().split(Regex("\\s+"))
    if (f.size < 20) return null
    return f[19].toLongOrNull()
}
