/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONObject
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.model.ActionConfig
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.MdiIcons
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.ui.Inter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun textView(ctx: VitroCardContext, sp: Float, color: Int, weight: Int = Inter.REGULAR): TextView =
    TextView(ctx.context).apply {
        textSize = sp
        setTextColor(color)
        typeface = Inter.typeface(ctx.context, weight, sp)
        includeFontPadding = false
    }

/**
 * Native HA `tile` card + Tier 2 HACS fast-path for `custom:mushroom-entity-card`,
 * `custom:mushroom-light-card`, `custom:mushroom-fan-card`, `custom:mushroom-cover-card`,
 * `custom:mushroom-person-card`, and `custom:mushroom-template-card`.
 */
class TileCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    private val iconBadge = MdiIconView(ctx.context)
    private val iconWrap = LinearLayout(ctx.context)
    private val titleView = textView(ctx, 14f, ctx.theme.primaryTextColor, Inter.MEDIUM)
    private val stateView = textView(ctx, 12f, ctx.theme.secondaryTextColor, Inter.REGULAR)

    init {
        val pad = ctx.px(12f)
        val vertical = config.raw.optBoolean("vertical", false) || config.raw.optString("layout") == "vertical"
        val row = LinearLayout(ctx.context).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (vertical) Gravity.CENTER else Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val badgeSize = ctx.px(38f)
        val iconPad = ctx.px(8f)
        iconWrap.apply {
            gravity = Gravity.CENTER
            setPadding(iconPad, iconPad, iconPad, iconPad)
            addView(iconBadge, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        row.addView(iconWrap, LinearLayout.LayoutParams(badgeSize, badgeSize).apply {
            if (vertical) bottomMargin = ctx.px(8f) else marginEnd = ctx.px(12f)
        })

        val labels = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (vertical) Gravity.CENTER_HORIZONTAL else Gravity.START
            titleView.maxLines = 1
            titleView.ellipsize = TextUtils.TruncateAt.END
            stateView.maxLines = 1
            stateView.ellipsize = TextUtils.TruncateAt.END
            addView(titleView)
            addView(stateView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.px(2f) })
        }
        row.addView(labels, LinearLayout.LayoutParams(if (vertical) WRAP_CONTENT else 0, WRAP_CONTENT, if (vertical) 0f else 1f))

        val featuresArr = config.raw.optJSONArray("features")
        val featureTypes = if (featuresArr != null) {
            (0 until featuresArr.length()).mapNotNull { featuresArr.optJSONObject(it)?.optString("type", "")?.takeIf(String::isNotEmpty) }
        } else {
            emptyList()
        }
        val rootCol = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val entityId = config.entity
        var brightnessSlider: SeekBar? = null
        var selectRow: LinearLayout? = null
        if ("light-brightness" in featureTypes && entityId != null) {
            brightnessSlider = SeekBar(ctx.context).apply {
                max = 255
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {}
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {
                        val bri = sb?.progress ?: return
                        if (bri <= 0) ctx.callService("light", "turn_off", JSONObject().put("entity_id", entityId), null)
                        else ctx.callService("light", "turn_on", JSONObject().put("entity_id", entityId).put("brightness", bri), null)
                    }
                })
            }
            rootCol.addView(brightnessSlider, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                leftMargin = pad; rightMargin = pad; bottomMargin = ctx.px(10f)
            })
        }
        if ("select-options" in featureTypes && entityId != null) {
            selectRow = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(pad, 0, pad, ctx.px(10f))
            }
            rootCol.addView(selectRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        addView(rootCol, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val defaultTap = if (entityId != null && entityId.substringBefore('.') in TOGGLE_DOMAINS) "toggle" else "more-info"
        val tapAction = ActionConfig.parse(config.raw.optJSONObject("tap_action"), defaultTap)
        val iconTapAction = ActionConfig.parse(config.raw.optJSONObject("icon_tap_action"), defaultTap)

        isClickable = true
        setOnClickListener { ctx.performAction(tapAction, entityId) }
        iconWrap.isClickable = true
        iconWrap.setOnClickListener { ctx.performAction(iconTapAction, entityId) }

        if (config.type == "custom:mushroom-template-card") {
            bindMushroomTemplate()
        } else if (entityId != null) {
            observe(entityId) { s ->
                renderState(s)
                brightnessSlider?.let { sb ->
                    val bri = (s?.attributes?.get("brightness") as? Number)?.toInt() ?: if (s?.isActive == true) 255 else 0
                    if (sb.progress != bri) sb.progress = bri
                }
                selectRow?.let { bar ->
                    val opts = (s?.attributes?.get("options") as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
                    bar.removeAllViews()
                    for (opt in opts.take(5)) {
                        val selected = s?.state == opt
                        val chip = textView(ctx, 11f, if (selected) Color.WHITE else ctx.theme.secondaryTextColor, Inter.MEDIUM).apply {
                            text = opt
                            gravity = Gravity.CENTER
                            setPadding(ctx.px(10f), ctx.px(6f), ctx.px(10f), ctx.px(6f))
                            background = GradientDrawable().apply {
                                cornerRadius = 999f
                                setColor(if (selected) ctx.theme.primaryColor else withAlpha(ctx.theme.primaryTextColor, 0.08f))
                            }
                            isClickable = true
                            setOnClickListener {
                                ctx.callService("select", "select_option", JSONObject().put("entity_id", entityId).put("option", opt), null)
                            }
                        }
                        bar.addView(chip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = ctx.px(6f) })
                    }
                }
            }
        } else {
            titleView.text = config.name ?: config.title ?: "Tile"
            stateView.visibility = GONE
            updateBadge(null, active = false)
        }
    }

    private fun bindMushroomTemplate() {
        val primaryTpl = config.raw.optString("primary", config.name.orEmpty())
        val secondaryTpl = config.raw.optString("secondary", "")
        val iconTpl = config.raw.optString("icon", config.icon ?: "mdi:home")

        fun bindTpl(tpl: String, apply: (String) -> Unit) {
            if ("{{" in tpl || "{%" in tpl) {
                track(ctx.subscribeTemplate(tpl) { rendered -> ctx.main.post { apply(rendered) } })
            } else {
                apply(tpl)
            }
        }
        bindTpl(primaryTpl) { titleView.text = it }
        if (secondaryTpl.isNotEmpty()) {
            stateView.visibility = VISIBLE
            bindTpl(secondaryTpl) { stateView.text = it }
        } else {
            stateView.visibility = GONE
        }
        bindTpl(iconTpl) { iconName ->
            val accent = ctx.theme.primaryColor
            iconBadge.setIcon(iconName.ifBlank { "mdi:home" }, accent)
            iconWrap.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(withAlpha(accent, 0.2f))
            }
        }
        config.entity?.let { eid -> observe(eid) { /* keep entity warm for tap actions */ } }
    }

    private fun renderState(s: EntityState?) {
        titleView.text = config.name ?: s?.friendlyName ?: config.entity.orEmpty()
        val hideState = config.raw.optBoolean("hide_state", false)
        stateView.visibility = if (hideState) GONE else VISIBLE
        stateView.text = s?.formattedState() ?: "…"
        updateBadge(s, active = s?.isActive == true)
    }

    private fun updateBadge(s: EntityState?, active: Boolean) {
        val color = if (active) ctx.theme.activeColor(s?.domain.orEmpty()) else ctx.theme.secondaryTextColor
        val iconName = MdiIcons.resolveIconName(config.icon, s)
        iconBadge.setIcon(iconName, color)
        iconWrap.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(withAlpha(color, if (active) 0.22f else 0.12f))
        }
    }

    companion object {
        private val TOGGLE_DOMAINS = setOf("light", "switch", "fan", "input_boolean", "automation", "script")
    }
}

