/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.clarity

import android.view.View
import dev.glassworks.engine.vitro.camera.CameraStream
import dev.glassworks.engine.vitro.camera.CameraStreamFactory
import dev.glassworks.engine.vitro.camera.CameraStreamKind
import dev.glassworks.engine.vitro.camera.CameraStreamVideoSize
import org.json.JSONArray
import org.json.JSONObject
import java.util.IdentityHashMap
import kotlin.math.min

/**
 * Clarity's camera task on Vitro (docs/perf.md, "Vitro"): what each camera stream of the view did, as the web harness
 * reads it from HA's players (`harness.js` trackCameras, camerasStart, camerasEnd).
 *
 * Every stream Vitro plays comes from a [CameraStreamFactory] the engine wraps with [measured], so each one reports
 * here, whatever its kind (WebRTC, HLS or MJPEG) and whatever the stream choice does with it. Outside a Clarity run
 * there is no [current], and each hook in [MeasuredCameraStream] is one null check.
 *
 * Main thread only: [CameraStream]'s calls and its [CameraStream.Listener]'s all come there. A picture is timed when
 * its listener call runs, on the main thread's clock ([now], the harness's page clock).
 */
internal class ClarityCameras(private val now: () -> Double) {

    /** One stream, of one kind, for one camera entity: when it started, each picture it drew, how it ended. */
    internal class Rec(val stream: CameraStream, val entityId: String, val kind: CameraStreamKind, val openedAt: Double) {
        var startedAt: Double? = null
        /** Each [CameraStream.Listener.onFirstFrame] (once per start or resume): a picture on screen. */
        val firstFrames = ArrayList<Double>()
        /** The stream's own time from its start to each of those first pictures, with when it came (its [firstFrames] time). */
        val sinceStart = ArrayList<Pair<Double, Long>>()
        /** Every picture drawn: each first frame and each [CameraStream.Listener.onFrameRendered]. */
        val frames = ArrayList<Double>()
        /** Each error it reported, with when (so a window counts only its own). */
        val errors = ArrayList<Pair<Double, String>>()
        var closedAt: Double? = null
        /**
         * The view it played in (its index in the dashboard), once found from the card that holds its picture. Kept by
         * index, not by card: Vitro builds a view's cards again (a late state, a theme), and a stream that played in
         * the card before the rebuild is still that view's camera.
         */
        var viewIndex: Int? = null
    }

    private val recs = IdentityHashMap<CameraStream, Rec>()
    private val order = ArrayList<Rec>()

    /**
     * The view a top-level card belongs to (its index), of any view built now ([VitroClarity]'s registry); null for any
     * other view. Main thread.
     */
    var viewOfCard: (View) -> Int? = { null }

    private fun rec(s: MeasuredCameraStream): Rec = recs.getOrPut(s) {
        Rec(s, s.entityId, s.kind, now()).also {
            order += it
            trim()
        }
    }

    /** Streams that closed long ago go first: a run opens a few dozen at most. */
    private fun trim() {
        if (order.size <= MAX_STREAMS) return
        val old = order.filter { it.closedAt != null }.take(order.size - MAX_STREAMS)
        for (r in old) {
            order.remove(r)
            recs.remove(r.stream)
        }
    }

    /** The view [r] played in: the first card up from its picture that a built view holds. Kept once found. */
    private fun viewOf(r: Rec): Int? {
        r.viewIndex?.let { return it }
        var v: View? = r.stream.view
        while (v != null) {
            viewOfCard(v)?.let { i -> return i.also { r.viewIndex = it } }
            v = v.parent as? View
        }
        return null
    }

    /** [s] was made (before its card places and starts it): its [Rec.openedAt]. */
    internal fun opened(s: MeasuredCameraStream) {
        rec(s)
    }

    internal fun started(s: MeasuredCameraStream) {
        val r = rec(s)
        if (r.startedAt == null) r.startedAt = now()
        viewOf(r)
    }

    internal fun firstFrame(s: MeasuredCameraStream, sinceStartMs: Long) {
        val r = rec(s)
        val t = now()
        r.firstFrames += t
        r.sinceStart += t to sinceStartMs
        frame(r, t)
        viewOf(r)
    }

