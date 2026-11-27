/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.engine.vitro.camera.CameraFitFrame
import dev.glassworks.engine.vitro.cards.CustomButtonCardView
import dev.glassworks.engine.vitro.cards.SensorGraphCardView
import dev.glassworks.engine.vitro.cards.StackInCardView
import dev.glassworks.engine.vitro.cards.ThermostatCardView
import dev.glassworks.engine.vitro.cards.ThermostatDialView
import dev.glassworks.engine.vitro.cards.TileCardView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HistoryPoint
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.ContainedCard
import dev.glassworks.engine.vitro.ui.VideoRedraw
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.IdentityHashMap

/**
 * With hardware drawing, Android rebuilds only the display list of a view that changed, straight from its parent's,
 * past every card's draw. A card ([CardSurface]) records itself again when a view inside it changes, so a throw there
 * stays in the card. This measures what that costs on real hardware frames: which views record again after an
 * update, counted by a drawable each view draws only when it records. The card adds its own list and nothing else;
 * the views that didn't change, the heavy ones included (the thermostat's dial, a graph), keep theirs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HardwareRedrawTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val store = EntityStore()
    private val contained = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String?>>()

    @Before fun listen() {
        VitroFailures.resetForTest()
        VitroFailures.listener = { where, type, _ -> contained += where to type }
    }

    @After fun stopListening() {
        VitroFailures.listener = null
    }

    /**
     * Counts the draws of the view it's the foreground of: one per recording of that view's display list. A view that
     * has a foreground of its own (a tile's ripple) keeps it: this draws it, then counts.
     */
    private class Counter(val view: View, val counts: IdentityHashMap<View, Int>, val own: Drawable?) : Drawable() {
        override fun draw(canvas: Canvas) {
            own?.draw(canvas)
            counts[view] = (counts[view] ?: 0) + 1
        }
        override fun onBoundsChange(bounds: android.graphics.Rect) { own?.bounds = bounds }
        override fun isStateful(): Boolean = own?.isStateful == true
        override fun onStateChange(state: IntArray): Boolean = own?.setState(state) == true
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Drawable API") override fun getOpacity() = PixelFormat.TRANSPARENT
    }

    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    private fun dashboard(vararg cards: JSONObject, testCards: ((dev.glassworks.engine.vitro.model.CardConfig, VitroCardContext) -> View?)? = null): VitroView {
        val vv = VitroView(activity, store, 0xFF000000.toInt())
        vv.testCards = testCards
        vv.contextFactory = { theme ->
            VitroCardContext(
                context = activity, haBase = FakeHaServer.BASE, store = store, theme = theme, resources = emptyList(),
                accessToken = { null }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
                fetchHistory = { ids, _, done ->
                    val now = System.currentTimeMillis() / 1000.0
                    done(ids.associateWith { (0 until 48).map { i -> HistoryPoint(now - (48 - i) * 1800, 20.0 + (i % 7) * 0.3) } })
                },
                subscribeTemplate = { _, _ -> {} },
                // HA's error card shows its title to an administrator.
                haUser = { JSONObject().put("id", "u1").put("is_admin", true) },
            )
        }
        val view = JSONObject().put("title", "Home").put("cards", JSONArray().apply { cards.forEach { put(it) } })
        vv.setDashboard(LovelaceParser.parseDashboard(JSONObject().put("views", JSONArray().put(view))), null)
        activity.setContentView(vv)
        vv.rootView.invalidate()
        hardwareFrame(vv)
        return vv
    }

    /** What one update cost: the views in [card], and those that recorded their display list again. */
    private class Cost(val card: View, val views: List<View>, val asked: Set<View>, val recorded: Map<View, Int>) {
        /** The card guards ([CardSurface], [ContainedCard]) that recorded their own list again. */
        val guards: List<View> get() = recorded.keys.filter { it is CardSurface || it is ContainedCard }
    }

    /**
     * Puts a [Counter] on every view in [card], settles a frame, then runs
     * [update] and the next frame, and returns what recorded again.
     */
    private fun measure(vv: VitroView, card: View, update: () -> Unit): Cost {
        val counts = IdentityHashMap<View, Int>()
        val views = all(card)
        views.forEach { it.foreground = Counter(it, counts, it.foreground) }
        hardwareFrame(vv)
        hardwareFrame(vv)
        counts.clear()
        update()
        val asked = java.util.Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
        hardwareFrame(vv) { views.filterTo(asked) { it.isInvalidated() } }
        return Cost(card, views, asked, HashMap(counts).let { m -> IdentityHashMap<View, Int>().apply { putAll(m) } })
    }

    private fun report(name: String, c: Cost) {
        println("HW redraw | $name | views in card ${c.views.size} | recorded again ${c.recorded.size} | " +
            "guards' own lists ${c.guards.size} | " +
            "recorded: ${c.recorded.keys.map { it.javaClass.simpleName.ifEmpty { "anon" } }.sorted()}")
    }

    /** No view records twice in a frame, and none records unless it asked to: the views that didn't change keep theirs. */
    private fun assertBounded(c: Cost) {
        assertTrue("a view recorded twice in one frame: ${c.recorded}", c.recorded.values.all { it == 1 })
        val unasked = c.recorded.keys.filter { it !in c.asked }
        assertTrue("recorded without asking: ${unasked.map { it.javaClass.simpleName }}", unasked.isEmpty())
        // The guards that record again are the nearest ones above what changed (a button-card's own views are under its
        // ContainedCard, its state under the card surface inside it): never one further up.
        val nearest = c.recorded.keys.filter { it !is CardSurface && it !is ContainedCard }.mapNotNull(::nearestGuard).toSet()
        assertTrue("a guard records itself again, so a throw inside stays in it", c.guards.isNotEmpty())
        assertTrue("only the guards nearest to a change: ${c.guards.map { it.javaClass.simpleName }}", c.guards.all { it in nearest })
        assertTrue("only some of the card's views recorded again (${c.recorded.size} of ${c.views.size})", c.recorded.size < c.views.size)
    }

    private fun nearestGuard(v: View): View? {
        var p = v.parent as? View
        while (p != null && p !is CardSurface && p !is ContainedCard) p = p.parent as? View
        return p
    }

    private fun climate(low: Double) = EntityState(
        "climate.hall", "heat_cool",
        mapOf(
            "friendly_name" to "Hall", "hvac_modes" to listOf("off", "heat_cool"), "supported_features" to 386,
            "min_temp" to 10.0, "max_temp" to 30.0, "target_temp_step" to 0.5, "current_temperature" to 22.0,
            "target_temp_low" to low, "target_temp_high" to 24.0, "hvac_action" to "idle",
        ),
    )

    @Test fun aThermostatsDialAloneRecordsWithItsCard() {
        store.putLocal(climate(20.0))
        val vv = dashboard(JSONObject().put("type", "thermostat").put("entity", "climate.hall"))
        val card = all(vv).filterIsInstance<ThermostatCardView>().single()
        val dial = all(card).filterIsInstance<ThermostatDialView>().single()
        // One frame of a drag: the dial alone changes.
        val drag = measure(vv, card) { dial.invalidate() }
        report("thermostat, one drag frame (dial)", drag)
        assertBounded(drag)
        assertEquals("the dial and the card's own list", setOf(dial, card), drag.recorded.keys.toSet())
    }

    @Test fun aThermostatsNewTargetRecordsOnlyWhatChanged() {
        store.putLocal(climate(20.0))
        val vv = dashboard(JSONObject().put("type", "thermostat").put("entity", "climate.hall"))
        val card = all(vv).filterIsInstance<ThermostatCardView>().single()
        val update = measure(vv, card) { store.putLocal(climate(21.0)) }
        report("thermostat, new target from HA", update)
        assertBounded(update)
    }

    @Test fun aGraphsNewStateRecordsOnlyWhatChanged() {
        store.putLocal(EntityState("sensor.temp", "21.5", mapOf("friendly_name" to "Temperature", "unit_of_measurement" to "°C")))
        val vv = dashboard(JSONObject().put("type", "custom:mini-graph-card").put("entities", JSONArray().put("sensor.temp")))
        val card = all(vv).filterIsInstance<SensorGraphCardView>().single()
        val update = measure(vv, card) {
            store.putLocal(EntityState("sensor.temp", "22.0", mapOf("friendly_name" to "Temperature", "unit_of_measurement" to "°C")))
        }
        report("mini-graph-card, new state", update)
        assertBounded(update)
    }

    @Test fun aButtonCardsNewStateRecordsOnlyWhatChanged() {
        store.putLocal(EntityState("light.desk", "off", mapOf("friendly_name" to "Desk")))
        val vv = dashboard(JSONObject().put("type", "custom:button-card").put("entity", "light.desk").put("show_state", true))
        // Not a card surface: the guard round it (ContainedCard) and the card surfaces inside it are measured together.
        val card = all(vv).filterIsInstance<CustomButtonCardView>().single().parent as ContainedCard
        val update = measure(vv, card) { store.putLocal(EntityState("light.desk", "on", mapOf("friendly_name" to "Desk"))) }
        report("button-card, off to on", update)
        assertBounded(update)
    }

    @Test fun aChangeInACardInsideAStackInCardLeavesTheStackAlone() {
        store.putLocal(EntityState("light.a", "off", mapOf("friendly_name" to "A")))
        store.putLocal(EntityState("light.b", "off", mapOf("friendly_name" to "B")))
        val tiles = JSONArray()
            .put(JSONObject().put("type", "tile").put("entity", "light.a"))
            .put(JSONObject().put("type", "tile").put("entity", "light.b"))
        val vv = dashboard(JSONObject().put("type", "custom:stack-in-card").put("cards", tiles))
        val stack = all(vv).filterIsInstance<StackInCardView>().single()
        val tileA = all(stack).filterIsInstance<TileCardView>().first()
        val update = measure(vv, stack) { store.putLocal(EntityState("light.a", "on", mapOf("friendly_name" to "A"))) }
        report("stack-in-card, one tile's light on", update)
        assertTrue("the changed tile records itself again", tileA in update.recorded)
        assertFalse("the nearest card takes it: the stack round it keeps its list", stack in update.recorded)
        assertTrue(update.recorded.values.all { it == 1 })
        assertTrue(update.recorded.keys.all { it in update.asked })
    }

    @Test fun aChangeInATileInsideAVerticalStackLeavesTheStacksGuardAlone() {
        store.putLocal(EntityState("light.a", "off", mapOf("friendly_name" to "A")))
        store.putLocal(EntityState("light.b", "off", mapOf("friendly_name" to "B")))
        val tiles = JSONArray()
            .put(JSONObject().put("type", "tile").put("entity", "light.a"))
            .put(JSONObject().put("type", "tile").put("entity", "light.b"))
        val vv = dashboard(JSONObject().put("type", "vertical-stack").put("cards", tiles))
        val tileA = all(vv).filterIsInstance<TileCardView>().first()
        val guard = generateSequence(tileA.parent as? View) { it.parent as? View }.filterIsInstance<ContainedCard>().first()
        val update = measure(vv, guard) { store.putLocal(EntityState("light.a", "on", mapOf("friendly_name" to "A"))) }
        report("vertical-stack, one tile's light on", update)
        assertTrue("the changed tile records itself again", tileA in update.recorded)
        assertFalse("the stack's guard keeps its list", guard in update.recorded)
        assertEquals(listOf<View>(tileA), update.guards)
    }

    /** A view that throws in its touch handling or its draw once armed. */
    private class Boom(context: android.content.Context) : View(context) {
        var touchArmed = false
        var drawArmed = false
        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            if (touchArmed) throw IllegalStateException("touch boom")
            return true
        }
        override fun onDraw(canvas: Canvas) {
            if (drawArmed) throw IllegalStateException("redraw boom")
        }
    }

    private fun touch(v: View) {
        val now = android.os.SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, 2f, 2f, 0)
        v.dispatchTouchEvent(down)
        down.recycle()
    }

    @Test fun aRedrawInsideAGuardWaitingForItsErrorCardStaysInsideItsCatch() {
        // A card without a surface in a vertical stack: two guards, the card's inside the stack's.
        var boom: Boom? = null
        val stack = JSONObject().put("type", "vertical-stack").put("cards", JSONArray().put(JSONObject().put("type", PLAIN)))
        val vv = dashboard(stack) { card, ctx ->
            if (card.type != PLAIN) null else android.widget.FrameLayout(ctx.context).apply {
                addView(Boom(ctx.context).also { boom = it }, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40))
            }
        }
        val inner = boom!!.parent.parent as ContainedCard
        assertTrue("nested in the stack's guard", generateSequence(inner.parent as? View) { it.parent as? View }.any { it is ContainedCard })
        vv.rootView.invalidate()
        hardwareFrame(vv)
        // A touch throws: the card's guard waits for its error card, posted for the next message.
        boom!!.touchArmed = true
        touch(inner)
        assertNotNull(inner.failure)
        // In the same frame, before the swap, a view inside the failed card changes: its redraw would throw.
        boom!!.drawArmed = true
        boom!!.invalidate()
        hardwareFrame(vv, idle = false)
        assertEquals("the redraw never ran outside a catch", listOf("touch" to PLAIN), contained.toList())
        hardwareFrame(vv)
        assertTrue(inner.content is dev.glassworks.engine.vitro.ui.ErrorCardView)
        assertEquals(1, contained.size)
    }

    /** A card surface with a [Boom] inside. */
    private class BoomSurface(ctx: VitroCardContext, val boom: Boom) : CardSurface(ctx) {
        init {
            addView(boom, LayoutParams(LayoutParams.MATCH_PARENT, 40))
        }
    }

    @Test fun aRedrawInsideACardWaitingForItsErrorCardStaysInsideItsCatch() {
        var card: BoomSurface? = null
        val vv = dashboard(JSONObject().put("type", PLAIN)) { c, ctx -> if (c.type == PLAIN) BoomSurface(ctx, Boom(ctx.context)).also { card = it } else null }
        val boom = card!!.boom
        vv.rootView.invalidate()
        hardwareFrame(vv)
        boom.touchArmed = true
        touch(card!!)
        assertNotNull(card!!.failure)
        boom.drawArmed = true
        boom.invalidate()
        hardwareFrame(vv, idle = false)
        assertEquals("the redraw never ran outside a catch", listOf("touch" to PLAIN), contained.toList())
        hardwareFrame(vv)
        assertTrue(all(card!!).filterIsInstance<TextView>().any { it.text.toString() == dev.glassworks.engine.vitro.ui.ErrorCardView.TITLE })
        assertEquals(1, contained.size)
    }

    @Test fun aThrowWhenHardwareRedrawsOneViewInsideACardIsContained() {
        store.putLocal(EntityState("light.desk", "off", mapOf("friendly_name" to "Desk")))
        val vv = dashboard(JSONObject().put("type", "tile").put("entity", "light.desk"), JSONObject().put("type", "tile").put("entity", "light.desk"))
        val (card, other) = all(vv).filterIsInstance<TileCardView>()
        var armed = false
        val boom = object : View(activity) {
            override fun onDraw(canvas: Canvas) {
                if (armed) throw IllegalStateException("redraw boom")
            }
        }
        (all(card).filterIsInstance<ViewGroup>().last { it !== card }).addView(boom, ViewGroup.LayoutParams(8, 8))
        vv.rootView.invalidate()
        hardwareFrame(vv)
        // Only the view inside the card changes: Android would rebuild its list alone, from its parent's, past the card.
        armed = true
        boom.invalidate()
        hardwareFrame(vv)
        assertEquals(listOf("draw" to "tile"), contained.toList())
        hardwareFrame(vv)
        assertNotNull(card.failure)
        assertTrue(all(card).filterIsInstance<TextView>().any { it.text.toString() == dev.glassworks.engine.vitro.ui.ErrorCardView.TITLE })
        assertEquals("the other card works", null, other.failure)
    }

    /** A card surface with a camera's picture (a TextureView in its fit frame, as the players place it) and a label. */
    private class VideoSurface(ctx: VitroCardContext) : CardSurface(ctx) {
        val texture = android.view.TextureView(ctx.context)
        val label = View(ctx.context)

        init {
            val fit = CameraFitFrame(ctx.context, CameraFitFrame.CONTAIN)
            fit.addView(texture, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val host = android.widget.FrameLayout(ctx.context)
            host.addView(fit, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(host, LayoutParams(LayoutParams.MATCH_PARENT, 90))
            addView(label, LayoutParams(LayoutParams.MATCH_PARENT, 10))
        }
    }

    @Test fun aVideoFrameRedrawsItsTextureViewAloneAndAnyOtherChangeStillRecordsTheCard() {
        var card: VideoSurface? = null
        val vv = dashboard(JSONObject().put("type", PLAIN)) { c, ctx -> if (c.type == PLAIN) VideoSurface(ctx).also { card = it } else null }
        val c = card!!
        hardwareFrame(vv)
        hardwareFrame(vv)
        assertFalse(c.isInvalidated())
        // A new frame: the TextureView invalidates itself (15 or more times a second per camera). Its draw is the
        // framework's own and runs no card code: the card keeps its display list.
        c.texture.invalidate()
        assertTrue(c.texture.isInvalidated())
        assertFalse("a video frame doesn't record its card again", c.isInvalidated())
        hardwareFrame(vv)
        // Anything else inside the card still records the card, so its draw stays inside the card's catch.
        c.label.invalidate()
        assertTrue("another view's change records the card", c.isInvalidated())
        hardwareFrame(vv)
        assertEquals(emptyList<Pair<String, String?>>(), contained.toList())
    }

    companion object {
        const val PLAIN = "custom:test-plain"
    }
}

/**
 * Android 6 and 7, where a change climbs through `invalidateChildInParent` without saying which view changed: a video's
 * [CameraFitFrame] marks its TextureView's frame ([VideoRedraw]) for the card guards above it, for the rest of that
 * main-thread message only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], application = Application::class)
class VideoRedrawLegacyTest {
    private val context = org.robolectric.RuntimeEnvironment.getApplication()

    @After fun reset() = VideoRedraw.resetForTest()

    @Suppress("DEPRECATION")
    @Test fun aVideoFramesChangeIsMarkedForTheCardsAboveItUntilTheMessageEnds() {
        val card = android.widget.FrameLayout(context)
        val other = android.widget.FrameLayout(context)
        val fit = CameraFitFrame(context, CameraFitFrame.CONTAIN)
        card.addView(fit)
        fit.addView(android.view.TextureView(context))
        fit.invalidateChildInParent(IntArray(2), android.graphics.Rect(0, 0, 10, 10))
        assertTrue("the guard above the video hears a video frame", VideoRedraw.isVideoFrame(card))
        assertFalse("a guard elsewhere doesn't", VideoRedraw.isVideoFrame(other))
        // The next main-thread message: whatever changes then isn't the video's.
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertFalse(VideoRedraw.isVideoFrame(card))
    }

    @Suppress("DEPRECATION")
    @Test fun anImagesChangeInAFitFrameIsNotAVideoFrame() {
        // MJPEG draws into an ImageView, whose draw runs app code: its card still records itself again.
        val card = android.widget.FrameLayout(context)
        val fit = CameraFitFrame(context, CameraFitFrame.FILL)
        card.addView(fit)
        fit.addView(android.widget.ImageView(context))
        fit.invalidateChildInParent(IntArray(2), android.graphics.Rect(0, 0, 10, 10))
        assertFalse(VideoRedraw.isVideoFrame(card))
    }
}
