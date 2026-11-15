package sh.edm.s1kiosk.radio.ui

import dev.glassworks.pane.SettingsItem
import sh.edm.s1kiosk.radio.RadioDefinition
import sh.edm.s1kiosk.radio.RadioMode
import sh.edm.s1kiosk.radio.ZigbeeStack
import sh.edm.s1kiosk.radio.ui.RadioFollowUp.Request

/**
 * The radio's roles and card as typed items (#269): the twin of [RadioSettingsView], which the Manage page
 * draws. Both come from the same [RadioFollowUp.card] and [RadioFollowUp.ask], and both send the same
 * requests ([RadioDefinition.run]), so a role, the Zigbee stack and the role's buttons ask first and do the
 * same on either surface.
 *
 * Left out on purpose:
 * - The Thread dataset. It is never shown anywhere, and pasting one stays on the panel: it carries the
 *   network key, and the Manage page is plain HTTP. "Get it from Home Assistant" is the way from here.
 * - The QR codes, which are for a phone at the panel, and "How does this work?".
 *
 * Picking the role or the stack the radio already has does nothing, as tapping it on the panel does:
 * a request for the mode in effect would restart the radio for nothing.
 */
object RadioTwin {
    const val ROLE = "Role"
    const val STACK = "Zigbee stack"
    const val OFF_TITLE = "The radio is off"
    const val OFF_TEXT = "Turn it on at the top right, then pick what it does."
    const val PICK = "Pick a role"
    /** What the radio's log says a request came from. */
    const val SOURCE = "Manage page"

    /** How the twin sends a request: to [RadioDefinition.run], as the panel's card does. Tests give their own. */
    fun interface Send {
        fun send(r: Request, done: (String?) -> Unit)
    }

    /**
     * The items for the radio as it is now; none while its controller isn't running. Reading them changes
     * nothing (#269 verifier): a page that only looks writes no state.
     */
    fun items(def: RadioDefinition, send: Send = Send { r, done -> def.run(r, SOURCE, done) {} }): List<SettingsItem> {
        val f = def.readout.facts() ?: return emptyList()
        val c = RadioFollowUp.card(f, RadioFollowUp.Place.SETTINGS)
        val out = ArrayList<SettingsItem>()
        if (f.mode != RadioMode.OFF || def.choosing) out += role(def, send)
        if (f.mode == RadioMode.OFF) {
            if (!def.choosing) out += SettingsItem.Info({ OFF_TITLE }, { OFF_TEXT })
        } else {
            val health = RadioFollowUp.health(f)
            c.headline?.let { h -> out += SettingsItem.Info({ h }, { c.subline.orEmpty() }) }
            if (c.title.isNotEmpty()) out += SettingsItem.Info({ c.title }, { c.lines.joinToString(" ") })
            else c.lines.forEach { l -> out += SettingsItem.Note { l } }
            c.kv.forEach { kv -> out += SettingsItem.Info({ kv.label }, { kv.value }) }
            val state = if (health == RadioFollowUp.Health.ERROR) RadioFollowUp.statusDetail(f) else c.state
            val ok = health == RadioFollowUp.Health.RUNNING || health == RadioFollowUp.Health.JOINED
            state?.let { out += SettingsItem.Status({ it }, { ok }, { health == RadioFollowUp.Health.ERROR }) }
            c.badge?.let { b -> out += SettingsItem.Status({ b }, { b == "Dataset set" }) }
            if (c.stackChoice) out += stack(def, f, send)
        }
        c.actions.forEach { a -> out += action(def, a, f, send) }
        c.note?.let { n -> out += SettingsItem.Note { n } }
        return out
    }

    /** The roles, without Off (the page's switch turns it off). A new role asks first, with the panel's words. */
    private fun role(def: RadioDefinition, send: Send) = SettingsItem.Choice(
        ROLE,
        value = { def.readout.mode().takeIf { it != RadioMode.OFF }?.id.orEmpty() },
        options = { RadioStep.OPTIONS.filter { it.mode != RadioMode.OFF }.map { it.mode.id to it.title } },
        line = { id -> RadioMode.fromId(id)?.takeIf { it != RadioMode.OFF }?.let(RadioStep::shortName) ?: PICK },
        // Only for a role in the list: Off isn't one, so it asks nothing and is refused (#269 verifier).
        confirm = { id -> roleOf(id)?.let { m -> RadioFollowUp.ask(Request.Mode(m), def.readout.facts())?.confirm() } },
    ) { id, done ->
        val m = roleOf(id) ?: return@Choice done("Pick one of the roles.")
        if (m == def.readout.mode()) return@Choice done(null)
        val r = Request.Mode(m)
        if (!start(def, r)) return@Choice done(BUSY)
        sending(def, r) { send.send(r) { why -> def.twinGuard.finish(r); if (why == null) def.choosing = false; done(why) } }
    }

