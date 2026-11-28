package dev.glassworks.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import dev.glassworks.dock.DockRow
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
import dev.glassworks.Glassworks
import dev.glassworks.KioskPrefs
import dev.glassworks.Licences
import dev.glassworks.auth.HaAuth
import dev.glassworks.manage.AppManage
import dev.glassworks.setup.SetupPrefs

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
        val hardware = ScreenHardware.of(frame, panes, context)
        val core = CoreSettings(
            prefs, version,
            status = { runtime.screen.status(hardware) + " · Voice: " + runtime.voiceState },
            deviceSettings = if (!deviceSettings) null else SettingsItem.Link(SettingsMenu.deviceSettingsTitle(frame), SettingsMenu.DEVICE_SETTINGS_DETAIL) { c ->
                if (!frame.openSystemSettings(c)) Toast.makeText(c, "No settings app on this panel", Toast.LENGTH_LONG).show()
            },
            licences = SettingsItem.Link(CoreSettings.LICENCES_TITLE, CoreSettings.LICENCES_DETAIL) { c -> (c as? Activity)?.let(Licences::show) },
            live = CoreSettings.Live(
                fullScreen = fullScreen,
                presenceRange = { runtime.screen.applyPresenceRange() },
                brightness = { runtime.screen.refreshBrightness() },
                screenAwake = {
                    val wake = { if (!runtime.screen.screenOn) runtime.screen.wake("settings") }
                    if (Looper.myLooper() == Looper.getMainLooper()) wake() else Handler(Looper.getMainLooper()).post(wake)
                },
                closeSetup = { runtime.lanSetup?.close() },
                factoryReset = { done -> factoryReset(runtime, prefs, context).run(done) },
            ),
            hardware = hardware,
            screenBlueprints = blueprints(CoreSettings.SCREEN),
            account = runtime.dashboardAccount::shown,
            product = runtime.build.product,
            screenLock = { dev.glassworks.platform.ScreenLock.secure(context) },
            panelIp = { dev.glassworks.platform.PanelAddress.now(context) },
            volume = runtime.volume(context),
        )
        // One pane's failure leaves the others' pages.
        val sections = panes.flatMap { d ->
            runCatching { paneSections(listOf(d), blueprints, runtime::runningPane) }.onFailure { Log.w(TAG, "${d.id}'s settings failed", it) }.getOrDefault(emptyList())
        }
        // The frame's own page; a frame whose page fails keeps the rest.
        val device = runCatching { frame.deviceSection(context, runtime::runningPane) }.onFailure { Log.w(TAG, "the device page failed", it) }.getOrNull()
            ?.let { DockRow.withRow(it, runtime::docked) }
        val own = core.sections() + listOfNotNull(device?.let { SettingsPlace.DEVICE to it })
        val ha = withHaLink(settingsOrder(own, sections)) { HaLink.of(connected = runtime.homeAssistantConnected(), added = prefs.haSeen) }
        return KioskSettingsOverlay.Model(ha, version, sections.map { it.id }.toSet(), frameName(runtime))
    }

    /** The factory reset's steps on this app: HaAuth's sign-out, the core's settings files, the Manage page's browsers. */
    private fun factoryReset(runtime: Glassworks, prefs: KioskPrefs, context: Context): FactoryReset {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        return FactoryReset(
            signOut = { done -> HaAuth.init(app); HaAuth.signOut("factory reset", done) },
            // A sign-in started before the reset doesn't finish after it, nor after the restart (they are saved, StateStore).
            clearSettings = { prefs.resetSettings() && SetupPrefs(app).reset() && HaAuth.forgetPendingSignIns(app) },
            signOutBrowsers = { HaAuth.manage?.signOutAll() },
            // As the Overview's Restart app: after the answer has gone out (AppManage.ACTION_DELAY_MS).
            restart = {
                val i = Intent(app, runtime.components.mainActivity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("restart", true)
                main.postDelayed({
                    val sink = runtime.commandSink
                    if (sink != null) sink(i) else runCatching { app.startActivity(i) }.onFailure { Log.w(TAG, "can't restart after the reset", it) }
                }, AppManage.ACTION_DELAY_MS)
            },
            onMain = { main.post(it) },
            log = { Log.i(TAG, it) },
        )
    }

    /** The frame's name in words: "S1 Plus", or the device's ("Google Pixel Tablet"). */
    fun frameName(runtime: Glassworks): String = runtime.frame.shortName ?: runtime.frame.friendlyName()

    /** Whether [context]'s frame has its own settings app, for [model]. */
    fun hasDeviceSettings(runtime: Glassworks, context: Context): Boolean = runtime.frame.systemSettings(context) != null

    private const val TAG = "Glassworks.Settings"
}
