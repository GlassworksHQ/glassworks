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
import dev.glassworks.engine.vitro.cards.HeadingCardView
import dev.glassworks.engine.vitro.cards.MushroomChipsCardView
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.ContainedCard
import dev.glassworks.engine.vitro.ui.ErrorCardView
import dev.glassworks.engine.vitro.ui.HaGridLayout
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper

/**
 * One card that throws never takes the view or the app down: a card whose build, state update, measure, layout, draw
 * or touch throws shows HA's error card in its slot, the other cards carry on, and the failure is logged once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CardContainmentTest {
    /** Its clients never go to the network for HA's frontend files ([NoHaFrontendFetch]). */
    @get:org.junit.Rule val noFrontendFetch = NoHaFrontendFetch()

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

    private fun view(boom: Boom, sections: Boolean = true, admin: Boolean = true): VitroView {
        store.putLocal(EntityState("sensor.boom", "ok", mapOf("friendly_name" to "Boom")))
        store.putLocal(EntityState("light.kitchen", "off", mapOf("friendly_name" to "Kitchen")))
        val vv = VitroView(activity, store, 0xFF000000.toInt())
        vv.contextFactory = { theme ->
            VitroCardContext(
                context = activity, haBase = FakeHaServer.BASE, store = store, theme = theme, resources = emptyList(),
                accessToken = { null }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
                fetchHistory = { _, _, done -> done(emptyMap()) }, subscribeTemplate = { _, _ -> {} },
                // HA's error card shows its title to an administrator.
                haUser = { JSONObject().put("id", "u1").put("is_admin", admin) },
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

    /**
     * hui-error-card for a user who isn't an administrator: the same card, its icon alone where it is for an
     * administrator (16 px in: `no-title` centers nothing in a block ha-card), and TalkBack reads its generic title.
     */
    @Test fun aNonAdminSeesTheFailedCardsIconAloneAtTheStart() {
        val vv = view(Boom.BUILD, admin = false)
        drawAll(vv)
        val error = all(vv).filterIsInstance<ErrorCardView>().single()
        assertTrue("no title for a user who isn't an administrator", texts(error).isEmpty())
        val icon = all(error).filterIsInstance<dev.glassworks.engine.vitro.ui.MdiIconView>().single()
        assertEquals("the icon starts 16 px in", (16 * activity.resources.displayMetrics.density).roundToInt(), icon.left)
        assertEquals(ErrorCardView.TITLE, all(error).firstNotNullOfOrNull { it.contentDescription }?.toString())
        // A failure after the build turns into the same card.
        val late = view(Boom.UPDATE, admin = false)
        drawAll(late)
        store.putLocal(EntityState("sensor.boom", "boom", emptyMap()))
        drawAll(late)
        assertNotNull(boomCard(late).failure)
        assertTrue(texts(boomCard(late)).isEmpty())
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

    /** A card whose own onDraw throws, always: once it's the error card, that onDraw never runs again. */
    private class DrawBoomCard(ctx: VitroCardContext) : CardSurface(ctx) {
        var draws = 0
        override fun onDraw(canvas: Canvas) {
            draws++
            throw IllegalStateException("onDraw boom")
        }
    }

    @Test fun aFailedCardsOwnOnDrawNeverRunsAgain() {
        lateinit var card: DrawBoomCard
        val vv = view(Boom.NONE)
        vv.testCards = { c, ctx -> if (c.type == BOOM) DrawBoomCard(ctx).also { card = it } else null }
        vv.setUserTheme(null, true)
        drawAll(vv)
        drawAll(vv)
        val after = card.draws
        drawAll(vv)
        drawAll(vv)
        assertEquals("the error card draws without the card's onDraw", after, card.draws)
        assertErrorCard(card)
        assertEquals(1, contained.size)
    }

    @Test fun aConfirmedActionThatThrowsIsContained() {
        val ctx = VitroCardContext(
            context = activity, haBase = FakeHaServer.BASE, store = store,
            theme = dev.glassworks.engine.vitro.theme.ThemeResolver.resolve(dev.glassworks.engine.vitro.theme.ThemeCatalog(), true),
            resources = emptyList(), accessToken = { null }, callService = { _, _, _, _ -> },
            navigate = { throw IllegalStateException("navigate boom") }, openMoreInfo = {},
            fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
        )
        val action = dev.glassworks.engine.vitro.model.ActionConfig.parse(
            JSONObject().put("action", "navigate").put("navigation_path", "/lovelace/1").put("confirmation", JSONObject().put("text", "Go?")),
            "none",
        )
        ctx.performAction(action, null)
        ShadowLooper.idleMainLooper()
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("asks first", dialog)
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("action"), contained.map { it.first })
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

    @Test fun aCardThatIsntACardSurfaceIsContainedInASectionsGrid() {
        // A heading, Mushroom's chips or title: plain views, without CardSurface's guards. Their own guard holds the line.
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

    /** A view of [cards] (masonry unless [type] says otherwise), with [testCards] standing in for Vitro's own. */
    private fun dashboard(cards: JSONArray, type: String? = null, testCards: (CardConfig, VitroCardContext) -> View?): VitroView {
        store.putLocal(EntityState("light.kitchen", "off", mapOf("friendly_name" to "Kitchen")))
        val vv = VitroView(activity, store, 0xFF000000.toInt())
        vv.contextFactory = { theme ->
            VitroCardContext(
                context = activity, haBase = FakeHaServer.BASE, store = store, theme = theme, resources = emptyList(),
                accessToken = { null }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
                fetchHistory = { _, _, done -> done(emptyMap()) }, subscribeTemplate = { _, _ -> {} },
                // HA's error card shows its title to an administrator.
                haUser = { JSONObject().put("id", "u1").put("is_admin", true) },
            )
        }
        vv.testCards = testCards
        val view = JSONObject().put("title", "Home").put("cards", cards)
        type?.let { view.put("type", it) }
        vv.setDashboard(LovelaceParser.parseDashboard(JSONObject().put("views", JSONArray().put(view))), null)
        activity.setContentView(vv)
        drawAll(vv)
        return vv
    }

    private fun tile(entity: String = "light.kitchen") = JSONObject().put("type", "tile").put("entity", entity)

    /** The error card in [guard]'s place, and nothing of the card it replaced. */
    private fun assertGuardShowsErrorCard(vv: VitroView, guard: ContainedCard) {
        assertNotNull("the card failed", guard.failure)
        assertTrue("the guard is still in the view", all(vv).contains(guard))
        val error = guard.content as ErrorCardView
        assertEquals(listOf(ErrorCardView.TITLE), texts(error))
        assertEquals(1, all(vv).filterIsInstance<ErrorCardView>().size)
    }

    @Test fun aHeadingWhoseDrawThrowsBecomesTheErrorCardInAMasonryView() {
        var armed = false
        val vv = dashboard(JSONArray().put(JSONObject().put("type", "heading").put("heading", "Living room")).put(tile())) { card, ctx ->
            if (card.type != "heading") return@dashboard null
            // A real heading card, with a view inside it whose draw throws.
            HeadingCardView(ctx, card).apply {
                addView(object : View(ctx.context) {
                    override fun onDraw(canvas: Canvas) {
                        if (armed) throw IllegalStateException("heading draw boom")
                    }
                }, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.px(8f)))
            }
        }
        val heading = all(vv).filterIsInstance<HeadingCardView>().single()
        val guard = heading.parent as ContainedCard
        assertTrue("Living room" in texts(vv))
        armed = true
        heading.invalidate()
        drawAll(vv)
        assertEquals(listOf("draw" to "heading"), contained.map { it.first to it.second })
        drawAll(vv)
        assertGuardShowsErrorCard(vv, guard)
        assertTrue("the heading left the view", all(vv).none { it is HeadingCardView })
        // The error card draws: the next frame throws nothing.
        drawAll(vv)
        assertEquals(1, contained.size)
        assertTileStillUpdates(vv)
    }

    @Test fun chipsWhoseMeasureThrowsInAStackInAMasonryViewBecomeTheErrorCard() {
        var armed = false
        var boomChip: View? = null
        val chipsCard = JSONObject().put("type", "custom:mushroom-chips-card").put("chips", JSONArray())
        val stack = JSONObject().put("type", "vertical-stack").put("cards", JSONArray().put(chipsCard).put(tile()))
        store.putLocal(EntityState("light.hall", "off", mapOf("friendly_name" to "Hall")))
        val vv = dashboard(JSONArray().put(stack).put(tile("light.hall"))) { card, ctx ->
            if (card.type != "custom:mushroom-chips-card") return@dashboard null
            // Real Mushroom chips, with a chip whose measure throws.
            MushroomChipsCardView(ctx, card).apply {
                addView(object : View(ctx.context) {
                    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                        if (armed) throw IndexOutOfBoundsException("chips measure boom")
                        setMeasuredDimension(ctx.px(40f), ctx.px(36f))
                    }
                }.also { boomChip = it })
            }
        }
        val chips = all(vv).filterIsInstance<MushroomChipsCardView>().single()
        val guard = chips.parent as ContainedCard
        val stackView = guard.parent as ViewGroup
        assertTrue("the stack is guarded too", stackView.parent is ContainedCard)
        armed = true
        // The chip asks for a new measure (its own spec hasn't changed, so asking on the card alone reuses its size).
        boomChip!!.requestLayout()
        drawAll(vv)
        assertEquals(listOf("measure" to "custom:mushroom-chips-card"), contained.map { it.first to it.second })
        drawAll(vv)
        assertGuardShowsErrorCard(vv, guard)
        assertSame("in the chips' slot in the stack", stackView, guard.parent)
        assertTrue(guard.height > 0)
        // The tile next to it in the stack, and the one outside it, keep following their lights.
        store.putLocal(EntityState("light.hall", "on", mapOf("friendly_name" to "Hall")))
        assertTileStillUpdates(vv)
        assertEquals(2, texts(vv).count { it.equals("On", ignoreCase = true) })
        assertEquals(1, contained.size)
    }

    @Test fun cardSurfacesAreNeverWrappedTwice() {
        val stack = JSONObject().put("type", "vertical-stack").put("cards", JSONArray().put(tile()))
        val conditional = JSONObject().put("type", "conditional").put("conditions", JSONArray())
            .put("card", JSONObject().put("type", "heading").put("heading", "Shown"))
        val vv = dashboard(JSONArray().put(stack).put(tile()).put(conditional)) { _, _ -> null }
        val guards = all(vv).filterIsInstance<ContainedCard>()
        assertTrue("a tile is a card surface, never guarded", guards.none { it.content is CardSurface })
        assertTrue("a guard never holds another", guards.none { it.content is ContainedCard || it.parent is ContainedCard })
        // The stack and the conditional's heading: one guard each.
        assertEquals(2, guards.size)
    }

    @Test fun aCardThatHidesItselfHidesItsGuardToo() {
        // auto-entities with nothing to show and `show_empty: false` takes no space, and no gap in its stack.
        val auto = JSONObject().put("type", "custom:auto-entities").put("show_empty", false)
            .put("filter", JSONObject().put("include", JSONArray().put(JSONObject().put("entity_id", "switch.none*").put("state", "on"))))
        val stack = JSONObject().put("type", "vertical-stack").put("cards", JSONArray().put(auto).put(tile()))
        val vv = dashboard(JSONArray().put(stack)) { _, _ -> null }
        val autoView = all(vv).filterIsInstance<dev.glassworks.engine.vitro.cards.AutoEntitiesCardView>().single()
        val guard = autoView.parent as ContainedCard
        assertEquals(View.GONE, autoView.visibility)
        assertEquals(View.GONE, guard.visibility)
        // Something to show: both come back.
        store.putLocal(EntityState("switch.none_kettle", "on", mapOf("friendly_name" to "Kettle")))
        drawAll(vv)
        assertEquals(View.VISIBLE, autoView.visibility)
        assertEquals(View.VISIBLE, guard.visibility)
        assertTrue(guard.height > 0)
        // Nothing again, once laid out: it empties itself (a layout request) before it hides, and the guard still follows.
        store.putLocal(EntityState("switch.none_kettle", "off", mapOf("friendly_name" to "Kettle")))
        drawAll(vv)
        assertEquals(View.GONE, autoView.visibility)
        assertEquals(View.GONE, guard.visibility)
        // The guard's own visibility (the card's conditions) still holds while the card shows.
        store.putLocal(EntityState("switch.none_kettle", "on", mapOf("friendly_name" to "Kettle")))
        drawAll(vv)
        assertEquals(View.VISIBLE, guard.visibility)
        guard.visibility = View.GONE
        assertEquals(View.GONE, guard.visibility)
        assertTrue(contained.isEmpty())
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun aThrowWhenHardwareRedrawsOneViewInsideAGuardedCardIsContained() {
        var armed = false
        var boom: View? = null
        val vv = dashboard(JSONArray().put(JSONObject().put("type", "heading").put("heading", "Living room")).put(tile())) { card, ctx ->
            if (card.type != "heading") return@dashboard null
            HeadingCardView(ctx, card).apply {
                addView(object : View(ctx.context) {
                    override fun onDraw(canvas: Canvas) {
                        if (armed) throw IllegalStateException("heading redraw boom")
                    }
                }.also { boom = it }, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.px(8f)))
            }
        }
        val guard = all(vv).filterIsInstance<HeadingCardView>().single().parent as ContainedCard
        vv.rootView.invalidate()
        hardwareFrame(vv)
        // Only the view inside changes: Android would rebuild its list alone, from the heading's, past the guard.
        armed = true
        boom!!.invalidate()
        hardwareFrame(vv)
        assertEquals(listOf("draw" to "heading"), contained.map { it.first to it.second })
        hardwareFrame(vv)
        assertGuardShowsErrorCard(vv, guard)
        hardwareFrame(vv)
        assertEquals(1, contained.size)
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

    @Test fun aFailedCardEndsTheSubscriptionsItRunsItselfAndNeverRenewsThem() {
        // The calendar card keeps its own calendar/event/subscribe handles, outside CardSurface's observe.
        store.putLocal(EntityState("calendar.maintenance", "off", mapOf("friendly_name" to "Maintenance")))
        var open = 0
        var opened = 0
        val ctx = VitroCardContext(
            context = activity, haBase = FakeHaServer.BASE, store = store,
            theme = dev.glassworks.engine.vitro.theme.ThemeResolver.resolve(dev.glassworks.engine.vitro.theme.ThemeCatalog(), true),
            resources = emptyList(), accessToken = { null }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
            fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
            haUser = { JSONObject().put("is_admin", true) },
            subscribeWs = { _, _, _ ->
                open++
                opened++
                var closed = false
                val unsub: () -> Unit = { if (!closed) { closed = true; open-- } }
                unsub
            },
        )
        val config = JSONObject().put("type", "calendar").put("entities", JSONArray().put("calendar.maintenance"))
        val card = dev.glassworks.engine.vitro.cards.CalendarCardView(ctx, LovelaceParser.parseCard(config))
        activity.setContentView(card)
        ShadowLooper.idleMainLooper()
        assertTrue("subscribed while shown", open > 0)
        card.fail("draw", IllegalStateException("calendar boom"))
        ShadowLooper.idleMainLooper()
        assertErrorCard(card)
        assertEquals("its own subscriptions ended", 0, open)
        val before = opened
        // Hidden and shown again, then detached and attached again: a failed card opens nothing.
        card.visibility = View.GONE
        card.visibility = View.VISIBLE
        activity.setContentView(View(activity))
        activity.setContentView(card)
        ShadowLooper.idleMainLooper()
        assertEquals(before, opened)
        assertEquals(0, open)
    }

    @Test fun aFailedTodoCardReadsNothingMoreWhenItsSubscriptionIsRejected() {
        store.putLocal(EntityState("todo.shopping", "2", mapOf("friendly_name" to "Shopping", "supported_features" to 15)))
        var opened = 0
        val rejects = ArrayList<(JSONObject?) -> Unit>()
        val ctx = VitroCardContext(
            context = activity, haBase = FakeHaServer.BASE, store = store,
            theme = dev.glassworks.engine.vitro.theme.ThemeResolver.resolve(dev.glassworks.engine.vitro.theme.ThemeCatalog(), true),
            resources = emptyList(), accessToken = { null }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
            fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
            subscribeWs = { _, _, onError ->
                opened++
                rejects += onError
                val unsub: () -> Unit = {}
                unsub
            },
        )
        val config = JSONObject().put("type", "todo-list").put("entity", "todo.shopping")
        val card = dev.glassworks.engine.vitro.cards.TodoListCardView(ctx, LovelaceParser.parseCard(config))
        activity.setContentView(card)
        ShadowLooper.idleMainLooper()
        assertEquals("the item subscription", 1, opened)
        card.fail("draw", IllegalStateException("todo boom"))
        ShadowLooper.idleMainLooper()
        // Home Assistant rejects the subscription after the card failed: a healthy card would read the list instead.
        rejects.toList().forEach { it(null) }
        ShadowLooper.idleMainLooper()
        org.robolectric.shadows.ShadowLooper.idleMainLooper(10, java.util.concurrent.TimeUnit.MINUTES)
        assertEquals("no fallback read, no resubscribe", 1, opened)
    }

    @Test fun everyWayTheStoreTellsItsListenersContainsAThrow() {
        val s = EntityStore()
        var heard = 0
        s.subscribe("sensor.a") { throw IllegalStateException("listener boom") }
        s.subscribe("sensor.a") { heard++ }
        // renotifyAll (HA's display data arrived) and markSnapshot (a subscription's first snapshot) too.
        s.renotifyAll()
        s.markSnapshot(emptySet())
        assertEquals(2, heard)
        assertEquals(2, contained.size)
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
