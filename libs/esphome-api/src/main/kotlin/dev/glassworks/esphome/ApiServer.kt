package dev.glassworks.esphome

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where the library logs. By default: `android.util.Log` (found by reflection, so this module has
 * no Android dependency) under [tag] on Android, stdout elsewhere. Replace [sink] to route it into
 * your own logger.
 */
public object EspHomeLog {
    /** How much a line matters. */
    public enum class Level {
        /** Detail for debugging, such as an ignored message type. */
        DEBUG,
        /** Normal events: connections, commands, key changes. */
        INFO,
        /** Something failed or was refused. */
        WARN,
    }

    /** Takes each log line. Called on whichever thread logs, so it must be thread-safe. */
    public fun interface Sink {
        /** Logs [msg] at [level] under [tag], with [t] when an exception caused it. */
        public fun log(level: Level, tag: String, msg: String, t: Throwable?)
    }

    /** The tag (Android's log tag) every line carries. */
    @Volatile public var tag: String = "ESPHomeApi"

    /** Where the lines go. */
    @Volatile public var sink: Sink = defaultSink()

    private fun defaultSink(): Sink {
        val android = runCatching {
            if (System.getProperty("java.vendor")?.contains("Android") != true) return@runCatching null
            val log = Class.forName("android.util.Log")
            val d = log.getMethod("d", String::class.java, String::class.java)
            val i = log.getMethod("i", String::class.java, String::class.java)
            val w = log.getMethod("w", String::class.java, String::class.java, Throwable::class.java)
            Sink { level, tag, msg, t ->
                when (level) {
                    Level.DEBUG -> d.invoke(null, tag, msg)
                    Level.INFO -> i.invoke(null, tag, msg)
                    Level.WARN -> w.invoke(null, tag, msg, t)
                }
            }
        }.getOrNull()
        return android ?: Sink { level, tag, msg, t ->
            println("${level.name.first()}/$tag: $msg${if (t != null) " $t" else ""}")
        }
    }

    /** Logs [msg] at [Level.DEBUG]. */
    public fun d(msg: String): Unit = sink.log(Level.DEBUG, tag, msg, null)

    /** Logs [msg] at [Level.INFO]. */
    public fun i(msg: String): Unit = sink.log(Level.INFO, tag, msg, null)

    /** Logs [msg] at [Level.WARN], with the exception [t] if there is one. */
    public fun w(msg: String, t: Throwable? = null): Unit = sink.log(Level.WARN, tag, msg, t)
}

/**
 * The device's Sendspin client, as the native API exposes it: its client id in
 * `DeviceCapabilitiesResponse`, and the pairing token Home Assistant hands to Music Assistant. Set it
 * as [ApiServer.sendspin].
 */
public interface SendspinTokenSource {
    /** The Sendspin client_id, or "" until the client has started since boot. */
    public fun clientIdIfStarted(): String

    /**
     * A [SendspinApi] `STATUS_*` value, and the token when it is [SendspinApi.STATUS_OK]. The token is a
     * credential: never log it. Called only over an encrypted connection with the device's key.
     */
    public fun pairingToken(): Pair<Int, String?>
}

/**
 * Who the device is: what `HelloResponse`, `DeviceInfoResponse` and the mDNS record report. Home
 * Assistant shows these on its device page.
 *
 * @property name The ESPHome node name, hostname-style, such as `aqara-s1-plus-a1b2c3`.
 * @property friendlyName The name Home Assistant shows, such as `Aqara S1 Plus`.
 * @property macAddress `AA:BB:CC:DD:EE:FF`. Home Assistant derives the config entry's unique id from it,
 *   so keep it stable.
 * @property esphomeVersion The ESPHome version the device claims, such as `2026.10.2`. Home Assistant
 *   checks it for features, so claim one it supports.
 * @property compilationTime When the firmware was built, as text.
 * @property manufacturer Shown as the device's manufacturer.
 * @property model Shown as the device's model.
 * @property voiceFeatures [VoiceFeature] flags, or 0 for no voice assistant.
 * @property webserverPort The port of the device's own web page, or 0 for none. Home Assistant then
 *   links the device page's "Visit" to `http://<host>:<port>` (`configuration_url`,
 *   components/esphome/manager.py).
 */
public data class DeviceIdentity(
    public val name: String,
    public val friendlyName: String,
    public val macAddress: String,
    public val esphomeVersion: String,
    public val compilationTime: String,
    public val manufacturer: String,
    public val model: String,
    public val voiceFeatures: Int,
    public val webserverPort: Int = 0,
) {
    /** Lowercase hex without separators, as in ESPHome's mDNS `mac` TXT and Noise ServerHello. */
    public val macHex: String get() = macAddress.replace(":", "").lowercase()
}

/**
 * A wake word the device can listen for, offered in Home Assistant's voice settings.
 *
 * @property id The id Home Assistant passes back in [VoiceAssistantHandler.setActiveWakeWords].
 * @property wakeWord The phrase, as shown, such as `Okay Nabu`.
 * @property trainedLanguages The languages it was trained for, as language codes.
 */
public data class WakeWordInfo(
    public val id: String,
    public val wakeWord: String,
    public val trainedLanguages: List<String> = listOf("en"),
)

/**
 * The device's wake word settings, for `VoiceAssistantConfigurationResponse`.
 *
 * @property available Every wake word the device has.
 * @property active The ids of the ones it listens for now.
 * @property maxActive How many can be active at once.
 */
public data class VoiceConfiguration(
    public val available: List<WakeWordInfo>,
    public val active: List<String>,
    public val maxActive: Int,
)

/**
 * The device side of Home Assistant's voice assistant: the messages Home Assistant sends to a voice
 * satellite. Set it as [ApiServer.voice] and send the device's own messages (`VoiceAssistantRequest`,
 * audio) with [ApiServer.sendVoice]. Every method runs on the connection's reader thread, so return
 * quickly.
 */
public interface VoiceAssistantHandler {
    /** Home Assistant subscribed to the device's voice messages ([subscribed]), or left. */
    public fun onSubscribe(subscribed: Boolean)

    /** Home Assistant answered a `VoiceAssistantRequest`: a UDP [port] for audio (0 for API audio), or an [error]. */
    public fun onStartResponse(port: Int, error: Boolean)

    /** A pipeline event: [type] is a [VoiceEventType], [data] its key-value data. */
    public fun onEvent(type: Int, data: Map<String, String>)

    /** A timer changed: [type] is a [TimerEventType]; times are in seconds; [active] is false while paused. */
    public fun onTimer(type: Int, id: String, name: String, totalSeconds: Int, secondsLeft: Int, active: Boolean)

    /**
     * Home Assistant asks the device to announce [text] by playing [mediaId], after [preannounceMediaId]
     * (a chime) if not empty. With [startConversation], listen right after it. Answer with
     * `VoiceAssistantAnnounceFinished` through [ApiServer.sendVoice].
     */
    public fun onAnnounce(mediaId: String, text: String, preannounceMediaId: String, startConversation: Boolean)

