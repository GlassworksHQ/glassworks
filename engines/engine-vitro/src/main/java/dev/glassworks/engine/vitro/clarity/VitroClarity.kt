/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.clarity

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ScrollView
import dev.glassworks.engine.vitro.cards.MoreInfoSheet
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.ui.VitroView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Clarity's harness for Vitro: the native twin of `tools/perf/page/harness.js` (docs/perf.md, "Vitro").
 *
 * Clarity measures the web engine from inside Home Assistant's frontend. Vitro runs no page, so this measures it from
 * inside Vitro, with the same protocol: it takes the runner's commands from the fake Home Assistant
 * (`/clarity/next`), opens each reference view as a view tab does, and writes the same `GWPERF` lines, with the
 * same fields and the same rules (`ClarityStats`, stats.js's). perfrun.py, report.py and the grade read them as
 * they read the web harness's.
 *
 * What stands in for the page's tools:
 * - animation frames: [Choreographer] frame callbacks on the main thread, only while a view is measured;
 * - the main thread free: a chain of posted tasks, each run within 50 ms, for `freeWindowMs`;
 * - a change drawn: the store's message read on the main thread, then the card's own render, then the next frame;
 * - Event Timing (taps): from the touch's own timestamp to the end of the first frame drawn after its handlers,
 *   rounded as Event Timing rounds;
 * - the more-info dialog's events: [MoreInfoSheet.Trace];
 * - a camera's video element: each [dev.glassworks.engine.vitro.camera.CameraStream]'s own reports, through
 *   [ClarityCameras] (its first picture, each picture drawn, its errors, its kind).
 *
 * It runs only while the kiosk's test hold shows Clarity's reference dashboard on a loopback fake Home Assistant
 * (`VitroEngine` starts it); otherwise none of this exists and every hook is a null check. The page clock is
 * CLOCK_MONOTONIC in ms after [originNs]: the lines say so (`clock`, `origin_mono_ms`), so perflib ties them to
 * logcat and SurfaceFlinger exactly.
 */
class VitroClarity internal constructor(
    /** The fake Home Assistant's origin (`http://127.0.0.1:<port>`). */
    internal val fake: String,
    private val view: VitroView,
    private val store: EntityStore,
    /** The view the kiosk landed on: the restart's (`start`), or an idle sample's. */
    private val landing: String,
    /**
     * Follows an entity no card of the view shows (the page's runtime subscription, main thread): the dialogs'. HA's
     * frontend holds every state, and a tile's tap opens an entity its card already follows.
     */
    private val follow: (String) -> Unit = {},
    /** The worker ended by itself (an error it can't go on from), on the main thread: the page lets this harness go. */
    private val onExit: (VitroClarity) -> Unit = {},
    /**
     * Whether the test hold still shows this fake's reference dashboard (main thread). Checked before each command, so a
     * hold that ends stops the harness even when no page load follows it (a panel with no saved dashboard).
     */
    private val active: () -> Boolean = { true },
    /** How it reaches the fake: HTTP on the loopback, no redirects ([ClarityHttp.Loopback]). Tests give their own. */
    private val transport: ClarityHttp = ClarityHttp.Loopback,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    private val main = Handler(Looper.getMainLooper())
    private val originNs = System.nanoTime()
    @Volatile private var stopped = false
    private var thread: Thread? = null

    /** The page config the fake gives its harness (dashboards.page_config: tiers.json and the reference home). */
    private var c = JSONObject()

    /** The view being measured: the runner's command. Before the first, the landing view (no nonce). */
    private data class Cmd(
        val nonce: String = "",
        val run: Int = 0,
        val taps: Int = 0,
        val swipes: Int = 0,
        val dialogs: Int = 0,
        val vsync: Double = 1000.0 / 60,
        val tier: String,
    )

    @Volatile private var v = Cmd(tier = landing)

    /** The store's tap: each live change read, then handed to the cards ([onState], [onDelivered]). */
    private val tap = object : EntityStore.ContextTap {
        override fun read(entityId: String, context: String?) = onState(entityId, context)
        override fun delivered(entityId: String, context: String?) = onDelivered(entityId, context)
    }

    /**
     * The test hold watched on the main thread while the harness runs: once it no longer shows this fake's reference
     * dashboard, the harness stops (interrupting whatever the worker waits on: a request, a frame, a sheet) and the
     * page lets it go. A config that arrives after that, or a view half done, does nothing more: every wait and every
     * main-thread task checks [stopped].
     */
    private val holdWatch = object : Runnable {
        override fun run() {
            if (checkHold()) main.postDelayed(this, HOLD_CHECK_MS)
        }
    }

    /** One look at the hold (main thread): false, with the harness stopped and the page told, once it has ended. */
    internal fun checkHold(): Boolean {
        if (stopped) return false
        if (active()) return true
        stop()
        onExit(this)
        return false
    }

    /** Tests: told each time the worker has queued a step for the main thread, before that step runs. */
    @Volatile internal var onPosted: (() -> Unit)? = null

    /** The worker still runs (tests). */
    internal val workerAlive: Boolean get() = thread?.isAlive == true

    fun start() {
        if (thread != null) return
        store.contextTap = tap
        ClarityCameras.current = cameras
        main.post(holdWatch)
        thread = Thread({
            try {
                script()
            } catch (_: Stopped) {
            } catch (e: Throwable) {
                if (!stopped) line(JSONObject().put("t", "error").put("message", Log.getStackTraceString(e).take(MAX_ERROR)))
            } finally {
                // Ended by itself (bad config, an error out of the command loop): nothing measures any more, so the
                // store's tap goes and the page may start a new harness on its next load.
                if (!stopped) {
                    stop()
                    main.post { onExit(this) }
                }
            }
        }, "vitro-clarity").apply {
            isDaemon = true
            start()
        }
    }

    /** The page left the reference dashboard, or went: nothing more is measured or asked. */
    fun stop() {
        if (stopped) return
        stopped = true
        if (store.contextTap === tap) store.contextTap = null
        if (ClarityCameras.current === cameras) ClarityCameras.current = null
        thread?.interrupt()
        main.removeCallbacks(holdWatch)
        main.post {
            ticking = false
            steps.clear()
            live.watch.clear()
            scrollWatch?.let { view.viewTreeObserver.removeOnScrollChangedListener(it) }
            scrollWatch = null
        }
    }

    private class Stopped : RuntimeException()

    private fun check() {
        if (stopped) throw Stopped()
    }

    // ---- the clock, the main thread and the fake Home Assistant

    private fun now(): Double = (System.nanoTime() - originNs) / 1e6
    private fun fromNanos(ns: Long): Double = (ns - originNs) / 1e6

    /** A MotionEvent's time ([SystemClock.uptimeMillis], CLOCK_MONOTONIC) on the page clock. */
    private fun fromUptime(ms: Long): Double = ms - originNs / 1e6

    private fun sleep(ms: Double) {
        check()
        if (ms <= 0) return
        try {
            Thread.sleep(ms.toLong())
        } catch (_: InterruptedException) {
            throw Stopped()
        }
        check()
    }

    private fun <T> onMain(block: () -> T): T {
        check()
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        // Checked again where it runs: a stop that came while it waited in the queue (the page leaving the reference
        // dashboard) means it never runs, so nothing opens or switches after the harness stopped.
        val task = FutureTask { if (stopped) throw Stopped() else block() }
        main.post(task)
        onPosted?.invoke()
        try {
            return task.get(MAIN_WAIT_S, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            task.cancel(false)
            throw Stopped()
        } catch (e: java.util.concurrent.TimeoutException) {
            task.cancel(false)
            throw e
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun http(path: String, timeoutMs: Int = 10_000): Pair<Int, String?> {
        check()
        return transport.get(fake.trimEnd('/') + path, timeoutMs)
    }

    private var seq = 0

    /** One page line, as the web harness logs it (`GWPERF {json}`, split past 900 bytes). */
    private fun line(obj: JSONObject) {
        val cmd = v
        val o = JSONObject().put("run", cmd.run).put("nonce", cmd.nonce)
        for (k in obj.keys()) o.put(k, obj.get(k))
        synchronized(this) {
            seq++
            for (l in ClarityStats.consoleLines("GWPERF", "${cmd.nonce.ifEmpty { "restart" }}:$seq", o.toString())) log(l)
        }
    }

    /** The fields that tie the page clock to logcat's: CLOCK_MONOTONIC, so to the millisecond (perflib.page_to_mono_ms). */
    private fun clockFields(o: JSONObject): JSONObject =
        o.put("engine", ENGINE).put("clock", "monotonic").put("origin_mono_ms", originNs / 1e6)

    // ---- frames: Choreographer callbacks on the main thread, only while something asks for them

    /** Main thread: every frame's time since [startTicks] (the view's), and the steps that look at each frame. */
    private var ticking = false
    private var frameCallbackPosted = false
    private val frames = ArrayList<Double>()
    private val steps = ArrayList<FrameProbe>()
    private val choreographer: Choreographer by lazy { Choreographer.getInstance() }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameCallbackPosted = false
            if (stopped) return
            val ts = fromNanos(frameTimeNanos)
            if (ticking) {
                // A view's frames, bounded: past MAX_FRAMES (over 8 minutes at 60 Hz, more than any view's deadline)
                // the oldest go.
                if (frames.size >= MAX_FRAMES) {
                    val drop = frames.subList(0, frames.size - MAX_FRAMES + 1)
                    droppedUntil = drop.last()
                    drop.clear()
                }
                frames += ts
                markTicks(ts)
            }
            if (steps.isNotEmpty()) {
                val it = steps.iterator()
                while (it.hasNext()) {
                    if (it.next().look(ts)) it.remove()
                }
            }
            requestFrame()
        }
    }

    /** Main thread. */
    private fun requestFrame() {
        if (!frameCallbackPosted && (ticking || steps.isNotEmpty())) {
            frameCallbackPosted = true
            choreographer.postFrameCallback(frameCallback)
        }
    }

    private fun startTicks() = onMain {
        frames.clear()
        droppedUntil = null
        ticking = true
        requestFrame()
    }

    private fun stopTicks() = onMain { ticking = false }

    private fun framesIn(from: Double, to: Double): List<Double> = onMain { frames.filter { it in from..to } }

    /** The newest frame dropped to keep [frames] bounded (main thread), or null when none was. */
    private var droppedUntil: Double? = null


    /**
     * Runs [step] on the main thread at each frame until it returns true (that frame's time) or [timeoutMs] pass
     * (null). Once this returns, [step] has stopped for good: a step running on the main thread as the wait ends
     * finishes first, and none runs after, so the caller reads what the steps wrote without racing the next frame.
     */
    private fun awaitFrames(timeoutMs: Double, step: (Double) -> Boolean): Double? {
        val done = CountDownLatch(1)
        var at: Double? = null
        val s = FrameProbe { ts ->
            if (step(ts)) {
                at = ts
                done.countDown()
                true
            } else {
                false
            }
        }
        var ok = false
        try {
            onMain {
                steps += s
                requestFrame()
            }
            try {
                done.await(timeoutMs.toLong().coerceAtLeast(1), TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                throw Stopped()
            }
        } finally {
            // Ended either way: a step that said done after the wait timed out still counts. One that never did (timed
            // out, stopped, the main thread stalled past onMain's wait) is dropped unread by the next frame, and its
            // removal is posted, never waited for.
            ok = s.end()
            if (!ok) main.post { steps.remove(s) }
        }
        check()
        return if (ok) at else null
    }

    private fun frame(): Double? = awaitFrames(FRAME_WAIT_MS) { true }

    // ---- the cards: the views Vitro built for each top-level card of a view, in config order

    private val built = HashMap<Int, java.util.TreeMap<Int, Pair<CardConfig, View>>>()

    /** VitroView: view [index] is being built again; its cards follow. Main thread. */
    internal fun buildStarted(index: Int) {
        built[index] = java.util.TreeMap()
    }

    /** VitroView: only the views in [cached] are still built; the cards of the others are let go. Main thread. */
    internal fun keepCards(cached: Set<Int>) {
        built.keys.retainAll(cached)
    }

    /** VitroView: the top-level card [cardIndex] of view [index] (its config order). Main thread. */
    internal fun cardBuilt(index: Int, cardIndex: Int, config: CardConfig, card: View) {
        built.getOrPut(index) { java.util.TreeMap() }[cardIndex] = config to card
    }

    private fun currentCards(): java.util.TreeMap<Int, Pair<CardConfig, View>> = built[view.currentViewIndex] ?: java.util.TreeMap()

    /** The page's viewport: Vitro's own view, on the screen. Main thread. */
    private fun viewport(): Rect {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
    }

    private fun rectOf(v: View): Rect {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    /** Drawn: laid out with a size and shown (harness.js `rendered`: its ha-card laid out). */
    private fun rendered(v: View?): Boolean = v != null && v.height > 0 && v.width > 0 && v.isShown

    private fun inView(v: View, vp: Rect): Boolean {
        val r = rectOf(v)
        return r.width() > 0 && r.top >= vp.top && r.left >= vp.left && r.bottom <= vp.bottom && r.right <= vp.right
    }

    private fun entitiesOf(cfg: CardConfig): List<String> {
        val out = ArrayList<String>()
        cfg.entity?.let { out += it }
        val arr = cfg.raw.optJSONArray("entities") ?: return out
        for (i in 0 until arr.length()) {
            when (val e = arr.opt(i)) {
                is String -> out += e
                is JSONObject -> e.optString("entity").takeIf { it.isNotEmpty() }?.let { out += it }
            }
        }
        return out
    }

    private fun density() = view.resources.displayMetrics.density.toDouble()

    // ---- usable: the view's first screen drawn, and the main thread free

    /** harness.js firstScreenDrawn: each frame, the first screen as usableStep reads it. */
    private fun firstScreenDrawn(tier: String): JSONObject? {
        val headCards = c.optInt("HEAD_CARDS", 10)
        var st = ClarityStats.Usable()
        var onScreenCount = 0
        val ts = awaitFrames(VIEW_DEADLINE_MS) {
            val cfg = view.dashboard
            val want = cfg?.let { d -> d.views.indexOfFirst { it.path == tier }.takeIf { it >= 0 } }
            val cards = currentCards()
            val vp = viewport()
            val shown = cards.values.map { it.second }.filter { cv ->
                val r = rectOf(cv)
                cv.height > 0 && r.top < vp.bottom && r.bottom > vp.top
            }
            onScreenCount = shown.size
            val nCfg = cfg?.views?.getOrNull(view.currentViewIndex)?.cards?.size ?: 0
            val head = (0 until minOf(nCfg, headCards)).map { rendered(cards[it]?.second) }
            st = ClarityStats.usableStep(
                ClarityStats.Look(
                    launch = cfg == null || view.statusShowing || !view.currentViewOnScreen,
                    viewIndex = if (cfg == null) null else view.currentViewIndex,
                    wantIndex = want,
                    head = head,
                    onScreen = shown.map { rendered(it) to rectOf(it).top.toDouble() / density() },
                ),
                st, headCards,
            )
            st.done
        } ?: return null
        return JSONObject().put("rendered_tick_ms", ClarityStats.round1(ts)).put("first_screen_cards", onScreenCount)
            .put("head_cards", headCards)
    }

    /**
     * harness.js freeFrom: tasks posted back to back on the main thread, each timed from its post to its run, until
     * they all ran within 50 ms for `freeWindowMs`. Returns when that stretch began, and each second's longest wait.
     */
    private fun freeFrom(): Pair<Double, JSONArray> {
        val window = c.getJSONObject("PHASES").getDouble("freeWindowMs")
        val t0 = now()
        val busy = ArrayList<Long>()
        var start: Double? = null
        val done = CountDownLatch(1)
        var result = 0.0
        // This wait's own end: past its deadline (or interrupted) no probe of it posts again, so none lands in the next
        // view's measurement.
        val over = java.util.concurrent.atomic.AtomicBoolean(false)
        lateinit var probe: (Double) -> Unit
        probe = { posted ->
            main.post {
                if (stopped || over.get()) return@post
                val ran = now()
                val k = ((posted - t0) / 1000).toInt()
                if (k < 600) {
                    while (busy.size <= k) busy += 0L
                    busy[k] = max(busy[k], (ran - posted).roundToInt().toLong())
                }
                val (s, free) = ClarityStats.freeStep(posted, ran, start, window)
                start = s
                if (free) {
                    result = s!!
                    done.countDown()
                } else {
                    probe(now())
                }
            }
        }
        probe(now())
        val ok = try {
            done.await(VIEW_DEADLINE_MS.toLong(), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            throw Stopped()
        } finally {
            over.set(true)
        }
        check()
        if (!ok) throw IllegalStateException("the main thread wasn't free within ${VIEW_DEADLINE_MS.toLong() / 1000} s")
        return result to JSONArray(onMain { busy.toList() })
    }

    /** harness.js reportUsable: the `drawn` line at once, then `refdash` once the main thread is free. */
    private fun reportUsable(nav: Double, first: Boolean, before: JSONObject?): JSONObject {
        val tier = v.tier
        val drawn = firstScreenDrawn(tier) ?: throw IllegalStateException("the $tier view's first screen wasn't drawn")
        line(clockFields(JSONObject().put("t", "drawn").put("tier", tier).put("protocol", c.getInt("PROTOCOL"))
            .put("clarity", c.getInt("CLARITY")).put("nav_ms", ClarityStats.round1(nav)).put("usable", drawn)))
        val (freeAt, busy) = freeFrom()
        val drawnAt = drawn.getDouble("rendered_tick_ms")
        // Frames dropped inside the span (a wait past MAX_FRAMES) would read as one long gap: then the blocking isn't
        // measured (null), never a number made of what was dropped.
        // The span's frames and what was dropped, in one look: a frame dropped between two looks can't void a span its
        // frames still cover.
        val (span, dropped) = onMain { frames.filter { it in drawnAt..freeAt } to droppedUntil }
        val b = ClarityStats.blocking(span, dropped, drawnAt, freeAt)
        val u = JSONObject(drawn.toString()).put("interactive_ms", ClarityStats.round1(freeAt)).put("busy_ms", busy)
            .put("blocking_ms", b?.opt("blocking_ms") ?: JSONObject.NULL).put("long_frames", b?.opt("long_frames") ?: JSONObject.NULL)
            .put("longest_frame_ms", b?.opt("longest_ms") ?: JSONObject.NULL)
        val page = onMain {
            val vp = viewport()
            val d = density()
            val scroll = view.currentScroll()
            val nCards = view.dashboard?.views?.getOrNull(view.currentViewIndex)?.cards?.size ?: 0
            JSONObject().put("nodes", countViews(view)).put("cards", nCards).put("cards_rendered", currentCards().values.count { rendered(it.second) })
                .put("css", JSONArray().put((vp.width() / d).roundToInt()).put((vp.height() / d).roundToInt()))
                .put("dpr", d)
                .put("scroll_height", ((scroll?.getChildAt(0)?.height ?: vp.height()) / d).roundToInt())
        }
        line(clockFields(JSONObject().put("t", "refdash").put("tier", tier).put("protocol", c.getInt("PROTOCOL"))
            .put("clarity", c.getInt("CLARITY")).put("frontend", JSONObject.NULL).put("nodes", page.get("nodes"))
            .put("cards", page.get("cards")).put("cards_rendered", page.get("cards_rendered")).put("css", page.get("css"))
            .put("dpr", page.get("dpr")).put("scroll_height", page.get("scroll_height"))
            // A page load's paint and load event: none, Vitro loads no page (the restart is timed to usable).
            .put("fcp_ms", JSONObject.NULL).put("lcp_ms", JSONObject.NULL).put("load_ms", JSONObject.NULL)
            .put("nav_ms", ClarityStats.round1(nav)).put("usable", u).put("video_caps", JSONObject.NULL)
            .put("dialogs_before", if (first) JSONObject.NULL else before ?: JSONObject.NULL)))
        return u
    }

    private fun countViews(v: View): Int {
        if (v !is android.view.ViewGroup) return 1
        var n = 1
        for (i in 0 until v.childCount) n += countViews(v.getChildAt(i))
        return n
    }

    // ---- live updates: the fake streams them while the view asks

    private class Change(
        var at: Double,
        val d: Int,
        val kind: Int,
        val slot: String?,
        val gen: Int?,
        val late: Int,
    ) {
        var dom: Double? = null
        var tick: Double? = null
    }

    /** Main thread, except [offset], [rtt], [askedAt], [gen], [liveAt] (the script's). */
    private class Live {
        val watch = HashMap<String, View>()
        val pending = ArrayList<Change>()
        /** Each change read, by entity and context id, until the cards were told of it ([onDelivered]). */
        val byKey = HashMap<String, Change>()
        val changes = ArrayList<Change>()
        val seen = HashSet<String>()
        var on = false
        var applied = 0
        @Volatile var gen: Int? = null
        @Volatile var liveAt = 0.0
        @Volatile var offset: Double? = null
        @Volatile var rtt: Double? = null
        @Volatile var askedAt = 0.0
    }

    private val live = Live()

    /**
     * The store read a state change for [id] ([EntityStore.ContextTap.read], its reader thread), before it tells the
     * cards. Arrived is when the main thread gets to it (harness.js: its listener runs on the page's main thread,
     * before HA's). The card renders it from a task the store's listeners queue next ([onDelivered]).
     */
    private fun onState(id: String, context: String?) {
        if (stopped) return
        main.post {
            if (!live.watch.containsKey(id)) return@post
            val key = "$id|$context"
            if (!live.seen.add(key)) return@post
            val onOff = !id.startsWith("sensor.")
            live.applied++
            val m = context?.let { SLOT.matchEntire(it) }
            val ch = Change(
                at = now(),
                d = if (onOff) c.getJSONObject("LIVE").getInt("tileTransitionMs") else 0,
                kind = if (onOff) 2 else 0,
                slot = m?.let { it.groupValues[1] + it.groupValues[3].toInt() },
                gen = m?.groupValues?.get(2)?.toInt(),
                late = m?.groupValues?.get(4)?.toInt() ?: 0,
            )
            live.byKey[key] = ch
            if (live.on) live.changes += ch
        }
    }

    /**
     * The store told the cards of the change ([EntityStore.ContextTap.delivered], its reader thread, after each card
     * queued its render on this looper): this task runs after those renders, so the change's card has it, and the next
     * frame ([markTicks]) draws it. Its tick can't come before the card got the change.
     */
    private fun onDelivered(id: String, context: String?) {
        if (stopped) return
        main.post {
            val ch = live.byKey.remove("$id|$context") ?: return@post
            if (ch.dom == null) {
                ch.dom = now()
                live.pending += ch
            }
        }
    }

    /** Main thread, each frame while ticking: a change whose card has rendered is drawn in this frame. */
    private fun markTicks(ts: Double) {
        if (live.pending.isEmpty()) return
        val it = live.pending.iterator()
        while (it.hasNext()) {
            val ch = it.next()
            if (ch.dom != null && ch.tick == null) {
                ch.tick = ts
                it.remove()
            }
        }
    }

    private fun watch(ids: List<String>): Map<String, View> = onMain {
        live.watch.clear()
        live.pending.clear()
        live.byKey.clear()
        for ((cfg, card) in currentCards().values) for (id in entitiesOf(cfg)) if (id in ids) live.watch[id] = card
        HashMap(live.watch)
    }

    private fun unwatch() = onMain {
        live.watch.clear()
        live.pending.clear()
        live.byKey.clear()
    }

    /** harness.js syncClock: 12 round trips to /clarity/time, the shortest kept (offset = fake - page). */
    private fun syncClock() {
        var best: Pair<Double, Double>? = null
        repeat(12) {
            try {
                val a = now()
                val (code, body) = http("/clarity/time")
                val b = now()
                if (code == 200 && body != null) {
                    val ms = JSONObject(body).getDouble("ms")
                    if (best == null || b - a < best!!.first) best = (b - a) to (ms - (a + b) / 2)
                }
            } catch (e: java.io.IOException) {
                // the fake is gone
            }
        }
        live.offset = best?.second
        live.rtt = best?.first
    }

    private fun stream(on: Boolean) {
        if (on) live.askedAt = now()
        val (code, body) = try {
            http("/clarity/${if (on) "live" else "still"}?nonce=${URLEncoder.encode(v.nonce, "UTF-8")}")
        } catch (e: java.io.IOException) {
            if (on) throw e
            return
        }
        if (!on) return
        val j = body?.let { JSONObject(it) } ?: JSONObject()
        if (!j.has("gen")) throw IllegalStateException("the live stream didn't start (HTTP $code)")
        live.gen = j.getInt("gen")
        val off = live.offset
        live.liveAt = if (off != null && j.has("start_ms")) j.getDouble("start_ms") - off else live.askedAt
    }

    /** harness.js updatesWindow: a warm-up, then a window in which each change the fake sent is timed to its frame. */
    private fun updatesWindow(head: Map<String, View>) {
        val live0 = c.getJSONObject("LIVE")
        val every = 1000.0 / live0.getDouble("updatesPerS")
        val flip = live0.getDouble("flipEveryMs")
        val ph = c.getJSONObject("PHASES")
        line(JSONObject().put("t", "warmup").put("stage", "updates").put("n", 1))
        onMain {
            live.changes.clear()
            live.on = true
        }
        stream(true)
        sleep(ph.getDouble("warmupMs"))
        val watched = head.values.toSet()
        val off = onMain { val vp = viewport(); watched.count { !inView(it, vp) } }
        line(JSONObject().put("t", "measure").put("stage", "updates").put("n", 1).put("moving", watched.size)
            .put("offscreen", off).put("targets", head.size).put("update_every_ms", every.roundToInt())
            .put("animations", 0).put("animations_expected", 0))
        val t0 = now()
        val pacing = ClarityStats.measureMs(v.vsync)
        sleep(pacing)
        sleep(t0 + ph.getDouble("updatesWindowMs") - now())
        onMain { live.on = false }
        frame()
        frame()
        val t1 = t0 + ph.getDouble("updatesWindowMs")
        val raf = framesIn(t0, t0 + pacing)
        // Every value of each change read in one look on the main thread, the one that picks them: a tick the next
        // frame sets later can't land in this record beside ticks of an earlier moment.
        class Seen(val at: Double, val late: Int, val tick: Double?)
        val got = onMain {
            live.changes.filter { it.slot != null && it.gen == live.gen }.associate { it.slot!! to Seen(it.at, it.late, it.tick) }
        }
        val sched = ArrayList<Triple<String, Double, Int>>()
        var k = 1
        while (live.liveAt + k * every <= t1) sched += Triple("S$k", live.liveAt + k * every, 0).also { k++ }
        var f = 1
        val tileMs = live0.getInt("tileTransitionMs")
        while (live.liveAt + f * flip <= t1) sched += Triple("F$f", live.liveAt + f * flip, 2).also { f++ }
        sched.sortBy { it.second }
        val changes = JSONArray()
        for ((slot, due, kind) in sched) {
            val d = if (kind == 2) tileMs else 0
            if (due + d < t0 - every) continue
            val ch = got[slot]
            val sent = due + (ch?.late ?: 0)
            changes.put(JSONArray().put(ClarityStats.round1(sent - t0))
                .put(ch?.let { ClarityStats.round1(it.at - t0) } ?: JSONObject.NULL).put(d).put(kind)
                .put(ch?.tick?.let { ClarityStats.round1(it - t0) } ?: JSONObject.NULL))
        }
        line(JSONObject().put("t", "step").put("stage", "updates").put("n", 1).put("window_ms", (t1 - t0).roundToInt())
            .put("t0", ClarityStats.round1(t0)).put("pacing_ms", pacing).put("from", "sent")
            .put("stream_at_ms", ClarityStats.round1(live.liveAt - t0)).put("stream_asked_ms", ClarityStats.round1(live.askedAt - t0))
            .put("clock_rtt_ms", live.rtt?.let { ClarityStats.round1(it) } ?: JSONObject.NULL)
            .put("raf", ClarityStats.frameStats(ClarityStats.intervals(raf), v.vsync))
            .put("raf_times", JSONArray(raf.map { ClarityStats.round1(it - t0) })).put("changes", changes))
    }

    // ---- taps: the toggle tile, Event Timing's way

    /** One tap: its down and up, each from the event's timestamp to the end of the next frame after its handlers. */
    /** Main thread. */
    private val taps = Taps()
    private var downAt: Double? = null

    /**
     * VitroView, after its children handled [ev] (main thread). Event Timing times an event from its own timestamp to
     * the next paint after its handlers; here, to the end of the first frame drawn after them. A tap's click runs in a
     * task Android posts on the up (View.performClick), so the up is followed from a task queued after that one.
     */
    internal fun touched(ev: MotionEvent) {
        if (stopped) return
        val at = fromUptime(ev.eventTime)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = at
                val t = taps.down(at) ?: return
                afterNextFrame { end -> taps.downDone(t, end) }
            }
            // Android ends the gesture: its tap stays unfinished, and no later gesture's up is taken for it.
            MotionEvent.ACTION_CANCEL -> taps.cancel()
            MotionEvent.ACTION_UP -> {
                // The up of a tap already down, even after counting stopped: it ends that tap.
                val t = taps.up(at) ?: return
                main.post { afterNextFrame { end -> taps.upDone(t, end) } }
            }
        }
    }

    /** Main thread: [done] with the time the next frame's main-thread work ended (its traversal and draw included). */
    private fun afterNextFrame(done: (Double) -> Unit) {
        choreographer.postFrameCallback { main.postAtFrontOfQueue { done(now()) } }
    }

    private fun input() {
        val toggle = onMain { currentCards().values.firstOrNull { (cfg, _) -> cfg.entity == TOGGLE }?.second }
        if (toggle == null) {
            line(JSONObject().put("t", "input").put("stage", v.tier).put("method", "none").put("taps", 0)
                .put("values", JSONArray()).put("inp_ms", JSONObject.NULL))
            return
        }
        val r = onMain {
            taps.start()
            rectOf(toggle)
        }
        line(JSONObject().put("t", "input-ready").put("taps", v.taps).put("x", r.centerX()).put("y", r.centerY()))
        val ph = c.getJSONObject("PHASES")
        val end = now() + max(ph.getDouble("tapWaitMinMs"), v.taps * ph.getDouble("tapEachMs") + 5000)
        while (onMain { taps.count } < v.taps && now() < end) sleep(100.0)
        sleep(500.0)
        onMain { taps.stop() }
        // Each tap counted needs its down and its up, each timed to the end of a frame after its handlers: wait for a
        // late up and those frames, a while.
        val done = now() + TAP_FINISH_MS
        while (onMain { taps.unfinished } && now() < done) sleep(50.0)
        val (n, values) = onMain { taps.count to taps.times() }
        val all = values.sorted()
        // A tap still without its frame isn't given a time (the web harness fills only Event Timing's sub-16 ms ones):
        // the run's tap is then not measured (inp_ms null), never faster than it was.
        val incomplete = n - all.size
        line(JSONObject().put("t", "input").put("stage", v.tier).put("method", METHOD_TAPS).put("taps", n)
            .put("values", JSONArray(all.map { it.roundToInt() })).put("incomplete", incomplete)
            .put("inp_ms", if (incomplete > 0) JSONObject.NULL else all.lastOrNull()?.roundToInt() ?: JSONObject.NULL))
    }

    // ---- scroll: the same flick down from the top, during the live updates

    private var scrollWatch: ViewTreeObserver.OnScrollChangedListener? = null

    private fun swipe(i: Int) {
        val ph = c.getJSONObject("PHASES")
        onMain { view.currentScroll()?.scrollTo(0, 0) }
        sleep(ph.getDouble("repositionStillMs"))
        val g = onMain {
            val vp = viewport()
            val s = view.currentScroll()
            Geo(vp.centerX(), (vp.top + vp.height() * 0.85).roundToInt(), (vp.top + vp.height() * 0.15).roundToInt(),
                s?.let { (it.getChildAt(0)?.height ?: 0) - it.height } ?: 0, s?.scrollY ?: 0)
        }
        var first: Double? = null
        var last: Double? = null
        var appliedAt = 0
        var down: Double? = null
        // This swipe's listener, set and read on the main thread only: the cleanup below is posted there, after the
        // registration task (the looper runs them in order), so it finds a listener registered while the worker gave up.
        val mine = arrayOfNulls<ViewTreeObserver.OnScrollChangedListener>(1)
        try {
            onMain {
                downAt = null
                val s = view.currentScroll()
                var lastY = s?.scrollY ?: 0
                val w = ViewTreeObserver.OnScrollChangedListener {
                    val y = view.currentScroll()?.scrollY ?: return@OnScrollChangedListener
                    if (y == lastY) return@OnScrollChangedListener
                    lastY = y
                    val t = now()
                    if (first == null) {
                        first = t
                        down = downAt
                        appliedAt = live.applied
                        line(JSONObject().put("t", "measure").put("stage", "scroll").put("n", i))
                    }
                    last = t
                }
                scrollWatch = w
                mine[0] = w
                view.viewTreeObserver.addOnScrollChangedListener(w)
            }
            swipeWindow(i, g, ph, { first }, { last }, { appliedAt }, { down })
        } finally {
            // On every way out (the registration or the window timing out, a command that failed): this swipe's own
            // listener, never the next one's.
            main.post {
                mine[0]?.let { w ->
                    view.viewTreeObserver.removeOnScrollChangedListener(w)
                    if (scrollWatch === w) scrollWatch = null
                }
                mine[0] = null
            }
        }
    }

    private class Geo(val x: Int, val lo: Int, val hi: Int, val max: Int, val y0: Int)

    private fun swipeWindow(
        i: Int, g: Geo, ph: JSONObject, first: () -> Double?, last: () -> Double?, appliedAt: () -> Int, down: () -> Double?,
    ) {
        line(JSONObject().put("t", "swipe-ready").put("n", i).put("x", g.x).put("y1", g.lo).put("y2", g.hi)
            .put("ms", ph.getInt("swipeMs")))
        val cap = ClarityStats.measureMs(v.vsync)
        val end = now() + ph.getDouble("swipeWaitMs")
        while (now() < end) {
            sleep(50.0)
            val (f0, l0) = onMain { first() to last() }
            if (f0 != null && (now() - l0!! > ph.getDouble("scrollQuietMs") || now() - f0 > cap)) break
        }
        // The window ends here: the listener goes in the same look, so no late first scroll logs a measure line after it.
        val (f0, l0, y, applied) = onMain {
            scrollWatch?.let { view.viewTreeObserver.removeOnScrollChangedListener(it) }
            scrollWatch = null
            listOf(first(), last(), view.currentScroll()?.scrollY?.toDouble(), live.applied.toDouble())
        }
        if (f0 == null) {
            line(JSONObject().put("t", "step").put("stage", "scroll").put("n", i).put("window_ms", 0).put("scrolled_px", 0)
                .put("raf", JSONObject().put("n", 0)))
            return
        }
        val windowMs = minOf(cap, l0!! - f0 + v.vsync)
        val stamps = framesIn(f0, f0 + windowMs)
        val iv = ClarityStats.intervals(stamps)
        val yNow = y ?: g.y0.toDouble()
        line(JSONObject().put("t", "step").put("stage", "scroll").put("n", i).put("window_ms", windowMs.roundToInt())
            .put("scrolled_px", (abs(yNow - g.y0) / density()).roundToInt()).put("hit_edge", yNow >= g.max - 1)
            .put("changes_during", applied!!.toInt() - appliedAt())
            .put("first_scroll_ms", down()?.let { (f0 - it).roundToInt() } ?: JSONObject.NULL)
            .put("raf", ClarityStats.frameStats(iv, v.vsync)).put("raf_intervals", JSONArray(iv.map { ClarityStats.round1(it) })))
        sleep(300.0)
    }

    // ---- the more-info dialog: Vitro's own sheet, opened as a tile's tap opens it

    /** What one sheet did, as [MoreInfoSheet] tells it (main thread). */
    private class Sheet : MoreInfoSheet.Trace {
        @Volatile var opened = false
        @Volatile var shown = false
        @Volatile var closed = false
        @Volatile var chart: View? = null
        @Volatile var wide: Boolean? = null
        @Volatile var showMs: Long? = null

        override fun entered(wide: Boolean, durationMs: Long) {
            opened = true
            this.wide = wide
            showMs = durationMs
        }

        override fun shown() {
            shown = true
        }

        override fun graph(chart: View) {
            this.chart = chart
        }

        override fun closed() {
            closed = true
        }

        /** The history graph laid out with its data. Main thread. */
        fun graphDrawn(): Boolean = chart?.let { it.height > 0 && it.isShown } == true
    }

    private fun reducedMotion(): Boolean = try {
        Settings.Global.getFloat(view.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (_: Exception) {
        false
    }

    private fun moreInfo(spec: JSONObject, n: Int, of: Int, stage: String) {
        val d = c.getJSONObject("DIALOG")
        val entity = spec.getString("entity")
        val hasGraph = spec.optBoolean("graph")
        val sheet = Sheet()
        val t0 = now()
        onMain { view.openMoreInfo(entity, sheet) }
        val frames = ArrayList<Double>()
        var st = ClarityStats.Dialog()
        awaitFrames(d.getDouble("waitMs")) { ts ->
            frames += ts
            val graph = if (hasGraph) (st.graph != null || (sheet.opened && sheet.graphDrawn())) else null
            st = ClarityStats.dialogStep(sheet.opened, sheet.shown, graph, ts, st)
            st.done
        }
        if (st.done) sleep(d.getDouble("stillMs"))
        val closeFrames = ArrayList<Double>()
        var c0: Double? = null
        var closed: Double? = null
        if (onMain { view.moreInfoOpen }) {
            c0 = now()
            onMain { view.closeMoreInfo() }
            if (sheet.opened) {
                awaitFrames(d.getDouble("closeWaitMs")) { ts ->
                    closeFrames += ts
                    if (sheet.closed) closed = ts
                    sheet.closed
                }
            }
        }
        val times = ClarityStats.dialogTimes(t0, frames, st, if (sheet.opened) c0 else null, closed, closeFrames, v.vsync)
        val secs = { ms: Double -> "${(ms / 1000).roundToInt()} s" }
        val error: Any = when {
            !times.getBoolean("opened") -> "it never opened within ${secs(d.getDouble("waitMs"))}"
            !times.isNull("settled_ms") -> JSONObject.NULL
            hasGraph && !times.isNull("shown_ms") -> "its history graph wasn't drawn within ${secs(d.getDouble("waitMs"))}"
            else -> "it didn't finish opening within ${secs(d.getDouble("waitMs"))}"
        }
        val o = JSONObject().put("t", "dialog").put("stage", stage).put("n", n).put("of", of).put("entity", entity)
            .put("kind", spec.optString("kind")).put("graph", hasGraph)
            .put("mode", sheet.wide?.let { if (it) "dialog" else "bottom-sheet" } ?: JSONObject.NULL)
            .put("show_duration_ms", sheet.showMs ?: JSONObject.NULL).put("reduced_motion", reducedMotion())
        for (k in times.keys()) o.put(k, times.get(k))
        o.put("error", error).put("close_error",
            if (times.getBoolean("opened") && times.isNull("close_ms")) "it didn't close within ${secs(d.getDouble("closeWaitMs"))}" else JSONObject.NULL)
        line(o)
        sleep(d.getDouble("stillMs"))
    }

    /** The dialogs' entities followed, untimed, so each sheet opens with its state as HA's dialog does. */
    private fun followDialogs() {
        val list = c.getJSONObject("DIALOG").getJSONArray("entities")
        onMain { for (i in 0 until list.length()) follow(list.getJSONObject(i).getString("entity")) }
    }

    /** harness.js dialogs: at the view's top, while the live updates still run, each dialog in turn. */
    private fun dialogs(count: Int, stage: String): Int {
        val list = c.getJSONObject("DIALOG").getJSONArray("entities")
        val n = minOf(count, list.length())
        if (n == 0) return 0
        onMain { view.currentScroll()?.scrollTo(0, 0) }
        sleep(c.getJSONObject("DIALOG").getDouble("stillMs"))
        for (i in 0 until n) {
            try {
                moreInfo(list.getJSONObject(i), i, n, stage)
            } catch (e: Stopped) {
                throw e
            } catch (e: Exception) {
                line(JSONObject().put("t", "dialog").put("stage", stage).put("n", i).put("of", n)
                    .put("entity", list.getJSONObject(i).getString("entity")).put("kind", list.getJSONObject(i).optString("kind"))
                    .put("opened", false).put("settled_ms", JSONObject.NULL).put("error", e.toString()))
            }
        }
        return n
    }

    /**
     * harness.js settleDialogs, for Vitro's sheet: none open (one still open is closed). Vitro has no dialog manager
     * that can show one late (HA's history-held shows, #410), so nothing is pending and no history entry is current.
     */
    private fun settleDialogs(limitMs: Double): JSONObject {
        val t0 = now()
        var closedN = 0
        while (true) {
            val open = onMain { view.moreInfoOpen }
            if (!open) break
            if (now() - t0 > limitMs) break
            if (onMain { view.closeMoreInfo() }) closedN++
            sleep(100.0)
        }
        val open = onMain { view.moreInfoOpen }
        return JSONObject().put("ms", (now() - t0).roundToInt()).put("clean", !open).put("closed", closedN)
            .put("pending", JSONArray()).put("open", open).put("history", false)
    }

    // ---- one reference view, as the runner asked

    private fun openView(cmd: JSONObject) {
        val ph = c.getJSONObject("PHASES")
        v = Cmd(
            nonce = cmd.getString("nonce"), run = cmd.optString("run", "0").toDoubleOrNull()?.toInt() ?: 0,
            taps = max(0, cmd.optString("taps", "0").toDoubleOrNull()?.toInt() ?: 0),
            swipes = if (cmd.has("swipes")) max(0, cmd.optString("swipes").toDoubleOrNull()?.toInt() ?: 0) else ph.getInt("swipes"),
            dialogs = if (cmd.has("dialogs")) max(0, cmd.optString("dialogs").toDoubleOrNull()?.toInt() ?: 0)
            else c.getJSONObject("DIALOG").getJSONArray("entities").length(),
            vsync = cmd.optString("vsync").toDoubleOrNull() ?: (1000.0 / 60), tier = cmd.getString("view"),
        )
        unwatch()
        val before = settleDialogs(c.getJSONObject("DIALOG").getDouble("beforeMs"))
        if (!before.getBoolean("clean")) {
            throw IllegalStateException("a more-info sheet of the view before was still open after ${before.getInt("ms") / 1000} s; " +
                "this view isn't measured on it")
        }
        val shownBefore = if (before.getInt("ms") > 0 || before.getInt("closed") > 0) before else null
        startTicks()
        val path = c.optString("DASHBOARD", DASHBOARD)
        val nav = now()
        // A view tab's switch (VitroView's tab), with the page's history and location as a tap on the tab keeps them.
        if (!onMain { view.openViewAsTab(v.tier) }) throw IllegalStateException("no dashboard to open /$path/${v.tier} in")
        val u = reportUsable(nav, false, shownBefore)
        // In the still time after usable, before anything is timed: the view's own subscription replaced the last's.
        followDialogs()
        sleep(u.getDouble("interactive_ms") + ph.getDouble("stillAfterUsableMs") - now())
        roomToFlick()
        syncClock()
        val h = c.getJSONObject("HEAD_ENTITIES")
        val ids = ArrayList<String>()
        for (k in listOf("sensors", "rows", "switches")) h.optJSONArray(k)?.let { a -> for (i in 0 until a.length()) ids += a.getString(i) }
        val head = watch(ids)
        // The cameras' fixed window runs beside the updates and the taps, from when every camera played (or 20 s from
        // the navigation), and the swipes come after it: harness.js trackCameras, camerasStart and camerasEnd.
        val cam = cameraPlan(nav)
        updatesWindow(head)
        if (v.taps > 0) input()
        cam?.let { camerasEnd(it) }
        for (i in 0 until v.swipes) swipe(i)
        val fired = dialogs(v.dialogs, v.tier)
        try {
            stream(false)
        } catch (_: Exception) {
        }
        unwatch()
        stopTicks()
        val settle: Any = if (fired > 0) settleDialogs(c.getJSONObject("DIALOG").getDouble("settleMs")) else JSONObject.NULL
        line(JSONObject().put("t", "done").put("stage", v.tier).put("settle", settle))
    }

    // ---- live cameras: each stream's own reports (ClarityCameras), over the web harness's window

    private val cameras = ClarityCameras(::now).also {
        it.viewOfCard = { card -> built.entries.firstOrNull { (_, cards) -> cards.values.any { p -> p.second === card } }?.key }
    }

    /** One view's camera window: its cameras (entities, in card order), how many were asked for, its path, its start. */
    private class CamPlan(val cameras: List<String>, val expected: Int, val path: String, val nav: Double, val t0: Double)

    /**
     * harness.js camCount and pathOf: the cameras tiers.json gives the view (a list in it is a grid of them), and the
     * path they are graded on (`CAMERA.path`, WebRTC; HLS on a fallback copy).
     */
    private fun cameraCount(tier: String): Int {
        val fallback = c.optJSONObject("FALLBACK")
        val list = c.optJSONObject("CAMERAS")?.optJSONArray(fallback?.optString(tier)?.ifEmpty { null } ?: tier) ?: return 0
        return (0 until list.length()).sumOf { (list.opt(it) as? JSONArray)?.length() ?: 1 }
    }

    private fun cameraPath(tier: String): String =
        if (c.optJSONObject("FALLBACK")?.has(tier) == true) "hls" else c.getJSONObject("CAMERA").getString("path")

    /** The live cameras of the view's cards, in card order (a grid's in its order): `camera_view: live` pictures. */
    private fun cameraEntities(): List<String> = onMain {
        val out = ArrayList<String>()
        fun walk(o: JSONObject) {
            if (o.optString("camera_view") == "live") {
                (o.optString("camera_image").ifEmpty { null } ?: o.optString("entity").ifEmpty { null })
                    ?.takeIf { it.startsWith("camera.") && it !in out }?.let { out += it }
            }
            o.optJSONArray("cards")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(::walk) }
        }
        for ((cfg, _) in currentCards().values) walk(cfg.raw)
        out
    }

    /**
     * harness.js trackCameras, then camerasStart: waits up to [CAMERA_FIND_MS] from [nav] for every camera of the view to
     * show a picture on its path, then starts the window. Null when the view has no cameras, or this build's streams
     * don't report ([ClarityCameras.wired]): then no camera line goes out and the task is not measured.
     */
    private fun cameraPlan(nav: Double): CamPlan? {
        val expected = cameraCount(v.tier)
        if (expected == 0 || !ClarityCameras.wired) return null
        val path = cameraPath(v.tier)
        val kind = ClarityCameras.kindOf(path) ?: return null
        val list = cameraEntities()
        val end = nav + CAMERA_FIND_MS
        while (now() < end) {
            if (list.size >= expected && onMain { cameras.allPlaying(view.currentViewIndex, list, kind, nav) }) break
            sleep(100.0)
        }
        return CamPlan(list, expected, path, nav, now())
    }

    /** harness.js camerasEnd: at the window's end (`CAMERA.windowMs` from its start), the view's `camera` line. */
    private fun camerasEnd(p: CamPlan) {
        val cam = c.getJSONObject("CAMERA")
        val t1 = p.t0 + cam.getDouble("windowMs")
        sleep(t1 - now())
        val fps = cam.getDouble("fps")
        // Read after the taps when they ran past the window: only what happened by t1 counts (ClarityCameras.window).
        val w = onMain {
            cameras.window(view.currentViewIndex, p.cameras, p.expected, ClarityCameras.kindOf(p.path)!!, p.nav, p.t0, t1, fps)
        }
        line(JSONObject().put("t", "camera").put("stage", v.tier).put("path", p.path).put("window_ms", (t1 - p.t0).roundToInt())
            .put("t0_ms", ClarityStats.round1(p.t0)).put("nav_ms", ClarityStats.round1(p.nav)).put("fps", fps)
            .put("expected", p.expected).put("found", w.getInt("found")).put("streams", w.getJSONArray("streams"))
            .put("fetches", JSONObject.NULL))
    }

    /** harness.js roomToFlick: every view leaves `scrollRoomScreens` screens of scrolling below its top. */
    private fun roomToFlick() = onMain {
        val s = view.currentScroll() ?: return@onMain
        val body = s.getChildAt(0) ?: return@onMain
        val want = ceil(s.height * (1 + c.getJSONObject("PHASES").getDouble("scrollRoomScreens"))).toInt()
        if (body.minimumHeight < want) {
            body.minimumHeight = want
            body.requestLayout()
        }
    }

    private fun script() {
        // The page config (one source with the web harness's: dashboards.page_config), until the fake answers.
        while (true) {
            if (!onMain { active() }) return
            try {
                val (code, body) = http("/clarity/config.json")
                if (code == 200 && body != null) {
                    c = JSONObject(body)
                    break
                }
            } catch (e: java.io.IOException) {
            }
            sleep(1000.0)
        }
        // The landing view (the restart's end, or an idle sample's page): when it was usable.
        startTicks()
        reportUsable(0.0, true, null)
        stopTicks()
        line(JSONObject().put("t", "done").put("stage", "restart"))
        // After a restart, each dialog once, not graded: the sheet's first build pays for what it loads, as HA's does.
        if (landing == c.getJSONArray("VIEWS").optString(0, "start")) {
            followDialogs()
            sleep(FOLLOW_MS)
            startTicks()
            dialogs(c.getJSONObject("DIALOG").getJSONArray("entities").length(), "warmup")
            stopTicks()
            settleDialogs(c.getJSONObject("DIALOG").getDouble("settleMs"))
        }
        var last = ""
        while (true) {
            check()
            // The test hold gone (or moved to another fake): this harness is done, whether or not a page load follows.
            if (!onMain { active() }) return
            val cmd = try {
                val (code, body) = http("/clarity/next?after=${URLEncoder.encode(last, "UTF-8")}", NEXT_TIMEOUT_MS)
                if (code == 200 && body != null) JSONObject(body) else null
            } catch (e: java.io.IOException) {
                sleep(1000.0)
                continue
            }
            if (cmd == null) continue
            if (!onMain { active() }) return
            last = cmd.getString("nonce")
            try {
                openView(cmd)
            } catch (e: Stopped) {
                throw e
            } catch (e: Exception) {
                line(JSONObject().put("t", "error").put("message", Log.getStackTraceString(e).take(MAX_ERROR)))
                try {
                    stream(false)
                } catch (_: Exception) {
                }
                unwatch()
                stopTicks()
            }
        }
    }

    companion object {
        private const val TAG = "Glassworks.Clarity"

        /** The engine the lines say measured them (report.py's `engine`). */
        const val ENGINE = "vitro"

        /** The reference dashboard's path (tiers.json DASHBOARD): only it starts the harness. */
        const val DASHBOARD = "clarity-ref"

        /** How taps were timed (the input line's `method`; the web harness says `event-timing`). */
        const val METHOD_TAPS = "vitro-frame"

        /** The reference home's toggle (dashboards.py): its tap is the one timed. */
        private const val TOGGLE = "light.living_room"

        /** The live stream's context ids: its kind, generation, number and how late the fake sent it (harness.js SLOT). */
        private val SLOT = Regex("^01JC1ARITY([SF])(\\d{5})(\\d{6})(\\d{4})$")

        /** The runner's deadline for a view that never becomes usable (perfrun.py VIEW_DEADLINE_S). */
        private const val VIEW_DEADLINE_MS = 300_000.0
        private const val FRAME_WAIT_MS = 2_000.0
        private const val MAIN_WAIT_S = 60L
        private const val NEXT_TIMEOUT_MS = 30_000
        private const val MAX_ERROR = 600

        /** The most frames a view keeps ([frames]). */
        private const val MAX_FRAMES = 30_000

        /** How often the main thread checks the test hold still shows this harness's fake ([holdWatch]). */
        private const val HOLD_CHECK_MS = 500L

        /** How long after the taps each one may still take to get its frame (past it, the tap isn't measured). */
        private const val TAP_FINISH_MS = 2_000.0

        /** harness.js trackCameras: how long after the navigation the cameras may take to show a picture. */
        private const val CAMERA_FIND_MS = 20_000.0

        /** For the followed entities' states to land (the page batches its resubscription for 150 ms). */
        private const val FOLLOW_MS = 1_000.0
    }
}

/** How Clarity's harness reaches the fake Home Assistant: a GET, its status and (on 200) its body. */
fun interface ClarityHttp {
    fun get(url: String, timeoutMs: Int): Pair<Int, String?>

    companion object {
        /**
         * HTTP to the fake on the loopback. No redirect is followed (CWE-918): whatever else answered on the port can't
         * send the harness, its nonce or its reads to another host; a 3xx is any other non-200, with no body read.
         */
        val Loopback = ClarityHttp { url, timeoutMs ->
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.useCaches = false
                conn.instanceFollowRedirects = false
                val code = conn.responseCode
                code to (if (code == 200) conn.inputStream.bufferedReader().readText() else null)
            } finally {
                conn.disconnect()
            }
        }
    }
}
