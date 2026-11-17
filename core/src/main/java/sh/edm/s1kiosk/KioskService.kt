package sh.edm.s1kiosk

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.SystemClock
import dev.glassworks.frame.ServiceWork
import dev.glassworks.platform.Hotspot
import dev.glassworks.platform.HotspotFailure
import dev.glassworks.platform.WifiRadio
import dev.glassworks.platform.NetworkUp
import dev.glassworks.platform.Platform
import dev.glassworks.setup.CaptivePortal
import dev.glassworks.setup.LanSetup
import dev.glassworks.setup.PortalAddresses
import dev.glassworks.setup.PortalHotspot
import dev.glassworks.setup.SetupHotspot
import sh.edm.s1kiosk.settings.SettingsMenu
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import sh.edm.s1kiosk.auth.HaAuth
import sh.edm.s1kiosk.auth.OAuth
import sh.edm.s1kiosk.auth.UrlConnectionPoster
import dev.glassworks.frame.systemSettings
import sh.edm.s1kiosk.discovery.HaDiscovery
import sh.edm.s1kiosk.esphome.PanelDevice
import sh.edm.s1kiosk.improv.ImprovController
import sh.edm.s1kiosk.improv.ImprovSerialServer
import sh.edm.s1kiosk.manage.CodeOverlay
import sh.edm.s1kiosk.setup.SetupChannelServer

/**
 * Long-lived foreground service that owns the sensor/screen policy and the ESPHome device
 * (native API server + voice assistant), so they keep running while the screen is off and the
 * activity is stopped.
 */
class KioskService : Service() {

    private val main = Handler(Looper.getMainLooper())
    /** The app's runtime, for its parts and the device it starts. */
    private val runtime get() = glassworksRuntime
    private var device: PanelDevice? = null
    private var discovery: HaDiscovery? = null
    private var frameWork: ServiceWork? = null
    private var codeOverlay: CodeOverlay? = null
    private lateinit var foreground: KioskForeground
    private var hotspotNetworks: ConnectivityManager.NetworkCallback? = null

    /** The scan before each hotspot start, into the runtime's list the setup page reads. */
    private val scanFirst = sh.edm.s1kiosk.setup.HotspotScanStep(
        scan = { done ->
            ImprovController.scanFromInstaller { nets ->
                done(nets.map { sh.edm.s1kiosk.setup.WifiNetwork(it.ssid, it.rssi, secure = it.auth != "NO") })
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
            onRefused = { t, e -> Log.w("S1Kiosk", "foreground service types $t (${KioskForeground.names(t)}) refused", e) },
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
                if (shown != null) glassworks.screen.wake("panel code")
                codeOverlay?.show(shown)
                // Off the screen when it runs out (expire() leaves a newer code alone), so it doesn't keep the screen on.
                if (shown != null) main.postDelayed({ glassworks.panelCode.expire() }, (shown.untilMs - System.currentTimeMillis()).coerceAtLeast(0) + 500)
            }
        }
        val d = HaDiscovery(this, UrlConnectionPoster(timeoutMs = 5_000)) { url, source ->
            // Applied by the activity (dashboard URL "auto": load it, or restart for secure-context hosts).
            main.post { glassworks.commandSink?.invoke(Intent().putExtra("ha_discovered", url).putExtra("ha_source", source)) }
        }
        discovery = d
        glassworks.discovery = d
        d.start()
        device = PanelDevice(this, runtime, runtime::devicePanes, d::onApiHello).also {
            it.start()
            runtime.device = it
        }
        ImprovController.init(this) // Improv Wi-Fi via BLE (provisioning mode; see improv/)
        // The web installer, over adb (docs/onboarding.md): Wi-Fi via Improv Serial, then HA + radio.
        ImprovSerialServer.start(glassworks.frame, glassworks.build)
        SetupChannelServer.start(this, glassworks)
        startSetupHotspot()
        Log.i("S1Kiosk", "KioskService started")
    }

