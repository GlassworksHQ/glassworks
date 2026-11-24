/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.HistoryPoint
import dev.glassworks.engine.vitro.theme.ResolvedTheme
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.MdiIcons
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.ui.Inter
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Native More-Info dialog matching Home Assistant 2026: a bottom sheet on narrow screens (under 600 dp) and a
 * centered 420 dp card on wide ones, over a dimmed scrim. Each domain gets HA's own controls (the big vertical
 * brightness and toggle pills, cover buttons, climate stepper, option lists, media transport); sensors and the
 * History tab show a 24 h stepped chart. Raw attributes are never printed.
 */
class MoreInfoSheet(private val ctx: VitroCardContext, private val onDismiss: () -> Unit) : FrameLayout(ctx.context) {
    private val theme: ResolvedTheme = ctx.theme
    private val wide = ctx.context.resources.configuration.screenWidthDp >= WIDE_DP
    private val panel = MaxHeightLinearLayout(ctx.context, if (wide) 0.9f else 0.85f)
    private val titleView = text(20f, theme.primaryTextColor, Inter.MEDIUM).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val historyIcon = MdiIconView(ctx.context)
    private val historyButton = iconButton(historyIcon, "mdi:chart-box-outline")
    private val body = LinearLayout(ctx.context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.px(24f), ctx.px(8f), ctx.px(24f), ctx.px(24f))
    }

    private var entityId: String? = null
    private var unsub: (() -> Unit)? = null
    private var updater: ((EntityState?) -> Unit)? = null
    private val releasers = ArrayList<() -> Unit>()
    private var showingHistory = false
    private var dismissed = false

    /** Refreshes "X minutes ago" while the sheet is open. */
    private val clockTick = object : Runnable {
        override fun run() {
            val id = entityId ?: return
            if (dismissed) return
            updater?.invoke(ctx.store[id])
            ctx.main.postDelayed(this, TIME_REFRESH_MS)
        }
    }

    init {
        setBackgroundColor(SCRIM)
        isClickable = true
        setOnClickListener { dismiss() }

        val radius = 28f * ctx.dp
        panel.apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true // swallows taps so only the scrim dismisses
            background = GradientDrawable().apply {
                setColor(theme.cardBackgroundColor)
                cornerRadii = if (wide) FloatArray(8) { radius }
                else floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            clipToOutline = true
        }
        if (!wide) {
            val handle = View(ctx.context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 2f * ctx.dp
                    setColor(theme.dividerColor)
                }
            }
            panel.addView(handle, LinearLayout.LayoutParams(ctx.px(36f), ctx.px(4f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = ctx.px(10f)
            })
        }

        val header = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.px(12f), ctx.px(if (wide) 12f else 6f), ctx.px(12f), ctx.px(4f))
        }
        val closeIcon = MdiIconView(ctx.context)
        val closeButton = iconButton(closeIcon, "mdi:close").apply { setOnClickListener { dismiss() } }
        header.addView(closeButton, LinearLayout.LayoutParams(ctx.px(40f), ctx.px(40f)))
        header.addView(titleView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            marginStart = ctx.px(12f)
            marginEnd = ctx.px(8f)
        })
        historyButton.setOnClickListener {
            showingHistory = !showingHistory
            buildBody()
        }
        header.addView(historyButton, LinearLayout.LayoutParams(ctx.px(40f), ctx.px(40f)))
        panel.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val scroll = ScrollView(ctx.context).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = false
            addView(body, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        panel.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 1f))

        if (wide) {
            val screenW = ctx.context.resources.displayMetrics.widthPixels
            val w = minOf(ctx.px(420f), (screenW - ctx.px(32f)).coerceAtLeast(ctx.px(280f)))
            addView(panel, LayoutParams(w, WRAP_CONTENT, Gravity.CENTER))
        } else {
            addView(panel, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        }
    }

    fun show(entityId: String) {
        unsub?.invoke()
        this.entityId = entityId
        showingHistory = false
        dismissed = false
        val domain = entityId.substringBefore('.')
        historyButton.visibility = if (domain in HISTORY_TOGGLE_DOMAINS) VISIBLE else GONE
        buildBody()
        unsub = ctx.store.subscribe(entityId) { s -> ctx.main.post { if (!dismissed) updater?.invoke(s) } }
        ctx.main.removeCallbacks(clockTick)
        ctx.main.postDelayed(clockTick, TIME_REFRESH_MS)

        // Slide up and fade in.
        alpha = 0f
        panel.translationY = ctx.dp * if (wide) 24f else 96f
        animate().alpha(1f).setDuration(ENTER_MS).setInterpolator(DecelerateInterpolator()).start()
        panel.animate().translationY(0f).setDuration(ENTER_MS).setInterpolator(DecelerateInterpolator()).start()
    }

    fun dismiss() {
        if (dismissed) return
        dismissed = true
        release()
        onDismiss()
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    private fun release() {
        ctx.main.removeCallbacks(clockTick)
        unsub?.invoke()
        unsub = null
        releaseBody()
    }

    private fun releaseBody() {
        releasers.forEach { runCatching { it() } }
        releasers.clear()
    }

    private fun buildBody() {
        val id = entityId ?: return
        releaseBody()
        body.removeAllViews()
        val s = ctx.store[id]
        titleView.text = s?.friendlyName ?: id
        historyIcon.setIcon("mdi:chart-box-outline", if (showingHistory) theme.primaryColor else theme.primaryTextColor)
        val domain = id.substringBefore('.')
        val update: (EntityState?) -> Unit = if (showingHistory) {
            buildSensor(id)
        } else when (domain) {
            "light" -> buildLight(id, s)
            "switch", "input_boolean", "automation", "siren", "humidifier" -> buildToggle(id, domain)
            "fan" -> buildFan(id, s)
            "camera" -> buildCamera(id)
            "climate" -> buildClimate(id)
            "cover" -> buildCover(id, s)
            "select", "input_select" -> buildSelect(id, domain)
            "lock" -> buildLock(id)
            "media_player" -> buildMedia(id)
            "button", "input_button", "script", "scene" -> buildAction(id, domain)
            else -> buildSensor(id)
        }
        updater = { st ->
            titleView.text = st?.friendlyName ?: id
            update(st)
        }
        updater?.invoke(s)
    }

    // ---- Domain views ----

    private fun buildLight(id: String, initial: EntityState?): (EntityState?) -> Unit {
        val stateLabel = bigState()
        val timeLabel = timeLabel()
        addCentered(stateLabel)
        addCentered(timeLabel, topDp = 4f)
        val color = theme.activeColor("light")
        val modes = supportedModes(initial)
        val dimmable = initial?.attributes?.get("brightness") != null || modes.any { it in DIMMABLE_MODES }
        var slider: VerticalSliderPillView? = null
        var toggle: VerticalTogglePillView? = null
        if (dimmable) {
            slider = VerticalSliderPillView(ctx.context, color).apply {
                onChange = { pct -> stateLabel.text = if (pct <= 0) "Off" else "$pct%" }
                onRelease = { pct ->
                    val data = JSONObject().put("entity_id", id)
                    if (pct <= 0) ctx.callService("light", "turn_off", data, null)
                    else ctx.callService("light", "turn_on", data.put("brightness", (pct * 255 / 100).coerceIn(1, 255)), null)
                }
            }
            addPill(slider)
        } else {
            toggle = VerticalTogglePillView(ctx.context, color, theme.dark).apply {
                onToggle = { on -> optimisticToggle(id, "light", on) }
            }
            addPill(toggle)
        }
        val power = circleButton("mdi:power", 52f, 24f) { optimisticToggle(id, "light", ctx.store[id]?.state != "on") }
        addCentered(power, topDp = 20f, widthDp = 52f, heightDp = 52f)

        val presets = buildList {
            if ("color_temp" in modes) {
                add(PRESET_WARM); add(PRESET_SOFT); add(PRESET_COOL)
            }
            if (modes.any { it in RGB_MODES }) {
                add(PRESET_AMBER); add(PRESET_CORAL); add(PRESET_SKY)
            }
        }
        if (presets.isNotEmpty()) {
            val row = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for (p in presets) {
                val dot = View(ctx.context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(p.swatch)
                        setStroke(ctx.px(1f).coerceAtLeast(1), theme.dividerColor)
                    }
                    isClickable = true
                    contentDescription = p.name
                    setOnClickListener {
                        val data = JSONObject().put("entity_id", id)
                        if (p.kelvin != null) data.put("color_temp_kelvin", p.kelvin)
                        else data.put("rgb_color", JSONArray().put(Color.red(p.swatch)).put(Color.green(p.swatch)).put(Color.blue(p.swatch)))
                        ctx.callService("light", "turn_on", data, null)
                    }
                }
                row.addView(dot, LinearLayout.LayoutParams(ctx.px(32f), ctx.px(32f)).apply {
                    marginStart = ctx.px(6f); marginEnd = ctx.px(6f)
                })
            }
            addCentered(row, topDp = 20f)
        }

        return { s ->
            val on = s?.state == "on"
            val pct = if (on) s.brightnessPct else null
            if (slider?.dragging != true) {
                stateLabel.text = when {
                    s == null -> "Unknown"
                    s.isUnavailable -> s.formattedState()
                    on && pct != null -> "$pct%"
                    on -> "On"
                    else -> "Off"
                }
            }
            slider?.active = on
            slider?.setPercent(if (on) pct ?: 100 else 0)
            toggle?.setOn(on)
            timeLabel.text = relativeTime(s)
        }
    }

    private fun buildToggle(id: String, domain: String): (EntityState?) -> Unit {
        val stateLabel = bigState()
        val timeLabel = timeLabel()
        addCentered(stateLabel)
        addCentered(timeLabel, topDp = 4f)
        val pill = VerticalTogglePillView(ctx.context, theme.activeColor(domain), theme.dark).apply {
            onToggle = { on -> optimisticToggle(id, domain, on) }
        }
        addPill(pill)
        return { s ->
            val on = s?.state == "on"
            stateLabel.text = when {
                s == null -> "Unknown"
                s.isUnavailable -> s.formattedState()
                on -> "On"
                else -> "Off"
            }
            pill.setOn(on)
            timeLabel.text = relativeTime(s)
        }
    }

    private fun buildFan(id: String, initial: EntityState?): (EntityState?) -> Unit {
        if (initial?.attributes?.get("percentage") == null) return buildToggle(id, "fan")
        val stateLabel = bigState()
        val timeLabel = timeLabel()
        addCentered(stateLabel)
        addCentered(timeLabel, topDp = 4f)
        val slider = VerticalSliderPillView(ctx.context, theme.activeColor("fan")).apply {
            onChange = { pct -> stateLabel.text = if (pct <= 0) "Off" else "$pct%" }
            onRelease = { pct ->
                ctx.callService("fan", "set_percentage", JSONObject().put("entity_id", id).put("percentage", pct), null)
            }
        }
        addPill(slider)
        val power = circleButton("mdi:power", 52f, 24f) { optimisticToggle(id, "fan", ctx.store[id]?.state != "on") }
        addCentered(power, topDp = 20f, widthDp = 52f, heightDp = 52f)
        return { s ->
            val on = s?.state == "on"
            val pct = (s?.attributes?.get("percentage") as? Number)?.toInt()
            if (!slider.dragging) {
                stateLabel.text = when {
                    s == null -> "Unknown"
                    s.isUnavailable -> s.formattedState()
                    on && pct != null -> "$pct%"
                    on -> "On"
                    else -> "Off"
                }
            }
            slider.active = on
            slider.setPercent(if (on) pct ?: 100 else 0)
            timeLabel.text = relativeTime(s)
        }
    }

    private fun buildLock(id: String): (EntityState?) -> Unit {
        val stateLabel = bigState()
        val timeLabel = timeLabel()
        addCentered(stateLabel)
        addCentered(timeLabel, topDp = 4f)
        val pill = VerticalTogglePillView(ctx.context, theme.activeColor("lock"), theme.dark).apply {
            onToggle = { locked ->
                ctx.store[id]?.let { ctx.store.putLocal(it.copy(state = if (locked) "locked" else "unlocked")) }
                ctx.callService("lock", if (locked) "lock" else "unlock", JSONObject().put("entity_id", id), null)
            }
        }
        addPill(pill)
        return { s ->
            stateLabel.text = s?.formattedState() ?: "Unknown"
            pill.setOn(s?.state == "locked" || s?.state == "locking")
            timeLabel.text = relativeTime(s)
        }
    }

    private fun buildCover(id: String, initial: EntityState?): (EntityState?) -> Unit {
        val stateLabel = bigState()
        val timeLabel = timeLabel()
        addCentered(stateLabel)
        addCentered(timeLabel, topDp = 4f)
        val hasPosition = initial?.attributes?.get("current_position") != null
        val slider = if (hasPosition) {
            VerticalSliderPillView(ctx.context, theme.activeColor("cover")).apply {
                onChange = { pct -> stateLabel.text = "$pct%" }
                onRelease = { pct ->
                    ctx.callService("cover", "set_cover_position", JSONObject().put("entity_id", id).put("position", pct), null)
                }
            }.also { addPill(it) }
        } else {
            null
        }
        val buttons = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf("mdi:arrow-up" to "open_cover", "mdi:stop" to "stop_cover", "mdi:arrow-down" to "close_cover")
            .forEachIndexed { i, (icon, service) ->
                val b = roundedButton(icon) { ctx.callService("cover", service, JSONObject().put("entity_id", id), null) }
                buttons.addView(b, LinearLayout.LayoutParams(ctx.px(72f), ctx.px(56f)).apply {
                    if (i > 0) marginStart = ctx.px(12f)
                })
            }
        addCentered(buttons, topDp = 20f)
        return { s ->
            val pos = (s?.attributes?.get("current_position") as? Number)?.toInt()
            if (slider?.dragging != true) {
                stateLabel.text = when {
                    s == null -> "Unknown"
                    s.state == "open" && pos != null && pos in 1..99 -> "$pos%"
                    else -> s.formattedState()
                }
            }
            slider?.active = s?.isActive == true
            slider?.setPercent(pos ?: 0)
            timeLabel.text = relativeTime(s)
        }
    }

    private fun buildClimate(id: String): (EntityState?) -> Unit {
        val target = text(38f, theme.primaryTextColor, Inter.REGULAR).apply { gravity = Gravity.CENTER }
        val current = text(14f, theme.secondaryTextColor, Inter.MEDIUM).apply { gravity = Gravity.CENTER }
        val stepper = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        var targetTemp: Double? = null
        var step = 0.5
        fun adjust(delta: Double) {
            val t = (targetTemp ?: return) + delta
            targetTemp = t
            target.text = String.format(Locale.US, "%.1f°", t)
            ctx.callService("climate", "set_temperature", JSONObject().put("entity_id", id).put("temperature", t), null)
        }
        stepper.addView(circleButton("mdi:minus", 48f, 22f) { adjust(-step) }, LinearLayout.LayoutParams(ctx.px(48f), ctx.px(48f)))
        stepper.addView(target, LinearLayout.LayoutParams(ctx.px(150f), WRAP_CONTENT))
        stepper.addView(circleButton("mdi:plus", 48f, 22f) { adjust(step) }, LinearLayout.LayoutParams(ctx.px(48f), ctx.px(48f)))
        addCentered(stepper, topDp = 8f)
        addCentered(current, topDp = 6f)

        val modesRow = LinearLayout(ctx.context).apply { orientation = LinearLayout.HORIZONTAL }
        val modesScroll = HorizontalScrollView(ctx.context).apply {
            isHorizontalScrollBarEnabled = false
            addView(modesRow, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        body.addView(modesScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(24f) })
        var shownModes: List<String> = emptyList()
        var shownState: String? = null
        return { s ->
            (s?.attributes?.get("target_temp_step") as? Number)?.toDouble()?.let { if (it > 0) step = it }
            targetTemp = (s?.attributes?.get("temperature") as? Number)?.toDouble()
            target.text = targetTemp?.let { String.format(Locale.US, "%.1f°", it) } ?: "–"
            val cur = (s?.attributes?.get("current_temperature") as? Number)?.toDouble()
            current.text = cur?.let { String.format(Locale.US, "Currently %.1f°", it) } ?: relativeTime(s)
            val modes = listAttr(s, "hvac_modes")
            if (modes != shownModes || s?.state != shownState) {
                shownModes = modes
                shownState = s?.state
                modesRow.removeAllViews()
                for (m in modes) {
                    val selected = s?.state == m
                    val chip = pillLabel(humanize(m), selected) {
                        ctx.callService("climate", "set_hvac_mode", JSONObject().put("entity_id", id).put("hvac_mode", m), null)
                    }
                    modesRow.addView(chip, LinearLayout.LayoutParams(WRAP_CONTENT, ctx.px(40f)).apply { marginEnd = ctx.px(8f) })
                }
            }
        }
    }

    private fun buildSelect(id: String, domain: String): (EntityState?) -> Unit {
        val list = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(8f) })
        var shown: Pair<List<String>, String?>? = null
        return { s ->
            val opts = listAttr(s, "options")
            val key = opts to s?.state
            if (key != shown) {
                shown = key
                list.removeAllViews()
                for (opt in opts) {
                    val selected = s?.state == opt
                    val row = LinearLayout(ctx.context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(ctx.px(16f), 0, ctx.px(12f), 0)
                        background = GradientDrawable().apply {
                            cornerRadius = 12f * ctx.dp
                            setColor(if (selected) CardSurface.withAlpha(theme.primaryColor, 0.12f) else Color.TRANSPARENT)
                        }
                        isClickable = true
                        setOnClickListener {
                            ctx.store[id]?.let { ctx.store.putLocal(it.copy(state = opt)) }
                            ctx.callService(domain, "select_option", JSONObject().put("entity_id", id).put("option", opt), null)
                        }
                    }
                    row.addView(text(15f, if (selected) theme.primaryColor else theme.primaryTextColor, if (selected) Inter.MEDIUM else Inter.REGULAR).apply {
                        this.text = opt
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    if (selected) {
                        row.addView(MdiIconView(ctx.context).apply { setIcon("mdi:check", theme.primaryColor) },
                            LinearLayout.LayoutParams(ctx.px(22f), ctx.px(22f)))
                    }
                    list.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(48f)).apply { bottomMargin = ctx.px(2f) })
                }
            }
        }
    }

    private fun buildMedia(id: String): (EntityState?) -> Unit {
        val title = text(20f, theme.primaryTextColor, Inter.MEDIUM).apply {
            gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        val artist = text(15f, theme.secondaryTextColor).apply {
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val state = text(13f, theme.secondaryTextColor, Inter.MEDIUM).apply { gravity = Gravity.CENTER }
        body.addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(12f) })
        body.addView(artist, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(4f) })
        body.addView(state, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(4f) })

        fun media(service: String) = ctx.callService("media_player", service, JSONObject().put("entity_id", id), null)
        val transport = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val playIcon = MdiIconView(ctx.context)
        val play = circleButton(playIcon, "mdi:play", 64f, 30f) { media("media_play_pause") }
        transport.addView(circleButton("mdi:skip-previous", 52f, 24f) { media("media_previous_track") },
            LinearLayout.LayoutParams(ctx.px(52f), ctx.px(52f)))
        transport.addView(play, LinearLayout.LayoutParams(ctx.px(64f), ctx.px(64f)).apply {
            marginStart = ctx.px(20f); marginEnd = ctx.px(20f)
        })
        transport.addView(circleButton("mdi:skip-next", 52f, 24f) { media("media_next_track") },
            LinearLayout.LayoutParams(ctx.px(52f), ctx.px(52f)))
        addCentered(transport, topDp = 20f)

        val volumeRow = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        var tracking = false
        val volume = SeekBar(ctx.context).apply {
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) = Unit
                override fun onStartTrackingTouch(sb: SeekBar?) { tracking = true }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    tracking = false
                    val level = (sb?.progress ?: return) / 100.0
                    ctx.callService("media_player", "volume_set", JSONObject().put("entity_id", id).put("volume_level", level), null)
                }
            })
        }
        volumeRow.addView(MdiIconView(ctx.context).apply { setIcon("mdi:volume-high", theme.secondaryTextColor) },
            LinearLayout.LayoutParams(ctx.px(22f), ctx.px(22f)).apply { marginEnd = ctx.px(8f) })
        volumeRow.addView(volume, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        body.addView(volumeRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(24f) })

        return { s ->
            val playing = s?.state == "playing"
            title.text = (s?.attributes?.get("media_title") as? String)?.takeIf { it.isNotBlank() } ?: s?.friendlyName ?: id
            val a = (s?.attributes?.get("media_artist") as? String)?.takeIf { it.isNotBlank() }
            artist.text = a.orEmpty()
            artist.visibility = if (a == null) GONE else VISIBLE
            state.text = listOf(s?.formattedState().orEmpty(), relativeTime(s)).filter { it.isNotEmpty() }.joinToString(" · ")
            playIcon.setIcon(if (playing) "mdi:pause" else "mdi:play", theme.primaryTextColor)
            val level = (s?.attributes?.get("volume_level") as? Number)?.toDouble()
            volumeRow.visibility = if (level == null) GONE else VISIBLE
            if (level != null && !tracking) volume.progress = (level * 100).roundToInt()
        }
    }

    private fun buildAction(id: String, domain: String): (EntityState?) -> Unit {
        val timeLabel = timeLabel()
        val (caption, service) = when (domain) {
            "script" -> "Run" to "turn_on"
            "scene" -> "Activate" to "turn_on"
            else -> "Press" to "press"
        }
        val button = text(16f, Color.WHITE, Inter.MEDIUM).apply {
            this.text = caption
            gravity = Gravity.CENTER
            setPadding(ctx.px(36f), 0, ctx.px(36f), 0)
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(theme.primaryColor)
            }
            isClickable = true
            setOnClickListener { ctx.callService(domain, service, JSONObject().put("entity_id", id), null) }
        }
        addCentered(button, topDp = 24f, heightDp = 52f)
        addCentered(timeLabel, topDp = 12f)
        return { s -> timeLabel.text = relativeTime(s) }
    }

    private fun buildCamera(id: String): (EntityState?) -> Unit {
        val image = AspectImageView(ctx.context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                cornerRadius = 16f * ctx.dp
                setColor(CardSurface.withAlpha(theme.primaryTextColor, 0.06f))
            }
            clipToOutline = true
        }
        body.addView(image, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(8f) })
        val status = text(14f, theme.secondaryTextColor, Inter.MEDIUM)
        body.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(12f) })

        var picture: String? = null
        var polling = true
        var inFlight = false
        val poll = object : Runnable {
            override fun run() {
                if (!polling) return
                val pic = picture
                if (pic != null && !inFlight) {
                    inFlight = true
                    val sep = if ('?' in pic) '&' else '?'
                    ctx.loadImage("$pic${sep}t=${System.currentTimeMillis()}") { bmp ->
                        inFlight = false
                        if (polling && bmp != null) image.setImageBitmap(bmp)
                    }
                }
                ctx.main.postDelayed(this, CAMERA_POLL_MS)
            }
        }
        releasers += {
            polling = false
            ctx.main.removeCallbacks(poll)
        }
        ctx.main.post(poll)
        return { s ->
            picture = s?.entityPicture
            val label = when (s?.state) {
                null -> "Unknown"
                "idle" -> "Idle"
                "streaming" -> "Streaming"
                "recording" -> "Recording"
                else -> s.formattedState()
            }
            status.text = listOf(label, relativeTime(s)).filter { it.isNotEmpty() }.joinToString(" · ")
        }
    }

    /** HA's sensor More-Info: a summary row, then the 24 h history chart. Also the History tab of every control. */
    private fun buildSensor(id: String): (EntityState?) -> Unit {
        val row = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = MdiIconView(ctx.context)
        val name = text(15f, theme.primaryTextColor, Inter.MEDIUM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val time = text(13f, theme.secondaryTextColor)
        val value = text(15f, theme.primaryTextColor).apply { maxLines = 1 }
        val labels = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            addView(name)
            addView(time, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.px(2f) })
        }
        row.addView(icon, LinearLayout.LayoutParams(ctx.px(24f), ctx.px(24f)).apply { marginEnd = ctx.px(16f) })
        row.addView(labels, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = ctx.px(12f) })
        row.addView(value)
        body.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(8f) })

        body.addView(text(20f, theme.primaryTextColor, Inter.MEDIUM).apply { this.text = "History" },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.px(20f) })
        val chart = MoreInfoHistoryChartView(ctx.context, theme)
        body.addView(chart, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(200f)).apply { topMargin = ctx.px(12f) })

        var alive = true
        val timeout = Runnable { if (alive) chart.setPoints(emptyList(), chart.unit) }
        releasers += {
            alive = false
            ctx.main.removeCallbacks(timeout)
        }
        chart.unit = ctx.store[id]?.unit.orEmpty()
        ctx.fetchHistory(listOf(id), 24) { map ->
            ctx.main.post {
                if (!alive) return@post
                ctx.main.removeCallbacks(timeout)
                chart.setPoints(map[id].orEmpty(), ctx.store[id]?.unit.orEmpty())
            }
        }
        // history/stream only answers when it has numeric points; don't leave "Loading" up forever.
        ctx.main.postDelayed(timeout, HISTORY_TIMEOUT_MS)

        return { s ->
            icon.setIcon(MdiIcons.resolveIconName(null, s), theme.stateIconColor)
            name.text = s?.friendlyName ?: id
            time.text = relativeTime(s)
            value.text = s?.formattedState() ?: "Unknown"
        }
    }

    // ---- Shared pieces ----

    private fun optimisticToggle(id: String, domain: String, turnOn: Boolean) {
        val cur = ctx.store[id]
        if (cur != null && cur.state in setOf("on", "off")) ctx.store.putLocal(cur.copy(state = if (turnOn) "on" else "off"))
        ctx.callService(domain, if (turnOn) "turn_on" else "turn_off", JSONObject().put("entity_id", id), null)
    }

    private fun text(sp: Float, color: Int, weight: Int = Inter.REGULAR): TextView = TextView(ctx.context).apply {
        textSize = sp
        setTextColor(color)
        typeface = Inter.typeface(ctx.context, weight, sp)
        includeFontPadding = false
    }

    private fun bigState(): TextView = text(36f, theme.primaryTextColor, Inter.REGULAR).apply { gravity = Gravity.CENTER }

    private fun timeLabel(): TextView =
        text(14f, CardSurface.withAlpha(theme.primaryTextColor, 0.75f), Inter.MEDIUM).apply { gravity = Gravity.CENTER }

    private fun addCentered(v: View, topDp: Float = 0f, widthDp: Float? = null, heightDp: Float? = null) {
        val lp = LinearLayout.LayoutParams(
            widthDp?.let { ctx.px(it) } ?: WRAP_CONTENT,
            heightDp?.let { ctx.px(it) } ?: WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = ctx.px(topDp)
        }
        body.addView(v, lp)
    }

    private fun addPill(v: View) = addCentered(v, topDp = 24f, widthDp = 132f, heightDp = 276f)

    private fun iconButton(icon: MdiIconView, name: String): FrameLayout = FrameLayout(ctx.context).apply {
        icon.setIcon(name, theme.primaryTextColor)
        addView(icon, LayoutParams(ctx.px(22f), ctx.px(22f), Gravity.CENTER))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
        }
        isClickable = true
    }

    private fun circleButton(name: String, sizeDp: Float, iconDp: Float, onClick: () -> Unit): FrameLayout =
        circleButton(MdiIconView(ctx.context), name, sizeDp, iconDp, onClick)

    private fun circleButton(icon: MdiIconView, name: String, sizeDp: Float, iconDp: Float, onClick: () -> Unit): FrameLayout =
        FrameLayout(ctx.context).apply {
            icon.setIcon(name, theme.primaryTextColor)
            addView(icon, LayoutParams(ctx.px(iconDp), ctx.px(iconDp), Gravity.CENTER))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(BUTTON_SURFACE)
            }
            minimumWidth = ctx.px(sizeDp)
            minimumHeight = ctx.px(sizeDp)
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun roundedButton(name: String, onClick: () -> Unit): FrameLayout = FrameLayout(ctx.context).apply {
        val icon = MdiIconView(ctx.context).apply { setIcon(name, theme.primaryTextColor) }
        addView(icon, LayoutParams(ctx.px(24f), ctx.px(24f), Gravity.CENTER))
        background = GradientDrawable().apply {
            cornerRadius = 16f * ctx.dp
            setColor(BUTTON_SURFACE)
        }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun pillLabel(caption: String, selected: Boolean, onClick: () -> Unit): TextView =
        text(14f, if (selected) Color.WHITE else theme.primaryTextColor, Inter.MEDIUM).apply {
            this.text = caption
            gravity = Gravity.CENTER
            setPadding(ctx.px(16f), 0, ctx.px(16f), 0)
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(if (selected) theme.primaryColor else BUTTON_SURFACE)
            }
            isClickable = true
            setOnClickListener { onClick() }
        }

    private class Preset(val name: String, val swatch: Int, val kelvin: Int? = null)

    private companion object {
        const val WIDE_DP = 600
        const val SCRIM = 0x66000000
        const val BUTTON_SURFACE = 0x14888888
        const val ENTER_MS = 200L
        const val CAMERA_POLL_MS = 1_500L
        const val TIME_REFRESH_MS = 30_000L
        const val HISTORY_TIMEOUT_MS = 8_000L

        /** Domains whose More-Info has a control view and a History tab. */
        val HISTORY_TOGGLE_DOMAINS = setOf(
            "light", "switch", "fan", "input_boolean", "climate", "cover", "lock", "automation", "humidifier", "siren",
        )
        val DIMMABLE_MODES = setOf("brightness", "color_temp", "hs", "rgb", "xy", "rgbw", "rgbww")
        val RGB_MODES = setOf("hs", "rgb", "xy", "rgbw", "rgbww")

        val PRESET_WARM = Preset("Warm white", 0xFFFFB46B.toInt(), 2700)
        val PRESET_SOFT = Preset("Soft white", 0xFFFFE4C4.toInt(), 4000)
        val PRESET_COOL = Preset("Cool white", 0xFFE6F0FF.toInt(), 6500)
        val PRESET_AMBER = Preset("Amber", 0xFFFFBF00.toInt())
        val PRESET_CORAL = Preset("Coral", 0xFFFF7F50.toInt())
        val PRESET_SKY = Preset("Sky blue", 0xFF87CEEB.toInt())

        fun supportedModes(s: EntityState?): List<String> = listAttr(s, "supported_color_modes")

        fun listAttr(s: EntityState?, key: String): List<String> = when (val v = s?.attributes?.get(key)) {
            is List<*> -> v.mapNotNull { it?.toString() }
            is JSONArray -> (0 until v.length()).map { v.optString(it) }
            else -> emptyList()
        }

        fun humanize(raw: String): String = raw.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }

        fun relativeTime(s: EntityState?): String = relativeTime(s?.lastChanged ?: 0.0, System.currentTimeMillis())

        /** "Just now", "5 minutes ago", "3 hours ago", "2 days ago", "4 weeks ago"; empty without a timestamp. */
        fun relativeTime(lastChangedSec: Double, nowMs: Long): String {
            if (lastChangedSec <= 0.0) return ""
            val secs = (nowMs / 1000.0 - lastChangedSec).toLong().coerceAtLeast(0L)
            fun plural(n: Long, unit: String) = if (n == 1L) "1 $unit ago" else "$n ${unit}s ago"
            return when {
                secs < 60 -> "Just now"
                secs < 3_600 -> plural(secs / 60, "minute")
                secs < 86_400 -> plural(secs / 3_600, "hour")
                secs < 7 * 86_400 -> plural(secs / 86_400, "day")
                else -> plural(secs / (7 * 86_400), "week")
            }
        }
    }
}