    internal fun frameRendered(s: MeasuredCameraStream) = frame(rec(s), now())

    private fun frame(r: Rec, t: Double) {
        if (r.frames.size >= MAX_FRAMES) r.frames.subList(0, MAX_FRAMES / 2).clear()
        r.frames += t
    }

    internal fun error(s: MeasuredCameraStream, reason: String) {
        val r = rec(s)
        if (r.errors.size < MAX_ERRORS) r.errors += now() to reason.take(MAX_REASON)
    }

    internal fun ended(s: MeasuredCameraStream) = error(s, "ended")

    internal fun closed(s: MeasuredCameraStream) {
        val r = rec(s)
        if (r.closedAt == null) r.closedAt = now()
    }

    /**
     * The streams of view [viewIndex] between [nav] and [end]: those that played in one of its cards (before or after
     * a rebuild of them), opened by [end] and not closed before [nav].
     */
    private fun ofView(viewIndex: Int, nav: Double, end: Double): List<Rec> =
        order.filter { it.openedAt <= end && (it.closedAt == null || it.closedAt!! >= nav) && viewOf(it) == viewIndex }

    /** A camera's first picture on [kind] between [nav] and [end], or null. */
    private fun firstFrame(rs: List<Rec>, kind: CameraStreamKind, nav: Double, end: Double): Double? =
        rs.filter { it.kind == kind }.flatMap { it.firstFrames }.filter { it in nav..end }.minOrNull()

    /**
     * harness.js trackCameras' test: every one of view [viewIndex]'s [cameras] has shown a picture on [path] since
     * [nav].
     */
    fun allPlaying(viewIndex: Int, cameras: List<String>, path: CameraStreamKind, nav: Double): Boolean {
        val end = now()
        val rs = ofView(viewIndex, nav, end)
        return cameras.isNotEmpty() && cameras.all { e -> firstFrame(rs.filter { it.entityId == e }, path, nav, end) != null }
    }

