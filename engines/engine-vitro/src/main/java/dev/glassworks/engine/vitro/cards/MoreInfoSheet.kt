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
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import dev.glassworks.engine.vitro.data.HaText
import dev.glassworks.engine.vitro.data.HaLocale
import dev.glassworks.engine.vitro.data.HaFormat
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.HaDashboardClient
import dev.glassworks.engine.vitro.data.LightColor
import dev.glassworks.engine.vitro.theme.ResolvedTheme
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.MdiIcons
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.ui.Inter
import dev.glassworks.engine.vitro.ui.VitroFonts
import dev.glassworks.engine.vitro.theme.ThemeResolver
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Native More-Info dialog matching Home Assistant's `ha-more-info-dialog` (frontend 20261028): `ha-adaptive-dialog`'s
 * centred dialog when the window is wider than 870 and taller than 500 CSS px (580 px wide, 40 px from the top), else
 * its bottom sheet (the window's width, as tall as the window less 48 px), over a 25 % black scrim, on the dialog
 * surface of the user's theme (the view's theme never reaches HA's dialogs). Its header names the entity on two lines,
 * area and device over the entity's own name (#885). Each domain gets HA's own controls (the big vertical brightness
 * and toggle pills, cover buttons, climate stepper, option lists, media transport); a sensor gets HA's state row and
 * history ([MoreInfoHistoryView]: statistics or the state history, and "Show more"), the History tab of the others
 * the same history. Raw attributes are never printed.
 */
class MoreInfoSheet(private val ctx: VitroCardContext, private val onDismiss: () -> Unit) : FrameLayout(ctx.context) {
    private val theme: ResolvedTheme = ctx.theme

    /** HA's dialog mode: not `(max-width: 870px), (max-height: 500px)`, else its bottom sheet. */
    private val wide = ctx.context.resources.configuration.let { it.screenWidthDp > SHEET_MAX_WIDTH_DP && it.screenHeightDp > SHEET_MAX_HEIGHT_DP }

    /**
     * Compact landscape wall panels (the S1 Plus at 1440×720 is 480 dp tall): the pills, wheel and dial shrink
     * and the gaps tighten so the header, the control and its dock fit without scrolling.
     */
    private val compact = ctx.context.resources.displayMetrics.heightPixels / ctx.dp < COMPACT_HEIGHT_DP
    private val pillW = if (compact) 116f else 132f
    private val pillH = if (compact) 196f else 276f
    private val wheelDp = if (compact) 196f else 248f
    private val dialDp = if (compact) 204f else 260f
    private val controlGap = if (compact) 10f else 24f
    private val dockGap = if (compact) 10f else 20f
    private val dialGap = if (compact) 6f else 12f
    private val panel = DialogSurface(ctx.context, wide, ctx.dp)

    /** The header's two lines: `.breadcrumb` (area ▸ device) over `.main` (the entity's own name). */
    private val breadcrumbView = cssText(ctx, 14f, theme.secondaryTextColor).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
    }
    private val titleView = cssText(ctx, 20f, theme.primaryTextColor, Inter.MEDIUM).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
    }
    private val historyIcon = MdiIconView(ctx.context)
    private val historyButton = iconButton(historyIcon, "mdi:chart-box-outline").apply {
        // HA's header button between the controls and the history: pressed while the history shows.
        dev.glassworks.engine.vitro.ui.VitroAccess.role(this, dev.glassworks.engine.vitro.ui.A11yRole.TOGGLE_BUTTON, "History") { showingHistory }
    }

    /** The sheet's surface: what a screenshot compares with HA's dialog (the parity harness, #885). */
    internal val surface: View get() = panel

    /** The header's Close: where keyboard focus starts when the sheet opens. */
    private var closeButton: View? = null
    private val footerSlot = FrameLayout(ctx.context).apply { visibility = GONE }
    private val body = LinearLayout(ctx.context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.px(24f), ctx.px(if (compact) 4f else 8f), ctx.px(24f), ctx.px(if (compact) 12f else 24f))
    }

    /**
     * What Clarity's native harness times (VitroClarity): the open animation's start and end, the history graph's data,
     * the close animation's end. Null outside a Clarity run. Main thread.
     */
    internal interface Trace {
        /** The sheet's first frame: its enter animation ([durationMs]) starts. [wide]: the centred dialog. */
        fun entered(wide: Boolean, durationMs: Long)

        /** The enter animation finished. */
        fun shown()

        /** The history graph [chart] got its data. */
        fun graph(chart: View)

        /** The exit animation finished. */
        fun closed()
    }

    internal var trace: Trace? = null

    private var entityId: String? = null
    private var unsub: (() -> Unit)? = null
    private var updater: ((EntityState?) -> Unit)? = null
    private val releasers = ArrayList<() -> Unit>()
    private var showingHistory = false
    private var dismissed = false

    /** Closed, or closing: its exit may still be running (VitroView tidies one a detach cut short). */
    val isDismissed: Boolean get() = dismissed

    /** The dimmed backdrop (`--mdc-dialog-scrim-color`, 25 % black); its alpha fades with the sheet. */
    private val scrim = ColorDrawable(ThemeResolver.parseColor(theme.vars["mdc-dialog-scrim-color"]) ?: SCRIM)
    private var scrimAnimator: ValueAnimator? = null
    private var enterListener: ViewTreeObserver.OnPreDrawListener? = null

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
        background = scrim
        isClickable = true
        setOnClickListener { dismiss() }
        // The scrim and the panel take taps only to close the sheet or keep it open: not controls for TalkBack or
        // keys, which close it with Close, Back or Escape as HA's dialog does.
        dev.glassworks.engine.vitro.ui.VitroAccess.notAControl(this)

        // The dialog's corners (`--ha-border-radius-3xl`) or the sheet's top ones (`--ha-border-radius-2xl`).
        val radius = (if (wide) DIALOG_RADIUS else SHEET_RADIUS) * ctx.dp
        panel.apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true // swallows taps so only the scrim dismisses
            dev.glassworks.engine.vitro.ui.VitroAccess.notAControl(this)
            background = GradientDrawable().apply {
                setColor(dialogSurface(theme))
                cornerRadii = if (wide) FloatArray(8) { radius }
                else floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            clipToOutline = true
            // ha-bottom-sheet's handle: 40 × 4 px in the divider color, centred on the sheet's top 16 px, over the header.
            if (!wide) handle = GradientDrawable().apply {
                cornerRadius = 8f * ctx.dp
                setColor(theme.dividerColor)
            }
        }

        // ha-dialog-header: 68 px, 8 px sides (4 in the sheet), 48 px icon buttons, the title between them.
        val header = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val side = ctx.px(if (wide) 8f else 4f)
            setPadding(side, 0, side, 0)
        }
        val closeIcon = MdiIconView(ctx.context)
        val closeButton = iconButton(closeIcon, "mdi:close").apply {
            contentDescription = HaText.ui("ui.common.close")
            setOnClickListener { dismiss() }
        }
        this.closeButton = closeButton
        header.addView(closeButton, LinearLayout.LayoutParams(ctx.px(48f), ctx.px(48f)))
        // .title: the breadcrumb's 16 px line 18 px down, the name's 24 px line under it (22 px down without one).
        val titles = FrameLayout(ctx.context)
        titles.addView(breadcrumbView, LayoutParams(MATCH_PARENT, ctx.px(16f)).apply { topMargin = ctx.px(18f) })
        titles.addView(titleView, LayoutParams(MATCH_PARENT, ctx.px(24f)).apply { topMargin = ctx.px(34f) })
        header.addView(titles, LinearLayout.LayoutParams(0, ctx.px(HEADER_HEIGHT), 1f).apply {
            marginStart = ctx.px(4f)
            marginEnd = ctx.px(4f)
        })
        historyButton.setOnClickListener {
            showingHistory = !showingHistory
            buildBody()
            fadeIn(body)
        }
        header.addView(historyButton, LinearLayout.LayoutParams(ctx.px(48f), ctx.px(48f)))
        panel.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(HEADER_HEIGHT)))

        val scroll = ScrollView(ctx.context).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = false
            addView(body, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        panel.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 1f))
        // A dialog's sticky footer (an update's actions): under the content, at the panel's bottom while it scrolls.
        panel.addView(footerSlot, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        if (wide) {
            // `min(var(--ha-dialog-width-md, 580px), 95vw)`, its top 40 px down (half the 80 px its height leaves).
            val screenW = ctx.context.resources.displayMetrics.widthPixels
            val w = minOf(ctx.px(DIALOG_WIDTH), (screenW * 0.95f).toInt())
            addView(panel, LayoutParams(w, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = ctx.px(DIALOG_MARGIN / 2f) })
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
        // `_shouldShowHistory`: a dialog of its own, and HA's history or its logbook (the tab shows either).
        val tab = historyLoaded() || HaLogbook.shows(entityId, ctx.store[entityId], ctx.haConfig())
        historyButton.visibility = if (domain in HISTORY_TOGGLE_DOMAINS && tab) VISIBLE else GONE
        buildBody()
        unsub = ctx.store.subscribe(entityId) { s -> ctx.main.post { if (!dismissed) updater?.invoke(s) } }
        ctx.main.removeCallbacks(clockTick)
        ctx.main.postDelayed(clockTick, TIME_REFRESH_MS)

        playEnter()
        // A keyboard lands in the sheet, as in HA's dialog, and TalkBack announces it by the entity's name.
        closeButton?.requestFocus()
    }

    /** Back and Escape close the sheet, as Escape closes HA's dialog. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK || event.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == android.view.KeyEvent.ACTION_UP && !dismissed) dismiss()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * HA 2026's `ha-dialog` / `ha-bottom-sheet` entrance. Narrow: the sheet slides up from its own height over
     * 240 ms (decelerate) while the scrim fades in. Wide: the card scales 0.94 → 1 and fades in over 200 ms.
     */
    private fun playEnter() {
        cancelMotion()
        panel.alpha = 1f
        panel.scaleX = 1f
        panel.scaleY = 1f
        panel.translationY = 0f
        scrim.alpha = 0
        val duration = if (wide) ENTER_WIDE_MS else ENTER_SHEET_MS
        if (wide) {
            panel.alpha = 0f
            panel.scaleX = ENTER_SCALE
            panel.scaleY = ENTER_SCALE
        } else {
            panel.translationY = SHEET_FALLBACK_DP * ctx.dp
        }
        // Wait for the first layout so the sheet starts exactly one sheet-height below its resting place.
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                panel.viewTreeObserver.removeOnPreDrawListener(this)
                if (enterListener === this) enterListener = null
                if (dismissed) return true
                if (!wide && panel.height > 0) panel.translationY = panel.height.toFloat()
                // On a hardware layer while it runs: the panel's content is recorded once and each frame only moves
                // (or fades) the layer, rather than drawing the whole sheet again over the dashboard's live cameras.
                // The layer goes when the entrance ends.
                val anim = panel.animate().setDuration(duration).setInterpolator(HaMotion.decelerate()).withLayer()
                if (wide) anim.alpha(1f).scaleX(1f).scaleY(1f) else anim.translationY(0f)
                trace?.let { t ->
                    // As the sheet runs it: Android's "Animator duration scale" (0 with "Remove animations").
                    val scale = runCatching {
                        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
                    }.getOrDefault(1f)
                    t.entered(wide, (duration * scale).toLong())
                    anim.withEndAction { t.shown() }
                }
                anim.start()
                fadeScrim(255, duration, HaMotion.decelerate())
                return true
            }
        }
        enterListener = listener
        panel.viewTreeObserver.addOnPreDrawListener(listener)
    }

    private fun fadeScrim(to: Int, duration: Long, interpolator: android.animation.TimeInterpolator, onEnd: (() -> Unit)? = null) {
        scrimAnimator?.cancel()
        scrimAnimator = ValueAnimator.ofInt(scrim.alpha, to).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener { scrim.alpha = it.animatedValue as Int }
            if (onEnd != null) {
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                    override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) onEnd() }
                })
            }
            start()
        }
    }

    /**
     * Closes the sheet. On screen it slides down (narrow) or fades and shrinks to 0.94 (wide) over 180 ms with the
     * accelerate ease while the scrim fades out, then calls `onDismiss`; off screen it tears down at once.
     */
    fun dismiss() {
        if (dismissed) return
        dismissed = true
        release()
        if (!isAttachedToWindow) {
            cancelMotion()
            trace?.closed()
            onDismiss()
            return
        }
        cancelMotion()
        val anim = panel.animate().setDuration(EXIT_MS).setInterpolator(HaMotion.accelerate())
        if (wide) {
            anim.alpha(0f).scaleX(ENTER_SCALE).scaleY(ENTER_SCALE)
        } else {
            anim.translationY(panel.height.toFloat().coerceAtLeast(EXIT_MIN_DP * ctx.dp)).alpha(0f)
        }
        anim.start()
        fadeScrim(0, EXIT_MS, HaMotion.accelerate()) {
            trace?.closed()
            // The host may already have removed us (and opened another sheet): only report while still attached.
            if (isAttachedToWindow) onDismiss()
        }
    }

    /** Swallows touches while the sheet is closing so nothing under the fading panel reacts. */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = dismissed || super.onInterceptTouchEvent(ev)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = if (dismissed) true else super.onTouchEvent(event)

    private fun cancelMotion() {
        enterListener?.let { panel.viewTreeObserver.removeOnPreDrawListener(it) }
        enterListener = null
        panel.animate().cancel()
        scrimAnimator?.cancel()
        scrimAnimator = null
    }

    override fun onDetachedFromWindow() {
        cancelMotion()
        release()
        super.onDetachedFromWindow()
    }

    /** HA's tab / mode cross-fade: the incoming view fades 0 → 1 over 150 ms (standard ease) on screen. */
    private fun fadeIn(v: View) {
        v.animate().cancel()
        if (!v.isAttachedToWindow) {
            v.alpha = 1f
            return
        }
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(CROSS_FADE_MS).setInterpolator(HaMotion.standard()).start()
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
        setTitle(id, s)
        historyIcon.setIcon("mdi:chart-box-outline", if (showingHistory) theme.primaryColor else theme.primaryTextColor)
        val domain = id.substringBefore('.')
        // The sensor's content (`.content`, 24 px all round) and the history tab's (8 px over, 24 under) have their
        // own sides; every other domain keeps its control layout's.
        val custom = showingHistory || domain !in CONTROL_DOMAINS
        footerSlot.removeAllViews()
        footerSlot.visibility = GONE
        // HA's `.content` (24 px all round) around the controls drawn as HA's newer dialogs are; an update's sides are
        // its own (its state row and summary), and its sticky footer takes the bottom's place.
        if (!showingHistory && domain == "update") body.setPadding(0, ctx.px(24f), 0, 0)
        else if (!showingHistory && domain in CONTENT_DOMAINS) body.setPadding(ctx.px(24f), ctx.px(24f), ctx.px(24f), ctx.px(24f))
        else if (custom) body.setPadding(0, ctx.px(if (showingHistory) 8f else 24f), 0, ctx.px(24f))
        else body.setPadding(ctx.px(24f), ctx.px(if (compact) 4f else 8f), ctx.px(24f), ctx.px(if (compact) 12f else 24f))
        val update: (EntityState?) -> Unit = if (showingHistory) {
            buildSensor(id, stateRow = false)
        } else when (domain) {
            "light" -> buildLight(id, s)
            "switch", "input_boolean", "automation", "siren", "humidifier" -> buildToggle(id, domain)
            "fan" -> buildFan(id, s)
            "camera" -> buildCamera(id)
            "climate" -> buildClimate(id)
            "cover" -> buildCover(id, s)
            "lock" -> buildLock(id)
            "media_player" -> buildMedia(id)
            "alarm_control_panel" -> buildAlarm(id, s)
            "script", "scene" -> buildAction(id, domain)
            "vacuum" -> MoreInfoVacuumView(ctx, id).also { body.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }::update
            "update" -> MoreInfoUpdateView(ctx, id) { url ->
                // The release announcement: HA opens it in a new tab; the page goes there, as a `url` action does.
                dismiss()
                ctx.navigate(url)
            }.also {
                body.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                footerSlot.addView(it.footer, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                footerSlot.visibility = VISIBLE
            }::update
            else -> buildSensor(id, stateRow = true)
        }
        updater = { st ->
            setTitle(id, st)
            update(st)
        }
        updater?.invoke(s)
    }

    /**
     * The header's title as HA's dialog names an entity (`computeEntityNameParts`): its area, device and own name, the
     * last one present on the `.main` line and the rest over it, joined by " ▸ ". Without a registry entry, the
     * friendly name alone.
     */
    private fun setTitle(id: String, s: EntityState?) {
        val parts = HaDisplay.nameParts(id, s?.friendlyName ?: id)
        val crumbs = listOfNotNull(parts.area, parts.device, parts.entity)
        val main = crumbs.lastOrNull() ?: id
        val crumb = crumbs.dropLast(1).joinToString(" ▸ ")
        if (titleView.text?.toString() != main) titleView.text = main
        if (breadcrumbView.text?.toString() != crumb) breadcrumbView.text = crumb
        breadcrumbView.visibility = if (crumb.isEmpty()) GONE else VISIBLE
        (titleView.layoutParams as? LayoutParams)?.let { lp ->
            val top = ctx.px(if (crumb.isEmpty()) 22f else 34f)
            if (lp.topMargin != top) {
                lp.topMargin = top
                titleView.layoutParams = lp
            }
        }
        // TalkBack names the sheet by the whole name, as HA's dialog is labelled by its title.
        val full = crumbs.joinToString(" ")
        // The title last set is kept here: View's getter is API 28, and the setter runs only where it exists.
        if (full != paneTitle) {
            paneTitle = full
            if (dev.glassworks.platform.Platform.hasAccessibilityPaneTitle) panel.accessibilityPaneTitle = full
        }
    }

    // ---- Domain views ----

    private fun buildLight(id: String, initial: EntityState?): (EntityState?) -> Unit {
        val stateLabel = bigState()
        val timeLabel = timeLabel()
        addCentered(stateLabel)
        addCentered(timeLabel, topDp = 4f)
        val caps = LightCaps.of(initial)
        val fallback = theme.activeColor("light")
        if (!caps.brightness) {
            val toggle = VerticalTogglePillView(ctx.context, fallback, theme.dark).apply {
                onToggle = { on -> optimisticToggle(id, "light", on) }
            }
            addPill(toggle)
            val power = circleButton("mdi:power", HaText.ui("ui.card.common.toggle"), 52f, 24f) { optimisticToggle(id, "light", ctx.store[id]?.state != "on") }
            addCentered(power, topDp = dockGap, widthDp = 52f, heightDp = 52f)
            return { s ->
                stateLabel.text = lightStateText(s)
                toggle.setOn(s?.state == "on")
                timeLabel.text = relativeTime(s)
            }
        }

        // The last real brightness (1..100 %) seen, so an optimistic `on` restores it instead of flashing 100%.
        var lastPct: Int? = initial?.takeIf { it.state == "on" }?.let { brightnessOf(it) }

        // The three center controls share one frame; the dock below switches between them.
        val center = FrameLayout(ctx.context)
        val slider = VerticalSliderPillView(ctx.context, initial?.lightTintColor() ?: fallback).apply {
            // Named for TalkBack, as HA names the slider (the attribute it sets).
            contentDescription = HaText.ui("ui.card.light.brightness")
            onChange = { pct -> stateLabel.text = if (pct <= 0) "Off" else "$pct%" }
            onRelease = { pct ->
                val data = JSONObject().put("entity_id", id)
                val bri = (pct * 255 / 100).coerceIn(1, 255)
                val next = ctx.store[id]?.let { cur ->
                    if (pct <= 0) cur.copy(state = "off") else cur.copy(state = "on", attributes = cur.attributes + ("brightness" to bri))
                }
                if (pct <= 0) ctx.callOptimistic(next, "light", "turn_off", data)
                else ctx.callOptimistic(next, "light", "turn_on", data.put("brightness", bri))
            }
        }
        center.addView(slider, LayoutParams(ctx.px(pillW), ctx.px(pillH), Gravity.CENTER))
        val wheel = if (caps.color) {
            RgbColorWheelView(ctx.context).apply {
                onRelease = { c -> setLightColor(id, c, null) }
                center.addView(this, LayoutParams(ctx.px(wheelDp), ctx.px(wheelDp), Gravity.CENTER))
            }
        } else {
            null
        }
        val minK = (initial?.attributes?.get("min_color_temp_kelvin") as? Number)?.toInt() ?: ColorTempSliderView.DEFAULT_MIN_K
        val maxK = (initial?.attributes?.get("max_color_temp_kelvin") as? Number)?.toInt() ?: ColorTempSliderView.DEFAULT_MAX_K
        val temp = if (caps.colorTemp) {
            ColorTempSliderView(ctx.context, minK, maxK).apply {
                // Named for TalkBack, as HA names the slider (the attribute it sets).
                contentDescription = HaText.ui("ui.card.light.color_temperature")
                onChange = { k -> stateLabel.text = "$k K" }
                onRelease = { k -> setLightColor(id, LightColor.kelvinToArgb(k), k) }
                center.addView(this, LayoutParams(ctx.px(pillW), ctx.px(pillH), Gravity.CENTER))
            }
        } else {
            null
        }
        body.addView(center, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(pillH)).apply { topMargin = ctx.px(controlGap) })

        // HA 2026's dock: power, a divider, then one button per control.
        var mode = LightMode.BRIGHTNESS
        val dock = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = ctx.px(6f)
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                cornerRadius = 999f * ctx.dp
                setColor(if (theme.dark) DOCK_DARK else DOCK_LIGHT)
            }
        }
        val dockSize = LinearLayout.LayoutParams(ctx.px(44f), ctx.px(44f))
        val powerIcon = MdiIconView(ctx.context)
        dock.addView(FrameLayout(ctx.context).apply {
            powerIcon.setIcon("mdi:power", theme.primaryTextColor)
            addView(powerIcon, LayoutParams(ctx.px(22f), ctx.px(22f), Gravity.CENTER))
            isClickable = true
            ripple(this)
            contentDescription = HaText.ui("ui.card.common.toggle")
            setOnClickListener {
                val cur = ctx.store[id]
                val next = if (cur != null && cur.state in setOf("on", "off")) cur.copy(state = if (cur.state == "on") "off" else "on") else null
                ctx.callOptimistic(next, "light", "toggle", JSONObject().put("entity_id", id))
            }
        }, LinearLayout.LayoutParams(dockSize))
        val modeButtons = ArrayList<Pair<LightMode, LightModeButton>>()
        if (caps.color || caps.colorTemp) {
            dock.addView(View(ctx.context).apply { setBackgroundColor(theme.dividerColor) },
                LinearLayout.LayoutParams(ctx.px(1f).coerceAtLeast(1), ctx.px(24f)).apply {
                    marginStart = ctx.px(6f); marginEnd = ctx.px(6f)
                })
            val kinds = buildList {
                add(LightMode.BRIGHTNESS)
                if (caps.color) add(LightMode.COLOR)
                if (caps.colorTemp) add(LightMode.TEMP)
            }
            kinds.forEachIndexed { i, k ->
                val b = LightModeButton(ctx.context, k, theme)
                ripple(b)
                modeButtons += k to b
                dock.addView(b, LinearLayout.LayoutParams(dockSize).apply { if (i > 0) marginStart = ctx.px(4f) })
            }
        }
        fun showMode(m: LightMode) {
            val changed = m != mode
            mode = m
            slider.visibility = if (m == LightMode.BRIGHTNESS) VISIBLE else GONE
            wheel?.visibility = if (m == LightMode.COLOR) VISIBLE else GONE
            temp?.visibility = if (m == LightMode.TEMP) VISIBLE else GONE
            modeButtons.forEach { (k, b) -> b.chosen = k == m }
            if (changed) {
                when (m) {
                    LightMode.BRIGHTNESS -> slider
                    LightMode.COLOR -> wheel
                    LightMode.TEMP -> temp
                }?.let(::fadeIn)
            }
        }
        modeButtons.forEach { (k, b) -> b.setOnClickListener { showMode(k); updater?.invoke(ctx.store[id]) } }
        showMode(LightMode.BRIGHTNESS)
        addCentered(dock, topDp = dockGap)

        // Favorite colors: warm whites always, the colored row when the light takes RGB.
        if (caps.color || caps.colorTemp) {
            val grid = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
            val rows = if (caps.color) listOf(FAVORITES_WARM, FAVORITES_COLOR) else listOf(FAVORITES_WARM)
            rows.forEachIndexed { r, favorites ->
                val row = LinearLayout(ctx.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                favorites.forEachIndexed { i, fav ->
                    val dot = View(ctx.context).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(fav.color)
                            setStroke(ctx.px(1f).coerceAtLeast(1), theme.dividerColor)
                        }
                        isClickable = true
                        ripple(this)
                        contentDescription = fav.name
                        setOnClickListener {
                            if (caps.color) setLightColor(id, fav.color, null)
                            else setLightColor(id, LightColor.kelvinToArgb(fav.kelvin ?: 2700), fav.kelvin ?: 2700)
                        }
                    }
                    row.addView(dot, LinearLayout.LayoutParams(ctx.px(42f), ctx.px(42f)).apply {
                        if (i > 0) marginStart = ctx.px(16f)
                    })
                }
                grid.addView(row, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    if (r > 0) topMargin = ctx.px(14f)
                })
            }
            addCentered(grid, topDp = 24f)
        }

        return { s ->
            val tint = s?.lightTintColor() ?: fallback
            slider.setActiveColor(tint)
            val on = s?.state == "on"
            val pct = if (on) brightnessOf(s) else null
            val kelvin = (s?.attributes?.get("color_temp_kelvin") as? Number)?.toInt()
            val busy = slider.adjusting || wheel?.dragging == true || temp?.adjusting == true
            if (!busy) {
                stateLabel.text = if (mode == LightMode.TEMP && on && kelvin != null) "$kelvin K" else lightStateText(s)
            }
            slider.active = on
            when {
                !on -> slider.setPercent(0)
                pct != null -> {
                    lastPct = pct
                    slider.setPercent(pct)
                }
                // Optimistic `on` before HA reports `brightness`: restore the last level, or hold still until the
                // real value lands; never flash to 100%.
                else -> lastPct?.let { slider.setPercent(it) }
            }
            (s?.attributes?.get("rgb_color") as? List<*>)?.let { rgb ->
                val c = rgb.take(3).map { (it as? Number)?.toInt() ?: 0 }
                if (c.size == 3) wheel?.setColor(LightColor.pack(c[0], c[1], c[2]))
            }
            kelvin?.let { temp?.setKelvin(it) }
            timeLabel.text = relativeTime(s)
        }
    }

    /** Sets a light's color (and color temperature, when [kelvin] is given) optimistically, then calls HA. */
    private fun setLightColor(id: String, color: Int, kelvin: Int?) {
        val rgb = listOf(LightColor.red(color), LightColor.green(color), LightColor.blue(color))
        var next: EntityState? = null
        ctx.store[id]?.let { cur ->
            val attrs = cur.attributes.toMutableMap()
            attrs["rgb_color"] = rgb
            if (kelvin != null) {
                attrs["color_temp_kelvin"] = kelvin
                attrs["color_mode"] = "color_temp"
            } else {
                attrs.remove("color_temp_kelvin")
                attrs.remove("color_temp")
                attrs["color_mode"] = "rgb"
            }
            next = cur.copy(state = "on", attributes = attrs)
        }
        val data = JSONObject().put("entity_id", id)
        if (kelvin != null) data.put("color_temp_kelvin", kelvin)
        else data.put("rgb_color", JSONArray().put(rgb[0]).put(rgb[1]).put(rgb[2]))
        ctx.callOptimistic(next, "light", "turn_on", data)
    }

    /** A light's real brightness as 1..100 %, or null while HA hasn't reported one (off, or an optimistic `on`). */
    private fun brightnessOf(s: EntityState?): Int? {
        val raw = (s?.attributes?.get("brightness") as? Number)?.toDouble() ?: return null
        if (raw <= 0.0) return null
        return s.brightnessPct?.coerceIn(1, 100)
    }

    private fun lightStateText(s: EntityState?): String {
        val on = s?.state == "on"
        val pct = if (on) brightnessOf(s) else null
        return when {
            s == null -> HaText.ui("state.default.unknown")
            s.isUnavailable -> s.formattedState()
            on && pct != null -> HaFormat.formatNumber(pct) + HaFormat.blankBeforePercent(HaLocale.current) + "%"
            else -> s.plainState()
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
                s == null -> HaText.ui("state.default.unknown")
                else -> s.plainState()
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
            // Named for TalkBack, as HA names the slider (the attribute it sets).
            contentDescription = "Speed"
            onChange = { pct -> stateLabel.text = if (pct <= 0) "Off" else "$pct%" }
            onRelease = { pct ->
                ctx.callService("fan", "set_percentage", JSONObject().put("entity_id", id).put("percentage", pct), null)
            }
        }
        addPill(slider)
        val power = circleButton("mdi:power", HaText.ui("ui.card.common.toggle"), 52f, 24f) { optimisticToggle(id, "fan", ctx.store[id]?.state != "on") }
        addCentered(power, topDp = dockGap, widthDp = 52f, heightDp = 52f)
        var lastPct: Int? = initial.takeIf { it.state == "on" }?.let { (it.attributes["percentage"] as? Number)?.toInt() }?.takeIf { it > 0 }
        return { s ->
            val on = s?.state == "on"
            val pct = (s?.attributes?.get("percentage") as? Number)?.toInt()?.takeIf { it > 0 }
            if (!slider.adjusting) {
                stateLabel.text = when {
                    s == null -> HaText.ui("state.default.unknown")
                    s.isUnavailable -> s.formattedState()
                    on && pct != null -> "$pct%"
                    on -> "On"
                    else -> "Off"
                }
            }
            slider.active = on
            when {
                !on -> slider.setPercent(0)
                pct != null -> {
                    lastPct = pct
                    slider.setPercent(pct)
                }
                // Optimistic `on` before HA reports `percentage`: no flash to 100%.
                else -> lastPct?.let { slider.setPercent(it) }
            }
            timeLabel.text = relativeTime(s)
        }
    }

    private fun buildLock(id: String): (EntityState?) -> Unit {
        val stateLabel = bigState()
        val timeLabel = timeLabel()
        addCentered(stateLabel)
        addCentered(timeLabel, topDp = 4f)
        // One lock write at a time: a second tap while one is pending would make the rollbacks restore each other's
        // optimistic states. Kept per entity outside these controls ([PENDING_LOCK_WRITES]): switching to History and
        // back rebuilds them, and must not release the guard while the write is out.
        val pill = VerticalTogglePillView(ctx.context, theme.activeColor("lock"), theme.dark).apply {
            onToggle = toggle@{ locked ->
                val reset = { setOn(ctx.store[id]?.state.let { it == "locked" || it == "locking" }) }
                if (PENDING_LOCK_WRITES.containsKey(id)) {
                    reset()
                    return@toggle
                }
                val send = { code: String? ->
                    // This write's own token: a late answer settles it alone, never a newer write for the lock.
                    val write = Any()
                    PENDING_LOCK_WRITES[id] = write
                    val before = ctx.store[id]
                    val optimistic = before?.copy(state = if (locked) "locked" else "unlocked")
                    optimistic?.let { ctx.store.putLocal(it) }
                    val data = JSONObject().put("entity_id", id)
                    if (!code.isNullOrEmpty()) data.put("code", code)
                    val msg = JSONObject().put("type", "call_service").put("domain", "lock")
                        .put("service", if (locked) "lock" else "unlock").put("service_data", data)
                    // Settled once: by HA's answer or, without one, after LOCK_WRITE_TIMEOUT_MS.
                    fun settle(rollBack: Boolean) {
                        if (PENDING_LOCK_WRITES[id] !== write) return
                        PENDING_LOCK_WRITES.remove(id)
                        // Put back the state from before the optimistic flip, unless a newer state from HA has
                        // replaced the flip meanwhile (that one is the truth).
                        if (rollBack && before != null && ctx.store[id] === optimistic) ctx.store.putLocal(before)
                    }
                    ctx.callWs(msg) { ok, _, error ->
                        // A lost connection says nothing about the lock (HA may have run it): the flip stays until
                        // the reconnect's snapshot brings the real state. Only a refusal rolls back.
                        val code = error?.optString("code")
                        val unknown = code == HaDashboardClient.DISCONNECTED
                        ctx.main.post { settle(rollBack = !ok && !unknown) }
                    }
                    // No answer while connected: HA would have reported a change by now, so the lock is as it was.
                    ctx.main.postDelayed({ settle(rollBack = true) }, LOCK_WRITE_TIMEOUT_MS)
                    Unit
                }
                // A lock with a `code_format` asks for its code first, as HA's more-info does. Cancelled, the pill
                // (which already moved) goes back to the lock's state.
                if (ctx.store[id]?.attributes?.get("code_format") != null) {
                    val label = if (locked) HaText.ui("ui.card.lock.lock") else HaText.ui("ui.card.lock.unlock")
                    HaCodeDialog.ask(ctx, "text", label, label, onCancel = reset) { send(it) }
                } else {
                    send(null)
                }
            }
        }
        addPill(pill)
        return { s ->
            stateLabel.text = s?.formattedState() ?: HaText.ui("state.default.unknown")
            pill.setOn(s?.state == "locked" || s?.state == "locking")
            timeLabel.text = relativeTime(s)
        }
    }

    private fun buildCover(id: String, initial: EntityState?): (EntityState?) -> Unit {
        val stateLabel = bigState()
        val timeLabel = timeLabel()
        addCentered(stateLabel)
        addCentered(timeLabel, topDp = 4f)
        // HA's capabilities decide what the sheet offers: SET_POSITION the slider and presets, OPEN / CLOSE / STOP
        // their buttons. Without `supported_features` (older fixtures), the reported position and all three buttons.
        val features = (initial?.attributes?.get("supported_features") as? Number)?.toInt()
        val hasPosition = if (features != null) features and COVER_SET_POSITION != 0
        else initial?.attributes?.get("current_position") != null
        val actions = COVER_ACTIONS.filter { (_, service) -> features == null || features and (COVER_ACTION_BITS[service] ?: 0) != 0 }

        fun setPosition(pos: Int) {
            val next = ctx.store[id]?.let { cur ->
                cur.copy(state = if (pos > 0) "open" else "closed", attributes = cur.attributes + ("current_position" to pos))
            }
            ctx.callOptimistic(next, "cover", "set_cover_position", JSONObject().put("entity_id", id).put("position", pos))
        }

        val buttons = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        actions.forEachIndexed { i, (icon, service) ->
            val b = roundedButton(icon) { ctx.callService("cover", service, JSONObject().put("entity_id", id), null) }
            b.contentDescription = service.substringBefore('_').replaceFirstChar { it.titlecase(Locale.ROOT) }
            // Compact: three buttons and two gaps fit the slider's height (3 x 56 + 2 x 12 <= 196).
            val buttonH = if (!hasPosition) 64f else if (compact) 56f else 80f
            buttons.addView(b, LinearLayout.LayoutParams(ctx.px(if (hasPosition) 132f else 96f), ctx.px(buttonH)).apply {
                if (i > 0) topMargin = ctx.px(12f)
            })
        }

        if (!hasPosition) {
            // No position: HA shows the open / stop / close buttons alone, side by side.
            buttons.orientation = LinearLayout.HORIZONTAL
            for (i in 0 until buttons.childCount) {
                (buttons.getChildAt(i).layoutParams as LinearLayout.LayoutParams).apply {
                    topMargin = 0
                    if (i > 0) marginStart = ctx.px(12f)
                }
            }
            addCentered(buttons, topDp = 24f)
            return { s ->
                stateLabel.text = s?.formattedState() ?: HaText.ui("state.default.unknown")
                timeLabel.text = relativeTime(s)
            }
        }

        val center = FrameLayout(ctx.context)
        val slider = VerticalCoverSliderView(ctx.context).apply {
            // Named for TalkBack, as HA names the slider (the attribute it sets).
            contentDescription = HaText.ui("ui.card.cover.position")
            onChange = { pct -> stateLabel.text = VerticalCoverSliderView.stateText(pct) }
            onRelease = { pct -> setPosition(pct) }
        }
        center.addView(slider, LayoutParams(ctx.px(pillW), ctx.px(pillH), Gravity.CENTER))
        center.addView(buttons, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        body.addView(center, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(pillH)).apply { topMargin = ctx.px(controlGap) })

        // The mode switcher: the position slider, or the big buttons.
        val switcher = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = ctx.px(6f)
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                cornerRadius = 999f * ctx.dp
                setColor(if (theme.dark) DOCK_DARK else DOCK_LIGHT)
            }
        }
        val sliderIcon = MdiIconView(ctx.context)
        val buttonsIcon = MdiIconView(ctx.context)
        val sliderBtn = FrameLayout(ctx.context).apply {
            addView(sliderIcon, LayoutParams(ctx.px(22f), ctx.px(22f), Gravity.CENTER))
            isClickable = true
            ripple(this)
            contentDescription = HaText.ui("ui.card.cover.position")
        }
        val buttonsBtn = FrameLayout(ctx.context).apply {
            addView(buttonsIcon, LayoutParams(ctx.px(22f), ctx.px(22f), Gravity.CENTER))
            isClickable = true
            ripple(this)
            contentDescription = "Buttons"
        }
        var sliderShown: Boolean? = null
        fun showSlider(on: Boolean) {
            val changed = sliderShown != null && sliderShown != on
            sliderShown = on
            slider.visibility = if (on) VISIBLE else GONE
            buttons.visibility = if (on) GONE else VISIBLE
            if (changed) fadeIn(if (on) slider else buttons)
            listOf(Triple(sliderBtn, sliderIcon, on), Triple(buttonsBtn, buttonsIcon, !on)).forEach { (btn, icon, sel) ->
                btn.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (sel) selectedFill() else Color.TRANSPARENT)
                }
                icon.setIcon(if (btn === sliderBtn) "mdi:tune-vertical" else "mdi:swap-vertical",
                    if (sel) selectedIcon() else theme.primaryTextColor)
            }
        }
        sliderBtn.setOnClickListener { showSlider(true) }
        buttonsBtn.setOnClickListener { showSlider(false) }
        switcher.addView(sliderBtn, LinearLayout.LayoutParams(ctx.px(44f), ctx.px(44f)))
        switcher.addView(buttonsBtn, LinearLayout.LayoutParams(ctx.px(44f), ctx.px(44f)).apply { marginStart = ctx.px(4f) })
        showSlider(true)
        addCentered(switcher, topDp = dockGap)
        // Nothing to switch to: a cover that only sets a position.
        if (actions.isEmpty()) switcher.visibility = GONE

        val presets = LinearLayout(ctx.context).apply { orientation = LinearLayout.HORIZONTAL }
        COVER_PRESETS.forEachIndexed { i, p ->
            presets.addView(pillLabel("$p%", false) { setPosition(p) },
                LinearLayout.LayoutParams(0, ctx.px(44f), 1f).apply { if (i > 0) marginStart = ctx.px(8f) })
        }
        body.addView(presets, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(20f) })

        return { s ->
            val pos = (s?.attributes?.get("current_position") as? Number)?.toInt()
            if (!slider.adjusting) {
                stateLabel.text = when {
                    s == null -> HaText.ui("state.default.unknown")
                    s.isUnavailable -> s.formattedState()
                    pos != null && s.state in setOf("open", "closed") -> VerticalCoverSliderView.stateText(pos)
                    else -> s.formattedState()
                }
            }
            slider.setPercent(pos ?: 0)
            timeLabel.text = relativeTime(s)
        }
    }

    private fun selectedFill(): Int = if (theme.dark) Color.WHITE else SELECTED_DARK
    private fun selectedIcon(): Int = if (theme.dark) SELECTED_DARK else Color.WHITE

    private fun buildClimate(id: String): (EntityState?) -> Unit {
        val unit = ThermostatCardView.temperatureUnit(ctx)
        val caption = text(13f, theme.secondaryTextColor).apply {
            gravity = Gravity.CENTER
            this.text = "Current temperature"
        }
        val current = text(22f, theme.primaryTextColor, Inter.MEDIUM).apply { gravity = Gravity.CENTER }
        addCentered(caption, topDp = 4f)
        addCentered(current, topDp = 4f)

        val dial = ThermostatDialView(ctx, unit, showCurrentInDial = false).apply {
            onCommit = commit@{ t ->
                val s = ctx.store[id]
                // Only while the store still holds a single target: not after HA moved to a range (its state, not the
                // dial's last bind), and not for an entity the store no longer has.
                if (s == null || ClimateTargets.of(s.attributes).value == null) return@commit
                ctx.callOptimistic(ClimateTargets.withValue(s, t), "climate", "set_temperature", JSONObject().put("entity_id", id).put("temperature", t))
            }
            onRangeCommit = { handle, low, high ->
                // Finished against the entity as the store holds it now: HA's newer other end wins.
                val s = ctx.store[id]
                ClimateTargets.rangeToSend(handle, low, high, s)?.let { (lo, hi) ->
                    ctx.callOptimistic(ClimateTargets.withRange(s, lo, hi), "climate", "set_temperature", ClimateTargets.rangeData(id, lo, hi))
                }
            }
        }
        val dialFrame = FrameLayout(ctx.context)
        dialFrame.addView(dial, LayoutParams(MATCH_PARENT, ctx.px(dialDp)))
        val steppers = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun stepBtn(icon: String, sign: Int) = FrameLayout(ctx.context).apply {
            addView(MdiIconView(ctx.context).apply { setIcon(icon, theme.primaryTextColor) },
                LayoutParams(ctx.px(22f), ctx.px(22f), Gravity.CENTER))
            contentDescription = ThermostatCardView.stepperLabel(sign, null)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(ctx.px(1f).coerceAtLeast(1), CardSurface.withAlpha(theme.secondaryTextColor, 0.5f))
            }
            isClickable = true
            ripple(this)
            setOnClickListener { dial.nudge(sign) }
        }
        val minus = stepBtn("mdi:minus", -1)
        val plus = stepBtn("mdi:plus", 1)
        // A range: the − and + move the selected end, named after it and outlined in its color while it runs.
        fun styleSteppers() {
            val handle = dial.selected.takeIf { dial.isRange }
            val outline = handle?.let { dial.handleColor(it) }?.takeIf { it != theme.secondaryTextColor }
                ?: CardSurface.withAlpha(theme.secondaryTextColor, 0.5f)
            for ((view, sign) in listOf(minus to -1, plus to 1)) {
                view.contentDescription = ThermostatCardView.stepperLabel(sign, handle)
                (view.background as? GradientDrawable)?.setStroke(ctx.px(1f).coerceAtLeast(1), outline)
            }
        }
        dial.onSelect = { styleSteppers() }
        steppers.addView(minus, LinearLayout.LayoutParams(ctx.px(44f), ctx.px(44f)).apply { marginEnd = ctx.px(20f) })
        steppers.addView(plus, LinearLayout.LayoutParams(ctx.px(44f), ctx.px(44f)))
        dialFrame.addView(steppers, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        body.addView(dialFrame, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(dialDp)).apply { topMargin = ctx.px(dialGap) })

        val controls = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val modeBtn = infoPill("Mode")
        val presetBtn = infoPill("Preset")
        controls.addView(modeBtn.root, LinearLayout.LayoutParams(WRAP_CONTENT, ctx.px(56f)))
        controls.addView(presetBtn.root, LinearLayout.LayoutParams(WRAP_CONTENT, ctx.px(56f)).apply { marginStart = ctx.px(12f) })
        addCentered(controls, topDp = dockGap)

        modeBtn.root.setOnClickListener { v ->
            val modes = listAttr(ctx.store[id], "hvac_modes")
            showMenu(v, modes.map { humanize(it) }) { i ->
                val m = modes[i]
                ctx.callOptimistic(ctx.store[id]?.copy(state = m), "climate", "set_hvac_mode", JSONObject().put("entity_id", id).put("hvac_mode", m))
            }
        }
        presetBtn.root.setOnClickListener { v ->
            val presets = listAttr(ctx.store[id], "preset_modes")
            showMenu(v, presets.map { humanize(it) }) { i ->
                val p = presets[i]
                val next = ctx.store[id]?.let { it.copy(attributes = it.attributes + ("preset_mode" to p)) }
                ctx.callOptimistic(next, "climate", "set_preset_mode", JSONObject().put("entity_id", id).put("preset_mode", p))
            }
        }

        return { s ->
            val attrs = s?.attributes.orEmpty()
            val mode = s?.state ?: "off"
            val targets = ClimateTargets.of(attrs)
            dial.bind(
                min = (attrs["min_temp"] as? Number)?.toDouble() ?: 7.0,
                max = (attrs["max_temp"] as? Number)?.toDouble() ?: 35.0,
                step = ClimateTargets.stepOf(attrs, unit),
                current = (attrs["current_temperature"] as? Number)?.toDouble(),
                target = targets.value,
                mode = mode,
                action = attrs["hvac_action"] as? String,
                stateLabel = s?.let { dev.glassworks.engine.vitro.data.HaDisplay.stateLabel(it) }.orEmpty(),
                low = targets.low,
                high = targets.high,
                // As the dashboard's dial: one mode (heat, cool) fills from the start, several (auto) draw the segment
                // from the current temperature to the target.
                hvacModes = (attrs["hvac_modes"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty(),
            )
            styleSteppers()
            current.text = (attrs["current_temperature"] as? Number)?.toDouble()
                ?.let { HaDisplay.formatFixed(it, 1) + " " + unit } ?: "–"
            modeBtn.icon.setIcon(ThermostatCardView.modeIcon(mode), theme.primaryTextColor)
            modeBtn.value.text = if (s == null) "Unknown" else humanize(mode).replace("Heat cool", "Heat/Cool")
            val presets = listAttr(s, "preset_modes")
            presetBtn.root.visibility = if (presets.isEmpty()) GONE else VISIBLE
            presetBtn.icon.setIcon("mdi:tune", theme.primaryTextColor)
            presetBtn.value.text = (attrs["preset_mode"] as? String)?.let { humanize(it) } ?: HaText.ui("ui.common.none")
        }
    }

    private class InfoPill(val root: LinearLayout, val icon: MdiIconView, val value: TextView)

    /** HA 2026's rounded control button: an icon, then a small caption over the current value. */
    private fun infoPill(caption: String): InfoPill {
        val icon = MdiIconView(ctx.context)
        val value = text(14f, theme.primaryTextColor, Inter.MEDIUM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val root = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.px(16f), 0, ctx.px(20f), 0)
            background = GradientDrawable().apply {
                cornerRadius = 16f * ctx.dp
                setColor(if (theme.dark) DOCK_DARK else DOCK_LIGHT)
            }
            isClickable = true
            ripple(this, 16f * ctx.dp)
            contentDescription = caption
            addView(icon, LinearLayout.LayoutParams(ctx.px(22f), ctx.px(22f)).apply { marginEnd = ctx.px(12f) })
            addView(LinearLayout(ctx.context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(11f, theme.secondaryTextColor).apply { this.text = caption })
                addView(value, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.px(2f) })
            })
        }
        return InfoPill(root, icon, value)
    }

    private fun showMenu(anchor: View, labels: List<String>, onPick: (Int) -> Unit) {
        if (labels.isEmpty()) return
        val menu = PopupMenu(ctx.context, anchor)
        labels.forEachIndexed { i, l -> menu.menu.add(0, i, i, l) }
        menu.setOnMenuItemClickListener { item ->
            onPick(item.itemId)
            true
        }
        menu.show()
    }

    /**
     * HA's `more-info-alarm_control_panel`: the state header, then `ha-state-control-alarm_control_panel-modes`, the
     * alarm's modes in a vertical `ha-control-select` ([AlarmModesSelect]) with the current one filled in the state's
     * color. While the alarm is triggered, arming or pending, a pulsing 144 px status icon takes its place, with a
     * Disarm `ha-control-button` under it. A mode that needs a code ([AlarmModes.needsCode]) and has no default code
     * in the entity registry asks for it in HA's code dialog ([HaCodeDialog]); cancelled, the select goes back.
     */
    private fun buildAlarm(id: String, initial: EntityState?): (EntityState?) -> Unit {
        // Inside HA's `.content` (24 px all round, [CONTENT_DOMAINS]): `ha-more-info-state-header` heads it.
        val header = MoreInfoStateHeader(ctx)
        body.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val select = AlarmModesSelect(ctx) { mode, revert -> setAlarmMode(id, mode, revert) }
        val n = AlarmModes.supported(initial).size
        // `height: 45vh` between `max(200px, n * 80px)` and `max(320px, n * 80px)`, 130 px thick.
        val screenH = ctx.context.resources.configuration.screenHeightDp.toFloat()
        val selectH = (screenH * 0.45f).coerceIn(maxOf(200f, n * 80f), maxOf(320f, n * 80f))
        addCentered(select, widthDp = 130f, heightDp = selectH)
        // `.controls:not(:last-child)`: the Disarm container always follows it (empty unless triggered, arming or
        // pending), so the controls keep their 24 px under them.
        (select.layoutParams as LinearLayout.LayoutParams).bottomMargin = ctx.px(24f)

        // `.status .icon`: 144 px, the state's color at 20 % behind an 80 px icon, pulsing once a second.
        val statusIcon = MdiIconView(ctx.context)
        val status = FrameLayout(ctx.context).apply {
            addView(statusIcon, LayoutParams(ctx.px(80f), ctx.px(80f), Gravity.CENTER))
            visibility = GONE
        }
        addCentered(status, widthDp = 144f, heightDp = 144f)
        (status.layoutParams as LinearLayout.LayoutParams).bottomMargin = ctx.px(24f)
        val pulse = ValueAnimator.ofFloat(1f, 0f, 1f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { status.alpha = it.animatedValue as Float }
        }
        releasers += { pulse.cancel() }
        // `ha-control-button.disarm`: 60 px tall, 130 to 200 px wide, `--ha-border-radius-3xl`.
        val disarm = text(14f, theme.primaryTextColor, Inter.MEDIUM).apply {
            this.text = HaText.ui("ui.card.alarm_control_panel.disarm")
            gravity = Gravity.CENTER
            minWidth = ctx.px(130f)
            maxWidth = ctx.px(200f)
            setPadding(ctx.px(8f), 0, ctx.px(8f), 0)
            background = GradientDrawable().apply {
                cornerRadius = 24f * ctx.dp
                setColor(CardSurface.withAlpha(disabledColor(), 0.2f))
            }
            isClickable = true
            ripple(this, 24f * ctx.dp)
            dev.glassworks.engine.vitro.ui.VitroAccess.role(this, dev.glassworks.engine.vitro.ui.A11yRole.BUTTON)
            setOnClickListener { setAlarmMode(id, "disarmed") {} }
            visibility = GONE
        }
        addCentered(disarm, heightDp = 60f)

        return { s ->
            val st = s?.state.orEmpty()
            header.bind(s, MoreInfoStateHeader.stateOf(s, ctx.now()))
            val color = alarmColor(st)
            val changing = st in AlarmModes.CHANGING
            select.visibility = if (changing) GONE else VISIBLE
            status.visibility = if (changing) VISIBLE else GONE
            disarm.visibility = if (changing) VISIBLE else GONE
            if (changing) {
                status.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(CardSurface.withAlpha(color, 0.2f))
                }
                statusIcon.setIcon(MdiIcons.resolveIconName(null, s).takeIf { s != null } ?: alarmIcon(st), color)
                if (!pulse.isStarted) pulse.start()
            } else {
                pulse.cancel()
                status.alpha = 1f
                select.bind(s, color, disabledColor())
            }
        }
    }

    /** `--disabled-color`: the control buttons' and the select's background, at 20 %. */
    private fun disabledColor(): Int =
        ThemeResolver.parseColor(theme.vars["disabled-color"]?.let { ThemeResolver.resolveValue(it, theme.vars, 0) })
            ?: if (theme.dark) 0xFF464646.toInt() else 0xFFBDBDBD.toInt()

    /**
     * HA's `setProtectedAlarmControlPanelMode`: [mode]'s service, after the code when it needs one and the entity
     * registry holds no default code. [revert] runs when the dialog is cancelled (HA's select goes back to the mode it
     * showed).
     */
    private fun setAlarmMode(id: String, mode: String, revert: () -> Unit) {
        val s = ctx.store[id] ?: return revert()
        val service = AlarmModes.service(mode) ?: return revert()
        fun send(code: String?) {
            val data = JSONObject().put("entity_id", id)
            if (code != null) data.put("code", code)
            ctx.callService("alarm_control_panel", service, data, null)
        }
        if (!AlarmModes.needsCode(s, mode)) return send(null)
        ctx.callWs(JSONObject().put("type", "config/entity_registry/get").put("entity_id", id)) { ok, result, _ ->
            val defaultCode = (result as? JSONObject)?.takeIf { ok }?.optJSONObject("options")?.optJSONObject("alarm_control_panel")
                ?.opt("default_code")?.takeIf { it != JSONObject.NULL && it.toString().isNotEmpty() }
            ctx.main.post {
                if (dismissed) return@post revert()
                if (defaultCode != null) return@post send(null)
                val title = if (mode == "disarmed") HaText.ui("ui.card.alarm_control_panel.disarm") else HaText.ui("ui.card.alarm_control_panel.arm")
                HaCodeDialog.ask(ctx, s.attributes["code_format"]?.toString(), title, title, onCancel = revert) { send(it) }
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
        val play = circleButton(playIcon, "mdi:play", HaText.ui("ui.card.media_player.media_play"), 64f, 30f) { media("media_play_pause") }
        transport.addView(circleButton("mdi:skip-previous", HaText.ui("ui.card.media_player.media_previous_track"), 52f, 24f) { media("media_previous_track") },
            LinearLayout.LayoutParams(ctx.px(52f), ctx.px(52f)))
        transport.addView(play, LinearLayout.LayoutParams(ctx.px(64f), ctx.px(64f)).apply {
            marginStart = ctx.px(20f); marginEnd = ctx.px(20f)
        })
        transport.addView(circleButton("mdi:skip-next", HaText.ui("ui.card.media_player.media_next_track"), 52f, 24f) { media("media_next_track") },
            LinearLayout.LayoutParams(ctx.px(52f), ctx.px(52f)))
        addCentered(transport, topDp = 20f)

        val volumeRow = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        var tracking = false
        // The volume HA reported last, or the one sent since: what a dropped key edit shows again.
        var bound = -1
        fun setVolume(percent: Int) {
            bound = percent
            ctx.callService("media_player", "volume_set", JSONObject().put("entity_id", id).put("volume_level", percent / 100.0), null)
        }
        // HA's slider sends a key edit when its key comes up (`keyup`), as a drag on release: a held arrow is one call.
        var keyEdit = 0
        var keyFrom = 0
        val volume = object : SeekBar(ctx.context) {
            override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
                if (keyEdit == 0) keyFrom = progress
                val wasEdit = keyEdit
                keyEdit = keyCode
                val moved = super.onKeyDown(keyCode, event)
                // A key the bar didn't take (focus moves on): no edit, unless it's the held one's repeat.
                if (!moved && wasEdit != keyCode) keyEdit = wasEdit
                return moved
            }

            override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
                if (keyEdit == 0 || keyCode != keyEdit) return super.onKeyUp(keyCode, event)
                keyEdit = 0
                if (event.isCanceled) progress = if (bound >= 0) bound else keyFrom else if (progress != keyFrom) setVolume(progress)
                return true
            }

            override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
                super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
                // Focus left mid-edit: nothing is sent, HA's volume shows again.
                if (!gainFocus && keyEdit != 0) {
                    keyEdit = 0
                    progress = if (bound >= 0) bound else keyFrom
                }
            }
        }.apply {
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    // TalkBack's steps go to HA at once. A held key sends when it comes up, a drag on release.
                    if (fromUser && !tracking && keyEdit == 0) setVolume(progress)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { tracking = true }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    tracking = false
                    setVolume(sb?.progress ?: return)
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
            play.contentDescription = HaText.ui(if (playing) "ui.card.media_player.media_pause" else "ui.card.media_player.media_play")
            val level = (s?.attributes?.get("volume_level") as? Number)?.toDouble()
            volumeRow.visibility = if (level == null) GONE else VISIBLE
            if (level != null) bound = (level * 100).roundToInt()
            if (level != null && !tracking && keyEdit == 0) volume.progress = bound
        }
    }

    private fun buildAction(id: String, domain: String): (EntityState?) -> Unit {
        val timeLabel = timeLabel()
        val (caption, service) = if (domain == "script") HaText.ui("ui.card.script.run") to "turn_on"
        else HaText.ui("ui.card.scene.activate") to "turn_on"
        val button = text(16f, Color.WHITE, Inter.MEDIUM).apply {
            this.text = caption
            gravity = Gravity.CENTER
            setPadding(ctx.px(36f), 0, ctx.px(36f), 0)
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(theme.primaryColor)
            }
            isClickable = true
            ripple(this)
            // A script with fields never runs from here without them: HA's own dialog, with their form, takes More
            // Info for such a script (VitroView.openMoreInfo), and this sheet doesn't start it.
            setOnClickListener {
                if (domain == "script") EntityRowControls.runScript(ctx, id, onFields = {})
                else ctx.callService(domain, service, JSONObject().put("entity_id", id), null)
            }
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
        // The live players' views, over the still, in its rounded shape. The feed starts once the sheet is in a window
        // that shows, pauses while the window is hidden (the screen off, the app in the background) and goes on when it
        // shows again, as on the picture cards.
        var feedRef: LiveCameraFeed? = null
        // Posted, so the first state bind lands first; until then the feed waits.
        var bound = false
        var open = true
        var started = false
        var windowVisible = false
        fun follow() {
            val f = feedRef ?: return
            if (!open || !bound) return
            runCatching {
                if (windowVisible) {
                    if (!started) {
                        started = true
                        f.start()
                    } else {
                        f.setActive(true)
                    }
                } else if (started) {
                    f.setActive(false)
                }
            }
        }
        val streamHost = object : FrameLayout(ctx.context) {
            override fun onAttachedToWindow() {
                super.onAttachedToWindow()
                // Android calls back on attach only when the window isn't GONE: read it here.
                windowVisible = windowVisibility == VISIBLE
                follow()
            }

            override fun onWindowVisibilityChanged(visibility: Int) {
                super.onWindowVisibilityChanged(visibility)
                windowVisible = visibility == VISIBLE
                follow()
            }
        }.apply {
            background = GradientDrawable().apply {
                cornerRadius = 16f * ctx.dp
                setColor(Color.TRANSPARENT)
            }
            clipToOutline = true
        }
        val frame = FrameLayout(ctx.context).apply {
            addView(image, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(streamHost, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(liveBadge(), LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = ctx.px(10f); marginEnd = ctx.px(10f)
            })
        }
        body.addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(8f) })
        val status = text(14f, theme.secondaryTextColor, Inter.MEDIUM)
        body.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.px(12f) })

        var picture: String? = ctx.store[id]?.entityPicture
        // HA's ha-camera-stream (WebRTC, HLS, then MJPEG), snapshots every 1.5 s if none plays.
        // It comes first for a video slot: with every slot held by the cards behind, it takes one of theirs.
        val feed = LiveCameraFeed(ctx, id, { picture }, host = streamHost, cardType = "more-info", foreground = true, still = image) { bmp ->
            image.setImageBitmap(bmp)
        }
        feedRef = feed
        releasers += {
            open = false
            feed.stop()
        }
        ctx.main.post {
            bound = true
            follow()
        }
        return { s ->
            picture = s?.entityPicture
            val label = when (s?.state) {
                null -> HaText.ui("state.default.unknown")
                "idle" -> "Idle"
                "streaming" -> "Streaming"
                "recording" -> "Recording"
                else -> s.formattedState()
            }
            status.text = listOf(label, relativeTime(s)).filter { it.isNotEmpty() }.joinToString(" · ")
        }
    }

    /** The green `LIVE` pill over the camera picture. */
    private fun liveBadge(): LinearLayout = LinearLayout(ctx.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.px(8f), ctx.px(4f), ctx.px(10f), ctx.px(4f))
        background = GradientDrawable().apply {
            cornerRadius = 999f
            setColor(0xE62E7D32.toInt())
        }
        addView(View(ctx.context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
        }, LinearLayout.LayoutParams(ctx.px(6f), ctx.px(6f)).apply { marginEnd = ctx.px(6f) })
        addView(text(11f, Color.WHITE, Inter.MEDIUM).apply {
            this.text = "LIVE"
            letterSpacing = 0.08f
        })
    }

    /**
     * HA's More Info for a sensor (and any domain without controls of its own): `state-card-content`'s row (the state
     * badge, the entity's name over how long ago it changed, the state on the right), then `ha-more-info-history`
     * ([MoreInfoHistoryView]). Without [stateRow], the History tab of a domain with controls.
     */
    private fun buildSensor(id: String, stateRow: Boolean): (EntityState?) -> Unit {
        val side = ctx.px(24f)
        var update: (EntityState?) -> Unit = {}
        // computeShowLogBookComponent: the Activity section after the history (`ha-more-info-logbook`).
        val logbook = HaLogbook.shows(id, ctx.store[id], ctx.haConfig())
        if (stateRow) {
            // state-info: a 40 px badge with its 24 px icon, the name (16.8 px line) over the time (22.4 px line).
            val row = LinearLayout(ctx.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val badge = FrameLayout(ctx.context)
            val icon = MdiIconView(ctx.context)
            badge.addView(icon, LayoutParams(ctx.px(24f), ctx.px(24f), Gravity.CENTER))
            row.addView(badge, LinearLayout.LayoutParams(ctx.px(40f), ctx.px(40f)))
            val labels = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
            val name = cssText(ctx, 14f, theme.primaryTextColor).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
            }
            val time = cssText(ctx, 14f, theme.secondaryTextColor).apply {
                maxLines = 1
                // `.time-ago { text-overflow: ellipsis }`, as the name.
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
            }
            labels.addView(name, LinearLayout.LayoutParams(MATCH_PARENT, Math.round(16.8f * ctx.dp)))
            labels.addView(time, LinearLayout.LayoutParams(MATCH_PARENT, Math.round(22.4f * ctx.dp)))
            val number = id.substringBefore('.') in NUMBER_DOMAINS
            val value = cssText(ctx, 14f, theme.primaryTextColor).apply {
                maxLines = 1
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
            }
            var control: EntityRowControls.Control? = null
            var stateCard: MoreInfoStateCard? = null
            if (number) {
                // state-card-number / state-card-input_number: state-info, then the slider and its state (or the box)
                // in the `.flex` that takes the rest of the row.
                row.removeView(badge)
                val card = StateCardRow(ctx.context, badge, labels, ctx.dp)
                val c = EntityRowControls.stateCardNumber(ctx, id, { HaDisplay.nameParts(id, ctx.store[id]?.friendlyName ?: id).let { it.entity ?: it.device ?: id } },
                    rowWidthCss = { card.rowWidthPx / ctx.dp }, flexWidthCss = { card.flexWidthPx / ctx.dp })
                control = c
                card.setControl(c.view)
                body.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    marginStart = side
                    marginEnd = side
                    if (historyLoaded() || logbook) bottomMargin = ctx.px(16f)
                })
            } else {
                // Every other domain's row ([MoreInfoStateCard]): the state on the right, or a select's menu, a button's
                // Press, an alert's toggle, an event's time and type.
                val card = MoreInfoStateCard(ctx, id)
                stateCard = card
                body.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    marginStart = side
                    marginEnd = side
                    if (historyLoaded() || logbook) bottomMargin = ctx.px(16f)
                })
            }
            update = update@{ s ->
                stateCard?.let { it.bind(s); return@update }
                control?.update?.invoke(s)
                // state-info's state-badge (`stateColor`): an active entity in its state's color (a tracker at home
                // green), anything else in the icon color.
                val tint = s?.takeIf { it.isActive }?.let { HaStateColor.of(ctx, it) } ?: theme.stateIconColor
                icon.setIcon(MdiIcons.resolveIconName(null, s), tint)
                // formatEntityName(stateObj, { type: "entity" }): the entity's own name, else its device's.
                val parts = HaDisplay.nameParts(id, s?.friendlyName ?: id)
                name.text = parts.entity ?: parts.device ?: s?.friendlyName ?: id
                // No age without a last change (0: HA never sent one), as ha-relative-time shows nothing then.
                time.text = s?.takeIf { it.lastChanged > 0.0 }?.let { HaFormat.relativeTime((it.lastChanged * 1000).toLong(), ctx.now()) }.orEmpty()
                value.text = s?.let { MoreInfoStateHeader.stateCardDisplay(it, ctx.now()) } ?: HaText.ui("state.default.unknown")
            }
        }
        // computeShowHistoryComponent: no history section when HA runs without its `history` integration.
        val withHistory = historyLoaded()
        if (withHistory) {
            val history = MoreInfoHistoryView(ctx, id, onShowMore = { href ->
                // HA's link: the dialog closes as the page moves to /history (HA's frontend, with the way back of #863).
                dismiss()
                ctx.navigate(href)
            }, onGraph = { trace?.graph(it) })
            historyView = history
            releasers += {
                history.stop()
                if (historyView === history) historyView = null
            }
            body.addView(history, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (stateRow) bottomMargin = ctx.px(16f) })
        }
        if (!logbook) return update
        val view = if (stateRow) "info" else "history"
        val cfg = ctx.context.resources.configuration
        val activity = MoreInfoLogbookView(ctx, id, MoreInfoLogbookView.capped(cfg.screenWidthDp, cfg.screenHeightDp),
            onEntry = {
                // HA's row opens its logbook detail dialog (the entry, its cause, the run's trace), which only HA's
                // frontend has: its More Info dialog takes over, on the view the entry is in.
                inFrontend?.invoke(id, view, "More Info: an Activity entry's detail")
            },
            onShowMore = { href ->
                // HA's link, as the history's: the dialog closes as the page moves to /logbook.
                dismiss()
                ctx.navigate(href)
            },
            onUnsupported = { reason -> inFrontend?.invoke(id, view, "More Info: $reason") },
        )
        logbookView = activity
        releasers += {
            activity.stop()
            if (logbookView === activity) logbookView = null
        }
        body.addView(activity, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            // `ha-more-info-history + ha-more-info-logbook` on the History tab; under the default view's history it's
            // not the last child (more-info-content follows), so 16 px under it.
            if (!stateRow && withHistory) topMargin = ctx.px(16f)
            if (stateRow) bottomMargin = ctx.px(16f)
        })
        return { st ->
            update(st)
            activity.tick()
        }
    }

    /** The Activity section showing, if any. */
    private var logbookView: MoreInfoLogbookView? = null

    /**
     * Opens HA's own More Info dialog for an entity on one of its views (`more-info-view`: `info`, `history`), in
     * HA's frontend, for what only it has: an Activity entry's detail. Null (no page, a test): nothing opens.
     */
    var inFrontend: ((entityId: String, view: String, reason: String) -> Unit)? = null

    // ---- Shared pieces ----

    /** The pane title TalkBack names the sheet by, as last set. */
    private var paneTitle: String? = null

    /** The history section showing, for [reconnected]. */
    private var historyView: MoreInfoHistoryView? = null

    /** The connection to HA came back: a state history's subscription starts again (MoreInfoHistoryView). */
    fun reconnected() {
        historyView?.reconnected()
    }

    /**
     * HA's `isComponentLoaded(hass.config, "history")`, from HA's config's `components`. A config that doesn't list
     * them (a test's) counts as one with history.
     */
    private fun historyLoaded(): Boolean {
        val components = ctx.haConfig().optJSONArray("components") ?: return true
        return (0 until components.length()).any { components.optString(it) == "history" }
    }

    /** "5 minutes ago" at the card context's clock. */
    private fun relativeTime(s: EntityState?): String = relativeTime(s?.lastChanged ?: 0.0, ctx.now())

    private fun optimisticToggle(id: String, domain: String, turnOn: Boolean) {
        val cur = ctx.store[id]
        val next = if (cur != null && cur.state in setOf("on", "off")) cur.copy(state = if (turnOn) "on" else "off") else null
        ctx.callOptimistic(next, domain, if (turnOn) "turn_on" else "turn_off", JSONObject().put("entity_id", id))
    }

    private fun text(sp: Float, color: Int, weight: Int = Inter.REGULAR): TextView = TextView(ctx.context).apply {
        textSize = sp
        setTextColor(color)
        typeface = VitroFonts.typeface(ctx.context, weight, sp)
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

    private fun addPill(v: View) = addCentered(v, topDp = controlGap, widthDp = pillW, heightDp = pillH)

    private fun iconButton(icon: MdiIconView, name: String): FrameLayout = FrameLayout(ctx.context).apply {
        icon.setIcon(name, theme.primaryTextColor)
        // ha-icon-button: a 24 px icon in its 48 px circle.
        addView(icon, LayoutParams(ctx.px(24f), ctx.px(24f), Gravity.CENTER))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
        }
        isClickable = true
        ripple(this)
    }

    /** HA's ripple on a sheet control, clipped to [radiusPx] (a pill or circle by default). */
    private fun ripple(v: View, radiusPx: Float = 999f) = applyHaRipple(v, radiusPx, theme.dark)

    private fun circleButton(name: String, label: String, sizeDp: Float, iconDp: Float, onClick: () -> Unit): FrameLayout =
        circleButton(MdiIconView(ctx.context), name, label, sizeDp, iconDp, onClick)

    /** A round icon button, named [label] for TalkBack (HA's `.label`). */
    private fun circleButton(icon: MdiIconView, name: String, label: String, sizeDp: Float, iconDp: Float, onClick: () -> Unit): FrameLayout =
        FrameLayout(ctx.context).apply {
            contentDescription = label
            icon.setIcon(name, theme.primaryTextColor)
            addView(icon, LayoutParams(ctx.px(iconDp), ctx.px(iconDp), Gravity.CENTER))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(BUTTON_SURFACE)
            }
            minimumWidth = ctx.px(sizeDp)
            minimumHeight = ctx.px(sizeDp)
            isClickable = true
            ripple(this)
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
        ripple(this, 16f * ctx.dp)
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
            ripple(this)
            setOnClickListener { onClick() }
        }

    private class Favorite(val name: String, val color: Int, val kelvin: Int? = null)

    private enum class LightMode { BRIGHTNESS, COLOR, TEMP }

    /** A More-Info dock button: brightness (a filled circle when chosen), or a mini color wheel / temperature swatch. */
    private class LightModeButton(context: Context, private val kind: LightMode, private val theme: ResolvedTheme) : View(context) {
        private val dp = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * dp }
        private val iconPath = Path()
        private val matrix = android.graphics.Matrix()
        private var shaderSize = 0
        private var shaderHeight = 0
        private var shaderKind: LightMode? = null
        private val brightnessGlyph: Path by lazy { MdiIcons.pathFor("mdi:brightness-6") }

        var chosen = false
            set(v) { if (field != v) { field = v; invalidate() } }

        /** HA's mode buttons stay pressed (`aria-pressed`) on the mode that shows. */
        override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = "android.widget.ToggleButton"
            info.isCheckable = true
            info.isChecked = chosen
        }

        init {
            isClickable = true
            contentDescription = when (kind) {
                LightMode.BRIGHTNESS -> HaText.ui("ui.card.light.brightness")
                LightMode.COLOR -> HaText.ui("ui.dialogs.more_info_control.light.color")
                LightMode.TEMP -> HaText.ui("ui.card.light.color_temperature")
            }
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) / 2f
            if (kind == LightMode.BRIGHTNESS) {
                paint.shader = null
                val iconColor = if (chosen) {
                    paint.color = if (theme.dark) Color.WHITE else SELECTED_DARK
                    canvas.drawCircle(cx, cy, r, paint)
                    if (theme.dark) SELECTED_DARK else Color.WHITE
                } else {
                    theme.primaryTextColor
                }
                val size = 22f * dp
                iconPath.set(brightnessGlyph)
                matrix.setScale(size / 24f, size / 24f)
                matrix.postTranslate(cx - size / 2f, cy - size / 2f)
                iconPath.transform(matrix)
                paint.color = iconColor
                canvas.drawPath(iconPath, paint)
                return
            }
            val inner = r - 6f * dp
            // The shader follows the size (and the mode, which fixes its kind): built once per change, not per draw.
            if (shaderSize != width || shaderHeight != height || shaderKind != kind) {
                shaderSize = width
                shaderHeight = height
                shaderKind = kind
                paint.shader = if (kind == LightMode.COLOR) {
                    android.graphics.SweepGradient(cx, cy, RgbColorWheelView.WHEEL_COLORS, null)
                } else {
                    android.graphics.LinearGradient(0f, cy - inner, 0f, cy + inner, ColorTempSliderView.GRADIENT, null,
                        android.graphics.Shader.TileMode.CLAMP)
                }
            }
            paint.color = Color.WHITE
            canvas.drawCircle(cx, cy, inner, paint)
            if (chosen) {
                ring.color = theme.primaryTextColor
                canvas.drawCircle(cx, cy, r - ring.strokeWidth / 2f, ring)
            }
        }
    }

    private companion object {
        /** Locks with a write out, by entity id (main thread only): see buildLock. */
        val PENDING_LOCK_WRITES = HashMap<String, Any>()

        /** How long a lock write may go unanswered before its guard is released and its flip given up. */
        const val LOCK_WRITE_TIMEOUT_MS = 30_000L

        /** `ha-adaptive-dialog`'s bottom sheet: a window at most this wide or this tall (CSS px). */
        const val SHEET_MAX_WIDTH_DP = 870
        const val SHEET_MAX_HEIGHT_DP = 500

        /** The dialog's width (`--ha-dialog-width-md`) and the height it leaves the window (`--ha-space-20`). */
        const val DIALOG_WIDTH = 580f
        const val DIALOG_MARGIN = 80f
        const val DIALOG_RADIUS = 24f
        const val SHEET_RADIUS = 20f

        /** The bottom sheet's top gap: `100vh - max(var(--safe-area-inset-top), 48px)`. */
        const val SHEET_TOP = 48f

        /** ha-dialog-header's height. */
        const val HEADER_HEIGHT = 68f

        /** The domains with a control view of their own (buildBody); the rest show HA's state row and history. */
        val CONTROL_DOMAINS = setOf(
            "light", "switch", "input_boolean", "automation", "siren", "humidifier", "fan", "camera", "climate", "cover",
            "lock", "media_player", "alarm_control_panel", "script", "scene",
        )

        /** The domains whose state row is a slider or a box (`state-card-number`, `state-card-input_number`). */
        val NUMBER_DOMAINS = setOf("number", "input_number")

        /** The domains drawn from HA's own parts ([MoreInfoVacuumView], the alarm's modes), inside its 24 px `.content`. */
        val CONTENT_DOMAINS = setOf("vacuum", "alarm_control_panel")

        /** Below this viewport height (dp) the sheet uses its compact landscape sizes. */
        const val COMPACT_HEIGHT_DP = 580
        const val SCRIM = 0x40000000
        const val BUTTON_SURFACE = 0x14888888
        const val ENTER_SHEET_MS = 240L
        const val ENTER_WIDE_MS = 200L
        const val EXIT_MS = 180L
        const val CROSS_FADE_MS = 150L
        const val ENTER_SCALE = 0.94f

        /** The bottom sheet's start offset before its real height is known, and its least exit travel. */
        const val SHEET_FALLBACK_DP = 220f
        const val EXIT_MIN_DP = 180f
        const val TIME_REFRESH_MS = 30_000L

        /**
         * Domains whose More-Info has a control view and a History tab: HA's `_shouldShowHistory`, the sheet's domains in
         * `DOMAINS_WITH_MORE_INFO` (a camera has no history).
         */
        val HISTORY_TOGGLE_DOMAINS = setOf(
            "light", "switch", "fan", "input_boolean", "climate", "cover", "lock", "siren", "alarm_control_panel",
            "media_player", "script", "scene", "vacuum", "update",
        )

        const val DOCK_LIGHT = 0xFFF2F2F2.toInt()
        const val DOCK_DARK = 0xFF2A2A2A.toInt()
        const val SELECTED_DARK = 0xFF212121.toInt()

        /** HA 2026's favorite colors: warm whites, then colors. */
        val FAVORITES_WARM = listOf(
            Favorite("Deep orange", LightColor.pack(255, 140, 0), 2000),
            Favorite("Warm amber", LightColor.pack(255, 190, 133), 2700),
            Favorite("Soft peach", LightColor.pack(252, 224, 197), 3500),
            Favorite("Warm white", LightColor.pack(255, 250, 240), 4500),
        )
        val FAVORITES_COLOR = listOf(
            Favorite("Sky blue", LightColor.pack(126, 176, 255)),
            Favorite("Lavender", LightColor.pack(204, 142, 255)),
            Favorite("Pink", LightColor.pack(255, 153, 230)),
            Favorite("Coral", LightColor.pack(255, 110, 84)),
        )
        val COVER_ACTIONS = listOf("mdi:arrow-up" to "open_cover", "mdi:stop" to "stop_cover", "mdi:arrow-down" to "close_cover")

        /** HA's `CoverEntityFeature` bits for the buttons, and for setting a position. */
        val COVER_ACTION_BITS = mapOf("open_cover" to 1, "close_cover" to 2, "stop_cover" to 8)
        const val COVER_SET_POSITION = 4
        val COVER_PRESETS = listOf(0, 25, 75, 100)

        fun listAttr(s: EntityState?, key: String): List<String> = when (val v = s?.attributes?.get(key)) {
            is List<*> -> v.mapNotNull { it?.toString() }
            is JSONArray -> (0 until v.length()).map { v.optString(it) }
            else -> emptyList()
        }

        fun alarmIcon(state: String): String = when (state) {
            "disarmed" -> "mdi:shield-off"
            "armed_home" -> "mdi:shield-home"
            "triggered" -> "mdi:bell"
            else -> if (state.startsWith("armed") || state == "arming" || state == "pending") "mdi:shield-lock" else "mdi:shield-home"
        }

        fun alarmColor(state: String): Int = when {
            state == "triggered" -> 0xFFF44336.toInt()
            state == "arming" || state == "pending" -> 0xFFFF9800.toInt()
            state.startsWith("armed") -> 0xFF4CAF50.toInt()
            else -> 0xFF9E9E9E.toInt()
        }

        fun humanize(raw: String): String = raw.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }


        /** `<ha-relative-time capitalize>` of the last change ("Now", "5 minutes ago"); empty without a timestamp. */
        fun relativeTime(lastChangedSec: Double, nowMs: Long): String {
            if (lastChangedSec <= 0.0) return ""
            return HaFormat.capitalize(HaFormat.relativeTime((lastChangedSec * 1000).toLong(), nowMs))
        }
    }
}

