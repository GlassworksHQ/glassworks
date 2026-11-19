package dev.glassworks.s1.frame

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import dev.glassworks.frame.FrameRelease
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.pane.Pane
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import dev.glassworks.update.SilentUpdates
import dev.glassworks.update.UpdateChannelRow
import dev.glassworks.s1.PanePrefs
import dev.glassworks.radio.RadioHelper
import dev.glassworks.radio.RadioMode
import dev.glassworks.radio.ShellProps
import dev.glassworks.radio.SysProps
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The S1's Device page ([dev.glassworks.frame.Frame.deviceSection]): what belongs to the device and to no
 * pane. Block Aqara cloud (the wall base pane's setting and Home Assistant entity, `block_aqara_cloud`; the
 * pane still applies it), the cloud daemon's state, which of Aqara's apps are turned off
 * (frames/aqara-s1-plus/tools/debloat/debloat.sh), the radio's mode, which opens the Radio pane, and whether
 * app updates install silently (the boot image's root update service, #422), whether boot has the frame loader (the
 * boot writer's record, #139 route 2), and the frame version (#139).
 */
class S1Device(
    private val cloud: AqaraCloud,
    private val props: SysProps = ShellProps,
    /** Where `getprop` runs: never on the main thread, which builds this page every couple of seconds. */
    private val reader: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "s1-device").apply { isDaemon = true } },
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    /** The boot writer's record (/data/glassworks-pub/boot-writer); a test passes its own. */
    private val record: () -> BootWriter.Record? = { BootWriter.read() },
) {
    private val values = ConcurrentHashMap<String, String>()
    private val readAt = ConcurrentHashMap<String, Long>()

    /** [key]'s last value from [read], read again on [reader] when older than [STALE_MS]; null until the first read lands. */
    private fun cached(key: String, read: () -> String): String? {
        val now = clock()
        val last = readAt[key]
        if (last == null || now - last > STALE_MS) {
            readAt[key] = now
            reader.execute { values[key] = read() }
        }
        return values[key]
    }

    fun section(context: Context, release: FrameRelease? = null, running: (String) -> Pane?): SettingsSection {
        val wallBase = PanePrefs(context).wallBase
        return SettingsSection(GenericAndroid.DEVICE, TITLE, listOfNotNull(
            SettingsItem.Switch(BLOCK_TITLE, "Stops ha_master, the panel's link to Aqara's cloud", { wallBase.blockAqaraCloud }) { v, done ->
                wallBase.blockAqaraCloud = v
                done(null)
            },
            SettingsItem.Info({ DAEMON_TITLE }, { daemon(running) }),
            appsReadable(context).takeIf { it }?.let { SettingsItem.Info({ APPS_TITLE }, { apps(context) }) },
            SettingsItem.Link(RADIO_TITLE, radioMode(running), section = "radio"),
            GenericAndroid.silentUpdatesItem { rootService()?.let { SilentUpdates.read(context, it) } },
            SettingsItem.Info({ BootWriter.TITLE }, { loader() }),
            UpdateChannelRow.item(context),
            release?.let { GenericAndroid.frameVersionItem(context, it) },
        ))
    }

    /** Whether this boot image has the root update service; null until the first getprop lands. */
    private fun rootService(): SilentUpdates.Root? =
        cached("root") { RootUpdateInstaller(props).present().toString() }?.let { if (it.toBoolean()) SilentUpdates.Root.PRESENT else SilentUpdates.Root.MISSING }

    /** The frame loader in boot (#139): what the boot writer's record says, with whether this boot ran the loader. */
    private fun loader(): String =
        cached("loader") { BootWriter.rowText(record(), props.get(BootWriter.LOADER_PROP).isNotBlank()) } ?: "Checking"

    /** What the wall base pane says of the daemon (`aqara_cloud`), else init's word for it. */
    private fun daemon(running: (String) -> Pane?): String =
        running("wall-base")?.entities()?.firstOrNull { it.objectId == "aqara_cloud" }?.shownState()?.takeIf { it.isNotBlank() && it != "unknown" }
            ?: cached("daemon", cloud::daemonState)?.ifBlank { null } ?: "Unknown"

    /** The radio pane's `Radio mode`, else the mode the root helper was asked for. */
    private fun radioMode(running: (String) -> Pane?): String =
        running("radio")?.entities()?.firstOrNull { it.objectId == "radio_mode" }?.shownState()?.takeIf { it.isNotBlank() }
            ?: cached("radio") { props.get(RadioHelper.PROP_MODE) }?.let { RadioMode.fromId(it)?.label ?: RadioMode.OFF.label } ?: "Unknown"

    /** The app can see Aqara's apps (Android 10 lets it); on another firmware none is there and the row is left out. */
    private fun appsReadable(context: Context): Boolean = AQARA_APPS.keys.any { state(context, it) != null }

    /** "Turned off: Cloud manager, Firmware updater (2 of 10)", or "None turned off". */
    private fun apps(context: Context): String {
        val states = AQARA_APPS.keys.associateWith { state(context, it) }.filterValues { it != null }
        val off = states.filterValues { it == false }.keys.map { AQARA_APPS.getValue(it) }
        return if (off.isEmpty()) "None turned off" else "Turned off: ${off.joinToString(", ")} (${off.size} of ${states.size})"
    }

    /** True while the app is on, false while turned off, null when it isn't installed or can't be read. */
    private fun state(context: Context, pkg: String): Boolean? = runCatching {
        when (context.packageManager.getApplicationEnabledSetting(pkg)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
            else -> true
        }
    }.getOrNull()

    companion object {
        const val TITLE = "S1 Plus"
        const val BLOCK_TITLE = "Block Aqara cloud"
        const val DAEMON_TITLE = "Aqara cloud daemon"
        const val APPS_TITLE = "Aqara apps"
        const val RADIO_TITLE = "Radio mode"
        const val STALE_MS = 5_000L

        /** frames/aqara-s1-plus/tools/debloat/debloat.sh's list and its launcher, in its order, with a name for each. */
        val AQARA_APPS = mapOf(
            "com.aqara.inhome.manager" to "Cloud manager",
            "com.lumi.ota" to "Firmware updater",
            "com.aqara.inhome.vassistant" to "Voice assistant",
            "com.aqara.inhome.embedded_chat" to "Chat",
            "com.aqara.music" to "Music",
            "com.lumi.siri" to "HomeKit bridge",
            "com.lumi.dream" to "Screensaver",
            "com.lumi.aging" to "Burn-in test",
            "com.lumi.factory" to "Factory test",
            "com.aqara.inhome.launcher" to "Launcher",
        )
    }
}
