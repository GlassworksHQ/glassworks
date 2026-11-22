package dev.glassworks.radio

import dev.glassworks.frame.FrameAgent
import dev.glassworks.frame.Capability
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import dev.glassworks.pane.PaneState
import dev.glassworks.pane.PaneChoice
import dev.glassworks.pane.SetupHost
import dev.glassworks.pane.SetupOp
import dev.glassworks.pane.SetupScreen
import dev.glassworks.pane.SetupStep
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import dev.glassworks.auth.HaAuth
import dev.glassworks.auth.ThreadJoin
import dev.glassworks.discovery.HaUrls
import dev.glassworks.radio.ui.RadioFollowUp
import dev.glassworks.radio.ui.RadioSettingsView
import dev.glassworks.radio.ui.RadioTwin
import dev.glassworks.radio.ui.RadioSetupScreen
import dev.glassworks.radio.ui.RadioStep

/** The controller of the running [RadioPane], null while none runs: the app's [RadioDefinition] reads it. */
class RunningRadio {
    @Volatile var controller: RadioController? = null
}

/**
 * What the radio's setup step and settings section read. The app's comes from the running
 * [RadioController] ([RunningRadio]); the mock-ups give fixed values.
 */
interface RadioReadout {
    /** Null while the kiosk service (and so the controller) isn't running. */
    fun controller(): RadioController?

    /** The controller runs: the settings' picker and follow-up card can drive it. */
    fun running(): Boolean = controller() != null

    /** The mode in effect: the controller's, else the stored one. */
    fun mode(): RadioMode

    /** The radio as the follow-up cards and the settings picker show it; null without a controller. */
    fun facts(): RadioFollowUp.Facts?

    /** Home Assistant's address, for the follow-up cards' links. */
    fun haBase(): String?

    /** The panel's LAN address, for the addresses Home Assistant needs. */
    fun panelIp(): String?

    /** The owner's allow-list as typed ("" = Home Assistant only), as `Radio allowed clients` shows it. */
    fun allowedClients(): String = controller()?.allowedClients().orEmpty()

    /** Who the helper's firewall lets in now; null when no firewall is active. */
    fun allowedNow(): String? = controller()?.allowedNow()

    /** "Connected now" read afresh (#217), for the page's live row; null without a controller. */
    fun connectedNow(): String? = controller()?.connectedNow()
}

/**
 * The radio pane ([RadioPane]) for the whole process: the setup step (docs/onboarding.md, "Radio"),
 * the settings menu's Radio section, and the requests both send to the [RadioController]. Built once
 * by the app (S1Panes.paneDefinitions).
 */