/** A vertical LinearLayout capped at [maxFraction] of the height its parent offers. */
private class MaxHeightLinearLayout(context: Context, private val maxFraction: Float) : LinearLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val avail = MeasureSpec.getSize(heightMeasureSpec)
        val spec = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED || avail <= 0) {
            heightMeasureSpec
        } else {
            MeasureSpec.makeMeasureSpec((avail * maxFraction).toInt(), MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, spec)
    }
}

/** An ImageView that keeps a 16:9 frame at whatever width it gets. */
private class AspectImageView(context: Context) : ImageView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (w <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        setMeasuredDimension(w, w * 9 / 16)
    }
}

/**
 * HA's big vertical slider (light brightness, fan speed, cover position): a 36 dp rounded pill whose
 * bottom fills with [activeColor] up to the level, with a white handle 16 dp under the top of the fill.
 * Dragging reports the level live through [onChange] and once on release through [onRelease].
 */
internal class VerticalSliderPillView(context: Context, private val activeColor: Int) : View(context) {
    private val dp = resources.displayMetrics.density
    private val radius = 36f * dp
    private val rect = RectF()
    private val handleRect = RectF()
    private val clip = Path()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private var pct = 0f

    var active = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var dragging = false
        private set
    var onChange: ((Int) -> Unit)? = null
    var onRelease: ((Int) -> Unit)? = null