    /**
     * The setup hotspot (#197): the panel opens its own Wi-Fi when no saved Wi-Fi works (SetupHotspotPolicy). Here,
     * not in the activity: Android starts a local-only hotspot only for an app in the foreground, and this foreground
     * service counts, screen off included. Any network coming or going is a change for the rule.
     */
    private fun startSetupHotspot() {
        val schedule: (Long, () -> Unit) -> () -> Unit = { delay, run -> val r = Runnable(run); main.postDelayed(r, delay); { main.removeCallbacks(r) } }
        // On a frame whose root sends the hotspot's DNS and port 80 to the app, a phone opens the setup page by itself.
        val hotspot = Hotspot.of(this, main).let { h ->
            if (!glassworks.frame.hotspotPortal) h
            else PortalHotspot(h, CaptivePortal({ ap -> "http://$ap:${OAuth.SIGNIN_PORT}${LanSetup.PAGE}" }, log = { Log.i(HOTSPOT_TAG, it) }), schedule,
                addresses = { PortalAddresses.now(this) }, log = { Log.i(HOTSPOT_TAG, it) })
        }
        val hs = SetupHotspot(
            hotspot = hotspot,
            connected = { NetworkUp.now(this) },
            clock = SystemClock::elapsedRealtime,
            schedule = schedule,
            log = { Log.i(HOTSPOT_TAG, it) },
            blocked = { if (WifiRadio.lockedOff(this)) HotspotFailure.WIFI_OFF else null },
            // A fresh scan first (up to 15 s): the radio can't scan once it is the access point.
            beforeStart = { start ->
                // Where the app may turn the radio on (Android 9 and older, the device owner), it does: an off radio
                // couldn't scan nor join. Where it may not, the hotspot is blocked before it gets here.
                if (!WifiRadio.ensureOn(this)) Log.w(HOTSPOT_TAG, "Wi-Fi is off, and it didn't come on")
                scanFirst(start)
            },
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

    override fun onDestroy() {
        hotspotNetworks?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        hotspotNetworks = null
        glassworks.setupHotspot?.close() // stops it, and puts back what it changed
        glassworks.setupHotspot = null
        glassworks.voiceCancel = null
        SetupChannelServer.stop()
        ImprovSerialServer.stop()
        ImprovController.shutdown()
        runtime.device = null
        device?.stop()
        device = null
        discovery?.stop()
        discovery = null
        glassworks.discovery = null
        HaAuth.stopServer()
        glassworks.panelCode.display = {}
        glassworks.panelCode.canDisplay = { false }
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
            Log.i("S1Kiosk", "foreground service types $types (${KioskForeground.names(types)}; location ${if (types and KioskForeground.LOCATION != 0) "on" else "off"})")
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
                NotificationChannel(CHANNEL, "Kiosk", NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false) }
            )
        }
        // Tapping it opens the kiosk settings menu (a second way in besides the 5 taps; anyone at the panel
        // can pull the shade down). MainActivity is singleTask: the running one gets it in onNewIntent.
        val menu = PendingIntent.getActivity(
            this, REQ_KIOSK_SETTINGS,
            Intent(this, MainActivity::class.java)
                .putExtra(SettingsMenu.EXTRA, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        @Suppress("DEPRECATION")
        val b = if (Platform.hasNotificationChannels) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this)
        b.setSmallIcon(notificationIcon())
            .setContentTitle(glassworks.build.product)
            .setContentText("Tap for kiosk settings · screen, sensors & ESPHome API running")
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
        private const val HOTSPOT_TAG = "S1Kiosk.Hotspot"
        private const val CHANNEL = "kiosk"
        private const val NOTIFICATION_ID = 1
        private const val REQ_KIOSK_SETTINGS = 10  // distinct PendingIntent request codes
        private const val REQ_PANEL_SETTINGS = 11

        fun start(context: Context) {
            val i = Intent(context, KioskService::class.java)
            if (Platform.hasForegroundServiceStart) context.startForegroundService(i)
            else context.startService(i)
        }
    }
}