/**
 * A number's `state-card-*` row: `state-info` (the 40 px badge, 8 px, the name over the time), at least 120 px and as
 * wide as its text, then the control in the `.flex` that takes the rest ([flexWidthPx]), all centred on the row's
 * height (at least 40 px). As the browser lays out that flex row when the two don't fit: the `.flex` doesn't shrink
 * below its content (the slider's 100 px minimum and the state, or the input box), so `state-info` gives way, down to
 * its 120 px `min-width`, and its name ellipsizes. Narrower still, the control overflows the row's end.
 */
@SuppressLint("ViewConstructor")
internal class StateCardRow(context: Context, private val badge: View, private val labels: View, private val dp: Float) : android.view.ViewGroup(context) {
    private var control: View? = null

    /** The row's width and the `.flex`'s, as last measured (px): what the slider shares. */
    var rowWidthPx = 0
        private set
    var flexWidthPx = 0
        private set

    /** `state-info`'s width, as last measured (px): the `.flex` starts after it. */
    private var infoPx = 0

    init {
        addView(badge)
        addView(labels)
    }

    fun setControl(v: View) {
        control?.let(::removeView)
        control = v
        addView(v)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val badgePx = Math.round(40f * dp)
        badge.measure(MeasureSpec.makeMeasureSpec(badgePx, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(badgePx, MeasureSpec.EXACTLY))
        labels.measure(unspecified, unspecified)
        val minInfo = Math.round(120f * dp)
        val natural = maxOf(minInfo, badgePx + Math.round(8f * dp) + labels.measuredWidth)
        rowWidthPx = w
        val c = control
        val lp = c?.layoutParams
        val hs = if (lp != null && lp.height > 0) MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY) else unspecified
        // The `.flex`'s min-content: the control measured with no room to grow (the slider row keeps its basis).
        flexWidthPx = 0
        val controlMin = c?.let { it.measure(unspecified, hs); it.measuredWidth } ?: 0
        val info = minOf(natural, maxOf(minInfo, w - controlMin)).coerceAtMost(w)
        infoPx = info
        flexWidthPx = maxOf(w - info, controlMin)
        // The labels get what state-info has after the badge (they ellipsize).
        labels.measure(MeasureSpec.makeMeasureSpec((info - badgePx - Math.round(8f * dp)).coerceAtLeast(0), MeasureSpec.EXACTLY), unspecified)
        var h = badgePx
        c?.let {
            it.measure(MeasureSpec.makeMeasureSpec(flexWidthPx, MeasureSpec.AT_MOST), hs)
            h = maxOf(h, it.measuredHeight)
        }
        h = maxOf(h, labels.measuredHeight)
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val h = b - t
        val w = r - l
        fun center(v: View, x: Int) {
            val top = (h - v.measuredHeight) / 2
            v.layout(x, top, x + v.measuredWidth, top + v.measuredHeight)
        }
        center(badge, 0)
        center(labels, badge.measuredWidth + Math.round(8f * dp))
        // `.flex { justify-content: flex-end }`: the control at the `.flex`'s end, which is the row's unless it overflows.
        control?.let { c -> center(c, infoPx + flexWidthPx - c.measuredWidth) }
    }
}

