package sh.edm.s1kiosk.notifications

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import dev.glassworks.frame.Input
import dev.glassworks.notifications.Delivery
import dev.glassworks.notifications.ImageFetch
import dev.glassworks.notifications.Notification
import dev.glassworks.notifications.NotificationAction
import dev.glassworks.notifications.NotificationImages
import dev.glassworks.notifications.NotificationStore.Entry
import dev.glassworks.notifications.NotificationsDefinition
import dev.glassworks.notifications.NotificationsHub
import dev.glassworks.notifications.Priority
import dev.glassworks.notifications.ViewSize
import dev.glassworks.pane.PaneChoice
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.PanePrefs
import sh.edm.s1kiosk.radio.FixedReadout
import sh.edm.s1kiosk.radio.RadioMode
import sh.edm.s1kiosk.radio.radioDefinition
import sh.edm.s1kiosk.settings.KioskSettingsOverlay
import sh.edm.s1kiosk.settings.MenuFixture
import sh.edm.s1kiosk.setup.ImprovFacts
import sh.edm.s1kiosk.setup.SetupFlow
import sh.edm.s1kiosk.setup.SetupOverlay
import sh.edm.s1kiosk.setup.SetupPane
import sh.edm.s1kiosk.setup.WifiSetupStatus
import sh.edm.s1kiosk.setup.WifiStep
import sh.edm.s1kiosk.setup.s1SetupOverlay
import sh.edm.s1kiosk.ui.Mockup
import java.io.ByteArrayOutputStream

