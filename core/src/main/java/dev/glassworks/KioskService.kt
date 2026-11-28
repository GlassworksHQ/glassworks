package dev.glassworks

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.SystemClock
import dev.glassworks.frame.AgentCapability
import dev.glassworks.frame.ServiceWork
import dev.glassworks.platform.Hotspot
import dev.glassworks.platform.HotspotFailure
import dev.glassworks.platform.WifiDriverRecovery
import dev.glassworks.platform.WifiRadio
import dev.glassworks.platform.NetworkUp
import dev.glassworks.platform.Platform
import dev.glassworks.setup.CaptivePortal
import dev.glassworks.setup.LanSetup
import dev.glassworks.setup.PortalAddresses
import dev.glassworks.setup.PortalHotspot
import dev.glassworks.setup.SetupHotspot
import dev.glassworks.settings.SettingsMenu
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.glassworks.auth.HaAuth
import dev.glassworks.auth.OAuth
import dev.glassworks.auth.UrlConnectionPoster
import dev.glassworks.frame.systemSettings
import dev.glassworks.discovery.HaDiscovery
import dev.glassworks.device.PanelDevice
import dev.glassworks.provisioning.ImprovController
import dev.glassworks.provisioning.ImprovSelfAuth
import dev.glassworks.provisioning.ImprovSerialServer
import dev.glassworks.device.SecretHandoverServer
import dev.glassworks.improv.AdbSocketServer
import dev.glassworks.manage.CodeOverlay
import dev.glassworks.setup.SetupChannelServer

/**
 * Long-lived foreground service that owns the sensor/screen policy and the ESPHome device
 * (native API server + voice assistant), so they keep running while the screen is off and the
 * activity is stopped.
 */
open class KioskService : Service() {

    private val main = Handler(Looper.getMainLooper())
    /** The app's runtime, for its parts and the device it starts. */
    private val runtime get() = glassworksRuntime
    private var device: PanelDevice? = null
    private var discovery: HaDiscovery? = null
    /** Cancels the start-up work still waiting for the first dashboard frame ([AfterFirstFrame]). */
    private val deferred = ArrayList<() -> Unit>()
    /** The ESPHome API key's hand-over over adb, for a reflash (#574). */
    private var secretHandover: AdbSocketServer? = null
    private var frameWork: ServiceWork? = null
    private var codeOverlay: CodeOverlay? = null
    private lateinit var foreground: KioskForeground
    private var hotspotNetworks: ConnectivityManager.NetworkCallback? = null
    private var wifiRecovery: WifiDriverRecovery? = null
    private var wifiStates: BroadcastReceiver? = null

    /** The scan before each hotspot start, into the runtime's list the setup page reads. */
    private val scanFirst = dev.glassworks.setup.HotspotScanStep(
        scan = { done ->
            ImprovController.scanFromInstaller { nets ->
                done(nets.map { dev.glassworks.setup.WifiNetwork(it.ssid, it.rssi, secure = it.auth != "NO") })
            }
        },
        store = { glassworks.hotspotScan = it },
        log = { Log.i(HOTSPOT_TAG, it) },
    )

