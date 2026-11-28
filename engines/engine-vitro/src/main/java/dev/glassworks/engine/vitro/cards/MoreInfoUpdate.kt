/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.data.HaFormat
import dev.glassworks.engine.vitro.data.HaText
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.MdiIcons
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.ui.Inter
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * `more-info-update` (HA 20261028.5) under the dialog's state row (`state-card-content`, as HA's dialog shows it for
 * a domain without its newer design): the update's title, its installed and latest versions, the release
 * announcement link, then its release notes (`update/release_notes`) or summary in `ha-markdown` (no `breaks`), and
 * a progress bar while it installs. [footer] is the dialog's sticky footer: Skip (or Clear skipped) and Update, which
 * shows its progress while installing, and why an install failed. An update with the backup option, one of the
 * Supervisor's, or one that updates itself (Skip then asks first) is HA's own dialog ([MoreInfoRoute]).
 */
internal class MoreInfoUpdateView(
    private val ctx: VitroCardContext,
    private val entityId: String,
    private val onLink: (String) -> Unit,
) : LinearLayout(ctx.context) {
    private val row = MoreInfoStateRow(ctx, entityId)
    private val progress = HaProgressBar(ctx)
    private val title = cssText(ctx, 16.38f, ctx.theme.primaryTextColor, 700)
    private val error = HaAlert(ctx)
    private val installedKey = cssText(ctx, 14f, ctx.theme.primaryTextColor)
    private val installedValue = cssText(ctx, 14f, ctx.theme.primaryTextColor)
    private val latestKey = cssText(ctx, 14f, ctx.theme.primaryTextColor)
    private val latestValue = cssText(ctx, 14f, ctx.theme.primaryTextColor)
    private val link = cssText(ctx, 14f, ctx.theme.primaryColor).apply {
        paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        isClickable = true
        isFocusable = true
    }
    private val rule = View(ctx.context).apply { setBackgroundColor(ctx.theme.dividerColor) }
    private val notes = LinearLayout(ctx.context).apply { orientation = VERTICAL }
    private val loader = FrameLayout(ctx.context).apply {
        addView(HaSpinner(ctx, 48f), FrameLayout.LayoutParams(ctx.px(48f), ctx.px(48f), Gravity.CENTER))
    }
    private val renderer = MarkdownRenderer(ctx)

    /** The sticky footer: the dialog puts it under the scrolling content. */
    val footer = LinearLayout(ctx.context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = GradientDrawable().apply { setColor(dialogSurface(ctx.theme)) }
    }
    private val installError = HaAlert(ctx)
    private val skip = HaButton(ctx, accent = false)
    private val install = HaButton(ctx, accent = true).apply { setLabel(HaText.ui("ui.dialogs.more_info_control.update.update")) }

    /** `_releaseNotes`: not fetched yet ([NOT_FETCHED]), none (null), or the Markdown. */
    private var releaseNotes: Any? = NOT_FETCHED
    private var notesError: String? = null
    private var installErrorText: String? = null
    private var shownMarkdown: String? = null
    private var fetched = false
    private var detached = false

    init {
        orientation = VERTICAL
        // The state row (40 px, its sides the dialog's 24 px), 16 px above the update's own content.
        addView(row, LayoutParams(MATCH_PARENT, ctx.px(40f)).apply { bottomMargin = ctx.px(16f) })
        val content = LinearLayout(ctx.context).apply {
            orientation = VERTICAL
            setPadding(ctx.px(24f), 0, ctx.px(24f), 0)
        }
        // .summary: the progress bar, the h3 (16.38 px bold, 1em above and below), the alert, the version rows.
        content.addView(progress, LayoutParams(MATCH_PARENT, ctx.px(12f)))
        content.addView(title, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = Math.round(16.38f * ctx.dp)
            bottomMargin = Math.round(16.38f * ctx.dp)
        })
        content.addView(error, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        content.addView(keyValue(installedKey, installedValue), LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        content.addView(keyValue(latestKey, latestValue), LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        content.addView(link, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        link.text = HaText.ui("ui.dialogs.more_info_control.update.release_announcement")
        // The summary's 16 px, then the hr (1 px, 16 px below it), then the notes with their 16 px bottom padding.
        content.addView(rule, LayoutParams(MATCH_PARENT, ctx.px(1f).coerceAtLeast(1)).apply {
            topMargin = ctx.px(16f)
            bottomMargin = ctx.px(16f)
        })
        content.addView(loader, LayoutParams(MATCH_PARENT, ctx.px(80f)).apply { bottomMargin = 0 })
        notes.setPadding(0, 0, 0, ctx.px(16f))
        content.addView(notes, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        // Without notes, the summary's own 16 px margin ends the content.
        content.addView(View(ctx.context), LayoutParams(MATCH_PARENT, 0))
        addView(content, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        error.visibility = GONE

        // .footer: a 1 px top border; the install error (8 px over, 16 px sides); the actions (16 px around, 8 px apart,
        // at the end).
        footer.addView(View(ctx.context).apply { setBackgroundColor(ctx.theme.dividerColor) }, LayoutParams(MATCH_PARENT, ctx.px(1f).coerceAtLeast(1)))
        footer.addView(FrameLayout(ctx.context).apply {
            setPadding(ctx.px(16f), ctx.px(8f), ctx.px(16f), 0)
            addView(installError, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val actions = LinearLayout(ctx.context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(ctx.px(16f), ctx.px(16f), ctx.px(16f), ctx.px(16f))
        }
        actions.addView(skip, LayoutParams(WRAP_CONTENT, ctx.px(40f)))
        actions.addView(install, LayoutParams(WRAP_CONTENT, ctx.px(40f)).apply { marginStart = ctx.px(8f) })
        footer.addView(actions, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        (installError.parent as View).visibility = GONE

        link.setOnClickListener { ctx.store[entityId]?.let(::releaseUrl)?.let(onLink) }
        skip.setOnClickListener { skipOrClear() }
        install.setOnClickListener { startInstall() }
    }

    override fun onDetachedFromWindow() {
        detached = true
        super.onDetachedFromWindow()
    }

    private fun keyValue(key: TextView, value: TextView) = LinearLayout(ctx.context).apply {
        orientation = HORIZONTAL
        addView(key, LayoutParams(0, WRAP_CONTENT, 1f))
        addView(value, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        value.gravity = Gravity.END
    }

    fun update(s: EntityState?) {
        row.bind(s, s?.let { updateStateText(it) } ?: HaText.ui("state.default.unknown"))
        // more-info-update draws nothing while the update is unknown or unavailable: the row alone.
        val shown = s != null && !s.isUnavailable
        (getChildAt(1) as View).visibility = if (shown) VISIBLE else GONE
        footer.visibility = if (shown) VISIBLE else GONE
        if (!shown) return
        s!!
        if (!fetched && s.supports(UpdateFeatures.RELEASE_NOTES)) {
            fetched = true
            fetchReleaseNotes()
        }
        val attrs = s.attributes
        val installing = attrs["in_progress"] == true
        progress.visibility = if (installing) VISIBLE else GONE
        val pct = (attrs["update_percentage"] as? Number)?.toFloat()
        progress.bind(if (s.supports(UpdateFeatures.PROGRESS) && pct != null) pct else null)
        title.text = attrs["title"]?.toString().orEmpty()
        (title.layoutParams as LayoutParams).topMargin = Math.round(16.38f * ctx.dp)
        error.visibility = if (notesError != null) VISIBLE else GONE
        notesError?.let { error.bind(it) }
        installedKey.text = HaDisplay.attributeName(s, "installed_version")
        installedValue.text = attrs["installed_version"]?.toString() ?: HaText.ui("state.default.unavailable")
        latestKey.text = HaDisplay.attributeName(s, "latest_version")
        latestValue.text = attrs["latest_version"]?.toString() ?: HaText.ui("state.default.unavailable")
        link.visibility = if (releaseUrl(s) != null) VISIBLE else GONE
        bindNotes(s)
        bindFooter(s)
    }

    /** The hr and what follows it: the notes (or, until they come, the spinner), else the release summary. */
    private fun bindNotes(s: EntityState) {
        val markdown: String?
        var loading = false
        if (s.supports(UpdateFeatures.RELEASE_NOTES) && notesError == null) {
            when (val n = releaseNotes) {
                NOT_FETCHED -> { markdown = null; loading = true }
                is String -> markdown = n.takeIf { it.isNotEmpty() }
                else -> markdown = null
            }
        } else {
            markdown = s.attributes["release_summary"]?.toString()?.takeIf { it.isNotEmpty() }
        }
        rule.visibility = if (loading || markdown != null) VISIBLE else GONE
        loader.visibility = if (loading) VISIBLE else GONE
        notes.visibility = if (markdown != null) VISIBLE else GONE
        if (markdown != null && markdown != shownMarkdown) {
            shownMarkdown = markdown
            // ha-markdown, as the dialog uses it: no `breaks`, the dialog's 14 px text at a normal line height.
            val root = renderer.defaultRootStyle().apply {
                lineHeight = -1f
                lineHeightFactor = -1f
            }
            renderer.render(markdown, notes, root, MdSheet.EMPTY, breaks = false)
        }
        // The content's last margin: the summary's 16 px when nothing follows it.
        (getChildAt(1) as LinearLayout).let { c ->
            (c.getChildAt(c.childCount - 1).layoutParams as LayoutParams).height = if (rule.visibility == GONE) ctx.px(16f) else 0
        }
    }

    private fun bindFooter(s: EntityState) {
        val attrs = s.attributes
        val installing = attrs["in_progress"] == true
        val latest = attrs["latest_version"]?.toString()
        val skipped = attrs["skipped_version"]?.toString()
        val latestSkipped = latest != null && skipped == latest
        if (s.state == "off" && skipped != null) {
            skip.setLabel(HaText.ui("ui.dialogs.more_info_control.update.clear_skipped"))
            skip.setState(enabled = true, loading = false)
        } else {
            skip.setLabel(HaText.ui("ui.dialogs.more_info_control.update.skip"))
            skip.setState(enabled = !(latestSkipped || s.state == "off" || installing), loading = false)
        }
        install.visibility = if (s.supports(UpdateFeatures.INSTALL)) VISIBLE else GONE
        // `updateButtonIsDisabled`: off, unless it's the skipped latest version.
        install.setState(enabled = !(s.state == "off" && !latestSkipped), loading = installing)
        val failed = installErrorText
        (installError.parent as View).visibility = if (failed != null) VISIBLE else GONE
        if (failed != null) installError.bind(HaText.ui("ui.dialogs.more_info_control.update.install_failed", "error" to failed))
    }

    private fun skipOrClear() {
        val s = ctx.store[entityId] ?: return
        val service = if (s.state == "off" && s.attributes["skipped_version"] != null) "clear_skipped" else "skip"
        ctx.callService("update", service, JSONObject().put("entity_id", entityId), null)
    }

    /** `_handleInstall`: `update.install`, with the version when the update installs a given one. A failure shows. */
    private fun startInstall() {
        val s = ctx.store[entityId] ?: return
        val data = JSONObject().put("entity_id", entityId)
        val latest = s.attributes["latest_version"]?.toString()
        if (s.supports(UpdateFeatures.SPECIFIC_VERSION) && latest != null) data.put("version", latest)
        installErrorText = null
        bindFooter(s)
        val msg = JSONObject().put("type", "call_service").put("domain", "update").put("service", "install").put("service_data", data)
        ctx.callWs(msg) { ok, _, err ->
            ctx.main.post {
                if (ok) return@post
                val message = err?.optString("message")?.takeIf { it.isNotEmpty() } ?: err?.optString("code").orEmpty()
                if (!shownInSheet()) {
                    // `_isShown()` is false: the dialog closed, is closing, or shows its History tab. HA tells it in a
                    // toast over the page, for 10 s. (Its Supervisor logs action is for a Supervisor update, which
                    // HA's own dialog shows: MoreInfoRoute.)
                    ctx.showToast(dev.glassworks.engine.vitro.ui.HaToast(
                        HaText.ui("ui.dialogs.more_info_control.update.install_failed", "error" to message),
                        durationMs = INSTALL_FAILED_TOAST_MS,
                    ))
                    return@post
                }
                installErrorText = message
                ctx.store[entityId]?.let(::bindFooter)
            }
        }
    }

    private fun fetchReleaseNotes() {
        ctx.callWs(JSONObject().put("type", "update/release_notes").put("entity_id", entityId)) { ok, result, err ->
            ctx.main.post {
                if (detached) return@post
                if (ok) {
                    releaseNotes = (result as? String)
                } else {
                    notesError = err?.optString("message").orEmpty()
                    releaseNotes = null
                }
                ctx.store[entityId]?.let(::update)
            }
        }
    }

    /** HA's `_isShown()`: this view is in the sheet, and the sheet is open (not closing). */
    private fun shownInSheet(): Boolean {
        if (detached) return false
        var p = parent
        while (p != null) {
            if (p is MoreInfoSheet) return !p.isDismissed
            p = p.parent
        }
        return true
    }

    companion object {
        private val NOT_FETCHED = Any()

        /** How long HA's install-failed toast stays. */
        const val INSTALL_FAILED_TOAST_MS = 10_000L

        /** `sanitizeHttpUrl`: an http(s) URL, else none. */
        fun releaseUrl(s: EntityState): String? = s.attributes["release_url"]?.toString()?.trim()
            ?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }

        /** `computeUpdateStateDisplay`: installing (with its progress), the skipped version, else HA's state. */
        fun updateStateText(s: EntityState): String {
            val attrs = s.attributes
            if (attrs["in_progress"] == true) {
                val pct = attrs["update_percentage"] as? Number
                if (s.supports(UpdateFeatures.PROGRESS) && pct == null) return HaText.ui("ui.card.update.preparing")
                if (s.supports(UpdateFeatures.PROGRESS) && pct != null) {
                    val digits = (attrs["display_precision"] as? Number)?.toInt() ?: 0
                    val opts = HaFormat.NumberOptions(minimumFractionDigits = digits, maximumFractionDigits = digits)
                    return HaText.ui("ui.card.update.installing_with_progress", "progress" to HaFormat.formatNumber(pct, options = opts))
                }
                return HaText.ui("ui.card.update.installing")
            }
            val latest = attrs["latest_version"]?.toString()
            if (s.state == "off" && latest != null && attrs["skipped_version"]?.toString() == latest) return latest
            return s.formattedState()
        }
    }
}

/**
 * The dialog's `state-card-content` row for a plain entity (`state-info` and its state): the 40 px `state-badge` (the
 * entity's picture in a circle, else its icon in its state color), the name over when it last changed, and the state
 * at the end. Its sides are the dialog's 24 px.
 */
internal class MoreInfoStateRow(private val ctx: VitroCardContext, private val entityId: String) : LinearLayout(ctx.context) {
    private val icon = MdiIconView(ctx.context)
    private val picture = CirclePicture(ctx)
    private val name = cssText(ctx, 14f, ctx.theme.primaryTextColor).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
    }
    private val time = cssText(ctx, 14f, ctx.theme.secondaryTextColor).apply {
        maxLines = 1
        gravity = Gravity.CENTER_VERTICAL
    }
    private val value = cssText(ctx, 14f, ctx.theme.primaryTextColor).apply {
        maxLines = 1
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
    }
    private var pictureUrl: String? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.px(24f), 0, ctx.px(24f), 0)
        val badge = FrameLayout(ctx.context)
        badge.addView(icon, FrameLayout.LayoutParams(ctx.px(24f), ctx.px(24f), Gravity.CENTER))
        badge.addView(picture, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(badge, LayoutParams(ctx.px(40f), ctx.px(40f)))
        val labels = LinearLayout(ctx.context).apply { orientation = VERTICAL }
        labels.addView(name, LayoutParams(MATCH_PARENT, Math.round(16.8f * ctx.dp)))
        labels.addView(time, LayoutParams(MATCH_PARENT, Math.round(22.4f * ctx.dp)))
        addView(labels, LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = ctx.px(8f) })
        addView(value, LayoutParams(WRAP_CONTENT, ctx.px(40f)).apply { marginStart = ctx.px(8f) })
    }

    /** Shows [s] with [state] at the end. */
    fun bind(s: EntityState?, state: String) {
        val url = s?.entityPicture
        if (url != pictureUrl) {
            pictureUrl = url
            picture.setBitmap(null)
            if (url != null) ctx.loadImage(url, owner = picture) { bmp -> if (pictureUrl == url) picture.setBitmap(bmp) }
        }
        picture.visibility = if (url != null) VISIBLE else GONE
        icon.visibility = if (url != null) INVISIBLE else VISIBLE
        // state-badge: the state's color where HA colors it (an update available is orange), else the icon color. Its
        // `--state-inactive-color: initial` keeps an inactive state (a skipped update is off) in the icon color.
        icon.setIcon(MdiIcons.resolveIconName(null, s), s?.let { HaStateColor.of(ctx, it, inactiveDefault = false) } ?: ctx.theme.stateIconColor)
        val parts = HaDisplay.nameParts(entityId, s?.friendlyName ?: entityId)
        name.text = parts.entity ?: parts.device ?: s?.friendlyName ?: entityId
        time.text = s?.takeIf { it.lastChanged > 0.0 }?.let { HaFormat.relativeTime((it.lastChanged * 1000).toLong(), ctx.now()) }.orEmpty()
        value.text = state
    }

    /** `state-badge`'s picture: `background-size: cover` in a circle. */
    private class CirclePicture(ctx: VitroCardContext) : View(ctx.context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private var bitmap: Bitmap? = null

        fun setBitmap(b: Bitmap?) {
            bitmap = b
            paint.shader = null
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val b = bitmap ?: return
            if (paint.shader == null || width == 0) {
                val shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                val scale = maxOf(width / b.width.toFloat(), height / b.height.toFloat())
                shader.setLocalMatrix(android.graphics.Matrix().apply {
                    setScale(scale, scale)
                    postTranslate((width - b.width * scale) / 2f, (height - b.height * scale) / 2f)
                })
                paint.shader = shader
            }
            canvas.drawCircle(width / 2f, height / 2f, minOf(width, height) / 2f, paint)
        }
    }
}

/**
 * `ha-button` (webawesome's, 40 px): a pill with 16 px inside a 1 px border, a 14 px medium label. [accent]: filled
 * in `--ha-color-fill-primary-loud-resting` with white text; else `plain`, the label in `--ha-color-on-primary-normal`.
 * Disabled, both take HA's disabled colors; loading, the label hides behind a spinner.
 */
internal class HaButton(private val ctx: VitroCardContext, private val accent: Boolean) : FrameLayout(ctx.context) {
    private val label = cssText(ctx, 14f, Color.WHITE, Inter.MEDIUM).apply {
        maxLines = 1
        gravity = Gravity.CENTER
    }
    private val spinner = HaSpinner(ctx, 14f, small = true)
    private val fill = GradientDrawable().apply { cornerRadius = 999f * ctx.dp }
    private var loading = false

    init {
        setPadding(ctx.px(17f), 0, ctx.px(17f), 0)
        background = fill
        addView(label, LayoutParams(WRAP_CONTENT, MATCH_PARENT, Gravity.CENTER))
        addView(spinner, LayoutParams(ctx.px(14f), ctx.px(14f), Gravity.CENTER))
        isClickable = true
        isFocusable = true
        applyHaRipple(this, 999f * ctx.dp, ctx.theme.dark)
        setState(enabled = true, loading = false)
    }

    fun setLabel(text: String) {
        label.text = text
        contentDescription = text
    }

    fun setState(enabled: Boolean, loading: Boolean) {
        isEnabled = enabled && !loading
        this.loading = loading
        val dark = ctx.theme.dark
        fun v(name: String) = ThemeResolver.parseColor(ctx.theme.vars[name])
        val (bg, fg) = when {
            !enabled && accent -> (v("ha-color-fill-disabled-loud-resting") ?: if (dark) 0xFF4A4A4A.toInt() else 0xFFCCCCCC.toInt()) to
                (v("ha-color-on-disabled-loud") ?: if (dark) 0xFF7A7A7A.toInt() else 0xFFF3F3F3.toInt())
            !enabled -> Color.TRANSPARENT to (v("ha-color-on-disabled-quiet") ?: if (dark) 0xFF5E5E5E.toInt() else 0xFFCCCCCC.toInt())
            accent -> (v("ha-color-fill-primary-loud-resting") ?: 0xFF009AC7.toInt()) to (v("ha-color-on-primary-loud") ?: Color.WHITE)
            else -> Color.TRANSPARENT to onPrimaryNormal(ctx)
        }
        fill.setColor(bg)
        label.setTextColor(fg)
        spinner.color = fg
        // `:host([loading])`: the label keeps its room, hidden; the spinner turns in its place.
        label.visibility = if (loading) INVISIBLE else VISIBLE
        spinner.visibility = if (loading) VISIBLE else GONE
    }
}

/** HA's `--ha-color-on-primary-normal`: a plain button's label and the progress bar's indicator. */
internal fun onPrimaryNormal(ctx: VitroCardContext): Int =
    ThemeResolver.parseColor(ctx.theme.vars["ha-color-on-primary-normal"]) ?: if (ctx.theme.dark) 0xFF37C8FD.toInt() else 0xFF009AC7.toInt()

/**
 * `ha-progress-bar` (12 px, a pill): the track in `--ha-color-fill-neutral-normal-hover`, the indicator in
 * `--ha-color-on-primary-normal` to [bind]'s percentage with the loading highlight sweeping along it, or, without one,
 * the indeterminate run. Still where animations are off.
 */
internal class HaProgressBar(private val ctx: VitroCardContext) : View(ctx.context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = ThemeResolver.parseColor(ctx.theme.vars["ha-color-fill-neutral-normal-hover"])
        ?: if (ctx.theme.dark) 0xFF363636.toInt() else 0xFFCCCCCC.toInt()
    private val indicator = onPrimaryNormal(ctx)
    private var percent: Float? = null
    private val start = SystemClock.uptimeMillis()

    fun bind(pct: Float?) {
        if (pct == percent) return
        percent = pct
        invalidate()
    }

    private fun animating() = runCatching {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }.getOrDefault(true)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        val save = canvas.save()
        val clip = android.graphics.Path().apply { addRoundRect(RectF(0f, 0f, w, h), r, r, android.graphics.Path.Direction.CW) }
        canvas.clipPath(clip)
        paint.shader = null
        paint.color = track
        canvas.drawRect(0f, 0f, w, h, paint)
        val anim = animating()
        val t = ((SystemClock.uptimeMillis() - start) % 2500L) / 2500f
        val pct = percent
        paint.color = indicator
        if (pct != null) {
            val end = w * pct.coerceIn(0f, 100f) / 100f
            canvas.drawRect(0f, 0f, end, h, paint)
            if (anim) {
                // The loading highlight: half the bar wide, from -200 % to 200 % of its width, over 2.5 s.
                val hw = w / 2f
                val x = -2f * hw + 4f * hw * VacuumEase.inOut(t)
                paint.shader = LinearGradient(x, 0f, x + hw, 0f, intArrayOf(Color.TRANSPARENT, lighter(indicator), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                paint.alpha = 128
                canvas.drawRect(x, 0f, minOf(x + hw, end), h, paint)
                paint.alpha = 255
                paint.shader = null
            }
        } else {
            // wa-progress-indeterminate: a half-width indicator from -50 % to 100 % over the first 75 %.
            val p = if (anim) (t / 0.75f).coerceAtMost(1f) else 0f
            val left = w * (-0.5f + 1.5f * VacuumEase.inOut(p))
            canvas.drawRect(left, 0f, left + w / 2f, h, paint)
        }
        canvas.restoreToCount(save)
        if (anim && isAttachedToWindow && visibility == VISIBLE) postInvalidateOnAnimation()
    }

    private fun lighter(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[2] = 1f
        hsv[1] *= 0.5f
        return Color.HSVToColor(hsv)
    }
}

/** `cubic-bezier(0.37, 0, 0.63, 1)`, the progress bar's timing. */
internal object VacuumEase {
    fun inOut(x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        var lo = 0f
        var hi = 1f
        var u = x
        repeat(24) {
            val bx = 3 * (1 - u) * (1 - u) * u * 0.37f + 3 * (1 - u) * u * u * 0.63f + u * u * u
            if (bx < x) lo = u else hi = u
            u = (lo + hi) / 2
        }
        return 3 * (1 - u) * u * u + u * u * u
    }
}

/**
 * `ha-spinner` / webawesome's spinner: a [sizeDp] ring, its track in `--divider-color` (a button's: its label color at
 * 10 %), the indicator arc in `--primary-color` (a button's: its label color) turning once every [periodMs].
 */
internal class HaSpinner(private val ctx: VitroCardContext, private val sizeDp: Float, private val small: Boolean = false) : View(ctx.context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    var color: Int = ctx.theme.primaryColor
        set(v) { field = v; invalidate() }
    private val start = SystemClock.uptimeMillis()
    private val periodMs = if (small) 2000L else 3500L

    override fun onDraw(canvas: Canvas) {
        val stroke = (if (small) 2f else 4f) * ctx.dp
        paint.strokeWidth = stroke
        val r = minOf(width, height) / 2f - stroke / 2f
        val cx = width / 2f
        val cy = height / 2f
        paint.color = if (small) CardSurface.withAlpha(color, 0.1f) else ctx.theme.dividerColor
        canvas.drawCircle(cx, cy, r, paint)
        paint.color = color
        val anim = runCatching {
            android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        }.getOrDefault(true)
        val turn = if (anim) ((SystemClock.uptimeMillis() - start) % periodMs) / periodMs.toFloat() * 360f else 0f
        canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f + turn, 270f, false, paint)
        if (anim && isAttachedToWindow && visibility == VISIBLE) postInvalidateOnAnimation()
    }
}

/**
 * `ha-alert alert-type="error"`: 8 px around, `--error-color` at 12 % behind (4 px corners), the 24 px
 * `mdi:alert-circle-outline` in `--error-color`, the text 8 px from it at a normal line height.
 */
internal class HaAlert(private val ctx: VitroCardContext) : LinearLayout(ctx.context) {
    private val error = ThemeResolver.parseColor(ctx.theme.vars["error-color"]) ?: 0xFFDB4437.toInt()
    private val text = cssText(ctx, 14f, ctx.theme.primaryTextColor)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.px(8f), ctx.px(8f), ctx.px(8f), ctx.px(8f))
        background = GradientDrawable().apply {
            cornerRadius = 4f * ctx.dp
            setColor(CardSurface.withAlpha(error, 0.12f))
        }
        addView(MdiIconView(ctx.context).apply { setIcon("mdi:alert-circle-outline", error) }, LayoutParams(ctx.px(24f), ctx.px(24f)))
        addView(text, LayoutParams(0, WRAP_CONTENT, 1f).apply {
            marginStart = ctx.px(8f)
            marginEnd = ctx.px(8f)
        })
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun bind(message: String) {
        text.text = message
        contentDescription = message
    }
}
