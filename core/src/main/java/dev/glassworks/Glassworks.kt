package dev.glassworks

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import dev.glassworks.engine.Engine
import dev.glassworks.frame.Frame
import dev.glassworks.manage.ManagePage
import dev.glassworks.manage.PanelCode
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.SetupStep
import dev.glassworks.platform.AppBuild
import dev.glassworks.screen.PresenceRelay
import dev.glassworks.screen.ScreenPolicy
import dev.glassworks.auth.HaTokenClient
import dev.glassworks.auth.SignInServer
import dev.glassworks.discovery.HaDiscovery
import dev.glassworks.setup.SetupPane
import dev.glassworks.update.UpdateManager
import dev.glassworks.assist.OrbBakes

/**
 * What the app's components hand each other while the process runs. The app owns the one instance;
 * Android entry points get it with [glassworks], everything else is given it.
 */
interface Glassworks {
    /** The device this app runs on, made once with the runtime. */
    val frame: Frame

    /** The APK's build: its version, for everything that reports or compares it. */
    val build: AppBuild

    /** Where this app's updates come from: its own releases' tags and APK names (#23). */
    val releases: dev.glassworks.update.ReleaseLine

    /** The frame's presence sensor, plus the camera pane's readings on a device without one (#117). */
    val presence: PresenceRelay

    /** The screen's on/off and brightness, from the frame's sensors. MainActivity and KioskService start it. */
    val screen: ScreenPolicy

    /** The web engine that shows the dashboard. */
    val engine: Engine

    /** The glass orb's shared layers: the timer-done stage and the critical alert bake them once. Main thread. */
    val orbs: OrbBakes

    /**
     * Every pane's definition, made once: their on/off, setup steps, settings sections and adb extras.
     * MainActivity and KioskService ask for them first thing, before discovery can save an HA URL in
     * this process (the radio step's one-time check needs that).
     */
    fun paneDefinitions(context: Context): List<PaneDefinition>

    /** The panes' setup steps, in order, for the setup screen and the setup channel. */
    fun setupSteps(context: Context): List<SetupStep>

    /** The panes on setup's Panes step, in definition order: each card with its pane's usable steps. */
    fun setupPanes(context: Context): List<SetupPane>

    /**
     * Whether camera frames are leaving the panel now (a still to Home Assistant, a stream), for the
     * privacy indicator (#117). Set by the camera pane; [onCameraLeaving] hears each change.
     */
    var cameraLeaving: Boolean
    var onCameraLeaving: (() -> Unit)?

    /**
     * The panes the device shell built, also the ones that are off; empty while it isn't running. Each
     * pane's settings page reads its own (#212).
     */
    var panes: List<Pane>

    fun runningPane(id: String): Pane? = panes.firstOrNull { it.id == id }

    /** Home Assistant is connected and listening to the panel's states now; false while the device isn't running. */
    fun homeAssistantConnected(): Boolean

    /** Set while KioskService runs; the sign-in screen uses it for manual HA URL entry. */
    var discovery: HaDiscovery?

    /**
     * The setup hotspot (#197), set while KioskService runs (it needs the service's foreground). The setup screen
     * draws it and asks for it; Wi-Fi details, setup's end and the setup page's Done stop it. Set and used on the main
     * thread; other threads (the setup page's pairing gate) read only its `isOn`.
     */
    var setupHotspot: dev.glassworks.setup.SetupHotspot?

    /**
     * The networks the panel saw just before its setup hotspot started: while it runs, the radio can't scan on most
     * devices (S1 panel run, 2026-11-16), so the setup page lists these. Any thread.
     */
    var hotspotScan: List<dev.glassworks.setup.WifiNetwork>

    /**
     * Applies an intent's extras in the running activity (main thread), like onNewIntent. Used by the
     * ESPHome device so Home Assistant can drive the kiosk without starting activities from the background.
     */
    var commandSink: ((Intent) -> Unit)?

    /** The running update manager, for UpdateInstallReceiver. */
    var updates: UpdateManager?

    /** The code the panel shows for the Manage page (sign-in without HA, dangerous actions). KioskService draws it. */
    val panelCode: PanelCode

    /** Last voice assistant state, exposed to the page via MainActivity.stateJson(). */
    var voiceState: String

    /** Called on the main thread after every voice event (the activity's voice overlay). */
    var onVoiceUi: (() -> Unit)?

    /** Cancels the current voice run (stops streaming and playback); null while the service isn't running. */
    var voiceCancel: (() -> Unit)?

    /** The app's classes for the core's components ([AppComponents]): start and name these, never the core's own. */
    val components: AppComponents

    /** The file that holds the settings of the pane with id [namespace]: the app decides (PaneContext.prefs). */
    fun paneFile(context: Context, namespace: String): SharedPreferences

    /** Who the dashboard is signed in as in Home Assistant, for the settings' "Dashboard sign-in" row. */
    val dashboardAccount: dev.glassworks.auth.DashboardAccount

    /** The app's Manage page behind the sign-in pages ([signIn]), for the panel's web server. */
    fun managePage(context: Context, signIn: SignInServer, tokens: HaTokenClient, prefs: KioskPrefs): ManagePage
}

/** The app's Application: it owns the runtime. */
interface GlassworksApp {
    val glassworks: Glassworks
}

val Context.glassworks: Glassworks get() = (applicationContext as GlassworksApp).glassworks