/** Native HA `entities` card with live rows and interactive switches. */
class EntitiesCardView(ctx: VitroCardContext, config: CardConfig) : CardSurface(ctx) {
    init {
        val pad = ctx.px(14f)
        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        config.title?.let { t ->
            col.addView(textView(ctx, 16f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
                text = t
                setPadding(0, 0, 0, ctx.px(10f))
            })
        }

        val rows = config.entities()
        rows.forEachIndexed { idx, rowCfg ->
            if (idx > 0) {
                col.addView(View(ctx.context).apply {
                    setBackgroundColor(ctx.theme.dividerColor)
                }, LinearLayout.LayoutParams(MATCH_PARENT, 1))
            }
            val row = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, ctx.px(9f), 0, ctx.px(9f))
                isClickable = true
            }
            val iconView = MdiIconView(ctx.context)
            val nameView = textView(ctx, 14f, ctx.theme.primaryTextColor).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val valueView = textView(ctx, 13f, ctx.theme.secondaryTextColor, Inter.MEDIUM).apply {
                maxLines = 1
                gravity = Gravity.END
            }
            val togglePill = View(ctx.context)
            val isSwitchable = rowCfg.entity.substringBefore('.') in setOf("light", "switch", "input_boolean", "fan")

            row.addView(iconView, LinearLayout.LayoutParams(ctx.px(22f), ctx.px(22f)).apply { marginEnd = ctx.px(12f) })
            row.addView(nameView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = ctx.px(8f) })
            if (isSwitchable) {
                row.addView(togglePill, LinearLayout.LayoutParams(ctx.px(36f), ctx.px(20f)))
            } else {
                row.addView(valueView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            }

            val tap = rowCfg.tapAction ?: ActionConfig(if (isSwitchable) "toggle" else "more-info")
            row.setOnClickListener { ctx.performAction(tap, rowCfg.entity) }

            observe(rowCfg.entity) { s ->
                val active = s?.isActive == true
                val tint = if (active) ctx.theme.activeColor(s?.domain.orEmpty()) else ctx.theme.secondaryTextColor
                iconView.setIcon(MdiIcons.resolveIconName(rowCfg.icon, s), tint)
                nameView.text = rowCfg.name ?: s?.friendlyName ?: rowCfg.entity
                if (isSwitchable) {
                    togglePill.background = GradientDrawable().apply {
                        cornerRadius = 999f
                        setColor(if (active) ctx.theme.primaryColor else withAlpha(ctx.theme.secondaryTextColor, 0.28f))
                    }
                } else {
                    valueView.text = s?.formattedState() ?: "…"
                }
            }
            col.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }
}

