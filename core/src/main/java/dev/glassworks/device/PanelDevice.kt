package dev.glassworks.device

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import dev.glassworks.dock.DockWatcher
import dev.glassworks.dock.Docked
import dev.glassworks.frame.Dock
import dev.glassworks.frame.Frame
import dev.glassworks.frame.friendlyName
import dev.glassworks.frame.nodeName
import dev.glassworks.pane.CantRun
import dev.glassworks.pane.EntityRegistry
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PaneSwitch
import dev.glassworks.pane.PanelEvent
import dev.glassworks.pane.forEachPane
import dev.glassworks.pane.stopAll
import dev.glassworks.screen.ScreenHardware
import java.io.IOException
import java.net.NetworkInterface
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import dev.glassworks.esphome.*
import dev.glassworks.esphome.DeviceIdentity
import dev.glassworks.esphome.android.EspHomeMdns
import dev.glassworks.Glassworks
import dev.glassworks.KioskPrefs
import dev.glassworks.auth.HaAuth
import dev.glassworks.auth.OAuth
import dev.glassworks.dashboard.Dashboard
import dev.glassworks.dashboard.DashboardOptions
import dev.glassworks.dashboard.Dashboards
import dev.glassworks.dashboard.StableChange
import dev.glassworks.setup.SetupSignals
import dev.glassworks.update.UpdateManager
import dev.glassworks.assist.ExoAudioPlayer
import dev.glassworks.assist.TtsPlayer

/**
 * The panel as one ESPHome device for Home Assistant: native API server (port 6053), mDNS,
 * entities bound to the kiosk settings / the screen policy, a media player, and the panes the app
 * builds for it ([buildPanes], from [DeviceHost]). Owned by KioskService.
 *
 * Entity values are read from [KioskPrefs] / [screen] (thread-safe); anything touching
 * the screen or the activity is posted to the main thread. Kiosk actions (URL, reload, zoom,
 * header, restart) go to the app's activity ([AppComponents.mainActivity]) as intents, exactly like the
 * `adb am start` extras.
 */
