package dev.glassworks.notifications

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.provider.Settings
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import dev.glassworks.ui.Mockup
import java.time.Duration
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

    /** The overlay's clock. The store's stays at 0, so a view with a timeout of 90 s ends at 90 000. */
    private var t = 0L

    private val overlay = Mockup.show(FrameLayout(Mockup.activity())).let { root ->
        NotificationOverlay(root.context, hub, images = null, now = { t }).also {
            root.addView(it, FrameLayout.LayoutParams(-1, -1))
            // Robolectric's window stays GONE; a resumed activity's window on the panel is VISIBLE (the screen on).
            it.dispatchWindowVisibilityChanged(View.VISIBLE)
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

    /** A picture that never comes doesn't hold the view: past the deadline it opens over the backdrop; a late one is let go. */
    @Test
    fun aLateCaptureOpensTheViewWithoutThePicture() {
        val events = halfHooks()
        var deliver: ((Bitmap?) -> Unit)? = null
        overlay.capturePage = { done -> events += "capture"; deliver = done }
        center.handle("m", "Doorbell", """{"url":"/dashboard-door/0","size":"half","timeout":90}""")
        assertEquals("waiting for the picture", listOf("capture"), events)
        Mockup.idle()
        // By now the overlay has had a layout in Robolectric's window, which is shorter than the panel: place by its size.
        val area = NotificationOverlay.dialogRect(overlay.width, overlay.height).apply { bottom -= 108 }
        assertEquals(listOf("capture", "area $area", "page http://homeassistant.local:8123/dashboard-door/0"), events)
        assertTrue(shownText().contains("Closes in 1:30"))
        val late = Bitmap.createBitmap(1440, 720, Bitmap.Config.ARGB_8888)
        deliver!!(late)
        assertTrue("a late picture is recycled, not shown", late.isRecycled)
        assertEquals(3, events.size)
    }

    /** With the screen off the activity is stopped and GeckoView holds the capture: the deadline counts from the screen on. */
    @Test
    fun theDeadlineWaitsForTheScreen() {
        val events = halfHooks()
        var deliver: ((Bitmap?) -> Unit)? = null
        overlay.capturePage = { done -> events += "capture"; deliver = done }
        overlay.dispatchWindowVisibilityChanged(View.GONE)
        center.handle("m", "Doorbell", """{"url":"/dashboard-door/0","size":"half","timeout":90}""")
        Mockup.idle()
        assertEquals("the screen is off: still waiting", listOf("capture"), events)
        overlay.dispatchWindowVisibilityChanged(View.VISIBLE)
        val picture = Bitmap.createBitmap(1440, 720, Bitmap.Config.ARGB_8888)
        deliver!!(picture)
        assertEquals(3, events.size)
        assertFalse("the picture came in time and is shown", picture.isRecycled)
    }

    /** The maintainer on #429: the bar slides over the time left in one animation, and stops when the view closes. */
    @Test
    fun theBarSlidesOverTheTimeLeftAndStopsOnClose() {
        t = 12_000
        center.handle("m", "Doorbell", """{"url":"/dashboard-door/0","timeout":90}""")
        val bar = timeLeft()
        val slide = slide(bar)!!
        assertTrue(slide.isRunning)
        assertEquals("over the 78 s left", 78_000L, slide.duration)
        assertEquals("from where the time is", 78f / 90, fill(bar).scaleX, 0.001f)
        assertEquals(0f, fill(bar).pivotX)
        Mockup.layout(overlay.rootView)
        assertTrue("laid out: the fill has its pixels", fill(bar).width > 0)
        // Seek the animation itself: Robolectric's vsync shadow moves the clock on each frame, so idling runs it fast.
        slide.currentPlayTime = 39_000
        assertEquals("halfway, linear, in whole pixels of the fill", 39f / 90, fill(bar).scaleX, 1f / fill(bar).width)
        assertTrue("the text still says the time", shownText().contains("Back to your dashboard in 1:18"))
        assertTrue("the tick doesn't rebuild the bar", timeLeft() === bar)
        click("Back now")
        assertFalse("cancelled on close", slide.isStarted)
        assertNull(slide(bar))
    }

    @Test
    fun theBarStopsWithTheScreenAndKeepsRealTimeAtAnyAnimationScale() {
        center.handle("m", "Doorbell", """{"url":"/dashboard-door/0","size":"half","timeout":90}""")
        val bar = timeLeft()
        val first = slide(bar)!!
        overlay.dispatchWindowVisibilityChanged(View.GONE)
        assertFalse("nothing moves on a sleeping panel", first.isStarted)
        assertNull(slide(bar))
        t = 30_000
        animationScale(2f)
        try {
            overlay.dispatchWindowVisibilityChanged(View.VISIBLE)
            val again = slide(bar)!!
            assertEquals("60 s left, at twice the animation scale: half the duration", 30_000L, again.duration)
            assertEquals(60f / 90, fill(bar).scaleX, 0.001f)
        } finally {
            animationScale(1f)
        }
    }

    @Test
    fun withAnimationsOffTheBarStepsEachSecond() {
        animationScale(0f)
        try {
            center.handle("m", "Doorbell", """{"url":"/dashboard-door/0","timeout":90}""")
            val bar = timeLeft()
            assertNull("no animation", slide(bar))
            assertEquals(1f, fill(bar).scaleX, 0.001f)
            t = 30_000
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100))
            assertEquals("stepped with the text", 60f / 90, fill(bar).scaleX, 0.001f)
            assertTrue(shownText().contains("Back to your dashboard in 1:00"))
        } finally {
            animationScale(1f)
        }
    }

    /** CodeRabbit on #429: Battery Saver turns animators off past the setting (it stays 1): the bar steps, it doesn't empty. */
    @Test
    fun whenTheSystemEndsTheAnimationEarlyTheBarStepsFromWhereItIs() {
        val effective = ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType)
        effective.invoke(null, 0f)
        try {
            center.handle("m", "Doorbell", """{"url":"/dashboard-door/0","timeout":90}""")
            val bar = timeLeft()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            assertNull("the animation ended at once and let go", slide(bar))
            assertEquals("not empty: where the time is", 1f, fill(bar).scaleX, 0.001f)
            t = 30_000
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100))
            assertEquals("steps with the text", 60f / 90, fill(bar).scaleX, 0.001f)
        } finally {
            effective.invoke(null, 1f)
        }
    }

    /** CodeRabbit on #429: on a narrow portrait screen the stage's buttons wrap, and each, Dismiss too, keeps its one line. */
    @Test
    @Config(qualifiers = "w480dp-h800dp-port-hdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun onANarrowScreenTheStageButtonsWrapAndKeepTheirWidth() {
        val buttons = stageButtons(720, 1200)
        assertTrue("more than one row: ${buttons.map { it.second }}", buttons.map { it.second }.distinct().size > 1)
        for ((b, _) in buttons) {
            assertEquals("${b.text} on one line", 1, b.lineCount)
            val at = IntArray(2).also { b.getLocationInWindow(it) }
            assertTrue("${b.text} on screen: ${at[0]}..${at[0] + b.width}", at[0] >= 0 && at[0] + b.width <= 720)
        }
        assertEquals("the same order: Dismiss last", "Dismiss", buttons.last().first.text.toString())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun onTheS1TheStageButtonsStayInOneRow() {
        val buttons = stageButtons(1440, 720)
        assertEquals(1, buttons.map { it.second }.distinct().size)
        assertEquals(listOf(NotificationOverlay.OPEN_THE_VIEW, "Close the valve", "Call", "Silence", "Dismiss"), buttons.map { it.first.text.toString() })
    }

    /** A critical one with a view and three buttons, laid out at [w] x [h]: each button and the top of its row. */
    private fun stageButtons(w: Int, h: Int): List<Pair<TextView, Int>> {
        center.handle(
            "The leak sensor turned on.", "Water under the sink",
            """{"priority":"critical","url":"/leak/0","actions":[{"action":"valve","title":"Close the valve"},{"action":"call","title":"Call"},{"action":"silence","title":"Silence"}]}""",
        )
        Mockup.layout(overlay.rootView, w, h)
        val row = views(overlay).filterIsInstance<TextView>().first { it.text.toString() == "Dismiss" && it.isShown }.parent as ViewGroup
        return (0 until row.childCount).map { row.getChildAt(it) as TextView }
            .map { b -> b to IntArray(2).also { b.getLocationInWindow(it) }[1] }
    }

    /** The system's animator duration scale (Developer options, or 0 with Remove animations): the setting, as the system keeps it. */
    private fun animationScale(scale: Float) {
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
    }

    private fun timeLeft(): ViewGroup = views(overlay).filterIsInstance<ViewGroup>().single { it.javaClass.simpleName == "TimeLeft" && it.isAttachedToWindow }
    private fun fill(bar: ViewGroup): View = bar.getChildAt(0)
    private fun slide(bar: ViewGroup): ValueAnimator? =
        bar.javaClass.getDeclaredField("animator").apply { isAccessible = true }.get(bar) as ValueAnimator?

    @Test
    fun thePictureIsLetGoWhenTheViewClosesAndWhenTheOverlayGoes() {
        val pictures = mutableListOf<Bitmap>()
        halfHooks()
        overlay.capturePage = { done -> done(Bitmap.createBitmap(1440, 720, Bitmap.Config.ARGB_8888).also { pictures += it }) }
        center.handle("m", "Doorbell", """{"url":"/dashboard-door/0","size":"half"}""")
        assertFalse(pictures[0].isRecycled)
        click("Close")
        Mockup.idle()
        assertTrue("recycled after Close", pictures[0].isRecycled)
        center.handle("m", "Garage", """{"url":"/garage/0","size":"half"}""")
        assertFalse(pictures[1].isRecycled)
        (overlay.parent as ViewGroup).removeView(overlay)
        assertTrue("recycled when the overlay goes", pictures[1].isRecycled)
    }

    /** The S1 run's finding B (2026-11-16): a queued half view after a half view keeps the dimmed picture behind it. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aQueuedHalfViewKeepsThePictureAndTheScrim() {
        val events = halfHooks()
        overlay.capturePage = { done ->
            events += "capture"
            done(Bitmap.createBitmap(1440, 720, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) })
        }
        center.handle("m", "Doorbell", """{"url":"/dashboard-door/0","size":"half"}""")
        center.handle("m", "Garage", """{"url":"/garage/0","size":"half"}""")
        click("Next")
        Mockup.idle()
        assertEquals("Garage", hub.state.page?.notification?.title)
        assertEquals("one picture, kept", 1, events.count { it == "capture" })
        val behind = views(overlay).first { it.javaClass.simpleName == "HalfDialog" }
        assertTrue(behind.isShown)
        val drawn = Bitmap.createBitmap(1440, 720, Bitmap.Config.ARGB_8888)
        behind.draw(Canvas(drawn))
        val left = drawn.getPixel(200, 300)
        assertTrue("the picture, dimmed: ${Integer.toHexString(left)}", Color.red(left) in 120..180 && Color.green(left) < 30 && Color.alpha(left) == 255)
        assertEquals("the dialog lets the page show", 0, Color.alpha(drawn.getPixel(1000, 300)))
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
