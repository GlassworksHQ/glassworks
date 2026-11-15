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

    /** The items for the radio as it is now; none while its controller isn't running. */
    fun items(def: RadioDefinition): List<SettingsItem> {
        val f = def.readout.facts() ?: return emptyList()
        // As the panel's view: a mode on means the role was picked.
        if (f.mode != RadioMode.OFF) def.choosing = false
        val c = RadioFollowUp.card(f, RadioFollowUp.Place.SETTINGS)
        val out = ArrayList<SettingsItem>()
        if (f.mode != RadioMode.OFF || def.choosing) out += role(def)
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
            if (c.stackChoice) out += stack(def, f)
        }
        c.actions.forEach { a -> out += action(def, a, f) }
        c.note?.let { n -> out += SettingsItem.Note { n } }
        return out
    }

    /** The roles, without Off (the page's switch turns it off). A new role asks first, with the panel's words. */
    private fun role(def: RadioDefinition) = SettingsItem.Choice(
        ROLE,
        value = { def.readout.mode().takeIf { it != RadioMode.OFF }?.id.orEmpty() },
        options = { RadioStep.OPTIONS.filter { it.mode != RadioMode.OFF }.map { it.mode.id to it.title } },
        line = { id -> RadioMode.fromId(id)?.takeIf { it != RadioMode.OFF }?.let(RadioStep::shortName) ?: PICK },
        confirm = { id -> RadioMode.fromId(id)?.let { m -> RadioFollowUp.ask(Request.Mode(m), def.readout.facts())?.confirm() } },
    ) { id, done ->
        val m = RadioMode.fromId(id)?.takeIf { it != RadioMode.OFF } ?: return@Choice done("Pick one of the roles.")
        if (m == def.readout.mode()) return@Choice done(null)
        def.run(Request.Mode(m), SOURCE, { why -> if (why == null) def.choosing = false; done(why) }) {}
    }

    /** Aqara's stack or Silicon Labs', in Zigbee coordinator mode; a switch asks first. */
    private fun stack(def: RadioDefinition, f: RadioFollowUp.Facts) = SettingsItem.Choice(
        STACK,
        value = { (def.readout.facts()?.stack ?: f.stack).id },
        options = { ZigbeeStack.entries.map { it.id to RadioFollowUp.stackTitle(it) } },
        confirm = { id -> ZigbeeStack.entries.firstOrNull { it.id == id }?.let { z -> RadioFollowUp.ask(Request.Stack(z), def.readout.facts())?.confirm() } },
    ) { id, done ->
        val z = ZigbeeStack.entries.firstOrNull { it.id == id } ?: return@Choice done("Pick one of the stacks.")
        if (z == def.readout.facts()?.stack) return@Choice done(null)
        def.run(Request.Stack(z), SOURCE, done) {}
    }

    /**
     * A role's button (Join, Leave, Get it from Home Assistant). Join and Leave have no answer of their own on
     * the panel (its card follows the status), so the page answers once the radio took the request.
     */
    private fun action(def: RadioDefinition, a: RadioFollowUp.Action, f: RadioFollowUp.Facts) = SettingsItem.Action(
        a.label, { if (a == RadioFollowUp.Action.THREAD_JOIN_HA) JOIN_HA_DETAIL else "" }, short(a),
        confirm = RadioFollowUp.ask(Request.Act(a), f)?.confirm(),
    ) { done ->
        var answered = false
        def.run(Request.Act(a), SOURCE, { why -> if (!answered) { answered = true; done(why) } }) {}
        if (!answered) { answered = true; done(null) }
    }

    /** The button's word on the Manage page, where the row's title says the rest. */
    fun short(a: RadioFollowUp.Action): String = when (a) {
        RadioFollowUp.Action.ROUTER_JOIN -> "Join"
        RadioFollowUp.Action.ROUTER_LEAVE, RadioFollowUp.Action.THREAD_LEAVE -> "Leave"
        RadioFollowUp.Action.THREAD_JOIN_HA -> "Get it"
    }

    const val JOIN_HA_DETAIL = "If the panel's own sign-in isn't an administrator, the panel shows a code to sign in once."

    private fun RadioFollowUp.Ask.confirm() = SettingsItem.Confirm(title, body, button)
}
