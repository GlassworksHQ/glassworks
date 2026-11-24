package dev.glassworks.setup

import android.app.Application
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.glassworks.pane.PaneChoice
import dev.glassworks.s1.frame.TestFrames
import dev.glassworks.ui.GroupedRail
import dev.glassworks.ui.Mockup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Setup on a phone (the generic app on a 360x640 dp screen, the smallest common one), either way up: every step keeps
 * its heading, its words and its buttons on screen, the buttons stack when they don't fit across, the card's text
 * scrolls above them, and upright the steps run across the top. The S1's 960x480 dp keeps its own layout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h640dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real text sizes: what fits is what the fonts leave
class SetupPhoneTest {
    @Before fun interFromSources() = Mockup.interFromSources()

    private val names = listOf(
        "Voice" to "Assist and timers", "Music" to "A Sendspin speaker", "Notifications" to "On-screen alerts",
        "Bluetooth proxy" to "For BLE sensors", "Camera" to "The panel's camera in HA", "Matter" to "Join Matter devices",
    )
    private val online = WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi")
    private val offline = WifiSetupStatus.Snapshot(WifiSetupStatus.State.NOT_CONNECTED)

    private fun model(step: String, wifi: WifiSetupStatus.Snapshot = online, done: Set<String> = SetupFlow.ids(true, emptyList()).takeWhile { it != step }.toSet()) =
        SetupOverlay.Model(
            step = step, done = done, wifi = wifi, haUrl = "http://homeassistant.local:8123", haSource = "mdns",
            panelIp = "192.0.2.10", signedIn = false,
        )

    private fun screen(m: SetupOverlay.Model, w: Int, h: Int, phone: Boolean = true): SetupOverlay {
        val a = Mockup.activity()
        val frame = if (phone) TestFrames.phone(a) else TestFrames.s1()
        val s = SetupOverlay(a, frame, names.map { (t, d) -> SetupPane(PaneChoice(t, d, on = { false }, apply = { null }), emptyList()) })
        a.setContentView(s, ViewGroup.LayoutParams(-1, -1))
        s.render(m)
        // Laid out by the activity's window at the screen's size, less its bars: twice, for what the first pass moved
        // (the rail's strip, the card's scroll).
        Mockup.idle()
        s.requestLayout()
        Mockup.idle()
        assertTrue("laid out ${s.width}x${s.height} for a ${w}x$h screen", s.width in w - 200..w && s.height in h - 200..h)
        return s
    }

