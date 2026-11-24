/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.parity

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import com.sun.net.httpserver.HttpServer
import dev.glassworks.engine.vitro.cards.ClockCardView
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.ui.HaGridLayout
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroView
import org.json.JSONObject
import org.robolectric.Shadows.shadowOf
import java.io.Closeable
import java.io.File
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max

/** A text Vitro shows: the TextView, and its text with whitespace collapsed (with a map back to the original offsets). */
class VitroText(val view: TextView, val normalized: String, val offsets: IntArray)

/** One Vitro render of a case on a frame. Rectangles are CSS px in the page (the scrolled content). */
class VitroRender(
    val bitmap: Bitmap,
    val density: Float,
    val slots: List<CssRect>,
    val slotViews: List<View>,
    val root: View,
    val templatesAsked: List<String>,
    val templatesMissing: List<String>,
    val assetsMissing: List<String>,
) {
    /** The texts under slot [i]: every visible TextView with text. Canvas-drawn text is not here. */
    fun texts(i: Int): List<VitroText> = ParityViews.textViews(slotViews[i]).map { tv ->
        val raw = tv.text.toString()
        val sb = StringBuilder()
        val map = ArrayList<Int>()
        var lastSpace = true
        raw.forEachIndexed { idx, ch ->
            val space = ch.isWhitespace() || ch == ' '
            if (space) {
                if (!lastSpace) { sb.append(' '); map += idx }
            } else {
                sb.append(ch); map += idx
            }
            lastSpace = space
        }
        VitroText(tv, sb.toString().trimEnd(), map.toIntArray())
    }

    /** Where offset [o] of [tv]'s text sits (left, vertical center, CSS px, in the page) and its font size (CSS px). */
    fun textMetrics(tv: TextView, o: Int): Triple<Double, Double, Double> {
        val (vx, vy) = ParityViews.offsetIn(root, tv)
        var size = tv.textSize / density
        (tv.text as? Spanned)?.let { sp ->
            for (s in sp.getSpans(o, o + 1, AbsoluteSizeSpan::class.java)) size = if (s.dip) s.size.toFloat() else s.size / density
            for (s in sp.getSpans(o, o + 1, RelativeSizeSpan::class.java)) size *= s.sizeChange
        }
        val layout = tv.layout ?: return Triple(vx / density.toDouble(), vy / density.toDouble(), size.toDouble())
        val line = layout.getLineForOffset(o)
        val x = vx + tv.totalPaddingLeft + layout.getPrimaryHorizontal(o)
        val fm = tv.paint.fontMetrics
        val ratio = size * density / tv.textSize
        val center = vy + tv.totalPaddingTop + layout.getLineBaseline(line) + (fm.ascent + fm.descent) / 2f * ratio
        return Triple(x / density.toDouble(), center / density.toDouble(), size.toDouble())
    }
}

object ParityViews {
    fun all(v: View): Sequence<View> = sequence {
        yield(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(all(v.getChildAt(i)))
    }

    /** [v]'s top-left in [root]'s content, px (scrolling undone). */
    fun offsetIn(root: View, v: View): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        var cur: View? = v
        while (cur != null && cur !== root) {
            x += cur.left + cur.translationX
            y += cur.top + cur.translationY
            val parent = cur.parent as? View
            if (parent != null && parent !is ScrollView) {
                x -= parent.scrollX
                y -= parent.scrollY
            }
            cur = parent
        }
        return x to y
    }

    private fun shown(v: View, stop: View): Boolean {
        var cur: View? = v
        while (cur != null) {
            if (cur.visibility != View.VISIBLE || cur.alpha <= 0f) return false
            if (cur === stop) return true
            cur = cur.parent as? View
        }
        return true
    }

    fun textViews(slot: View): List<TextView> = all(slot).filterIsInstance<TextView>()
        .filter { it.text.isNotBlank() && it.width > 0 && it.height > 0 && shown(it, slot) }.toList()

    /**
     * The top-level card slots of a sections view, in order: each section's grid cells, like the HA side's
     * `hui-grid-section .container > .card`. Hidden cards (GONE) and a section's title are skipped; a pinned card
     * (in [pinned], VitroView's overlay, in order) takes its placeholder's place.
     */
    fun cardSlots(viewRoot: View, pinned: List<View> = emptyList()): List<View> {
        val grids = all(viewRoot).filterIsInstance<HaGridLayout>().toList()
        val sectionsGrid = grids.firstOrNull() ?: return emptyList()
        val out = ArrayList<View>()
        val pins = pinned.iterator()
        for (i in 0 until sectionsGrid.childCount) {
            val section = sectionsGrid.getChildAt(i)
            if (section.visibility != View.VISIBLE) continue
            val grid = all(section).filterIsInstance<HaGridLayout>().firstOrNull() ?: continue
            for (j in 0 until grid.childCount) {
                val c = grid.getChildAt(j)
                // A pinned card (card-mod `position: fixed`) leaves a bare placeholder here and draws in the overlay,
                // in the same order; a hidden one still takes its overlay entry.
                if (c.javaClass == View::class.java) {
                    val pin = if (pins.hasNext()) pins.next() else null
                    if (pin != null && c.visibility == View.VISIBLE && pin.visibility == View.VISIBLE && pin.width > 0 && pin.height > 0) out += pin
                    continue
                }
                if (c.visibility != View.VISIBLE) continue
                if (c.width == 0 || c.height == 0) continue
                if (c is TextView) continue // the section's title
                out += c
            }
        }
        return out
    }
}