    override fun onCreate() {
        super.onCreate()
        glassworks.paneDefinitions(this) // first: a step's one-time check runs before discovery can save an HA URL
        val notification = buildNotification()
        foreground = KioskForeground(
            Platform,
            onRefused = { t, e -> Log.w("Glassworks", "foreground service types $t (${KioskForeground.names(t)}) refused", e) },
            startWith = { Platform.startForeground(this, NOTIFICATION_ID, notification, it) },
        )
        promote()
        glassworks.screen.start(this)
        frameWork = glassworks.frame.serviceWork(this) { main.post { if (frameWork != null) promote() } }?.also { it.start() }
        runtime.app.serviceStarted(this, runtime)
        glassworks.voiceCancel = { device?.cancelVoice() }
        HaAuth.startServer(this)
        codeOverlay = CodeOverlay(this, touch = glassworks.frame.input == dev.glassworks.frame.Input.TOUCH) { glassworks.panelCode.dismiss() }
        // Without "draw over other apps" the code can't show: the Manage page says so instead of waiting for it.
        glassworks.panelCode.canDisplay = { android.provider.Settings.canDrawOverlays(this) }
        glassworks.panelCode.display = { shown ->
            main.post {
                if (codeOverlay == null) return@post
                glassworks.screen.stayOn(PANEL_CODE_STAY_ON, shown != null)
                if (shown != null) glassworks.screen.wake("panel code")
                codeOverlay?.show(shown)
                // Off the screen when it runs out (expire() leaves a newer code alone), so it doesn't keep the screen on.
                if (shown != null) main.postDelayed({ glassworks.panelCode.expire() }, (shown.untilMs - System.currentTimeMillis()).coerceAtLeast(0) + 500)
            }
        }
        val d = HaDiscovery(this, UrlConnectionPoster(timeoutMs = 5_000)) { url, source ->
            // Applied by the activity (dashboard URL "auto", or a .local one reached by address: load it, or restart for
            // secure-context hosts).
            main.post { glassworks.commandSink?.invoke(Intent().putExtra("ha_discovered", url).putExtra("ha_source", source)) }
        }
        discovery = d
        glassworks.discovery = d
        // The mDNS browse, and the ESPHome device with its panes (the wake word and microphone, Sendspin, the wall
        // base's probes, the API server and its mDNS), start after the first dashboard frame: on the S1 they took the
        // CPU and the main thread from its first build. A sign-in, a manual URL or the ESPHome hello still reach
        // discovery meanwhile, and Home Assistant only retries until the API server listens.
        deferred += glassworks.afterFirstFrame.run("Home Assistant discovery") { d.start() }
        device = PanelDevice(this, runtime, runtime::devicePanes, d::onApiHello).also { dev ->
            runtime.device = dev
            deferred += glassworks.afterFirstFrame.run("ESPHome device and panes") { dev.start() }
        }
        ImprovController.init(this) // Improv Wi-Fi via BLE (provisioning mode; see improv/)
        // The web installer, over adb (docs/onboarding.md): Wi-Fi via Improv Serial, then HA + radio.
        ImprovSerialServer.start(this, glassworks.frame, glassworks.build)
        SetupChannelServer.start(this, glassworks)
        secretHandover = SecretHandoverServer.create(this).also { it.start() }
        startSetupHotspot()
        watchWifiDriver()
        Log.i("Glassworks", "KioskService started")
    }