    private fun views(v: View): Sequence<View> = sequence {
        yield(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(views(v.getChildAt(i)))
    }
    private fun rect(v: View) = Rect().also { v.getGlobalVisibleRect(it) }
    private fun whole(v: View) = Rect().let { r -> v.getGlobalVisibleRect(r) && r.height() == v.height && r.width() == v.width }
    private fun cut(t: TextView) = t.layout?.let { l -> (0 until l.lineCount).any { l.getEllipsisCount(it) > 0 } } ?: true
    private fun buttons(s: View) = views(s).filterIsInstance<TextView>().filter { it.isShown && it.isClickable && it.text.isNotEmpty() }.toList()
    private fun heading(s: View, text: String) = Mockup.label(s, text)

    /** The steps a phone shows most, each with its heading. */
    private val steps = listOf(
        model(SetupFlow.WIFI, done = setOf(SetupFlow.WIFI)) to "Connected to Example-WiFi",
        model(SetupFlow.HA) to "Add it in Home Assistant",
        model(SetupFlow.HA).copy(haUrl = null) to "Find Home Assistant",
        model(SetupFlow.SIGNIN) to "Scan to sign in",
        model(SetupFlow.PANES) to "What should it do?",
    )

    @Test fun uprightEveryHeadingIsWhole() = headingsWhole(720, 1280, "upright")

    @Test @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun onItsSideEveryHeadingIsWhole() = headingsWhole(1280, 720, "on its side")

    @Test fun uprightEveryButtonIsOnScreenAndNoneOverlap() = buttonsOnScreen(720, 1280, "upright")

    @Test @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun onItsSideEveryButtonIsOnScreenAndNoneOverlap() = buttonsOnScreen(1280, 720, "on its side")

    private fun headingsWhole(w: Int, h: Int, way: String) {
        for ((m, title) in steps) assertFalse("$way: \"$title\" cut short", cut(heading(screen(m, w, h), title)))
    }

    private fun buttonsOnScreen(w: Int, h: Int, way: String) {
        for ((m, title) in steps) {
            val s = screen(m, w, h)
            val rail = views(s).filterIsInstance<GroupedRail>().single()
            val shown = buttons(s).filter { b -> generateSequence(b as View) { it.parent as? View }.none { it === rail } }
            val screen = rect(s)
            for (b in shown) {
                assertTrue("$way, $title: \"${b.text}\" off screen at ${rect(b)}", screen.contains(rect(b)) && whole(b))
                assertFalse("$way, $title: \"${b.text}\" cut short", cut(b))
            }
            for ((i, a) in shown.withIndex()) for (b in shown.drop(i + 1)) {
                assertFalse("$way, $title: \"${a.text}\" overlaps \"${b.text}\"", Rect.intersects(rect(a), rect(b)))
            }
        }
    }

    /** Upright, "Use another network" and Continue don't fit side by side: one over the other, across the card. */
    @Test fun buttonsThatDontFitAcrossStack() {
        val s = screen(steps[0].first, 720, 1280)
        val go = rect(Mockup.label(s, "Continue"))
        val other = rect(Mockup.label(s, "Use another network"))
        assertTrue("Continue above Use another network", go.bottom <= other.top)
        assertEquals("as wide as each other", go.width(), other.width())
    }

    /** On its side they fit: one row, as on the S1. */
    @Test @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun buttonsThatFitStayInARow() {
        val s = screen(steps[0].first, 1280, 720)
        assertEquals(rect(Mockup.label(s, "Continue")).centerY(), rect(Mockup.label(s, "Use another network")).centerY())
    }

    @Test fun uprightTheStepsRunAcrossTheTop() {
        val rail = views(screen(steps[1].first, 720, 1280)).filterIsInstance<GroupedRail>().single()
        assertEquals(LinearLayout.HORIZONTAL, rail.orientation)
    }

    @Test @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun onItsSideTheStepsAreAColumn() {
        val rail = views(screen(steps[1].first, 1280, 720)).filterIsInstance<GroupedRail>().single()
        assertEquals(LinearLayout.VERTICAL, rail.orientation)
    }

    /** Upright, one card to a row; the whole card scrolls to the last one, and Continue stays whole under it. */
    @Test fun uprightPanesAreOneToARowAndScrollToTheLast() {
        val s = screen(model(SetupFlow.PANES), 720, 1280)
        fun card(t: String): View = generateSequence(Mockup.label(s, t) as View) { it.parent as? View }.first { it.isClickable }
        assertTrue("Music under Voice", rect(card("Voice")).bottom <= rect(card("Music")).top)
        for ((t, d) in names) {
            assertFalse("\"$t\" cut short", cut(Mockup.label(s, t)))
            assertFalse("\"$d\" cut short", cut(Mockup.label(s, d)))
        }
        val scroll = generateSequence(card("Matter")) { it.parent as? View }.filterIsInstance<ScrollView>().last()
        assertTrue("the card scrolls with six panes", scroll.canScrollVertically(1))
        scroll.scrollTo(0, scroll.getChildAt(0).height)
        Mockup.idle()
        assertTrue("the last card in full", whole(card("Matter")))
        val go = Mockup.label(s, "Continue")
        assertTrue("Continue in full: ${rect(go)}, ${go.width}x${go.height}", whole(go))
    }

    @Test @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun onItsSidePanesAreTwoToARow() {
        val s = screen(model(SetupFlow.PANES), 1280, 720)
        fun card(t: String): View = generateSequence(Mockup.label(s, t) as View) { it.parent as? View }.first { it.isClickable }
        assertEquals(rect(card("Voice")).top, rect(card("Music")).top)
    }

    /** The S1's own size is no phone: its steps in a column, its heading at full size, no scroll around the card's text. */
    @Test
    @Config(qualifiers = "w960dp-h480dp-land-hdpi")
    fun theS1KeepsItsLayout() {
        val s = screen(steps[1].first, Mockup.W, Mockup.H, phone = false)
        assertEquals(LinearLayout.VERTICAL, views(s).filterIsInstance<GroupedRail>().single().orientation)
        val title = heading(s, steps[1].second)
        assertEquals(45f, title.textSize / s.resources.displayMetrics.scaledDensity, 0.01f)
        assertTrue("no scroll around the card's text", generateSequence(title as View) { it.parent as? View }.none { it is ScrollView })
    }
}