/** Native HA `glance` card (compact multi-column icons + states). */
class GlanceCardView(ctx: VitroCardContext, config: CardConfig) : CardSurface(ctx) {
    init {
        val pad = ctx.px(12f)
        val root = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        config.title?.let { t ->
            root.addView(textView(ctx, 16f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
                text = t
                setPadding(0, 0, 0, ctx.px(8f))
            })
        }
        val showName = config.raw.optBoolean("show_name", true)
        val showState = config.raw.optBoolean("show_state", true)
        val entities = config.entities()
        val cols = config.raw.optInt("columns", minOf(entities.size.coerceAtLeast(1), 5)).coerceIn(1, 6)

        entities.chunked(cols).forEach { chunk ->
            val row = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = cols.toFloat()
            }
            for (e in chunk) {
                val cell = LinearLayout(ctx.context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(ctx.px(4f), ctx.px(6f), ctx.px(4f), ctx.px(6f))
                    isClickable = true
                }
                val icon = MdiIconView(ctx.context)
                val name = textView(ctx, 11f, ctx.theme.secondaryTextColor).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                }
                val state = textView(ctx, 12f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                }
                cell.addView(icon, LinearLayout.LayoutParams(ctx.px(24f), ctx.px(24f)))
                if (showName) cell.addView(name, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(4f) })
                if (showState) cell.addView(state, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(2f) })
                cell.setOnClickListener {
                    ctx.performAction(e.tapAction ?: ActionConfig("more-info"), e.entity)
                }
                observe(e.entity) { s ->
                    val active = s?.isActive == true
                    val tint = if (active) ctx.theme.activeColor(s?.domain.orEmpty()) else ctx.theme.secondaryTextColor
                    icon.setIcon(MdiIcons.resolveIconName(e.icon, s), tint)
                    name.text = e.name ?: s?.friendlyName ?: e.entity
                    state.text = s?.formattedState() ?: "…"
                }
                row.addView(cell, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
            root.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        addView(root, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }
}

