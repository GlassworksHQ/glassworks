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
import dev.glassworks.engine.ScrollTuning
import dev.glassworks.engine.vitro.cards.ClockCardView
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.ui.CardColumn
import dev.glassworks.engine.vitro.ui.CardSurface
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
 * Clarity's heavy view at the S1's size: how many views it is, per card type, and what [ScrollTuning.Knob.CULL]
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

    @After fun reset() = ScrollTuning.resetForTest()

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))

    private fun heavy(cull: Boolean): VitroView {
        ScrollTuning.setForTest(ScrollTuning.Knob.CULL, cull)
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
            val x0 = (loc[0] - root[0]).coerceIn(0, W)
            val y0 = (loc[1] - root[1]).coerceIn(0, H)
            for (y in y0 until (y0 + c.height).coerceAtMost(H)) for (x in x0 until (x0 + c.width).coerceAtMost(W)) px[y * W + x] = 0
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
            out.append("cull on, margin ${ScrollTuning.cullMarginPercent}%, scrollY $y: views in drawn cards $drawn, left out $left (${"%.0f".format(100.0 * left / (drawn + left))}%)\n")
            val px = screen(on)
            val n = px.indices.count { px[it] != offScreens[i][it] }
            if (n > 0) differ += "scrollY $y: $n pixels"
        }
        out.append("screen pixels, cull on vs off: " + if (differ.isEmpty()) "identical at every position\n" else "differ: $differ\n")
        File(dir, "heavy-measure.txt").writeText(out.toString())
        println(out)
        assertTrue("the screen differs with culling: $differ", differ.isEmpty())
    }

    private companion object {
        const val W = 1440
        const val H = 720
        const val NOW = 1_794_500_000_000L
    }
}
