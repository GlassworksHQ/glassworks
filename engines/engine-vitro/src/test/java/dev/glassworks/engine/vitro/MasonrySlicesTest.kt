/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import dev.glassworks.engine.vitro.cards.IframeCardView
import dev.glassworks.engine.vitro.cards.MarkdownCardView
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.ui.MasonryColumnsLayout
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroMetrics
import dev.glassworks.engine.vitro.ui.VitroView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * The masonry view builds as HA's does (`hui-masonry-view.ts` `_createColumns`): its columns on screen first, then its
 * cards in config order, at most 16 ms of them at a time with a frame between, and the finished view the same as one
 * built in one go. Slices are counted on a clock the test moves ([VitroView.sliceClock]): 5 ms per look, so four cards
 * fit in a slice (the budget is checked before each card after the first, as HA checks it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1440dp-h720dp-mdpi")
class MasonrySlicesTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    private fun cards(prefix: String, n: Int) = JSONArray().apply {
        // Contents of different lengths, so the columns are of different heights and the placement isn't trivial.
        for (i in 0 until n) put(JSONObject().put("type", "markdown").put("content", "$prefix $i" + "\n\nline".repeat(i % 4)))
    }

    private fun dashboard(vararg views: Pair<String, Int>) = JSONObject().put(
        "views",
        JSONArray().apply {
            for ((path, n) in views) put(JSONObject().put("path", path).put("title", path.uppercase()).put("cards", cards(path, n)))
        },
    ).toString()

    /** A view of [dashboard]; [sliced]: on a clock that moves 5 ms per look, else on Robolectric's (one slice). */
    private fun vitroView(
        dashboard: String,
        sliced: Boolean,
        metrics: MutableList<VitroMetrics> = ArrayList(),
        layers: VitroView.CardLayers? = null,
    ): VitroView {
        val store = EntityStore()
        val vv = VitroView(activity, store, 0xFF000000.toInt())
        layers?.let { vv.cardLayers = it }
        if (sliced) {
            var t = 0L
            vv.sliceClock = { t += 5; t }
            vv.afterNextFrame = { task ->
                nextFrame += task
                val cancel = { nextFrame.remove(task); Unit }
                cancel
            }
        }
        vv.onMetrics = { metrics += it }
        vv.contextFactory = { theme ->
            VitroCardContext(
                context = activity, haBase = FakeHaServer.BASE, store = store, theme = theme, resources = emptyList(),
                accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
                fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
            )
        }
        activity.setContentView(vv)
        vv.setDashboard(LovelaceParser.parseDashboard(JSONObject(dashboard)), null)
        return vv
    }

    /** The slices waiting for the next frame ([VitroView.afterNextFrame]), oldest first. */
    private val nextFrame = ArrayDeque<Runnable>()

    /** One frame drawn: the slice waiting for it runs, and whatever else the main thread has queued. */
    private fun frame() {
        nextFrame.removeFirstOrNull()?.run()
        ShadowLooper.idleMainLooper()
    }

    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    /** The masonry columns of the view on screen. */
    private fun columns(vv: VitroView): MasonryColumnsLayout = all(vv.currentScroll()!!).filterIsInstance<MasonryColumnsLayout>().single()

    /** The cards attached to [row]'s columns, by column. */
    private fun attached(row: MasonryColumnsLayout): List<Int> = (0 until row.childCount).map { (row.getChildAt(it) as ViewGroup).childCount }

    /** Every view under the masonry columns: its depth, class, place and size, and whether it shows. */
    private fun layoutDump(vv: VitroView): List<String> {
        vv.measure(View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        vv.layout(0, 0, 1440, 720)
        val out = ArrayList<String>()
        fun walk(v: View, depth: Int) {
            val text = (v as? android.widget.TextView)?.text?.toString().orEmpty()
            out += "$depth ${v.javaClass.simpleName} ${v.left},${v.top} ${v.width}x${v.height} ${v.visibility} $text"
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
        }
        walk(columns(vv), 0)
        return out
    }

    @Test
    fun theFirstSliceAttachesTheFirstCardsAndLeavesTheRestPending() {
        val metrics = ArrayList<VitroMetrics>()
        val vv = vitroView(dashboard("heavy" to 30), sliced = true, metrics = metrics)
        val row = columns(vv)
        // HA's masonry view on a 1440 px screen: four columns, on screen before the cards.
        assertEquals(4, row.childCount)
        assertEquals("the first slice: four cards, in config order", 4, attached(row).sum())
        assertTrue(vv.buildInProgress)
        assertTrue("the view's tree is on screen (Clarity's launch screen is over)", vv.currentViewOnScreen)
        assertFalse("not built yet: the page isn't ready until its last card", vv.currentViewBuilt)
        assertTrue("no metrics until the view is built", metrics.isEmpty())
        // The first cards in config order, each in the column HA's placement gives it: the first column takes cards
        // until its size reaches 5.
        val first = (0 until row.childCount).flatMap { c -> all(row.getChildAt(c)).filterIsInstance<android.widget.TextView>().map { it.text.toString() } }
        assertTrue(first.toString(), first.any { it.startsWith("heavy 0") })
        assertFalse(first.toString(), first.any { it.startsWith("heavy 4") })

        frame()
        assertEquals("one frame, one more slice", 8, attached(row).sum())
        repeat(10) { frame() }
        assertFalse(vv.buildInProgress)
        assertEquals(30, attached(row).sum())
        assertTrue(vv.currentViewBuilt)
        assertEquals("one report, once the last card is in", 1, metrics.size)
        assertFalse(metrics.single().cacheHit)
    }

    @Test
    fun theFinishedViewIsTheOneABuildInOneGoMakes() {
        val config = dashboard("heavy" to 40)
        val once = vitroView(config, sliced = false)
        assertFalse("Robolectric's clock stands still: one slice", once.buildInProgress)
        val oneShot = layoutDump(once)
        assertTrue("every card and its insides, laid out", oneShot.size > 80)
        assertTrue("cards stacked down the columns", oneShot.map { it.split(' ')[2] }.distinct().size > 10)

        val sliced = vitroView(config, sliced = true)
        assertTrue(sliced.buildInProgress)
        repeat(20) { frame() }
        assertFalse(sliced.buildInProgress)
        assertEquals(oneShot, layoutDump(sliced))
        assertEquals(40, attached(columns(sliced)).sum())
    }

    @Test
    fun aViewSwitchStopsTheBuildAndAddsNoCardToTheViewLeft() {
        val metrics = ArrayList<VitroMetrics>()
        val vv = vitroView(dashboard("heavy" to 30, "light" to 2), sliced = true, metrics = metrics)
        val heavyRow = columns(vv)
        val heavyTree = vv.currentScroll()!!
        assertEquals(4, attached(heavyRow).sum())

        assertTrue(vv.navigateToView("light"))
        assertFalse("the light view is built in one slice, and the heavy build stopped", vv.buildInProgress)
        assertTrue("nothing waits for a frame", nextFrame.isEmpty())
        repeat(10) { frame() }
        assertEquals("no card added to the view left", 4, attached(heavyRow).sum())
        assertNull("its half-built tree is gone", heavyTree.parent)
        assertEquals(listOf("LIGHT"), metrics.map { it.viewTitle })

        // Back to it: built again from the start, all of it.
        assertTrue(vv.navigateToView("heavy"))
        assertTrue(vv.buildInProgress)
        repeat(10) { frame() }
        assertFalse(vv.buildInProgress)
        assertEquals(30, attached(columns(vv)).sum())
        assertEquals(listOf("LIGHT", "HEAVY"), metrics.map { it.viewTitle })
    }

    @Test
    fun aRebuildOfTheSameViewStartsAgainAndTheTabShownAgainKeepsBuilding() {
        val metrics = ArrayList<VitroMetrics>()
        val vv = vitroView(dashboard("heavy" to 30), sliced = true, metrics = metrics)
        frame()
        assertTrue("still building after two slices", vv.buildInProgress)
        // The same tab again, not a rebuild: the build goes on.
        assertTrue(vv.navigateToView("heavy"))
        assertTrue("the same tab again keeps building", vv.buildInProgress)
        assertTrue("nothing reported for a view still adding its cards", metrics.isEmpty())
        // A new config (a lovelace_updated): the build starts over, and only the new one finishes.
        vv.setDashboard(LovelaceParser.parseDashboard(JSONObject(dashboard("heavy" to 12))), null)
        repeat(10) { frame() }
        assertFalse("the new build finished", vv.buildInProgress)
        assertEquals(12, attached(columns(vv)).sum())
        assertEquals(1, metrics.size)
    }

    @Test
    fun aPageThatGoesStopsTheBuild() {
        val vv = vitroView(dashboard("heavy" to 30), sliced = true)
        val row = columns(vv)
        vv.closeIslands()
        assertFalse(vv.buildInProgress)
        assertTrue("nothing waits for a frame", nextFrame.isEmpty())
        repeat(10) { frame() }
        assertEquals(4, attached(row).sum())
    }

    // #947's rules hold for cards a later slice adds: a card holding an island (a browser's SurfaceView, blank under a
    // layer) never gets a layer, and an island attaches into a tree already on screen, where it lifts any layer above it.
    @Test
    fun cardsALaterSliceAddsKeepTheIslandLayerRules() {
        val cards = cards("heavy", 10).apply {
            put(JSONObject("""{"type": "iframe", "url": "https://example.com/", "aspect_ratio": "60%"}"""))
            put(JSONObject("""{"type": "vertical-stack", "cards": [{"type": "iframe", "url": "https://example.com/", "aspect_ratio": "60%"}]}"""))
            put(JSONObject().put("type", "markdown").put("content", "Last"))
        }
        val config = JSONObject().put("views", JSONArray().put(JSONObject().put("path", "heavy").put("cards", cards))).toString()
        val vv = vitroView(config, sliced = true, layers = VitroView.CardLayers.ALL)
        assertTrue("no island in the first slice", all(vv).filterIsInstance<IframeCardView>().isEmpty())
        repeat(5) { frame() }
        assertFalse(vv.buildInProgress)
        val islands = all(vv).filterIsInstance<IframeCardView>()
        assertEquals(2, islands.size)
        for (island in islands) {
            assertTrue("added to a tree on screen", island.isAttachedToWindow)
            generateSequence(island as View) { it.parent as? View }.takeWhile { it !== vv }.forEach {
                assertEquals("no layer on ${it.javaClass.simpleName} around an island", View.LAYER_TYPE_NONE, it.layerType)
            }
        }
        val last = all(vv).filterIsInstance<MarkdownCardView>()
            .single { card -> all(card).filterIsInstance<android.widget.TextView>().any { it.text.toString() == "Last" } }
        assertTrue(
            "the last slice's own card keeps its layer",
            generateSequence(last as View) { it.parent as? View }.takeWhile { it !== vv }.any { it.layerType == View.LAYER_TYPE_HARDWARE },
        )
    }
}