    /** The wake words to offer in Home Assistant's settings. */
    public fun configuration(): VoiceConfiguration

    /** Home Assistant chose these wake word [ids] (from [WakeWordInfo.id]). */
    public fun setActiveWakeWords(ids: List<String>)
}

/**
 * ESPHome native API server (TCP, default port 6053). Each connection has a reader thread and a
 * writer thread with its own queue ([MAX_QUEUED_BYTES]), so a client that stops reading holds up only
 * itself: its states wait as the newest per entity, and once its other messages fill the queue it is
 * dropped. Message handling follows esphome `api_connection.cpp`: Hello,
 * DeviceInfo, DeviceCapabilities, ListEntities, SubscribeStates, commands, actions ([ApiAction]),
 * camera images ([CameraEntity]), Ping, Disconnect and the voice assistant messages. Unknown messages
 * are ignored, as ESPHome does.
 *
 * Encryption follows an ESPHome build with `api: encryption:` but no YAML key ([NoiseKeys]):
 * without a key the server accepts plaintext and zero-PSK Noise clients and lets Home Assistant
 * provision a key (`NoiseEncryptionSetKeyRequest`); with a key, only Noise with that key.
 *
 * Construct it, then [start] it; [publishStates] after an entity's value changes; [stop] it to close
 * every connection.
 *
 * @param port The TCP port, usually [DEFAULT_PORT]. 0 picks a free one ([localPort]).
 * @param identity Who the device is. Called for every connection and request, so it can change.
 * @param entities The entities Home Assistant sees, with unique object ids. [setEntities] replaces them.
 * @param keys Noise key state, or null for a plaintext-only server (no encryption support at all).
 * @param bindAddress The address to listen on, or null for every address (a device: Home Assistant
 *   reaches it on the LAN). Tests pass the loopback address they connect to. On macOS a wildcard
 *   listener can get a port another process holds on 127.0.0.1, and that process then answers
 *   127.0.0.1 (#113).
 */
