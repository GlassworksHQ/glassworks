package sh.edm.s1kiosk.notifications

import android.app.Application
import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import dev.glassworks.frame.Input
import dev.glassworks.notifications.NotificationCenter
import dev.glassworks.notifications.NotificationOutputs
import dev.glassworks.notifications.NotificationStore
import dev.glassworks.notifications.NotificationsHub
import dev.glassworks.notifications.ActionReport
import dev.glassworks.notifications.Sound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.ui.Mockup
import java.util.concurrent.CompletableFuture

/** The overlay sends taps back to the hub, opens and closes full-screen views, and handles Back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class NotificationOverlayTest {
    private val hub = NotificationsHub()
    private val reports = mutableListOf<ActionReport>()
    private val pages = mutableListOf<String?>()
    private val center = NotificationCenter(
        NotificationStore({ 0L }),
        object : NotificationOutputs {
            override fun render(cards: List<NotificationStore.Entry>, page: NotificationStore.Entry?, list: List<NotificationStore.Entry>) =
                hub.publish(NotificationsHub.State(cards, page, list))
            override fun wake(overNight: Boolean) {}
            override fun play(sound: Sound, critical: Boolean) {}
        },
        { reports += it }, { false }, { "http://homeassistant.local:8123" }, { 0L }, { _, _ -> CompletableFuture<Unit>() },
    ).also { hub.attach(it) }

    private val overlay = Mockup.show(FrameLayout(Mockup.activity())).let { root ->
        NotificationOverlay(root.context, hub, images = null, now = { 0L }).also {
            root.addView(it, FrameLayout.LayoutParams(-1, -1))
            it.onPage = { url -> pages += url }
            hub.addListener { s -> it.update(s) }
        }
    }

    private fun views(v: View): Sequence<View> = sequence {
        yield(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(views(v.getChildAt(i)))
    }

    private fun click(text: String) {
        Mockup.layout(overlay.rootView)
        views(overlay).filterIsInstance<TextView>().first { it.text.toString() == text && it.isShown }.performClick()
        Mockup.idle()
    }

    @Test
    fun aButtonReportsAndCloses() {
        center.handle("Someone is at the door", "Doorbell", """{"tag":"doorbell","actions":[{"action":"open_gate","title":"Open the gate"}]}""")
        click("Open the gate")
        assertEquals(listOf(ActionReport("open_gate", "doorbell")), reports)
        assertTrue(hub.state.cards.isEmpty())
    }

    @Test
    fun theCrossDismisses() {
        center.handle("m", "Washer", "")
        click("×")
        assertTrue(hub.state.list.isEmpty())
        assertTrue(reports.isEmpty())
    }

    @Test
    fun aButtonWithAUriOpensItFullScreen() {
        center.handle("m", "Garage", """{"actions":[{"action":"look","title":"Look","uri":"/lovelace/garage"}]}""")
        click("Look")
        assertEquals(listOf<String?>("http://homeassistant.local:8123/lovelace/garage"), pages)
        assertEquals("Garage", hub.state.page?.notification?.title)
    }

    @Test
    fun aPageOpensAndBackNowReturns() {
        center.handle("Someone is at the door", "Doorbell", """{"url":"/dashboard-door/0","timeout":90}""")
        assertEquals(listOf<String?>("http://homeassistant.local:8123/dashboard-door/0"), pages)
        click("Back now")
        assertEquals(listOf("http://homeassistant.local:8123/dashboard-door/0", null), pages)
        assertNull(hub.state.page)
    }

    /** #230: the open view keeps the screen; its one button says Next while another waits, and opens it. */
    @Test
    fun nextOpensTheViewThatWaits() {
        center.handle("Someone is at the door", "Doorbell", """{"url":"/dashboard-door/0","timeout":90}""")
        center.handle("Open for 20 minutes", "Garage", """{"url":"/garage/0"}""")
        assertEquals(listOf<String?>("http://homeassistant.local:8123/dashboard-door/0"), pages)
        assertEquals("Next in 1:30  ·  1 more waiting", shownText().first { it.startsWith("Next in") })
        assertFalse("the bar counts it, not the pill", shownText().any { it.contains("Show all") })
        click("Next")
        assertEquals("http://homeassistant.local:8123/garage/0", pages.last())
        click("Back now")
        assertEquals(null, pages.last())
        assertNull(hub.state.page)
    }

    /** #230: a half view takes the dashboard's picture, then moves the page into the right half, over the bar's 72 dp. */
    @Test
    fun aHalfViewOpensInADialogOverThePictureOfTheDashboard() {
        val events = halfHooks()
        center.handle("Someone is at the door", "Doorbell", """{"url":"/dashboard-door/0","timeout":90,"size":"half"}""")
        assertEquals(
            listOf("capture", "area Rect(720, 0 - 1440, 612)", "page http://homeassistant.local:8123/dashboard-door/0"),
            events,
        )
        assertTrue(shownText().contains("Closes in 1:30"))
        click("Close")
        assertEquals(listOf("page null", "area null"), events.drop(3).sortedDescending())
        assertNull(hub.state.page)
    }

    /**
     * On a portrait screen (a tablet, the Mirror) the half view is the bottom half: the page above its bar,
     * the bar at the foot, a tap on the dashboard above closes it, and a tap in the sheet goes to the page.
     */
    @Test
    @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    fun onAPortraitScreenTheHalfViewIsTheBottomHalf() {
        val events = halfHooks()
        center.handle("m", "Doorbell", """{"url":"/dashboard-door/0","size":"half","timeout":90}""")
        center.handle("m", "Garage", """{"url":"/garage/0","size":"half"}""")
        assertEquals(listOf("capture", "area Rect(0, 640 - 800, 1208)", "page http://homeassistant.local:8123/dashboard-door/0"), events)
        Mockup.layout(overlay.rootView, 800, 1280)
        val bar = views(overlay).filterIsInstance<TextView>().first { it.isShown && it.text.toString() == "Next" }
        val at = IntArray(2).also { bar.getLocationInWindow(it) }
        assertTrue("the bar is at the foot, under the page: y=${at[1]}", at[1] >= 1208 && at[1] + bar.height <= 1280)
        assertTrue(views(overlay).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == "Next in 1:30  ·  1 more waiting" })
        val behind = views(overlay).first { it.javaClass.simpleName == "HalfDialog" }
        assertFalse("in the sheet: the page's", tap(behind, 400f, 900f))
        assertEquals("Doorbell", hub.state.page?.notification?.title)
        assertTrue("above the sheet: next", tap(behind, 400f, 300f))
        assertEquals("Garage", hub.state.page?.notification?.title)
        assertEquals("the same dialog: no second capture or move", 1, events.count { it == "capture" })
    }

    @Test
    fun aTapOutsideAHalfViewClosesItAndATapInsideGoesToThePage() {
        halfHooks()
        center.handle("m", "Doorbell", """{"url":"/dashboard-door/0","size":"half"}""")
        center.handle("m", "Garage", """{"url":"/garage/0","size":"half"}""")
        Mockup.layout(overlay.rootView)
        val behind = views(overlay).first { it.javaClass.simpleName == "HalfDialog" }
        assertFalse("inside: the page's", tap(behind, 1000f, 300f))
        assertEquals("Doorbell", hub.state.page?.notification?.title)
        assertTrue(tap(behind, 200f, 300f))
        assertEquals("outside: next", "Garage", hub.state.page?.notification?.title)
        assertEquals(listOf("http://homeassistant.local:8123/dashboard-door/0", "http://homeassistant.local:8123/garage/0"), pages)
    }

    /** #230: on a frame without touch, a view's screen has nothing to tap: no button, and taps outside do nothing. */
    @Test
    fun withoutTouchNothingOnAViewTakesATap() {
        overlay.input = Input.NONE
        halfHooks()
        center.handle("m", "Doorbell", """{"url":"/dashboard-door/0","size":"half","timeout":90}""")
        center.handle("m", "Garage", """{"url":"/garage/0","timeout":60}""")
        Mockup.layout(overlay.rootView)
        assertEquals(emptyList<View>(), views(overlay).filter { it.isShown && (it.isClickable || it.hasOnClickListeners()) }.toList())
        assertTrue(shownText().contains("Next in 1:30  ·  1 more waiting"))
        assertFalse(shownText().any { it in setOf("Next", "Close", "Back now") })
        val behind = views(overlay).first { it.javaClass.simpleName == "HalfDialog" }
        assertFalse(tap(behind, 200f, 300f))
        assertEquals("Doorbell", hub.state.page?.notification?.title)
    }

    /** #230: a critical one with a view is the stage, with "Open the view" first; there is no such button without touch. */
    @Test
    fun aCriticalOneOpensItsViewFromTheStage() {
        center.handle("The kitchen smoke alarm went off.", "Smoke in the kitchen", """{"priority":"critical","url":"/fire/0"}""")
        assertTrue("the stage, not the view", pages.isEmpty())
        overlay.input = Input.NONE
        overlay.update(hub.state)
        Mockup.layout(overlay.rootView)
        assertFalse(shownText().contains(NotificationOverlay.OPEN_THE_VIEW))
        overlay.input = Input.TOUCH
        overlay.update(hub.state)
        click(NotificationOverlay.OPEN_THE_VIEW)
        assertEquals(listOf<String?>("http://homeassistant.local:8123/fire/0"), pages)
        assertTrue(hub.state.cards.isEmpty())
        assertTrue(shownText().contains("Back now"))
    }

    private fun halfHooks(): MutableList<String> {
        val events = mutableListOf<String>()
        overlay.capturePage = { done -> events += "capture"; done(Bitmap.createBitmap(1440, 720, Bitmap.Config.ARGB_8888)) }
        overlay.onPageArea = { area -> events += "area $area" }
        overlay.onPage = { url -> pages += url; events += "page $url" }
        return events
    }

    private fun shownText(): List<String> {
        Mockup.layout(overlay.rootView)
        return views(overlay).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }.toList()
    }

    /** A tap at ([x], [y]) on [v]: whether it took it. */
    private fun tap(v: View, x: Float, y: Float): Boolean {
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0)
        val took = v.dispatchTouchEvent(down)
        if (took) v.dispatchTouchEvent(MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, x, y, 0))
        Mockup.idle()
        return took
    }

    @Test
    fun backClosesTheListThenThePage() {
        center.handle("m", "Doorbell", """{"url":"/dashboard-door/0"}""")
        overlay.openList()
        assertTrue(overlay.back())
        assertFalse(overlay.listOpen)
        assertTrue(overlay.back())
        assertNull(hub.state.page)
        assertFalse("nothing left to close", overlay.back())
    }

    @Test
    fun theCriticalStageAlwaysHasDismiss() {
        center.handle("Under the sink", "Water leak", """{"priority":"critical","actions":[{"action":"close_valve","title":"Close the valve"}]}""")
        click("Dismiss")
        assertTrue(hub.state.cards.isEmpty())
        assertTrue("dismissing isn't a button press", reports.isEmpty())
    }

    /** CodeRabbit on #228: with a long title, a long message and three buttons, Dismiss stays on the 480 dp screen. */
    @Test
    fun aLongCriticalAlertKeepsItsButtonsOnScreen() {
        center.handle(
            "The leak sensor under the kitchen sink turned on at 14:32. The main valve is still open, and the " +
                "dishwasher is running. Water is reaching the floor near the fridge.",
            "Water under the kitchen sink and behind the dishwasher",
            """{"priority":"critical","actions":[{"action":"a","title":"Close the valve"},{"action":"b","title":"Call"},{"action":"c","title":"Silence"}]}""",
        )
        Mockup.layout(overlay.rootView)
        val dismiss = views(overlay).filterIsInstance<TextView>().first { it.text.toString() == "Dismiss" }
        val at = IntArray(2).also { dismiss.getLocationInWindow(it) }
        assertTrue("Dismiss has its height (${dismiss.height})", dismiss.height > 0)
        assertTrue("Dismiss ends on screen (${at[1] + dismiss.height} ≤ ${Mockup.H})", at[1] + dismiss.height <= Mockup.H)
    }

    /** CodeRabbit on #228: a picture that arrives after the overlay left the window is dropped. */
    @Test
    fun aLatePictureAfterDetachIsDropped() {
        val release = java.util.concurrent.CountDownLatch(1)
        val root = Mockup.show(FrameLayout(Mockup.activity()))
        val o = NotificationOverlay(root.context, hub, images = dev.glassworks.notifications.NotificationImages {
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            dev.glassworks.notifications.ImageFetch.Failed("late")
        }, now = { 0L })
        root.addView(o, FrameLayout.LayoutParams(-1, -1))
        hub.addListener { s -> o.update(s) }
        center.handle("m", "Porch", """{"image":"/api/camera_proxy/camera.porch"}""")
        root.removeView(o)
        release.countDown()
        repeat(10) { Thread.sleep(30); Mockup.idle() }
        val pictures = NotificationOverlay::class.java.getDeclaredField("pictures").apply { isAccessible = true }.get(o) as Map<*, *>
        assertTrue("nothing stored after detach", pictures.isEmpty())
    }

    /** Verifier: the list keeps its scroll position when a notification comes or goes. */
    @Test
    fun theListKeepsItsScroll() {
        repeat(12) { center.handle("Message $it", "Notification $it", """{"tag":"t$it"}""") }
        overlay.openList()
        Mockup.idle(); Mockup.layout(overlay.rootView)
        val list = { views(overlay).filterIsInstance<android.widget.ScrollView>().first() }
        list().scrollTo(0, 300)
        assertEquals(300, list().scrollY)
        center.handle("Message new", "Notification new", "")
        Mockup.layout(overlay.rootView); Mockup.idle(); Mockup.layout(overlay.rootView)
        assertEquals(300, list().scrollY)
    }

    /**
     * S1 run 2026-11-14: the critical stage stood still. Its orb now runs the timer-done stage's motion
     * (a ripple every 1.5 s, the core pulsing) while the stage is shown, and stops on Dismiss.
     */
    @Test
    fun theCriticalStageAnimatesUntilDismissed() {
        val orb = overlay.criticalOrb
        assertFalse("nothing critical: no loop", orb.running)
        center.handle("Under the sink", "Water leak", """{"priority":"critical"}""")
        assertTrue(orb.running)
        val scales = (1..6).map {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250))
            orb.coreScale
        }
        assertTrue("frames are drawn (${orb.frames})", orb.frames > 5)
        assertTrue("the core pulses: $scales", scales.toSet().size > 1)
        click("Dismiss")
        assertFalse("dismissed: the loop stops", orb.running)
        val after = orb.frames
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_000))
        assertEquals("no frames after", after, orb.frames)
    }

    /** Verifier on #228: with the screen off the critical orb doesn't tick; it resumes when the screen is on. */
    @Test
    fun theCriticalOrbRestsWhileTheScreenIsOff() {
        var screen = false
        overlay.screenOn = { screen }
        center.handle("Under the sink", "Water leak", """{"priority":"critical"}""")
        val orb = overlay.criticalOrb
        assertFalse("the screen is off: no ticks", orb.running)
        val frames = orb.frames
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2_000))
        assertEquals(frames, orb.frames)
        screen = true
        overlay.update(hub.state)   // MainActivity does this when the screen state changes
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
        assertTrue("the screen is on: it moves", orb.frames > frames + 5)
    }
}