    fun setPercent(value: Int) {
        if (dragging) return
        val p = value.coerceIn(0, 100).toFloat()
        if (p != pct) { pct = p; invalidate() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.save()
        canvas.clipPath(clip)
        trackPaint.color = if (active || dragging) CardSurface.withAlpha(activeColor, 0.20f) else CardSurface.withAlpha(NEUTRAL, 0.10f)
        canvas.drawRect(rect, trackPaint)
        if (pct > 0f) {
            val top = h - h * pct / 100f
            fillPaint.color = activeColor
            canvas.drawRect(0f, top, w, h, fillPaint)
            val y = (top + 16f * dp).coerceAtMost(h - 10f * dp)
            val hw = 22f * dp
            handleRect.set(w / 2f - hw, y - 2f * dp, w / 2f + hw, y + 2f * dp)
            canvas.drawRoundRect(handleRect, 2f * dp, 2f * dp, handlePaint)
        }
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                track(event.y)
            }
            MotionEvent.ACTION_MOVE -> track(event.y)
            MotionEvent.ACTION_UP -> {
                track(event.y)
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onRelease?.invoke(pct.roundToInt())
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun track(y: Float) {
        val h = height.toFloat().coerceAtLeast(1f)
        pct = ((1f - y / h) * 100f).coerceIn(0f, 100f)
        invalidate()
        onChange?.invoke(pct.roundToInt())
    }

    private companion object {
        const val NEUTRAL = 0xFF888888.toInt()
    }
}

/**
 * HA's big vertical on/off switch: a 36 dp rounded track with a 28 dp rounded thumb that sits at the top in
 * [onColor] with a white `|` when on, and at the bottom in grey with a white `○` when off. A tap toggles; a
 * vertical drag or fling picks the side it moves towards.
 */
internal class VerticalTogglePillView(context: Context, private val onColor: Int, dark: Boolean) : View(context) {
    private val dp = resources.displayMetrics.density
    private val trackColor = if (dark) 0xFF2C2C2C.toInt() else 0xFFEEEEEE.toInt()
    private val rect = RectF()
    private val thumbRect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glyphStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2.2f * dp
    }
    private val evaluator = ArgbEvaluator()
    private var on = false
    private var frac = 0f
    private var animator: ValueAnimator? = null
    private var downY = 0f

    var onToggle: ((Boolean) -> Unit)? = null

    fun setOn(value: Boolean) {
        if (value == on && animator == null) {
            val target = if (value) 1f else 0f
            if (frac != target) { frac = target; invalidate() }
            return
        }
        on = value
        animateTo(if (value) 1f else 0f)
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        if (!isAttachedToWindow) {
            frac = target
            animator = null
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(frac, target).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                frac = it.animatedValue as Float
                invalidate()
            }
            start()
        }.also { a ->
            a.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (animator === a) animator = null
                }
            })
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        paint.color = trackColor
        canvas.drawRoundRect(rect, 36f * dp, 36f * dp, paint)

        val inset = 8f * dp
        val thumbH = minOf(132f * dp, h / 2f)
        val bottomTop = h - inset - thumbH
        val top = bottomTop + (inset - bottomTop) * frac
        thumbRect.set(inset, top, w - inset, top + thumbH)
        paint.color = evaluator.evaluate(frac, OFF_THUMB, onColor) as Int
        canvas.drawRoundRect(thumbRect, 28f * dp, 28f * dp, paint)

        val cx = thumbRect.centerX()
        val cy = thumbRect.centerY()
        if (frac >= 0.5f) {
            paint.color = Color.WHITE
            val bw = 1.5f * dp
            val bh = 10f * dp
            canvas.drawRoundRect(cx - bw, cy - bh, cx + bw, cy + bh, bw, bw, paint)
        } else {
            canvas.drawCircle(cx, cy, 8f * dp - glyphStroke.strokeWidth / 2f, glyphStroke)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val dy = event.y - downY
                val next = if (abs(dy) > DRAG_DP * dp) dy < 0f else !on
                if (next != on) {
                    setOn(next)
                    onToggle?.invoke(next)
                }
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private companion object {
        const val OFF_THUMB = 0xFF9E9E9E.toInt()
        const val DRAG_DP = 24f
    }
}

