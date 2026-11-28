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
import dev.glassworks.engine.vitro.cards.GraphWork
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.data.HaLocale
import dev.glassworks.engine.vitro.data.HaText
import dev.glassworks.engine.vitro.fonts.WebFonts
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
    /** History or statistics calls Vitro made that HA's page didn't (ParityWs.canonical form). */
    val wsUnmatched: List<String> = emptyList(),
) {
    /**
     * The texts slot [i] draws on a canvas (a thermostat dial, a gauge), from the content description those views set
     * for screen readers: whitespace collapsed. They have no position or size to compare, only presence.
     */
    fun describedTexts(i: Int): List<String> = ParityViews.describedViews(slotViews[i])
        .map { it.contentDescription.toString().replace(Regex("\\s+"), " ").trim() }

    /** The texts under slot [i]: every visible TextView with text. Canvas-drawn text is in [describedTexts]. */
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
    /** Whether any char of [tv]'s text in [start, end) falls in a range its layout replaces with an ellipsis. */
    fun elided(tv: TextView, start: Int, end: Int): Boolean {
        val layout = tv.layout ?: return false
        for (line in 0 until layout.lineCount) {
            val count = layout.getEllipsisCount(line)
            if (count <= 0) continue
            val from = layout.getLineStart(line) + layout.getEllipsisStart(line)
            if (start < from + count && end > from) return true
        }
        return false
    }

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

    /** The views that draw their own text, described for screen readers: shown leaves that aren't TextViews. */
    fun describedViews(slot: View): List<View> = all(slot)
        .filter { it !is TextView && it !is android.view.ViewGroup && !it.contentDescription.isNullOrBlank() }
        .filter { it.width > 0 && it.height > 0 && shown(it, slot) }.toList()

    /**
     * The top-level card slots of a sections view, in order: each section's grid cells, like the HA side's
     * `hui-grid-section .container > .card`. Hidden cards (GONE) and a section's title are skipped; a pinned card
     * (in [pinned], VitroView's overlay, in order) takes its placeholder's place.
     */
    fun cardSlots(viewRoot: View, pinned: List<View> = emptyList()): List<View> {
        otherViewSlots(viewRoot)?.let { return it }
        val grids = all(viewRoot).filterIsInstance<HaGridLayout>().toList()
        val sectionsGrid = grids.firstOrNull() ?: return emptyList()
        val out = ArrayList<View>()
        val pins = pinned.iterator()
        for (i in 0 until sectionsGrid.childCount) {
            val section = sectionsGrid.getChildAt(i)
            val grid = all(section).filterIsInstance<HaGridLayout>().firstOrNull() ?: continue
            if (section.visibility != View.VISIBLE) {
                // Hidden: nothing of it is reported, but its pinned cards still take their overlay entries, so a
                // later section's placeholder pairs with its own pin.
                for (j in 0 until grid.childCount) if (grid.getChildAt(j).javaClass == View::class.java && pins.hasNext()) pins.next()
                continue
            }
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

/**
 * The top-level cards of a masonry, sidebar or layout-card view, in HA's document order (each column's cards, the
 * columns in order; a layout-card grid's rows), or null for a sections view.
 */
private fun otherViewSlots(viewRoot: View): List<View>? {
    fun kids(g: android.view.ViewGroup) = (0 until g.childCount).map { g.getChildAt(it) }
    fun shown(v: View) = v.visibility == View.VISIBLE && v.width > 0 && v.height > 0 && v !is android.widget.Space
    val views = ParityViews.all(viewRoot)
    views.filterIsInstance<dev.glassworks.engine.vitro.ui.MasonryColumnsLayout>().firstOrNull()?.let { row ->
        return kids(row).flatMap { col -> kids(col as android.view.ViewGroup) }.filter(::shown)
    }
    views.filterIsInstance<dev.glassworks.engine.vitro.ui.SidebarColumnsLayout>().firstOrNull()?.let { row ->
        return kids(row).flatMap { col -> kids(col as android.view.ViewGroup) }.filter(::shown)
    }
    // Under 760 px: one column, the cards in config order.
    views.filterIsInstance<dev.glassworks.engine.vitro.ui.SidebarStackLayout>().firstOrNull()?.let { stack ->
        return kids(stack).flatMap { col -> kids(col as android.view.ViewGroup) }.filter(::shown)
    }
    views.filterIsInstance<dev.glassworks.engine.vitro.cards.LayoutCardView>().firstOrNull()
        ?.takeIf { it.parent is android.widget.ScrollView }
        ?.let { grid -> return grid.tracks.flatMap { kids(it) }.filter(::shown) }
    return null
}

/**
 * Serves a corpus's assets/ folder on 127.0.0.1, as Home Assistant serves /static: what Vitro's image loader fetches.
 * Paths under [ParityCase.FIXTURE_PREFIX] come from [fixtures] instead (a case's own stylesheets and fonts).
 */
class FixtureHttp(
    private val assets: File,
    /** Paths HA's page couldn't load (the reference's imagesHaMissing): never served, even if a file is there. */
    private val haMissing: Set<String> = emptySet(),
    private val fixtures: File? = null,
) : Closeable {
    val missing = CopyOnWriteArrayList<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            // As the capture saved it: `%20` stays in the file name (and in imagesHaMissing).
            val path = ex.requestURI.rawPath
            val fixture = fixtures != null && path.startsWith(ParityCase.FIXTURE_PREFIX)
            val dir = if (fixture) fixtures!! else assets
            val rel = if (fixture) path.removePrefix(ParityCase.FIXTURE_PREFIX) else path.removePrefix("/")
            val f = File(dir, rel).canonicalFile
            // Contained by path component, not by string prefix: `assets-other/x` isn't inside `assets`.
            if (path !in haMissing && f.isFile && f.toPath().startsWith(dir.canonicalFile.toPath())) {
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
    companion object {
        /** How long a case's images may take to load from the fixture server before the case fails. */
        const val IMAGE_TIMEOUT_MS = 10_000L
    }

    fun render(case: ParityCase, ref: ParityReference?): VitroRender {
        // HaDisplay and HaLocale are process-wide: hold the case's display data and its user's language and formats
        // while it renders, and nothing after. The browser's own locale and zone are the harness's (en-US, HA's zone),
        // as capture.mjs gives Chromium.
        HaDisplay.reset()
        HaLocale.reset()
        try {
            // Inside the try: a setup that fails halfway (a bad `requested.domains`) is cleared too.
            shared?.haConfig?.let(HaLocale::applyConfig)
            ref?.json?.optJSONObject("locale")?.let(HaLocale::applyUserData)
            // HA's frontend strings in the case's language, as HaFrontend installs them from HA (the tests' copy of
            // the pinned frontend's; English is the classpath's).
            HaLocale.current.language.takeIf { it != "en" }?.let { lang ->
                javaClass.getResourceAsStream("/dev/glassworks/engine/vitro/locale/strings-$lang.json")?.use { s ->
                    val o = JSONObject(s.readBytes().toString(Charsets.UTF_8))
                    HaText.install(lang, o.keys().asSequence().associateWith { o.getString(it) })
                }
            }
            ref?.display?.let(::applyDisplay)
            return renderWithDisplay(case, ref)
        } finally {
            HaDisplay.reset()
            HaLocale.reset()
            HaText.reset()
        }
    }

    /**
     * Feeds the reference's `display` block to HaDisplay the way HaDashboardClient feeds it HA's answers: the
     * registry (`list_for_display`), the devices, then the icons and state labels of the requested domains and
     * integrations.
     */
    private fun applyDisplay(d: JSONObject) {
        HaDisplay.applyRegistry(JSONObject().put("entities", d.optJSONArray("entities") ?: org.json.JSONArray()))
        d.optJSONArray("devices")?.let(HaDisplay::applyDevices)
        d.optJSONArray("areas")?.let(HaDisplay::applyAreas)
        val requested = d.optJSONObject("requested") ?: JSONObject()
        fun names(key: String) = requested.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val domains = names("domains")
        val platforms = names("platforms")
        val icons = d.optJSONObject("icons") ?: JSONObject()
        val labels = d.optJSONObject("translations") ?: JSONObject()
        HaDisplay.applyIcons(JSONObject().put("resources", icons.optJSONObject("entity_component") ?: JSONObject()), true, domains)
        HaDisplay.applyIcons(JSONObject().put("resources", icons.optJSONObject("entity") ?: JSONObject()), false, platforms)
        HaDisplay.applyTranslations(JSONObject().put("resources", labels.optJSONObject("entity_component") ?: JSONObject()), true, domains)
        HaDisplay.applyTranslations(JSONObject().put("resources", labels.optJSONObject("entity") ?: JSONObject()), false, platforms)
    }

    private fun renderWithDisplay(case: ParityCase, ref: ParityReference?): VitroRender {
        // Each reference is a fresh HA page: its timeline's generic state colors start over.
        dev.glassworks.engine.vitro.cards.HaTimelineChart.resetGenericColors()
        val store = EntityStore()
        ref?.states?.let { if (it.length() > 0) store.applyCompressedEvent(JSONObject().put("a", it)) }
        // A reference's states are every one HA's page held for the case's entities: one it had none for doesn't exist
        // in HA, as the first event of the view's subscription tells Vitro on a panel (a tile's "Entity not found").
        // Without a reference nothing is known, and nothing is missing.
        if (ref != null) store.markSnapshot(emptySet())
        val templates = ref?.templates.orEmpty()
        val asked = CopyOnWriteArrayList<String>()
        val missing = CopyOnWriteArrayList<String>()
        val haConfig = shared?.haConfig ?: JSONObject()
        val ws = ParityWs(ref?.ws)
        val main = android.os.Handler(Looper.getMainLooper())
        FixtureHttp(File(root, "assets"), ref?.imagesHaMissing.orEmpty(), File(root, "fixtures")).use { http ->
            val view = VitroView(activity, store, Color.BLACK)
            view.cardLayers = VitroView.CardLayers.forTotalMem(frame.memTotalKb * 1024)
            view.contextFactory = { theme ->
                VitroCardContext(
                    context = activity,
                    haBase = http.base,
                    store = store,
                    theme = theme,
                    resources = case.resources,
                    accessToken = { null },
                    haConfig = { haConfig },
                    // The user HA's page drew for: an administrator, unless the reference says it wasn't one.
                    haUser = { JSONObject().put("is_admin", ref?.json?.optJSONObject("user")?.optBoolean("is_admin", true) ?: true).put("name", "Harness") },
                    callService = { _, _, _, _ -> },
                    navigate = {},
                    openMoreInfo = {},
                    // HA's REST history, as mini-graph-card read it in HA's page (`rest/history/period` in the reference).
                    fetchHistory = { ids, hours, done -> ws.restHistory(ids, hours, ref?.now ?: System.currentTimeMillis()) { main.post { done(it) } } },
                    callWs = { msg, done -> ws.call(msg) { ok, result, error -> main.post { done(ok, result, error) } } },
                    subscribeWs = { msg, onEvent, onError -> ws.subscribe(msg, { main.post(it) }, onEvent, onError) },
                    // HaDashboardClient's held forecast, answered from HA's own `weather/subscribe_forecast`.
                    subscribeForecast = { entityId, type, onForecast ->
                        val msg = JSONObject().put("type", "weather/subscribe_forecast").put("entity_id", entityId).put("forecast_type", type)
                        ws.subscribe(msg, { main.post(it) }, { ev ->
                            ev.optJSONArray("forecast")?.let { onForecast(dev.glassworks.engine.vitro.data.ForecastDay.parseList(it)) }
                        })
                    },
                    // HA's page drew at the pinned instant (refs/*.json "now"): the graphs' time windows end there too.
                    now = { ref?.now ?: System.currentTimeMillis() },
                    // The pointer HA's page had: coarse when the capture emulated the frame's touch screen.
                    touch = ref?.json?.optJSONObject("viewport")?.optBoolean("touch", false) ?: false,
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
            // A panel's window shows: what waits for it (a live camera's still) starts, as on the panel.
            dev.glassworks.engine.vitro.showWindow(activity.window.decorView)
            val w = frame.pxWidth
            var h = frame.pxHeight
            layout(view, w, h)
            settle(view, ref, w, h)
            // A More Info case (#885): the sheet opens over the view, as a tap on the card opens it, at the panel's size
            // (HA's dialog is shot in the window as it shows it). Its surface is the one slot.
            case.moreInfo?.let { id ->
                // HA's dialog is captured with `prefers-reduced-motion: reduce` (capture.mjs): Android's equivalent,
                // "Remove animations", while the sheet draws (a vacuum's robot stands still, as HA's does).
                val resolver = activity.contentResolver
                val scale = android.provider.Settings.Global.getFloat(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
                android.provider.Settings.Global.putFloat(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
                try {
                    view.openMoreInfo(id)
                    settle(view, ref, w, h)
                } finally {
                    android.provider.Settings.Global.putFloat(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, scale)
                }
                val sheet = view.openSheet ?: error("${case.id}: the More Info sheet didn't open")
                val surface = sheet.surface
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                Canvas(bmp).apply { drawColor(Color.BLACK) }.also { view.draw(it) }
                val (x, y) = ParityViews.offsetIn(view, surface)
                val d = frame.density.toDouble()
                val slot = CssRect(x / d, y / d, surface.width / d, surface.height / d)
                return VitroRender(bmp, frame.density, listOf(slot), listOf(surface), view, asked.toList(), missing.toList(), http.missing.toList(), ws.unmatched.toList())
            }
            // Like a full-page screenshot: as tall as the content.
            val scroll = ParityViews.all(view).filterIsInstance<ScrollView>().firstOrNull { it.isShown && it.childCount > 0 }
            // HA's page may run longer than its cards (its view's bottom padding): lay Vitro out as tall, so the
            // extra is compared as page background, not as a missing strip. Card bounds still tell the two apart.
            val haPageH = ref?.json?.optJSONObject("docSize")?.optDouble("height")?.let { Math.round(it * frame.density).toInt() } ?: 0
            val contentH = max(scroll?.getChildAt(0)?.height ?: h, haPageH)
            if (contentH > h) {
                h = contentH
                layout(view, w, h)
                settle(view, ref, w, h)
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
            return VitroRender(bmp, frame.density, slots, slotViews, view, asked.toList(), missing.toList(), http.missing.toList(), ws.unmatched.toList())
        }
    }

    /**
     * Lays [v] out at [w] x [h]. A render taller than the panel (a full page, like HA's screenshot) keeps the pinned
     * cards' overlay at the panel's own size, as on the device: a `position: fixed` card stays anchored to the
     * panel's edges (HA's page is captured at scroll 0, where its fixed cards sit too), not to the content's.
     */
    private fun layout(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val overlay = (v as? VitroView)?.overlayLayer ?: return
        val panelH = minOf(h, frame.pxHeight)
        overlay.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(panelH, View.MeasureSpec.EXACTLY))
        overlay.layout(0, 0, w, panelH)
    }

    /**
     * Runs the main looper (animations end, posted work runs), then keeps running it until every image load has
     * reached its card, or fails the case after [IMAGE_TIMEOUT_MS].
     */
    private fun step(view: View) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        Thread.sleep(25)
        layout(view, view.width, view.height)
    }

    /**
     * Runs the case until it has settled, then lays [view] out at [w] x [h] again: the window's own pass, which runs
     * with the looper, sizes it to the activity's content area (the panel less Robolectric's system bars), and drawn
     * at that height the page's bottom would stay black. The kiosk has no system bars.
     */
    private fun settle(view: View, ref: ParityReference?, w: Int, h: Int) {
        repeat(8) { step(view) }
        // Images load on a pool and reach their card through the main looper: run it until none is in flight, so a
        // late image can't leave a card drawn without its picture.
        val deadline = System.nanoTime() + IMAGE_TIMEOUT_MS * 1_000_000L
        // Web fonts too: the view draws again in a font once it has arrived, as HA's page swaps it in. And graph
        // data, which statistics charts build and down-sample off the main thread. And a masonry view's cards, which
        // a slow build adds a slice per frame (VitroView.SlicedBuild): the shot is of the finished view.
        while (VitroCardContext.pendingImageLoads.get() > 0 || WebFonts.pending.get() > 0 || GraphWork.pending.get() > 0 ||
            (view as? VitroView)?.buildInProgress == true
        ) {
            if (System.nanoTime() > deadline) {
                throw IllegalStateException(
                    "${VitroCardContext.pendingImageLoads.get()} image, ${WebFonts.pending.get()} font and ${GraphWork.pending.get()} graph job(s) still pending after $IMAGE_TIMEOUT_MS ms" +
                        (if ((view as? VitroView)?.buildInProgress == true) "; the masonry build still adding cards" else "") +
                        (if (WebFonts.pending.get() > 0) "; fonts: ${WebFonts.describe()}" else ""),
                )
            }
            step(view)
        }
        // The clock shows the instant HA's page was frozen at (refs/*.json "now").
        ref?.let { r -> ParityViews.all(view).filterIsInstance<ClockCardView>().forEach { it.renderAt(Date(r.now)) } }
        shadowOf(Looper.getMainLooper()).idle()
        layout(view, w, h)
    }
}
