/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.ui

import android.content.Context
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import dev.glassworks.engine.vitro.VitroFailures
import dev.glassworks.engine.vitro.cards.TileCardView
import dev.glassworks.engine.vitro.model.AreaCardConfig
import dev.glassworks.engine.vitro.model.CardConfig
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A CSS grid with `grid-auto-flow: row` as Home Assistant's sections view and grid sections use it: [columns]
 * equal columns with [colGap] / [rowGap] between them, each child spanning [LayoutParams.span] columns (it wraps
 * to the next row when it doesn't fit), rows as tall as their tallest child. With [stretch] the children of a row
 * grow to the row's height (a grid section's cards); without it they keep their own (the sections themselves).
 * GONE children take no space, so hidden cards and sections leave no gap. [maxContentWidth] caps the content box,
 * which is then centered, like the view's `max-width` with `margin: 0 auto`.
 */
class HaGridLayout(
    context: Context,
    private val columns: Int,
    private val colGap: Int,
    private val rowGap: Int,
    private val stretch: Boolean,
) : ViewGroup(context) {
    var maxContentWidth: Int = Int.MAX_VALUE

    // CSS `overflow: visible`: a card's content may paint past its cell (the clock's digits do), so cells don't clip.
    init { clipChildren = false; clipToPadding = false }

    /** [span] columns; [fixedHeight] px for a card with a fixed number of grid rows, else its own height. */
    class LayoutParams(val span: Int, val fixedHeight: Int? = null) : ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

    private var rowOf = IntArray(0)
    private var colOf = IntArray(0)
    private var rowTop = IntArray(0)
    private var rowHeight = IntArray(0)
    private var contentLeft = 0
    private var colWidth = 0f

    /** The gap measuring and placing use: the configured one, or less when the gaps alone outgrow the content. */
    private var gap = 0f

    /**
     * A child threw in its measure, layout or draw past its own guards ([CardSurface]'s, or the [ContainedCard] round
     * every other card): the view swaps it for HA's error card, on the next message. Until then, and without a swap,
     * the grid leaves it out of its draw and its layout, so the other cards carry on. The last line: a card's own guard
     * catches first.
     */
    var onChildFailed: ((child: View) -> Unit)? = null

    private val failedChildren = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<View, Boolean>())
    private val main = Handler(Looper.getMainLooper())

    private fun childFailed(child: View, where: String, e: Exception) {
        if (!failedChildren.add(child)) return
        VitroFailures.report(where, (child as? CardSurface)?.cardType, e)
        val swap = onChildFailed ?: return
        main.post { if (child.parent === this) runCatching { swap(child) } }
    }

    /** [block] for [child], whose throw is contained ([childFailed]). */
    private inline fun guard(child: View, where: String, block: () -> Unit) {
        if (child in failedChildren) return
        try {
            block()
        } catch (e: Exception) {
            childFailed(child, where, e)
        }
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (child in failedChildren) return false
        val saved = canvas.saveCount
        return try {
            super.drawChild(canvas, child, drawingTime)
        } catch (e: Exception) {
            canvas.restoreToCount(saved)
            childFailed(child, "draw", e)
            false
        }
    }

    override fun onViewRemoved(child: View) {
        super.onViewRemoved(child)
        failedChildren.remove(child)
    }

    private fun span(child: View): Int = ((child.layoutParams as? LayoutParams)?.span ?: columns).coerceIn(1, columns)

    private fun cellWidth(col: Int, span: Int): Int {
        val left = (col * (colWidth + gap)).roundToInt()
        val right = ((col + span) * (colWidth + gap) - gap).roundToInt()
        return (right - left).coerceAtLeast(0)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val avail = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val content = min(avail, maxContentWidth)
        contentLeft = paddingLeft + (avail - content) / 2
        gap = if (columns > 1 && (columns - 1) * colGap > content) content.toFloat() / (columns - 1) else colGap.toFloat()
        colWidth = (content - (columns - 1) * gap).coerceAtLeast(0f) / columns
        val n = childCount
        rowOf = IntArray(n) { -1 }
        colOf = IntArray(n)
        val heights = ArrayList<Int>()
        var row = -1
        var col = columns
        for (i in 0 until n) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val s = span(child)
            if (col + s > columns) { row++; col = 0; heights += 0 }
            rowOf[i] = row
            colOf[i] = col
            col += s
            val lp = child.layoutParams as? LayoutParams
            val hs = lp?.fixedHeight?.let { MeasureSpec.makeMeasureSpec(it, MeasureSpec.EXACTLY) }
                ?: MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            guard(child, "measure") { child.measure(MeasureSpec.makeMeasureSpec(cellWidth(colOf[i], s), MeasureSpec.EXACTLY), hs) }
            heights[row] = max(heights[row], child.measuredHeight)
        }
        rowHeight = heights.toIntArray()
        if (stretch) {
            for (i in 0 until n) {
                val child = getChildAt(i)
                if (rowOf[i] < 0 || (child.layoutParams as? LayoutParams)?.fixedHeight != null) continue
                if (child.measuredHeight < rowHeight[rowOf[i]]) {
                    guard(child, "measure") {
                        child.measure(
                            MeasureSpec.makeMeasureSpec(child.measuredWidth, MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(rowHeight[rowOf[i]], MeasureSpec.EXACTLY),
                        )
                    }
                }
            }
        }
        rowTop = IntArray(rowHeight.size)
        var y = paddingTop
        for (r in rowHeight.indices) {
            rowTop[r] = y
            y += rowHeight[r] + if (r < rowHeight.lastIndex) rowGap else 0
        }
        // The parent's height constraint holds (EXACTLY, AT_MOST); UNSPECIFIED (a scroller) gets the whole content.
        setMeasuredDimension(width, resolveSize(y + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (i >= rowOf.size || rowOf[i] < 0 || child.visibility == GONE) continue
            val x = contentLeft + (colOf[i] * (colWidth + gap)).roundToInt()
            val y = rowTop[rowOf[i]]
            guard(child, "layout") { child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight) }
        }
    }

    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams = LayoutParams(columns)
    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p is LayoutParams
    override fun generateLayoutParams(p: ViewGroup.LayoutParams?): ViewGroup.LayoutParams = LayoutParams(columns)
}

