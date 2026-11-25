/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.engine.EngineSettings
import dev.glassworks.engine.Page
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HaDashboardClient
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.ErrorCardView
import dev.glassworks.engine.vitro.ui.HaGridLayout
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper

/**
 * One card that throws never takes the view or the app down: a card whose build, state update, measure, layout, draw
 * or touch throws shows HA's error card in its slot, the other cards carry on, and the failure is logged once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CardContainmentTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val store = EntityStore()
    private val contained = java.util.concurrent.CopyOnWriteArrayList<Triple<String, String?, Throwable>>()

    @Before fun listen() {
        VitroFailures.resetForTest()
        VitroFailures.listener = { where, type, t -> contained += Triple(where, type, t) }
        ShadowLog.clear()
    }

    @After fun stopListening() {
        VitroFailures.listener = null
    }

    /** What a test card does wrong, and when. */
    private enum class Boom { NONE, BUILD, UPDATE, MEASURE, LAYOUT, DRAW, TOUCH }

    /**
     * A card that follows `sensor.boom` and throws at [boom] once the state says `boom` (or at once, for [Boom.BUILD]),
     * from its own code ([observe], [measureCard], [layoutCard]) or a child view's (draw, touch).
     */
    private class BoomCard(ctx: VitroCardContext, private val boom: Boom) : CardSurface(ctx) {
        var armed = false
        val cardCtx: VitroCardContext get() = ctx
        val label = TextView(ctx.context)
        val child = object : View(ctx.context) {
            override fun onDraw(canvas: Canvas) {
                if (armed && boom == Boom.DRAW) throw IllegalStateException("draw boom")
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (armed && boom == Boom.TOUCH) throw IllegalStateException("touch boom")
                return true
            }
        }

        init {
            if (boom == Boom.BUILD) throw IllegalArgumentException("build boom")
            addView(label, LayoutParams(LayoutParams.MATCH_PARENT, ctx.px(56f)))
            addView(child, LayoutParams(LayoutParams.MATCH_PARENT, ctx.px(56f)))
            observe("sensor.boom") { s ->
                armed = s?.state == "boom"
                if (armed && boom == Boom.UPDATE) throw ClassCastException("update boom")
                label.text = s?.state.orEmpty()
            }
        }

        override fun measureCard(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (armed && boom == Boom.MEASURE) throw IndexOutOfBoundsException("measure boom")
            super.measureCard(widthMeasureSpec, heightMeasureSpec)
        }

        override fun layoutCard(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            if (armed && boom == Boom.LAYOUT) throw ArithmeticException("layout boom")
            super.layoutCard(changed, left, top, right, bottom)
        }
    }

    private fun view(boom: Boom, sections: Boolean = true): VitroView {
        store.putLocal(EntityState("sensor.boom", "ok", mapOf("friendly_name" to "Boom")))
        store.putLocal(EntityState("light.kitchen", "off", mapOf("friendly_name" to "Kitchen")))
        val vv = VitroView(activity, store, 0xFF000000.toInt())
        vv.contextFactory = { theme ->
            VitroCardContext(
                context = activity, haBase = FakeHaServer.BASE, store = store, theme = theme, resources = emptyList(),
                accessToken = { null }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
                fetchHistory = { _, _, done -> done(emptyMap()) }, subscribeTemplate = { _, _ -> {} },
            )
        }
        vv.testCards = { card, ctx -> if (card.type == BOOM) BoomCard(ctx, boom) else null }
        val cards = JSONArray()
            .put(JSONObject().put("type", BOOM).put("grid_options", JSONObject().put("rows", 2)))
            .put(JSONObject().put("type", "tile").put("entity", "light.kitchen"))
        val view = if (sections) {
            JSONObject().put("type", "sections").put("sections", JSONArray().put(JSONObject().put("type", "grid").put("cards", cards)))
        } else {
            JSONObject().put("cards", cards)
        }
        vv.setDashboard(LovelaceParser.parseDashboard(JSONObject().put("views", JSONArray().put(view.put("title", "Home")))), null)
        activity.setContentView(vv)
        drawAll(vv)
        return vv
    }

    private fun drawAll(v: View) {
        ShadowLooper.idleMainLooper()
        v.measure(View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1440, 720)
        val bmp = Bitmap.createBitmap(1440, 720, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        bmp.recycle()
        ShadowLooper.idleMainLooper()
    }

    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    private fun texts(v: View): List<String> = all(v).filterIsInstance<TextView>().map { it.text.toString() }

    private fun boomCard(vv: VitroView): BoomCard = all(vv).filterIsInstance<BoomCard>().single()

    /** The card shows HA's error card: its own views gone, the icon and "Configuration error" in their place. */
    private fun assertErrorCard(card: CardSurface) {
        assertNotNull("the card failed", card.failure)
        assertEquals("the card's own views gave way to the error card", listOf(ErrorCardView.TITLE), texts(card))
        assertEquals(ErrorCardView.TITLE, all(card).firstNotNullOfOrNull { it.contentDescription }?.toString())
    }

    /** The other card still follows its entity: one card's failure doesn't stop the view. */
    private fun assertTileStillUpdates(vv: VitroView) {
        store.putLocal(EntityState("light.kitchen", "on", mapOf("friendly_name" to "Kitchen")))
        drawAll(vv)
        assertTrue(texts(vv).toString(), texts(vv).any { it.equals("On", ignoreCase = true) })
    }

    @Test fun aCardWhoseBuildThrowsIsHasErrorCardSizedAsOne() {
        val vv = view(Boom.BUILD)
        val error = all(vv).filterIsInstance<ErrorCardView>().single()
        assertEquals(BOOM, error.cardType)
        assertEquals(listOf(ErrorCardView.TITLE), texts(error))
        // hui-error-card's grid options (6 columns), the card's own grid_options (2 rows) over them.
        var slot: View = error
        while (slot.layoutParams !is HaGridLayout.LayoutParams) slot = slot.parent as View
        val lp = slot.layoutParams as HaGridLayout.LayoutParams
        assertEquals(6, lp.span)
        assertNotNull(lp.fixedHeight)
        assertEquals(listOf("build" to BOOM), contained.map { it.first to it.second })
        assertTileStillUpdates(vv)
    }

    @Test fun aBuildFailureInAMasonryViewIsAnErrorCardToo() {
        val vv = view(Boom.BUILD, sections = false)
        assertEquals(1, all(vv).filterIsInstance<ErrorCardView>().size)
        assertTileStillUpdates(vv)
    }

    @Test fun aCardWhoseStateUpdateThrowsBecomesTheErrorCard() {
        val vv = view(Boom.UPDATE)
        val card = boomCard(vv)
        assertNull(card.failure)
        store.putLocal(EntityState("sensor.boom", "boom", emptyMap()))
        drawAll(vv)
        assertErrorCard(card)
        assertEquals(listOf("update" to BOOM), contained.map { it.first to it.second })
        // Its subscription ended with it: later states don't run its code again.
        store.putLocal(EntityState("sensor.boom", "boom again", emptyMap()))
        drawAll(vv)
        assertEquals(1, contained.size)
        assertTileStillUpdates(vv)
    }

    @Test fun aCardWhoseMeasureThrowsBecomesTheErrorCard() {
        val vv = view(Boom.MEASURE)
        val card = boomCard(vv)
        store.putLocal(EntityState("sensor.boom", "boom", emptyMap()))
        card.requestLayout()
        drawAll(vv)
        assertErrorCard(card)
        assertEquals("measure", contained.single().first)
        assertTrue("the error card has a height", card.measuredHeight > 0)
        assertTileStillUpdates(vv)
    }

    @Test fun aCardWhoseLayoutThrowsBecomesTheErrorCard() {
        val vv = view(Boom.LAYOUT)
        val card = boomCard(vv)
        store.putLocal(EntityState("sensor.boom", "boom", emptyMap()))
        card.requestLayout()
        drawAll(vv)
        assertErrorCard(card)
        assertEquals("layout", contained.single().first)
        assertTileStillUpdates(vv)
    }

    @Test fun aCardWhoseDrawThrowsBecomesTheErrorCard() {
        val vv = view(Boom.DRAW)
        val card = boomCard(vv)
        store.putLocal(EntityState("sensor.boom", "boom", emptyMap()))
        card.child.invalidate()
        drawAll(vv)
        assertEquals("draw", contained.single().first)
        drawAll(vv)
        assertErrorCard(card)
        // The error card draws: the next frame throws nothing.
        drawAll(vv)
        assertEquals(1, contained.size)
        assertTileStillUpdates(vv)
    }

    @Test fun aCardWhoseTouchThrowsBecomesTheErrorCard() {
        val vv = view(Boom.TOUCH)
        val card = boomCard(vv)
        store.putLocal(EntityState("sensor.boom", "boom", emptyMap()))
        drawAll(vv)
        val now = SystemClock.uptimeMillis()
        // In the card's own coordinates: its test child, drawn last, takes the touch first.
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        card.dispatchTouchEvent(down)
        down.recycle()
        drawAll(vv)
        assertErrorCard(card)
        assertEquals("touch", contained.single().first)
        assertTileStillUpdates(vv)
    }

    @Test fun aCardThatIsntACardSurfaceIsContainedByItsSectionsGrid() {
        // A heading, Mushroom's chips or title: plain views, without CardSurface's guards. The grid holds the line.
        var armed = false
        val vv = view(Boom.NONE)
        vv.testCards = { card, ctx ->
            if (card.type == BOOM) object : View(ctx.context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    if (armed) throw IllegalStateException("plain measure boom")
                    setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), ctx.px(56f))
                }
            } else null
        }
        vv.setUserTheme(null, true) // rebuilds the view with the plain card
        drawAll(vv)
        assertTrue(all(vv).none { it is ErrorCardView })
        armed = true
        all(vv).forEach { it.requestLayout() }
        drawAll(vv)
        drawAll(vv)
        assertEquals("measure", contained.single().first)
        assertEquals(1, all(vv).filterIsInstance<ErrorCardView>().size)
        assertTileStillUpdates(vv)
    }

    @Test fun anUpdateOnTheCardsHandlerThatThrowsIsDroppedAndLogged() {
        val vv = view(Boom.NONE)
        val card = boomCard(vv)
        val ctx = card.cardCtx
        // A template's answer or a timer, posted by a card's own code: no card to swap, so it's logged and dropped.
        val answer = Runnable { throw IllegalStateException("template boom") }
        ctx.main.post(answer)
        ctx.main.post(answer)
        drawAll(vv)
        assertEquals(listOf("update", "update"), contained.map { it.first })
        assertEquals("logged once for both", 1, ShadowLog.getLogsForTag("Vitro").count { it.type == Log.WARN })
        assertTileStillUpdates(vv)
    }

    @Test fun aStoreListenerThatThrowsDoesntStopTheOthers() {
        val s = EntityStore()
        var heard = 0
        s.subscribe("sensor.a") { throw IllegalStateException("listener boom") }
        s.subscribe("sensor.a") { heard++ }
        s.subscribeAny { throw IllegalStateException("any boom") }
        s.putLocal(EntityState("sensor.a", "1", emptyMap()))
        assertEquals(1, heard)
        assertEquals(2, contained.size)
    }

    @Test fun theSameFailureIsLoggedOnceAndCountedEachTime() {
        val e = IllegalStateException("same")
        repeat(5) { VitroFailures.report("update", "tile", e) }
        VitroFailures.report("draw", "tile", e)
        assertEquals(6L, VitroFailures.contained)
        assertEquals("one line per kind of failure", 2, ShadowLog.getLogsForTag("Vitro").count { it.type == Log.WARN })
    }

    @Test fun aNonAdminSeesTheErrorIconAlone() {
        val ctx = VitroCardContext(
            context = activity, haBase = FakeHaServer.BASE, store = store,
            theme = dev.glassworks.engine.vitro.theme.ThemeResolver.resolve(dev.glassworks.engine.vitro.theme.ThemeCatalog(), false),
            resources = emptyList(), accessToken = { null }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
            fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} }, haUser = { JSONObject().put("is_admin", false) },
        )
        val card = ErrorCardView(ctx, CardConfig("tile", JSONObject()))
        assertTrue("no title for a user who isn't an administrator", texts(card).isEmpty())
        assertEquals(ErrorCardView.TITLE, all(card).firstNotNullOfOrNull { it.contentDescription }?.toString())
    }

    companion object {
        const val BOOM = "custom:test-boom"
    }
}