/**
 * HA's dialog surface: `--ha-dialog-surface-background`, else `--card-background-color` (never `--ha-card-background`).
 */
internal fun dialogSurface(theme: ResolvedTheme): Int =
    ThemeResolver.parseColor(theme.vars["ha-dialog-surface-background"])
        ?: ThemeResolver.parseColor(theme.vars["card-background-color"])
        ?: theme.cardBackgroundColor

/**
 * HA's dialog surface: in dialog mode ([wide]) as tall as its content, at most the window's height less 80 px; as a
 * bottom sheet as tall as the window less 48 px (`--ha-bottom-sheet-height`), whatever its content. [handle] is the
 * sheet's handle, drawn over the header at the top centre.
 */
internal class DialogSurface(context: Context, private val wide: Boolean, private val dp: Float) : LinearLayout(context) {
    var handle: android.graphics.drawable.Drawable? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val offered = MeasureSpec.getSize(heightMeasureSpec)
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED || offered <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        // Dialog: the window less 80 px; its 40 px top margin is already out of what the parent offers.
        val spec = if (wide) MeasureSpec.makeMeasureSpec((offered - Math.round(40f * dp)).coerceAtLeast(0), MeasureSpec.AT_MOST)
        else MeasureSpec.makeMeasureSpec((offered - Math.round(48f * dp)).coerceAtLeast(0), MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasureSpec, spec)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        handle?.let { h ->
            val w = Math.round(40f * dp)
            val left = (width - w) / 2
            val top = Math.round(6f * dp)
            h.setBounds(left, top, left + w, top + Math.round(4f * dp))
            h.draw(canvas)
        }
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
internal class VerticalSliderPillView(context: Context, private var activeColor: Int) : View(context) {
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
    private var targetPct = 0f
    private var targetColor = activeColor
    private var pctAnimator: ValueAnimator? = null
    private var colorAnimator: ValueAnimator? = null

