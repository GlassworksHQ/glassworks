package dev.glassworks.radio

import dev.glassworks.frame.AgentCapability
import dev.glassworks.frame.BundleAgent
import dev.glassworks.frame.FrameAgent
import dev.glassworks.frame.RadioAgent
import dev.glassworks.frame.RadioFile
import dev.glassworks.frame.RadioSetting
import dev.glassworks.frame.RadioStatus
import android.util.Log
import java.io.File
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** The serial path ZHA (bellows, radio type EZSP) uses for the coordinator mode. */
fun zhaPath(panelIp: String?): String = "socket://${panelIp ?: "<panel IP>"}:${RadioReports.ZIGBEE_TCP_PORT}"

/** System properties as seen by the app (getprop/setprop; `ctl.*` controls init services). */
interface SysProps {
    fun get(name: String): String
    fun set(name: String, value: String)
    fun sleep(ms: Long) = Thread.sleep(ms)
    /** [get] without starting a process, for reads on the UI thread (the settings page's live rows). */
    fun peek(name: String): String = get(name)
}

/** Real implementation: toybox getprop/setprop as the app uid (SELinux is permissive on this firmware). */
object ShellProps : SysProps {
    override fun get(name: String) = run("getprop", name)
    override fun set(name: String, value: String) { run("setprop", name, value) }

    override fun peek(name: String): String = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String ?: ""
    } catch (_: Exception) { get(name) }

    private fun run(vararg cmd: String): String = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor(3, TimeUnit.SECONDS)
        out
    } catch (e: Exception) {
        Log.w(RadioController.TAG, "${cmd.joinToString(" ")} failed: ${e.message}")
        ""
    }
}

/**
 * Where the app keeps what decides who may reach the radio's network services (RadioPrefs on the
 * panel): Home Assistant's last address per family, and the owner's explicit list.
 */
interface AllowStore {
    /** Last IPv4 / IPv6 address of an authenticated Home Assistant ESPHome connection. */
    var haV4: String?
    var haV6: String?
    /** `Radio allowed clients`, normalized by [RadioAllow.parse]: "" = automatic (Home Assistant). */
    var explicit: String
}

class MemoryAllowStore(override var haV4: String? = null, override var haV6: String? = null, override var explicit: String = "") : AllowStore

/** Where the app keeps the chosen [ZigbeeStack] id (RadioPrefs on the panel). */
interface StackStore { var stack: String? }
class MemoryStackStore(override var stack: String? = null) : StackStore

/**
 * Radio mode manager (frames/aqara-s1-plus/docs/radio.md). Two backends:
 *  - **Root helper installed** (`init.svc.s1kiosk_radio` set): every mode is delegated to it: the app
 *    writes the agent's mode setting ([RadioSetting.MODE]), restarts the agent, and shows the helper's status.
 *  - **No helper** (today): `Off` is applied directly by stopping Aqara's radio init services;
 *    the root modes are refused with an explanatory status.
 *
 * Nothing here is persistent at the OS level without the helper: init restarts Aqara's stack on
 * boot, so [start] re-applies the stored mode after each app start (like AqaraCloud).
 * All work runs on one "s1-radio" thread; [onChange] fires after every status change.
 *
 * Zigbee router: the helper only provides a localhost EZSP bridge; the router itself is
 * [RouterHost] (ZigbeeRouterHost) in this process, run while the helper reports `ok zigbee_router`.
 * Thread router: [setThreadDataset] hands the dataset to the helper through [filesDir].
 * Firewall (helper v3): the agent's allow setting ([RadioSetting.ALLOW]) is kept equal to [RadioAllow.decide] of [allow]:
 * Home Assistant's address ([homeAssistantConnected]) unless the owner set a list ([setAllowedClients]).
 * Helper v4: the radio bundle this APK ships is installed through the root bundle service ([bundle], on
 * its own thread), the Zigbee stack choice goes to [RadioSetting.ZIGBEE_STACK] ([setZigbeeStack];
 * Silicon Labs' zigbeed is downloaded by [zigbeedFetcher] when the helper asks for it), and radio
 * firmware requests go through [firmware]. Mode changes wait while a firmware flash runs.
 */
/** The root helper as the UI needs it: installed, and its contract version (0 = none). */
data class HelperFacts(val installed: Boolean, val version: Int) {
    val v4: Boolean get() = installed && version >= RadioReports.BUNDLE_VERSION
}

