/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
 * A masonry view builds its first screen in one pass, so its first frame shows that screen whole, then adds the cards
 * below it in chunks that don't wait for frames ([VitroView.postSlice]); the finished view is the one a build in one go
 * makes. Chunks are counted on a clock the test moves ([VitroView.sliceClock]): 5 ms per look.
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

    /** The chunks waiting for the main thread ([VitroView.postSlice]), oldest first. */
    private val queued = ArrayDeque<Runnable>()

    /** For each chunk posted, whether it waits for the next frame. */
    private val firstAfterFrame = ArrayList<Boolean>()

    /** A view of [dashboard]; [sliced]: on a clock that moves 5 ms per look, else on Robolectric's (one pass). */
    private fun vitroView(
        dashboard: String,
        sliced: Boolean,
        metrics: MutableList<VitroMetrics> = ArrayList(),
        layers: VitroView.CardLayers? = null,
        store: EntityStore = EntityStore(),
        islands: Pair<dev.glassworks.engine.vitro.island.VitroIslandServer, (String) -> dev.glassworks.engine.Page?>? = null,
        realScheduler: Boolean = false,
        beforeLoad: (VitroView) -> Unit = {},
    ): VitroView {
        val vv = VitroView(activity, store, 0xFF000000.toInt())
        layers?.let { vv.cardLayers = it }
        if (sliced) {
            var t = 0L
            vv.sliceClock = { t += 5; t }
            if (!realScheduler) vv.postSlice = { task, afterFrame ->
                firstAfterFrame += afterFrame
                queued += task
                val cancel = { queued.remove(task); Unit }
                cancel
            }
        }
        vv.onMetrics = { metrics += it }
        vv.contextFactory = { theme ->
            VitroCardContext(
                context = activity, haBase = FakeHaServer.BASE, store = store, theme = theme, resources = emptyList(),
                accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
                fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
                islandServer = islands?.first, openIslandPage = islands?.second,
            )
        }
        activity.setContentView(vv)
        beforeLoad(vv)
        vv.setDashboard(LovelaceParser.parseDashboard(JSONObject(dashboard)), null)
        return vv
    }

    /** The next chunk runs, and whatever else the main thread has queued. */
    private fun chunk() {
        queued.removeFirstOrNull()?.run()
        ShadowLooper.idleMainLooper()
    }

    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    /** The masonry columns of the view on screen. */
    private fun columns(vv: VitroView): MasonryColumnsLayout = all(vv.currentScroll()!!).filterIsInstance<MasonryColumnsLayout>().single()

    /** An empty slot holding a card's place in its column until its chunk builds it. */
    private fun isSlot(v: View) = v.javaClass == View::class.java && v.visibility == View.GONE

    /** The card views in [row]'s columns (not the slots still waiting for theirs). */
    private fun cardViews(row: MasonryColumnsLayout): List<View> =
        columnsOf(row).flatMap { col -> (0 until col.childCount).map { col.getChildAt(it) } }.filterNot(::isSlot)

    /** [row]'s columns, not the stage where a build's later cards wait. */
    private fun columnsOf(row: MasonryColumnsLayout): List<ViewGroup> =
        (0 until row.childCount).map { row.getChildAt(it) }.filterNot { it is dev.glassworks.engine.vitro.ui.CardStage }.map { it as ViewGroup }

    /** The cards attached to [row]'s columns, by column. */
    private fun attached(row: MasonryColumnsLayout): List<Int> = columnsOf(row).map { col ->
        (0 until col.childCount).count { !isSlot(col.getChildAt(it)) }
    }

    /** The name a test card shows (`heavy 12`). */
    /** The cards made and waiting in the view's stage, to be placed together. */
    private fun staged(vv: VitroView): Int = all(vv).filterIsInstance<dev.glassworks.engine.vitro.ui.CardStage>().sumOf { it.childCount }

    private fun nameOf(card: View): String =
        all(card).filterIsInstance<TextView>().firstNotNullOf { Regex("""\w+ \d+""").find(it.text)?.value }

    private fun layOut(vv: VitroView) {
        vv.measure(View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        vv.layout(0, 0, 1440, 720)
    }

    /** Every view under the masonry columns: its depth, class, place and size, and whether it shows. */
    private fun layoutDump(vv: VitroView): List<String> {
        layOut(vv)
        val out = ArrayList<String>()
        fun walk(v: View, depth: Int) {
            val text = (v as? TextView)?.text?.toString().orEmpty()
            out += "$depth ${v.javaClass.simpleName} ${v.left},${v.top} ${v.width}x${v.height} ${v.visibility} $text"
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
        }
        walk(columns(vv), 0)
        return out
    }

    @Test
    fun theFirstPassBuildsTheWholeFirstScreenAndLeavesTheRestPending() {
        val config = dashboard("heavy" to 80)
        // The cards a build in one go puts on the first screen (their top above the panel's 720 px).
        val once = vitroView(config, sliced = false)
        layOut(once)
        val onFirstScreen = cardViews(columns(once)).filter { (it.parent as View).top + it.top < 720 }.map(::nameOf).toSet()
        assertTrue(onFirstScreen.size in 10..60)

        val metrics = ArrayList<VitroMetrics>()
        val vv = vitroView(config, sliced = true, metrics = metrics)
        val row = columns(vv)
        val first = cardViews(row).map(::nameOf).toSet()
        assertTrue("every card of the first screen, at once: missing ${onFirstScreen - first}", first.containsAll(onFirstScreen))
        assertTrue("the rest waits: ${first.size} of 80", first.size < 80)
        assertTrue("every column has its first card, so none changes width later", attached(row).all { it > 0 })
        assertTrue(vv.buildInProgress)
        assertTrue("the view's tree is on screen (Clarity's launch screen is over)", vv.currentViewOnScreen)
        assertFalse("not built yet: the page isn't ready until its last card", vv.currentViewBuilt)
        assertTrue("no metrics until the view is built", metrics.isEmpty())
        assertEquals("one chunk waits", 1, queued.size)
        assertEquals("the first chunk waits for the first screen to be drawn (twice)", listOf(true), firstAfterFrame)

        // A chunk: up to 24 ms of cards, at 5 ms a look, then the next one queued. Its cards wait in the stage: no
        // column changes, so nothing on the dashboard is laid out again for them.
        val before = attached(row).sum()
        val stagedBefore = staged(vv)
        chunk()
        assertEquals("the columns are as they were", before, attached(row).sum())
        val added = staged(vv) - stagedBefore
        assertTrue("a chunk makes several cards: $added", added in 2..6)
        assertEquals("the chunks after it don't wait for frames", listOf(true, false), firstAfterFrame)
        while (queued.size > 0 && vv.buildInProgress) {
            val chunked = staged(vv)
            chunk()
            if (vv.buildInProgress) assertEquals("still in the stage", before, attached(row).sum())
            if (vv.buildInProgress) assertTrue(staged(vv) > chunked)
        }
        assertFalse(vv.buildInProgress)
        assertEquals("all of them placed in one pass with the last chunk", 80, attached(row).sum())
        assertTrue("the stage is gone", all(vv).none { it is dev.glassworks.engine.vitro.ui.CardStage })
        assertTrue(vv.currentViewBuilt)
        assertEquals("one report, once the last card is in", 1, metrics.size)
        assertFalse(metrics.single().cacheHit)
    }

    @Test
    fun theFinishedViewIsTheOneABuildInOneGoMakes() {
        val config = dashboard("heavy" to 80)
        val once = vitroView(config, sliced = false)
        assertFalse("Robolectric's clock stands still: one pass", once.buildInProgress)
        val oneShot = layoutDump(once)
        assertTrue("every card and its insides, laid out", oneShot.size > 160)
        assertTrue("cards stacked down the columns", oneShot.map { it.split(' ')[2] }.distinct().size > 10)

        val sliced = vitroView(config, sliced = true)
        assertTrue(sliced.buildInProgress)
        repeat(30) { chunk() }
        assertFalse(sliced.buildInProgress)
        assertEquals(oneShot, layoutDump(sliced))
        assertEquals(80, attached(columns(sliced)).sum())
    }

    @Test
    fun aViewSwitchStopsTheBuildAndAddsNoCardToTheViewLeft() {
        val metrics = ArrayList<VitroMetrics>()
        val vv = vitroView(dashboard("heavy" to 80, "light" to 2), sliced = true, metrics = metrics)
        val heavyRow = columns(vv)
        val heavyTree = vv.currentScroll()!!
        val firstPass = attached(heavyRow).sum()
        assertTrue(firstPass < 80)

        assertTrue(vv.navigateToView("light"))
        assertFalse("the light view is built in one pass, and the heavy build stopped", vv.buildInProgress)
        assertTrue("no chunk waits", queued.isEmpty())
        repeat(10) { chunk() }
        assertEquals("no card added to the view left", firstPass, attached(heavyRow).sum())
        assertNull("its half-built tree is gone", heavyTree.parent)
        assertEquals(listOf("LIGHT"), metrics.map { it.viewTitle })

        // Back to it: built again from the start, all of it.
        assertTrue(vv.navigateToView("heavy"))
        assertTrue(vv.buildInProgress)
        repeat(30) { chunk() }
        assertFalse(vv.buildInProgress)
        assertEquals(80, attached(columns(vv)).sum())
        assertEquals(listOf("LIGHT", "HEAVY"), metrics.map { it.viewTitle })
    }

    @Test
    fun aRebuildOfTheSameViewStartsAgainAndTheTabShownAgainKeepsBuilding() {
        val metrics = ArrayList<VitroMetrics>()
        val vv = vitroView(dashboard("heavy" to 80), sliced = true, metrics = metrics)
        chunk()
        assertTrue("still building after a chunk", vv.buildInProgress)
        // The same tab again, not a rebuild: the build goes on.
        assertTrue(vv.navigateToView("heavy"))
        assertTrue("the same tab again keeps building", vv.buildInProgress)
        assertTrue("nothing reported for a view still adding its cards", metrics.isEmpty())
        // A new config (a lovelace_updated): the build starts over, and only the new one finishes.
        vv.setDashboard(LovelaceParser.parseDashboard(JSONObject(dashboard("heavy" to 60))), null)
        repeat(30) { chunk() }
        assertFalse("the new build finished", vv.buildInProgress)
        assertEquals(60, attached(columns(vv)).sum())
        assertEquals(1, metrics.size)
    }

    @Test
    fun aPageThatGoesStopsTheBuild() {
        val vv = vitroView(dashboard("heavy" to 80), sliced = true)
        val row = columns(vv)
        val firstPass = attached(row).sum()
        vv.closeIslands()
        assertFalse(vv.buildInProgress)
        assertTrue("no chunk waits", queued.isEmpty())
        repeat(10) { chunk() }
        assertEquals(firstPass, attached(row).sum())
    }

    // #947's rules hold for cards a later chunk adds: a card holding an island (a browser's SurfaceView, blank under a
    // layer) never gets a layer, and an island attaches into a tree already on screen, where it lifts any layer above it.
    @Test
    fun cardsALaterChunkAddsKeepTheIslandLayerRules() {
        val cards = cards("heavy", 60).apply {
            put(JSONObject("""{"type": "iframe", "url": "https://example.com/", "aspect_ratio": "60%"}"""))
            put(JSONObject("""{"type": "vertical-stack", "cards": [{"type": "iframe", "url": "https://example.com/", "aspect_ratio": "60%"}]}"""))
            put(JSONObject().put("type", "markdown").put("content", "Last"))
        }
        val config = JSONObject().put("views", JSONArray().put(JSONObject().put("path", "heavy").put("cards", cards))).toString()
        val vv = vitroView(config, sliced = true, layers = VitroView.CardLayers.ALL)
        assertTrue("no island in the first pass: they're below the first screen", all(vv).filterIsInstance<IframeCardView>().isEmpty())
        repeat(20) { chunk() }
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
            .single { card -> all(card).filterIsInstance<TextView>().any { it.text.toString() == "Last" } }
        assertTrue(
            "the last chunk's own card keeps its layer",
            generateSequence(last as View) { it.parent as? View }.takeWhile { it !== vv }.any { it.layerType == View.LAYER_TYPE_HARDWARE },
        )
    }

    // #960's budget holds across chunks: the cards a later chunk adds claim their layers from the view's one budget, as
    // they're laid out, after the first screen's (laid out first), and the view's layers never pass the limit.
    @Test
    fun cardsALaterChunkAddsCountAgainstTheViewsLayerBudget() {
        val big = JSONObject().put("type", "markdown").put("content", "# Big\n\nA\n\nB\n\nC\n\nD\n\nE\n\nF\n\nG\n\nH\n\nI")
        val config = JSONObject().put("views", JSONArray().put(JSONObject().put("path", "heavy").put("cards", JSONArray().apply { repeat(80) { put(big) } }))).toString()
        for (mode in VitroView.CardLayers.values()) {
            val vv = vitroView(config, sliced = true, layers = mode)
            layOut(vv)
            val firstScreen = cardViews(columns(vv)).toSet()
            assertTrue("$mode: a first screen, then chunks", vv.buildInProgress && firstScreen.size < 80)
            while (vv.buildInProgress) {
                chunk()
                layOut(vv)
            }
            val cards = cardViews(columns(vv))
            assertEquals(80, cards.size)
            assertTrue("$mode: every card is big", cards.all { VitroView.isBigCard(it.width, it.height, 1440, 720) })
            val layered = cards.filter { it.layerType == View.LAYER_TYPE_HARDWARE }
            val limit = if (mode == VitroView.CardLayers.BIG) VitroView.BIG_LAYER_BUDGET_BYTES else 1440L * 720 * VitroView.HWUI_CACHE_BYTES_PER_PIXEL / 2
            val used = layered.sumOf { VitroView.layerBytes(it.width, it.height) }
            assertTrue("$mode: $used bytes of layers within $limit", used <= limit)
            assertTrue("$mode: not every card has one", layered.size < cards.size)
            assertTrue("$mode: the next card wouldn't have fit", used + VitroView.layerBytes(cards[0].width, cards[0].height) > limit)
            // Laid out first, the first screen's cards claimed first: every one of them that the budget allows has its
            // layer before any card a chunk added.
            val firstLayered = firstScreen.count { it.layerType == View.LAYER_TYPE_HARDWARE }
            val firstFit = (limit / VitroView.layerBytes(cards[0].width, cards[0].height)).toInt().coerceAtMost(firstScreen.size)
            assertEquals("$mode: the first screen's cards hold the first layers", firstFit, firstLayered)
        }
    }

    // A card below the first screen is never built apart from its view (Greptile on #950): a live camera there is made
    // and attached in the first pass, in its place, so a build dropped before its chunks takes everything that card
    // opened with the view. Here a stack holding the camera and an iframe island, dropped by a view switch: the
    // island's page closes and nothing follows the camera's entity any more, as for any card of a dropped view.
    @Test
    fun aBuildDroppedBeforeItsChunksReleasesWhatItsCardsOpened() {
        val camera = JSONObject().put("type", "picture-entity").put("entity", "camera.porch").put("camera_view", "live")
        val iframe = JSONObject().put("type", "iframe").put("url", "https://example.com/").put("aspect_ratio", "60%")
        val stack = JSONObject().put("type", "vertical-stack").put("cards", JSONArray().put(camera).put(iframe))
        val heavy = cards("heavy", 60).put(stack).apply { for (i in 0 until 10) put(JSONObject().put("type", "markdown").put("content", "after $i")) }
        val config = JSONObject().put("views", JSONArray()
            .put(JSONObject().put("path", "heavy").put("title", "HEAVY").put("cards", heavy))
            .put(JSONObject().put("path", "light").put("title", "LIGHT").put("cards", cards("light", 2)))).toString()
        val store = EntityStore()
        val server = dev.glassworks.engine.vitro.island.VitroIslandServer(session = { FakeHaServer.BASE to FakeHaServer.TOKEN })
        val pages = ArrayList<RecordingPage>()
        try {
            val vv = vitroView(config, sliced = true, store = store, islands = server to { _ -> RecordingPage(activity).also { pages += it } })
            assertTrue("the rest of the view waits for its chunks", vv.buildInProgress && queued.isNotEmpty())
            assertEquals("the iframe's page is open, with the view's first pass", 1, pages.size)
            assertTrue("the camera follows its entity", store.listenerCount("camera.porch") > 0)

            assertTrue(vv.navigateToView("light"))
            ShadowLooper.idleMainLooper()
            assertFalse(vv.buildInProgress)
            assertTrue("no island page is left open", pages.all { it.closed })
            assertEquals("nothing follows the camera any more", 0, store.listenerCount("camera.porch"))
        } finally {
            server.stop()
        }
    }

    // A card hidden now takes no room on the first screen (Greptile on #950): conditionals whose conditions don't hold,
    // at the top of every column, mustn't push the visible cards under them out of the first pass. Their columns stay
    // where HA's placement puts them.
    @Test
    fun cardsHiddenNowDontPushTheVisibleOnesOutOfTheFirstScreen() {
        val rows = JSONArray().apply { for (i in 0 until 10) put("sensor.row_$i") }
        val hidden = JSONObject().put("type", "conditional")
            .put("conditions", JSONArray().put(JSONObject().put("condition", "state").put("entity", "switch.away").put("state", "on")))
            .put("card", JSONObject().put("type", "entities").put("entities", rows))
        val cards = JSONArray().apply {
            repeat(20) { put(hidden) }
            for (i in 0 until 40) put(JSONObject().put("type", "markdown").put("content", "visible $i" + "\n\nline".repeat(i % 3)))
        }
        val config = JSONObject().put("views", JSONArray().put(JSONObject().put("path", "home").put("cards", cards))).toString()
        fun store() = EntityStore().apply { putLocal(dev.glassworks.engine.vitro.data.EntityState("switch.away", "off", emptyMap())) }

        // The visible cards a build in one go puts on the first screen.
        val once = vitroView(config, sliced = false, store = store())
        layOut(once)
        val onFirstScreen = cardViews(columns(once)).filter { it.visibility == View.VISIBLE && (it.parent as View).top + it.top < 720 }
            .map(::nameOf).toSet()
        assertTrue("visible cards on the first screen: $onFirstScreen", onFirstScreen.size >= 8)

        val vv = vitroView(config, sliced = true, store = store())
        assertTrue("the rest waits for its chunks", vv.buildInProgress)
        val first = cardViews(columns(vv)).filter { it.visibility == View.VISIBLE }.map(::nameOf).toSet()
        assertTrue("every visible card of the first screen, at once: missing ${onFirstScreen - first}", first.containsAll(onFirstScreen))
        repeat(30) { chunk() }
        assertFalse(vv.buildInProgress)
        assertEquals("the columns are HA's, hidden cards and all", layoutDump(once), layoutDump(vv))
    }

    // Scrolled while the rest is being made: what's made so far goes to its place at once, and each chunk after that
    // places its own cards, so the user never scrolls into an empty column that has cards waiting.
    @Test
    fun aScrollPlacesWhatsMadeAtOnce() {
        val vv = vitroView(dashboard("heavy" to 80), sliced = true)
        val row = columns(vv)
        chunk()
        val waiting = staged(vv)
        assertTrue("cards wait in the stage: $waiting", waiting > 0)
        val before = attached(row).sum()
        layOut(vv)
        // A small scroll, short of where the columns' placed cards end: nothing moves.
        vv.currentScroll()!!.scrollTo(0, 20)
        assertEquals(waiting, staged(vv))
        // Down to where the shortest column ends.
        vv.currentScroll()!!.scrollTo(0, 2000)
        assertEquals("nothing waits any more", 0, staged(vv))
        assertEquals("the cards made so far are in their columns", before + waiting, attached(row).sum())
        chunk()
        assertEquals("a chunk after the scroll places its cards", 0, staged(vv))
        assertTrue(attached(row).sum() > before + waiting)
        repeat(30) { chunk() }
        assertFalse(vv.buildInProgress)
        assertEquals(80, attached(row).sum())
    }

    // A card a chunk made waits in the stage, inside the view's tree: a build dropped then (a view switch) releases its
    // island's page and its subscriptions with the rest of the view.
    @Test
    fun aDroppedBuildReleasesWhatItsStagedCardsOpened() {
        val iframe = JSONObject().put("type", "iframe").put("url", "https://example.com/").put("aspect_ratio", "60%")
        val tile = JSONObject().put("type", "tile").put("entity", "light.porch")
        val heavy = cards("heavy", 60).put(iframe).put(tile).apply { for (i in 0 until 10) put(JSONObject().put("type", "markdown").put("content", "after $i")) }
        val config = JSONObject().put("views", JSONArray()
            .put(JSONObject().put("path", "heavy").put("title", "HEAVY").put("cards", heavy))
            .put(JSONObject().put("path", "light").put("title", "LIGHT").put("cards", cards("light", 2)))).toString()
        val store = EntityStore()
        val server = dev.glassworks.engine.vitro.island.VitroIslandServer(session = { FakeHaServer.BASE to FakeHaServer.TOKEN })
        val pages = ArrayList<RecordingPage>()
        try {
            val vv = vitroView(config, sliced = true, store = store, islands = server to { _ -> RecordingPage(activity).also { pages += it } })
            while (vv.buildInProgress && pages.isEmpty() && queued.isNotEmpty()) chunk()
            assertEquals("the iframe was made in a chunk, and waits in the stage", 1, pages.size)
            assertTrue(vv.buildInProgress)
            assertTrue(all(vv).filterIsInstance<IframeCardView>().single().parent is dev.glassworks.engine.vitro.ui.CardStage)
            while (vv.buildInProgress && store.listenerCount("light.porch") == 0 && queued.isNotEmpty()) chunk()
            assertTrue("the tile, staged too, follows its entity", vv.buildInProgress && store.listenerCount("light.porch") > 0)

            assertTrue(vv.navigateToView("light"))
            ShadowLooper.idleMainLooper()
            assertTrue("no island page is left open", pages.all { it.closed })
            assertEquals("nothing follows the tile's entity any more", 0, store.listenerCount("light.porch"))
        } finally {
            server.stop()
        }
    }

    // One chunk a frame, each after its frame has drawn: a chunk is an ordinary message, which a pending frame's
    // barrier holds back, posted from a frame callback. Sent asynchronously, chunks passed the barrier and starved the
    // frames (on the S1, only 39 of 106 chunks back to back had a frame between them).
    @Test
    fun everyChunkWaitsForAFrame() {
        // The real scheduler, watched: each chunk notes that it ran, and asks to hear of the next frame; the next chunk
        // must find that a frame came between.
        var framed = true
        var chunks = 0
        var withoutFrame = 0
        val vv = vitroView(dashboard("heavy" to 80), sliced = true, realScheduler = true) { v ->
            val real = v.postSlice
            v.postSlice = { task, afterFrames ->
                real(Runnable {
                    chunks++
                    if (!framed) withoutFrame++
                    framed = false
                    android.view.Choreographer.getInstance().postFrameCallback { framed = true }
                    task.run()
                }, afterFrames)
            }
        }
        assertFalse("a chunk is an ordinary message", vv.chunkMessage(Runnable {}).isAsynchronous)
        assertTrue(vv.buildInProgress)
        var guard = 0
        while (vv.buildInProgress && guard++ < 500) ShadowLooper.idleMainLooper(16, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertFalse(vv.buildInProgress)
        assertTrue("several chunks: $chunks", chunks >= 4)
        assertEquals("every chunk came after a frame", 0, withoutFrame)
        assertEquals(80, attached(columns(vv)).sum())
    }

    // A card that takes long to make starts a chunk: after other cards it would run their budget over.
    @Test
    fun aHeavyCardStartsAChunk() {
        val rows = JSONArray().apply { for (i in 0 until 12) put("sensor.row_$i") }
        val heavy = cards("heavy", 70).apply {
            for (i in 0 until 6) put(JSONObject().put("type", "entities").put("title", "Rows $i").put("entities", rows))
            for (i in 0 until 6) put(JSONObject().put("type", "markdown").put("content", "after $i"))
        }
        val config = JSONObject().put("views", JSONArray().put(JSONObject().put("path", "heavy").put("cards", heavy))).toString()
        val vv = vitroView(config, sliced = true)
        fun stage() = all(vv).filterIsInstance<dev.glassworks.engine.vitro.ui.CardStage>().single()
        var checked = 0
        while (vv.buildInProgress && queued.isNotEmpty()) {
            val from = stage().childCount
            chunk()
            if (!vv.buildInProgress) break
            val made = (from until stage().childCount).map { stage().getChildAt(it) }
            val heavyAt = made.indices.filter { made[it] is dev.glassworks.engine.vitro.cards.EntitiesCardView }
            // A big entities card is the first card of its chunk, and the only big one in it.
            assertTrue("chunk ${made.map { it.javaClass.simpleName }}", heavyAt.isEmpty() || heavyAt == listOf(0))
            checked += heavyAt.size
        }
        assertTrue("several big entities cards were made in chunks: $checked", checked >= 3)
        assertFalse(vv.buildInProgress)
    }

    // A card a chunk made is shown in the stage as below the fold of a view built in one go, so what it decides from
    // being shown holds when it moves to its column (Greptile on #950: a GONE stage told a clock it was hidden, and the
    // move, which dispatches no visibility change, left it stopped).
    @Test
    fun aDeferredClockKeepsTickingOnceItsInItsColumn() {
        val heavy = cards("heavy", 60).put(JSONObject().put("type", "clock").put("clock_size", "small"))
            .apply { for (i in 0 until 10) put(JSONObject().put("type", "markdown").put("content", "after $i")) }
        val config = JSONObject().put("views", JSONArray().put(JSONObject().put("path", "heavy").put("cards", heavy))).toString()
        val vv = vitroView(config, sliced = true)
        assertTrue("the clock is below the first screen", all(vv).none { it is dev.glassworks.engine.vitro.cards.ClockCardView })
        while (vv.buildInProgress && queued.isNotEmpty()) chunk()
        assertFalse(vv.buildInProgress)
        val clock = all(vv).filterIsInstance<dev.glassworks.engine.vitro.cards.ClockCardView>().single()
        assertTrue("in its column, shown", clock.isShown && clock.parent?.parent is MasonryColumnsLayout)
        // A time it would never show now, then its next tick (5 s apart without seconds).
        clock.renderAt(java.util.Date(978_356_220_000L))
        val stale = clock.renderedTime
        ShadowLooper.idleMainLooper(6, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue("it ticked: still $stale", clock.renderedTime != stale)
    }

    // A camera card of HA's default `auto` view (snapshots) below the first screen: placed, it runs and is shown, off
    // screen there, and on screen once scrolled to, as a card below the fold of a view built in one go.
    @Test
    fun aDeferredCameraCardFollowsBeingShownAndOnScreen() {
        val camera = JSONObject().put("type", "picture-entity").put("entity", "camera.porch")
        val heavy = cards("heavy", 60).put(camera).apply { for (i in 0 until 10) put(JSONObject().put("type", "markdown").put("content", "after $i")) }
        val config = JSONObject().put("views", JSONArray().put(JSONObject().put("path", "heavy").put("cards", heavy))).toString()
        // The panel's window shows, as on the panel: a camera in a hidden window starts nothing.
        val vv = vitroView(config, sliced = true) { showWindow(activity.window.decorView) }
        assertTrue("the camera is below the first screen", all(vv).none { it is dev.glassworks.engine.vitro.cards.PictureCardView })
        while (vv.buildInProgress && queued.isNotEmpty()) chunk()
        assertFalse(vv.buildInProgress)
        ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        val card = all(vv).filterIsInstance<dev.glassworks.engine.vitro.cards.PictureCardView>().single()
        val feed = card.feedForTests!!
        val (running, shown, _) = feed.stateForTests
        assertTrue("its snapshots run", running)
        assertTrue("it plays as shown", shown)
        assertFalse("below the fold, off screen", feed.stateForTests.third)
        // Scrolled to it: on screen.
        val scroll = vv.currentScroll()!!
        val r = android.graphics.Rect(0, 0, card.width, card.height)
        scroll.offsetDescendantRectToMyCoords(card, r)
        scroll.scrollTo(0, r.top)
        ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        // What a frame after the scroll dispatches, which Robolectric's window doesn't: the card looks again.
        vv.viewTreeObserver.dispatchOnGlobalLayout()
        assertTrue("scrolled to, on screen", feed.stateForTests.third)
    }
}