/**
 * HA's sensor history chart: 5 horizontal and 4 vertical grid lines, min/mid/max labels on the left with the
 * unit above, times along the bottom, and a 2 dp blue stepped line (across, then up or down) over 24 hours.
 */
internal class MoreInfoHistoryChartView(context: Context, private val theme: ResolvedTheme) : View(context) {
    private val dp = resources.displayMetrics.density
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * dp
        color = theme.dividerColor
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
        color = HA_BLUE
        strokeJoin = Paint.Join.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.secondaryTextColor
        textSize = 11f * dp
    }
    private val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.secondaryTextColor
        textSize = 13f * dp
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val timeFormat = SimpleDateFormat(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", Locale.getDefault())
    private var points: List<HistoryPoint>? = null
    private val spanSec = 24 * 3_600.0

    var unit: String = ""

    fun setPoints(data: List<HistoryPoint>, unit: String) {
        points = data.sortedBy { it.epochSec }
        this.unit = unit
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pts = points
        if (pts == null || pts.isEmpty()) {
            canvas.drawText(if (pts == null) "Loading history…" else "No recent history", w / 2f, h / 2f, messagePaint)
            return
        }
        var minV = pts.minOf { it.value }
        var maxV = pts.maxOf { it.value }
        if (maxV - minV < 1e-6) { minV -= 1.0; maxV += 1.0 }
        val left = 40f * dp
        val top = 18f * dp
        val bottom = h - 20f * dp
        val right = w - 4f * dp
        val chartH = (bottom - top).coerceAtLeast(1f)
        val chartW = (right - left).coerceAtLeast(1f)

        for (i in 0..4) {
            val y = top + chartH * i / 4f
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        val nowSec = System.currentTimeMillis() / 1000.0
        val startSec = nowSec - spanSec
        labelPaint.textAlign = Paint.Align.CENTER
        for (i in 0..3) {
            val x = left + chartW * i / 3f
            canvas.drawLine(x, top, x, bottom, gridPaint)
            val t = startSec + spanSec * i / 3.0
            labelPaint.textAlign = when (i) {
                0 -> Paint.Align.LEFT
                3 -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            canvas.drawText(timeFormat.format(Date((t * 1000).toLong())), x, h - 4f * dp, labelPaint)
        }

        labelPaint.textAlign = Paint.Align.RIGHT
        val labelX = left - 6f * dp
        val half = labelPaint.textSize / 3f
        canvas.drawText(format(maxV, maxV - minV), labelX, top + half, labelPaint)
        canvas.drawText(format((maxV + minV) / 2, maxV - minV), labelX, top + chartH / 2f + half, labelPaint)
        canvas.drawText(format(minV, maxV - minV), labelX, bottom + half, labelPaint)
        if (unit.isNotEmpty()) {
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(unit, 0f, labelPaint.textSize, labelPaint)
        }

        fun xOf(sec: Double) = left + (((sec - startSec) / spanSec).toFloat().coerceIn(0f, 1f)) * chartW
        fun yOf(v: Double) = bottom - ((v - minV) / (maxV - minV)).toFloat() * chartH
        path.reset()
        var prevY = yOf(pts[0].value)
        path.moveTo(xOf(pts[0].epochSec), prevY)
        for (i in 1 until pts.size) {
            val x = xOf(pts[i].epochSec)
            val y = yOf(pts[i].value)
            path.lineTo(x, prevY)
            path.lineTo(x, y)
            prevY = y
        }
        path.lineTo(right, prevY) // the last state holds until now
        canvas.drawPath(path, linePaint)
    }

    private fun format(v: Double, range: Double): String =
        if (range >= 10.0 || v == Math.rint(v)) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)

    private companion object {
        const val HA_BLUE = 0xFF4269D0.toInt()
    }
}
