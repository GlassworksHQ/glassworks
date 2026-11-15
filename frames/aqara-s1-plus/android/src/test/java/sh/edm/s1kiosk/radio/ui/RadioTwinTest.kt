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
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
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

    /** #269 verifier: Off isn't a role. Picking it asks nothing (the page's switch does that) and is refused. */
    @Test fun offIsNotARoleAndAsksNothing() {
        val d = def(facts(RadioMode.THREAD_ROUTER, thread = threadStates[3], stored = true))
        val role = RadioTwin.items(d).filterIsInstance<SettingsItem.Choice>().single { it.title == RadioTwin.ROLE }
        assertNull(role.confirm(RadioMode.OFF.id))
        assertNull(role.confirm("nonsense"))
    }

    /** #269 verifier: drawing the page writes nothing, not even the picker's "choosing". */
    @Test fun readingTheTwinChangesNothing() {
        val d = def(facts(RadioMode.ZIGBEE_TCP))
        d.choosing = true
        repeat(3) { RadioTwin.items(d) }
        assertTrue(d.choosing)
    }

    /**
     * #269 verifier: one radio request at a time, as on the panel's card. A second role while the first waits
     * for the radio, or a second Leave before the status says the first ran its course, is answered "busy"
     * and sends nothing.
     */
    @Test fun oneRequestAtATime() {
        val sent = ArrayList<RadioFollowUp.Request>()
        val answers = ArrayList<(String?) -> Unit>()
        val send = RadioTwin.Send { r, done -> sent += r; answers += done }
        val d = def(facts(RadioMode.THREAD_ROUTER, thread = threadStates[3], stored = true))
        fun role() = RadioTwin.items(d, send).filterIsInstance<SettingsItem.Choice>().single { it.title == RadioTwin.ROLE }
        fun leave() = RadioTwin.items(d, send).filterIsInstance<SettingsItem.Action>().single { it.title == RadioFollowUp.Action.THREAD_LEAVE.label }
        val got = ArrayList<String?>()
        role().save(RadioMode.ZIGBEE_TCP.id) { got += it }
        role().save(RadioMode.THREAD.id) { got += it }
        leave().run { got += it }
        assertEquals("one request reached the radio", 1, sent.size)
        assertEquals(listOf(RadioTwin.BUSY, RadioTwin.BUSY), got)
        answers.single()(null) // the radio answered the first
        got.clear()
        leave().run { got += it }
        leave().run { got += it }
        assertEquals(listOf(RadioFollowUp.Request.Mode(RadioMode.ZIGBEE_TCP), RadioFollowUp.Request.Act(RadioFollowUp.Action.THREAD_LEAVE)), sent)
        assertEquals("the first Leave answered once the radio took it, the second is busy", listOf(null, RadioTwin.BUSY), got)
    }

    /** A request the radio refused frees the way at once. */
    @Test fun aRefusedRequestDoesntBlockTheNext() {
        val d = def(facts(RadioMode.THREAD_ROUTER, thread = threadStates[3], stored = true))
        val leave = RadioTwin.items(d).filterIsInstance<SettingsItem.Action>().single()
        val got = ArrayList<String?>()
        leave.run { got += it }
        leave.run { got += it }
        assertEquals("no radio here: refused twice, never busy", listOf(NOT_RUNNING, NOT_RUNNING), got)
    }

    /**
     * #269 verifier: both ways against the panel's own radio view, in every mode. Each card word in the twin is
     * shown by RadioSectionView, and each word the view shows is in the twin or one of the view's own (the role
     * tiles' header, the status pill, the dataset and "How does this work?" disclosures, the Off legend), so a
     * field either side drops fails here.
     */
    @Test fun theTwinAndThePanelsViewSayTheSame() {
        val cases = RadioMode.entries.filter { it != RadioMode.THREAD_ROUTER }.map { facts(it) } +
            facts(RadioMode.ZIGBEE_ROUTER, status = "ok Zigbee router (repeater): in a network", stored = true) +
            threadStates.map { facts(RadioMode.THREAD_ROUTER, thread = it, stored = it?.dataset) }
        for (f in cases) {
            val what = "${f.mode} ${f.thread} ${f.status}"
            val view = RadioSectionView(sh.edm.s1kiosk.ui.Mockup.activity()).apply { onAsk = { _, _ -> }; onRun = { _, _ -> } }
            val root = android.widget.LinearLayout(view.panel.context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                addView(view.tiles); addView(view.panel)
            }
            (view.panel.context as android.app.Activity).setContentView(root)
            view.bind(f)
            sh.edm.s1kiosk.ui.Mockup.idle()
            val shown = texts(root)
            val twin = RadioTwin.items(def(f))
            val twinWords = twin.flatMap { i ->
                words(listOf(i)) + ((i as? SettingsItem.Choice)?.options()?.map { it.second } ?: emptyList())
            }.toSet()
            val own = setOf(RadioTwin.ROLE, RadioTwin.JOIN_HA_DETAIL, RadioTwin.PICK) + RadioMode.entries.map(RadioStep::shortName) +
                RadioFollowUp.Action.entries.map(RadioTwin::short) + RadioFollowUp.Health.entries.map { it.label }
            val onPanelMissing = twinWords.filter { it !in shown && it !in own && !(it in RadioStep.OPTIONS.map { o -> o.title }) }
            assertTrue("$what: the twin says $onPanelMissing, which the panel's view doesn't show", onPanelMissing.isEmpty())
            val viewOwn = VIEW_OWN + RadioMode.entries.map(RoleStyle::header) + RadioFollowUp.Health.entries.map { it.label }
            val twinMissing = shown.filter { it !in twinWords && it !in viewOwn && !it.startsWith(RadioFollowUp.DATASET_DISCLOSURE) && !it.startsWith("How does this work?") }
            assertTrue("$what: the panel's view shows $twinMissing, which the twin doesn't have", twinMissing.isEmpty())
        }
    }

    private val VIEW_OWN = setOf(
        // The role picker's tiles (RadioTechPicker): the twin's Role row and its list.
        "Zigbee", "For ZHA or Zigbee2MQTT", "Coordinator", "Repeater β", "Thread", "For Matter over Thread", "Border router",
        // Off's legend under "The radio is off".
        "β  experimental",
    )

    private fun texts(v: android.view.View): List<String> {
        val out = ArrayList<String>()
        fun walk(x: android.view.View) {
            if (!x.isShown) return
            if (x is android.widget.TextView) x.text?.toString()?.trim()?.takeIf { t -> t.any { it.isLetterOrDigit() } }?.let(out::add)
            if (x is android.view.ViewGroup) for (c in 0 until x.childCount) walk(x.getChildAt(c))
        }
        walk(v)
        return out
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
