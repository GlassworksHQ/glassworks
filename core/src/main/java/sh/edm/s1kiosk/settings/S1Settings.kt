package sh.edm.s1kiosk.settings

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.Toast
import dev.glassworks.frame.friendlyName
import dev.glassworks.frame.openSystemSettings
import dev.glassworks.frame.systemSettings
import dev.glassworks.pane.HaLink
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsPlace
import dev.glassworks.pane.paneSections
import dev.glassworks.pane.settingsOrder
import dev.glassworks.pane.withHaLink
import dev.glassworks.screen.ScreenHardware
import sh.edm.s1kiosk.Glassworks
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.Licences

/**
 * The panel's settings: the one model the menu ([KioskSettingsOverlay]) and the Manage page both draw
 * and change (#242). The core's sections and the panes', in the order every settings surface uses. Made
 * again each time it's read, so it has the values and the running panes of now.
 *
 * [fullScreen]: what the running dashboard does when Full screen changes (the menu pushes the page's
 * config itself; the Manage page asks the activity).
 */
object S1Settings {

    fun model(
        runtime: Glassworks, prefs: KioskPrefs, panes: List<PaneDefinition>,
        /** The frame has its own settings app ([dev.glassworks.frame.Frame.systemSettings]): resolved by the caller, it costs a lookup. */
        deviceSettings: Boolean,
        fullScreen: () -> Unit,
        /** The app's version as the menu and About show it ("0.4.3 · test build abc1234" on a test build). */
        version: String = runtime.build.shown,
        /** A pane's Blueprints card by its id ([PanelBlueprints.item]), or null for none. */
        blueprints: (String) -> SettingsItem.Blueprints? = { null },
        /** The device: the frame's Device page and the Screen page's hardware ([ScreenHardware.of]) read it. */
        context: Context,
    ): KioskSettingsOverlay.Model {
        val frame = runtime.frame
        val core = CoreSettings(
            prefs, version,
            status = { runtime.screen.statusLine() + " · voice=" + runtime.voiceState },
            deviceSettings = if (!deviceSettings) null else SettingsItem.Link(SettingsMenu.deviceSettingsTitle(frame), SettingsMenu.DEVICE_SETTINGS_DETAIL) { c ->
                if (!frame.openSystemSettings(c)) Toast.makeText(c, "No settings app on this panel", Toast.LENGTH_LONG).show()
            },
            licences = SettingsItem.Link(CoreSettings.LICENCES_TITLE, CoreSettings.LICENCES_DETAIL) { c -> (c as? Activity)?.let(Licences::show) },
            live = CoreSettings.Live(
                fullScreen = fullScreen,
                presenceRange = { runtime.screen.applyPresenceRange() },
                brightness = { runtime.screen.refreshBrightness() },
            ),
            hardware = ScreenHardware.of(frame, panes, context),
            screenBlueprints = blueprints(CoreSettings.SCREEN),
            account = runtime.dashboardAccount::shown,
            product = runtime.build.product,
        )
        // One pane's failure leaves the others' pages.
        val sections = panes.flatMap { d ->
            runCatching { paneSections(listOf(d), blueprints, runtime::runningPane) }.onFailure { Log.w(TAG, "${d.id}'s settings failed", it) }.getOrDefault(emptyList())
        }
        // The frame's own page; a frame whose page fails keeps the rest.
        val device = runCatching { frame.deviceSection(context, runtime::runningPane) }.onFailure { Log.w(TAG, "the device page failed", it) }.getOrNull()
        val own = core.sections() + listOfNotNull(device?.let { SettingsPlace.DEVICE to it })
        val ha = withHaLink(settingsOrder(own, sections)) { HaLink.of(connected = runtime.homeAssistantConnected(), added = prefs.haSeen) }
        return KioskSettingsOverlay.Model(ha, version, sections.map { it.id }.toSet(), frameName(runtime))
    }

    /** The frame's name in words: "S1 Plus", or the device's ("Google Pixel Tablet"). */
    fun frameName(runtime: Glassworks): String = runtime.frame.shortName ?: runtime.frame.friendlyName()

    /** Whether [context]'s frame has its own settings app, for [model]. */
    fun hasDeviceSettings(runtime: Glassworks, context: Context): Boolean = runtime.frame.systemSettings(context) != null

    private const val TAG = "S1Kiosk.Settings"
}