/** Native HA `button` card. */
class ButtonCardView(ctx: VitroCardContext, config: CardConfig) : CardSurface(ctx) {
    init {
        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(ctx.px(14f), ctx.px(16f), ctx.px(14f), ctx.px(16f))
        }
        val icon = MdiIconView(ctx.context)
        val label = textView(ctx, 14f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        col.addView(icon, LinearLayout.LayoutParams(ctx.px(36f), ctx.px(36f)))
        if (config.raw.optBoolean("show_name", true)) {
            col.addView(label, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(8f) })
        }
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val eid = config.entity
        val tap = ActionConfig.parse(config.raw.optJSONObject("tap_action"), "toggle")
        isClickable = true
        setOnClickListener { ctx.performAction(tap, eid) }
        if (eid != null) {
            observe(eid) { s ->
                val active = s?.isActive == true
                icon.setIcon(MdiIcons.resolveIconName(config.icon, s), if (active) ctx.theme.activeColor(s?.domain.orEmpty()) else ctx.theme.secondaryTextColor)
                label.text = config.name ?: s?.friendlyName ?: eid
            }
        } else {
            icon.setIcon(config.icon ?: "mdi:home", ctx.theme.primaryColor)
            label.text = config.name ?: "Button"
        }
    }
}

/** Native HA `heading` card + `custom:mushroom-title-card`. */
class HeadingCardView(ctx: VitroCardContext, config: CardConfig) : LinearLayout(ctx.context) {
    init {
        orientation = VERTICAL
        setPadding(ctx.px(4f), ctx.px(10f), ctx.px(4f), ctx.px(4f))
        val heading = config.raw.optString("heading", config.raw.optString("title", "")).ifEmpty { config.name.orEmpty() }
        val subtitle = config.raw.optString("subtitle", "")
        val style = config.raw.optString("heading_style", "title")
        val sizeSp = if (style == "subtitle") 14f else 20f

        val hView = textView(ctx, sizeSp, ctx.theme.primaryTextColor, Inter.MEDIUM)
        if ("{{" in heading) {
            val unsub = ctx.subscribeTemplate(heading) { rendered -> ctx.main.post { hView.text = rendered } }
            addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) { unsub() }
            })
        } else {
            hView.text = heading
        }
        if (heading.isNotEmpty()) addView(hView)

        if (subtitle.isNotEmpty()) {
            val subView = textView(ctx, 13f, ctx.theme.secondaryTextColor)
            if ("{{" in subtitle) {
                val unsub = ctx.subscribeTemplate(subtitle) { rendered -> ctx.main.post { subView.text = rendered } }
                addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {}
                    override fun onViewDetachedFromWindow(v: View) { unsub() }
                })
            } else {
                subView.text = subtitle
            }
            addView(subView, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.px(2f) })
        }
    }
}

