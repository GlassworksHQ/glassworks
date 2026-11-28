/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.glassworks.engine.vitro.cards.ClockCardView
import dev.glassworks.engine.vitro.cards.EntitiesCardView
import dev.glassworks.engine.vitro.cards.EntityCardView
import dev.glassworks.engine.vitro.cards.GlanceCardView
import dev.glassworks.engine.vitro.cards.PictureCardView
import dev.glassworks.engine.vitro.cards.TileCardView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.OutlineClip
import dev.glassworks.engine.vitro.ui.ScrollPath
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * The scroll path ([ScrollPath]): a card draws what it drew before, the outline clip stays on exactly the cards whose
 * content reaches their rounded corners, and a culled masonry view draws the same screen as one that draws every card.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class ScrollPathTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val store = EntityStore()

    @After fun forget() {
        HaDisplay.reset()
        ScrollPath.cull = true
        ScrollPath.contentClip = true
    }

    private fun ctx() = VitroCardContext(
        context = activity, haBase = FakeHaServer.BASE, store = store,
        theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> },
        navigate = {}, openMoreInfo = {}, fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
        now = { NOW },
    )

    private fun state(id: String, value: String, attrs: Map<String, Any?> = emptyMap()) = EntityState(
        entityId = id, state = value, attributes = mapOf<String, Any?>("friendly_name" to id.substringAfter('.')) + attrs,
        lastChanged = NOW / 1000.0 - 60, lastUpdated = NOW / 1000.0 - 60,
    )

    private fun seed() {
        store.putLocal(state("light.kitchen", "on", mapOf("brightness" to 180, "supported_color_modes" to listOf("brightness"), "color_mode" to "brightness")))
        store.putLocal(state("switch.fan", "off"))
        store.putLocal(state("sensor.power", "512", mapOf("unit_of_measurement" to "W", "device_class" to "power")))
        store.putLocal(state("sensor.temp", "21.5", mapOf("unit_of_measurement" to "°C", "device_class" to "temperature")))
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))

    /** The cards heavy is made of, and corner cases: (name, config, whether its clip must stay). */
    private val cards = listOf(
        Triple("tile", """{"type": "tile", "entity": "light.kitchen"}""", false),
        Triple("tile with a feature", """{"type": "tile", "entity": "light.kitchen", "features": [{"type": "light-brightness"}]}""", false),
        Triple("entities", """{"type": "entities", "title": "Room", "entities": ["light.kitchen", "switch.fan", "sensor.power"]}""", false),
        Triple("entities without title", """{"type": "entities", "entities": ["switch.fan", "sensor.temp"]}""", false),
        // Corners rounder than the rows' 16 px inset: a row's ripple reaches into them, so the clip stays, as it did
        // when a row was a LinearLayout of views.
        Triple(
            "entities with round corners",
            """{"type": "entities", "entities": ["sensor.temp", "switch.fan"], "card_mod": {"style": "ha-card { border-radius: 40px }"}}""",
            true,
        ),
        Triple("entity", """{"type": "entity", "entity": "sensor.power"}""", false),
        Triple("glance", """{"type": "glance", "entities": ["light.kitchen", "switch.fan", "sensor.temp"]}""", false),
        // Content along the edges: the clip stays.
        Triple("sensor with graph", """{"type": "sensor", "entity": "sensor.temp", "graph": "line"}""", true),
        Triple("picture", """{"type": "picture", "image": "/local/room.png"}""", true),
        // A card_mod gradient is a rectangle the clip rounds: kept, whatever the content.
        Triple(
            "tile with a card_mod gradient",
            """{"type": "tile", "entity": "light.kitchen", "card_mod": {"style": "ha-card { background: linear-gradient(90deg, #102030, #405060); border: 3px solid #ff0000; border-radius: 40px }"}}""",
            true,
        ),
        Triple(
            "entities with card_mod padding 0",
            """{"type": "entities", "entities": ["switch.fan"], "card_mod": {"style": "ha-card { padding: 0px; background: #223344; }"}}""",
            true,
        ),
    )

    /** The card as VitroView builds it: its view, then its card_mod. */
    private fun build(json: String): CardSurface {
        val config = LovelaceParser.parseCard(JSONObject(json))
        val ctx = ctx()
        val card = when (config.type) {
            "tile" -> TileCardView(ctx, config)
            "entities" -> EntitiesCardView(ctx, config)
            "entity", "sensor" -> EntityCardView(ctx, config)
            "glance" -> GlanceCardView(ctx, config)
            "picture" -> PictureCardView(ctx, config)
            "clock" -> ClockCardView(ctx, config)
            else -> error(config.type)
        }
        card.applyCardMod(config)
        return card
    }

    /** [card] laid out at a masonry column's width on screen, drawn once (where the clip is decided). */
    private fun show(card: CardSurface): CardSurface {
        val root = FrameLayout(activity)
        activity.setContentView(root)
        root.addView(card, FrameLayout.LayoutParams(COLUMN, ViewGroup.LayoutParams.WRAP_CONTENT))
        idle()
        pixels(card)
        return card
    }

    private fun pixels(v: View): IntArray {
        val bmp = Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        return IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
    }

    @Test
    fun theClipStaysOnlyWhereContentReachesTheCorners() {
        seed()
        ScrollPath.contentClip = true
        val wrong = ArrayList<String>()
        for ((name, json, clips) in cards) {
            val card = show(build(json))
            val reason = OutlineClip.needsClip(card)
            if (card.clipToOutline != clips || (reason != null) != clips) wrong += "$name: clip=${card.clipToOutline} ($reason)"
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun offEveryRoundedCardClipsAsBefore() {
        seed()
        ScrollPath.contentClip = false
        for ((name, json, _) in cards) {
            val card = show(build(json))
            assertTrue(name, card.clipToOutline)
        }
    }

    @Test
    fun eachCardDrawsTheSamePixelsWithTheContentClipAsWithout() {
        seed()
        val differ = ArrayList<String>()
        for ((name, json, _) in cards + Triple("clock", """{"type": "clock", "clock_size": "large", "show_seconds": true}""", false)) {
            ScrollPath.contentClip = false
            val off = show(build(json))
            val offPixels = pixels(off)
            ScrollPath.contentClip = true
            val on = show(build(json))
            assertEquals(name, off.width to off.height, on.width to on.height)
            if (!pixels(on).contentEquals(offPixels)) differ += name
        }
        assertTrue("differ: $differ", differ.isEmpty())
    }

    @Test
    fun aSensorCardsLiveValueLaysOutOnlyItsCard() {
        seed()
        val root = Root(activity)
        activity.setContentView(root)
        val card = build("""{"type": "entity", "entity": "sensor.power"}""")
        root.addView(card, FrameLayout.LayoutParams(COLUMN, ViewGroup.LayoutParams.WRAP_CONTENT))
        idle()
        root.requests = 0
        store.putLocal(state("sensor.power", "1234.5", mapOf("unit_of_measurement" to "W", "device_class" to "power")))
        idle()
        assertEquals("the request stopped at the card", 0, root.requests)
        assertFalse(root.isLayoutRequested)

        // A card built with that value, laid out whole: the same pixels.
        val fresh = build("""{"type": "entity", "entity": "sensor.power"}""")
        root.addView(fresh, FrameLayout.LayoutParams(COLUMN, ViewGroup.LayoutParams.WRAP_CONTENT))
        idle()
        assertTrue(pixels(fresh).contentEquals(pixels(card)))
    }

    @Test
    fun aRectAtTheCornerNeedsTheClipAndOneInsideThePaddingDoesNot() {
        ScrollPath.contentClip = true
        val ctx = ctx()
        val inside = object : CardSurface(ctx) {}
        inside.addView(View(activity).apply { setBackgroundColor(0xFF00FF00.toInt()) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40).apply { setMargins(16, 16, 16, 16) })
        show(inside)
        assertNull(OutlineClip.needsClip(inside))
        assertFalse(inside.clipToOutline)

        val corner = object : CardSurface(ctx) {}
        corner.addView(View(activity).apply { setBackgroundColor(0xFF00FF00.toInt()) }, FrameLayout.LayoutParams(COLUMN, 40))
        show(corner)
        assertNotNull(OutlineClip.needsClip(corner))
        assertTrue(corner.clipToOutline)
    }

    /**
     * The clip's effect as the GPU path draws it (Robolectric's HWUI renderer, which honors clipToOutline; a software
     * canvas ignores it): each card with the content clip against every rounded card clipping, the differing pixels counted. Without the
     * clip, a card's anti-aliased corner is the fill's own edge; with it, the edge is anti-aliased twice (the fill's
     * and the clip's coverage multiplied), a shade thinner: measured 2026-11-27, 105 corner pixels per card differ, by up
     * to 41 of 255, and elsewhere a few edge pixels by 1 (Skia's CPU rasterizer rounding through the clip's mask).
     */
    @Test
    fun onTheGpuPathTheCardsDifferOnlyInTheirCornersEdgePixels() {
        System.setProperty("robolectric.pixelCopyRenderMode", "hardware")
        try {
            seed()
            val report = ArrayList<String>()
            val outside = ArrayList<String>()
            for ((name, json, _) in cards) {
                ScrollPath.contentClip = false
                val off = gpuPixels(build(json))
                ScrollPath.contentClip = true
                val card = build(json)
                val on = gpuPixels(card)
                assertEquals(name, off.size, on.size)
                val w = card.width
                val r = (ctx().theme.cardBorderRadiusDp * ctx().dp).toInt() + 1
                var n = 0
                var max = 0
                for (i in on.indices) {
                    if (on[i] == off[i]) continue
                    n++
                    val d = (0..24 step 8).maxOf { sh -> Math.abs(((on[i] ushr sh) and 0xFF) - ((off[i] ushr sh) and 0xFF)) }
                    max = maxOf(max, d)
                    val x = i % w
                    val y = i / w
                    val inCorner = (x < r || x >= w - r) && (y < r || y >= card.height - r)
                    // Away from the corners, only the rasterizer's rounding through a clip mask (1 of 255).
                    if (!inCorner && d > 1) outside += "$name ($x,$y) by $d"
                }
                report += "$name: clip=${card.clipToOutline} differing=$n maxDelta=$max"
                // With -PvitroMockupDir: the two renders and their difference (x8), to look at.
                System.getProperty("vitro.mockupDir")?.takeIf { it.isNotEmpty() }?.let { dir ->
                    val slug = name.replace(Regex("[^a-z0-9]+"), "-")
                    fun png(px: IntArray, file: String) {
                        val b = Bitmap.createBitmap(px, w, card.height, Bitmap.Config.ARGB_8888)
                        java.io.File(dir).mkdirs()
                        java.io.FileOutputStream(java.io.File(dir, file)).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    png(off, "clip-$slug-off.png")
                    png(on, "clip-$slug-on.png")
                    png(IntArray(on.size) { i ->
                        val d = (0..16 step 8).maxOf { sh -> Math.abs(((on[i] ushr sh) and 0xFF) - ((off[i] ushr sh) and 0xFF)) }
                        val v = (d * 8).coerceAtMost(255)
                        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                    }, "clip-$slug-diff.png")
                }
            }
            println("GPU clip on/off:\n" + report.joinToString("\n"))
            assertTrue("pixels differ away from the corners: ${outside.take(20)}\n$report", outside.isEmpty())
        } finally {
            System.clearProperty("robolectric.pixelCopyRenderMode")
        }
    }

    /** [card] shown alone at a column's width, captured through the window's hardware path (PixelCopy). */
    private fun gpuPixels(card: CardSurface): IntArray {
        show(card)
        val loc = IntArray(2).also { card.getLocationInWindow(it) }
        val win = activity.window.decorView
        val bmp = Bitmap.createBitmap(win.width, win.height, Bitmap.Config.ARGB_8888)
        var done = false
        android.view.PixelCopy.request(activity.window, bmp, { done = true }, android.os.Handler(Looper.getMainLooper()))
        idle()
        assertTrue("PixelCopy answered", done)
        val out = IntArray(card.width * card.height)
        bmp.getPixels(out, 0, card.width, loc[0], loc[1], card.width, card.height)
        return out
    }

    /** A masonry view of [n] entity cards (one sensor each) at the S1's size, with culling [cull]. */
    private fun masonry(n: Int, cull: Boolean, host: ViewGroup? = null): dev.glassworks.engine.vitro.ui.VitroView {
        ScrollPath.cull = cull
        val cards = org.json.JSONArray()
        for (i in 0 until n) {
            store.putLocal(state("sensor.s$i", "${10 + i}", mapOf("unit_of_measurement" to "W")))
            cards.put(JSONObject().put("type", if (i % 3 == 0) "entities" else "entity")
                .apply { if (i % 3 == 0) put("entities", org.json.JSONArray().put("sensor.s$i").put("switch.fan")) else put("entity", "sensor.s$i") })
        }
        val dash = JSONObject().put("views", org.json.JSONArray().put(JSONObject().put("path", "v").put("cards", cards)))
        val vv = dev.glassworks.engine.vitro.ui.VitroView(activity, store, 0xFF111111.toInt())
        vv.contextFactory = { theme ->
            VitroCardContext(
                context = activity, haBase = FakeHaServer.BASE, store = store, theme = theme, resources = emptyList(),
                accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
                fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} }, now = { NOW },
            )
        }
        if (host != null) {
            host.addView(vv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            activity.setContentView(host)
        } else {
            activity.setContentView(vv)
        }
        vv.setDashboard(LovelaceParser.parseDashboard(JSONObject(dash.toString())), "v")
        repeat(10) { idle() }
        screenOf(vv)
        return vv
    }

    private fun screenOf(vv: View): IntArray {
        vv.measure(View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        vv.layout(0, 0, 1440, 720)
        val bmp = Bitmap.createBitmap(1440, 720, Bitmap.Config.ARGB_8888)
        vv.draw(Canvas(bmp))
        return IntArray(1440 * 720).also { bmp.getPixels(it, 0, 1440, 0, 0, 1440, 720) }
    }

    @Test
    fun cullingLeavesFarCardsOutAndDrawsTheSameScreen() {
        seed()
        val off = masonry(90, cull = false)
        val on = masonry(90, cull = true)
        val cull = requireNotNull(on.currentCull())
        for (y in listOf(0, 2_000, 4_000)) {
            off.currentScroll()!!.scrollTo(0, y)
            on.currentScroll()!!.scrollTo(0, y)
            idle()
            cull.update()
            val (drawn, left) = cull.counts()
            assertTrue("some cards left out at $y: $drawn drawn, $left left", left > drawn)
            assertTrue("the same screen at $y", screenOf(on).contentEquals(screenOf(off)))
        }
        cull.release()
    }

    @Test
    fun aCulledCardCatchesUpBeforeItIsDrawnAgain() {
        seed()
        val vv = masonry(90, cull = true)
        val scroll = vv.currentScroll()!!
        val cull = requireNotNull(vv.currentCull())
        scroll.scrollTo(0, 0)
        idle()
        cull.update()
        fun texts(v: View): List<String> = if (v is ViewGroup) (0 until v.childCount).flatMap { texts(v.getChildAt(it)) } else listOfNotNull((v as? android.widget.TextView)?.text?.toString())
        // A card far below, left out: its live change waits.
        val card = generateSequence(listOf<View>(scroll)) { level -> level.flatMap { v -> (v as? ViewGroup)?.let { g -> (0 until g.childCount).map(g::getChildAt) } ?: emptyList() }.takeIf { it.isNotEmpty() } }
            .flatten().filterIsInstance<EntityCardView>().first { it.culled }
        val entity = "sensor." + texts(card).first { Regex("""s\d+""").matches(it) }
        store.putLocal(state(entity, "777", mapOf("unit_of_measurement" to "W")))
        idle()
        assertFalse("not rendered while left out", "777" in texts(card))
        // Scrolled to it: drawn again, caught up first.
        val at = IntArray(2).also(card::getLocationInWindow)[1] - IntArray(2).also(scroll::getLocationInWindow)[1] + scroll.scrollY
        scroll.scrollTo(0, (at - 100).coerceAtLeast(0))
        cull.update()
        assertFalse(card.culled)
        assertTrue("caught up as it came back", "777" in texts(card))
    }

    /**
     * A culled card whose content is replaced by one that must always be drawn (an auto-entities card that now matches a
     * camera) is drawn again at once; one whose new content may stay culled keeps its live changes waiting.
     */
    @Test
    fun aCulledCardThatNowHoldsACameraIsDrawnAgain() {
        seed()
        val vv = masonry(90, cull = true)
        val cull = requireNotNull(vv.currentCull())
        vv.currentScroll()!!.scrollTo(0, 0)
        cull.update()
        val columns = generateSequence(listOf<View>(vv.currentScroll()!!)) { level ->
            level.flatMap { v -> (v as? ViewGroup)?.let { g -> (0 until g.childCount).map(g::getChildAt) } ?: emptyList() }.takeIf { it.isNotEmpty() }
        }.flatten().filterIsInstance<dev.glassworks.engine.vitro.ui.CardColumn>().toList()
        val (column, card) = columns.firstNotNullOf { col ->
            (0 until col.childCount).map(col::getChildAt).lastOrNull { cull.skips(it) }?.let { col to it as CardSurface }
        }
        // New content that may stay culled: still left out, and its live changes wait too.
        val plain = object : CardSurface(ctx()) {}
        card.addView(plain)
        dev.glassworks.engine.vitro.ui.ViewportCull.contentChanged(plain)
        assertTrue(cull.skips(card))
        assertTrue(plain.culled)
        // A picture card (a camera's): drawn again at once.
        val picture = PictureCardView(ctx(), LovelaceParser.parseCard(JSONObject("""{"type": "picture", "image": "/local/room.png"}""")))
        card.addView(picture)
        dev.glassworks.engine.vitro.ui.ViewportCull.contentChanged(picture)
        assertFalse("drawn again", cull.skips(card))
        assertFalse(card.culled)
        assertFalse(plain.culled)
        // And it stays drawn as the view scrolls on.
        vv.currentScroll()!!.scrollTo(0, 10)
        cull.update()
        assertFalse(cull.skips(card))
        assertTrue(column.indexOfChild(card) >= 0)
    }

    /**
     * A view built again (another dashboard, the same scroller's view rebuilt) keeps nothing of the old scroller: its
     * culling, and with it the old card tree, can go.
     */
    @Test
    fun aRebuiltViewLeavesTheOldScrollersCullingCollectable() {
        seed()
        val vv = masonry(30, cull = true)
        val oldScroll = java.lang.ref.WeakReference(vv.currentScroll()!!)
        val oldCull = java.lang.ref.WeakReference(requireNotNull(vv.currentCull()))
        val cards = org.json.JSONArray()
        for (i in 0 until 30) cards.put(JSONObject().put("type", "entity").put("entity", "sensor.s$i").put("name", "again $i"))
        vv.setDashboard(LovelaceParser.parseDashboard(JSONObject().put("views", org.json.JSONArray().put(JSONObject().put("path", "v").put("cards", cards)))), "v")
        repeat(10) { idle() }
        val newCull = requireNotNull(vv.currentCull())
        assertTrue("a new scroller, with its own culling", newCull !== oldCull.get())
        repeat(20) {
            if (oldCull.get() == null && oldScroll.get() == null) return@repeat
            System.gc()
            Thread.sleep(20)
            idle()
        }
        assertNull("the old culling is gone", oldCull.get())
        assertNull("the old scroller is gone", oldScroll.get())
    }

    /** Above the Vitro view: counts the redraws asked of the window (each one a frame on a device). */
    private class Redraws(context: Context) : FrameLayout(context) {
        var asked = 0
        override fun onDescendantInvalidated(child: View, target: View) {
            asked++
            super.onDescendantInvalidated(child, target)
        }
    }

    /**
     * A culled view at rest asks for no more redraws than one that draws every card: the cull pass changes nothing (and
     * invalidates nothing) while no card crosses the band, and a culled card's live changes wait until it's back.
     */
    @Test
    fun aCulledViewAtRestAsksForNoMoreRedrawsThanOneThatDrawsEveryCard() {
        seed()
        fun redrawsAtRest(cull: Boolean): Int {
            val host = Redraws(activity)
            val vv = masonry(90, cull, host)
            vv.currentScroll()!!.scrollTo(0, 0)
            repeat(5) { idle() }
            vv.currentCull()?.update()
            repeat(5) { idle() }
            host.asked = 0
            // Ten seconds at rest: a live change on screen (the first cards) and one far below, each second.
            for (i in 0 until 10) {
                store.putLocal(state("sensor.s${i % 3}", "${100 + i}", mapOf("unit_of_measurement" to "W")))
                store.putLocal(state("sensor.s89", "${200 + i}", mapOf("unit_of_measurement" to "W")))
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            }
            return host.asked
        }
        val off = redrawsAtRest(cull = false)
        val on = redrawsAtRest(cull = true)
        println("redraws asked at rest over 10 s: culled $on, every card drawn $off")
        assertTrue("the live changes drew", off > 0)
        assertTrue("culled $on redraws, not culled $off", on <= off)
    }

    /** The window's root: counts the layout requests that reach it. */
    private class Root(context: Context) : FrameLayout(context) {
        var requests = 0
        override fun requestLayout() {
            requests++
            super.requestLayout()
        }
    }

    private companion object {
        const val NOW = 1_800_000_000_000L

        /** A heavy masonry column on the S1: 960 dp in three, at density 1.5. */
        const val COLUMN = 476
    }
}