/**
 * Screenshots of the real notifications views (#118): [NotificationOverlay] over a stand-in for a light
 * Home Assistant dashboard, the pane's settings page and its card on setup's Panes step, at the panel's
 * 1440x720 px. Illustrative values only: made-up names, a drawn porch with nobody in it. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*NotificationsMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationsMockups {

    @Before fun interFromSources() = Mockup.interFromSources()

    /**
     * Still frames, as a panel with Remove animations: the time-left bar steps instead of sliding. A running
     * animator is a Choreographer loop, which Robolectric's paused looper runs a vsync at a time through every idle
     * (the orb avoids Choreographer for this reason), so the shots took over 10 minutes with it.
     */
    @Before fun stillFrames() = animationScale(0f)
    @After fun animationsBack() = animationScale(1f)

    private fun animationScale(scale: Float) {
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
    }

    private val now = 10_000_000L
    private val HA = "http://homeassistant.local:8123"
    private var nextId = 1L

    private fun entry(
        title: String, message: String, p: Priority = Priority.NORMAL, dnd: Boolean = false, agoMs: Long = 0,
        image: String? = null, actions: List<String> = emptyList(), timeout: Int? = null, url: String? = null,
        tag: String? = null, held: Boolean? = null,
    ): Entry {
        val n = Notification(
            title, message, tag = tag, priority = p, image = image,
            actions = actions.map { NotificationAction(it.lowercase().replace(' ', '_'), it) }, timeoutS = timeout, url = url,
        )
        val d = Delivery.of(p, dnd)
        return Entry(nextId++, n, now - agoMs, d, held ?: d.held)
    }

    /** A picture of a porch at night, as a JPEG from "Home Assistant". */
    private val porchJpeg: ByteArray by lazy {
        val b = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
        Porch.draw(Canvas(b), RectF(0f, 0f, 640f, 400f), 1f)
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private val images = NotificationImages { ImageFetch.Ok(porchJpeg, "image/jpeg") }
    private val noSignIn = NotificationImages { ImageFetch.Failed("the panel isn't signed in to Home Assistant") }

    private fun shot(name: String, state: NotificationsHub.State, door: Boolean = false, imgs: NotificationImages = images, then: (NotificationOverlay) -> Unit = {}) {
        val root = Mockup.show(FrameLayout(Mockup.activity()))
        root.addView(Dashboard(root.context, door), FrameLayout.LayoutParams(-1, -1))
        val o = NotificationOverlay(root.context, NotificationsHub(), imgs, now = { now })
        root.addView(o, FrameLayout.LayoutParams(-1, -1))
        o.update(state)
        then(o)
        // Pictures load on a worker thread, and the critical orb's layers bake on another; both come back
        // through the main looper.
        val critical = state.cards.any { it.notification.priority == Priority.CRITICAL }
        repeat(if (critical) 60 else 10) { Thread.sleep(if (critical) 100 else 50); Mockup.idle() }
        Mockup.layout(root)
        Mockup.save(name, root)
    }

    private val washer = entry("Washing machine", "Done. 1 h 32 min, 0.84 kWh.", actions = listOf("Start the dryer"), timeout = 1800, agoMs = 20_000)
    private val parcel = entry(
        "Parcel at the door", "The porch camera saw a delivery.", Priority.HIGH,
        image = "http://homeassistant.local:8123/api/camera_proxy/camera.porch", actions = listOf("Open the gate", "Snooze 10 min"),
    )

    @Test
    fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)

        shot("notify-01-card-normal", NotificationsHub.State(cards = listOf(washer), list = listOf(washer)))
        shot("notify-02-card-important-picture", NotificationsHub.State(cards = listOf(parcel), list = listOf(parcel)))
        val bin = entry("Bin day", "Put the green bin out tonight.", Priority.LOW, agoMs = 300_000)
        shot("notify-03-card-low", NotificationsHub.State(cards = listOf(bin), list = listOf(bin)))
        val garage = entry("Garage door open", "Open for 20 minutes.", agoMs = 60_000)
        val three = listOf(washer, garage, bin)
        shot("notify-04-stacked", NotificationsHub.State(cards = three, list = three))
        val door = entry("Doorbell", "Someone rang at the front door.", Priority.HIGH, image = parcel.notification.image, url = "http://homeassistant.local:8123/dashboard-door/0", held = true)
        val four = listOf(door, washer, garage, bin)
        shot("notify-05-list", NotificationsHub.State(list = four)) { it.openList() }
        val heldWasher = entry("Washing machine", "Done. 1 h 32 min, 0.84 kWh.", dnd = true, agoMs = 120_000)
        val heldParcel = entry("Parcel at the door", "The porch camera saw a delivery.", Priority.HIGH, dnd = true, agoMs = 540_000, image = parcel.notification.image)
        shot("notify-06-do-not-disturb", NotificationsHub.State(list = listOf(heldWasher, heldParcel), doNotDisturb = true))
        shot("notify-07-do-not-disturb-list", NotificationsHub.State(list = listOf(heldWasher, heldParcel, bin), doNotDisturb = true)) { it.openList() }
        val page = entry("Doorbell", "Someone is at the door", Priority.HIGH, timeout = 90, agoMs = 6_000, url = "http://homeassistant.local:8123/dashboard-door/0", tag = "doorbell")
        shot("notify-08-full-screen-page", NotificationsHub.State(page = page, list = listOf(page)), door = true)
        val leak = entry("Water under the kitchen sink", "The leak sensor turned on at 14:32. The main valve is still open.", Priority.CRITICAL, actions = listOf("Close the valve"), tag = "water_leak")
        shot("notify-09-critical", NotificationsHub.State(cards = listOf(leak), list = listOf(leak)))
        val longLeak = entry(
            "Water under the kitchen sink and behind the dishwasher",
            "The leak sensor under the kitchen sink turned on at 14:32. The main valve is still open, and the dishwasher is running. Water is reaching the floor near the fridge.",
            Priority.CRITICAL, actions = listOf("Close the valve", "Call", "Silence"), tag = "water_leak",
        )
        shot("notify-14-critical-long", NotificationsHub.State(cards = listOf(longLeak), list = listOf(longLeak)))
        shot("notify-10-card-no-picture", NotificationsHub.State(cards = listOf(parcel), list = listOf(parcel)), imgs = noSignIn)

        // #230: a view's one button, a queue of two, and a critical one's view from its stage.
        val doorView = view("Doorbell", "/dashboard-door/0", timeout = 90, agoMs = 6_000)
        val garageView = view("Garage door open", "/garage/0", timeout = 60, agoMs = 19_000)
        viewShot("notify-15-view-full-next", doorView, listOf(garageView), Ha.DOOR)
        viewShot("notify-16-view-full-back-now", garageView, emptyList(), Ha.GARAGE)
        val doorHalf = view("Doorbell", "/dashboard-door/0", timeout = 90, agoMs = 6_000, half = true)
        val garageHalf = view("Garage door open", "/garage/0", timeout = 60, agoMs = 19_000, half = true)
        viewShot("notify-17-view-half-next", doorHalf, listOf(garageHalf), Ha.DOOR)
        viewShot("notify-18-view-half-close", garageHalf, emptyList(), Ha.GARAGE)
        val smoke = entry("Smoke in the kitchen", "The kitchen smoke alarm went off at 14:32.", Priority.CRITICAL, tag = "smoke", url = "$HA/fire/0")
        viewShot("notify-22-critical-with-view", null, emptyList(), Ha.HOME, cards = listOf(smoke))

        settings("notify-11-settings", on = true, listOf(washer, heldWasher, bin))
        settings("notify-12-settings-off", on = false, emptyList())
        setupPanes()
    }

    /** A portrait tablet (#230): the half view is the bottom half. */
    @Test
    @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    fun portraitTablet() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        val door = view("Doorbell", "/dashboard-door/0", timeout = 90, agoMs = 6_000, half = true)
        viewShot("notify-19-tablet-portrait-half-next", door, listOf(view("Garage door open", "/garage/0", timeout = 60, half = true)), Ha.DOOR, w = 800, h = 1280)
    }

    /** A narrow portrait screen (CodeRabbit on #429): a critical one with its view and three buttons, all readable with Dismiss. */
    @Test
    @Config(qualifiers = "w480dp-h800dp-port-hdpi")
    fun portraitCritical() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        val leak = entry(
            "Water under the kitchen sink", "The leak sensor turned on at 14:32. The main valve is still open.",
            Priority.CRITICAL, actions = listOf("Close the valve", "Call", "Silence"), tag = "water_leak", url = "$HA/leak/0",
        )
        viewShot("notify-23-portrait-critical-buttons", null, emptyList(), Ha.HOME, cards = listOf(leak), w = 720, h = 1200)
    }

    /** A frame without touch, the Mirror's portrait screen (#230): no button; the countdown moves on. */
    @Test
    @Config(qualifiers = "w540dp-h960dp-port-xhdpi")
    fun noTouch() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        val door = view("Doorbell", "/dashboard-door/0", timeout = 90, agoMs = 6_000, half = true)
        val garage = view("Garage door open", "/garage/0", timeout = 120, agoMs = 79_000)
        viewShot("notify-20-no-touch-half", door, listOf(view("Garage door open", "/garage/0", timeout = 60, half = true)), Ha.DOOR,
            w = 1080, h = 1920, dark = true, input = Input.NONE)
        viewShot("notify-21-no-touch-full", garage, emptyList(), Ha.GARAGE, w = 1080, h = 1920, dark = true, input = Input.NONE)
    }

    private fun view(title: String, path: String, timeout: Int, agoMs: Long = 0, half: Boolean = false): Entry {
        val n = Notification(title, "", priority = Priority.HIGH, timeoutS = timeout, url = "$HA$path", size = if (half) ViewSize.HALF else ViewSize.FULL)
        return Entry(nextId++, n, now - agoMs, Delivery.of(Priority.HIGH, false))
    }

    /**
     * The overlay over a stand-in for the activity's page: a drawn HA dashboard that goes to the view the overlay
     * opens, and moves where it puts it ([dev.glassworks.engine.PageCapture.place], as the activity does). A half view
     * gets the dashboard's picture first.
     */
    private fun viewShot(
        name: String, open: Entry?, waiting: List<Entry>, kind: Int, cards: List<Entry> = emptyList(),
        w: Int = Mockup.W, h: Int = Mockup.H, dark: Boolean = false, input: Input = Input.TOUCH,
    ) {
        // Full screen, as the panel's activity: the window's own layout pass then agrees with the shot's size.
        val a = Mockup.activity()
        @Suppress("DEPRECATION")
        a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        @Suppress("DEPRECATION")
        a.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val root = FrameLayout(a)
        a.setContentView(root, android.view.ViewGroup.LayoutParams(-1, -1))
        val page = FrameLayout(root.context)
        fun show(k: Int) { page.removeAllViews(); page.addView(Ha(root.context, k, dark), FrameLayout.LayoutParams(-1, -1)) }
        show(Ha.HOME)
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        val o = NotificationOverlay(root.context, NotificationsHub(), images, now = { now })
        o.input = input
        o.capturePage = { done ->
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Mockup.layout(page, w, h)
            page.draw(Canvas(b))
            done(b)
        }
        o.onPage = { url -> show(if (url == null) Ha.HOME else kind) }
        o.onPageArea = { area -> dev.glassworks.engine.PageCapture.place(page, area) }
        root.addView(o, FrameLayout.LayoutParams(-1, -1))
        o.update(NotificationsHub.State(cards = cards, page = open, list = cards + listOfNotNull(open) + waiting))
        val critical = cards.any { it.notification.priority == Priority.CRITICAL }
        repeat(if (critical) 60 else 10) { Thread.sleep(if (critical) 100 else 50); Mockup.idle() }
        // At the final size, as the panel's first layout: the overlay puts the page in the dialog again.
        Mockup.layout(root, w, h)
        o.update(NotificationsHub.State(cards = cards, page = open, list = cards + listOfNotNull(open) + waiting))
        Mockup.idle()
        Mockup.save(name, root, w, h)
    }

    private fun settings(name: String, on: Boolean, list: List<Entry>) {
        val o = Mockup.show(KioskSettingsOverlay(Mockup.activity()))
        val app = RuntimeEnvironment.getApplication()
        KioskPrefs(app).urlSetting = "http://homeassistant.local:8123/lovelace/panel"
        val panePrefs = PanePrefs(app)
        val hub = NotificationsHub().apply { publish(NotificationsHub.State(list = list)) }
        val notify = NotificationsDefinition(panePrefs.paneFile("notifications"), hub, { "aqara-s1-plus-a1b2c3" }, now = { now })
        notify.switch.on = on
        val radio = radioDefinition(app, FixedReadout(null, running = true))
        val panes = listOf(
            MenuFixture.cardOnly("voice", "Voice", "Assist and timers"), MenuFixture.cardOnly("sendspin", "Music", "A Sendspin speaker"),
            notify, radio,
        ) + MenuFixture.switchOnly(app)
        o.show(MenuFixture.model(app, radio, status = "screen on", panes = panes))
        Mockup.idle()
        o.previewSection("notifications")
        Mockup.idle()
        Mockup.layout(o)
        Mockup.save(name, o)
    }

    private fun setupPanes() {
        val a = Mockup.activity()
        val ha = "http://homeassistant.local:8123"
        val radio = radioDefinition(a, FixedReadout(null, mode = RadioMode.OFF, haBase = ha, panelIp = "192.0.2.10"))
        val notify = NotificationsDefinition(PanePrefs(a).paneFile("notifications"), NotificationsHub(), { "aqara-s1-plus-a1b2c3" }, now = { 0L })
        val panes = listOf(
            SetupPane(PaneChoice("Voice", "Assist and timers", on = { true }, apply = { null }), emptyList()),
            SetupPane(PaneChoice("Music", "A Sendspin speaker", on = { true }, apply = { null }), emptyList()),
            SetupPane(notify.setupChoice!!, emptyList()),
            SetupPane(radio.setupChoice, radio.setupSteps),
            SetupPane(PaneChoice("Wall base", "The S1's wired base", on = { true }, apply = { null }), emptyList()),
        )
        val s = s1SetupOverlay(a, panes)
        a.setContentView(s, android.view.ViewGroup.LayoutParams(-1, -1))
        val flow = SetupFlow.ids(true, listOf("radio"))
        s.render(SetupOverlay.Model(
            step = SetupFlow.PANES, done = flow.takeWhile { it != SetupFlow.PANES }.toSet(),
            wifi = WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi"), haUrl = ha, haSource = "mdns",
            panelIp = "192.0.2.10", signedIn = false, improv = WifiStep.improv(ImprovFacts()), installer = null, preview = false,
        ))
        Mockup.idle()
        Mockup.save("notify-13-setup-panes", s)
    }

    /** A light Home Assistant dashboard (the default theme), so the dark card has to hold its own on it. */
    private class Dashboard(context: Context, private val door: Boolean) : View(context) {
        private val d = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        private val ink = Color.parseColor("#212121")
        private val grey = Color.parseColor("#727272")

        private fun card(c: Canvas, l: Float, tp: Float, r: Float, b: Float) {
            p.shader = null
            p.color = Color.argb(18, 0, 0, 0); c.drawRoundRect(l * d, (tp + 1.5f) * d, r * d, (b + 1.5f) * d, 12 * d, 12 * d, p)
            p.color = Color.WHITE; c.drawRoundRect(l * d, tp * d, r * d, b * d, 12 * d, 12 * d, p)
        }

        private fun text(c: Canvas, s: String, x: Float, y: Float, sp: Float, color: Int) {
            t.textSize = sp * d; t.color = color
            c.drawText(s, x * d, y * d, t)
        }

        override fun onDraw(c: Canvas) {
            c.drawColor(Color.parseColor("#fafafa"))
            p.color = Color.parseColor("#03a9f4"); c.drawRect(0f, 0f, width.toFloat(), 56 * d, p)
            text(c, if (door) "Front door" else "Home", 20f, 35f, 20f, Color.WHITE)
            if (door) {
                card(c, 16f, 72f, 640f, 464f)
                Porch.draw(c, RectF(28 * d, 84 * d, 628 * d, 452 * d), d)
                text(c, "Porch · live", 44f, 108f, 13f, Color.WHITE)
                card(c, 656f, 72f, 944f, 260f); text(c, "Gate", 672f, 100f, 16f, ink); text(c, "Open the gate", 744f, 226f, 15f, ink)
                card(c, 656f, 276f, 944f, 464f); text(c, "Visitor", 672f, 304f, 16f, ink); text(c, "Done", 784f, 430f, 15f, ink)
                return
            }
            card(c, 16f, 72f, 316f, 232f); text(c, "Living room", 32f, 100f, 16f, ink); text(c, "Ceiling · On · 70 %", 32f, 140f, 14f, grey)
            card(c, 330f, 72f, 630f, 232f); text(c, "Climate", 346f, 100f, 16f, ink); text(c, "21.5 °C", 346f, 160f, 38f, ink)
            card(c, 644f, 72f, 944f, 232f); text(c, "Weather", 660f, 100f, 16f, ink); text(c, "14° · Cloudy", 660f, 160f, 30f, ink)
            card(c, 16f, 248f, 472f, 464f); text(c, "Energy today", 32f, 276f, 16f, ink)
            p.color = Color.parseColor("#4db6ac")
            listOf(40, 60, 35, 80, 120, 90, 70, 110, 140, 95, 60, 45).forEachIndexed { i, h -> c.drawRoundRect((40 + i * 34f) * d, (440 - h) * d, (62 + i * 34f) * d, 440 * d, 3 * d, 3 * d, p) }
            card(c, 488f, 248f, 944f, 464f); text(c, "Rooms", 504f, 276f, 16f, ink)
            listOf("Kitchen" to "2 lights on", "Hall" to "Door closed", "Office" to "19.8 °C", "Bedroom" to "Off").forEachIndexed { i, (n, s) ->
                val x = 504f + (i % 2) * 220f; val y = 300f + (i / 2) * 76f
                p.color = Color.argb(12, 0, 0, 0); c.drawRoundRect(x * d, y * d, (x + 208) * d, (y + 64) * d, 10 * d, 10 * d, p)
                text(c, n, x + 14, y + 28, 15f, ink); text(c, s, x + 14, y + 48, 12f, grey)
            }
        }
    }

    /** A drawn porch at night: a door, a warm lamp and a parcel. Nobody in it. */
    private object Porch {
        fun draw(c: Canvas, r: RectF, d: Float) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            c.save(); c.clipRect(r)
            p.shader = LinearGradient(0f, r.top, 0f, r.bottom, Color.parseColor("#1b2433"), Color.parseColor("#2b2f38"), Shader.TileMode.CLAMP)
            c.drawRect(r, p); p.shader = null
            val w = r.width(); val h = r.height()
            p.color = Color.parseColor("#3a3f4a"); c.drawRect(r.left, r.top + h * 0.78f, r.right, r.bottom, p)
            p.color = Color.parseColor("#4a3a2c"); c.drawRect(r.left + w * 0.40f, r.top + h * 0.22f, r.left + w * 0.60f, r.top + h * 0.78f, p)
            p.shader = RadialGradient(r.left + w * 0.68f, r.top + h * 0.28f, h * 0.35f, Color.argb(120, 255, 196, 120), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawRect(r, p); p.shader = null
            p.color = Color.parseColor("#ffd8a0"); c.drawCircle(r.left + w * 0.68f, r.top + h * 0.28f, 5f * d, p)
            p.color = Color.parseColor("#8d6e4f"); c.drawRect(r.left + w * 0.64f, r.top + h * 0.68f, r.left + w * 0.76f, r.top + h * 0.80f, p)
            c.restore()
        }
    }

    /** A Home Assistant view, drawn: the home dashboard, the door view or the garage view. Lays out to its size. */
    private class Ha(context: Context, private val kind: Int, private val dark: Boolean = false) : View(context) {
        companion object { const val HOME = 0; const val DOOR = 1; const val GARAGE = 2 }

        private val d = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        private val ink = if (dark) Color.parseColor("#e1e1e1") else Color.parseColor("#212121")
        private val grey = if (dark) Color.parseColor("#9b9b9b") else Color.parseColor("#727272")

        private fun card(c: Canvas, r: RectF) {
            p.shader = null
            p.color = Color.argb(18, 0, 0, 0); c.drawRoundRect(r.left, r.top + 1.5f * d, r.right, r.bottom + 1.5f * d, 12 * d, 12 * d, p)
            p.color = if (dark) Color.parseColor("#1c1c1c") else Color.WHITE; c.drawRoundRect(r, 12 * d, 12 * d, p)
        }

        private fun text(c: Canvas, s: String, x: Float, y: Float, sp: Float, color: Int = ink) {
            t.textSize = sp * d; t.color = color; c.drawText(s, x, y, t)
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            c.drawColor(if (dark) Color.parseColor("#111111") else Color.parseColor("#fafafa"))
            p.shader = null; p.color = if (dark) Color.parseColor("#101e24") else Color.parseColor("#03a9f4")
            c.drawRect(0f, 0f, w, 56 * d, p)
            text(c, when (kind) { DOOR -> "Front door"; GARAGE -> "Garage"; else -> "Home" }, 20 * d, 35 * d, 20f, Color.WHITE)
            val m = 16 * d
            val top = 72 * d
            when (kind) {
                HOME -> home(c, w, h, m, top)
                else -> view(c, w, h, m, top)
            }
        }

        private fun home(c: Canvas, w: Float, h: Float, m: Float, top: Float) {
            val cols = ((w / d - 16) / 300).toInt().coerceIn(1, 4)
            val cw = (w - m * (cols + 1)) / cols
            val cards = listOf(
                Triple("Living room", "Ceiling · On · 70 %", 160f), Triple("Climate", "21.5 °C", 160f), Triple("Weather", "14° · Cloudy", 160f),
                Triple("Energy today", "", 216f), Triple("Rooms", "Kitchen · Hall · Office", 216f), Triple("Lights", "3 on", 160f),
                Triple("Media", "Nothing playing", 160f), Triple("Security", "Doors closed", 160f), Triple("Calendar", "Bin day tomorrow", 216f),
            )
            val ys = FloatArray(cols) { top }
            for ((i, card) in cards.withIndex()) {
                val col = ys.indices.minBy { ys[it] }
                val x = m + col * (cw + m); val y = ys[col]
                if (y > h) continue
                val r = RectF(x, y, x + cw, y + card.third * d)
                card(c, r)
                text(c, card.first, x + 16 * d, y + 28 * d, 16f)
                if (card.first == "Climate") text(c, card.second, x + 16 * d, y + 90 * d, 38f)
                else text(c, card.second, x + 16 * d, y + 60 * d, 14f, grey)
                if (card.first == "Energy today") {
                    p.color = Color.parseColor("#4db6ac")
                    val bw = (cw - 32 * d) / 12
                    listOf(40, 60, 35, 80, 120, 90, 70, 110, 140, 95, 60, 45).forEachIndexed { j, bh ->
                        val bx = x + 16 * d + j * bw
                        c.drawRoundRect(bx + 3 * d, r.bottom - 16 * d - bh * d * 0.9f, bx + bw - 3 * d, r.bottom - 16 * d, 3 * d, 3 * d, p)
                    }
                }
                ys[col] = r.bottom + m
                if (i < 0) break
            }
        }

        private fun view(c: Canvas, w: Float, h: Float, m: Float, top: Float) {
            val wide = w > h * 1.4f
            val (camLabel, b1, b2) = if (kind == DOOR) Triple("Porch · live", "Open the gate" to "Gate", "Done" to "Visitor")
            else Triple("Garage · live", "Close the door" to "Garage door", "Done" to "Open for 20 min")
            val cam: RectF
            val b1r: RectF
            val b2r: RectF
            if (wide) {
                val right = 288 * d
                cam = RectF(m, top, w - right - 2 * m, h - m)
                b1r = RectF(w - right - m, top, w - m, (top + h - m) / 2 - m / 2)
                b2r = RectF(w - right - m, (top + h - m) / 2 + m / 2, w - m, h - m)
            } else {
                val camH = ((w - 2 * m) * 10 / 16).coerceAtMost(h - top - 150 * d)
                cam = RectF(m, top, w - m, top + camH)
                val by = cam.bottom + m
                val bh = 120 * d
                b1r = RectF(m, by, w / 2 - m / 2, by + bh)
                b2r = RectF(w / 2 + m / 2, by, w - m, by + bh)
            }
            card(c, cam)
            val inner = RectF(cam.left + 12 * d, cam.top + 12 * d, cam.right - 12 * d, cam.bottom - 12 * d)
            if (kind == DOOR) Scene.porch(c, inner, d) else Scene.garage(c, inner, d)
            text(c, camLabel, inner.left + 16 * d, inner.top + 24 * d, 13f, Color.WHITE)
            for ((r, words) in listOf(b1r to b1, b2r to b2)) {
                card(c, r)
                text(c, words.second, r.left + 16 * d, r.top + 28 * d, 16f)
                t.textSize = 15 * d
                text(c, words.first, r.centerX() - t.measureText(words.first) / 2, r.bottom - 24 * d, 15f)
            }
        }
    }

    /** Drawn camera pictures. Nobody in them. */
    private object Scene {
        fun porch(c: Canvas, r: RectF, d: Float) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            c.save(); c.clipRect(r)
            p.shader = LinearGradient(0f, r.top, 0f, r.bottom, Color.parseColor("#1b2433"), Color.parseColor("#2b2f38"), Shader.TileMode.CLAMP)
            c.drawRect(r, p); p.shader = null
            val w = r.width(); val h = r.height()
            p.color = Color.parseColor("#3a3f4a"); c.drawRect(r.left, r.top + h * 0.78f, r.right, r.bottom, p)
            p.color = Color.parseColor("#4a3a2c"); c.drawRect(r.left + w * 0.40f, r.top + h * 0.22f, r.left + w * 0.60f, r.top + h * 0.78f, p)
            p.shader = RadialGradient(r.left + w * 0.68f, r.top + h * 0.28f, h * 0.35f, Color.argb(120, 255, 196, 120), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawRect(r, p); p.shader = null
            p.color = Color.parseColor("#ffd8a0"); c.drawCircle(r.left + w * 0.68f, r.top + h * 0.28f, 5f * d, p)
            p.color = Color.parseColor("#8d6e4f"); c.drawRect(r.left + w * 0.64f, r.top + h * 0.68f, r.left + w * 0.76f, r.top + h * 0.80f, p)
            c.restore()
        }

        fun garage(c: Canvas, r: RectF, d: Float) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            c.save(); c.clipRect(r)
            p.shader = LinearGradient(0f, r.top, 0f, r.bottom, Color.parseColor("#2a2d33"), Color.parseColor("#1d1f24"), Shader.TileMode.CLAMP)
            c.drawRect(r, p); p.shader = null
            val w = r.width(); val h = r.height()
            val door = RectF(r.left + w * 0.18f, r.top + h * 0.18f, r.right - w * 0.18f, r.top + h * 0.82f)
            p.color = Color.parseColor("#0f1013"); c.drawRect(door, p)
            p.color = Color.parseColor("#5a5f6a")
            for (i in 0 until 3) {
                val y = door.top + i * door.height() * 0.09f
                c.drawRect(door.left, y, door.right, y + door.height() * 0.07f, p)
            }
            p.color = Color.parseColor("#3a3f4a"); c.drawRect(r.left, door.bottom, r.right, r.bottom, p)
            c.restore()
        }
    }
}