    var active = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var dragging = false
        private set

    /** A drag or a key edit is under way: what the slider and its caption show is the user's, not HA's. */
    val adjusting: Boolean get() = dragging || access.editing
    var onChange: ((Int) -> Unit)? = null
    var onRelease: ((Int) -> Unit)? = null

    /** TalkBack and keys: a 0–100 % slider, as HA's `ha-control-slider` is. */
    private val access = dev.glassworks.engine.vitro.ui.AdjustableAccess(
        min = { 0 }, max = { 100 }, step = { 1 },
        value = { Math.round(targetPct) },
        preview = { v ->
            pctAnimator?.cancel()
            pct = v.toFloat()
            targetPct = v.toFloat()
            invalidate()
            onChange?.invoke(v)
            dev.glassworks.engine.vitro.ui.VitroAccess.valueChanged(this)
        },
        commit = { v -> onRelease?.invoke(v) },
        // Back to HA's value, caption included (onChange), as a cancelled drag does: nothing is sent.
        restore = { bound?.let { targetPct = -1f; setPercent(it); onChange?.invoke(it) } },
    )

    init {
        isFocusable = true
    }

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        access.onInitialize(info)
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean =
        access.perform(action, arguments) ?: super.performAccessibilityAction(action, arguments)

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
        access.keyDown(keyCode) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean =
        access.keyUp(event) || super.onKeyUp(keyCode, event)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) access.cancel()
    }

    /** Re-tints the fill, e.g. when a light's RGB color or color temperature changes; cross-fades over 180 ms. */
    fun setActiveColor(color: Int) {
        if (color == targetColor) return
        targetColor = color
        colorAnimator?.cancel()
        val from = activeColor
        colorAnimator = HaMotion.run(this, MOTION_MS) { t ->
            activeColor = HaMotion.lerpColor(from, color, t)
            invalidate()
        }
    }

    /** Moves the fill to [value] %: animated over 180 ms on screen, snapped before attach. Ignored mid-drag. */
    /** The value bound last, kept during a drag so a cancelled one goes back to it. */
    private var bound: Int? = null

    fun setPercent(value: Int) {
        bound = value
        // A drag or a key edit keeps what it shows until it ends.
        if (dragging || access.editing) return
        val p = value.coerceIn(0, 100).toFloat()
        if (p == targetPct) return
        targetPct = p
        // A new value from HA: TalkBack reads it, as it would a SeekBar's.
        dev.glassworks.engine.vitro.ui.VitroAccess.valueChanged(this)
        pctAnimator?.cancel()
        val from = pct
        pctAnimator = HaMotion.run(this, MOTION_MS) { t ->
            pct = HaMotion.lerp(from, p, t)
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        pctAnimator?.cancel()
        pctAnimator = null
        colorAnimator?.cancel()
        colorAnimator = null
        pct = targetPct
        activeColor = targetColor
        super.onDetachedFromWindow()
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
                // TalkBack reads the value the drag left (HA's bind that follows matches it, so it says nothing).
                dev.glassworks.engine.vitro.ui.VitroAccess.valueChanged(this)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                // Nothing was sent: back to the bound value.
                // ...and the caption with it (onChange), without onRelease: nothing is sent.
                bound?.let { targetPct = -1f; setPercent(it); onChange?.invoke(it) }
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun track(y: Float) {
        val h = height.toFloat().coerceAtLeast(1f)
        pctAnimator?.cancel()
        pctAnimator = null
        pct = ((1f - y / h) * 100f).coerceIn(0f, 100f)
        targetPct = pct
        invalidate()
        onChange?.invoke(pct.roundToInt())
    }

    private companion object {
        const val NEUTRAL = 0xFF888888.toInt()
        const val MOTION_MS = 180L
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

    init {
        // A switch for TalkBack and a keyboard: a click flips it, as a tap does.
        isClickable = true
        isFocusable = true
        if (contentDescription == null) contentDescription = HaText.ui("ui.card.common.toggle")
    }

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.Switch::class.java.name
        info.isCheckable = true
        info.isChecked = on
    }

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
            duration = TOGGLE_MS
            interpolator = HaMotion.standard()
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
        frac = if (on) 1f else 0f
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
                if (abs(dy) > DRAG_DP * dp) {
                    // A drag sets the direction it went: up on, down off (no second flip through performClick).
                    val next = dy < 0f
                    if (next != on) {
                        setOn(next)
                        onToggle?.invoke(next)
                    }
                    super.performClick()
                } else {
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        val next = !on
        setOn(next)
        onToggle?.invoke(next)
        return true
    }

    private companion object {
        const val OFF_THUMB = 0xFF9E9E9E.toInt()
        const val DRAG_DP = 24f
        const val TOGGLE_MS = 200L
    }
}

/** What a light's More-Info can offer, from `supported_color_modes` and the attributes it reports. */
internal data class LightCaps(val brightness: Boolean, val color: Boolean, val colorTemp: Boolean) {
    companion object {
        private val COLOR_MODES = setOf("rgb", "hs", "xy", "rgbw", "rgbww")

        fun of(s: EntityState?): LightCaps {
            val modes = when (val v = s?.attributes?.get("supported_color_modes")) {
                is List<*> -> v.mapNotNull { it?.toString() }
                is JSONArray -> (0 until v.length()).map { v.optString(it) }
                else -> emptyList()
            }
            val attrs = s?.attributes.orEmpty()
            val color = modes.any { it in COLOR_MODES } || attrs.containsKey("rgb_color")
            val colorTemp = "color_temp" in modes || attrs.containsKey("color_temp_kelvin") || attrs.containsKey("color_temp")
            val brightness = color || colorTemp || "brightness" in modes || attrs.containsKey("brightness")
            return LightCaps(brightness, color, colorTemp)
        }
    }
}

/**
 * HA's hue/saturation wheel: hue around the circle (red at the right, counter-clockwise through yellow, green,
 * cyan, blue and magenta), white at the center. A 24 dp thumb in the chosen color follows the finger; the color
 * is reported on release through [onRelease].
 */
internal class RgbColorWheelView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private val huePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * dp
        color = Color.WHITE
    }
    private val thumbShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * dp
        color = 0x40000000
    }
    private var hue = 0f
    private var sat = 0f

    var dragging = false
        private set
    var onRelease: ((Int) -> Unit)? = null

    val color: Int get() = LightColor.hsvToArgb(hue, sat, 1f)

    /** The color bound last, kept during a drag so a cancelled one goes back to it. */
    private var bound: Int? = null

    fun setColor(argb: Int) {
        bound = argb
        if (dragging) return
        val (h, s) = LightColor.argbToHueSat(argb)
        if (h != hue || s != sat) { hue = h; sat = s; invalidate() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val cx = w / 2f
        val cy = h / 2f
        val r = radius().coerceAtLeast(1f)
        huePaint.shader = android.graphics.SweepGradient(cx, cy, WHEEL_COLORS, null)
        whitePaint.shader = android.graphics.RadialGradient(cx, cy, r, Color.WHITE, Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP)
    }

    private fun radius(): Float = minOf(width, height) / 2f - 2f * dp

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = radius()
        if (r <= 0f) return
        canvas.drawCircle(cx, cy, r, huePaint)
        canvas.drawCircle(cx, cy, r, whitePaint)
        val (dx, dy) = offsetOf(hue, sat, r)
        val tr = 12f * dp
        thumbFill.color = color
        canvas.drawCircle(cx + dx, cy + dy, tr, thumbFill)
        canvas.drawCircle(cx + dx, cy + dy, tr + thumbShadow.strokeWidth / 2f, thumbShadow)
        canvas.drawCircle(cx + dx, cy + dy, tr - thumbRing.strokeWidth / 2f, thumbRing)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                track(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> track(event.x, event.y)
            MotionEvent.ACTION_UP -> {
                track(event.x, event.y)
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onRelease?.invoke(color)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                bound?.let { hue = -1f; setColor(it) }
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun track(x: Float, y: Float) {
        val (h, s) = hueSatAt(x - width / 2f, y - height / 2f, radius())
        hue = h
        sat = s
        invalidate()
    }

    companion object {
        /** Clockwise on screen from the right, so hue climbs counter-clockwise as on HA's wheel. */
        val WHEEL_COLORS = intArrayOf(
            Color.RED, Color.MAGENTA, Color.BLUE, Color.CYAN, Color.GREEN, Color.YELLOW, Color.RED,
        )

        /** The hue (0..360, counter-clockwise from the right) and saturation (0..1, clamped at the rim) at an offset from the center. */
        fun hueSatAt(dx: Float, dy: Float, radius: Float): Pair<Float, Float> {
            var deg = Math.toDegrees(kotlin.math.atan2(-dy.toDouble(), dx.toDouble())).toFloat()
            if (deg < 0f) deg += 360f
            val sat = (kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat() / radius.coerceAtLeast(1f)).coerceIn(0f, 1f)
            return deg to sat
        }

        /** The offset from the center where [hue] and [sat] sit on a wheel of [radius]. */
        fun offsetOf(hue: Float, sat: Float, radius: Float): Pair<Float, Float> {
            val rad = Math.toRadians(hue.toDouble())
            val d = sat.coerceIn(0f, 1f) * radius
            return (d * kotlin.math.cos(rad)).toFloat() to (-d * kotlin.math.sin(rad)).toFloat()
        }
    }
}

/**
 * HA's color temperature slider: a 36 dp rounded pill running from warm amber at the top (the light's minimum
 * Kelvin) to cool sky blue at the bottom (its maximum), with a white 44×5 dp bar at the current temperature.
 */
internal class ColorTempSliderView(context: Context, private val minK: Int, private val maxK: Int) : View(context) {
    private val dp = resources.displayMetrics.density
    private val rect = RectF()
    private val clip = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val barOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * dp
        color = 0x33000000
    }
    private val barRect = RectF()
    private var kelvin = (minK + maxK) / 2

    /** Where the bar is drawn (0 = top), animated towards [kelvin]'s place. */
    private var shown = fractionOf(kelvin, minK, maxK)
    private var animator: ValueAnimator? = null

    var dragging = false
        private set

    /** A drag or a key edit is under way: what the slider and its caption show is the user's, not HA's. */
    val adjusting: Boolean get() = dragging || access.editing
    var onChange: ((Int) -> Unit)? = null
    var onRelease: ((Int) -> Unit)? = null

    /** TalkBack and keys: a Kelvin slider; an arrow moves it a hundredth of the range. */
    private val access = dev.glassworks.engine.vitro.ui.AdjustableAccess(
        min = { minOf(minK, maxK) }, max = { maxOf(minK, maxK) }, step = { maxOf(1, kotlin.math.abs(maxK - minK) / 100) },
        value = { kelvin },
        preview = { k ->
            animator?.cancel()
            kelvin = k
            shown = fractionOf(k, minK, maxK)
            invalidate()
            onChange?.invoke(k)
            dev.glassworks.engine.vitro.ui.VitroAccess.valueChanged(this)
        },
        commit = { k -> onRelease?.invoke(k) },
        // Back to HA's value, caption included (onChange), as a cancelled drag does: nothing is sent.
        restore = { bound?.let { kelvin = Int.MIN_VALUE; setKelvin(it); onChange?.invoke(it) } },
        // In Kelvin, as the caption reads it, not as a percentage of the range.
        valueText = { k -> "$k K" },
    )

    init {
        isFocusable = true
    }

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        access.onInitialize(info)
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean =
        access.perform(action, arguments) ?: super.performAccessibilityAction(action, arguments)

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
        access.keyDown(keyCode) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean =
        access.keyUp(event) || super.onKeyUp(keyCode, event)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) access.cancel()
    }

    /** Moves the bar to [k]: animated over 180 ms on screen, snapped before attach. Ignored mid-drag. */
    /** The temperature bound last, kept during a drag so a cancelled one goes back to it. */
    private var bound: Int? = null

    fun setKelvin(k: Int) {
        bound = k
        // A drag or a key edit keeps what it shows until it ends.
        if (dragging || access.editing) return
        val c = k.coerceIn(minOf(minK, maxK), maxOf(minK, maxK))
        if (c == kelvin) return
        kelvin = c
        // A new value from HA: TalkBack reads it, as it would a SeekBar's.
        dev.glassworks.engine.vitro.ui.VitroAccess.valueChanged(this)
        animator?.cancel()
        val from = shown
        val to = fractionOf(c, minK, maxK)
        animator = HaMotion.run(this, MOTION_MS) { t ->
            shown = HaMotion.lerp(from, to, t)
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        shown = fractionOf(kelvin, minK, maxK)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.reset()
        clip.addRoundRect(rect, 36f * dp, 36f * dp, Path.Direction.CW)
        fill.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), GRADIENT, null, android.graphics.Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(rect, fill)
        canvas.restore()
        val y = (shown * h).coerceIn(14f * dp, h - 14f * dp)
        val hw = 22f * dp
        val hh = 2.5f * dp
        barRect.set(w / 2f - hw, y - hh, w / 2f + hw, y + hh)
        canvas.drawRoundRect(barRect, hh, hh, bar)
        canvas.drawRoundRect(barRect, hh, hh, barOutline)
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
                onRelease?.invoke(kelvin)
                // TalkBack reads the value the drag left (HA's bind that follows matches it, so it says nothing).
                dev.glassworks.engine.vitro.ui.VitroAccess.valueChanged(this)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                bound?.let { kelvin = Int.MIN_VALUE; setKelvin(it); onChange?.invoke(it) }
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun track(y: Float) {
        animator?.cancel()
        animator = null
        kelvin = kelvinAt(y / height.toFloat().coerceAtLeast(1f), minK, maxK)
        shown = fractionOf(kelvin, minK, maxK)
        invalidate()
        onChange?.invoke(kelvin)
    }

    companion object {
        private const val MOTION_MS = 180L
        const val DEFAULT_MIN_K = 2000
        const val DEFAULT_MAX_K = 6500

        /** Warm amber → warm white → neutral white → cool sky blue, top to bottom. */
        val GRADIENT = intArrayOf(0xFFFF9329.toInt(), 0xFFFFD6AA.toInt(), 0xFFFFFDF7.toInt(), 0xFFA6C8FF.toInt())

        /** Kelvin at [fraction] (0 = top, warm) of the pill, rounded to 50 K; the ends are the device's own limits. */
        fun kelvinAt(fraction: Float, minK: Int, maxK: Int): Int {
            val f = fraction.coerceIn(0f, 1f)
            if (f <= 0f) return minK
            if (f >= 1f) return maxK
            val k = minK + (maxK - minK) * f
            return (Math.round(k / 50f) * 50).coerceIn(minOf(minK, maxK), maxOf(minK, maxK))
        }

        /** Where [kelvin] sits on the pill, 0 (top) to 1 (bottom). */
        fun fractionOf(kelvin: Int, minK: Int, maxK: Int): Float =
            if (maxK == minK) 0.5f else ((kelvin - minK).toFloat() / (maxK - minK)).coerceIn(0f, 1f)
    }
}

