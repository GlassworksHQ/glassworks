package dev.glassworks.setup

import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.view.FocusFinder
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.frame.Frame
import dev.glassworks.improv.ImprovState
import dev.glassworks.platform.HotspotFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.edm.s1kiosk.frame.TestFrames
import dev.glassworks.ui.Glass
import dev.glassworks.ui.Mockup

/**
 * The Wi-Fi step on the setup screen (#468): it leads with the first way the panel can do now, and folds the
 * others behind "Other ways to set up ›", which opens them in its place. Not now is the quietest thing on the card,
 * keys reach every way, and it all fits at each size the setup mockups cover.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real text measurement, for the fit checks
class WifiWaysScreenTest {
    @Before fun interFromSources() = Mockup.interFromSources()

    private val offline = WifiSetupStatus.Snapshot(WifiSetupStatus.State.NOT_CONNECTED)
    private val ready = HotspotPermission.Step.Ready
    private val offer = HotspotStep.card(SetupHotspot.View.Off(null, null), ready, null)
    private val wifiOff = HotspotStep.card(SetupHotspot.View.Off(null, HotspotFailure.WIFI_OFF), ready, null)
    private val btOn = ImprovFacts(running = true, advertising = true, state = ImprovState.AUTHORIZATION_REQUIRED)
    private val btOff = ImprovFacts()
    private val noBt = ImprovFacts(startError = "this panel has no Bluetooth adapter")

    /** A way leading: its card's title, its buttons (in the order keys reach them), and the other ways behind the link. */
    private class Lead(val name: String, val improv: ImprovFacts, val spot: HotspotStep.Card, val title: String, val buttons: List<String>, val others: List<String>)

    private val leads = listOf(
        Lead("Bluetooth on", btOn, offer, "Connect to Wi-Fi.", listOf("Allow", LINK, "Not now"), listOf("Set up from a phone", "Choose a network")),
        // Wi-Fi off: the hotspot can't open, so it isn't offered, and Turn on Wi-Fi says what fixes it. It opens the Wi-Fi
        // settings, as Choose a network would, so there is no other way to offer.
        Lead("Bluetooth on, Wi-Fi off", btOn, wifiOff, "Connect to Wi-Fi.", listOf("Allow", "Turn on Wi-Fi", "Not now"), emptyList()),
        Lead("Bluetooth switched off, Wi-Fi off", btOff, wifiOff, "Connect to Wi-Fi.", listOf("Use Bluetooth", "Turn on Wi-Fi"), emptyList()),
        Lead("Bluetooth switched off", btOff, offer, "Set up from a phone.", listOf(LINK, "Set up from a phone"), listOf("Use Bluetooth", "Choose a network")),
        // Bluetooth couldn't start: it never leads, but a retry stays behind the link.
        Lead("no Bluetooth", noBt, offer, "Set up from a phone.", listOf(LINK, "Set up from a phone"), listOf("Try Bluetooth again", "Choose a network")),
        // Wi-Fi is off, so the hotspot can't open: what fixes it, and Bluetooth's retry behind the link.
        Lead("neither", noBt, wifiOff, "Choose your Wi-Fi.", listOf(LINK, "Turn on Wi-Fi", "Choose a network"), listOf("Try Bluetooth again")),
    )

    private fun model(improv: ImprovFacts, spot: HotspotStep.Card?, step: String = SetupFlow.WIFI) = SetupOverlay.Model(
        step = step, done = emptySet(), wifi = offline, haUrl = null, haSource = "", panelIp = null,
        signedIn = false, improv = WifiStep.improv(improv), hotspot = spot,
    )

    private var w = Mockup.W
    private var h = Mockup.H

    private fun screen(improv: ImprovFacts, spot: HotspotStep.Card?, frame: (Activity) -> Frame = { TestFrames.s1() }): SetupOverlay {
        val a = Mockup.activity()
        val s = SetupOverlay(a, frame(a))
        a.setContentView(s, ViewGroup.LayoutParams(-1, -1))
        s.render(model(improv, spot))
        Mockup.idle()
        Mockup.layout(s, w, h)
        return s
    }

    private fun relayout(s: SetupOverlay) {
        Mockup.idle()
        Mockup.layout(s, w, h)
    }

    private fun views(v: View): Sequence<View> = sequence {
        yield(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(views(v.getChildAt(i)))
    }

    private fun shown(s: View) = views(s).filterIsInstance<TextView>().filter { it.isShown }
    private fun texts(s: View) = shown(s).map { it.text.toString() }.toList()
    private fun button(s: View, label: String) = shown(s).single { it.text.toString() == label && it.isClickable }
    private fun buttons(s: View) = shown(s).filter { it.isClickable }.map { it.text.toString() }.toList()

    @Test fun eachWayLeadsWithItsOwnCardAndFoldsTheOthersBehindOneLink() {
        for (l in leads) {
            val s = screen(l.improv, l.spot)
            assertTrue("${l.name}: ${texts(s)}", l.title in texts(s))
            assertEquals(l.name, l.buttons, buttons(s))
            if (l.others.isEmpty()) continue
            button(s, LINK).performClick()
            relayout(s)
            assertEquals("${l.name}, opened: in the link's place, in order", l.buttons.flatMap { if (it == LINK) l.others else listOf(it) }, buttons(s))
        }
    }

    @Test fun theLeadsButtonDoesItsWay() {
        val chose = mutableListOf<String>()
        val s = screen(noBt, wifiOff)
        s.onChooseNetwork = { chose += "network" }
        button(s, "Choose a network").performClick()
        assertEquals(listOf("network"), chose)
        val asked = mutableListOf<HotspotStep.Action>()
        val p = screen(btOff, offer)
        p.onHotspotAction = { asked += it }
        button(p, "Set up from a phone").performClick()
        assertEquals(listOf(HotspotStep.Action.OPEN), asked)
    }

    @Test fun theLinkOpensTheOtherWaysInItsPlaceInOrderAndEachDoesItsWay() {
        val s = screen(btOn, offer)
        val asked = mutableListOf<Any>()
        s.onHotspotAction = { asked += it }
        s.onChooseNetwork = { asked += "network" }
        button(s, LINK).performClick()
        relayout(s)
        button(s, "Set up from a phone").performClick()
        button(s, "Choose a network").performClick()
        assertEquals(listOf(HotspotStep.Action.OPEN, "network"), asked)
    }

    @Test fun useBluetoothStartsItAgain() {
        val s = screen(btOff, offer)
        val asked = mutableListOf<WifiStep.Action>()
        s.onImprovAction = { asked += it }
        button(s, LINK).performClick()
        relayout(s)
        button(s, "Use Bluetooth").performClick()
        assertEquals(listOf(WifiStep.Action.START), asked)
    }

    @Test fun tryBluetoothAgainRetriesAStartThatFailed() {
        val s = screen(noBt, offer)
        val asked = mutableListOf<WifiStep.Action>()
        s.onImprovAction = { asked += it }
        button(s, LINK).performClick()
        relayout(s)
        button(s, "Try Bluetooth again").performClick()
        assertEquals(listOf(WifiStep.Action.RETRY), asked)
    }

    @Test fun tappingTheWifiRowItIsOnKeepsTheOtherWaysOpenAndReopeningFoldsThem() {
        val s = screen(btOn, offer)
        button(s, LINK).performClick()
        relayout(s)
        // The rail's Wi-Fi row: the first "Wi-Fi" on screen, tapped through its row.
        var row: View = shown(s).first { it.text.toString() == "Wi-Fi" }
        while (!row.isClickable) row = row.parent as View
        row.performClick()
        relayout(s)
        assertTrue("still open: ${buttons(s)}", "Choose a network" in buttons(s) && LINK !in buttons(s))
        val m = s.model!!
        s.hide()
        s.render(m)
        relayout(s)
        assertTrue("opened again, folded: ${buttons(s)}", LINK in buttons(s) && "Choose a network" !in buttons(s))
    }

    @Test fun onceConnectedAnOlderFailedJoinLineGoes() {
        val s = screen(btOn, offer)
        s.render(s.model!!.copy(installer = InstallerWifi("Example-WiFi", InstallerWifi.Phase.FAILED, "wrong password")))
        relayout(s)
        assertTrue(texts(s).toString(), texts(s).any { it.startsWith("Couldn't join") })
        s.render(s.model!!.copy(wifi = WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi")))
        relayout(s)
        assertTrue(texts(s).toString(), "Connected to Example-WiFi." in texts(s))
        assertFalse(texts(s).toString(), texts(s).any { it.startsWith("Couldn't join") })
    }

    @Test fun theOtherWaysStayOpenOnARefreshAndFoldAgainOnAnotherStep() {
        val s = screen(btOn, offer)
        button(s, LINK).performClick()
        relayout(s)
        s.render(s.model!!.copy(improv = WifiStep.improv(btOn.copy(clients = 1))))
        relayout(s)
        assertTrue("a refresh keeps them open: ${buttons(s)}", "Choose a network" in buttons(s) && LINK !in buttons(s))
        s.render(s.model!!.copy(step = SetupFlow.HA))
        relayout(s)
        s.render(s.model!!.copy(step = SetupFlow.WIFI))
        relayout(s)
        assertTrue("back on Wi-Fi, folded: ${buttons(s)}", LINK in buttons(s) && "Choose a network" !in buttons(s))
    }

    @Test fun notNowIsTheQuietestThingOnTheCard() {
        val s = screen(btOn, offer)
        val notNow = button(s, "Not now")
        assertEquals(Glass.FA, notNow.currentTextColor)
        for (b in shown(s).filter { it.isClickable && it !== notNow }) {
            assertTrue("${b.text} is louder", b.background != null || b.currentTextColor != Glass.FA)
            assertTrue("${b.text} is no smaller", b.textSize >= notNow.textSize)
        }
        assertTrue("and keys reach it", notNow.isFocusable)
    }

    private fun next(s: SetupOverlay, from: View, dir: Int = View.FOCUS_FORWARD): String? =
        (FocusFinder.getInstance().findNextFocus(s, from, dir) as? TextView)?.text?.toString()

    private fun focusOrder(s: SetupOverlay) {
        val allow = button(s, "Allow")
        assertTrue(allow.requestFocusFromTouch())
        assertEquals("Allow, then the link", LINK, next(s, allow))
        assertEquals("then Not now", "Not now", next(s, button(s, LINK)))
        val l = button(s, LINK)
        assertTrue(l.requestFocus())
        l.performClick()
        relayout(s)
        val first = s.findFocus() as? TextView
        assertEquals("the first way takes the link's focus", "Set up from a phone", first?.text?.toString())
        assertEquals("Choose a network", next(s, first!!))
        assertEquals("Not now", next(s, button(s, "Choose a network")))
    }

    @Test fun keysReachTheLinkAndTheOpenedRow() = focusOrder(screen(btOn, offer))

    /** A refresh (Bluetooth's state, a Wi-Fi scan) draws the card again: the control keys are on keeps its focus. */
    @Test fun aRefreshKeepsTheFocusOnTheFootsControls() {
        fun refreshKeeps(s: SetupOverlay, label: String, open: Boolean = false) {
            if (open) { button(s, LINK).performClick(); relayout(s) }
            val b = button(s, label)
            assertTrue(label, b.requestFocusFromTouch())
            s.render(s.model!!.copy(improv = WifiStep.improv(btOn.copy(clients = 0, ssid = "Example-${label.length}"))))
            relayout(s)
            assertTrue("$label keeps the focus, not ${(s.findFocus() as? TextView)?.text}", button(s, label).isFocused)
        }
        refreshKeeps(screen(btOn, offer), "Not now")
        refreshKeeps(screen(btOn, offer), LINK)
        refreshKeeps(screen(btOn, offer), "Choose a network", open = true)
        refreshKeeps(screen(btOn, wifiOff), "Turn on Wi-Fi")
    }

    /**
     * Android 13+ before anyone asked for the hotspot: its button is Allow (Android's permission). Bluetooth setup still
     * leads, and the permission's reason shows only once the hotspot's button does, in the opened other ways.
     */
    @Test fun aFirstVisitsAllowDoesNotTakeTheLeadFromBluetooth() {
        val why = "the reason for Nearby devices"
        val firstVisit = HotspotStep.card(SetupHotspot.View.Off(null, null),
            HotspotPermission.Step.Explain("android.permission.NEARBY_WIFI_DEVICES", why), null)
        val s = screen(btOn, firstVisit)
        val asked = mutableListOf<HotspotStep.Action>()
        s.onHotspotAction = { asked += it }
        assertTrue(texts(s).toString(), "Connect to Wi-Fi." in texts(s))
        assertEquals(listOf("Allow", LINK, "Not now"), buttons(s))
        assertFalse("no reason under the Bluetooth tile", why in texts(s))
        button(s, LINK).performClick()
        relayout(s)
        assertTrue("with its button, its reason: ${texts(s)}", why in texts(s))
        button(s, "Set up from a phone").performClick()
        assertEquals(listOf(HotspotStep.Action.ALLOW), asked)
    }

    @Test @Config(qualifiers = "w540dp-h960dp-port-xhdpi")
    fun keysReachTheLinkAndTheOpenedRowInPortrait() {
        w = 1080; h = 1920
        focusOrder(screen(btOn, offer) { TestFrames.tablet(it) })
    }

    /** Every shown button and line of text lies inside the screen and its card, whole, and no two buttons overlap. */
    private fun fits(size: String, frame: (Activity) -> Frame) {
        for (l in leads) for (open in listOf(false, true)) {
            val s = screen(l.improv, l.spot, frame)
            if (open && l.others.isNotEmpty()) { button(s, LINK).performClick(); relayout(s) }
            val at = "$size, ${l.name}${if (open) ", opened" else ""}"
            val screenRect = Rect(0, 0, w, h)
            val boxes = mutableListOf<Pair<String, Rect>>()
            for (t in shown(s)) {
                val r = Rect().also { t.getGlobalVisibleRect(it) }
                val loc = IntArray(2).also { t.getLocationInWindow(it) }
                val full = Rect(loc[0], loc[1], loc[0] + t.width, loc[1] + t.height)
                assertTrue("$at: '${t.text}' is clipped ($full, visible $r)", screenRect.contains(full) && r == full)
                if (t.isClickable) boxes += t.text.toString() to full
                val layout = t.layout ?: continue
                for (line in 0 until layout.lineCount) assertEquals("$at: '${t.text}' ends in …", 0, layout.getEllipsisCount(line))
                if (t.maxLines == 1) {
                    val want = layout.getLineWidth(0) + t.totalPaddingLeft + t.totalPaddingRight
                    assertTrue("$at: '${t.text}' is cut (${t.width} < $want)", t.width + 1 >= want)
                }
            }
            for (i in boxes.indices) for (j in i + 1 until boxes.size) {
                val (a, ra) = boxes[i]
                val (b, rb) = boxes[j]
                assertFalse("$at: '$a' and '$b' overlap", Rect.intersects(ra, rb))
            }
        }
    }

    private companion object {
        const val LINK = "Other ways to set up ›"
    }

    @Test fun fitsTheS1() = fits("S1") { TestFrames.s1() }

    @Test @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun fitsATablet() {
        w = 1280; h = 800
        fits("tablet") { TestFrames.tablet(it) }
    }

    @Test @Config(qualifiers = "w540dp-h960dp-port-xhdpi")
    fun fitsPortrait() {
        w = 1080; h = 1920
        fits("portrait") { TestFrames.tablet(it) }
    }

    @Test @Config(qualifiers = "w480dp-h800dp-port-hdpi")
    fun fitsASmallPhone() {
        w = 720; h = 1200
        fits("small phone") { TestFrames.tablet(it) }
    }

    @Test fun withoutTouchNoneOfItShows() {
        val s = screen(btOn, offer) { TestFrames.mirror(it) }
        assertTrue(texts(s).toString(), texts(s).none { it == LINK || it == "Not now" || it == "Choose a network" || it == "Use Bluetooth" })
    }
}