/**
 * The review's claim 1 end to end: a weather entity that reports its humidity as text crashed the main looper on
 * every start. Now the card shows it (a number in text reads as one) and nothing is thrown or even contained.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TextHumidityEngineTest {
    private val base = FakeHaServer.BASE
    private val settings = EngineSettings(secureHosts = listOf("192.0.2.10"), zoom = 1f, remoteDebugging = false, dashboard = "$base/wall-test/living")
    private lateinit var activity: Activity
    private val host = RecordingHost()
    private val server = FakeHaServer()
    private var engine: VitroEngine? = null
    private var page: Page? = null
    private val contained = java.util.concurrent.CopyOnWriteArrayList<Throwable>()

    @Before fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        VitroFailures.listener = { _, _, t -> contained += t }
    }

    @After fun tearDown() {
        VitroFailures.listener = null
        runCatching { engine?.islandServer?.stop() }
        runCatching {
            val f = page!!.javaClass.getDeclaredField("client"); f.isAccessible = true
            (f.get(page) as? HaDashboardClient)?.close()
        }
        server.shutdown()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun aWeatherEntityWithATextHumidityRendersTheDashboard() {
        server.dashboardConfig = JSONObject("""{"views": [{"path": "living", "cards": [
            {"type": "weather-forecast", "entity": "weather.home", "show_forecast": false}]}]}""")
        server.initialEntities = JSONObject("""{"weather.home": {"s": "cloudy", "a": {"friendly_name": "Home", "humidity": "65"}, "lc": 1700000000.0}}""")
        val e = VitroEngine(
            fallback = RecordingEngine(), settings = { settings }, enabled = { true },
            tokenProvider = { _, done -> done(FakeHaServer.TOKEN) }, socketFactory = server::connect,
        ).also { engine = it }
        val p = e.open(activity, host, Color.BLACK).also { page = it }
        activity.setContentView(p.view)
        idle()
        p.load("$base/wall-test/living")
        awaitCondition("ready", tick = { idle() }) { host.messages("ready").isNotEmpty() }
        val vv = (p.view as ViewGroup).getChildAt(0) as VitroView
        fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        awaitCondition("humidity shown", tick = { idle() }) { all(vv).filterIsInstance<TextView>().any { it.text.toString() == "65%" } }
        assertTrue("nothing contained: $contained", contained.isEmpty())
        assertFalse(all(vv).any { it is ErrorCardView })
        assertSame(vv, (p.view as ViewGroup).getChildAt(0))
    }
}
