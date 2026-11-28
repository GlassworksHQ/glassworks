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
import dev.glassworks.engine.ScrollTuning
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
 * The scroll knobs ([ScrollTuning]): with each on, a card draws what it drew with it off, and the outline clip
 * ([ScrollTuning.Knob.CLIP]) stays on exactly the cards whose content reaches their rounded corners.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class ScrollKnobsTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val store = EntityStore()

    @After fun forget() {
        HaDisplay.reset()
        ScrollTuning.resetForTest()
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
        Triple("entity", """{"type": "entity", "entity": "sensor.power"}""", false),
        Triple("glance", """{"type": "glance", "entities": ["light.kitchen", "switch.fan", "sensor.temp"]}""", false),
        // Content along the edges: the clip stays.
        Triple("sensor with graph", """{"type": "sensor", "entity": "sensor.temp", "graph": "line"}""", true),
        Triple("picture", """{"type": "picture", "image": "/local/room.png"}""", true),
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
        ScrollTuning.setForTest(ScrollTuning.Knob.CLIP, true)
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
        ScrollTuning.setForTest(ScrollTuning.Knob.CLIP, false)
        for ((name, json, _) in cards) {
            val card = show(build(json))
            assertTrue(name, card.clipToOutline)
        }
    }

    @Test
    fun eachCardDrawsTheSamePixelsWithTheKnobsOnAndOff() {
        seed()
        val knobs = listOf(ScrollTuning.Knob.CLIP, ScrollTuning.Knob.RELAYOUT, ScrollTuning.Knob.ANIM_SNAP, ScrollTuning.Knob.CLOCK_SPAN)
        val differ = ArrayList<String>()
        for ((name, json, _) in cards + Triple("clock", """{"type": "clock", "clock_size": "large", "show_seconds": true}""", false)) {
            knobs.forEach { ScrollTuning.setForTest(it, false) }
            val off = show(build(json))
            val offPixels = pixels(off)
            knobs.forEach { ScrollTuning.setForTest(it, true) }
            val on = show(build(json))
            assertEquals(name, off.width to off.height, on.width to on.height)
            if (!pixels(on).contentEquals(offPixels)) differ += name
        }
        assertTrue("differ: $differ", differ.isEmpty())
    }

    @Test
    fun aSensorCardsLiveValueLaysOutOnlyItsCard() {
        seed()
        ScrollTuning.setForTest(ScrollTuning.Knob.RELAYOUT, true)
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
    fun offASensorCardsLiveValueAsksTheWindow() {
        seed()
        ScrollTuning.setForTest(ScrollTuning.Knob.RELAYOUT, false)
        val root = Root(activity)
        activity.setContentView(root)
        val card = build("""{"type": "entity", "entity": "sensor.power"}""")
        root.addView(card, FrameLayout.LayoutParams(COLUMN, ViewGroup.LayoutParams.WRAP_CONTENT))
        idle()
        root.requests = 0
        store.putLocal(state("sensor.power", "1234.5", mapOf("unit_of_measurement" to "W", "device_class" to "power")))
        idle()
        assertTrue(root.requests > 0)
    }

    @Test
    fun aRectAtTheCornerNeedsTheClipAndOneInsideThePaddingDoesNot() {
        ScrollTuning.setForTest(ScrollTuning.Knob.CLIP, true)
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
     * canvas ignores it): each card with the knob on against the knob off, the differing pixels counted. Without the
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
                ScrollTuning.setForTest(ScrollTuning.Knob.CLIP, false)
                val off = gpuPixels(build(json))
                ScrollTuning.setForTest(ScrollTuning.Knob.CLIP, true)
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