/**
 * HA 2026's cover position slider: a purple 36 dp pill that fills from the top down as the cover closes (70 %
 * open fills the top 30 %), with a white 44×4 dp handle 14 dp above the bottom of the fill. Dragging up opens.
 */
internal class VerticalCoverSliderView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private val rect = RectF()
    private val clip = Path()
    private val handleRect = RectF()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CardSurface.withAlpha(PURPLE, 0.18f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PURPLE }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var pct = 0

    /** The position drawn, animated towards [pct]. */
    private var shown = 0f
    private var animator: ValueAnimator? = null

    var dragging = false
        private set

    /** A drag or a key edit is under way: what the slider and its caption show is the user's, not HA's. */
    val adjusting: Boolean get() = dragging || access.editing
    var onChange: ((Int) -> Unit)? = null
    var onRelease: ((Int) -> Unit)? = null

    /** TalkBack and keys: the cover's position, 0–100 % open. */
    private val access = dev.glassworks.engine.vitro.ui.AdjustableAccess(
        min = { 0 }, max = { 100 }, step = { 1 },
        value = { pct },
        preview = { v ->
            animator?.cancel()
            pct = v
            shown = v.toFloat()
            invalidate()
            onChange?.invoke(v)
            dev.glassworks.engine.vitro.ui.VitroAccess.valueChanged(this)
        },
        commit = { v -> onRelease?.invoke(v) },
        // Back to HA's value, caption included (onChange), as a cancelled drag does: nothing is sent.
        restore = { bound?.let { pct = -1; setPercent(it); onChange?.invoke(it) } },
    )

    init {
        isFocusable = true
    }

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        access.onInitialize(info)
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean =
        access.perform(action, arguments) ?: super.performAccessibilityAction(action, arguments)

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
        access.keyDown(keyCode) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean =
        access.keyUp(event) || super.onKeyUp(keyCode, event)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) access.cancel()
    }

    /** Moves the fill to [value] % open: animated over 180 ms on screen, snapped before attach. Ignored mid-drag. */
    /** The position bound last, kept during a drag so a cancelled one goes back to it. */
    private var bound: Int? = null

    fun setPercent(value: Int) {
        bound = value
        // A drag or a key edit keeps what it shows until it ends.
        if (dragging || access.editing) return
        val p = value.coerceIn(0, 100)
        if (p == pct) return
        pct = p
        // A new value from HA: TalkBack reads it, as it would a SeekBar's.
        dev.glassworks.engine.vitro.ui.VitroAccess.valueChanged(this)
        animator?.cancel()
        val from = shown
        animator = HaMotion.run(this, MOTION_MS) { t ->
            shown = HaMotion.lerp(from, p.toFloat(), t)
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        shown = pct.toFloat()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.reset()
        clip.addRoundRect(rect, 36f * dp, 36f * dp, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(rect, track)
        val bottom = (1f - shown.coerceIn(0f, 100f) / 100f) * h
        if (bottom > 0f) {
            canvas.drawRect(0f, 0f, w, bottom, fill)
            val y = (bottom - 14f * dp).coerceAtLeast(10f * dp)
            val hw = 22f * dp
            handleRect.set(w / 2f - hw, y - 2f * dp, w / 2f + hw, y + 2f * dp)
            canvas.drawRoundRect(handleRect, 2f * dp, 2f * dp, handle)
        }
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                trackTo(event.y)
            }
            MotionEvent.ACTION_MOVE -> trackTo(event.y)
            MotionEvent.ACTION_UP -> {
                trackTo(event.y)
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onRelease?.invoke(pct)
                // TalkBack reads the value the drag left (HA's bind that follows matches it, so it says nothing).
                dev.glassworks.engine.vitro.ui.VitroAccess.valueChanged(this)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                bound?.let { pct = -1; setPercent(it); onChange?.invoke(it) }
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun trackTo(y: Float) {
        animator?.cancel()
        animator = null
        pct = positionAt(y, height.toFloat())
        shown = pct.toFloat()
        invalidate()
        onChange?.invoke(pct)
    }

    companion object {
        private const val MOTION_MS = 180L
        const val PURPLE = 0xFF8E63C9.toInt()

        /** The cover header for a position: "Closed", "Open · 70%", "Open". */
        fun stateText(pct: Int): String {
            fun word(state: String) = HaDisplay.domainStateLabel("cover", state) ?: state.replaceFirstChar { it.titlecase(java.util.Locale.ROOT) }
            return when {
                pct <= 0 -> word("closed")
                pct >= 100 -> word("open")
                else -> "${word("open")} · ${HaFormat.formatNumber(pct)}${HaFormat.blankBeforePercent(HaLocale.current)}%"
            }
        }

        /** The bottom edge of the purple fill for a cover [pct] open on a pill [height] tall. */
        fun fillBottom(pct: Int, height: Float): Float = (1f - pct.coerceIn(0, 100) / 100f) * height

        /** The position a finger at [y] asks for: the top is fully open, the bottom closed. */
        fun positionAt(y: Float, height: Float): Int =
            Math.round((1f - y / height.coerceAtLeast(1f)) * 100f).coerceIn(0, 100)
    }
}

/** HA's `ALARM_MODES` (data/alarm_control_panel.ts, frontend 20261028.5): the alarm's modes, as its More Info offers them. */
internal object AlarmModes {
    private class Mode(val feature: Int?, val service: String, val icon: String, val label: () -> String)

    /** In HA's order, with their `AlarmControlPanelEntityFeature` bit (Disarmed has none: always offered). */
    private val MODES = linkedMapOf(
        "armed_home" to Mode(1, "alarm_arm_home", "mdi:home") { HaText.ui("ui.card.alarm_control_panel.modes.armed_home") },
        "armed_away" to Mode(2, "alarm_arm_away", "mdi:lock") { HaText.ui("ui.card.alarm_control_panel.modes.armed_away") },
        "armed_night" to Mode(4, "alarm_arm_night", "mdi:moon-waning-crescent") { HaText.ui("ui.card.alarm_control_panel.modes.armed_night") },
        "armed_vacation" to Mode(32, "alarm_arm_vacation", "mdi:airplane") { HaText.ui("ui.card.alarm_control_panel.modes.armed_vacation") },
        "armed_custom_bypass" to Mode(16, "alarm_arm_custom_bypass", "mdi:shield") { HaText.ui("ui.card.alarm_control_panel.modes.armed_custom_bypass") },
        "disarmed" to Mode(null, "alarm_disarm", "mdi:shield-off") { HaText.ui("ui.card.alarm_control_panel.modes.disarmed") },
    )

    /** The states in which HA's More Info shows the pulsing status and Disarm instead of the modes. */
    val CHANGING = setOf("triggered", "arming", "pending")

    /**
     * `supportedAlarmModes`: the modes whose feature bit [s]'s `supported_features` has, and Disarmed. An alarm
     * without the attribute supports no arming mode (HA's `supportsFeature`).
     */
    fun supported(s: EntityState?): List<String> {
        val features = (s?.attributes?.get("supported_features") as? Number)?.toInt() ?: 0
        return MODES.filter { (_, m) -> m.feature == null || features and m.feature != 0 }.keys.toList()
    }

    fun service(mode: String): String? = MODES[mode]?.service
    fun icon(mode: String): String = MODES[mode]?.icon ?: "mdi:shield"

    /** `ui.card.alarm_control_panel.modes.<mode>`. */
    fun label(mode: String): String = MODES[mode]?.label?.invoke().orEmpty().ifEmpty { mode }

    /**
     * Whether [mode] needs a code: arming with `code_arm_required` and a `code_format`, disarming with a `code_format`
     * (HA's `setProtectedAlarmControlPanelMode`).
     */
    fun needsCode(s: EntityState, mode: String): Boolean {
        val format = s.attributes["code_format"]
        if (format == null || format == JSONObject.NULL) return false
        return mode == "disarmed" || s.attributes["code_arm_required"] == true
    }
}

/**
 * `ha-state-control-alarm_control_panel-modes`: a vertical `ha-control-select` (`role="radiogroup"`) on
 * `--disabled-color` at 20 % with `--ha-border-radius-6xl` corners and 4 px padding, one option per supported mode
 * ([AlarmModes.supported]) 4 px apart, each a 20 px icon over its label (14 px / 500); the current mode is filled in
 * the state's color, its content white. A tap selects at once and calls [onPick] with a way to go back.
 */
@SuppressLint("ViewConstructor")
internal class AlarmModesSelect(
    private val ctx: VitroCardContext,
    private val onPick: (mode: String, revert: () -> Unit) -> Unit,
) : LinearLayout(ctx.context) {
    private var modes: List<String> = emptyList()
    private var selected: String? = null
    private var selectColor = ctx.theme.primaryColor
    private var disabled = false
    private val fill = GradientDrawable().apply { cornerRadius = 36f * ctx.dp }

    /** The options by mode, for tests. */
    val options = LinkedHashMap<String, FrameLayout>()

    init {
        orientation = VERTICAL
        background = fill
        val pad = ctx.px(4f)
        setPadding(pad, pad, pad, pad)
        contentDescription = HaText.ui("ui.card.alarm_control_panel.modes_label")
    }

    /** Shows [s]'s modes with its current one selected in [stateColor]; unavailable, it's disabled. */
    fun bind(s: EntityState?, stateColor: Int, disabledColor: Int) {
        val list = AlarmModes.supported(s)
        if (list != modes) build(list)
        selected = s?.state?.takeIf { it in list }
        selectColor = stateColor
        disabled = s == null || s.state == "unavailable"
        fill.setColor(CardSurface.withAlpha(disabledColor, 0.2f))
        paint(disabledColor)
    }

    private fun build(list: List<String>) {
        modes = list
        removeAllViews()
        options.clear()
        list.forEachIndexed { i, mode ->
            val icon = MdiIconView(ctx.context)
            val label = TextView(ctx.context).apply {
                text = AlarmModes.label(mode)
                textSize = ctx.scaledSp(14f)
                typeface = VitroFonts.typeface(ctx.context, Inter.MEDIUM, ctx.scaledSp(14f))
                includeFontPadding = false
                gravity = Gravity.CENTER
            }
            val content = LinearLayout(ctx.context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                setPadding(ctx.px(2f), ctx.px(2f), ctx.px(2f), ctx.px(2f))
                addView(icon, LayoutParams(ctx.px(20f), ctx.px(20f)))
                // The label's block line box: 14 px at `--ha-line-height-normal` (1.6).
                addView(label, LayoutParams(LayoutParams.MATCH_PARENT, lineBoxPx(ctx, 14f, 1.6f)))
            }
            val option = FrameLayout(ctx.context).apply {
                addView(content, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                tag = icon to label
                applyHaRipple(this, 32f * ctx.dp, ctx.theme.dark)
                dev.glassworks.engine.vitro.ui.VitroAccess.role(this, dev.glassworks.engine.vitro.ui.A11yRole.RADIO, AlarmModes.label(mode)) { selected == mode }
                setOnClickListener { pick(mode) }
            }
            addView(option, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { if (i < list.size - 1) bottomMargin = ctx.px(4f) })
            options[mode] = option
        }
    }

    /** HA's `_valueChanged`: the same mode does nothing; another selects at once, and goes back if it's cancelled. */
    private fun pick(mode: String) {
        if (disabled || mode == selected) return
        val old = selected
        selected = mode
        paint(null)
        onPick(mode) {
            selected = old
            paint(null)
        }
    }

    private var lastDisabledColor = 0

    private fun paint(disabledColor: Int?) {
        disabledColor?.let { lastDisabledColor = it }
        val text = if (disabled) lastDisabledColor else ctx.theme.primaryTextColor
        for ((mode, option) in options) {
            @Suppress("UNCHECKED_CAST")
            val (icon, label) = option.tag as Pair<MdiIconView, TextView>
            val on = mode == selected
            option.background = if (on) GradientDrawable().apply {
                cornerRadius = 32f * ctx.dp
                setColor(if (disabled) lastDisabledColor else selectColor)
            } else null
            val fg = if (on) Color.WHITE else text
            icon.setIcon(AlarmModes.icon(mode), fg)
            label.setTextColor(fg)
            option.isEnabled = !disabled
        }
    }
}