/** Native HA `clock` card (zero-allocation live ticking clock). */
class ClockCardView(ctx: VitroCardContext, config: CardConfig) : CardSurface(ctx) {
    private val showSeconds = config.raw.optBoolean("show_seconds", false)
    private val format24 = config.raw.optString("time_format", "24") != "12"
    private val formatter = SimpleDateFormat(
        when {
            format24 && showSeconds -> "HH:mm:ss"
            format24 -> "HH:mm"
            showSeconds -> "h:mm:ss a"
            else -> "h:mm a"
        },
        Locale.getDefault(),
    )
    private val dateFormatter = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
    private val timeView: TextView
    private val dateView: TextView
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = Date()
            timeView.text = formatter.format(now)
            dateView.text = dateFormatter.format(now)
            val interval = if (showSeconds) 1_000L else 15_000L
            ctx.main.postDelayed(this, interval)
        }
    }

    init {
        val sizeSp = when (config.raw.optString("clock_size", "medium")) {
            "large" -> 42f
            "small" -> 22f
            else -> 32f
        }
        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(ctx.px(16f), ctx.px(18f), ctx.px(16f), ctx.px(18f))
        }
        timeView = textView(ctx, sizeSp, ctx.theme.primaryTextColor, Inter.LIGHT).apply {
            letterSpacing = -0.02f
            fontFeatureSettings = "tnum"
        }
        dateView = textView(ctx, 13f, ctx.theme.secondaryTextColor, Inter.REGULAR)
        col.addView(timeView)
        col.addView(dateView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.px(4f) })
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val now = Date()
        timeView.text = formatter.format(now)
        dateView.text = dateFormatter.format(now)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        tick.run()
    }

    override fun onDetachedFromWindow() {
        running = false
        ctx.main.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }
}

/** Native HA `markdown` card with live Jinja2 template support via `render_template`. */
class MarkdownCardView(ctx: VitroCardContext, config: CardConfig) : CardSurface(ctx) {
    private val body = textView(ctx, 14f, ctx.theme.primaryTextColor)

    init {
        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.px(16f), ctx.px(14f), ctx.px(16f), ctx.px(14f))
        }
        config.title?.let { t ->
            col.addView(textView(ctx, 16f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
                text = t
                setPadding(0, 0, 0, ctx.px(8f))
            })
        }
        body.setLineSpacing(ctx.px(3f).toFloat(), 1f)
        col.addView(body)
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val rawContent = config.raw.optString("content", "")
        if ("{{" in rawContent || "{%" in rawContent) {
            body.text = "…"
            track(ctx.subscribeTemplate(rawContent) { rendered ->
                ctx.main.post { body.text = stripMarkdownSyntax(rendered) }
            })
        } else {
            body.text = stripMarkdownSyntax(rawContent)
        }
    }

    private fun stripMarkdownSyntax(md: String): String =
        md.replace(Regex("""^#{1,6}\s+""", RegexOption.MULTILINE), "")
            .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
            .replace(Regex("""`([^`]+)`"""), "$1")
            .trim()
}

