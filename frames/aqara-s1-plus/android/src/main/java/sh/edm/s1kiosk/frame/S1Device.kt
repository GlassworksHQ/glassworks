package sh.edm.s1kiosk.frame

import android.content.Context
import android.content.pm.PackageManager
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.pane.Pane
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import sh.edm.s1kiosk.PanePrefs
import sh.edm.s1kiosk.radio.RadioHelper
import sh.edm.s1kiosk.radio.RadioMode
import sh.edm.s1kiosk.radio.ShellProps
import sh.edm.s1kiosk.radio.SysProps

/**
 * The S1's Device page ([dev.glassworks.frame.Frame.deviceSection]): what belongs to the device and to no
 * pane. Block Aqara cloud (the wall base pane's setting and Home Assistant entity, `block_aqara_cloud`; the
 * pane still applies it), the cloud daemon's state, which of Aqara's apps are turned off
 * (tools/debloat/debloat.sh), and the radio's mode, which opens the Radio pane.
 */
class S1Device(private val cloud: AqaraCloud, private val props: SysProps = ShellProps) {

    fun section(context: Context, running: (String) -> Pane?): SettingsSection {
        val wallBase = PanePrefs(context).wallBase
        return SettingsSection(GenericAndroid.DEVICE, TITLE, listOfNotNull(
            SettingsItem.Switch(BLOCK_TITLE, "Stops ha_master, the panel's link to Aqara's cloud", { wallBase.blockAqaraCloud }) { v, done ->
                wallBase.blockAqaraCloud = v
                done(null)
            },
            SettingsItem.Info({ DAEMON_TITLE }, { daemon(running) }),
            appsReadable(context).takeIf { it }?.let { SettingsItem.Info({ APPS_TITLE }, { apps(context) }) },
            SettingsItem.Link(RADIO_TITLE, radioMode(running), section = "radio"),
        ))
    }

    /** What the wall base pane says of the daemon (`aqara_cloud`), else init's word for it. */
    private fun daemon(running: (String) -> Pane?): String =
        running("wall-base")?.entities()?.firstOrNull { it.objectId == "aqara_cloud" }?.shownState()?.takeIf { it.isNotBlank() && it != "unknown" }
            ?: cloud.daemonState().ifBlank { "Unknown" }

    /** The radio pane's `Radio mode`, else the mode the root helper was asked for. */
    private fun radioMode(running: (String) -> Pane?): String =
        running("radio")?.entities()?.firstOrNull { it.objectId == "radio_mode" }?.shownState()?.takeIf { it.isNotBlank() }
            ?: RadioMode.fromId(props.get(RadioHelper.PROP_MODE))?.label ?: RadioMode.OFF.label

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

        /** tools/debloat/debloat.sh's list and its launcher, in its order, with a name for each. */
        val AQARA_APPS = linkedMapOf(
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
