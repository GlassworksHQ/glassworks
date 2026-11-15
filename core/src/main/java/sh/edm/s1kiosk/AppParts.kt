package sh.edm.s1kiosk

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import dev.glassworks.manage.ManagePage
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneDefinition
import sh.edm.s1kiosk.auth.HaTokenClient
import sh.edm.s1kiosk.auth.SignInServer
import sh.edm.s1kiosk.esphome.DeviceHost

/**
 * What an app adds to the [GlassworksRuntime] it runs: its panes and their layers over the dashboard, where
 * their settings live, its activity and its Manage page. Each app has its own (the S1's: [S1Panes]), so each runs the panes its frames can.
 */
interface AppParts {
    /**
     * The app's pane definitions, made once, in the order the settings list them, with the frame's own
     * ([frameDefinitions]) placed among them. The runtime makes the ones the frame can't run CantRun.
     */
    fun paneDefinitions(context: Context, runtime: GlassworksRuntime, frameDefinitions: List<PaneDefinition>): List<PaneDefinition>

    /** The app's panes for the panel's device, built from what the device gives them, in the order they start. */
    fun devicePanes(runtime: GlassworksRuntime, host: DeviceHost): List<Pane>

    /** The app's layers over the dashboard in its activity, built from what the activity gives them, bottom first. */
    fun overlays(runtime: GlassworksRuntime, host: OverlayHost): List<PaneOverlay>

    /** The activity started (each start, off the main thread): the app's own upkeep (the S1's direct-boot splash). */
    fun activityStarted(context: Context) {}

    /** The app's service started, before it starts the device: the app's own first state (the S1's voice state). */
    fun serviceStarted(context: Context, runtime: GlassworksRuntime) {}

    /** The app's activity: the one that shows the dashboard, and that Home Assistant's commands go to. */
    val mainActivity: Class<out Activity>

    /** The file that holds the settings of the pane with id [namespace]. */
    fun paneFile(context: Context, namespace: String): SharedPreferences

    /** The app's Manage page behind the sign-in pages ([signIn]), for the panel's web server. */
    fun managePage(context: Context, runtime: GlassworksRuntime, signIn: SignInServer, tokens: HaTokenClient, prefs: KioskPrefs): ManagePage
}