/** Native HA `picture`, `picture-entity`, and `picture-glance` card. */
class PictureCardView(ctx: VitroCardContext, config: CardConfig) : CardSurface(ctx) {
    private val img = ImageView(ctx.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val caption = textView(ctx, 13f, Color.WHITE, Inter.MEDIUM)

    init {
        val heightPx = ctx.px(180f)
        addView(img, LayoutParams(MATCH_PARENT, heightPx))
        val showName = config.raw.optBoolean("show_name", true)
        val showState = config.raw.optBoolean("show_state", true)
        if (config.type != "picture" && (showName || showState)) {
            val bar = LinearLayout(ctx.context).apply {
                setBackgroundColor(0x88000000.toInt())
                setPadding(ctx.px(12f), ctx.px(8f), ctx.px(12f), ctx.px(8f))
                addView(caption)
            }
            addView(bar, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        }

        val staticImage = config.raw.optString("image", "").ifEmpty { null }
        if (staticImage != null) {
            ctx.loadImage(staticImage) { bmp -> if (bmp != null) img.setImageBitmap(bmp) }
        }
        val eid = config.entity ?: config.raw.optString("camera_image", "").ifEmpty { null }
        if (eid != null) {
            isClickable = true
            setOnClickListener { ctx.performAction(ActionConfig.parse(config.raw.optJSONObject("tap_action")), eid) }
            observe(eid) { s ->
                val parts = buildList {
                    if (showName) add(config.name ?: s?.friendlyName ?: eid)
                    if (showState && s != null) add(s.formattedState())
                }
                caption.text = parts.joinToString(" · ")
                if (staticImage == null) {
                    s?.entityPicture?.let { pic ->
                        ctx.loadImage(pic) { bmp -> if (bmp != null) img.setImageBitmap(bmp) }
                    }
                }
            }
        }
    }
}

/** Native Tier 2 fast-path for `custom:mushroom-chips-card`. */
class MushroomChipsCardView(ctx: VitroCardContext, config: CardConfig) : LinearLayout(ctx.context) {
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, ctx.px(4f), 0, ctx.px(4f))
        val chips = config.raw.optJSONArray("chips")
        if (chips != null) {
            for (i in 0 until chips.length()) {
                val c = chips.optJSONObject(i) ?: continue
                val chip = LinearLayout(ctx.context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        cornerRadius = 999f
                        setColor(ctx.theme.cardBackgroundColor)
                        setStroke(ctx.px(1f).coerceAtLeast(1), ctx.theme.cardBorderColor)
                    }
                    setPadding(ctx.px(12f), ctx.px(8f), ctx.px(12f), ctx.px(8f))
                    isClickable = true
                }
                val icon = MdiIconView(ctx.context)
                val label = textView(ctx, 12f, ctx.theme.primaryTextColor, Inter.MEDIUM)
                chip.addView(icon, LayoutParams(ctx.px(16f), ctx.px(16f)).apply { marginEnd = ctx.px(6f) })
                chip.addView(label)

                val eid = c.optString("entity", "").ifEmpty { null }
                val tap = ActionConfig.parse(c.optJSONObject("tap_action"), "more-info")
                chip.setOnClickListener { ctx.performAction(tap, eid) }
                if (eid != null) {
                    val render: (EntityState?) -> Unit = { s ->
                        val active = s?.isActive == true
                        icon.setIcon(MdiIcons.resolveIconName(c.optString("icon", "").ifEmpty { null }, s),
                            if (active) ctx.theme.activeColor(s?.domain.orEmpty()) else ctx.theme.secondaryTextColor)
                        label.text = s?.formattedState() ?: "…"
                    }
                    render(ctx.store[eid])
                    val unsub = ctx.store.subscribe(eid) { s -> ctx.main.post { render(s) } }
                    chip.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) {}
                        override fun onViewDetachedFromWindow(v: View) { unsub() }
                    })
                } else {
                    icon.setIcon(c.optString("icon", "mdi:home"), ctx.theme.primaryColor)
                    label.text = c.optString("content", c.optString("type", "Chip"))
                }
                addView(chip, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = ctx.px(8f) })
            }
        }
    }
}