    /**
     * harness.js camerasEnd: the `camera` line's `found` and `streams` over the window [t0, t1], for view [viewIndex]'s
     * [cameras] (its camera entities, in card order) of which [expected] were asked for. Only what happened by [t1]
     * counts (a stream opened, a picture, an error), so reading it later changes nothing. Each camera is graded on
     * [path], the view's (WebRTC on a graded view, HLS on a fallback copy): its first picture on it since [nav], the
     * pictures it drew in the window against [fps], and its gaps, by the WebRTC receiver's freeze rule on [path]
     * WebRTC and by gaps over 500 ms on HLS, as on the web engine. A camera that never played on [path] is a stream
     * not found, which fails the task, as there; what it played instead is in `others` and `transport`.
     */
    fun window(
        viewIndex: Int, cameras: List<String>, expected: Int, path: CameraStreamKind,
        nav: Double, t0: Double, t1: Double, fps: Double,
    ): JSONObject {
        val rs = ofView(viewIndex, nav, t1)
        val dur = t1 - t0
        val streams = JSONArray()
        var found = 0
        for (i in 0 until maxOf(expected, cameras.size)) {
            val entity = cameras.getOrNull(i)
            val mine = if (entity == null) emptyList() else rs.filter { it.entityId == entity }
            val graded = mine.filter { it.kind == path }
            val first = firstFrame(mine, path, nav, t1)
            val out = JSONObject().put("path", name(path)).put("entity", entity ?: JSONObject.NULL)
                .put("w", JSONObject.NULL).put("h", JSONObject.NULL)
            if (graded.isEmpty() || first == null) {
                // Never played on the view's path: a stream not found, as harness.js `missing` (it fails the task).
                out.put("found", graded.isNotEmpty()).put("first_frame_ms", JSONObject.NULL).put("shown_pct", JSONObject.NULL)
                    .put("max_gap_ms", JSONObject.NULL).put("stall_total_ms", JSONObject.NULL)
                if (graded.isNotEmpty()) found++
            } else {
                found++
                // Every picture the camera drew on its path, its streams' together (a stream the choice made again
                // after an error goes on where the last one stopped, and the gap between them counts).
                val times = graded.flatMap { it.frames }.filter { it >= first && it <= t1 }.sorted()
                val gaps = if (path == CameraStreamKind.WEB_RTC) ClarityStats.freezeGaps(times, t0, t1)
                else ClarityStats.videoGaps(times, t0, t1)
                val frames = times.count { it in t0..t1 }
                out.put("found", true)
                    .put("first_frame_ms", ClarityStats.round1(first - nav))
                    // The stream's own time to that same first picture (one at or before t1, whatever came after).
                    .put("first_frame_since_start_ms", graded.flatMap { it.sinceStart }.firstOrNull { it.first == first }?.second
                        ?: JSONObject.NULL)
                    // When that stream was made and started, from nav: what passed before the stream's own clock ran
                    // (the view's build up to the card, capabilities, the player's set-up).
                    .let { o ->
                        val rec = graded.firstOrNull { first in it.firstFrames }
                        o.put("opened_ms", rec?.let { ClarityStats.round1(it.openedAt - nav) } ?: JSONObject.NULL)
                            .put("started_ms", rec?.startedAt?.let { ClarityStats.round1(it - nav) } ?: JSONObject.NULL)
                    }
                    .put("frames", frames)
                    .put("shown_pct", ClarityStats.round1(min(100.0, 100.0 * frames / (fps * dur / 1000))))
                    .put("max_gap_ms", ClarityStats.round1(gaps.maxGapMs))
                    .put("stall_total_ms", ClarityStats.round1(gaps.stallTotalMs))
                    .put("stalls_at", JSONArray(gaps.stallsAt.map { (a, b) -> JSONArray().put(ClarityStats.round2(a)).put(ClarityStats.round2(b)) }))
                    .put("freezes", gaps.freezes ?: JSONObject.NULL)
                    .put("gaps_source", if (path == CameraStreamKind.WEB_RTC) GAPS_FREEZE else GAPS_500)
                    .put("source", SOURCE)
                    // The pictures cover the whole window: nothing of it is left out (report.py uncovered_ms 0).
                    .put("span_ms", ClarityStats.round1(dur))
                    .put("sessions", graded.size)
                    .put("stall_presented", JSONObject.NULL).put("callbacks", JSONObject.NULL)
                    .put("decoded", JSONObject.NULL).put("dropped", JSONObject.NULL).put("waiting", JSONObject.NULL)
            }
            // The camera's other kinds (the choice's fallbacks, or the ones it tried first): detail, as harness.js `others`.
            out.put("others", JSONArray(mine.filter { it.kind != path }.groupBy { it.kind }.map { (k, xs) ->
                JSONObject().put("path", name(k))
                    .put("first_frame_ms", firstFrame(xs, k, nav, t1)?.let { ClarityStats.round1(it - nav) } ?: JSONObject.NULL)
                    .put("connected", xs.any { it.closedAt == null || it.closedAt!! > t1 })
            }))
            // What the camera showed at the window's end: the kind whose stream was still open there, with the latest
            // first picture. The report says it plainly when it isn't the view's path (an MJPEG fallback).
            val showing = mine.filter { (it.closedAt == null || it.closedAt!! > t1) && it.firstFrames.any { f -> f in nav..t1 } }
                .maxByOrNull { it.firstFrames.filter { f -> f <= t1 }.max() }
            out.put("transport", showing?.let { name(it.kind) } ?: JSONObject.NULL)
            val errors = mine.flatMap { r -> r.errors.filter { (at, _) -> at <= t1 }.map { (_, e) -> "${name(r.kind)}: $e" } }
                .take(MAX_ERRORS)
            out.put("errors", JSONArray(errors))
            streams.put(out)
        }
        return JSONObject().put("found", found).put("streams", streams)
    }