    private fun roleOf(id: String) = RadioMode.fromId(id)?.takeIf { it != RadioMode.OFF }

    /** Aqara's stack or Silicon Labs', in Zigbee coordinator mode; a switch asks first. */
    private fun stack(def: RadioDefinition, f: RadioFollowUp.Facts, send: Send) = SettingsItem.Choice(
        STACK,
        value = { (def.readout.facts()?.stack ?: f.stack).id },
        options = { ZigbeeStack.entries.map { it.id to RadioFollowUp.stackTitle(it) } },
        confirm = { id -> ZigbeeStack.entries.firstOrNull { it.id == id }?.let { z -> RadioFollowUp.ask(Request.Stack(z), def.readout.facts())?.confirm() } },
    ) { id, done ->
        val z = ZigbeeStack.entries.firstOrNull { it.id == id } ?: return@Choice done("Pick one of the stacks.")
        if (z == def.readout.facts()?.stack) return@Choice done(null)
        val r = Request.Stack(z)
        if (!start(def, r)) return@Choice done(BUSY)
        sending(def, r) { send.send(r) { why -> def.twinGuard.finish(r); done(why) } }
    }

    /**
     * A role's button (Join, Leave, Get it from Home Assistant). Join and Leave have no answer of their own on
     * the panel (its card follows the status), so the page answers once the radio took the request, and the
     * next request waits until the status says this one ran its course, as on the panel.
     */
    private fun action(def: RadioDefinition, a: RadioFollowUp.Action, f: RadioFollowUp.Facts, send: Send) = SettingsItem.Action(
        a.label, { if (a == RadioFollowUp.Action.THREAD_JOIN_HA) JOIN_HA_DETAIL else "" }, short(a),
        confirm = RadioFollowUp.ask(Request.Act(a), f)?.confirm(),
    ) { done ->
        val r = Request.Act(a)
        if (!start(def, r)) return@Action done(BUSY)
        var answered = false
        sending(def, r) {
            send.send(r) { why ->
                if (why != null) def.twinGuard.finish(r) // refused: nothing runs
                if (!answered) { answered = true; done(why) }
            }
        }
        if (!answered) { answered = true; done(null) }
    }

    /**
     * Sends [r] through [block]; a send that throws frees the guard at once (#269 delta verifier), and the
     * throw goes on to SettingsChange.change, which answers it in words.
     */
    private inline fun sending(def: RadioDefinition, r: Request, block: () -> Unit) {
        try { block() } catch (e: Throwable) { def.twinGuard.finish(r); throw e }
    }

    /** [r] may start: no other request runs, from here or (a mode change) the panel and Home Assistant. */
    private fun start(def: RadioDefinition, r: Request): Boolean =
        def.twinGuard.start(r, def.readout.facts()?.status.orEmpty(), pending = def.readout.controller()?.requestPending == true)

    /**
     * One radio request at a time from the Manage page, as the panel's card allows (#269 verifier): a mode or
     * stack until its answer (or [LOST_MS] without one), a role button until the status says it ran its course
     * ([RadioFollowUp.actDone]). Any thread.
     */
    class Guard(private val clock: () -> Long = android.os.SystemClock::elapsedRealtime) {
        private var running: Request? = null
        private var since = 0L
        private var before = ""

        /** Starts [r] with the radio's [status] now; false while another request runs or [pending] says one is queued. */
        @Synchronized fun start(r: Request, status: String, pending: Boolean): Boolean {
            if (pending || busy(status)) return false
            running = r; since = clock(); before = status
            return true
        }

        /** [r] answered. */
        @Synchronized fun finish(r: Request) { if (running === r) running = null }

        private fun busy(status: String): Boolean {
            val r = running ?: return false
            val waited = clock() - since
            val over = if (r is Request.Act) RadioFollowUp.actDone(r.action, status, before, waited) else waited > LOST_MS
            if (over) running = null
            return !over
        }

        companion object {
            /** A mode or stack request with no answer after this is over (the radio answers within ~25 s). */
            const val LOST_MS = 60_000L
        }
    }

    /** The button's word on the Manage page, where the row's title says the rest. */
    fun short(a: RadioFollowUp.Action): String = when (a) {
        RadioFollowUp.Action.ROUTER_JOIN -> "Join"
        RadioFollowUp.Action.ROUTER_LEAVE, RadioFollowUp.Action.THREAD_LEAVE -> "Leave"
        RadioFollowUp.Action.THREAD_JOIN_HA -> "Get it"
    }

    /** A second request while one runs, in plain words. */
    const val BUSY = "The radio is still busy with the last change. Try again when it's done."

    const val JOIN_HA_DETAIL = "If the panel's own sign-in isn't an administrator, the panel shows a code to sign in once."

    private fun RadioFollowUp.Ask.confirm() = SettingsItem.Confirm(title, body, button)
}