/**
 * The size of a card in a grid section, as HA computes it (`hui-card.getGridOptions()`): the card's own
 * `grid_options` (or the old `layout_options`) over the defaults its type reports. [columns] is in 12ths of a
 * section column, null for `"full"`; [rows] is a fixed number of 56 px rows, or null for `auto`.
 */
data class HaGridSize(val columns: Int?, val rows: Int?) {
    companion object {
        /** [state] is the card's entity's (when it has one): some cards' rows depend on what it supports. */
        fun of(card: CardConfig, state: dev.glassworks.engine.vitro.data.EntityState? = null): HaGridSize {
            val raw = card.raw
            val default = defaults(card, state)
            // hui-card.getGridOptions: the card type's options ({columns, rows, min_columns, min_rows}), overridden key by
            // key by the config's: `grid_options`, else the old `layout_options` migrated (columns and their bounds x 3).
            val typeMin = minimums(card)
            val merged = HashMap<String, Any?>()
            merged["columns"] = default.columns ?: "full"
            merged["rows"] = default.rows ?: "auto"
            typeMin.columns?.let { merged["min_columns"] = it }
            typeMin.rows?.let { merged["min_rows"] = it }
            val g = raw.optJSONObject("grid_options")
            val legacy = raw.optJSONObject("layout_options")
            if (g != null) {
                for (k in listOf("columns", "rows", "min_columns", "max_columns", "min_rows", "max_rows")) if (g.has(k)) merged[k] = g.opt(k)
            } else if (legacy != null) {
                fun times3(v: Any?): Any? = (v as? Number)?.let { it.toDouble() * 3 } ?: v
                if (legacy.has("grid_columns")) merged["columns"] = times3(legacy.opt("grid_columns"))
                if (legacy.has("grid_max_columns")) merged["max_columns"] = times3(legacy.opt("grid_max_columns"))
                if (legacy.has("grid_min_columns")) merged["min_columns"] = times3(legacy.opt("grid_min_columns"))
                if (legacy.has("grid_rows")) merged["rows"] = legacy.opt("grid_rows")
                if (legacy.has("grid_max_rows")) merged["max_rows"] = legacy.opt("grid_max_rows")
                if (legacy.has("grid_min_rows")) merged["min_rows"] = legacy.opt("grid_min_rows")
            }
            fun bound(k: String): Int? = (merged[k] as? Number)?.toInt()
            val cols: Any? = merged["columns"]
            val rows: Any? = merged["rows"]
            // conditionalClamp: each bound only when it's set.
            val minCols = bound("min_columns")
            val maxCols = bound("max_columns")
            val minRows = bound("min_rows")
            val maxRows = bound("max_rows")
            val c = when (cols) {
                is Number -> cols.toInt().let { v -> v.coerceAtLeast(minCols ?: 1).let { maxCols?.let { m -> minOf(it, m) } ?: it } }.coerceIn(1, 48)
                else -> null
            }
            val r = when (rows) {
                is Number -> rows.toInt().let { v -> v.coerceAtLeast(minRows ?: 1).let { maxRows?.let { m -> minOf(it, m) } ?: it } }
                else -> null
            }
            return HaGridSize(c, r?.takeIf { it > 0 })
        }

        /** A state-control card's rows for its `features`: `Math.ceil(features.length * 2 / 3)`. */
        private fun featureRows(raw: org.json.JSONObject): Int = ((raw.optJSONArray("features")?.length() ?: 0) * 2 + 2) / 3

        /** The minimum size a card type reports from `getGridOptions()`: entity and sensor 6 x 2, the graphs 6 wide. */
        fun minimums(card: CardConfig): HaGridSize = when (card.type) {
            "entity", "sensor", "history-graph" -> HaGridSize(6, 2)
            "statistics-graph" -> HaGridSize(6, 3)
            "thermostat", "humidifier" -> HaGridSize(6, 2 + featureRows(card.raw))
            // hui-error-card: `min_columns: 6, min_rows: 1`.
            ErrorCardView.TYPE -> HaGridSize(6, 1)
            else -> HaGridSize(null, null)
        }

        /**
         * Whether a Mushroom card draws its row of controls (Mushroom's `hasControls`): an option that is on and that
         * [state]'s entity supports (a brightness control for a light that dims; with no state yet, any option on).
         */
        internal fun mushroomHasControls(type: String, raw: org.json.JSONObject, state: dev.glassworks.engine.vitro.data.EntityState? = null): Boolean = when (type) {
            "custom:mushroom-light-card" -> {
                val modes = (state?.attributes?.get("supported_color_modes") as? List<*>)?.map { it.toString() }
                (raw.optBoolean("show_brightness_control") && (modes == null || modes.any { it != "onoff" })) ||
                    (raw.optBoolean("show_color_temp_control") && (modes == null || "color_temp" in modes)) ||
                    (raw.optBoolean("show_color_control") && (modes == null || modes.any { it in setOf("hs", "rgb", "rgbw", "rgbww", "xy") }))
            }
            "custom:mushroom-fan-card" -> {
                // FanEntityFeature: SET_SPEED 1, OSCILLATE 2, DIRECTION 4.
                val f = (state?.attributes?.get("supported_features") as? Number)?.toInt()
                fun has(bit: Int) = f == null || f and bit != 0
                (raw.optBoolean("show_percentage_control") && has(1)) || (raw.optBoolean("show_oscillate_control") && has(2)) ||
                    (raw.optBoolean("show_direction_control") && has(4))
            }
            "custom:mushroom-cover-card" -> {
                // Mushroom 5.3.0's `_controls`: buttons for a cover with OPEN 1, CLOSE 2 or STOP 8, the position slider
                // with SET_POSITION 4, the tilt one with SET_TILT_POSITION 128 (none without supported_features).
                val f = if (state == null) null else (state.attributes["supported_features"] as? Number)?.toInt() ?: 0
                fun has(bits: Int) = f == null || f and bits != 0
                (raw.optBoolean("show_buttons_control") && has(1 or 2 or 8)) || (raw.optBoolean("show_position_control") && has(4)) ||
                    (raw.optBoolean("show_tilt_position_control") && has(128))
            }
            "custom:mushroom-climate-card" -> {
                // A temperature control with a target to show; the hvac_modes the entity has and the card lists.
                val target = state == null || state.attributes["temperature"] != null ||
                    (state.attributes["target_temp_low"] != null && state.attributes["target_temp_high"] != null)
                val listed = (0 until (raw.optJSONArray("hvac_modes")?.length() ?: 0)).map { raw.optJSONArray("hvac_modes")!!.optString(it) }
                val modes = (state?.attributes?.get("hvac_modes") as? List<*>)?.map { it.toString() }
                (raw.optBoolean("show_temperature_control") && target) || listed.any { modes == null || it in modes }
            }
            "custom:mushroom-humidifier-card" -> raw.optBoolean("show_target_humidity_control")
            "custom:mushroom-vacuum-card" -> (raw.optJSONArray("commands")?.length() ?: 0) > 0
            "custom:mushroom-media-player-card" -> (raw.optJSONArray("media_controls")?.length() ?: 0) > 0 || (raw.optJSONArray("volume_controls")?.length() ?: 0) > 0
            "custom:mushroom-number-card", "custom:mushroom-select-card", "custom:mushroom-lock-card" -> true
            // Mushroom's `hasControls`: `Boolean(config.states?.length)`; without `states` the card has no buttons.
            "custom:mushroom-alarm-control-panel-card" -> (raw.optJSONArray("states")?.length() ?: 0) > 0
            else -> false
        }

        /** What each card type reports from `getGridOptions()` (HA 2026.11, Mushroom 5). */
        fun defaults(card: CardConfig, state: dev.glassworks.engine.vitro.data.EntityState? = null): HaGridSize {
            val raw = card.raw
            return when (card.type) {
                "clock" -> {
                    val size = raw.optString("clock_size", "small")
                    if (raw.optString("clock_style") == "analog") {
                        HaGridSize(6, when (size) { "medium" -> 3; "large" -> 4; else -> 2 })
                    } else {
                        HaGridSize(6, when (size) { "medium", "large" -> 2; else -> 1 })
                    }
                }
                "tile" -> {
                    val features = raw.optJSONArray("features")?.length() ?: 0
                    val vertical = raw.optBoolean("vertical", false)
                    // Inline, the first feature sits beside the info; the others go below in up to two columns.
                    val inline = features > 0 && !vertical && raw.optString("features_position") == "inline"
                    val below = if (inline) features - 1 else features
                    val featureRows = if (inline) (below + 1) / 2 else features
                    HaGridSize(if (inline) 12 else 6, 1 + featureRows + if (vertical) 1 else 0)
                }
                // hui-error-card's getGridOptions: 6 columns, 1 row.
                ErrorCardView.TYPE -> HaGridSize(6, 1)
                // hui-entity-card's (and so hui-sensor-card's) getGridOptions: 6 columns, 2 rows.
                "entity", "sensor" -> HaGridSize(6, 2)
                // hui-logbook-card's and hui-calendar-card's getGridOptions: 12 columns, 6 rows.
                "logbook", "calendar" -> HaGridSize(12, 6)
                // hui-area-card's getGridOptions: 6 columns; one row for the tile, two more for the picture (not
                // compact), one more for a vertical compact tile. Its features aren't drawn yet, so they add none.
                "area" -> {
                    val compact = AreaCardConfig.displayType(raw) == "compact"
                    HaGridSize(6, 1 + (if (compact) 0 else 2) + (if (compact && raw.optBoolean("vertical", false)) 1 else 0))
                }
                // Measured in HA: the light card spans the section's width.
                "light" -> HaGridSize(12, null)
                // hui-thermostat-card's and hui-humidifier-card's getGridOptions: 12 columns, 5 rows (and two thirds of
                // a row per feature, rounded up).
                "thermostat", "humidifier" -> HaGridSize(12, 5 + featureRows(raw))
                // hui-button-card's getGridOptions: 6 columns, 2 rows (`entity-button` is the same card).
                "button", "entity-button" -> HaGridSize(6, 2)
                // hui-statistic-card: 6 columns, 2 rows.
                "statistic" -> HaGridSize(6, 2)
                // hui-date-card: one row small, two medium or large.
                "date" -> HaGridSize(6, if (raw.optString("date_size") in setOf("medium", "large")) 2 else 1)
                // hui-toggle-group-card, hui-shortcut-card, hui-alert-card: one row, two vertical.
                "toggle-group", "shortcut", "alert" -> HaGridSize(6, if (raw.optBoolean("vertical", false)) 2 else 1)
                // hui-iframe-card: the section's full width, 4 rows.
                "iframe" -> HaGridSize(12, 4)
                // hui-error-card (a `section` card, a config HA rejects): 6 columns, 1 row.
                "section" -> HaGridSize(6, 1)
                // button-card answers only with `section_mode`: 6 columns, 2 rows; otherwise the full width.
                "custom:button-card" -> if (raw.optBoolean("section_mode", false)) HaGridSize(6, 2) else HaGridSize(12, null)
                // Mushroom's empty card: 6 columns, 1 row.
                "custom:mushroom-empty-card" -> HaGridSize(6, 1)
                // Measured in HA: the picture cards span the section's full width at their image's height.
                "picture", "picture-entity", "picture-glance" -> HaGridSize(12, null)
                "heading" -> HaGridSize(null, null)
                // hui-weather-forecast-card's getGridOptions: one row, one for the current conditions, one for the
                // forecast and one more for a daily one.
                "weather-forecast" -> {
                    val current = raw.optBoolean("show_current", true)
                    val forecast = raw.optBoolean("show_forecast", true)
                    HaGridSize(12, 1 + (if (current) 1 else 0) + (if (forecast) 1 + (if (raw.optString("forecast_type") == "daily") 1 else 0) else 0))
                }
                "custom:mushroom-template-card" -> HaGridSize(6, TileCardView.mushroomTemplateGridRows(raw))
                "custom:mushroom-entity-card", "custom:mushroom-light-card", "custom:mushroom-fan-card",
                "custom:mushroom-cover-card", "custom:mushroom-person-card", "custom:mushroom-lock-card",
                "custom:mushroom-media-player-card", "custom:mushroom-climate-card", "custom:mushroom-vacuum-card",
                "custom:mushroom-humidifier-card", "custom:mushroom-number-card", "custom:mushroom-select-card",
                "custom:mushroom-alarm-control-panel-card" -> {
                    // Mushroom's getGridOptions: a row for the icon or info, one more for the controls (vertical: one
                    // each for the icon, the info and the controls); horizontal is one full-width row. Collapsible
                    // controls show only while the entity is active: the card's own height decides then.
                    val controls = mushroomHasControls(card.type, raw, state)
                    val collapsible = controls && raw.optBoolean("collapsible_controls", false)
                    when (raw.optString("layout")) {
                        "horizontal" -> HaGridSize(12, 1)
                        "vertical" -> HaGridSize(6, if (collapsible) null else 2 + if (controls) 1 else 0)
                        else -> HaGridSize(6, if (collapsible) null else 1 + if (controls) 1 else 0)
                    }
                }
                else -> HaGridSize(12, null)
            }
        }
    }
}