/** Native Tier 2 fast-path for `custom:bubble-card` (button & separator). */
class BubbleCardView(ctx: VitroCardContext, config: CardConfig) : CardSurface(ctx) {
    init {
        val cardType = config.raw.optString("card_type", "button")
        if (cardType == "separator") {
            background = null
            val row = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.px(4f), ctx.px(10f), ctx.px(4f), ctx.px(6f))
            }
            val icon = MdiIconView(ctx.context).apply { setIcon(config.icon ?: "mdi:home", ctx.theme.secondaryTextColor) }
            val title = textView(ctx, 14f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply { text = config.name.orEmpty() }
            val line = View(ctx.context).apply { setBackgroundColor(ctx.theme.dividerColor) }
            row.addView(icon, LinearLayout.LayoutParams(ctx.px(18f), ctx.px(18f)).apply { marginEnd = ctx.px(8f) })
            row.addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = ctx.px(12f) })
            row.addView(line, LinearLayout.LayoutParams(0, ctx.px(2f), 1f))
            addView(row, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        } else {
            // Pill-shaped Bubble button card
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(ctx.theme.cardBackgroundColor)
                setStroke(ctx.px(1f).coerceAtLeast(1), ctx.theme.cardBorderColor)
            }
            val row = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.px(10f), ctx.px(10f), ctx.px(16f), ctx.px(10f))
            }
            val icon = MdiIconView(ctx.context)
            val circle = LinearLayout(ctx.context).apply {
                gravity = Gravity.CENTER
                setPadding(ctx.px(8f), ctx.px(8f), ctx.px(8f), ctx.px(8f))
                addView(icon, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
            val name = textView(ctx, 14f, ctx.theme.primaryTextColor, Inter.MEDIUM)
            val sub = textView(ctx, 12f, ctx.theme.secondaryTextColor)
            val textCol = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.VERTICAL
                addView(name); addView(sub)
            }
            row.addView(circle, LinearLayout.LayoutParams(ctx.px(38f), ctx.px(38f)).apply { marginEnd = ctx.px(12f) })
            row.addView(textCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(row, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

            val eid = config.entity
            val tap = ActionConfig.parse(config.raw.optJSONObject("tap_action"), "toggle")
            isClickable = true
            setOnClickListener { ctx.performAction(tap, eid) }
            if (eid != null) {
                observe(eid) { s ->
                    val active = s?.isActive == true
                    val tint = if (active) ctx.theme.activeColor(s?.domain.orEmpty()) else ctx.theme.secondaryTextColor
                    icon.setIcon(MdiIcons.resolveIconName(config.icon, s), tint)
                    circle.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(withAlpha(tint, if (active) 0.24f else 0.12f))
                    }
                    name.text = config.name ?: s?.friendlyName ?: eid
                    sub.text = s?.formattedState() ?: "…"
                }
            } else {
                icon.setIcon(config.icon ?: "mdi:home", ctx.theme.primaryColor)
                name.text = config.name ?: "Bubble"
                sub.visibility = GONE
            }
        }
    }
}

/** Native HA `media-control` card. */
class MediaControlCardView(ctx: VitroCardContext, config: CardConfig) : CardSurface(ctx) {
    init {
        val row = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.px(14f), ctx.px(14f), ctx.px(14f), ctx.px(14f))
        }
        val icon = MdiIconView(ctx.context)
        val title = textView(ctx, 15f, ctx.theme.primaryTextColor, Inter.MEDIUM).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val artist = textView(ctx, 12f, ctx.theme.secondaryTextColor).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val playBtn = MdiIconView(ctx.context).apply {
            isClickable = true
            setPadding(ctx.px(8f), ctx.px(8f), ctx.px(8f), ctx.px(8f))
        }
        val col = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title); addView(artist)
        }
        row.addView(icon, LinearLayout.LayoutParams(ctx.px(28f), ctx.px(28f)).apply { marginEnd = ctx.px(12f) })
        row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(playBtn, LinearLayout.LayoutParams(ctx.px(38f), ctx.px(38f)))
        addView(row, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val eid = config.entity
        if (eid != null) {
            playBtn.setOnClickListener {
                ctx.callService("media_player", "media_play_pause", org.json.JSONObject().put("entity_id", eid), null)
            }
            observe(eid) { s ->
                val playing = s?.state == "playing"
                icon.setIcon("mdi:speaker", if (playing) ctx.theme.primaryColor else ctx.theme.secondaryTextColor)
                title.text = (s?.attributes?.get("media_title") as? String)?.takeIf { it.isNotBlank() } ?: s?.friendlyName ?: eid
                artist.text = (s?.attributes?.get("media_artist") as? String)?.takeIf { it.isNotBlank() } ?: s?.formattedState().orEmpty()
                playBtn.setIcon(if (playing) "mdi:pause" else "mdi:play", ctx.theme.primaryTextColor)
            }
        }
    }
}
