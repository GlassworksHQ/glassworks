/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import dev.glassworks.engine.vitro.cards.ClockCardView
import dev.glassworks.engine.vitro.cards.EntitiesCardView
import dev.glassworks.engine.vitro.cards.EntityRowView
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.ui.CardColumn
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.ScrollPath
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroView
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * Clarity's heavy view at the S1's size: how many views it is, per card type, and what [ScrollPath.cull]
 * leaves drawn at a few scroll positions, with the screen's pixels the same with the knob on and off. A measure, run
 * only when asked (writes `heavy-measure.txt` into the directory it is given, which holds Clarity's dashboard):
 *
 * ```
 * (cd tools/perf && python3 -I -c "import sys,json; sys.path.insert(0,'.'); import dashboards as d; \
 *   json.dump(d.lovelace(d.DASHBOARD), open('$DIR/clarity-lovelace.json','w')); \
 *   json.dump({k: {'s': v['state'], 'a': v['attributes'], 'lc': v['last_changed']} for k, v in d.states().items()}, open('$DIR/clarity-states.json','w'))")
 * ./gradlew :engine-vitro:testDebugUnitTest --tests '*HeavyViewMeasure*' -PvitroHeavyMeasure=$DIR
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class HeavyViewMeasure {
    private val dir = System.getProperty("vitro.heavyMeasure")?.takeIf { it.isNotBlank() }?.let(::File)
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    @After fun reset() {
        ScrollPath.cull = true
        ScrollPath.contentClip = true
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))

    private fun heavy(cull: Boolean): VitroView {
        ScrollPath.cull = cull
        val store = EntityStore()
        store.applyCompressedEvent(JSONObject().put("a", JSONObject(File(dir, "clarity-states.json").readText())), snapshotScope = emptySet())
        val vv = VitroView(activity, store, 0xFF111111.toInt())
        vv.contextFactory = { theme ->
            VitroCardContext(
                context = activity, haBase = FakeHaServer.BASE, store = store, theme = theme, resources = emptyList(),
                accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
                fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} }, now = { NOW },
            )
        }
        activity.setContentView(vv)
        vv.setDashboard(LovelaceParser.parseDashboard(JSONObject(File(dir, "clarity-lovelace.json").readText())), "heavy")
        repeat(20) { idle() }
        layOut(vv)
        return vv
    }

    private fun layOut(vv: VitroView) {
        vv.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        vv.layout(0, 0, W, H)
    }

    private fun count(v: View): Int = 1 + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).sumOf { count(g.getChildAt(it)) } } ?: 0)

    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    private fun typeOf(v: View): String = (v as? CardSurface)?.cardType ?: all(v).filterIsInstance<CardSurface>().firstOrNull()?.cardType?.let { "$it (wrapped)" }
        ?: v.javaClass.simpleName

    /** The screen as drawn, the clock's card blanked (it shows the real time, seconds included). */
    private fun screen(vv: VitroView): IntArray {
        layOut(vv)
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        vv.draw(Canvas(bmp))
        val px = IntArray(W * H).also { bmp.getPixels(it, 0, W, 0, 0, W, H) }
        for (c in all(vv).filterIsInstance<ClockCardView>()) {
            val loc = IntArray(2).also { c.getLocationInWindow(it) }
            val root = IntArray(2).also { vv.getLocationInWindow(it) }
            // The clock's own rectangle, then its part on screen (none when it's scrolled off).
            val left = loc[0] - root[0]
            val top = loc[1] - root[1]
            val x0 = left.coerceIn(0, W)
            val y0 = top.coerceIn(0, H)
            val x1 = (left + c.width).coerceIn(0, W)
            val y1 = (top + c.height).coerceIn(0, H)
            for (y in y0 until y1) for (x in x0 until x1) px[y * W + x] = 0
        }
        return px
    }

    @Test
    fun measure() {
        assumeTrue("set -PvitroHeavyMeasure=<dir> with Clarity's dashboard in it", dir != null && File(dir, "clarity-lovelace.json").isFile)
        val out = StringBuilder()
        val off = heavy(cull = false)
        val scroll = off.currentScroll()!!
        val content = scroll.getChildAt(0)
        out.append("heavy at ${W}x$H: ${count(off)} views under the Vitro view, ${count(content)} in the scroll content, content ${content.height} px tall (${"%.1f".format(content.height.toDouble() / H)} screens)\n")
        val cards = all(content).filterIsInstance<CardColumn>().flatMap { col -> (0 until col.childCount).map { col.getChildAt(it) } }
            .filter { it.visibility != View.GONE }
        val byType = cards.groupBy(::typeOf).mapValues { (_, vs) -> vs.map(::count) }
        out.append("${cards.size} cards in the columns, ${cards.sumOf(::count)} views\n")
        out.append(String.format("%-28s %5s %7s %7s %7s%n", "card type", "cards", "views", "mean", "max"))
        for ((type, counts) in byType.entries.sortedByDescending { it.value.sum() }) {
            out.append(String.format("%-28s %5d %7d %7.1f %7d%n", type, counts.size, counts.sum(), counts.average(), counts.max()))
        }

        val positions = listOf(0, content.height / 4, content.height / 2, content.height - H).map { it.coerceAtLeast(0) }
        val offScreens = positions.map { y -> scroll.scrollTo(0, y); idle(); screen(off) }

        val on = heavy(cull = true)
        val onScroll = on.currentScroll()!!
        val cull = on.currentCull()!!
        val differ = ArrayList<String>()
        for ((i, y) in positions.withIndex()) {
            onScroll.scrollTo(0, y)
            idle()
            cull.update()
            val (drawn, left) = cull.counts()
            out.append("cull on, scrollY $y: views in drawn cards $drawn, left out $left (${"%.0f".format(100.0 * left / (drawn + left))}%)\n")
            val px = screen(on)
            val n = px.indices.count { px[it] != offScreens[i][it] }
            if (n > 0) differ += "scrollY $y: $n pixels"
        }
        out.append("screen pixels, cull on vs off: " + if (differ.isEmpty()) "identical at every position\n" else "differ: $differ\n")
        File(dir, "heavy-measure.txt").writeText(out.toString())
        println(out)
        assertTrue("the screen differs with culling: $differ", differ.isEmpty())
    }

    /**
     * What the heavy view's entities cards cost to make, measure, lay out and draw, off the dashboard, at the width
     * the view gives them: the median of [ROUNDS] rounds after a warm-up, all 26 cards each round. Robolectric's
     * clock is the computer's, not the S1's, so only the split and a before/after ratio mean anything: the rows as
     * one view each ([EntityRowView]) and as the four views they were made of, measured in turns. Also the views one
     * row is made of, each made [ROW_PARTS] times. Appends to `heavy-measure.txt`.
     */
    @Test
    fun entitiesCost() {
        assumeTrue("set -PvitroHeavyMeasure=<dir> with Clarity's dashboard in it", dir != null && File(dir, "clarity-lovelace.json").isFile)
        val vv = heavy(cull = true)
        val ctx = vv.contextFactory!!(dev.glassworks.engine.vitro.theme.ThemeResolver.resolve(dev.glassworks.engine.vitro.theme.ThemeCatalog(), true))
        val sample = all(vv.currentScroll()!!.getChildAt(0)).filterIsInstance<EntitiesCardView>()
        val width = sample.maxOf { it.width }
        val view = JSONObject(File(dir, "clarity-lovelace.json").readText()).getJSONArray("views").let { vs ->
            (0 until vs.length()).map { vs.getJSONObject(it) }.single { it.optString("path") == "heavy" }
        }
        val cfgs = LovelaceParser.parseCards(view.getJSONArray("cards")).filter { it.type == "entities" }
        val rows = cfgs.sumOf { it.entities().size }
        val wSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val bmp = Bitmap.createBitmap(width, 2000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // The rows as one view each, and (EntityRowView.enabled off) as the four views they replaced: rounds of the two
        // alternate, so both see the same machine.
        class Cost(val flat: Boolean) {
            val make = ArrayList<Long>(); val measure = ArrayList<Long>(); val layout = ArrayList<Long>(); val draw = ArrayList<Long>()
            var views = 0
        }
        val costs = listOf(Cost(flat = false), Cost(flat = true))
        try {
            repeat(WARMUP + ROUNDS) { round ->
                for (c in costs) {
                    EntityRowView.enabled = c.flat
                    val t0 = System.nanoTime()
                    val cards = cfgs.map { EntitiesCardView(ctx, it) }
                    val t1 = System.nanoTime()
                    cards.forEach { it.measure(wSpec, hSpec) }
                    val t2 = System.nanoTime()
                    cards.forEach { it.layout(0, 0, it.measuredWidth, it.measuredHeight) }
                    val t3 = System.nanoTime()
                    cards.forEach { it.draw(canvas) }
                    val t4 = System.nanoTime()
                    if (round >= WARMUP) { c.make += t1 - t0; c.measure += t2 - t1; c.layout += t3 - t2; c.draw += t4 - t3 }
                    c.views = cards.sumOf(::count)
                }
            }
        } finally {
            EntityRowView.enabled = true
        }
        fun ms(v: List<Long>) = v.sorted()[v.size / 2] / 1e6
        val out = StringBuilder()
        out.append("entities cost: ${cfgs.size} cards, $rows rows, $width px wide, median of $ROUNDS rounds\n")
        for (c in costs) {
            out.append(String.format(
                "  %-22s %4d views (%.2f a row, card frames included): make %.1f ms, measure %.1f ms, layout %.1f ms, draw %.1f ms, all %.1f ms%n",
                if (c.flat) "rows as one view" else "rows as four views", c.views, c.views.toDouble() / rows,
                ms(c.make), ms(c.measure), ms(c.layout), ms(c.draw), ms(c.make) + ms(c.measure) + ms(c.layout) + ms(c.draw),
            ))
        }
        // The parts a row was made of before it was drawn as one view, and that view, made alone.
        val rowPaint = EntityRowView.paint(ctx)
        val parts = linkedMapOf<String, () -> View>(
            "EntityRowView" to { EntityRowView(ctx, rowPaint, null) },
            "TextView" to { android.widget.TextView(activity) },
            "LinearLayout" to { android.widget.LinearLayout(activity) },
            "MdiIconView" to { dev.glassworks.engine.vitro.ui.MdiIconView(activity) },
            "HaSwitchView" to { dev.glassworks.engine.vitro.cards.HaSwitchView(ctx) },
        )
        for ((name, f) in parts) {
            repeat(ROW_PARTS) { f() }
            val t = System.nanoTime()
            repeat(ROW_PARTS) { f() }
            out.append(String.format("  %-13s %.1f µs each%n", name, (System.nanoTime() - t) / 1e3 / ROW_PARTS))
        }
        File(dir, "heavy-measure.txt").appendText(out.toString())
        println(out)
    }

    private companion object {
        const val WARMUP = 5
        const val ROUNDS = 15
        const val ROW_PARTS = 2_000
        const val W = 1440
        const val H = 720
        const val NOW = 1_794_500_000_000L
    }
}