class RadioDefinition(
    /** The radio namespace's settings (`kiosk.xml`), the step's two flags included. */
    private val prefs: RadioPrefs,
    /** The panel shows any trace of an earlier setup (SetupState.setUpBefore): the core's facts. */
    private val setUpBefore: () -> Boolean,
    internal val readout: RadioReadout,
    /** Where the running [RadioPane] puts its controller ([pane]); the app's [readout] reads it there ([app]). */
    val running: RunningRadio,
    /** "Join Home Assistant's Thread network": the panel's one join, whoever starts it. */
    private val threadJoin: () -> ThreadJoin = { HaAuth.threadJoin },
    /** The panel is signed in to Home Assistant (the setup channel's `ha.signedIn`). */
    private val signedIn: () -> Boolean = { HaAuth.signedIn },
    /** The frame's agent, which the running [RadioPane]'s controller talks to the radio through ([pane]). */
    private val agent: FrameAgent? = null,
) : PaneDefinition {

    override val id = "radio"
    override val requires = setOf(Capability.SILABS_RADIO)

    private val main by lazy { Handler(Looper.getMainLooper()) }

    init {
        migrate()
    }

    // ------------------------------------------------------------------ setup step

    /** The step was answered (a mode applied, or skipped = Off). */
    var stepDone: Boolean
        get() = prefs.setupDone
        set(v) { prefs.setupDone = v }

    /**
     * Once per install of a build with the radio step: a panel that was already set up before it never
     * sees the step. Runs when the definition is made, which the app does before discovery can save an
     * HA URL in this process (KioskService.onCreate, MainActivity.onCreate); later runs do nothing.
     */
    private fun migrate() {
        if (prefs.setupMigrated) return
        val done = setUpBefore() || prefs.mode != null
        prefs.setupMigrated = true
        if (done) prefs.setupDone = true
    }

    /**
     * The radio's setup sub-step shows the follow-up card for the mode it just applied: the step stays
     * current until Continue there. In memory only: after a restart there is nothing left to show.
     */
    @Volatile var followUpOpen = false

    /**
     * Settings' switch turned on while the radio is off: the picker shows, and the radio stays off until a
     * role is picked (#63 round 2). Cleared once a mode is on, or the switch goes off.
     */
    @Volatile var choosing = false

    /** The Manage page's radio requests, one at a time as on the panel's card (#269). */
    internal val twinGuard = RadioTwin.Guard()

    /**
     * Setup's radio step is being answered: from its Continue (or "Change the mode") until its
     * follow-up's Continue. The controller stores the mode before the helper answers, so without this the
     * 2 s setup tick read the step as done mid-apply and showed Done over a failure (verifier 4bc205dc).
     */
    @Volatile var answering = false

    val step: SetupStep = object : SetupStep {
        override val id = "radio"
        override val title = "Radio"
        override val reason = "radio mode not chosen"

        /**
         * Answered in setup (the mode, then its follow-up) or over the setup channel, or a mode was
         * chosen with HA's `Radio mode` select.
         */
        override fun done() = (stepDone || prefs.mode != null) && !followUpOpen && !answering

        override fun channelState() = channelJson()

        override val ops = mapOf(
            "set_mode" to SetupOp { setMode(it.optString("mode", "")) },
            "set_allow" to SetupOp { setAllow(it.optString("allow", "")) },
            "thread_join" to SetupOp { joinThread() },
        )

        /** What the S1's Kiln (kiln/packages/setup-channel) sends. */
        override val legacyOps = mapOf("set_radio" to "set_mode", "set_radio_allow" to "set_allow")

        override fun screen(context: Context, host: SetupHost): SetupScreen = RadioSetupScreen(context, host, this@RadioDefinition)
    }

    override val setupSteps = listOf(step)

    /**
     * The Panes step's radio card: on means a mode other than Off. Off applies Off through [setMode] and
     * answers the step; on leaves the mode to the radio's own sub-step under Panes.
     */
    override val setupChoice = PaneChoice(
        title = "Zigbee / Thread radio",
        detail = "For your devices",
        on = { readout.mode() != RadioMode.OFF },
        // Off on Panes also lets go of the radio's own step, half answered or not.
        apply = { on -> if (on) null else { answering = false; followUpOpen = false; applyChoice(RadioMode.OFF.id) } },
    )

    /**
     * The mode in effect already, and running or on its way: the step is answered without asking the
     * radio again. A mode that failed or was refused (the controller stores it before applying) asks again.
     */
    private fun applyChoice(id: String): String? = keepOrSet(id, "setup screen")

    /**
     * [applyChoice]'s rule for any [source]: a request for the mode in effect would restart the helper for
     * nothing. Not while another request is queued: the mode read here isn't the one that will run, so
     * this one goes after it (as the settings switch's Off does).
     */
    private fun keepOrSet(id: String, source: String): String? {
        val r = readout.controller()
        val mode = readout.mode()
        if (r != null && !r.requestPending && mode.id == id &&
            RadioStep.classify(mode, true, r.statusText()) in setOf(RadioStep.Result.OK, RadioStep.Result.APPLYING)
        ) { stepDone = true; return null }
        return setModeFrom(id, source)
    }

    // ------------------------------------------------------------------ setup channel

    @Volatile private var helper = false
    @Volatile private var helperAt = 0L

    /** helperInstalled() spawns getprop: not on every 1.5 s poll of the setup channel. */
    private fun helperInstalled(): Boolean {
        val r = readout.controller() ?: return false
        val now = SystemClock.elapsedRealtime()
        if (helperAt == 0L || now - helperAt > HELPER_CACHE_MS) {
            helper = r.helperInstalled()
            helperAt = now
        }
        return helper
    }

    /** The `radio` part of the setup channel's state (channel.ts `SetupState.radio`). */
    internal fun channelJson(): JSONObject {
        val r = readout.controller()
        val mode = readout.mode()
        return channelJson(
            mode = mode.id,
            status = r?.statusText() ?: "The radio controller isn't running yet",
            helper = helperInstalled(),
            allowed = r?.allowedNow(),
            answered = step.done(),
            threadJoin = if (mode != RadioMode.THREAD_ROUTER) null else threadJoinJson(
                mode, threadJoin().state, r?.threadHandOff() ?: RadioController.ThreadHandOff.ANSWERED,
                r?.threadRouterState(), r?.statusText(), readout::panelIp,
            ),
        )
    }

    /**
     * Through RadioController.request, like the panel's radio step: waits for the controller to take the
     * request, then up to [RADIO_FOLLOW_MS] for it to report how it went (the client waits 30 s), and
     * marks the step answered on success. The mode in effect already only answers the step ([keepOrSet]):
     * Kiln sends it for a fresh panel kept on Off.
     */
    internal fun setMode(id: String): String? = keepOrSet(id, "the installer")

    /** [setMode], saying in the log where the request came from ([source]). */
    internal fun setModeFrom(id: String, source: String): String? {
        val mode = RadioMode.fromId(id)?.takeIf { m -> RadioStep.OPTIONS.any { it.mode == m } }
            ?: return "Unknown radio mode '$id'."
        val r = readout.controller() ?: return CHANNEL_NOT_RUNNING
        val accepted = AtomicBoolean(false)
        val handled = CountDownLatch(1)
        Log.i(TAG, "radio mode ${mode.label} from $source")
        r.request(mode.label, "installer") { ok -> accepted.set(ok); handled.countDown() }
        if (!handled.await(RADIO_ACCEPT_MS, TimeUnit.MILLISECONDS)) return "The radio didn't answer in time."
        if (!accepted.get()) return r.statusText()
        val until = SystemClock.elapsedRealtime() + RADIO_FOLLOW_MS
        while (true) {
            val status = r.statusText()
            when (RadioStep.classify(mode, true, status)) {
                RadioStep.Result.OK -> break
                RadioStep.Result.REFUSED, RadioStep.Result.ERROR -> return status
                // Still applying (root helper): the panel's step lets you go on too; the status follows.
                RadioStep.Result.APPLYING -> if (SystemClock.elapsedRealtime() > until) break
            }
            r.refreshSoon()
            Thread.sleep(500)
        }
        stepDone = true // the panel's own radio step doesn't ask again
        return null
    }

    /** Who may reach the radio's TCP services, as the `Radio allowed clients` entity. */
    internal fun setAllow(text: String): String? {
        val r = readout.controller() ?: return CHANNEL_NOT_RUNNING
        r.setAllowedClients(text)?.let { return it }
        Log.i(TAG, "radio allowed clients from the installer")
        // The helper re-reads the list within 3 s; give the reply the enforced state.
        val until = SystemClock.elapsedRealtime() + 5_000
        val want = r.wantedAllow()
        // Poll the helper's one prop here (this isn't the radio thread), then refresh once.
        while (SystemClock.elapsedRealtime() < until) {
            Thread.sleep(500)
            val a = r.enforcedAllow() ?: break // Off / no firewall
            if ((if (a.any) RadioAllow.ANY else a.addresses.joinToString(" ")) == want) break
        }
        val refreshed = CountDownLatch(1)
        r.refreshSoon()
        r.afterPending { refreshed.countDown() } // the status text in the reply names the new list
        refreshed.await(3, TimeUnit.SECONDS)
        return null
    }

    /**
     * `radio.thread_join` (#166): starts the join from Home Assistant, as the panel's and HA's join buttons
     * do, and answers at once. Its progress is the state's `threadJoin`. A join already running is left to
     * run. Refused outside Thread router, and without the panel's sign-in (it fetches the dataset with it).
     */
    internal fun joinThread(): String? {
        readout.controller() ?: return CHANNEL_NOT_RUNNING
        if (readout.mode() != RadioMode.THREAD_ROUTER) return JOIN_NOT_ROUTER
        if (!signedIn()) return JOIN_NOT_SIGNED_IN
        startThreadJoin("the installer")
        return null
    }

    private fun startThreadJoin(source: String) {
        Log.i(MAIN_TAG, "Join Home Assistant's Thread network ($source)")
        threadJoin().start()
    }

    // ------------------------------------------------------------------ requests from the panel

    /**
     * A request from a follow-up card or the settings menu's picker, on the same RadioController calls as
     * the HA entities (`Radio mode`, `Zigbee stack`, `Join/Leave Zigbee network`, `Leave Thread network`).
     * [done] runs on the main thread: null = accepted (the status line tells the rest), else why not.
     * Join/Leave have no answer of their own: their card follows the status. [refresh] redraws the view
     * that asked, on the main thread.
     */
    fun run(req: RadioFollowUp.Request, source: String, done: (String?) -> Unit, refresh: () -> Unit) {
        val r = readout.controller() ?: run { done(NOT_RUNNING); return }
        Log.i(MAIN_TAG, "$source: radio request $req")
        when (req) {
            is RadioFollowUp.Request.Mode -> r.request(req.mode.label, source) { ok ->
                val st = r.statusText(); main.post { done(if (ok) null else st); refresh() }
            }
            is RadioFollowUp.Request.Stack -> r.setZigbeeStack(req.stack.label) { ok ->
                val why = r.helperStatusText().substringBefore(". v"); main.post { done(if (ok) null else why); refresh() }
            }
            is RadioFollowUp.Request.Act -> when (req.action) {
                RadioFollowUp.Action.ROUTER_JOIN -> r.routerJoin()
                RadioFollowUp.Action.ROUTER_LEAVE -> r.routerLeave()
                RadioFollowUp.Action.THREAD_LEAVE -> r.threadLeave()
                RadioFollowUp.Action.THREAD_JOIN_HA -> startThreadJoin(source)
            }
        }
        main.postDelayed(refresh, 400)
    }

    /**
     * `Thread dataset` typed or pasted in the settings menu, the same path as HA's text entity: null when
     * the radio took it, else why not. Never logged. Public for tests.
     */
    fun setDataset(hex: String): String? {
        val r = readout.controller() ?: return NOT_RUNNING
        return r.setThreadDataset(hex)
    }

    // ------------------------------------------------------------------ settings

    /**
     * The settings menu's Radio section: on/off at the top right (asks before Off), the roles (design C)
     * and the panel, then who may connect. Its dot, after its name, follows the radio's status; without the
     * root helper the radio can't run, and the nav says so.
     */
    override fun settings(): List<SettingsSection> {
        val running = readout.running()
        val allow = SettingsItem.Text(
            "IP addresses or ranges, or any", "empty = Home Assistant only", SettingsItem.TextKind.ADDRESS,
            // Same setting as HA's "Radio allowed clients" (frames/aqara-s1-plus/docs/radio.md "Firewall"): empty = Home Assistant only.
            value = readout::allowedClients,
            enabled = { running },
            shown = { it.ifBlank { "Home Assistant only" } },
            save = ::saveAllow,
        )
        val allowedNow = SettingsItem.Note { allowedNow(running, readout.allowedNow()) }
        val items = if (running) listOf(
            // The panel draws the roles and card itself; the Manage page draws their twin (#269).
            SettingsItem.Native(NATIVE_KEY, "Roles, pairing and Thread network", twin = { RadioTwin.items(this) }) { host -> RadioSettingsView(host, this) },
            SettingsItem.Group("Who may connect", listOf(allow, allowedNow)),
        ) else listOf(allow, allowedNow) // nothing to drive without the controller: no picker or card
        return listOf(SettingsSection(
            id, "Radio", items, heading = "Zigbee / Thread radio", master = master(),
            status = ::settingsStatus,
            // The radio thread's cached facts: this runs on the main thread whenever the menu draws, and
            // helperInstalled() can spawn getprop. Unknown until the radio's first refresh: not "can't run".
            unavailable = { if (readout.controller()?.helperShownIfKnown()?.installed == false) NO_HELPER else null },
        ))
    }

    /**
     * The same section and, while the radio's pane is built, "The radio": who is connected now (#217), its
     * firmware and the root helper, as Home Assistant has them (#212).
     */
    override fun settings(running: Pane?): List<SettingsSection> {
        val own = settings()
        val entities = running?.entities().orEmpty().associateBy { it.objectId }
        val rows = listOf("radio_connected" to "Connected now", "radio_firmware" to "Firmware", "radio_helper" to "Radio helper").mapNotNull { (objectId, title) ->
            // Connected now is read afresh while the page is open; Home Assistant's sensor lags up to 15 s.
            val live: () -> String? = if (objectId == "radio_connected") readout::connectedNow else { -> null }
            entities[objectId]?.let { e -> SettingsItem.Info({ title }, { live() ?: e.shownState() ?: dev.glassworks.pane.UNKNOWN }) }
        }
        if (rows.isEmpty()) return own
        return own.map { s -> SettingsSection(s.id, s.title, s.items + SettingsItem.Group("The radio", rows), s.heading, s.master, s.status, s.unavailable) }
    }

    /** The section's on/off: on shows the roles; off asks first, then turns the radio off (settings' log source). */
    private fun master() = SettingsItem.Switch(
        "Zigbee / Thread radio", "", value = { readout.mode() != RadioMode.OFF || choosing },
        confirm = { on ->
            val now = readout.mode()
            if (on || now == RadioMode.OFF) null
            else SettingsItem.Confirm("Turn the radio off?", RadioFollowUp.switchConsequence(now, RadioMode.OFF), "Turn off")
        },
    ) { on, done ->
        choosing = on && readout.mode() == RadioMode.OFF
        // A role picked a moment ago may still be queued on the radio thread with the mode still Off:
        // send Off anyway, so it runs after that request instead of the radio starting after "Off".
        val pending = readout.controller()?.requestPending == true
        if (on || (readout.mode() == RadioMode.OFF && !pending)) done(null)
        else Thread({ done(setModeFrom(RadioMode.OFF.id, "settings menu")) }, "s1-radio-off").apply { isDaemon = true; start() }
    }

    /**
     * The radio's chip and line (#243), from the same health the radio's card shows (RadioFollowUp.health):
     * Problem when it failed (also a Zigbee router whose router host failed under an `ok` helper), else
     * Running, with its role and status under it ([RadioFollowUp.paneLine]). Off is the switch's ([paneState]).
     * Null until the radio's first report.
     */
    private fun settingsStatus(): PaneState? {
        val f = readout.facts() ?: return null
        val detail = RadioFollowUp.paneLine(f)
        return when (RadioFollowUp.health(f)) {
            RadioFollowUp.Health.OFF -> null
            RadioFollowUp.Health.ERROR -> PaneState(PaneState.Kind.PROBLEM, detail)
            else -> PaneState(PaneState.Kind.RUNNING, detail)
        }
    }

    /** A new allow-list: the old list stays on an error; otherwise done once the radio thread handed it to the helper. */
    private fun saveAllow(text: String, done: (String?) -> Unit) {
        val r = readout.controller()
        val wanted = text.trim()
        if (r == null || wanted == r.allowedClients()) { done(null); return }
        val err = r.setAllowedClients(wanted)
        if (err != null) done("Radio allow-list not changed: $err")
        else r.afterPending { done(null) }
    }

    /** The device shell's radio pane, which puts its controller where this definition's step and section read it. */
    fun pane(ctx: PaneContext, panelIp: () -> String?): RadioPane =
        RadioPane(ctx, running, panelIp, requireNotNull(agent) { "the radio pane needs the frame agent (RadioDefinition.app)" })

    /** The app's: the controller [RadioPane] puts in [running] while it runs. */
    class AppReadout(
        private val running: RunningRadio,
        private val prefs: RadioPrefs,
        private val haBase: () -> String?,
    ) : RadioReadout {
        override fun controller() = running.controller
        override fun mode() = running.controller?.mode ?: RadioMode.parse(prefs.mode)

        /** Cached values: no getprop here. */
        override fun facts(): RadioFollowUp.Facts? {
            val r = running.controller ?: return null
            return RadioFollowUp.Facts(
                mode = r.mode, status = r.statusText(), helper = r.helperShown(), stack = r.zigbeeStack,
                threadStored = r.threadStored(), thread = r.threadRouterState(),
                haBase = haBase(), panelIp = panelIp(),
            )
        }

        override fun haBase() = haBase.invoke()
        override fun panelIp() = HaUrls.localIpv4()
    }

    companion object {
        /** The app's definition: its step and section read the controller of the running [RadioPane]. */
        fun app(prefs: RadioPrefs, setUpBefore: () -> Boolean, agent: FrameAgent, haBase: () -> String?): RadioDefinition {
            val running = RunningRadio()
            return RadioDefinition(prefs, setUpBefore, AppReadout(running, prefs, haBase), running, agent = agent)
        }

        private const val TAG = "Glassworks.SetupChannel"
        private const val MAIN_TAG = "Glassworks"
        internal const val NATIVE_KEY = "radio"
        internal const val NOT_RUNNING = "Glassworks is still starting up. Try again in a moment."
        /** Why the radio can't run (the settings page; the nav says "Can't run"). Public for tests. */
        const val NO_HELPER = "it needs the root helper, which the installer puts on the panel"
        private const val CHANNEL_NOT_RUNNING = "The radio controller isn't running yet; try again in a moment."
        const val JOIN_NOT_ROUTER = "The radio isn't a Thread router: set the mode to thread_router first."
        const val HANDOFF_NOT_TAKEN = "the radio's root helper didn't take the Thread network's settings within a minute (see the Radio helper sensor)"
        const val JOIN_NOT_SIGNED_IN = "The panel isn't signed in to Home Assistant: sign in first, then join."
        /** Wait for RadioController to take the request, then for it to report how it went (client waits 30 s). */
        private const val RADIO_ACCEPT_MS = 15_000L
        private const val RADIO_FOLLOW_MS = 10_000L
        private const val HELPER_CACHE_MS = 10_000L

        /** The line under the radio allow-list: who may connect right now. */
        fun allowedNow(radioRunning: Boolean, allowNow: String?): String = when {
            !radioRunning -> "Glassworks isn't running yet"
            allowNow == null -> "Allowed now: no firewall active (radio off or old root helper)"
            allowNow.isEmpty() -> "Allowed now: only the panel itself (Home Assistant hasn't connected yet)"
            else -> "Allowed now: ${allowNow.replace(" ", ", ")}"
        }

        /** The modes onboarding offers, as the setup channel lists them (the panel's radio step, same texts). */
        fun channelOptions(): JSONArray = JSONArray().apply {
            RadioStep.OPTIONS.forEach { o ->
                put(JSONObject().apply {
                    put("id", o.mode.id)
                    put("label", o.title)
                    put("detail", o.line)
                    // optional in channel.ts RadioOption: only sent when true
                    if (o.experimental) put("experimental", true)
                    if (o.repeater) put("repeater", true)
                })
            }
        }

        /**
         * channel.ts `SetupState.radio`. [allowed]: who the helper's firewall lets reach the radio's TCP
         * services (EZSP :6638, OTBR REST :8081): "any", "" (nobody but the panel yet: Home Assistant
         * hasn't connected), or space-separated addresses/CIDRs. Left out when unknown: Off, no helper,
         * or a helper without the firewall. [answered]: the panel's radio step is answered (Kiln then
         * leaves a kept mode alone).
         */
        fun channelJson(
            mode: String, status: String, helper: Boolean, allowed: String? = null, answered: Boolean? = null, threadJoin: JSONObject? = null,
        ): JSONObject = JSONObject().apply {
            put("mode", mode)
            put("status", status)
            put("options", channelOptions())
            put("helper", helper)
            allowed?.let { put("allowed", it) }
            answered?.let { put("answered", it) }
            threadJoin?.let { put("threadJoin", it) }
        }

        /**
         * channel.ts `SetupState.radio.threadJoin`: how the join from Home Assistant goes. Only phases,
         * roles and plain reasons, never the dataset or anything in it. Left out (null) outside Thread
         * router: [joinThread] refuses there, and a join from the panel's button switches to it.
         *  - `idle`: not started in this process. [router]'s `role` says if the panel is on a network anyway.
         *  - `fetching`: getting the dataset from Home Assistant with the panel's sign-in.
         *  - `needs_admin`: the panel's user isn't an administrator; the panel shows a QR code for
         *    `adminUrl` (left out while the panel has no address), where an administrator signs in once.
         *    `reason` says why.
         *  - `joining`: the dataset is fetched and handed to the radio; the radio hasn't attached yet.
         *    The radio's error after the hand-off ([status], RadioController.statusText) is `failed`.
         *    [router] and [status] count only once the helper answered the hand-off ([handOff]): until
         *    then they describe the network before it. A hand-off the helper never takes is `failed`.
         *  - `joined`: the radio is on the network; `role` is `child`, `router` or `leader`.
         *    Once the radio has no dataset again (Leave Thread network), the join is over: `idle`.
         *  - `failed`: `reason` says why, in plain words.
         */
        fun threadJoinJson(mode: RadioMode, join: ThreadJoin.State, handOff: RadioController.ThreadHandOff,
            router: ThreadRouterState?, status: String?, panelIp: () -> String?, now: Long = System.currentTimeMillis()): JSONObject? {
            if (mode != RadioMode.THREAD_ROUTER) return null
            val answered = handOff == RadioController.ThreadHandOff.ANSWERED
            val router = router.takeIf { answered }
            val o = JSONObject()
            fun phase(p: String, reason: String? = null) { o.put("phase", p); reason?.let { o.put("reason", it) } }
            when (join) {
                ThreadJoin.State.Idle -> phase("idle")
                ThreadJoin.State.Working -> phase("fetching")
                is ThreadJoin.State.NeedsAdmin ->
                    if (now < join.until) { phase("needs_admin", join.why); panelIp()?.let { o.put("adminUrl", ThreadJoin.url(it)) } }
                    else phase("failed", "nobody signed in as an administrator in time; start again")
                is ThreadJoin.State.Joined -> when {
                    handOff == RadioController.ThreadHandOff.NOT_TAKEN -> phase("failed", HANDOFF_NOT_TAKEN)
                    router?.joined == true -> phase("joined")
                    router?.dataset == false -> phase("idle")
                    answered && status != null && RadioStep.classify(mode, true, status) == RadioStep.Result.ERROR -> phase("failed", status)
                    else -> phase("joining")
                }
                is ThreadJoin.State.Failed -> phase("failed", join.reason)
            }
            router?.role?.takeIf { router.dataset }?.let { o.put("role", it) }
            return o
        }
    }
}
