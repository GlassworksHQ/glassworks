package sh.edm.s1kiosk.radio.ui

import android.app.Application
import dev.glassworks.pane.SettingsChange
import dev.glassworks.pane.SettingsItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.radio.FixedReadout
import sh.edm.s1kiosk.radio.HelperFacts
import sh.edm.s1kiosk.radio.RadioDefinition
import sh.edm.s1kiosk.radio.RadioMode
import sh.edm.s1kiosk.radio.ThreadRouterState
import sh.edm.s1kiosk.radio.ZigbeeStack
import sh.edm.s1kiosk.radio.radioDefinition

/**
 * #269: the radio's twin on the Manage page says what the panel's radio view says, from the same card
 * ([RadioFollowUp.card]), asks the same questions first ([RadioFollowUp.ask]), and never shows a secret.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RadioTwinTest {
    private val app = RuntimeEnvironment.getApplication()

    /** RadioDefinition's answer without a radio controller (its constant is internal to the pane). */
    private val NOT_RUNNING = "The kiosk service isn't running yet; try again in a moment."

    private fun facts(mode: RadioMode, status: String = "ok ${mode.label}: listening", thread: ThreadRouterState? = null, stored: Boolean? = null) =
        RadioFollowUp.Facts(mode, status, HelperFacts(true, 4), ZigbeeStack.AQARA, stored, thread, "http://homeassistant.local:8123", "192.0.2.10")

    private fun def(f: RadioFollowUp.Facts): RadioDefinition = radioDefinition(app, FixedReadout(f, running = true))

    private fun words(items: List<SettingsItem>): List<String> = items.flatMap { i ->
        when (i) {
            is SettingsItem.Info -> listOf(i.title(), i.detail())
            is SettingsItem.Note -> listOf(i.text())
            is SettingsItem.Status -> listOf(i.text())
            is SettingsItem.Choice -> listOf(i.title, SettingsChange.shown(i))
            is SettingsItem.Action -> listOf(i.title, i.detail(), i.label)
            else -> error("no ${i::class.simpleName} in the radio's twin")
        }
    }.filter { it.isNotEmpty() }

    private val threadStates = listOf(
        null,
        ThreadRouterState(dataset = false, role = null, network = null),
        ThreadRouterState(dataset = true, role = "detached", network = "Example-Thread"),
        ThreadRouterState(dataset = true, role = "router", network = "Example-Thread"),
    )

    @Test fun everyModeSaysWhatThePanelsCardSays() {
        val cases = RadioMode.entries.filter { it != RadioMode.THREAD_ROUTER }.map { facts(it) } +
            threadStates.map { facts(RadioMode.THREAD_ROUTER, thread = it, stored = it?.dataset) }
        for (f in cases) {
            val c = RadioFollowUp.card(f, RadioFollowUp.Place.SETTINGS)
            val twin = RadioTwin.items(def(f))
            val w = words(twin)
            val what = "${f.mode} ${f.thread}"
            if (f.mode == RadioMode.OFF) assertTrue(what, RadioTwin.OFF_TITLE in w && RadioTwin.OFF_TEXT in w)
            else {
                assertEquals(what, RadioStep.shortName(f.mode), SettingsChange.shown(twin.filterIsInstance<SettingsItem.Choice>().first { it.title == RadioTwin.ROLE }))
                if (c.title.isNotEmpty()) assertTrue(what, c.title in w && c.lines.joinToString(" ").let { it.isEmpty() || it in w })
                c.headline?.let { assertTrue(what, it in w) }
                c.subline?.let { assertTrue(what, it in w) }
                c.kv.forEach { assertTrue("$what: ${it.label}", it.label in w && it.value in w) }
                c.state?.let { assertTrue("$what: state", it in w) }
                c.badge?.let { assertTrue("$what: badge", it in w) }
                assertEquals("$what: the stack choice", c.stackChoice, twin.any { it is SettingsItem.Choice && it.title == RadioTwin.STACK })
            }
            c.note?.let { assertTrue("$what: note", it in w) }
            assertEquals("$what: the role's buttons", c.actions.map { it.label }, twin.filterIsInstance<SettingsItem.Action>().map { it.title })
            // Never the dataset, nor a field to read it back: no text item at all.
            assertTrue(what, twin.none { it is SettingsItem.Text || it is SettingsItem.SignIn })
            assertFalse("$what: no dataset field", w.any { RadioFollowUp.DATASET_DISCLOSURE in it || "TLV" in it })
        }
    }

    @Test fun theRolesAreTheOnesThePanelOffersAndANewOneAsksFirst() {
        val f = facts(RadioMode.THREAD_ROUTER, thread = threadStates[3], stored = true)
        val d = def(f)
        val role = RadioTwin.items(d).filterIsInstance<SettingsItem.Choice>().single { it.title == RadioTwin.ROLE }
        assertEquals(RadioStep.OPTIONS.filter { it.mode != RadioMode.OFF }.map { it.mode.id }, role.options().map { it.first })
        assertNull("the role it has asks nothing", role.confirm(RadioMode.THREAD_ROUTER.id))
        val ask = role.confirm(RadioMode.ZIGBEE_TCP.id)!!
        val panel = RadioFollowUp.ask(RadioFollowUp.Request.Mode(RadioMode.ZIGBEE_TCP), f)!!
        assertEquals(listOf(panel.title, panel.body, panel.button), listOf(ask.title, ask.detail, ask.action))
        assertTrue("says the cost", ask.detail.contains("stops repeating for your Thread network"))
    }

    /** The no-op the panel check runs: the role it has, sent back, sends nothing to the radio. */
    @Test fun pickingTheRoleItHasDoesNothing() {
        val d = def(facts(RadioMode.THREAD_ROUTER, thread = threadStates[3], stored = true))
        val role = RadioTwin.items(d).filterIsInstance<SettingsItem.Choice>().single { it.title == RadioTwin.ROLE }
        var answer: String? = "none"
        // A request would answer "not running": this readout has no controller.
        SettingsChange.change(role, RadioMode.THREAD_ROUTER.id) { answer = it }
        assertNull(answer)
        SettingsChange.change(role, RadioMode.ZIGBEE_TCP.id) { answer = it }
        assertEquals("a new role goes to the radio", NOT_RUNNING, answer)
        SettingsChange.change(role, RadioMode.OFF.id) { answer = it }
        assertEquals("Off is the page's switch, not a role", SettingsChange.PICK, answer)
    }

    @Test fun leaveAsksFirstAndJoinDoesnt() {
        val zr = RadioTwin.items(def(facts(RadioMode.ZIGBEE_ROUTER, status = "ok Zigbee router: in a network", stored = true)))
            .filterIsInstance<SettingsItem.Action>().associateBy { it.title }
        assertNull(zr.getValue(RadioFollowUp.Action.ROUTER_JOIN.label).confirm)
        assertEquals("Leave the Zigbee network?", zr.getValue(RadioFollowUp.Action.ROUTER_LEAVE.label).confirm?.title)
        assertEquals("Leave the Thread network?", zr.getValue(RadioFollowUp.Action.THREAD_LEAVE.label).confirm?.title)
        var answer: String? = "none"
        zr.getValue(RadioFollowUp.Action.ROUTER_JOIN.label).run { answer = it }
        assertEquals("no radio: says so, once", NOT_RUNNING, answer)
    }

    @Test fun turnedOnFromTheBrowserItOffersTheRoles() {
        val d = def(facts(RadioMode.OFF, status = "ok Off: idle"))
        assertTrue(RadioTwin.items(d).none { it is SettingsItem.Choice })
        d.choosing = true
        val twin = RadioTwin.items(d)
        assertEquals(RadioTwin.PICK, SettingsChange.shown(twin.filterIsInstance<SettingsItem.Choice>().single()))
        assertTrue("no 'radio is off' while choosing", twin.none { it is SettingsItem.Info })
    }
}