public class ApiServer(
    private val port: Int,
    private val identity: () -> DeviceIdentity,
    entities: List<ApiEntity>,
    private val keys: NoiseKeys?,
    private val bindAddress: InetAddress? = null,
) {
    /**
     * A server that starts with the 32-byte key [psk], kept in memory only: Home Assistant can still
     * replace it (`NoiseEncryptionSetKeyRequest`), and a new server starts from [psk] again. Pass a
     * [NoiseKeys] with your own [NoiseKeyStore] to keep a replaced key. With [psk] null, the server is
     * plaintext-only.
     */
    public constructor(port: Int, identity: () -> DeviceIdentity, entities: List<ApiEntity>, psk: ByteArray?) :
        this(port, identity, entities, psk?.let { NoiseKeys(MemoryKeyStore(it)) })

    /** The server's constants. */
    public companion object {
        /** ESPHome's native API port. Home Assistant tries it unless told otherwise. */
        public const val DEFAULT_PORT: Int = 6053

        /** The Noise protocol of an encrypted connection, as ESPHome's mDNS TXT `api_encryption` names it. */
        public const val NOISE_PROTOCOL: String = "Noise_NNpsk0_25519_ChaChaPoly_SHA256"

        /**
         * True if a client's `HelloRequest.client_info` is Home Assistant's: aioesphomeapi sends
         * "Home Assistant" and HA's version. A script on the LAN or the ESPHome dashboard sends its own.
         */
        public fun isHomeAssistant(clientInfo: String): Boolean = clientInfo.startsWith("Home Assistant")

        internal const val API_MAJOR = 1
        /** ESPHome 2026.10/11 reports 1.20 (api_connection.cpp); we implement DeviceCapabilities (1.15+). */
        internal const val API_MINOR = 20
        /**
         * Keepalive, as ESPHome's api_connection.cpp does it: after [KEEPALIVE_MS] without anything from
         * the client the *device* sends a PingRequest, and only if [UNRESPONSIVE_MS] pass with no traffic at
         * all after that is the client dropped. A read timeout can't do this job: aioesphomeapi (46.x,
         * connection.py `_async_send_keep_alive`) only pings when it has received nothing for 20 s, so a
         * device that keeps sending state updates hears nothing back from Home Assistant at all. The old
         * 90 s read timeout therefore dropped HA's connection every 90 s (seen on the panel 2026-11-10).
         */
        internal const val KEEPALIVE_MS = 60_000L
        internal const val UNRESPONSIVE_MS = 150_000L // ESPHome: KEEPALIVE_TIMEOUT_MS * 5 / 2
        /** ESPHome activates a newly saved PSK 100 ms after answering (api_server.cpp update_noise_psk_). */
        internal const val KEY_ACTIVATION_DELAY_MS = 100L

        /** How often the keepalive tries to read a key that is stored but unreadable ([NoiseKeys.locked]). */
        internal const val UNLOCK_RETRY_MS = 60_000L
        /** Action calls waiting for the action thread; more are refused, so a client can't fill the memory. */
        internal const val MAX_PENDING_ACTIONS = 32
        /** A camera stream runs this long after the last stream request (ESPHome api_connection.cpp `CAMERA_STOP_STREAM`). */
        internal const val CAMERA_STREAM_MS = 5_000L
        /**
         * Messages other than states that may wait for one client before it is dropped. Room for the
         * voice pre-roll (2 s of 16 kHz audio, 64 KiB) and several seconds of audio after it. The batch
         * the writer is sending doesn't count, so a client that stops reading can hold about twice this.
         */
        internal const val MAX_QUEUED_BYTES = 512 * 1024
        /**
         * How long a DisconnectRequest may wait to be written before the connection closes anyway. The
         * keepalive can't bound it: a retired connection ignores what its client sends, but still counts
         * it as traffic.
         */
        internal const val DISCONNECT_WRITE_MS = 10_000L
    }

    /** The device's voice assistant, or null for none (voice messages are then ignored). */
    @Volatile public var voice: VoiceAssistantHandler? = null

    /**
     * The device's Sendspin client, for Home Assistant's pairing-token hand-off to Music Assistant
     * (HA `esphome/sendspin.py`, `websocket_api.py` `sendspin/get_pairing_token`). Null = no Sendspin.
     */
    @Volatile public var sendspin: SendspinTokenSource? = null

    /**
     * The device's Bluetooth proxy, or null for none (no flags in DeviceInfo or DeviceCapabilities,
     * subscriptions ignored). Its [BluetoothProxyHandler.onSubscribed] is called under a lock, so it
     * must not block. Replacing it while a client is subscribed tells the old one the subscriber left
     * and the new one it came, unless the new one's [BluetoothProxyHandler.authOnly] refuses that
     * client: then the subscription ends.
     */
    @Volatile public var bluetooth: BluetoothProxyHandler? = null
        set(value) {
            synchronized(bluetoothLock) {
                val old = field
                if (old === value) return
                field = value
                val c = bluetoothConn ?: return
                old?.onSubscribed(false)
                if (value == null || (value.authOnly && !c.authenticated)) setBluetoothConn(null) else value.onSubscribed(true)
            }
        }

    /**
     * The Bluetooth adapter's own MAC (`AA:BB:CC:DD:EE:FF`), sent with the proxy ([bluetooth]) as
     * `bluetooth_mac_address` (DeviceInfo field 18) and the capabilities' `mac_address`, as an ESPHome
     * proxy does. Home Assistant keys the scanner on it (bleak-esphome connect.py:
     * `bluetooth_mac_address or mac_address`). Null, the default, sends neither, and HA keys the scanner
     * on [DeviceIdentity.macAddress]. Set a real adapter address or null, never a placeholder, and keep
     * it the same for the device's life: on a change, HA moves the device's scanner to the new address
     * (seen on an S1 with HA 2026.11: the same entry, re-keyed in place), and back again on a change back.
     */
    @Volatile public var bluetoothMacAddress: String? = null

    /**
     * The Bluetooth proxy's active connections (GATT), or null for a proxy that only listens. Works only
     * next to [bluetooth]: requests come from its subscriber, and the flags it adds go out with it.
     * Called under the same lock as [bluetooth], so it must not block either. Replacing it tells the old
     * one to close its links ([BluetoothConnectionHandler.onSubscriberChanged]): it gets no more requests.
     */
    @Volatile public var bluetoothConnections: BluetoothConnectionHandler? = null
        set(value) {
            synchronized(bluetoothLock) {
                val old = field
                if (old === value) return
                field = value
                try { old?.onSubscriberChanged() } catch (t: Throwable) { EspHomeLog.w("Bluetooth onSubscriberChanged failed", t) }
            }
        }

    /**
     * Called after a command or an action call was handled, when a state may have changed. A device
     * usually calls [publishStates] here.
     */
    @Volatile public var onCommand: (() -> Unit)? = null

    /** Called (on a connection thread) for every `HelloRequest`, with its client_info and the peer address. */
    @Volatile public var onClientHello: ((clientInfo: String, remote: InetAddress?) -> Unit)? = null

    /**
     * Called (on a connection thread) when a client subscribes to states, with its Hello
     * client_info and peer address. Home Assistant only does this once it has added the device (its
     * config flow's discovery probe just reads DeviceInfo), so it is the "HA has added the panel"
     * signal. `authenticated`: the connection is Noise with the provisioned key (not plaintext, not
     * the zero-PSK provisioning handshake), so the client really holds the device's API key.
     */
    @Volatile public var onStatesSubscribed: ((clientInfo: String, remote: InetAddress?, authenticated: Boolean) -> Unit)? = null

    /**
     * Called (on a connection thread) with Home Assistant's time zone. The server asks for the time
     * (`GetTimeRequest`) once an authenticated client subscribes to states, and Home Assistant's answer
     * carries its zone (`GetTimeResponse.timezone`) as a POSIX `TZ` rule, like
     * `CET-1CEST,M3.5.0,M10.5.0/3`: aioesphomeapi turns HA's IANA zone into one. An answer with only
     * `parsed_timezone` (ESPHome 2026.9 deprecated the string) gets the same rule with numeric names,
     * like `<+01>-1<+02>-2,M3.5.0/2,M10.5.0/3`.
     */
    @Volatile public var onTimeZone: ((String) -> Unit)? = null

    /** Called after a provisioned key became active (or was cleared), e.g. to update the mDNS TXT. */
    @Volatile public var onKeyActivated: (() -> Unit)? = null

    // Replaced as a whole by setEntities (the wall base adds/removes entities at runtime).
    @Volatile private var entities: List<ApiEntity> = entities.toList()
    @Volatile private var byKey: Map<Int, ApiEntity> = entities.associateBy { it.key }
    private val connections = CopyOnWriteArrayList<Connection>()

    /** A client has subscribed to the states now (Home Assistant, once it has added the device). */
    public val hasSubscriber: Boolean get() = connections.any { it.subscribedStates }
    private val ids = AtomicInteger()
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    /** [KEEPALIVE_MS] / [UNRESPONSIVE_MS]; shorter in tests. Set before [start]. */
    @Volatile internal var keepaliveMs = KEEPALIVE_MS
    @Volatile internal var unresponsiveMs = UNRESPONSIVE_MS
    /** The keepalive's time source, in ms; tests drive it by hand. */
    @Volatile internal var clock: () -> Long = System::currentTimeMillis
    /** Makes the listening socket; a test passes one whose sockets it can hold mid-write. Set before [start]. */
    @Volatile internal var newServerSocket: () -> ServerSocket = { ServerSocket() }
    /** Called on the sending thread for each frame a send has queued, before it returns; a test holds a caller there. */
    @Volatile internal var onQueued: ((type: Int) -> Unit)? = null
    private var keepaliveTimer: java.util.concurrent.ScheduledExecutorService? = null
    /** Runs [ApiAction] handlers, one at a time, off the connections' reader threads. */
    @Volatile private var actionThread: java.util.concurrent.ExecutorService? = null
    /** [CAMERA_STREAM_MS]; shorter in tests. Set before [start]. */
    @Volatile internal var cameraStreamMs = CAMERA_STREAM_MS
    @Volatile internal var disconnectWriteMs = DISCONNECT_WRITE_MS
    @Volatile private var voiceConn: Connection? = null
    private val bluetoothLock = Any()
    @Volatile private var bluetoothConn: Connection? = null

    private val stateLock = Any()
    private val lastStates = HashMap<Int, ByteArray>()

    /** The port the server listens on (useful with port 0), or -1 when it isn't running. */
    public val localPort: Int get() = serverSocket?.localPort ?: -1
    internal val hasVoiceClient: Boolean get() = voiceConn?.isOpen == true
    internal val connectionCount: Int get() = connections.size

    init {
        require(byKey.size == entities.size) { "duplicate entity keys" }
    }

    /**
     * Replaces the entity set and asks every connected client to disconnect (`DisconnectRequest`,
     * socket closed after the ack, or 2 s after the request is written). Returns without waiting for
     * either. Home Assistant reconnects and lists the entities again, which is how it picks up added
     * ones, like after an ESPHome firmware update.
     */
    public fun setEntities(newEntities: List<ApiEntity>) {
        val list = newEntities.toList()
        val map = list.associateBy { it.key }
        require(map.size == list.size) { "duplicate entity keys" }
        synchronized(stateLock) {
            entities = list
            byKey = map
            lastStates.keys.retainAll(map.keys)
        }
        EspHomeLog.i("Entity set changed (${list.size} entities); asking ${connections.size} client(s) to reconnect")
        for (c in connections) c.requestDisconnect()
    }

    /**
     * Fires [entity] with event [type], one of its [EventEntity.eventTypes], on every subscribed
     * connection. False if the entity isn't in the current set, the type isn't declared, or no client
     * took it.
     */
    public fun sendEvent(entity: EventEntity, type: String): Boolean {
        if (byKey[entity.key] !== entity) return false
        if (type !in entity.eventTypes) {
            EspHomeLog.w("event type '$type' not declared by ${entity.objectId}")
            return false
        }
        val frame = Frame(Msg.EVENT_RESPONSE, entity.event(type).toByteArray())
        var sent = false
        synchronized(stateLock) {
            for (c in connections) if (c.subscribedStates) sent = c.send(listOf(frame)) || sent
        }
        return sent
    }

    /**
     * Fires [event] on Home Assistant's event bus (ESPHome's `homeassistant.event`), on every connection
     * that asked for them with `SubscribeHomeassistantServicesRequest`; Home Assistant asks on each
     * connection. Queued like the states, on the connection's writer. True when at least one took it.
     */
    public fun sendHomeAssistantEvent(event: HomeAssistantEvent): Boolean {
        val frame = Frame(Msg.HOMEASSISTANT_ACTION_REQUEST, event.encode().toByteArray())
        var sent = false
        synchronized(stateLock) {
            for (c in connections) if (c.subscribedHaServices) sent = c.send(listOf(frame)) || sent
        }
        if (!sent) EspHomeLog.w("${event.event}: no Home Assistant connection subscribed to events")
        return sent
    }

    /** Binds the port and starts accepting connections. Throws [IOException] if the port is taken. */
    @Throws(IOException::class)
    public fun start() {
        val ss = newServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(bindAddress, port))
        serverSocket = ss
        running = true
        Thread({ acceptLoop(ss) }, "esphome-accept").apply { isDaemon = true; start() }
        val tick = (keepaliveMs / 6).coerceIn(20, 5_000)
        keepaliveTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "esphome-keepalive").apply { isDaemon = true }
        }.also { it.scheduleWithFixedDelay({ keepaliveTick() }, tick, tick, java.util.concurrent.TimeUnit.MILLISECONDS) }
        actionThread = java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            java.util.concurrent.ArrayBlockingQueue(MAX_PENDING_ACTIONS),
        ) { r -> Thread(r, "esphome-actions").apply { isDaemon = true } }
        EspHomeLog.i("API server listening on ${ss.localPort} (${modeName()})")
    }

    private fun modeName() = when {
        keys == null -> "plaintext only"
        keys.locked -> "a key is stored but can't be read now: no connections until it can"
        keys.hasKey -> "Noise, key fingerprint ${NoiseKeys.fingerprint(keys.active!!)}"
        else -> "no key yet: plaintext + zero-PSK Noise, key provisionable"
    }

    /** True while the device has no key: it accepts connections without one, and Home Assistant may set one. */
    public val provisionable: Boolean get() = keys?.provisionable == true

    /** True when a Noise key is active: only encrypted connections with that key get in. */
    public val encrypted: Boolean get() = keys?.hasKey == true

    /**
     * True when the device has a key, active or stored but unreadable now ([NoiseKeys.locked]). Advertise
     * this as `api_encryption`: a keyed device stays a keyed device, so a client tries Noise and fails
     * instead of trying plaintext while the key can't be read. Added in 0.1.1.
     */
    public val keyed: Boolean get() = keys?.let { it.hasKey || it.locked } == true

    /**
     * Applies a key change made outside the API, such as in the device's own settings: [key] is the
     * new 32-byte key, already saved by the caller, or null to remove it. Every open session is dropped,
     * as ESPHome does when a key is activated. Does nothing on a plaintext-only server.
     */
    public fun replaceKey(key: ByteArray?) {
        val k = keys ?: return
        if (!k.replace(key)) return
        EspHomeLog.i("API key replaced locally; now ${modeName()}")
        retireAll()
        onKeyActivated?.invoke()
    }

    private fun scheduleActivation() {
        Thread({
            try { Thread.sleep(KEY_ACTIVATION_DELAY_MS) } catch (_: InterruptedException) {}
            if (keys?.activatePending() == true) {
                EspHomeLog.i("Encryption key activated; disconnecting all clients to reset the PSK (${modeName()})")
                retireAll()
                try { onKeyActivated?.invoke() } catch (t: Throwable) { EspHomeLog.w("onKeyActivated failed", t) }
            }
        }, "esphome-key-activate").apply { isDaemon = true; start() }
    }

    /** Sessions opened before a key change must not survive it (esphome `flags_.next_close`). */
    /** When a locked key ([NoiseKeys.locked]) was last tried; the keepalive tries again every [UNLOCK_RETRY_MS]. */
    @Volatile private var unlockTriedAt = 0L

    /** Tries to read a locked key; when it ends the lock, the device advertises its new state. */
    private fun tryUnlock() {
        val k = keys ?: return
        if (!k.locked) return
        unlockTriedAt = clock()
        if (k.unlock()) {
            EspHomeLog.i("Encryption key readable again; now ${modeName()}")
            try { onKeyActivated?.invoke() } catch (t: Throwable) { EspHomeLog.w("onKeyActivated failed", t) }
        }
    }

    private fun retireAll() {
        for (c in connections) c.retire()
    }

    /** Pings quiet clients and drops the ones that stay silent (see [KEEPALIVE_MS]). Tests call it too. */
    internal fun keepaliveTick() {
        val now = clock()
        if (keys?.locked == true && now - unlockTriedAt >= UNLOCK_RETRY_MS) tryUnlock()
        for (c in connections) {
            try {
                if (!c.ready || !c.isOpen) continue
                val idle = now - c.lastRead
                if (c.pingSent) {
                    if (idle > unresponsiveMs) {
                        EspHomeLog.w("[${c.id}] no answer to the keepalive ping, nothing received for ${idle / 1000} s; closing")
                        c.close()
                    }
                } else if (idle > keepaliveMs) {
                    // Set first: the writer may send the ping, and the reader handle its answer, before send() returns.
                    c.pingSent = true
                    if (!c.send(listOf(Frame(Msg.PING_REQUEST, ByteArray(0))))) c.pingSent = false
                }
            } catch (t: Throwable) { EspHomeLog.w("keepalive failed", t) }
        }
    }

    /** Stops listening and closes every connection. */
    public fun stop() {
        keepaliveTimer?.shutdownNow()
        keepaliveTimer = null
        actionThread?.shutdownNow()
        actionThread = null
        running = false
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        connections.forEach { it.close() }
        connections.clear()
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val s = try { ss.accept() } catch (e: IOException) {
                if (running) EspHomeLog.w("accept failed", e)
                break
            }
            val c = Connection(ids.incrementAndGet(), s)
            connections += c
            Thread({ c.run() }, "esphome-conn-${c.id}").apply { isDaemon = true; start() }
        }
    }

    /** Sends the states that changed since the last call to every subscribed connection. */
    public fun publishStates() {
        // Sent under the lock so a connection that is subscribing can't get updates out of order.
        synchronized(stateLock) {
            val changed = ArrayList<Pair<Int, Frame>>()
            for (e in entities) {
                if (e is EventEntity) continue
                val st = safeState(e) ?: continue
                val prev = lastStates[e.key]
                if (prev == null || !prev.contentEquals(st)) {
                    lastStates[e.key] = st
                    changed += e.key to Frame(e.stateType, st)
                }
            }
            if (changed.isEmpty()) return
            for (c in connections) if (c.subscribedStates) c.sendStates(changed)
        }
    }

    private fun safeState(e: ApiEntity): ByteArray? = try {
        e.state()?.toByteArray()
    } catch (t: Throwable) {
        EspHomeLog.w("state of ${e.objectId} failed", t)
        null
    }

    /**
     * Sends one batch of advertisements, at most [RawAdvertisement.BATCH_SIZE], to the client that
     * subscribed to them. False if none did.
     */
    public fun sendBluetoothAdvertisements(batch: List<RawAdvertisement>): Boolean {
        val frame = Frame(BluetoothMsg.LE_RAW_ADVERTISEMENTS_RESPONSE, rawAdvertisements(batch).toByteArray())
        // Under the lock, so a batch never reaches a client another one has just replaced. send only queues.
        synchronized(bluetoothLock) {
            val c = bluetoothConn ?: return false
            return c.send(listOf(frame))
        }
    }

    /**
     * Sends a connection [event] to the client that holds the advertisement subscription, as ESPHome
     * does. False if none does.
     */
    public fun sendBluetoothEvent(event: GattEvent): Boolean {
        val frame = event.frame()
        synchronized(bluetoothLock) {
            val c = bluetoothConn ?: return false
            return c.send(listOf(frame))
        }
    }

    /** Takes [handler] out if it is still [bluetooth] (not if another one replaced it). True if it was. */
    public fun removeBluetooth(handler: BluetoothProxyHandler): Boolean = synchronized(bluetoothLock) {
        if (bluetooth !== handler) return false
        bluetooth = null
        true
    }

    /** Takes [handler] out if it is still [bluetoothConnections] (not if another one replaced it). True if it was. */
    public fun removeBluetoothConnections(handler: BluetoothConnectionHandler): Boolean = synchronized(bluetoothLock) {
        if (bluetoothConnections !== handler) return false
        bluetoothConnections = null
        true
    }

    private fun subscribeBluetooth(c: Connection) {
        synchronized(bluetoothLock) {
            val bt = bluetooth ?: return
            if (bt.authOnly && !c.authenticated) {
                EspHomeLog.w("[${c.id}] Bluetooth subscription refused: only from an authenticated (encrypted, provisioned) connection")
                bt.onRefused(c.clientInfo)
                return
            }
            val previous = bluetoothConn
            if (previous === c || !c.isOpen) return
            setBluetoothConn(c)
            if (previous == null) bt.onSubscribed(true)
            else EspHomeLog.w("[${c.id}] Bluetooth subscription replaces [${previous.id}]")
        }
    }

    private fun unsubscribeBluetooth(c: Connection) {
        synchronized(bluetoothLock) {
            if (bluetoothConn !== c) return
            setBluetoothConn(null)
            bluetooth?.onSubscribed(false)
        }
    }

    /** Under [bluetoothLock]. A new or no subscriber closes every link (ESPHome `disconnect_all_connections_`). */
    private fun setBluetoothConn(c: Connection?) {
        if (bluetoothConn === c) return
        bluetoothConn = c
        try { bluetoothConnections?.onSubscriberChanged() } catch (t: Throwable) { EspHomeLog.w("Bluetooth onSubscriberChanged failed", t) }
    }

    /** The proxy's flags: the handler's, plus active connections when [bluetoothConnections] is set. 0 for no proxy. */
    private fun bluetoothFlags(): Int {
        val bt = bluetooth ?: return 0
        val active = if (bluetoothConnections != null) BluetoothProxyFeature.ACTIVE_CONNECTIONS or BluetoothProxyFeature.REMOTE_CACHING else 0
        return bt.featureFlags or active
    }

    /**
     * A connection request from [c]. Only the advertisement subscriber may make one (it passed
     * [BluetoothProxyHandler.authOnly] to get there); anyone else is told the device isn't connected.
     */
    private fun bluetoothRequest(c: Connection, request: GattRequest) {
        synchronized(bluetoothLock) {
            val handler = bluetoothConnections
            val bt = bluetooth
            if (handler != null && bt != null && bluetoothConn === c && (!bt.authOnly || c.authenticated)) {
                try { handler.onRequest(request) } catch (t: Throwable) { EspHomeLog.w("Bluetooth request failed", t) }
                return
            }
        }
        EspHomeLog.w("[${c.id}] Bluetooth connection request refused: only from the client that subscribed to advertisements")
        c.send(listOf(refusal(request).frame()))
    }

    /** `SubscribeBluetoothConnectionsFreeRequest`: the slot table, now; later changes go to the subscriber. */
    private fun subscribeConnectionsFree(c: Connection) {
        // Under the lock, as sendBluetoothEvent: a newer table can't overtake this one on the way out.
        synchronized(bluetoothLock) {
            val bt = bluetooth ?: return
            val handler = bluetoothConnections ?: return
            if (bt.authOnly && !c.authenticated) {
                EspHomeLog.w("[${c.id}] Bluetooth connections subscription refused: only from an authenticated (encrypted, provisioned) connection")
                return
            }
            c.send(listOf(handler.connectionsFree().frame()))
        }
    }

    /**
     * Sends a voice assistant message of [type] (a [Msg] id, such as [Msg.VOICE_ASSISTANT_REQUEST]) to
     * the connection that subscribed to voice. False if none did.
     */
    public fun sendVoice(type: Int, payload: ProtoWriter): Boolean {
        val c = voiceConn ?: return false
        return c.send(listOf(Frame(type, payload.toByteArray())))
    }

    private inner class Connection(val id: Int, private val socket: Socket) {
        private val outbox = Outbox(MAX_QUEUED_BYTES)
        private lateinit var transport: FrameTransport
        @Volatile var subscribedStates = false
        /** Sent `SubscribeHomeassistantServicesRequest`: takes [sendHomeAssistantEvent]'s events. */
        @Volatile var subscribedHaServices = false
        @Volatile var isOpen = true
        /** HelloRequest.client_info ("" until the Hello). */
        @Volatile var clientInfo = ""
        /** Noise with the provisioned key (set once the handshake succeeded). */
        @Volatile var authenticated = false
        /** Set when the key changed: only the disconnect handshake is processed from now on. */
        @Volatile private var retired = false
        /**
         * A DisconnectRequest is on its way ([askToDisconnect]). Never cleared: every path that sets it
         * ends in [close], and clearing it would bring back a request and a thread per call.
         */
        private val disconnecting = AtomicBoolean()
        private val remote = socket.remoteSocketAddress?.toString() ?: "?"
        /** Handshake done and the writer running: messages can be sent, and the keepalive applies. */
        @Volatile var ready = false
        /** When the last frame arrived from the client; [pingSent]: we pinged since then. */
        @Volatile var lastRead = 0L
        @Volatile var pingSent = false
        private val camera = CameraFeed("esphome-camera-$id", cameraStreamMs, ::visibleCameras) { send(listOf(it), wait = true) }

        private fun visibleCameras(): List<CameraEntity> =
            if (!subscribedStates) emptyList()
            else entities.filterIsInstance<CameraEntity>().filter { !it.authOnly || authenticated }

        fun run() {
            try {
                socket.tcpNoDelay = true
                // Only a backstop: the keepalive (pings, [unresponsiveMs]) decides when a client is gone.
                socket.soTimeout = (unresponsiveMs + 30_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val input = BufferedInputStream(socket.getInputStream(), 8 * 1024)
                val output = BufferedOutputStream(socket.getOutputStream(), 16 * 1024)
                val ident = identity()
                val k = keys
                if (k != null && k.locked) tryUnlock()
                if (k != null && k.locked) {
                    EspHomeLog.w("[$id] $remote: the encryption key can't be read right now; refusing the connection")
                    return
                }
                val active = k?.active
                transport = when {
                    k == null -> PlaintextTransport(input, output)
                    active != null -> NoiseTransport(input, output, active, ident.name, ident.macHex)
                    else -> {
                        // No key yet (esphome api_frame_helper_plaintext.cpp): a 0x01 first byte is a
                        // Noise ClientHello and is handed to a Noise helper with the all-zeros PSK.
                        input.mark(1)
                        val first = input.read()
                        input.reset()
                        if (first == 0x01) {
                            EspHomeLog.i("[$id] $remote: Noise client on an unprovisioned device (zero PSK)")
                            NoiseTransport(input, output, NoiseKeys.ZERO_PSK, ident.name, ident.macHex)
                        } else {
                            PlaintextTransport(input, output)
                        }
                    }
                }
                if (!transport.handshake()) {
                    EspHomeLog.w("[$id] handshake with $remote failed")
                    return
                }
                authenticated = k != null && active != null && transport is NoiseTransport
                Thread(::writeLoop, "esphome-write-$id").apply { isDaemon = true; start() }
                lastRead = clock(); ready = true
                while (isOpen) {
                    val f = transport.read() ?: break
                    // any traffic counts, as in ESPHome (last_traffic_); a pending ping is answered by it
                    lastRead = clock(); pingSent = false
                    handle(f)
                }
            } catch (e: SocketTimeoutException) {
                EspHomeLog.w("[$id] $remote sent nothing for ${socket.soTimeout / 1000} s; closing")
            } catch (e: SocketException) {
                if (isOpen) EspHomeLog.i("[$id] $remote: ${e.message}")
            } catch (e: IOException) {
                if (isOpen) EspHomeLog.w("[$id] $remote: ${e.message}")
            } catch (e: Exception) {
                EspHomeLog.w("[$id] $remote: handler error", e)
            } finally {
                close()
            }
        }

        private fun writeLoop() {
            // Closes however the writer ends, an Error or a failing log sink included: left open, the
            // connection would hold every caller waiting for a write (the camera, the Disconnect and
            // set-key answers) until the keepalive dropped it.
            try {
                while (true) {
                    transport.write(outbox.take() ?: return)
                    outbox.written()
                }
            } catch (e: IOException) {
                if (isOpen) EspHomeLog.w("[$id] write failed: ${e.message}")
            } catch (t: Throwable) {
                EspHomeLog.w("[$id] writer error", t)
            } finally {
                close()
            }
        }

        /**
         * Queues [frames] for the writer; with [wait], returns once they are written or [timeoutMs] has
         * passed (only on this connection's own threads). False if the connection is closed, or was full
         * or too slow and is now closed.
         */
        fun send(frames: List<Frame>, wait: Boolean = false, timeoutMs: Long = Long.MAX_VALUE): Boolean {
            if (!isOpen || !ready) return false
            if (if (wait) outbox.offerAndWait(frames, timeoutMs) else outbox.offer(frames)) {
                onQueued?.let { queued -> frames.forEach { queued(it.type) } }
                return true
            }
            if (isOpen) {
                try {
                    EspHomeLog.w("[$id] $remote is not reading: messages waited too long or filled the queue (${MAX_QUEUED_BYTES / 1024} KiB); closing")
                } finally {
                    close()
                }
            }
            return false
        }

        /** (entity key, state) pairs; each replaces that entity's state if one is still waiting. */
        fun sendStates(states: List<Pair<Int, Frame>>) {
            if (!isOpen || !ready) return
            for ((key, f) in states) outbox.offerState(key, f)
        }

        private fun reply(type: Int, w: ProtoWriter = ProtoWriter()) = send(listOf(Frame(type, w.toByteArray())))

        /** Answers, and closes once the answer is written (the client waits for it before it closes). */
        private fun replyAndClose(type: Int) {
            send(listOf(Frame(type, ByteArray(0))), wait = true)
            close()
        }

        /** Server-initiated disconnect (entity set changed): ask, then close after the ack or 2 s. */
        fun requestDisconnect() {
            subscribedStates = false
            subscribedHaServices = false
            askToDisconnect(2_000)
        }

        /**
         * Sends a DisconnectRequest and closes [graceMs] after it is written, or on the client's answer.
         * The wait for the write runs on a thread of its own, so the caller doesn't wait for a slow client.
         * If the request isn't written within [disconnectWriteMs], the connection closes without it.
         * Only the first call does anything: a later one (more entity changes, a key change) would only
         * add a thread and a request, and the first one already closes the connection.
         */
        private fun askToDisconnect(graceMs: Long) {
            if (!disconnecting.compareAndSet(false, true)) return
            val thread = Thread({
                try {
                    if (send(listOf(Frame(Msg.DISCONNECT_REQUEST, ByteArray(0))), wait = true, timeoutMs = disconnectWriteMs)) {
                        Thread.sleep(graceMs)
                    }
                } catch (_: InterruptedException) {
                } finally {
                    close()
                }
            }, "esphome-disc-$id").apply { isDaemon = true }
            try {
                thread.start()
            } catch (t: Throwable) {
                // No thread (out of memory, thread limit): close now, or the flag stays set with nothing
                // left to close the connection.
                close()
                throw t
            }
        }

        /**
         * Asks the client to disconnect and closes after the ack or 500 ms; ignores everything else. If
         * the entities changed just before, their DisconnectRequest stands, and the connection closes
         * when that one ends (2 s after it is written).
         */
        fun retire() {
            if (retired) return
            retired = true
            subscribedStates = false
            subscribedHaServices = false
            if (voiceConn === this) {
                voiceConn = null
                voice?.onSubscribe(false)
            }
            unsubscribeBluetooth(this)
            askToDisconnect(500)
        }

        fun close() {
            if (!isOpen) return
            isOpen = false
            outbox.close()
            camera.close()
            try { socket.close() } catch (_: IOException) {}
            connections -= this
            if (voiceConn === this) {
                voiceConn = null
                voice?.onSubscribe(false)
            }
            unsubscribeBluetooth(this)
            EspHomeLog.i("[$id] $remote disconnected")
        }

        private fun handle(f: Frame) {
            val m = ProtoMessage(f.data)
            if (retired) {
                when (f.type) {
                    Msg.DISCONNECT_REQUEST -> replyAndClose(Msg.DISCONNECT_RESPONSE)
                    Msg.DISCONNECT_RESPONSE -> close()
                }
                return
            }
            when (f.type) {
                Msg.HELLO_REQUEST -> {
                    EspHomeLog.i("[$id] hello from '${m.string(1)}' API ${m.uint32(2)}.${m.uint32(3)} ($remote)")
                    clientInfo = m.string(1)
                    try { onClientHello?.invoke(m.string(1), socket.inetAddress) } catch (t: Throwable) {
                        EspHomeLog.w("onClientHello failed", t)
                    }
                    val ident = identity()
                    reply(
                        Msg.HELLO_RESPONSE,
                        ProtoWriter().uint32(1, API_MAJOR).uint32(2, API_MINOR)
                            .string(3, ident.esphomeVersion, force = true).string(4, ident.name, force = true),
                    )
                }
                Msg.AUTHENTICATION_REQUEST -> Unit // password auth was removed in ESPHome 2026.1; no reply
                Msg.DISCONNECT_REQUEST -> replyAndClose(Msg.DISCONNECT_RESPONSE)
                Msg.DISCONNECT_RESPONSE -> close()
                Msg.PING_REQUEST -> reply(Msg.PING_RESPONSE)
                Msg.NOISE_ENCRYPTION_SET_KEY_REQUEST -> {
                    // esphome api_connection.cpp send_noise_encryption_set_key_response_
                    val k = keys
                    val r = k?.handleSetKey(m.bytes(1)) ?: SetKeyResult(false, false, "encryption not supported")
                    val how = if (transport is NoiseTransport) "Noise" else "plaintext (deprecated by ESPHome)"
                    EspHomeLog.i("[$id] NoiseEncryptionSetKeyRequest over $how: ${r.message}")
                    // Written before the key activates: activation closes every connection and drops what still waits.
                    send(listOf(Frame(Msg.NOISE_ENCRYPTION_SET_KEY_RESPONSE, ProtoWriter().bool(1, r.success).toByteArray())), wait = true)
                    if (r.activate) scheduleActivation()
                }
                Msg.PING_RESPONSE -> Unit
                // Only an authenticated client was asked: anyone else's zone is not Home Assistant's.
                Msg.GET_TIME_RESPONSE -> if (authenticated) timeZoneOf(m)?.let { zone ->
                    try { onTimeZone?.invoke(zone) } catch (t: Throwable) { EspHomeLog.w("onTimeZone failed", t) }
                }
                Msg.DEVICE_INFO_REQUEST -> reply(Msg.DEVICE_INFO_RESPONSE, deviceInfo())
                Msg.DEVICE_CAPABILITIES_REQUEST -> reply(Msg.DEVICE_CAPABILITIES_RESPONSE, capabilities())
                Msg.SENDSPIN_PAIRING_TOKEN_REQUEST -> reply(Msg.SENDSPIN_PAIRING_TOKEN_RESPONSE, sendspinToken())
                Msg.LIST_ENTITIES_REQUEST -> {
                    val frames = entities.map { Frame(it.listType, it.info().toByteArray()) } +
                        Frame(Msg.LIST_ENTITIES_DONE, ByteArray(0))
                    send(frames)
                }
                Msg.SUBSCRIBE_HA_SERVICES_REQUEST -> subscribedHaServices = true
                BluetoothMsg.SUBSCRIBE_LE_ADVERTISEMENTS_REQUEST -> subscribeBluetooth(this)
                BluetoothMsg.UNSUBSCRIBE_LE_ADVERTISEMENTS_REQUEST -> unsubscribeBluetooth(this)
                BluetoothConnectionMsg.SUBSCRIBE_CONNECTIONS_FREE_REQUEST -> subscribeConnectionsFree(this)
                BluetoothConnectionMsg.DEVICE_REQUEST, BluetoothConnectionMsg.GATT_GET_SERVICES_REQUEST,
                BluetoothConnectionMsg.GATT_READ_REQUEST, BluetoothConnectionMsg.GATT_WRITE_REQUEST,
                BluetoothConnectionMsg.GATT_READ_DESCRIPTOR_REQUEST, BluetoothConnectionMsg.GATT_WRITE_DESCRIPTOR_REQUEST,
                BluetoothConnectionMsg.GATT_NOTIFY_REQUEST -> {
                    val request = gattRequest(f.type, m)
                    if (request != null) bluetoothRequest(this, request)
                    else unsupportedDeviceRequest(m)?.let { send(listOf(it)) }
                }
                Msg.SUBSCRIBE_STATES_REQUEST -> {
                    subscribeStates()
                    camera.announce()
                    try { onStatesSubscribed?.invoke(clientInfo, socket.inetAddress, authenticated) } catch (t: Throwable) { EspHomeLog.w("onStatesSubscribed failed", t) }
                    if (authenticated && onTimeZone != null) send(listOf(Frame(Msg.GET_TIME_REQUEST, ByteArray(0))))
                }
                Msg.LIGHT_COMMAND, Msg.SWITCH_COMMAND, Msg.NUMBER_COMMAND, Msg.SELECT_COMMAND,
                Msg.BUTTON_COMMAND, Msg.TEXT_COMMAND, Msg.MEDIA_PLAYER_COMMAND, Msg.UPDATE_COMMAND -> {
                    val e = byKey[m.fixed32(1)]
                    if (e == null || e.commandType != f.type) {
                        EspHomeLog.w("[$id] command type ${f.type} for unknown key ${m.fixed32(1)}")
                    } else if (e.authOnly && !authenticated) {
                        EspHomeLog.w("[$id] command for ${e.objectId} refused: only from an authenticated (encrypted, provisioned) connection")
                        // the client may already show the new value: send it the unchanged state back
                        safeState(e)?.let { st -> if (subscribedStates) sendStates(listOf(e.key to Frame(e.stateType, st))) }
                    } else {
                        EspHomeLog.i("[$id] command for ${e.objectId}")
                        try { e.command(m) } catch (t: Throwable) { EspHomeLog.w("command ${e.objectId} failed", t) }
                        onCommand?.invoke()
                    }
                }
                Msg.EXECUTE_SERVICE_REQUEST -> executeAction(m)
                Msg.CAMERA_IMAGE_REQUEST -> camera.request(single = m.bool(1), stream = m.bool(2))
                else -> handleVoice(f.type, m)
            }
        }

        /**
         * `ExecuteServiceRequest`: the handler runs on [actionThread]. Home Assistant waits for an
         * `ExecuteServiceResponse` only when it sent a call_id (every [ActionResponse] but NONE).
         */
        private fun executeAction(m: ProtoMessage) {
            val callId = m.uint32(3)
            fun fail(why: String) {
                EspHomeLog.w("[$id] action call refused: $why")
                if (callId != 0) reply(Msg.EXECUTE_SERVICE_RESPONSE, ProtoWriter().uint32(1, callId).string(3, why))
            }
            val action = byKey[m.fixed32(1)] as? ApiAction ?: return fail("unknown action key ${m.fixed32(1)}")
            if (action.authOnly && !authenticated) {
                return fail("${action.name} only runs over an encrypted connection with the device's key")
            }
            val call = try { action.decode(m) } catch (e: IllegalArgumentException) {
                return fail(e.message ?: "bad arguments")
            } catch (e: ProtoException) {
                return fail("bad arguments: ${e.message}")
            }
            EspHomeLog.i("[$id] action ${action.name}")
            val run = Runnable {
                val response = action.execute(call, callId)
                if (callId != 0) reply(Msg.EXECUTE_SERVICE_RESPONSE, response)
                try { onCommand?.invoke() } catch (t: Throwable) { EspHomeLog.w("onCommand failed", t) }
            }
            try { actionThread?.execute(run) ?: fail("server stopped") } catch (e: java.util.concurrent.RejectedExecutionException) {
                fail("busy: $MAX_PENDING_ACTIONS calls already waiting")
            }
        }

        private fun capabilities(): ProtoWriter {
            val w = ProtoWriter()
            // bluetooth_proxy = 1 { feature_flags = 1, mac_address = 2 }. Clients read the flags here from
            // API 1.15 on. Without the adapter's MAC, HA falls back to the device MAC.
            if (bluetooth != null) w.message(1, ProtoWriter().uint32(1, bluetoothFlags()).string(2, bluetoothMacAddress))
            // voice_assistant = 2 { feature_flags = 1 }
            w.message(2, ProtoWriter().uint32(1, identity().voiceFeatures))
            // sendspin = 6 { feature_flags = 1, client_id = 2 } (client id empty until the client has started)
            sendspin?.let { ss ->
                w.message(6, ProtoWriter().uint32(1, SendspinApi.FEATURE_PAIRING_TOKEN).string(2, ss.clientIdIfStarted()))
            }
            return w
        }

        /**
         * `SendspinPairingTokenResponse` (ESPHome sendspin_hub.cpp semantics): only over an encrypted
         * session with a provisioned key, since the token is a long-lived credential. Never logged.
         * [authenticated], not whether the device has a key now: a connection that came in on the zero PSK before a key
         * was provisioned is Noise too, but with a key anyone could use.
         */
        private fun sendspinToken(): ProtoWriter {
            val ss = sendspin
            val encrypted = authenticated
            val (status, token) = when {
                ss == null -> SendspinApi.STATUS_DISABLED to null
                !encrypted -> SendspinApi.STATUS_ENCRYPTION_REQUIRED to null
                else -> ss.pairingToken()
            }
            EspHomeLog.i("[$id] Sendspin pairing token requested: status $status")
            return ProtoWriter().uint32(1, status).string(2, token)
        }

        private fun subscribeStates() = synchronized(stateLock) {
            // Current state of everything (and refresh the cache, so other connections get
            // whatever changed on the next publish).
            val frames = entities.mapNotNull { e ->
                if (e is EventEntity) return@mapNotNull null
                val st = safeState(e) ?: return@mapNotNull null
                val prev = lastStates[e.key]
                lastStates[e.key] = st
                if (prev != null && !prev.contentEquals(st)) {
                    connections.filter { it !== this && it.subscribedStates }
                        .forEach { it.sendStates(listOf(e.key to Frame(e.stateType, st))) }
                }
                e.key to Frame(e.stateType, st)
            }
            sendStates(frames)
            subscribedStates = true
        }

        private fun handleVoice(type: Int, m: ProtoMessage) {
            val va = voice
            when (type) {
                Msg.SUBSCRIBE_VOICE_ASSISTANT_REQUEST -> {
                    // The flags aren't interpreted: like esphome voice_assistant.cpp, the audio
                    // transport follows VoiceAssistantResponse.port (0 = API audio). (aioesphomeapi
                    // sends 4 for API audio while api.proto's enum says 1.)
                    val subscribe = m.bool(1)
                    EspHomeLog.i("[$id] voice assistant subscribe=$subscribe flags=${m.uint32(2)}")
                    if (subscribe) {
                        voiceConn = this
                        va?.onSubscribe(true)
                    } else if (voiceConn === this) {
                        voiceConn = null
                        va?.onSubscribe(false)
                    }
                }
                Msg.VOICE_ASSISTANT_RESPONSE -> va?.onStartResponse(m.uint32(1), m.bool(2))
                Msg.VOICE_ASSISTANT_EVENT_RESPONSE -> {
                    val data = LinkedHashMap<String, String>()
                    for (d in m.messages(2)) data[d.string(1)] = d.string(2)
                    va?.onEvent(m.uint32(1), data)
                }
                Msg.VOICE_ASSISTANT_AUDIO -> Unit // TTS audio is only sent to SPEAKER devices; we play URLs
                Msg.VOICE_ASSISTANT_TIMER_EVENT -> va?.onTimer(
                    m.uint32(1), m.string(2), m.string(3), m.uint32(4), m.uint32(5), m.bool(6),
                )
                Msg.VOICE_ASSISTANT_ANNOUNCE_REQUEST -> {
                    if (va == null) {
                        reply(Msg.VOICE_ASSISTANT_ANNOUNCE_FINISHED, ProtoWriter().bool(1, false))
                    } else {
                        va.onAnnounce(m.string(1), m.string(2), m.string(3), m.bool(4))
                    }
                }
                Msg.VOICE_ASSISTANT_CONFIGURATION_REQUEST -> {
                    val cfg = va?.configuration() ?: VoiceConfiguration(emptyList(), emptyList(), 0)
                    val w = ProtoWriter()
                    for (ww in cfg.available) {
                        w.message(1, ProtoWriter().string(1, ww.id).string(2, ww.wakeWord).strings(3, ww.trainedLanguages))
                    }
                    w.strings(2, cfg.active).uint32(3, cfg.maxActive)
                    reply(Msg.VOICE_ASSISTANT_CONFIGURATION_RESPONSE, w)
                }
                Msg.VOICE_ASSISTANT_SET_CONFIGURATION -> va?.setActiveWakeWords(m.strings(1))
                else -> EspHomeLog.d("[$id] ignoring message type $type")
            }
        }

        private fun deviceInfo(): ProtoWriter {
            val d = identity()
            return ProtoWriter()
                .string(2, d.name, force = true)
                .string(3, d.macAddress, force = true)
                .string(4, d.esphomeVersion, force = true)
                .string(5, d.compilationTime, force = true)
                .string(6, d.model, force = true)
                .uint32(10, d.webserverPort)
                .string(12, d.manufacturer, force = true)
                .string(13, d.friendlyName, force = true)
                .uint32(15, bluetoothFlags())
                .string(18, bluetoothMacAddress.takeIf { bluetooth != null })
                .uint32(17, d.voiceFeatures)
                // api_encryption_supported (19) / api_encryption_provisionable (26), as an ESPHome
                // build with `api: encryption:` and no YAML key (api_connection.cpp device_info).
                .bool(19, keys != null)
                .bool(26, keys?.provisionable == true)
        }
    }
}