    /**
     * The setup hotspot (#197): the panel opens its own Wi-Fi when no saved Wi-Fi works (SetupHotspotPolicy). Here,
     * not in the activity: Android starts a local-only hotspot only for an app in the foreground, and this foreground
     * service counts, screen off included. Any network coming or going is a change for the rule.
     */
    private fun startSetupHotspot() {
        val schedule: (Long, () -> Unit) -> () -> Unit = { delay, run -> val r = Runnable(run); main.postDelayed(r, delay); { main.removeCallbacks(r) } }
        // On a frame whose agent sends the hotspot's DNS and port 80 to the app, a phone opens the setup page by itself.
        // Declared, not offered: the listeners are the request (docs/frame-agent.md), and without the agent they idle.
        val hotspot = Hotspot.of(this, main).let { h ->
            if (AgentCapability.HOTSPOT_PORTAL !in glassworks.frame.agent.declared) h
            else PortalHotspot(h, CaptivePortal({ ap -> "http://$ap:${OAuth.SIGNIN_PORT}${LanSetup.PAGE}" }, log = { Log.i(HOTSPOT_TAG, it) }), schedule,
                addresses = { PortalAddresses.now(this) }, log = { Log.i(HOTSPOT_TAG, it) })
        }
        val hs = SetupHotspot(
            hotspot = hotspot,
            connected = { NetworkUp.now(this) },
            clock = SystemClock::elapsedRealtime,
            schedule = schedule,
            // The screen follows at once (a phone joined: the setup page's QR), not on its 2 s tick.
            onChange = { dev.glassworks.setup.SetupSignals.changed() },
            log = { Log.i(HOTSPOT_TAG, it) },
            // Without touch, after Bluetooth setup's own 5 minutes (#611).
            graceMs = ImprovSelfAuth.hotspotGraceMs(glassworks.frame.input, KioskPrefs(this).improvAuto, ImprovController.BOOT_GRACE_MS),
            blocked = { if (WifiRadio.lockedOff(this)) HotspotFailure.WIFI_OFF else null },
            // A fresh scan first (up to 15 s): the radio can't scan once it is the access point.
            beforeStart = { start ->
                // Where the app may turn the radio on (Android 9 and older, the device owner), it does: an off radio
                // couldn't scan nor join. Where it may not, the hotspot is blocked before it gets here.
                if (!WifiRadio.ensureOn(this)) Log.w(HOTSPOT_TAG, "Wi-Fi is off, and it didn't come on")
                scanFirst(start)
            },
            // Rule 1's own start finds Wi-Fi off (the Mirror's driver failed at boot): on first, and the saved network
            // gets its minutes before the hotspot takes the radio.
            switchRadioOn = { WifiRadio.switchedOff(this) && WifiRadio.ensureOn(this) },
        )
        glassworks.setupHotspot = hs
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { main.post { hs.refresh() } }
            override fun onLost(network: Network) { main.post { hs.refresh() } }
        }
        runCatching { getSystemService(ConnectivityManager::class.java)?.registerNetworkCallback(NetworkRequest.Builder().build(), cb) }
            .onSuccess { hotspotNetworks = cb }
            .onFailure { Log.w(HOTSPOT_TAG, "can't follow the networks; the 3-minute check still runs", it) }
        hs.start()
    }

    /**
     * Switches Wi-Fi back on when Android failed to load its driver at boot, where the app may ([WifiDriverRecovery]:
     * the lululemon Mirror's stock Android loses that race once the apps are optimized). The broadcast is sticky, so
     * a failure from before the service started counts too.
     */
    @Suppress("DEPRECATION") // setWifiEnabled: the call this is about, where the app may (mayEnable)
    private fun watchWifiDriver() {
        val wifi = applicationContext.getSystemService(WifiManager::class.java) ?: return
        val schedule: (Long, () -> Unit) -> () -> Unit = { delay, run -> val r = Runnable(run); main.postDelayed(r, delay); { main.removeCallbacks(r) } }
        val recovery = WifiDriverRecovery(
            mayEnable = { WifiRadio.mayEnable(this) },
            state = { wifi.wifiState },
            enable = { wifi.setWifiEnabled(true) },
            schedule = schedule,
            log = { Log.i(WIFI_TAG, it) },
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                recovery.onState(
                    // -1 without the extra: never read as a failure.
                    i.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1),
                    i.getIntExtra(WifiManager.EXTRA_PREVIOUS_WIFI_STATE, -1),
                )
            }
        }
        runCatching { registerReceiver(receiver, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION), null, main) }
            .onSuccess { wifiRecovery = recovery; wifiStates = receiver }
            .onFailure { Log.w(WIFI_TAG, "can't follow Wi-Fi's state", it) }
    }

    override fun onDestroy() {
        deferred.forEach { it() } // what hasn't started yet doesn't start after the service
        deferred.clear()
        wifiStates?.let { runCatching { unregisterReceiver(it) } }
        wifiStates = null
        wifiRecovery?.close()
        wifiRecovery = null
        hotspotNetworks?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        hotspotNetworks = null
        glassworks.setupHotspot?.close() // stops it, and puts back what it changed
        glassworks.setupHotspot = null
        glassworks.voiceCancel = null
        SetupChannelServer.stop()
        secretHandover?.stop()
        secretHandover = null
        ImprovSerialServer.stop()
        ImprovController.shutdown()
        runtime.device = null
        device?.stop()
        device = null
        discovery?.stop()
        discovery = null
        glassworks.discovery = null
        HaAuth.stopServer()
        glassworks.lanSetup = null
        glassworks.panelCode.display = {}
        glassworks.panelCode.canDisplay = { false }
        glassworks.screen.stayOn(PANEL_CODE_STAY_ON, false)
        codeOverlay?.show(null)
        codeOverlay = null
        frameWork?.stop()
        frameWork = null
        super.onDestroy()
    }

    /**
     * Runs the service with every foreground type it may have now. Called on each start, so
     * [start] after a runtime grant adds the type without a restart (voice keeps the mic with the
     * screen off). Also called when the frame's service work asks (the S1's Wi-Fi scanner, on its first refusal for the location app-op), which
     * covers a grant from outside the app. Does nothing while the types are unchanged.
     */
    private fun promote() {
        val granted = { p: String -> checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED }
        val before = foreground.types
        val types = foreground.promote(
            micGranted = granted(Manifest.permission.RECORD_AUDIO),
            locationGranted = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION),
            // Only on a device with a camera: the S1 has none, and its service stays as it was.
            cameraGranted = granted(Manifest.permission.CAMERA) && packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
        )
        if (types != before) {
            Log.i("Glassworks", "foreground service types $types (${KioskForeground.names(types)}; location ${if (types and KioskForeground.LOCATION != 0) "on" else "off"})")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote()
        ImprovController.handleIntent(intent) // --ez improv / release_device_owner (via ImprovActivity)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Platform.hasNotificationChannels) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Glassworks", NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false) }
            )
        }
        // Tapping it opens the kiosk settings menu (a second way in besides the 5 taps; anyone at the panel
        // can pull the shade down). MainActivity is singleTask: the running one gets it in onNewIntent.
        val menu = PendingIntent.getActivity(
            this, REQ_KIOSK_SETTINGS,
            Intent(this, glassworks.components.mainActivity)
                .putExtra(SettingsMenu.EXTRA, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        @Suppress("DEPRECATION")
        val b = if (Platform.hasNotificationChannels) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this)
        b.setSmallIcon(notificationIcon())
            .setContentTitle(glassworks.build.product)
            .setContentText("Tap for panel settings · screen, sensors & panel service running")
            .setContentIntent(menu)
            .setOngoing(true)
        // The shade has no settings gear until Aqara's own onboarding ran: Aqara's settings app from here too.
        glassworks.frame.systemSettings(this)?.let { settings ->
            val pi = PendingIntent.getActivity(this, REQ_PANEL_SETTINGS, settings, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            @Suppress("DEPRECATION")
            b.addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, notificationIcon()), "Panel settings", pi).build())
        }
        return b.build()
    }

    /**
     * The app's notification icon ([dev.glassworks.platform.AppBuild.notificationIcon]), else its icon from its manifest;
     * Android's default app icon for an APK without one, so the service still starts. An icon Android can't load
     * crashes the app (RemoteServiceException "Couldn't create icon": the Mirror's first start, #198).
     */
    private fun notificationIcon(): Int = glassworks.build.notificationIcon.takeIf { it != 0 }
        ?: applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.sym_def_app_icon

    companion object {
        private const val HOTSPOT_TAG = "Glassworks.Hotspot"
        private const val WIFI_TAG = "Glassworks.Wifi"
        private const val PANEL_CODE_STAY_ON = "panel_code"
        private const val CHANNEL = "kiosk"
        private const val NOTIFICATION_ID = 1
        private const val REQ_KIOSK_SETTINGS = 10  // distinct PendingIntent request codes
        private const val REQ_PANEL_SETTINGS = 11

        fun start(context: Context) {
            val i = Intent(context, context.glassworks.components.service)
            if (Platform.hasForegroundServiceStart) context.startForegroundService(i)
            else context.startService(i)
        }
    }
}