class RadioController(
    /** The frame's agent: the radio capabilities and their requests ([FrameAgent.radio]). */
    private val agent: FrameAgent,
    private val load: () -> String?,
    private val store: (String) -> Unit,
    private val onChange: () -> Unit = {},
    private val inline: Boolean = false, // tests: run synchronously
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    /** The panel's LAN address, for the ZHA serial path in the status text. */
    private val panelIp: () -> String? = { null },
    /** The app's files dir (thread_router dataset hand-off, [RadioFile.THREAD_DATASET]). */
    private val filesDir: File? = null,
    /** Creates the Zigbee router host; its argument is "my status changed". */
    routerHostFactory: ((onStatus: () -> Unit) -> RouterHost)? = null,
    /** Home Assistant's last address and the owner's allow-list. */
    private val allow: AllowStore = MemoryAllowStore(),
    /** The chosen Zigbee stack (helper v4). */
    private val stackStore: StackStore = MemoryStackStore(),
    /** The signed radio bundle this APK ships (helper v4); null = none. */
    private val bundle: RadioBundle? = null,
    /** Downloads Silicon Labs' zigbeed into the given file (tests: a fake). */
    private val zigbeedFetcher: ((dest: File, progress: (String) -> Unit) -> Unit)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val TAG = "Glassworks.Radio"
        private const val SISDK_V = SisdkZigbeed.VERSION
        private val RANGE = Regex("""\(clients from ([^)]+)\)""")
        /** The helper takes a hand-off within one 3 s loop; past this, a status that never changed is its answer. */
        const val HANDOFF_MS = 60_000L
    }

    private val exec: ScheduledExecutorService? = if (inline) null else Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "s1-radio").apply { isDaemon = true }
    }
    /** Slow work (bundle install, zigbeed download) off the radio thread. */
    private val slow = if (inline) null else Executors.newSingleThreadExecutor { r -> Thread(r, "s1-radio-slow").apply { isDaemon = true } }

    /** Radio firmware updates (helper v4). */
    private val radio: RadioAgent = requireNotNull(agent.radio) { "the radio pane needs the frame agent's radio" }
    val firmware = RadioFirmware(agent, filesDir, log = log)
    @Volatile var zigbeeStack: ZigbeeStack = ZigbeeStack.parse(stackStore.stack)
        private set
    /** Why the last stack change was refused, or the zigbeed download's state. */
    @Volatile private var stackNote: String? = null
    /** Thread leave: when it was requested (and in which mode), and what to say about it. */
    @Volatile private var threadLeaveAt = 0L
    @Volatile private var threadLeaveMode: RadioMode? = null
    @Volatile private var threadLeaveNote: String? = null
    /** The last refused `Zigbee stack` change or Thread leave (shown first in `Radio helper`). */
    @Volatile private var refusedStack: String? = null
    @Volatile private var fetching = false
    @Volatile private var fetchFailedAt = 0L

    @Volatile var mode: RadioMode = RadioMode.parse(load())
        private set
    private val routerHost: RouterHost? by lazy { routerHostFactory?.invoke { refreshSoon() } }
    /** Local (no-helper) status. */
    @Volatile private var status = "starting"
    /** What the sensor shows; recomputed on the radio thread by [refresh] (spawns getprop). */
    @Volatile private var shown = "starting"
    /** Who the helper's firewall lets in now (setup channel); recomputed by [refresh]. */
    @Volatile private var allowedShown: String? = null

    /** Text for the "Radio status" sensor. Cheap: no process spawn on the caller's thread. */
    fun statusText(): String = shown
    /** `Radio helper` and `Radio firmware` texts, the firmware select's state: cached like [statusText]. */
    @Volatile private var helperShown = ""
    @Volatile private var firmwareShown = ""
    @Volatile private var fwSelectedShown = RadioFirmware.KEEP
    /**
     * [connectedNow] as the last [refresh] read it. Home Assistant's sensor shows this, not a live read: a
     * publish between refreshes could otherwise send a client that [refresh] never saw, and its leaving
     * would then never be published.
     */
    @Volatile private var connectedShown = ""
    fun connectedText(): String = connectedShown
    fun helperStatusText(): String = helperShown
    fun firmwareText(): String = firmwareShown
    fun firmwareSelected(): String = fwSelectedShown

    fun helperInstalled(): Boolean = agent.offers(AgentCapability.RADIO_MODE)

    /** Contract version of the installed helper (v1 helpers set "1"; unset = 1). */
    fun helperVersion(): Int = radio.version()

    /** Modes the select can take right now. */
    fun available(m: RadioMode) = !m.needsHelper || (helperInstalled() && helperVersion() >= m.minHelperVersion)

    /** Re-apply the stored mode (after app start). Does not bounce a helper already in that mode. */
    fun start() {
        post {
            syncAllow() // before the helper (re)starts, so it never opens the ports to the wrong list
            syncStack()
            applyMode(mode, "startup", forceHelperRestart = false)
        }
        bundle?.let { b ->
            slowly { b.installIfNewer(); refreshSoon() }
            // retried (at most hourly per version) in case the helper hadn't written its state yet, or a try failed
            exec?.scheduleWithFixedDelay({ slowly { b.installIfNewer(); refreshSoon() } }, 30, 30, TimeUnit.MINUTES)
        }
        // The helper updates its status asynchronously; poll it (and init's view of the stack).
        exec?.scheduleWithFixedDelay({ post { syncAllow(); refresh() } }, 15, 15, TimeUnit.SECONDS)
    }

    /**
     * Who the firewall lets in now, for the setup channel: "any", "" (nobody but the panel yet), or
     * space-separated addresses/CIDRs. Null when not known (Off, no helper, or a helper without the
     * firewall). Cheap.
     */
    fun allowedNow(): String? = allowedShown

    /**
     * An authenticated Home Assistant ESPHome connection (Noise with the provisioned key) subscribed
     * from [remote]. Remembers its address (one per family) and updates the allow-list if it changed.
     * Any thread.
     */
    fun homeAssistantConnected(remote: InetAddress?) = post {
        val a = RadioAllow.clientAddress(remote) ?: return@post
        val v6 = ':' in a
        val old = if (v6) allow.haV6 else allow.haV4
        if (old != a) {
            if (v6) allow.haV6 = a else allow.haV4 = a
            log("Home Assistant's ${if (v6) "IPv6" else "IPv4"} address: ${old ?: "none"} -> $a")
        }
        syncAllow()
        refresh()
    }

    /** What the app hands the helper now ([RadioSetting.ALLOW]): "" = nobody yet, "any", or addresses. */
    fun wantedAllow(): String = RadioAllow.decide(allow.explicit, allow.haV4, allow.haV6)

    /** What the agent's firewall enforces now ([RadioStatus.ALLOWED]), read on the caller's thread; null when it reports none. */
    fun enforcedAllow(): RadioReports.Allowed? = RadioReports.parseAllowed(radio.status(RadioStatus.ALLOWED))

    /** `Radio allowed clients` as stored ("" = automatic: Home Assistant only). */
    fun allowedClients(): String = allow.explicit

    /**
     * `Radio allowed clients` (HA text entity): addresses/CIDRs, `any`, or empty for automatic.
     * Returns null when stored, else why not (the old value stays). Any thread.
     */
    fun setAllowedClients(text: String): String? {
        val p = RadioAllow.parse(text)
        val v = p.value ?: return p.error
        if (allow.explicit != v) {
            allow.explicit = v
            log("radio allowed clients: ${v.ifEmpty { "automatic (Home Assistant)" }}")
        }
        post { syncAllow(); refresh() }
        return null
    }

    /**
     * "Connected now" (#217): who uses the Zigbee coordinator's EZSP port, from [RadioStatus.PEER]. Read
     * afresh on each call, without a process ([SysProps.peek]): the settings page reads it every couple of
     * seconds, and the helper every 3 s. Home Assistant's address (its last authenticated ESPHome connection)
     * is named; the time it connected is in the panel's time zone.
     */
    fun connectedNow(): String {
        if (mode != RadioMode.ZIGBEE_TCP) return "Only the Zigbee coordinator takes a client"
        return when (val p = RadioReports.parsePeer(radio.peek(RadioStatus.PEER))) {
            RadioReports.Peer.NotReported -> "Not known (the root helper doesn't report it yet)"
            RadioReports.Peer.Nobody -> "Nobody"
            is RadioReports.Peer.Connected -> {
                val name = when {
                    p.address == "127.0.0.1" || p.address == "::1" -> "The panel itself"
                    p.address == allow.haV4 || p.address == allow.haV6 -> "Home Assistant"
                    else -> null
                }
                listOfNotNull(name, p.address, "since ${since(p.sinceEpochSeconds * 1000)}").joinToString(" · ")
            }
        }
    }

    /** "14:32" today, else "14 Nov 14:32". */
    private fun since(ms: Long): String {
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT)
        val today = day.format(java.util.Date(ms)) == day.format(java.util.Date(clock()))
        return java.text.SimpleDateFormat(if (today) "HH:mm" else "d MMM HH:mm", java.util.Locale.ENGLISH).format(java.util.Date(ms))
    }

    /** Radio thread (or inline): recompute [shown]; notifies [onChange] when it changed. */
    fun refresh() {
        val helper = helperInstalled()
        syncRouterHost(helper)
        if (helper) zigbeedHandOff()
        firmware.cleanup()
        // Before the status: a hand-off made after this read waits for the next refresh, so its
        // baseline is never a status read before it existed.
        val pending = handOff.get()
        // Status first, then the allow-list: the helper writes them in the opposite order, so the list
        // is never older than the status. Read once, so the status text and allowedNow() agree.
        val raw = if (helper) radio.status(RadioStatus.MODE) else ""
        val allowed = if (helper) RadioReports.parseAllowed(radio.status(RadioStatus.ALLOWED)) else null
        allowedShown = allowed?.takeIf { RadioReports.parseStatus(raw)?.mode != RadioMode.OFF }
            ?.let { if (it.any) RadioAllow.ANY else it.addresses.joinToString(" ") }
        helperFactsShown = HelperFacts(helper, if (helper) helperVersion() else 0)
        helperFactsKnown = true
        threadStoredShown = if (!helper) null else when (radio.status(RadioStatus.THREAD_STORED).trim()) {
            "yes" -> true
            "no" -> false
            else -> null
        }
        threadShown = RadioReports.parseStatus(raw)
            ?.takeIf { mode == RadioMode.THREAD_ROUTER && it.mode == RadioMode.THREAD_ROUTER && it.state == "ok" }
            ?.let { ThreadRouterState.parse(it.detail) }
        var s = if (helper) helperStatusText(raw, allowed) else status
        if (s.length > 250) s = s.take(249) + "…" // ESPHome text sensors are capped at 255 bytes
        pending?.let { h ->
            // After the status read: a file still there means that status is from before the take.
            val gone = filesDir == null || !File(filesDir, radio.file(RadioFile.THREAD_DATASET)).exists()
            val late = clock() - h.since > HANDOFF_MS
            // Only the record looked at: a dataset handed over meanwhile (any thread) stays pending.
            handOff.compareAndSet(h, when {
                // Until the helper takes the file, any status is still the old network's: it's the new baseline.
                !gone -> h.copy(status = s, lost = late)
                h.status != null && s != h.status -> null
                // First seen taken: the wait for the helper's answer starts now, not at the hand-off.
                !h.taken -> h.copy(taken = true, since = clock(), lost = false)
                // Unchanged a minute after the take: that is the answer (a re-join can end where it was).
                late -> null
                else -> h
            })
        }
        val h = helperText().let { if (it.length > 250) it.take(249) + "…" else it }
        val fw = firmware.statusText().let { if (it.length > 250) it.take(249) + "…" else it }
        val sel = firmware.selected()
        val c = connectedNow()
        if (s != shown || h != helperShown || fw != firmwareShown || sel != fwSelectedShown || c != connectedShown) {
            shown = s; helperShown = h; firmwareShown = fw; fwSelectedShown = sel; connectedShown = c
            onChange()
        }
    }

    fun stop() {
        exec?.shutdownNow()
        slow?.shutdownNow()
        routerHost?.stop()
    }

    /**
     * Whether a Thread router network is stored (what `Leave Thread network` deletes): true/false as the
     * helper reports it ([RadioStatus.THREAD_STORED]), null when it doesn't say (older bundles, no
     * helper). Cached by [refresh], cheap.
     */
    fun threadStored(): Boolean? = threadStoredShown

    /** The Thread router's state from the helper's status (cached by [refresh]); null outside that mode. */
    fun threadRouterState(): ThreadRouterState? = threadShown

    /**
     * Where the last dataset hand-off ([setThreadDataset]) stands, as of the last [refresh]. The status
     * has no timestamp, so until the helper answers, it may still be the previous network's.
     */
    enum class ThreadHandOff {
        /** None, or the helper answered: the status is about the dataset handed over. */
        ANSWERED,
        /**
         * The helper hasn't taken the file, or no status since then differs from the last one [refresh]
         * saw before it took it (for at most [HANDOFF_MS] after the take, also when [refresh] saw none).
         * The helper takes the file before it writes a new status.
         */
        PENDING,
        /** The file is still there after [HANDOFF_MS]: the helper isn't taking it (stopped, or another mode). */
        NOT_TAKEN,
    }

    fun threadHandOff(): ThreadHandOff = handOff.get().let {
        when {
            it == null -> ThreadHandOff.ANSWERED
            it.lost -> ThreadHandOff.NOT_TAKEN
            else -> ThreadHandOff.PENDING
        }
    }

    /**
     * [status]: the last status [refresh] read while the file was still there, null before the first.
     * [since]: the hand-off, then the first [refresh] that found the file taken ([taken]).
     */
    private data class HandOff(val status: String?, val since: Long, val taken: Boolean = false, val lost: Boolean = false)
    private val handOff = java.util.concurrent.atomic.AtomicReference<HandOff?>()

    /** Helper installed and its contract version, as of the last [refresh] (for the UI thread: no getprop). */
    fun helperShown(): HelperFacts = helperFactsShown

    /** [helperShown] once the radio thread has refreshed at least once, else null (not known yet). */
    fun helperShownIfKnown(): HelperFacts? = if (helperFactsKnown) helperFactsShown else null
    @Volatile private var helperFactsKnown = false

    @Volatile private var helperFactsShown = HelperFacts(installed = false, version = 0)
    @Volatile private var threadStoredShown: Boolean? = null
    @Volatile private var threadShown: ThreadRouterState? = null

    /** The helper implements contract v4 (bundles, Zigbee stack, radio firmware). */
    fun helperV4(): Boolean = helperInstalled() && helperVersion() >= RadioReports.BUNDLE_VERSION

    /**
     * `Zigbee stack` (HA select). Silicon Labs' stack needs helper v4; it is used by the coordinator
     * mode only. [done] as for [request].
     */
    fun setZigbeeStack(label: String, done: ((Boolean) -> Unit)? = null) = post {
        val z = ZigbeeStack.fromLabel(label) ?: ZigbeeStack.entries.firstOrNull { it.id == label }
        if (z == null) { done?.invoke(false); return@post }
        if (z == zigbeeStack) { done?.invoke(true); return@post }
        val why = when {
            firmware.flashing() -> "a radio firmware update is running; try again when it's done"
            z != ZigbeeStack.AQARA && !helperV4() ->
                "needs the root helper v${RadioReports.BUNDLE_VERSION} (installed: ${if (helperInstalled()) "v${helperVersion()}" else "none"}): " +
                    "update the panel's boot image once with the web installer"
            else -> null
        }
        if (why != null) {
            refusedStack = "Zigbee stack “${z.label.substringBefore(" (")}” refused: $why"; log(refusedStack!!)
            refresh(); onChange() // the select snaps back
            done?.invoke(false); return@post
        }
        zigbeeStack = z; stackStore.stack = z.id; stackNote = null; refusedStack = null
        log("zigbee stack: ${z.label}")
        syncStack()
        if (mode == RadioMode.ZIGBEE_TCP && helperInstalled()) {
            radio.restart()
            log("helper restarted for the Zigbee stack change")
        }
        refresh()
        done?.invoke(true)
    }

    /** `Radio firmware image` (HA select): arms an image for [RadioFirmware.ARM_MS]. */
    fun firmwareSelect(option: String) = post { firmware.select(option); refresh() }

    /** `Flash radio firmware` (HA button): only flashes an armed image. */
    fun firmwareFlash() = post { firmware.flash(); refresh() }

    /** Text for the `Radio helper` diagnostic sensor. */
    fun helperText(): String = listOfNotNull(refusedStack, helperBase()).joinToString(". ")

    private fun helperBase(): String {
        if (!helperInstalled()) return "not installed: the panel's boot image has no root helper, so only Off works (see frames/aqara-s1-plus/docs/radio.md)"
        val v = helperVersion()
        if (v < RadioReports.BUNDLE_VERSION) {
            return "v$v from the boot image. Update the boot image once with the web installer: after that, helper fixes, " +
                "new radio stacks and radio firmware come with app updates"
        }
        val src = radio.status(RadioStatus.SOURCE).ifEmpty { "?" }
        val prop = radio.status(RadioStatus.BUNDLE)
        // A rejected bundle, with the why from the property. RadioBundle reads only the 0.4.3 image's record; on the frame
        // loader image (its four-field record) the property alone says it, as it did before. Wording only.
        val why = BundleAgent.rejectedWhy(prop)
        fun rejected(w: String?) = "rejected" + w?.ifEmpty { null }?.let { " ($it)" }.orEmpty()
        val inst = bundle?.installed()?.let { if (it.state == "bad") rejected(why) else "${it.state} ${it.version}" }
            ?: why?.let(::rejected) ?: prop.ifEmpty { "?" }
        val shipped = bundle?.shippedVersion()
        val last = bundle?.lastResult?.takeIf { it.startsWith("error") || it.startsWith("no answer") || it.startsWith("couldn't") }
        return listOfNotNull(
            "v$v, running the $src helper",
            "radio bundle on the panel: $inst",
            shipped?.let { "in this app: $it" },
            last?.let { "last install: $it" },
        ).joinToString("; ")
    }

    /**
     * `Leave Thread network` (HA button, helper v4, any mode): the helper detaches a running Thread
     * router and deletes the stored dataset (the network key) and the router's settings. A dataset the
     * app hasn't handed over yet is deleted here first.
     */
    fun threadLeave() = post {
        if (!helperV4()) {
            refusedStack = "Leave Thread network refused: needs the root helper v${RadioReports.BUNDLE_VERSION} " +
                "(installed: ${if (helperInstalled()) "v${helperVersion()}" else "none"}): update the panel's boot image once"
            log(refusedStack!!); refresh(); onChange(); return@post
        }
        val dir = filesDir ?: return@post
        File(dir, radio.file(RadioFile.THREAD_DATASET)).delete()
        val f = File(dir, radio.file(RadioFile.THREAD_LEAVE))
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, ".thread-leave.tmp")
            tmp.writeText("leave\n")
            tmp.setReadable(false, false); tmp.setReadable(true, true)
            if (!tmp.renameTo(f)) throw java.io.IOException("rename failed")
            threadLeaveAt = System.currentTimeMillis(); threadLeaveMode = mode
            threadLeaveNote = "leaving the Thread network…"
            log("Thread leave requested")
        } catch (e: Exception) {
            threadLeaveNote = "couldn't request the Thread leave: ${e.message}"
        }
        refresh()
    }

    /** `Join Zigbee network` (HA button): the router host scans and joins a network open for joining. */
    fun routerJoin() = post {
        if (mode != RadioMode.ZIGBEE_ROUTER) { log("router join ignored: mode is ${mode.label}"); return@post }
        routerHost?.requestJoin()
    }

    /** `Leave Zigbee network` (HA button). */
    fun routerLeave() = post {
        if (mode != RadioMode.ZIGBEE_ROUTER) { log("router leave ignored: mode is ${mode.label}"); return@post }
        routerHost?.requestLeave()
    }

    /**
     * Thread router: the active dataset to join with, hex TLVs as Home Assistant exports them. Written
     * owner-only to the app's files dir, where the root helper picks it up within seconds (and deletes
     * it). Never logged. Returns null when stored, else why not.
     */
    fun setThreadDataset(hex: String): String? {
        // Invisible characters a copy carries (spaces, line breaks, no-break and zero-width spaces) are dropped.
        ThreadDataset.problem(hex)?.let { return it }
        val d = ThreadDataset.clean(hex)
        val dir = filesDir ?: return "No place to store the dataset."
        val f = File(dir, radio.file(RadioFile.THREAD_DATASET))
        return try {
            f.parentFile?.let { d ->
                d.mkdirs()
                d.setReadable(false, false); d.setReadable(true, true)
                d.setWritable(false, false); d.setWritable(true, true)
                d.setExecutable(false, false); d.setExecutable(true, true)
            }
            val tmp = File(f.parentFile, ".thread-dataset.tmp")
            tmp.writeText(d)
            tmp.setReadable(false, false); tmp.setReadable(true, true)
            tmp.setWritable(false, false); tmp.setWritable(true, true)
            if (!tmp.renameTo(f)) throw java.io.IOException("rename failed")
            handOff.set(HandOff(null, clock())) // [shown] may be older than the helper's status now
            log("Thread dataset handed to the helper (${d.length / 2} bytes)")
            refreshSoon()
            null
        } catch (e: Exception) {
            "Couldn't store the dataset: ${e.message}"
        }
    }

    /**
     * "Join Home Assistant's Thread network" (auth/ThreadJoin): the dataset HA gave, then *Thread
     * router* mode if the panel isn't in it yet. Returns null when accepted, else why not.
     */
    fun joinThreadNetwork(hex: String): String? {
        if (!available(RadioMode.THREAD_ROUTER)) {
            return "Thread router needs a newer root helper on the panel (see the Radio helper sensor)"
        }
        // request() would refuse the mode switch after the dataset was stored, and the join would look done.
        if (mode != RadioMode.THREAD_ROUTER && firmware.flashing()) {
            return "a radio firmware update is running; try again when it's done"
        }
        setThreadDataset(hex)?.let { return it }
        if (mode != RadioMode.THREAD_ROUTER) request(RadioMode.THREAD_ROUTER.label, "thread-join")
        return null
    }

    /**
     * From the `Radio mode` select (and the setup screen's radio step). Unknown labels are ignored;
     * unavailable modes are refused. [done] runs on the radio thread once the request was handled:
     * true = accepted and applied (see [statusText] for how it went), false = refused/unknown, with
     * the reason in [statusText].
     */
    fun request(label: String, why: String = "select", done: ((Boolean) -> Unit)? = null) {
        pendingRequests.incrementAndGet()
        try {
            post { try { requestNow(label, why, done) } finally { pendingRequests.decrementAndGet() } }
        } catch (t: Throwable) {
            pendingRequests.decrementAndGet() // never queued (the radio thread was stopped): nothing pending
            throw t
        }
    }

    /**
     * A mode request is queued or running on the radio thread and hasn't set [mode] yet: a caller that
     * reads [mode] to decide whether to send Off must send it anyway (the queued request would otherwise
     * start the radio after an Off that was never sent).
     */
    val requestPending: Boolean get() = pendingRequests.get() > 0
    private val pendingRequests = java.util.concurrent.atomic.AtomicInteger()

    private fun requestNow(label: String, why: String, done: ((Boolean) -> Unit)?) {
        val m = RadioMode.fromLabel(label) ?: run {
            log("unknown radio mode '$label'")
            done?.invoke(false)
            return
        }
        if (firmware.flashing()) {
            status = "a radio firmware update is running; ${m.label} can be chosen when it's done. Still in ${mode.label}."
            log("refused '${m.label}': firmware flash in progress")
            refresh(); onChange(); done?.invoke(false)
            return
        }
        if (!available(m)) {
            status = if (!helperInstalled()) {
                "${m.label} needs the root helper (init service ${radio.name} is not installed; " +
                    "see frames/aqara-s1-plus/docs/radio.md). Still in ${mode.label}."
            } else {
                "${m.label} needs the root helper v${m.minHelperVersion} or newer (installed: v${helperVersion()}; " +
                    "update the panel's boot image). Still in ${mode.label}."
            }
            log("refused '${m.label}': helper missing or too old")
            refresh()
            onChange() // the select snaps back to the current mode
            done?.invoke(false)
            return
        }
        mode = m
        if (load() != m.id) store(m.id) // also normalizes labels/retired values stored by older builds
        syncAllow()
        applyMode(m, why, forceHelperRestart = true)
        done?.invoke(true)
    }

    /** Recompute [statusText] soon (radio thread), e.g. while the setup screen waits for the helper. */
    fun refreshSoon() = post { refresh() }

    /** Runs [block] on the radio thread after everything posted before it. */
    fun afterPending(block: () -> Unit) = post(block)

    // ------------------------------------------------------------------ internals

    private fun slowly(block: () -> Unit) {
        val e = slow
        if (e == null) block() else e.execute { try { block() } catch (t: Throwable) { log("radio background task failed: $t") } }
    }

    /** Hands the chosen stack to a v4 helper (persistent, so it applies at boot before the app runs). */
    private fun syncStack() {
        if (!helperV4()) return
        if (radio.setting(RadioSetting.ZIGBEE_STACK) != zigbeeStack.id) radio.set(RadioSetting.ZIGBEE_STACK, zigbeeStack.id)
    }

    /**
     * Silicon Labs' zigbeed: download it when the helper asks ("sisdk missing"), at most every 30 min
     * after a failure; delete the app's copy once the helper installed it ("sisdk ready").
     */
    private fun zigbeedHandOff() {
        val z = radio.status(RadioStatus.ZIGBEE)
        val f = filesDir?.let { File(it, radio.file(RadioFile.ZIGBEED)) } ?: return
        if (z.startsWith("sisdk ready")) { if (f.exists()) f.delete(); if (!fetching) stackNote = null; return }
        if (z.startsWith("sisdk bad") && f.exists() && !fetching) {
            // the helper refused our download (it re-reads it every 3 s otherwise): drop it, retry later
            f.delete(); fetchFailedAt = System.currentTimeMillis()
            stackNote = "the root helper refused the zigbeed download (${z.removePrefix("sisdk bad").trim()}); retrying in 30 minutes"
            log(stackNote!!); return
        }
        if (!z.startsWith("sisdk missing") || zigbeeStack != ZigbeeStack.SISDK) return
        if (f.exists() || fetching) return // waiting for the helper to take it (every 3 s)
        if (fetchFailedAt != 0L && System.currentTimeMillis() - fetchFailedAt < 30 * 60_000) return
        val fetch = zigbeedFetcher ?: return
        fetching = true
        slowly {
            try {
                fetch(f) { stackNote = it; refreshSoon() }
                fetchFailedAt = 0L
            } catch (e: Exception) {
                f.delete()
                fetchFailedAt = System.currentTimeMillis()
                stackNote = "zigbeed $SISDK_V download failed (${e.message}); retrying in 30 minutes"
                log(stackNote!!)
            } finally {
                fetching = false
                refreshSoon()
            }
        }
    }

    private fun post(block: () -> Unit) {
        val e = exec
        if (e == null) block() else e.execute {
            try { block() } catch (t: Throwable) { log("radio task failed: $t") }
        }
    }

    /**
     * Keeps [RadioSetting.ALLOW] equal to what [allow] decides. Written whenever a helper is
     * installed (a v2 helper ignores it); the helper re-reads it every 3 s, no restart needed.
     */
    private fun syncAllow() {
        if (!helperInstalled()) return
        val want = wantedAllow()
        if (radio.setting(RadioSetting.ALLOW) != want) {
            radio.set(RadioSetting.ALLOW, want)
            log("radio allow-list for the helper: ${want.ifEmpty { "(none: loopback only)" }}")
        }
    }

    /** "Who may connect" for the status text, or null when the helper has no firewall to report. */
    private fun whoText(allowed: RadioReports.Allowed?): String {
        val v = helperVersion()
        if (v < RadioReports.FIREWALL_VERSION) return "any client (root helper v$v has no firewall; update it)"
        val a = allowed ?: return "? (firewall not reported yet)"
        return RadioAllow.describe(a, allow.explicit.isNotEmpty(), allow.haV4, allow.haV6)
    }

    private fun applyMode(m: RadioMode, why: String, forceHelperRestart: Boolean) {
        if (helperInstalled()) {
            applyViaHelper(m, why, forceHelperRestart)
            refresh()
            return
        }
        if (m.needsHelper) {
            // The helper was removed (or an image without it): keep the choice, apply Off meanwhile.
            log("stored mode '${m.label}' unavailable ($why): no root helper, applying Off")
            val failed = radio.applyVendor(RadioMode.OFF.id, log)
            status = "${m.label} needs the root helper (${radio.name} not installed); radio is Off" +
                if (failed.isEmpty()) "." else " (not stopped: ${failed.joinToString()})."
            refresh()
            return
        }
        status = "${m.label}: applying…"
        refresh()
        val failed = radio.applyVendor(m.id, log)
        status = when {
            failed.isNotEmpty() -> "error: ${failed.joinToString { "$it=${radio.vendorState(it).ifEmpty { "?" }}" }} " +
                "(wanted ${m.label})"
            else -> "Off: Aqara's radio services stopped, radio idle. Other modes need the root helper " +
                "(${radio.name}, not installed)."
        }
        log("mode ${m.label} ($why): $status")
        refresh()
    }

    private fun applyViaHelper(m: RadioMode, why: String, forceRestart: Boolean) {
        val current = radio.setting(RadioSetting.MODE)
        if (current != m.id) radio.set(RadioSetting.MODE, m.id)
        val helperState = radio.state()
        val helperMode = RadioReports.parseStatus(radio.status(RadioStatus.MODE))?.mode
        if (forceRestart || current != m.id || helperState != "running" || helperMode != m) {
            radio.restart()
            log("helper restarted for ${m.label} ($why)")
        }
        status = "helper: ${m.label}"
    }

    private fun helperStatusText(raw: String, allowed: RadioReports.Allowed?): String {
        val st = RadioReports.parseStatus(raw)
            ?: return "Root helper ${radio.state()}, no status yet (wanted ${mode.label})"
        val label = st.mode?.label ?: "?"
        val inMode = st.mode == mode && st.state == "ok"
        val tail = when {
            // The helper only runs the bridge; the router's state is the host's.
            inMode && mode == RadioMode.ZIGBEE_ROUTER && routerHost != null -> ": ${routerHost!!.status}"
            inMode && mode == RadioMode.ZIGBEE_TCP -> {
                val range = RANGE.find(st.detail)?.groupValues?.get(1)?.let { " (socat range $it)" } ?: ""
                val z2m = if ("EZSP 18" in st.detail) {
                    " Silicon Labs zigbeed $SISDK_V (EZSP 18). Zigbee2MQTT: adapter ember, port tcp://${panelIp() ?: "<panel IP>"}:${RadioReports.ZIGBEE_TCP_PORT}."
                } else ""
                ": listening on :${RadioReports.ZIGBEE_TCP_PORT} for ${whoText(allowed)}$range.$z2m ZHA: radio type EZSP, serial path " +
                    zhaPath(panelIp())
            }
            // waiting for Silicon Labs' zigbeed: say how the download goes
            st.state == "applying" && "waiting for zigbeed $SISDK_V" in st.detail -> ": ${st.detail}${stackNote?.let { "; $it" } ?: ""}"
            st.state == "error" && stackNote != null && st.mode == RadioMode.ZIGBEE_TCP -> ": ${st.detail}; $stackNote"
            // Thread router: the role and the network's name, said plainly (never keys or ids).
            inMode && mode == RadioMode.THREAD_ROUTER && ThreadRouterState.parse(st.detail) != null ->
                ": ${ThreadRouterState.parse(st.detail)!!.describe()}"
            // Border router: its REST API (which exposes the network key) is behind the same firewall.
            inMode && mode == RadioMode.THREAD && "REST :" in st.detail -> ": ${st.detail}; REST open to ${whoText(allowed)}"
            st.detail.isNotEmpty() -> ": ${st.detail}"
            else -> ""
        }
        val mismatch = if (st.mode != mode && st.state != "applying") " (wanted ${mode.label})" else ""
        // A Silicon Labs choice the helper can't act on (helper downgraded to < v4), or a refused change
        val stack = when {
            zigbeeStack == ZigbeeStack.SISDK && !helperV4() && mode == RadioMode.ZIGBEE_TCP ->
                " (Zigbee stack: Silicon Labs needs the root helper v${RadioReports.BUNDLE_VERSION}; running Aqara's)"
            else -> ""
        }
        return "${st.state} $label$tail$mismatch$stack${threadLeaveSuffix()}"
    }

    /** " (Thread network left: …)" after a Leave in this mode; the helper's answer once it is there. */
    private fun threadLeaveSuffix(): String {
        if (threadLeaveNote == null) return ""
        if (threadLeaveMode != mode) { threadLeaveNote = null; return "" }   // a later mode change: old news
        val raw = radio.status(RadioStatus.THREAD_LEAVE).trim()
        val ts = raw.substringAfter(' ').substringBefore(':').toLongOrNull()
        if (ts != null && ts * 1000 >= threadLeaveAt - 5_000) {
            val detail = raw.substringAfter(": ", "")
            threadLeaveNote = if (raw.startsWith("ok ")) "Thread network left: $detail" else "Thread leave failed: $detail"
        } else if (System.currentTimeMillis() - threadLeaveAt > 30_000) {
            threadLeaveNote = "Thread leave: no answer from the root helper"
            File(filesDir ?: return "", radio.file(RadioFile.THREAD_LEAVE)).delete()
        }
        return " ($threadLeaveNote)"
    }

    /** Runs the router host exactly while the helper's router bridge is up. */
    private fun syncRouterHost(helper: Boolean) {
        val h = routerHost ?: return
        val st = if (helper) RadioReports.parseStatus(radio.status(RadioStatus.MODE)) else null
        if (mode == RadioMode.ZIGBEE_ROUTER && st?.mode == RadioMode.ZIGBEE_ROUTER && st.state == "ok") h.start() else h.stop()
    }
}