    companion object {
        /** The harness measuring now, or null: outside a Clarity run every hook is this one check. Main thread. */
        @Volatile
        @JvmStatic
        internal var current: ClarityCameras? = null

        /**
         * Whether this build's streams report to Clarity ([measured] wrapped a factory). Without it Vitro logs no
         * `camera` line, and the task is not measured, never failed for cameras it couldn't see.
         */
        @Volatile
        @JvmStatic
        var wired: Boolean = false
            internal set

        /**
         * [factory]'s streams, each reporting to Clarity while a run measures (a [MeasuredCameraStream]); the same
         * streams otherwise. The engine wraps each factory it builds, whatever the kind.
         */
        @JvmStatic
        fun measured(factory: CameraStreamFactory): CameraStreamFactory {
            wired = true
            if (factory is Measured) return factory
            return Measured(factory)
        }

        private class Measured(private val inner: CameraStreamFactory) : CameraStreamFactory {
            override fun create(entityId: String, kind: CameraStreamKind): CameraStream? =
                inner.create(entityId, kind)?.let { MeasuredCameraStream(it, entityId, kind) }
                    ?.also { ClarityCameras.current?.opened(it) }

            override fun prewarm() = inner.prewarm()
        }

        /** The kind's name in the page lines: the web harness's `path` names, and `mjpeg`. */
        fun name(kind: CameraStreamKind): String = when (kind) {
            CameraStreamKind.WEB_RTC -> "web_rtc"
            CameraStreamKind.HLS -> "hls"
            CameraStreamKind.MJPEG -> "mjpeg"
        }

        /** The page line's path name back to its kind (`web_rtc`, `hls`), or null. */
        fun kindOf(path: String): CameraStreamKind? = CameraStreamKind.entries.firstOrNull { name(it) == path }

        /** Where a Vitro stream's frames shown come from: the pictures its player drew, one call each. */
        const val SOURCE = "vitro-frames"

        /** The gaps by the WebRTC receiver's freeze rule ([ClarityStats.freezeGaps]). */
        const val GAPS_FREEZE = "vitro-frames (freeze rule)"

        /** The gaps over 500 ms ([ClarityStats.videoGaps]), the web engine's HLS fallback's. */
        const val GAPS_500 = "vitro-frames (gaps over 500 ms)"

        private const val MAX_STREAMS = 64
        private const val MAX_FRAMES = 20_000
        private const val MAX_ERRORS = 5
        private const val MAX_REASON = 120
    }
}

/**
 * A [CameraStream] that tells Clarity what it does ([ClarityCameras.current]) and then does what [inner] does. The
 * stream choice sets [listener] on this one; [inner] reports to this one's own listener, which passes each call on.
 * The video's size is [inner]'s, when it knows it ([CameraStreamVideoSize]; 0, unknown, otherwise), so wrapping a
 * stream never changes how its card fits the video.
 */
internal class MeasuredCameraStream(
    private val inner: CameraStream,
    val entityId: String,
    val kind: CameraStreamKind,
) : CameraStream, CameraStreamVideoSize {
    override val view: View get() = inner.view

    override val videoWidth: Int get() = (inner as? CameraStreamVideoSize)?.videoWidth ?: 0

    override val videoHeight: Int get() = (inner as? CameraStreamVideoSize)?.videoHeight ?: 0

    override var listener: CameraStream.Listener? = null

    init {
        inner.listener = object : CameraStream.Listener {
            override fun onFirstFrame(sinceStartMs: Long) {
                ClarityCameras.current?.firstFrame(this@MeasuredCameraStream, sinceStartMs)
                listener?.onFirstFrame(sinceStartMs)
            }

            override fun onFrameRendered() {
                ClarityCameras.current?.frameRendered(this@MeasuredCameraStream)
                listener?.onFrameRendered()
            }

            override fun onSurfaceReady() {
                listener?.onSurfaceReady()
            }

            override fun onSurfaceLost() {
                listener?.onSurfaceLost()
            }

            override fun onError(reason: String) {
                ClarityCameras.current?.error(this@MeasuredCameraStream, reason)
                listener?.onError(reason)
            }

            override fun onEnded() {
                ClarityCameras.current?.ended(this@MeasuredCameraStream)
                listener?.onEnded()
            }
        }
    }

    override fun start() {
        ClarityCameras.current?.started(this)
        inner.start()
    }

    override fun setActive(active: Boolean) = inner.setActive(active)

    override fun connectionRestored() = inner.connectionRestored()

    override fun setRendering(rendering: Boolean) = inner.setRendering(rendering)

    override val closeWhenHidden: Boolean get() = inner.closeWhenHidden

    override val surfaceReady: Boolean get() = inner.surfaceReady

    override fun close() {
        ClarityCameras.current?.closed(this)
        inner.close()
    }
}
