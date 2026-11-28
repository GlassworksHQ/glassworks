/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.data.HaText
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.VitroCardContext
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * `more-info-vacuum` (HA 20261028.5): the state header (the vacuum's `status` when it reports one, the device's
 * battery at the time row's end), `ha-state-control-vacuum-status`'s robot, the command buttons the vacuum's
 * `supported_features` allow (start/pause, stop, return home, locate, clean spot) and the fan speed menu. Cleaning by
 * area is a view of its own the sheet doesn't draw: such a vacuum's dialog is HA's ([MoreInfoRoute]).
 */
internal class MoreInfoVacuumView(private val ctx: VitroCardContext, private val entityId: String) : LinearLayout(ctx.context) {
    private val header = MoreInfoStateHeader(ctx)
    private val batteryText = cssText(ctx, 14f, ctx.theme.secondaryTextColor).apply { maxLines = 1 }
    private val batteryIcon = MdiIconView(ctx.context)
    private val battery = LinearLayout(ctx.context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(batteryText, LayoutParams(WRAP_CONTENT, ctx.px(18f)).apply { marginEnd = ctx.px(4f) })
        addView(batteryIcon, LayoutParams(ctx.px(18f), ctx.px(18f)))
        batteryText.gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
    }
    private val status = VacuumStatusView(ctx)
    private val startPause = HaControlButton(ctx, "mdi:play", "", 12f)
    private val stop = HaControlButton(ctx, "mdi:stop", HaText.ui("ui.dialogs.more_info_control.vacuum.stop"), 12f)
    private val home = HaControlButton(ctx, "mdi:home-import-outline", HaText.ui("ui.dialogs.more_info_control.vacuum.return_home"), 12f)
    private val locate = HaControlButton(ctx, "mdi:map-marker", HaText.ui("ui.dialogs.more_info_control.vacuum.locate"), 12f)
    private val spot = HaControlButton(ctx, "mdi:target-variant", HaText.ui("ui.dialogs.more_info_control.vacuum.clean_spot"), 12f)
    private val group = LinearLayout(ctx.context).apply { orientation = HORIZONTAL }
    private val buttons = LinearLayout(ctx.context).apply { gravity = Gravity.CENTER }
    private val fanSpeed = HaControlSelectMenu(ctx, "mdi:fan", "")
    private val selects = LinearLayout(ctx.context).apply { orientation = HORIZONTAL }
    private var built: Int? = null

    init {
        orientation = VERTICAL
        header.setAfterTime(battery)
        addView(header, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(status, LayoutParams(ctx.px(200f), ctx.px(200f)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        // .buttons: 8 px around the group, 12 px under the row.
        buttons.addView(group, LayoutParams(WRAP_CONTENT, ctx.px(48f)).apply { setMargins(ctx.px(8f), ctx.px(8f), ctx.px(8f), ctx.px(8f)) })
        addView(buttons, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = ctx.px(12f) })
        startPause.setOnClickListener { startOrPause() }
        stop.setOnClickListener { call("stop") }
        home.setOnClickListener { call("return_to_base") }
        locate.setOnClickListener { call("locate") }
        spot.setOnClickListener { call("clean_spot") }
        fanSpeed.onPick = { speed ->
            val s = ctx.store[entityId]
            if (s?.attributes?.get("fan_speed") != speed) {
                ctx.callService("vacuum", "set_fan_speed", JSONObject().put("entity_id", entityId).put("fan_speed", speed), null)
            }
        }
        selects.addView(fanSpeed, LayoutParams(WRAP_CONTENT, ctx.px(48f)))
        addView(selectContainer(ctx, selects))
    }

    private fun call(service: String) {
        ctx.callService("vacuum", service, JSONObject().put("entity_id", entityId), null)
    }

    /** `_handleStartPause`: the legacy `start_pause`, else pause while cleaning and start otherwise. */
    private fun startOrPause() {
        val s = ctx.store[entityId] ?: return
        if (!s.supports(VacuumFeatures.STATE) && !s.supports(VacuumFeatures.START) && s.supports(VacuumFeatures.PAUSE)) {
            call("start_pause")
            return
        }
        call(if (isCleaning(s)) "pause" else "start")
    }

    fun update(s: EntityState?) {
        val features = (s?.attributes?.get("supported_features") as? Number)?.toInt() ?: 0
        if (built != features) {
            built = features
            layoutButtons(s)
        }
        val statusText = s?.takeIf { it.supports(VacuumFeatures.STATUS) }?.attributes?.get("status")?.toString()?.takeIf { it.isNotEmpty() }
            ?.let { HaDisplay.attributeLabel(s, "status", it) ?: it }
        header.bind(s, MoreInfoStateHeader.stateOf(s, ctx.now(), statusText))
        bindBattery()
        status.bind(s)
        val unavailable = s?.state == "unavailable"
        // Start/pause: HA's icon, label and when it's disabled.
        val legacy = s != null && !s.supports(VacuumFeatures.STATE) && !s.supports(VacuumFeatures.START)
        val pausing = s != null && isCleaning(s) && s.supports(VacuumFeatures.PAUSE)
        startPause.setIcon(if (legacy) "mdi:play-pause" else if (pausing) "mdi:pause" else "mdi:play")
        startPause.setLabel(HaText.ui(
            if (legacy) "ui.dialogs.more_info_control.vacuum.start_pause"
            else if (pausing) "ui.dialogs.more_info_control.vacuum.pause"
            else "ui.dialogs.more_info_control.vacuum.start",
        ))
        // `_startPauseDisabled`: legacy or cleaning, never; else not when `canStart` fails, which is unavailable alone.
        startPause.isEnabled = s != null && !unavailable
        stop.isEnabled = s != null && !unavailable && s.state !in setOf("docked", "off", "idle")
        home.isEnabled = s != null && !unavailable && s.state != "returning"
        locate.isEnabled = !unavailable
        spot.isEnabled = !unavailable
        // The fan speed menu: only with FAN_SPEED and a list to pick from.
        val speeds = MoreInfoSheetLists.list(s, "fan_speed_list")
        val showFan = s.supports(VacuumFeatures.FAN_SPEED) && s?.attributes?.containsKey("fan_speed_list") == true
        fanSpeed.visibility = if (showFan) VISIBLE else GONE
        (selects.parent as View).visibility = if (showFan) VISIBLE else GONE
        if (showFan && s != null) {
            fanSpeed.setLabel(HaDisplay.attributeName(s, "fan_speed"))
            fanSpeed.bind(speeds.map { it to (HaDisplay.attributeLabel(s, "fan_speed", it) ?: it) }, s.attributes["fan_speed"]?.toString(), !unavailable)
        }
        // .controls:not(:last-child) leaves 24 px above the menu, which only shows with one.
        (buttons.layoutParams as LayoutParams).bottomMargin = ctx.px(if (showFan) 12f + 24f else 12f)
    }

    /** The buttons the vacuum supports, 12 px apart; none at all, and the status is the controls' last child. */
    private fun layoutButtons(s: EntityState?) {
        group.removeAllViews()
        val shown = listOfNotNull(
            startPause.takeIf { s.supports(VacuumFeatures.START) || s.supports(VacuumFeatures.PAUSE) },
            stop.takeIf { s.supports(VacuumFeatures.STOP) },
            home.takeIf { s.supports(VacuumFeatures.RETURN_HOME) },
            locate.takeIf { s.supports(VacuumFeatures.LOCATE) },
            spot.takeIf { s.supports(VacuumFeatures.CLEAN_SPOT) },
        )
        shown.forEachIndexed { i, b ->
            group.addView(b, LayoutParams(ctx.px(48f), ctx.px(48f)).apply { if (i > 0) marginStart = ctx.px(12f) })
        }
        buttons.visibility = if (shown.isEmpty()) GONE else VISIBLE
        // `.controls > *:not(:last-child)`: 24 px under the status when the buttons follow it.
        (status.layoutParams as LayoutParams).bottomMargin = ctx.px(if (shown.isEmpty()) 0f else 24f)
    }

    /**
     * The device's battery (`findBatteryEntity`: its first sensor or binary sensor with the `battery` device class),
     * its level in percent unless it's a binary sensor, and `ha-battery-icon` (charging from the device's
     * `battery_charging` binary sensor).
     */
    private fun bindBattery() {
        val device = HaDisplay.registry[entityId]?.deviceId
        val siblings = if (device == null) emptyList() else HaDisplay.registry.filterValues { it.deviceId == device }.keys.toList()
        val level = siblings.firstOrNull { id ->
            val st = ctx.store[id]
            st != null && st.deviceClass == "battery" && st.domain in setOf("sensor", "binary_sensor")
        }?.let { ctx.store[it] }
        if (level == null || (level.domain != "binary_sensor" && level.state.toDoubleOrNull() == null)) {
            battery.visibility = GONE
            return
        }
        val charging = siblings.firstOrNull { ctx.store[it]?.deviceClass == "battery_charging" }?.let { ctx.store[it] }
        battery.visibility = VISIBLE
        if (level.domain == "binary_sensor") {
            batteryText.visibility = GONE
        } else {
            batteryText.visibility = VISIBLE
            batteryText.text = "${level.state.toDouble().roundToInt()}%"
        }
        batteryIcon.setIcon(batteryIconName(level.state, charging?.state == "on"), ctx.theme.secondaryTextColor)
    }

    companion object {
        fun isCleaning(s: EntityState): Boolean = s.state == "cleaning" || s.state == "on"

        /** HA's `batteryLevelIcon(level, charging)`. */
        fun batteryIconName(level: String, charging: Boolean): String {
            val v = level.toDoubleOrNull() ?: return when (level) {
                "off" -> "mdi:battery"
                "on" -> "mdi:battery-alert"
                else -> "mdi:battery-unknown"
            }
            val round = (Math.round(v / 10) * 10).toInt().coerceIn(0, 100)
            if (charging && v >= 10) return if (round >= 100) "mdi:battery-charging" else "mdi:battery-charging-$round"
            if (charging) return "mdi:battery-charging-outline"
            if (v <= 5) return "mdi:battery-alert-variant-outline"
            return if (round >= 100) "mdi:battery" else "mdi:battery-$round"
        }
    }
}

/** `listAttr` for the sheet's parts outside [MoreInfoSheet]. */
internal object MoreInfoSheetLists {
    fun list(s: EntityState?, key: String): List<String> = when (val v = s?.attributes?.get(key)) {
        is List<*> -> v.mapNotNull { it?.toString() }
        is org.json.JSONArray -> (0 until v.length()).map { v.optString(it) }
        else -> emptyList()
    }
}

/**
 * `ha-more-info-control-select-container` around [row]: centred and wrapping (at most 450 px wide) where HA's media
 * query holds (`(hover: hover)`, or a window at least 600 × 501 px), else one row that scrolls sideways from the start,
 * running 24 px past the content's sides.
 */
internal fun selectContainer(ctx: VitroCardContext, row: LinearLayout): View {
    val cfg = ctx.context.resources.configuration
    val centred = !ctx.touch || (cfg.screenWidthDp >= 600 && cfg.screenHeightDp >= 501)
    row.gravity = if (centred) Gravity.CENTER_HORIZONTAL else Gravity.START
    if (centred) {
        return LinearLayout(ctx.context).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(row, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
    }
    return HorizontalScrollView(ctx.context).apply {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        setPadding(ctx.px(24f), ctx.px(2f), ctx.px(24f), ctx.px(2f))
        addView(row, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    }.also { scroll ->
        // margin: -2px -24px.
        scroll.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(-ctx.px(24f), -ctx.px(2f), -ctx.px(24f), -ctx.px(2f))
        }
    }
}

/**
 * `ha-state-control-vacuum-status`: HA's 200 px robot (a 240-unit SVG) in the vacuum's state color, posed for its
 * state: cleaning (wandering, brush spinning, dust drawn in), returning (turned round, an arrow under it), docked (on
 * its dock, glowing), error (a pulsing glow) or idle (faded). Animated as HA's CSS animates it, or still where
 * animations are off (Android's "Remove animations", as `prefers-reduced-motion: reduce` stops HA's).
 */
internal class VacuumStatusView(private val ctx: VitroCardContext) : View(ctx.context) {
    private enum class Pose { CLEANING, DOCKED, RETURNING, PAUSED, ERROR, IDLE }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pose = Pose.IDLE
    private var color = ctx.theme.stateInactiveColor
    private val surface = ThemeResolver.parseColor(ctx.theme.vars["card-background-color"]) ?: ctx.theme.cardBackgroundColor
    private var startMs = SystemClock.uptimeMillis()
    /** `M 60 94 A 68 68 0 0 1 180 94`: the short arc over the top, centred at (120, 126). */
    private val bumper = Path().apply {
        val half = Math.toDegrees(kotlin.math.atan2(32.0, 60.0)).toFloat()
        arcTo(RectF(52f, 58f, 188f, 194f), 180f + half, 180f - 2 * half, true)
    }

    fun bind(s: EntityState?) {
        val next = when {
            s == null || s.state == "unavailable" -> Pose.IDLE
            s.state == "error" -> Pose.ERROR
            MoreInfoVacuumView.isCleaning(s) -> Pose.CLEANING
            s.state == "returning" -> Pose.RETURNING
            s.state == "paused" -> Pose.PAUSED
            s.state == "docked" -> Pose.DOCKED
            else -> Pose.IDLE
        }
        // `stateColorCss`, else `--state-inactive-color`.
        val c = s?.let { HaStateColor.of(ctx, it) } ?: ctx.theme.stateInactiveColor
        if (next != pose) startMs = SystemClock.uptimeMillis()
        if (next != pose || c != color) {
            pose = next
            color = c
            invalidate()
        }
    }

    /** Animations run: not with Android's "Remove animations" (the reduced-motion setting). */
    private fun animating(): Boolean = runCatching {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }.getOrDefault(true) && pose in setOf(Pose.CLEANING, Pose.RETURNING, Pose.DOCKED, Pose.ERROR)

    override fun onDraw(canvas: Canvas) {
        val anim = animating()
        val t = if (anim) (SystemClock.uptimeMillis() - startMs) / 1000.0 else 0.0
        val k = width / 240f
        canvas.save()
        canvas.scale(k, k)
        // The glow: 6 % (pulsing while docked or in error).
        val glow = when {
            !anim -> 0.06f
            pose == Pose.DOCKED -> pulse(t, 4.0, 0.06f, 0.14f)
            pose == Pose.ERROR -> pulse(t, 1.8, 0.08f, 0.35f)
            else -> 0.06f
        }
        fill(canvas, 120f, 120f, 110f, color, glow)

        // The body: turned round while returning, faded when idle or docked.
        val bodyAlpha = when (pose) { Pose.DOCKED -> 0.75f; Pose.IDLE -> 0.65f; else -> 1f }
        canvas.save()
        if (pose == Pose.RETURNING) canvas.rotate(180f, 120f, 120f)
        if (anim && pose == Pose.CLEANING) wander(canvas, t)
        if (anim && pose == Pose.RETURNING) canvas.translate(0f, wave(t, 3.0, -6f, 4f))
        val layer = if (bodyAlpha < 1f) canvas.saveLayerAlpha(0f, 0f, 240f, 240f, (bodyAlpha * 255).roundToInt()) else -1
        drawBody(canvas, anim, t)
        if (layer >= 0) canvas.restoreToCount(layer)
        canvas.restore()

        if (pose == Pose.CLEANING) particles(canvas, anim, t)
        if (pose == Pose.DOCKED) {
            // The dock: an 88 × 28 plate, 8 px corners.
            val r = RectF(76f, 188f, 164f, 216f)
            paint.style = Paint.Style.FILL
            paint.color = surface
            canvas.drawRoundRect(r, 8f, 8f, paint)
            stroke(canvas, 2f, color, 1f) { canvas.drawRoundRect(r, 8f, 8f, it) }
        }
        if (pose == Pose.RETURNING) {
            canvas.save()
            var alpha = 0.55f
            if (anim) {
                canvas.translate(0f, wave(t, 1.6, -6f, 4f))
                alpha *= wave(t, 1.6, 0.3f, 0.85f)
            }
            val arrow = Path().apply { moveTo(120f, 220f); lineTo(110f, 206f); lineTo(130f, 206f); close() }
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(color, alpha)
            canvas.drawPath(arrow, paint)
            paint.strokeJoin = Paint.Join.ROUND
            stroke(canvas, 2f, color, alpha) { canvas.drawPath(arrow, it) }
            canvas.restore()
        }
        canvas.restore()
        if (anim && isAttachedToWindow) postInvalidateOnAnimation()
    }

    private fun drawBody(canvas: Canvas, anim: Boolean, t: Double) {
        // The side brush, front right, under the shell.
        val brushAlpha = when (pose) { Pose.RETURNING, Pose.IDLE -> 0.4f; Pose.DOCKED -> 0.5f; else -> 1f }
        canvas.save()
        if (anim && pose == Pose.CLEANING) canvas.rotate(((t % 0.6) / 0.6 * 360.0).toFloat(), 174f, 76f)
        paint.strokeCap = Paint.Cap.ROUND
        for ((x2, y2) in listOf(174f to 64f, 174f to 88f, 162f to 76f, 186f to 76f)) {
            stroke(canvas, 1.2f, color, 0.5f * brushAlpha) { canvas.drawLine(174f, 76f, x2, y2, it) }
        }
        canvas.restore()
        fill(canvas, 174f, 76f, 2f, color, 0.5f * brushAlpha)

        // The shell, its inner ring and the front bumper.
        fill(canvas, 120f, 120f, 72f, surface, 1f)
        stroke(canvas, 2f, color, 1f) { canvas.drawCircle(120f, 120f, 72f, it) }
        stroke(canvas, 0.8f, color, 0.2f) { canvas.drawCircle(120f, 120f, 66f, it) }
        paint.strokeCap = Paint.Cap.ROUND
        stroke(canvas, 3f, color, 1f) { canvas.drawPath(bumper, it) }

        // The direction lines (pulsing while cleaning).
        val nav = if (anim && pose == Pose.CLEANING) pulse(t, 2.0, 0.15f, 0.35f) else 0.15f
        for ((a, b) in listOf(floatArrayOf(120f, 56f) to floatArrayOf(120f, 74f), floatArrayOf(88f, 63f) to floatArrayOf(96f, 78f),
            floatArrayOf(152f, 63f) to floatArrayOf(144f, 78f))) {
            paint.strokeCap = Paint.Cap.BUTT
            stroke(canvas, 1.5f, color, nav) { canvas.drawLine(a[0], a[1], b[0], b[1], it) }
        }

        // The LIDAR turret and the power button.
        fill(canvas, 120f, 108f, 14f, surface, 1f)
        stroke(canvas, 2f, color, 1f) { canvas.drawCircle(120f, 108f, 14f, it) }
        stroke(canvas, 0.8f, color, 0.25f) { canvas.drawCircle(120f, 108f, 9f, it) }
        val ring = if (anim && pose == Pose.DOCKED) pulse(t, 2.5, 0.08f, 0.25f) else 0.08f
        fill(canvas, 120f, 140f, 8f, color, ring)
        fill(canvas, 120f, 140f, 4f, color, 0.25f)
    }

    /**
     * The dust: eight specks. Still, they sit at the centre (HA's reduced motion shows them there); animated, each is
     * drawn in from its own spot over 2.4 s (ease-in), fading in to 80 % and out again, as HA's `particle-suck`.
     */
    private fun particles(canvas: Canvas, anim: Boolean, t: Double) {
        PARTICLES.forEachIndexed { i, p ->
            val (dx, dy, r) = p
            if (!anim) {
                fill(canvas, 120f, 120f, r, color, 1f)
                return@forEachIndexed
            }
            val period = 2.4
            val phase = (((t + 0.3 * i) % period) / period).toFloat()
            // 0 → 80 %: transform from (d, 1.2) to (0.82 d, 0.6); opacity 0 → 0.8 (at 25 %) → 0; then held.
            val seg = (phase / 0.8f).coerceAtMost(1f)
            val e = easeIn(seg)
            val f = 1f + (0.82f - 1f) * e
            val scale = 1.2f + (0.6f - 1.2f) * e
            val alpha = when {
                phase < 0.25f -> 0.8f * easeIn(phase / 0.25f)
                phase < 0.8f -> 0.8f * (1f - easeIn((phase - 0.25f) / 0.55f))
                else -> 0f
            }
            if (alpha > 0f) fill(canvas, 120f + dx * f, 120f + dy * f, r * scale, color, alpha)
        }
    }

    /** `vacuum-wander`: 12 s of turns and forward runs, each step eased in and out. */
    private fun wander(canvas: Canvas, t: Double) {
        val p = ((t % 12.0) / 12.0).toFloat()
        var i = 0
        while (i < WANDER.size - 2 && p > WANDER[i + 1][0]) i++
        val a = WANDER[i]
        val b = WANDER[i + 1]
        val e = easeInOut(((p - a[0]) / (b[0] - a[0])).coerceIn(0f, 1f))
        canvas.rotate(a[1] + (b[1] - a[1]) * e, 120f, 120f)
        canvas.translate(0f, a[2] + (b[2] - a[2]) * e)
    }

    private inline fun stroke(canvas: Canvas, width: Float, c: Int, alpha: Float, draw: (Paint) -> Unit) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.color = withAlpha(c, alpha)
        draw(paint)
    }

    private fun fill(canvas: Canvas, cx: Float, cy: Float, r: Float, c: Int, alpha: Float) {
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(c, alpha)
        canvas.drawCircle(cx, cy, r, paint)
    }

    private fun withAlpha(c: Int, alpha: Float): Int =
        (c and 0x00FFFFFF) or ((android.graphics.Color.alpha(c) * alpha).roundToInt().coerceIn(0, 255) shl 24)

    private companion object {
        /** Each speck's start (from the centre) and radius. */
        val PARTICLES = listOf(
            Triple(-90f, -25f, 2f), Triple(90f, 20f, 1.5f), Triple(-85f, 40f, 1.5f), Triple(85f, -35f, 2f),
            Triple(-65f, 70f, 1.5f), Triple(70f, -65f, 1.5f), Triple(-75f, -55f, 1f), Triple(80f, 55f, 1f),
        )

        /** `vacuum-wander`'s keyframes: offset, rotation, forward run. */
        val WANDER = listOf(
            floatArrayOf(0f, 0f, 0f), floatArrayOf(0.15f, 0f, -30f), floatArrayOf(0.25f, 0f, 0f),
            floatArrayOf(0.32f, -18f, 0f), floatArrayOf(0.47f, -18f, -28f), floatArrayOf(0.57f, -18f, 0f),
            floatArrayOf(0.63f, 12f, 0f), floatArrayOf(0.78f, 12f, -25f), floatArrayOf(0.88f, 12f, 0f),
            floatArrayOf(0.95f, 0f, 0f), floatArrayOf(1f, 0f, 0f),
        )

        /** A 0 %/100 % → 50 % keyframe pair: [from] at the ends, [to] halfway, eased in and out. */
        fun wave(t: Double, period: Double, from: Float, to: Float): Float {
            val p = ((t % period) / period).toFloat()
            val e = if (p < 0.5f) easeInOut(p * 2f) else 1f - easeInOut((p - 0.5f) * 2f)
            return from + (to - from) * e
        }

        fun pulse(t: Double, period: Double, low: Float, high: Float): Float = wave(t, period, low, high)

        /** CSS `ease-in-out`, cubic-bezier(0.42, 0, 0.58, 1). */
        fun easeInOut(x: Float): Float = bezier(x, 0.42f, 0f, 0.58f, 1f)

        /** CSS `ease-in`, cubic-bezier(0.42, 0, 1, 1). */
        fun easeIn(x: Float): Float = bezier(x, 0.42f, 0f, 1f, 1f)

        fun bezier(x: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
            if (x <= 0f) return 0f
            if (x >= 1f) return 1f
            var lo = 0f
            var hi = 1f
            var u = x
            repeat(24) {
                val bx = 3 * (1 - u) * (1 - u) * u * x1 + 3 * (1 - u) * u * u * x2 + u * u * u
                if (bx < x) lo = u else hi = u
                u = (lo + hi) / 2
            }
            return 3 * (1 - u) * (1 - u) * u * y1 + 3 * (1 - u) * u * u * y2 + u * u * u
        }
    }
}