class PanelDevice(
    context: Context,
    private val runtime: Glassworks,
    /** The app's panes for this device, in order, built from what the device gives them (AppParts.devicePanes). */
    private val buildPanes: (DeviceHost) -> List<Pane>,
    /** HelloRequest from an API client (Home Assistant discovery, HaDiscovery.onApiHello). */
    private val onApiHello: (String, java.net.InetAddress?) -> Unit,
    /** esphome-main. A test passes one it runs by hand, to choose when queued work happens. */
    private val exec: ScheduledExecutorService,
    private val mdns: MdnsAdvertiser,
    /** The API server's port. A test passes 0 for any free one. */
    private val apiPort: Int,
    /** Where the API server listens: null for every address. A test passes the loopback address it connects to (#113). */
    private val apiBindAddress: java.net.InetAddress?,
) {

    constructor(
        context: Context,
        runtime: Glassworks,
        buildPanes: (DeviceHost) -> List<Pane>,
        onApiHello: (String, java.net.InetAddress?) -> Unit = { _, _ -> },
    ) : this(
        context, runtime, buildPanes, onApiHello,
        exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "esphome-main").apply { isDaemon = true } },
        mdns = EspHomeMdnsAdvertiser(EspHomeMdns(context.applicationContext, board = runtime.frame.board)),
        apiPort = ApiServer.DEFAULT_PORT,
        apiBindAddress = null,
    )

    companion object {
        /**
         * The MAC for a chip [serial]. The salt is the device's identity, not branding: a new one gives every panel a new
         * MAC and HA a new device, so it keeps the old app's name (#527).
         */
        internal fun macFromSerial(serial: String): ByteArray = localMac("s1kiosk-device-mac:$serial")

        /**
         * The device's MAC that Kiln writes into the image's build.prop on a frame it flashes (the Mirror), so a
         * reflash, which wipes the app's data, keeps the same device in Home Assistant (#523).
         */
        const val IMAGE_MAC_PROP = "ro.glassworks.device.mac"

        /**
         * The same identity as [IMAGE_MAC_PROP], from an installer that can't write build.prop: Kiln's in-place install
         * on a stock framework writes this global setting over adb, with the MAC its image install would write, so a
         * device keeps one Home Assistant device whichever way Kiln installs it.
         */
        const val INSTALLER_MAC_SETTING = "glassworks_device_mac"

        /**
         * The Home Assistant device identity, and where it came from. It must survive a reinstall or a reflash, or HA
         * sees a new device with new entity ids. The first that exists wins: the [saved] one; the one the image
         * carries ([image], [IMAGE_MAC_PROP]); the Wi-Fi MAC; one derived from the chip's [serial]; one derived from
         * [androidId], which Android keeps across a reinstall of the same signed app; else [random].
         */
        internal fun pickMac(saved: String, image: String, wlan0: ByteArray?, serial: String, androidId: String?,
                             random: () -> ByteArray): Pair<String, String> {
            if (MAC_RE.matches(saved)) return saved to "saved"
            if (MAC_RE.matches(image)) return image to "build.prop"
            val (bytes, source) = when {
                wlan0 != null && wlan0.size == 6 && wlan0.any { it != 0.toByte() } &&
                    !(wlan0[0] == 2.toByte() && wlan0.drop(1).all { it == 0.toByte() }) -> wlan0 to "wlan0"
                serial.length >= 8 && serial != "unknown" -> macFromSerial(serial) to "serial"
                !androidId.isNullOrEmpty() -> localMac("glassworks-device-mac:android_id:$androidId") to "android_id"
                else -> random().copyOf(6).also { it[0] = ((it[0].toInt() and 0xFC) or 0x02).toByte() } to "random"
            }
            return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xFF) } to source
        }

        /**
         * The line [stableMac] logs on every start: the identity and where it came from. Kept data says whether
         * build.prop carries the same one, so a reflash can be checked from any start's log, not only the first (#574).
         */
        internal fun macLine(mac: String, source: String, image: String): String = "Device MAC $mac (" + when {
            source != "saved" || !MAC_RE.matches(image) -> source
            image == mac -> "saved, the same as build.prop"
            else -> "saved; build.prop has another, the saved one wins"
        } + ")"

        /**
         * Set to `1` in build.prop by an installer that carries the API key over a reflash ([SecretHandover], #574):
         * the fresh app then keeps its API server shut until the key comes, so Home Assistant, which still holds that
         * key, never meets a device without one and opens no re-authentication.
         */
        const val API_KEY_HANDOVER_PROP = "ro.glassworks.api_key.handover"

        /** How long the API server waits for a carried key before it starts without one (provisionable, as before). */
        const val API_KEY_WAIT_MS = 15 * 60_000L

        /** Whether the API server still waits for the key [API_KEY_HANDOVER_PROP] promises, [waitedMs] after it began. */
        internal fun waitsForKey(handover: String, hasKey: Boolean, waitedMs: Long): Boolean =
            handover == "1" && !hasKey && waitedMs < API_KEY_WAIT_MS

        private fun localMac(seed: String): ByteArray = java.security.MessageDigest.getInstance("SHA-256")
            .digest(seed.toByteArray()).copyOf(6).also {
                it[0] = ((it[0].toInt() and 0xFC) or 0x02).toByte() // unicast, locally administered
            }

        /** The library's logs go under the tag the docs use (`adb logcat -s Glassworks.ESPHome`). */
        const val ESPHOME_LOG_TAG = "Glassworks.ESPHome"

        init { EspHomeLog.tag = ESPHOME_LOG_TAG }

        /** Reported ESPHome version (recent, so HA doesn't treat the device as outdated). */
        const val ESPHOME_VERSION = "2026.10.2"
        /** Same flags as an ESPHome voice_assistant with a media player (Voice PE): no SPEAKER. */
        const val VOICE_FEATURES = VoiceFeature.VOICE_ASSISTANT or VoiceFeature.API_AUDIO or
            VoiceFeature.TIMERS or VoiceFeature.ANNOUNCE or VoiceFeature.START_CONVERSATION
        private val MAC_RE = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
    }

    private val ctx = context.applicationContext
    private val prefs = KioskPrefs(ctx)
    private val main = Handler(Looper.getMainLooper())
    /** Typed as the interface. The device-only parts come in through [Frame.panes]. */
    private val frame: Frame = runtime.frame
    private val screen = runtime.screen
    /** Each pane's on/off, by pane id ([dev.glassworks.pane.PaneDefinition.switch]). A pane with none always runs. */
    private val switches: Map<String, PaneSwitch> =
        runtime.paneDefinitions(ctx).mapNotNull { d -> d.switch?.let { d.id to it } }.toMap()
    /** The ids of the panes started now. esphome-main. */
    private val started = HashSet<String>()

    private var server: ApiServer? = null
    /** Guards [stopped] and the hand-over of [server] between [startServer] and [stop]. */
    private val lifecycle = Any()
    private var stopped = false
    /** The server's keys: read again when the server starts, and followed when the stored key changes (#608). */
    private val serverKeys = ServerKeys(prefs.noiseKeyStore)
    /** Read by the API server on its connection threads; replaced when voice is turned on or off. */
    @Volatile private lateinit var identity: DeviceIdentity

    /** The node name it announces, or null before it has started. The Manage page allows it as a Host header (`<name>.local`). */
    val nodeName: String? get() = if (::identity.isInitialized) identity.name else null
    private lateinit var entities: List<ApiEntity>
    private lateinit var coreEntities: List<ApiEntity>
    private val voicePlayer = ExoAudioPlayer(context, "voice", AudioAttributes.USAGE_ASSISTANT)
    private val mediaPlayer = ExoAudioPlayer(context, "media", AudioAttributes.USAGE_MEDIA)
    private var chimePlayer: TtsPlayer? = null
    /** The master volume: Home Assistant's *Speaker* volume and mute set it, whichever panes are on. */
    private val volume = runtime.volume(ctx)
    /** The features that add entities and react to the panel (Sendspin, voice, timers, ...); built in [initEntities]. */
    @Volatile private var panes: List<Pane> = emptyList()
    /**
     * App updates from GitHub Releases (docs/releasing.md); the "App update" entity. runCatching:
     * the update thread may still report while [stop] has already shut [exec] down.
     */
    private val updates = UpdateManager(ctx, runtime, channel = { prefs.updateChannel }, onChange = { runCatching { exec.execute { server?.publishStates() } } })
    @Volatile private var announcing = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        exec.execute { onPrefChanged(key) }
    }
    private val screenListener: () -> Unit = { exec.execute { server?.publishStates() } }
    /** The master volume changed (here, on the Sound page, or by the volume keys): Home Assistant hears it. runCatching: after [stop]. */
    private val volumeListener: () -> Unit = { runCatching { exec.execute { applyVolume(); server?.publishStates() } } }
    private val authListener: (Boolean) -> Unit = { exec.execute { server?.publishStates() } }
    private val dashboardsListener: (List<Dashboard>) -> Unit = { exec.execute { checkDashboards() } }

    /** Whether the device sits on a dock; none on a frame that never docks ([Dock.NO]). Started and stopped on the main thread. */
    private val dockWatcher: DockWatcher? =
        if (frame.dock == Dock.NO) null else DockWatcher(ctx) { docked -> runCatching { exec.execute { onDock(docked) } } }
    /**
     * The *Docked* sensor, in the list while [Docked.hasEntity] says so. Its state is missing (Home Assistant: unknown)
     * until the watcher's first read on the main thread, which can come after Home Assistant subscribes.
     */
    private val dockedEntity = BinarySensorEntity("docked", "Docked", deviceClass = "plug", get = { dockWatcher?.docked })

    // "Dashboard" select (docs/onboarding.md step 5): built from [appliedDashboards]; a changed
    // option list rebuilds the entity set (debounced, like the wall base) so HA re-lists it.
    @Volatile private var appliedDashboards: List<Dashboard> = emptyList()
    private lateinit var dashboardRebuild: StableChange<List<String>>

    fun start() {
        exec.execute { startInternal() }
    }

    fun stop() {
        synchronized(lifecycle) { stopped = true }
        prefs.unregisterListener(prefListener)
        // A newer device may have set its own already.
        if (volume.onChange === volumeListener) volume.onChange = null
        screen.removeListener(screenListener)
        HaAuth.removeListener(authListener)
        Dashboards.removeListener(dashboardsListener)
        // Panes first, while esphome-main still runs: the wall base's thread can still post to it.
        val gone = panes
        val interrupted = gone.stopAll()
        panes = emptyList()
        // A newer device may have published its own already.
        if (runtime.panes === gone) runtime.panes = emptyList()
        // Each hook on its own: one that throws must not skip the others or the cleanup below.
        synchronized(stopHooks) { stopHooks.toList().also { stopHooks.clear() } }.forEach { hook ->
            try { hook() } catch (e: Exception) { EspHomeLog.w("A pane's stop hook failed: $e") }
        }
        host?.let { it.cancelVoice = null; it.onVoicePlaying = null }
        dockWatcher?.let { w -> if (Looper.myLooper() == Looper.getMainLooper()) w.stop() else main.post { w.stop() } }
        exec.shutdownNow()
        mdns.stop()
        synchronized(lifecycle) { server.also { server = null } }?.stop()
        updates.stop()
        voicePlayer.release()
        mediaPlayer.release()
        chimePlayer?.release()
        releaseLocks()
        // Passed on only now, so no step of the shutdown above is cut short by it.
        if (interrupted) Thread.currentThread().interrupt()
    }

    // ------------------------------------------------------------------ setup

    /**
     * Runs [block] unless [stop] has begun, and returns whether it ran. [stop] takes the same lock to
     * set [stopped], so it waits for a step that is running and then stops what that step started.
     */
    private fun unlessStopped(block: () -> Unit): Boolean = synchronized(lifecycle) {
        if (stopped) false else { block(); true }
    }

    /** esphome-main. Each step checks [stopped], so a [stop] during startup leaves nothing running. Public for tests. */
    fun startInternal() {
        if (synchronized(lifecycle) { stopped }) return
        val mac = stableMac()
        val name = frame.nodeName(mac) ?: run { EspHomeLog.w("No ESPHome node name for MAC '$mac': the device isn't announced"); return }
        // HA keys its entry and entity unique ids by the MAC, so a new node name keeps both (docs/esphome.md#names).
        val id = DeviceIdentity(
            name = name,
            friendlyName = frame.friendlyName(),
            macAddress = mac,
            esphomeVersion = ESPHOME_VERSION,
            compilationTime = "${runtime.build.product} ${runtime.build.versionName}",
            manufacturer = frame.hardware.manufacturer,
            model = "${frame.hardware.model} (${runtime.build.product})",
            voiceFeatures = voiceFeatures(),
            // Home Assistant's "Visit" link on the device page opens the Manage page (#33).
            webserverPort = OAuth.SIGNIN_PORT,
        )
        EspHomeLog.i("ESPHome device '${id.name}' mac ${id.macAddress}, voice=${id.voiceFeatures != 0}")
        if (!unlessStopped { initEntities(id); acquireLocks() }) return
        applyVolume()
        if (!unlessStopped { volume.onChange = volumeListener }) return
        mediaPlayer.onStateChanged = { exec.execute { server?.publishStates() } }
        voicePlayer.onStateChanged = {
            host?.onVoicePlaying?.invoke(voicePlayer.isPlaying)
            exec.execute { server?.publishStates() }
        }

        for ((pane, s) in switches) EspHomeLog.i("Pane $pane ${if (s.on) "on" else "off"}")
        panes.forEachPane("start") { pane -> unlessStopped { if (isOn(pane)) { pane.start(); started += pane.id } } }
        if (!unlessStopped { updates.start() }) return
        startServerWhenKeyed(SystemClock.elapsedRealtime())
        unlessStopped {
            prefs.registerListener(prefListener)
            // A key stored after the server started and before the listener above: no event came for it.
            exec.execute { onApiKeyPrefChanged() }
            screen.addListener(screenListener)
            HaAuth.addListener(authListener)
            Dashboards.addListener(dashboardsListener)
            exec.scheduleWithFixedDelay({ checkDashboards() }, 2, 2, TimeUnit.SECONDS)
            exec.scheduleWithFixedDelay({ server?.publishStates() }, 5, 5, TimeUnit.SECONDS)
            dockWatcher?.let { w -> ui { if (unlessStopped {}) w.start() } }
        }
        // A switch changed after its pane started and before the listener above: no event came for it. And
        // each pane that is off hears its own switch once, so it puts back what it undoes outside the app
        // (Aqara's daemon, the time zone the root helper keeps) after a restart too.
        val off = panes.filter { !isOn(it) }.mapNotNull { switches[it.id]?.key }
        for (key in (applySwitches() + off).distinct()) emit(PanelEvent.SettingChanged(key))
    }

    /**
     * The entity list Home Assistant gets at start, built without starting anything (the entity pin
     * test calls it too). esphome-main. Public for tests.
     */
    fun initEntities(identity: DeviceIdentity) {
        this.identity = identity
        val paneContext = PaneContext(
            ctx, { namespace -> runtime.paneFile(ctx, namespace) }, identity, runtime.build,
            publishStates = { runCatching { exec.execute { server?.publishStates() } } },
            emit = ::emit,
            entitiesChanged = { runCatching { exec.execute { rebuildEntities() } } },
            sendEvent = { entity, type -> runCatching { exec.execute { server?.sendEvent(entity, type) } } },
            fireEvent = { event -> runCatching { exec.execute { server?.sendHomeAssistantEvent(event) } } },
        )
        // A pane the frame lacks the hardware for (CantRun) isn't built: no entities, nothing started.
        val cantRun = runtime.paneDefinitions(ctx).filterIsInstance<CantRun>().map { it.id }.toSet()
        val host = Host(paneContext, cantRun).also { host = it }
        panes = buildPanes(host).filter { it.id !in cantRun }
        runtime.panes = panes
        // The screen's entities the device can back (#476), from the facts its settings and the Manage page show;
        // every pane's on/off, also while the pane is off. Android gives the app its sensor list once, so this holds
        // until the app starts again.
        val hardware = ScreenHardware.of(frame, runtime.paneDefinitions(ctx), ctx)
        coreEntities = buildEntities().filter { hardware.backs(it.objectId) } + switches.values.map { it.entity() }
        appliedDashboards = DashboardOptions.parse(prefs.dashboardsJson).orEmpty()
        dashboardRebuild = StableChange(dashboardOptions(appliedDashboards))
        entities = assemble()
    }

    /** The entities Home Assistant gets now. Public for tests. */
    fun entityList(): List<ApiEntity> = entities

    /** The panes this device built. Public for tests. */
    fun paneList(): List<Pane> = panes

    // ------------------------------------------------------------------ dashboard select

    private fun assemble(): List<ApiEntity> =
        EntityRegistry(panes).assemble(coreEntities + listOfNotNull(dockedEntity.takeIf { hasDockedEntity() }) + dashboardSelect(dashboardRebuild.applied()))

    private fun hasDockedEntity() = Docked.hasEntity(frame.dock, prefs.dockSeen)

    /** On its dock now, or null where Home Assistant has no Docked sensor ([Docked.hasEntity]) or before the first read. Any thread. */
    fun docked(): Boolean? = if (hasDockedEntity()) dockWatcher?.docked else null

    /**
     * esphome-main, from [dockWatcher]. The first dock a device reports is kept ([KioskPrefs.dockSeen]); on a device
     * that might dock ([Dock.IF_SEEN]) that adds the sensor, and Home Assistant lists the entities again.
     */
    private fun onDock(docked: Boolean) {
        if (docked && !prefs.dockSeen) {
            val had = hasDockedEntity()
            prefs.dockSeen = true
            if (!had && hasDockedEntity()) {
                EspHomeLog.i("First dock seen: adding the Docked sensor")
                rebuildEntities()
                return
            }
        }
        server?.publishStates()
    }

    private fun currentDashboardOption(list: List<Dashboard>) =
        DashboardOptions.currentOption(prefs.urlSetting, prefs.settingBase, list)

    private fun dashboardOptions(list: List<Dashboard>) = DashboardOptions.options(list, currentDashboardOption(list))

    /** Fixed object_id (so the key never changes); only the options change between rebuilds. */
    private fun dashboardSelect(options: List<String>) = SelectEntity(
        "dashboard", "Dashboard", "mdi:view-dashboard", disabledByDefault = true, options = options,
        get = { currentDashboardOption(appliedDashboards) },
        set = { option ->
            val url = DashboardOptions.urlFor(option, prefs.urlSetting, prefs.settingBase, prefs.haUrl, appliedDashboards)
            if (url != null) {
                EspHomeLog.i("Dashboard select: '$option'")
                toActivity { putExtra("url", url) } // saves the setting and reloads
            }
        },
    )

    /**
     * esphome-main, every 2 s and on new lists: rebuild the entity set when the option list really
     * changed and has been stable for 10 s (at most once a minute); a list change that keeps the
     * same options only updates the option → url_path mapping.
     */
    private fun checkDashboards() {
        val list = Dashboards.latest ?: appliedDashboards
        val wanted = dashboardOptions(list)
        val now = android.os.SystemClock.elapsedRealtime()
        if (wanted == dashboardRebuild.applied()) {
            if (list != appliedDashboards) adoptDashboards(list)
            dashboardRebuild.offer(wanted, now)
            return
        }
        val due = dashboardRebuild.offer(wanted, now) ?: return
        adoptDashboards(list)
        dashboardRebuild.markApplied(due, now)
        EspHomeLog.i("Dashboard options changed (${due.size}); rebuilding the entity list")
        rebuildEntities()
    }

    /** esphome-main: the entity list from the core and every pane again, given to Home Assistant. */
    private fun rebuildEntities() {
        entities = assemble()
        server?.setEntities(entities)
    }

    private fun adoptDashboards(list: List<Dashboard>) {
        appliedDashboards = list
        prefs.dashboardsJson = DashboardOptions.toJson(list)
    }

    /**
     * Starts the API server, unless the image says the installer hands over the key and it hasn't come yet
     * ([waitsForKey]): then nothing listens or announces, and Home Assistant only retries, until the key comes or
     * [API_KEY_WAIT_MS] has passed. esphome-main.
     */
    private fun startServerWhenKeyed(since: Long) {
        if (synchronized(lifecycle) { stopped }) return
        val handover = systemProperty(API_KEY_HANDOVER_PROP)
        val waited = SystemClock.elapsedRealtime() - since
        if (waitsForKey(handover, prefs.apiEncryption, waited)) {
            if (waited == 0L) EspHomeLog.i("API server waits for the key the installer hands over (up to ${API_KEY_WAIT_MS / 60_000} min)")
            exec.schedule({ startServerWhenKeyed(since) }, 1, TimeUnit.SECONDS)
            return
        }
        if (handover == "1") {
            // startServer reads the store again, so a key handed over since this object was made is the one it uses.
            EspHomeLog.i(if (prefs.apiEncryption) "API key handed over: starting the API server with it"
                else "No API key handed over in ${API_KEY_WAIT_MS / 60_000} min: starting without one, Home Assistant may ask to re-authenticate")
        }
        startServer()
    }

    private fun startServer() {
        // The store as it is now, not as it was when this object was made: a switch's hand-over may have come since.
        val s = ApiServer(apiPort, { identity }, entities, serverKeys.forStart(), apiBindAddress)
        panes.forEachPane("bind") { it.bind(s) }
        s.onCommand = { exec.execute { s.publishStates() } }
        s.onClientHello = { info, addr ->
            if (ApiServer.isHomeAssistant(info)) SetupSignals.onHaHello()
            onApiHello(info, addr)
        }
        // HA subscribes only after it has added the panel: remember that (setup screen, step 2).
        s.onStatesSubscribed = { info, addr, authenticated ->
            if (ApiServer.isHomeAssistant(info)) {
                if (!prefs.haSeen) { prefs.haSeen = true; EspHomeLog.i("Home Assistant has added the panel") }
                SetupSignals.onHaSubscribed()
                emit(PanelEvent.HomeAssistantSubscribed(addr, authenticated))
            }
        }
        s.onTimeZone = { zone -> emit(PanelEvent.HomeAssistantTimeZone(zone)) }
        // A provisioned (or cleared) key changes the mDNS TXT records; re-register after the
        // unregistration has gone out so the name doesn't collide with our own old record.
        s.onKeyActivated = { exec.execute { readvertise() } }
        try {
            s.start()
        } catch (e: IOException) {
            EspHomeLog.w("Cannot listen on $apiPort: ${e.message}; retrying in 10 s")
            exec.schedule({ if (server == null) startServer() }, 10, TimeUnit.SECONDS)
            return
        }
        // stop() may have run while this start was in flight: then the server must not outlive it.
        val kept = unlessStopped {
            server = s
            mdns.start(identity, s.localPort, encrypted = s.keyed, provisionable = s.provisionable)
        }
        if (!kept) s.stop()
    }

    private fun readvertise() {
        val s = server ?: return
        mdns.stop()
        exec.schedule({
            // Like the first registration in startServer(): never once stop() has begun.
            unlessStopped { if (server === s) mdns.start(identity, s.localPort, encrypted = s.keyed, provisionable = s.provisionable) }
        }, 1500, TimeUnit.MILLISECONDS)
    }

    /** Key changed in the panel settings (adb extras): apply it unless it is the one HA just provisioned. */
    private fun onApiKeyPrefChanged() {
        val k = prefs.effectiveApiKey()
        if (!serverKeys.follow(k, server)) return
        EspHomeLog.i(if (k == null) "API key removed in the panel settings" else "API key set in the panel settings (fingerprint ${NoiseKeys.fingerprint(k)})")
    }

    private fun emit(event: PanelEvent) = panes.forEachPane("onEvent") { it.onEvent(event) }

    /** esphome-main thread. */
    private fun onPrefChanged(key: String?) {
        if (key == "api_encryption" || key == "api_key") onApiKeyPrefChanged()
        // Before the event, so a pane that hears its own switch is already started or stopped.
        if (switches.values.any { it.key == key }) applySwitches()
        emit(PanelEvent.SettingChanged(key))
        server?.publishStates()
    }

    private fun isOn(pane: Pane) = switches[pane.id]?.on != false

    /** The voice assistant's features for Home Assistant: none while its pane ([PaneSwitch.voiceAssistant]) is off, or has no switch because it can't run here. */
    private fun voiceFeatures() = if (switches.values.firstOrNull { it.voiceAssistant }?.on == true) VOICE_FEATURES else 0

    /**
     * esphome-main: starts each pane that was turned on and stops each that was turned off (#67), and
     * returns their switches' keys. When voice changed, the device info changes too, and Home Assistant
     * reconnects to read it.
     */
    private fun applySwitches(): List<String> {
        val changed = ArrayList<String>()
        for (pane in panes) {
            val on = isOn(pane)
            if (on == (pane.id in started)) continue
            if (on) {
                // Recorded only once start() returned: a pane that failed to start is tried again on the next change.
                if (!unlessStopped { listOf(pane).forEachPane("start") { it.start(); started += pane.id } }) return changed
            } else {
                started -= pane.id
                listOf(pane).forEachPane("stop") { it.stop() }
            }
            switches[pane.id]?.let { changed += it.key }
            EspHomeLog.i("Pane ${pane.id} ${if (on) "on" else "off"}")
        }
        val features = voiceFeatures()
        if (features != identity.voiceFeatures) {
            identity = identity.copy(voiceFeatures = features)
            runtime.voiceState = if (features != 0) "idle" else "off"
            EspHomeLog.i("voice=${features != 0}: Home Assistant reconnects to read the voice features")
            rebuildEntities()
        }
        return changed
    }

    /** The chime player, made on first use (from the ESPHome, voice or main thread). */
    private fun chimes(): TtsPlayer = synchronized(this) { chimePlayer ?: TtsPlayer { volume.gain }.also { chimePlayer = it } }

    /** Home Assistant is connected and listening to the panel's states now. */
    fun homeAssistantConnected(): Boolean = server?.hasSubscriber == true

    /** The user cancelled the current voice run (tap on the overlay). */
    fun cancelVoice() {
        exec.execute { host?.cancelVoice?.invoke() }
    }

    /** Hooks the app's panes left for [stop] ([DeviceHost.onStop]). */
    private val stopHooks = ArrayList<() -> Unit>()

    /** What the app's panes get from this device; null until [initEntities] makes it with the pane context. */
    @Volatile private var host: Host? = null

    private inner class Host(override val paneContext: PaneContext, override val cantRun: Set<String>) : DeviceHost {
        override val exec get() = this@PanelDevice.exec
        override val voicePlayer get() = this@PanelDevice.voicePlayer
        override val mediaPlayer get() = this@PanelDevice.mediaPlayer
        override val announcing get() = this@PanelDevice.announcing
        override fun chimes() = this@PanelDevice.chimes()
        override fun silenceChimes() { synchronized(this@PanelDevice) { chimePlayer }?.cancel() }
        override fun resolveUrl(url: String) = this@PanelDevice.resolveUrl(url)
        override fun ipAddress() = this@PanelDevice.ipAddress()
        override fun onStop(block: () -> Unit) { synchronized(stopHooks) { stopHooks += block } }
        @Volatile override var cancelVoice: (() -> Unit)? = null
        @Volatile override var onVoicePlaying: ((Boolean) -> Unit)? = null
    }

    // ------------------------------------------------------------------ entities

    private fun ui(block: () -> Unit) { main.post(block) }

    /** Sends extras to the app's activity (same as `adb shell am start ... --e* key value`). */
    private fun toActivity(build: Intent.() -> Unit) {
        val i = Intent(ctx, runtime.components.mainActivity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply(build)
        ui {
            val sink = runtime.commandSink
            if (sink != null) sink(i) else try { ctx.startActivity(i) } catch (e: Exception) {
                EspHomeLog.w("Cannot start ${runtime.components.mainActivity.simpleName}", e)
            }
        }
    }

    /** Before the screen starts, the manual value it will apply; never while auto brightness is on (#129). */
    private fun currentBrightness(): Float =
        screen.brightness.takeIf { it >= 0f }
            ?: prefs.manualBrightness.takeIf { !prefs.autoBrightness && it > 0f }
            ?: 1f

    /**
     * Whether the device has what the core entity [objectId] reads or sets (#476). A frame without it never lists
     * it, so Home Assistant removes one it had before on the next reconnect, and gives it back its old id if it
     * returns. Presence from the camera pane is the pane's own `camera_presence`, listed while the pane can run,
     * on or off, as every pane's entities are.
     */
    private fun ScreenHardware.backs(objectId: String): Boolean = when (objectId) {
        "presence" -> presence is ScreenHardware.Presence.Sensor
        "presence_range" -> (presence as? ScreenHardware.Presence.Sensor)?.ranged == true
        "auto_brightness", "illuminance", "max_brightness" -> light
        else -> true
    }

    private fun buildEntities(): List<ApiEntity> = listOf(
        // --- screen & sensors
        // CONFIG (#591): Home Assistant then leaves the screen out of a room's "lights off" and out of Assist.
        BrightnessLightEntity(
            "screen", "Screen", "mdi:tablet-dashboard", EntityCategory.CONFIG,
            get = { screen.screenOn to currentBrightness() },
            set = { on, b ->
                ui {
                    if (b != null) screen.setManualBrightness(b)
                    when (on) {
                        true -> screen.wake("home assistant")
                        false -> screen.sleep("home assistant")
                        null -> Unit
                    }
                }
            },
        ),
        SwitchEntity(
            "auto_brightness", "Auto brightness", "mdi:brightness-auto", EntityCategory.CONFIG,
            disabledByDefault = true, get = { prefs.autoBrightness },
            set = { prefs.autoBrightness = it; ui { screen.refreshBrightness() } },
        ),
        BinarySensorEntity("presence", "Presence", deviceClass = "occupancy", get = { screen.near }),
        SensorEntity(
            "illuminance", "Illuminance", unit = "lx", accuracy = 0, deviceClass = "illuminance",
            get = { screen.lux },
        ),
        SelectEntity(
            "screen_mode", "Screen mode", "mdi:monitor-eye", options = listOf("presence", "always"),
            get = { prefs.screenMode }, set = { prefs.screenMode = it },
        ),
        // On by default, with Max auto brightness: Home Assistant's night mode (the Panel night mode blueprint, #473) sets both.
        // A timeout above 0 puts the screen mode back to presence, as the panel's own "Turn off when idle" row does:
        // in "always" nothing would turn the screen off.
        NumberEntity(
            "idle_timeout", "Idle timeout", "mdi:timer-outline", min = 0f, max = 3600f, step = 5f,
            unit = "s", mode = 1, deviceClass = "duration",
            get = { prefs.idleOffSec.toFloat() },
            set = { prefs.idleOffSec = it.toInt(); if (prefs.idleOffSec > 0) prefs.screenMode = "presence" },
        ),
        NumberEntity(
            "max_brightness", "Max auto brightness", "mdi:brightness-percent", min = 5f, max = 100f, step = 5f,
            unit = "%", mode = 1,
            get = { Math.round(prefs.maxBrightness * 100f).toFloat() },
            set = { prefs.maxBrightness = (it / 100f).coerceIn(0.05f, 1f); ui { screen.refreshBrightness(immediate = true) } },
        ),
        NumberEntity(
            "presence_range", "Presence range", "mdi:signal-distance-variant", disabledByDefault = true, min = 0f, max = 2.5f, step = 0.1f,
            unit = "m", mode = 2, deviceClass = "distance",
            get = { prefs.presenceRangeM }, set = { prefs.presenceRangeM = it; ui { screen.applyPresenceRange() } },
        ),
        // --- kiosk
        // authOnly (#847) here and on open_settings, improv_ble and sign_out: on a panel with no ESPHome key yet anyone
        // on the LAN can talk plaintext to the API. The dashboard's host becomes a secure context that gets the
        // microphone and the camera, so only Home Assistant with the key may set it. The panel and Manage still can.
        TextEntity(
            "dashboard_url", "Dashboard URL", "mdi:web", disabledByDefault = true, minLength = 1, maxLength = 255,
            get = { prefs.urlSetting }, set = { url -> toActivity { putExtra("url", url) } },
        ).apply { authOnly = true },
        NumberEntity(
            "zoom", "Zoom", "mdi:magnify-plus-outline", disabledByDefault = true, min = 0f, max = 3f, step = 0.05f, mode = 1,
            get = { prefs.zoom },
            set = { z -> if (kotlin.math.abs(z - prefs.zoom) > 0.001f) toActivity { putExtra("zoom", z) } },
        ),
        ButtonEntity("reload_dashboard", "Reload dashboard", "mdi:refresh", press = { toActivity { putExtra("reload", true) } }),
        ButtonEntity("open_settings", "Open device settings", "mdi:cog", EntityCategory.CONFIG,
            disabledByDefault = true, press = { toActivity { putExtra("open_settings", true) } }).apply { authOnly = true },
        ButtonEntity(
            "restart_app", "Restart app", "mdi:restart", EntityCategory.CONFIG, deviceClass = "restart",
            press = { toActivity { putExtra("restart", true) } },
        ),
        ButtonEntity(
            "improv_ble", "Start Wi-Fi setup (Bluetooth)", "mdi:bluetooth-settings", EntityCategory.CONFIG,
            disabledByDefault = true, press = { ui { dev.glassworks.provisioning.ImprovController.startOnDemand("Home Assistant button") } },
        ).apply { authOnly = true },
        // Pairing over the LAN closes once Home Assistant has added the panel; this opens it for a while (#34).
        ButtonEntity(
            "lan_setup", "Allow setup over the network", "mdi:lan-connect", EntityCategory.CONFIG,
            press = { prefs.armLanSetup(); EspHomeLog.i("Pairing over the network re-armed from Home Assistant") },
        ),
        SwitchEntity(
            "hide_ha_header", "Hide HA header", "mdi:page-layout-header", EntityCategory.CONFIG,
            disabledByDefault = true, get = { prefs.hideHeader },
            set = { v -> toActivity { putExtra("hide_header", v); putExtra("hide_sidebar", v) } },
        ),
        // --- audio
        MediaPlayerEntity(
            "speaker", "Speaker", "mdi:speaker",
            // mp3 for everything: HA's ffmpeg proxy converts media/announcements, and the assist
            // satellite asks the TTS engine for this format (assist_satellite.py _update_tts_format).
            formats = listOf("mp3" to 0, "mp3" to 1),
            featureFlags = MediaPlayerEntity.FEATURE_PAUSE or MediaPlayerEntity.FEATURE_VOLUME_SET or
                MediaPlayerEntity.FEATURE_VOLUME_MUTE or MediaPlayerEntity.FEATURE_PLAY_MEDIA or
                MediaPlayerEntity.FEATURE_VOLUME_STEP or MediaPlayerEntity.FEATURE_STOP or
                MediaPlayerEntity.FEATURE_PLAY or MediaPlayerEntity.FEATURE_BROWSE_MEDIA or
                MediaPlayerEntity.FEATURE_MEDIA_ANNOUNCE,
            get = {
                MediaPlayerStateData(
                    state = when {
                        mediaPlayer.isPlaying || (announcing && voicePlayer.isPlaying) -> MediaState.PLAYING
                        mediaPlayer.isPaused -> MediaState.PAUSED
                        else -> MediaState.IDLE
                    },
                    volume = volume.level,
                    muted = volume.muted,
                )
            },
            handle = ::onMediaCommand,
        ),
        // --- diagnostics
        TextSensorEntity("ip_address", "IP address", "mdi:ip-network", EntityCategory.DIAGNOSTIC, get = ::ipAddress),
        TextSensorEntity(
            "app_version", "App version", "mdi:new-box", EntityCategory.DIAGNOSTIC,
            disabledByDefault = true, get = { runtime.build.versionName },
        ),
        // --- app updates (GitHub Releases; update/UpdateManager.kt)
        UpdateEntity(
            "app_update", "App update", "mdi:cellphone-arrow-down",
            get = { updates.model.view() }, onCommand = updates::onCommand,
        ),
        // The frame's own parts (#139): only on a frame with releases of its own, the S1 and the Mirror.
        *listOfNotNull(updates.frame?.let { f ->
            UpdateEntity("frame_update", "Frame update", "mdi:package-up", get = f::view, onCommand = updates::onFrameCommand)
        }).toTypedArray(),
        TextSensorEntity(
            "update_status", "Update status", "mdi:update", EntityCategory.DIAGNOSTIC,
            disabledByDefault = true, get = { updates.statusText() },
        ),
        // On: it's what people check to know whether an update needs someone at the panel (#422).
        TextSensorEntity(
            "silent_updates", "Silent updates", "mdi:auto-download", EntityCategory.DIAGNOSTIC,
            get = { updates.silentUpdates?.state.orEmpty() },
        ),
        // --- Home Assistant sign-in (docs/onboarding.md)
        TextSensorEntity(
            "home_assistant_url", "Home Assistant URL", "mdi:home-assistant", EntityCategory.DIAGNOSTIC,
            disabledByDefault = true, get = { prefs.haBase ?: "" },
        ),
        TextSensorEntity(
            "signed_in", "Signed in", "mdi:account-key", EntityCategory.DIAGNOSTIC,
            disabledByDefault = true, get = { if (HaAuth.signedIn) "yes" else "no" },
        ),
        ButtonEntity(
            "sign_out", "Sign out", "mdi:logout", EntityCategory.CONFIG,
            disabledByDefault = true, press = {
                // The activity shows the sign-in screen through its HaAuth listener.
                HaAuth.signOut("Home Assistant button") { exec.execute { server?.publishStates() } }
            },
        ).apply { authOnly = true },
    )

    private fun onMediaCommand(c: MediaPlayerCommandData) {
        c.volume?.let { volume.set(it) }
        c.mediaUrl?.takeIf { it.isNotEmpty() }?.let { raw ->
            val url = resolveUrl(raw)
            when {
                c.announcement -> announce(url)
                c.command == MediaCommand.ENQUEUE -> mediaPlayer.enqueue(url)
                else -> mediaPlayer.play(listOf(url)) { exec.execute { server?.publishStates() } }
            }
            return
        }
        when (c.command) {
            MediaCommand.PLAY -> mediaPlayer.resume()
            MediaCommand.PAUSE -> mediaPlayer.pause()
            MediaCommand.STOP, MediaCommand.CLEAR_PLAYLIST -> mediaPlayer.stop()
            MediaCommand.TOGGLE -> if (mediaPlayer.isPlaying) mediaPlayer.pause() else mediaPlayer.resume()
            MediaCommand.MUTE -> volume.mute(true)
            MediaCommand.UNMUTE -> volume.mute(false)
            MediaCommand.VOLUME_UP -> volume.step(up = true)
            MediaCommand.VOLUME_DOWN -> volume.step(up = false)
        }
    }

    /** media_player announce: pause music, play on the voice channel, resume. */
    private fun announce(url: String) {
        val resume = mediaPlayer.isPlaying
        if (resume) mediaPlayer.pause()
        announcing = true
        emit(PanelEvent.SpeakerClaimed(true))
        voicePlayer.play(listOf(url)) {
            announcing = false
            emit(PanelEvent.SpeakerClaimed(false))
            if (resume) mediaPlayer.resume()
            exec.execute { server?.publishStates() }
        }
    }

    /**
     * Android's media volume is the master ([volume]): the players play at full under it. Only where the device keeps
     * its volume fixed do they scale themselves ([dev.glassworks.audio.MasterVolume.gain]): the voice and media
     * players, the chimes and notification sounds, and the panes' own players ([PanelEvent.VolumeGain]). The
     * dashboard's audio can't be reached there.
     */
    private fun applyVolume() {
        val g = volume.gain
        voicePlayer.setVolume(g)
        mediaPlayer.setVolume(g)
        synchronized(this) { chimePlayer }?.setVolume(g)
        emit(PanelEvent.VolumeGain(g))
    }

    /**
     * Home Assistant normally sends absolute URLs (its internal URL); relative ones (`/api/...`) are
     * resolved against the dashboard URL, which points at Home Assistant.
     */
    private fun resolveUrl(url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        val base = prefs.url.ifEmpty { prefs.haBase?.let { "$it/" } ?: return url }
        return try { URI(base).resolve(url).toString() } catch (e: Exception) { url }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The Home Assistant device identity (ESPHome "MAC"), from [pickMac]. Kept in prefs once chosen, so it never
     * changes while the app's data lives. Logged on every start with its source ([macLine]): before #574 only the start
     * that chose it logged it, so a log that began after that start had no `Device MAC` line.
     */
    @SuppressLint("HardwareIds") // the Wi-Fi MAC and ANDROID_ID are what keep the identity across reinstalls
    private fun stableMac(): String {
        val hw = try { NetworkInterface.getByName("wlan0")?.hardwareAddress } catch (_: Exception) { null }
        val androidId = try { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) } catch (_: Exception) { null }
        val prop = systemProperty(IMAGE_MAC_PROP)
        val installer = if (prop.isNotEmpty()) "" else try { Settings.Global.getString(ctx.contentResolver, INSTALLER_MAC_SETTING) ?: "" } catch (_: Exception) { "" }
        val image = prop.ifEmpty { installer }
        val (mac, picked) = pickMac(prefs.deviceMac, image, hw, systemProperty("ro.serialno"), androidId) {
            ByteArray(6).also { SecureRandom().nextBytes(it) }
        }
        // the identity from the global setting reads as such in the log, not as build.prop
        val source = if (picked == "build.prop" && prop.isEmpty()) "installer setting" else picked
        if (source != "saved") prefs.deviceMac = mac
        EspHomeLog.i(macLine(mac, source, image))
        return mac
    }

    private fun systemProperty(name: String): String = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String ?: ""
    } catch (_: Exception) { "" }

    /** The panel's LAN address ([dev.glassworks.platform.PanelAddress]): never an access point's, nor link-local. */
    private fun ipAddress(): String? = dev.glassworks.platform.PanelAddress.now(ctx)

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "glassworks:esphome").apply {
                setReferenceCounted(false)
                acquire()
            }
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "glassworks:esphome").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            EspHomeLog.w("Could not acquire wake/wifi lock", e)
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
    }
}