/** Serves a corpus's assets/ folder on 127.0.0.1, as Home Assistant serves /static: what Vitro's image loader fetches. */
class FixtureHttp(private val assets: File) : Closeable {
    val missing = CopyOnWriteArrayList<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val path = ex.requestURI.path
            val f = File(assets, path.removePrefix("/")).canonicalFile
            if (f.isFile && f.path.startsWith(assets.canonicalPath)) {
                val bytes = f.readBytes()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            } else {
                missing += path
                ex.sendResponseHeaders(404, -1)
                ex.close()
            }
        }
        start()
    }
    val base: String get() = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)
}

/**
 * Draws a case in Vitro as the engine would: a [VitroView] fed the reference's entity states, HA's themes and
 * config, and the templates HA rendered, laid out at the frame's px size (taller when the content scrolls).
 */
class ParityRenderer(private val activity: Activity, private val frame: ParityFrame, private val root: File, private val shared: ParityShared?) {
    fun render(case: ParityCase, ref: ParityReference?): VitroRender {
        val store = EntityStore()
        ref?.states?.let { if (it.length() > 0) store.applyCompressedEvent(JSONObject().put("a", it)) }
        val templates = ref?.templates.orEmpty()
        val asked = CopyOnWriteArrayList<String>()
        val missing = CopyOnWriteArrayList<String>()
        val haConfig = shared?.haConfig ?: JSONObject()
        FixtureHttp(File(root, "assets")).use { http ->
            val view = VitroView(activity, store, Color.BLACK)
            view.contextFactory = { theme ->
                VitroCardContext(
                    context = activity,
                    haBase = http.base,
                    store = store,
                    theme = theme,
                    resources = emptyList(),
                    accessToken = { null },
                    haConfig = { haConfig },
                    haUser = { JSONObject().put("is_admin", true).put("name", "Harness") },
                    callService = { _, _, _, _ -> },
                    navigate = {},
                    openMoreInfo = {},
                    fetchHistory = { _, _, done -> done(emptyMap()) },
                    subscribeTemplate = { tpl, onRendered ->
                        if (tpl !in asked) asked += tpl
                        val r = templates[tpl]
                        if (r != null) onRendered(r) else if (tpl !in missing) missing += tpl
                        ({})
                    },
                )
            }
            shared?.themes?.let { view.setThemes(ThemeCatalog.parse(it)) }
            view.setUserTheme(null, case.dark)
            val dash = LovelaceParser.parseDashboard(case.dashboardJson(root))
            view.setDashboard(dash, case.view ?: case.id)
            activity.setContentView(view)
            val w = frame.pxWidth
            var h = frame.pxHeight
            layout(view, w, h)
            settle(view, ref)
            // Like a full-page screenshot: as tall as the content.
            val scroll = ParityViews.all(view).filterIsInstance<ScrollView>().firstOrNull { it.isShown && it.childCount > 0 }
            // HA's page may run longer than its cards (its view's bottom padding): lay Vitro out as tall, so the
            // extra is compared as page background, not as a missing strip. Card bounds still tell the two apart.
            val haPageH = ref?.json?.optJSONObject("docSize")?.optDouble("height")?.let { Math.round(it * frame.density).toInt() } ?: 0
            val contentH = max(scroll?.getChildAt(0)?.height ?: h, haPageH)
            if (contentH > h) {
                h = contentH
                layout(view, w, h)
                settle(view, ref)
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            // What shows through where Vitro paints nothing (a radial backdrop's transparent stops): the window
            // behind the engine, black like the kiosk's.
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.BLACK)
            view.draw(canvas)
            val content = scroll?.getChildAt(0) ?: view
            val overlay = (0 until view.overlayLayer.childCount).map { view.overlayLayer.getChildAt(it) }.firstOrNull { it.visibility == View.VISIBLE }
            val pinned = (overlay as? ViewGroup)?.let { g -> (0 until g.childCount).map { g.getChildAt(it) } }.orEmpty()
            val slotViews = ParityViews.cardSlots(content, pinned)
            val slots = slotViews.map { s ->
                val (x, y) = ParityViews.offsetIn(view, s)
                CssRect(x / frame.density.toDouble(), y / frame.density.toDouble(), s.width / frame.density.toDouble(), s.height / frame.density.toDouble())
            }
            return VitroRender(bmp, frame.density, slots, slotViews, view, asked.toList(), missing.toList(), http.missing.toList())
        }
    }

    private fun layout(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    /** Runs the main looper (animations end, posted work runs) and gives image loads on the pool time to land. */
    private fun settle(view: View, ref: ParityReference?) {
        repeat(8) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            Thread.sleep(25)
            view.measure(View.MeasureSpec.makeMeasureSpec(view.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(view.height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, view.width, view.height)
        }
        // The clock shows the instant HA's page was frozen at (refs/*.json "now").
        ref?.let { r -> ParityViews.all(view).filterIsInstance<ClockCardView>().forEach { it.renderAt(Date(r.now)) } }
        shadowOf(Looper.getMainLooper()).idle()
        view.measure(View.MeasureSpec.makeMeasureSpec(view.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(max(1, view.height), View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.width, view.height)
    }
}
