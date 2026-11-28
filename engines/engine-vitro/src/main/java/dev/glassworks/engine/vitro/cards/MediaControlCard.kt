/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.engine.vitro.data.HaText
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.ui.AdjustableAccess
import dev.glassworks.engine.vitro.ui.CardSurface
import dev.glassworks.engine.vitro.ui.MdiIconView
import dev.glassworks.engine.vitro.ui.MdiIcons
import dev.glassworks.engine.vitro.ui.VitroAccess
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroFonts
import dev.glassworks.ui.Inter
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Native HA `media-control` card, as HA 2026's `hui-media-control-card` draws it.
 *
 * Without artwork the card is the primary color, with HA's placeholder picture on its right half. With artwork the
 * picture covers a square at the right (as wide as the card is tall) and fades into the background color, and the
 * text and icons take the foreground color. Both colors are extracted from the artwork the way HA does it
 * ([ArtworkColors]). On top: the player's icon and name, and a More info button in the corner. Then the title and
 * the artist (or whatever [mediaDescription] gives), HA's controls ([mediaControls]) with browse and join at the
 * end, and an 8 px progress bar while playing or paused. An off player is drawn in grayscale with only its power
 * button; an unavailable one shows only its name. Under 350 px the controls shrink and the progress bar goes; under
 * 300 px only an idle, on or off player keeps its controls.
 */
class MediaControlCardView(ctx: VitroCardContext, private val config: CardConfig) : CardSurface(ctx) {
    private val eid = config.entity
    private val background = MediaBackgroundView()
    private val stateIcon = MdiIconView(ctx.context)
    private val nameView = label(14f, Inter.REGULAR)
    private val moreIcon = MdiIconView(ctx.context)
    private val more = button(moreIcon, 48f, 24f).apply { contentDescription = HaText.ui("ui.panel.lovelace.editor.action-editor.actions.more-info") }
    private val titleView = label(16.8f, Inter.REGULAR)
    private val descriptionView = label(14f, Inter.REGULAR)
    private val mediaInfo = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
    private val start = LinearLayout(ctx.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val end = LinearLayout(ctx.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val controls = LinearLayout(ctx.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val titleControls = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
    private val progress = MediaProgressBar()
    private val lower = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }
    private val player = LinearLayout(ctx.context).apply { orientation = LinearLayout.VERTICAL }

    private var last: EntityState? = null
    private var narrow = false
    private var veryNarrow = false
    private var imageUrl: String? = null
    private var colors: ArtworkColors.Result? = null
    private var placeholder: Bitmap? = null
    private var ticking = false

    private val progressTick = object : Runnable {
        override fun run() {
            if (!ticking) return
            updateProgress(last)
            ctx.main.postDelayed(this, 1_000L)
        }
    }

    // HA lays the card out from its outer edge only for the background; the player sits inside the border.
    init {
        addView(background, LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val nameRow = LinearLayout(ctx.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(stateIcon, LinearLayout.LayoutParams(ctx.px(24f), ctx.px(24f)).apply { marginEnd = ctx.px(8f) })
            addView(nameView, LinearLayout.LayoutParams(WRAP_CONTENT, (22.4f * ctx.dp).roundToInt()))
        }
        // The row is 24.3 px: the 22.4 px name line in a 24 px icon's line box.
        player.addView(nameRow, LinearLayout.LayoutParams(MATCH_PARENT, (24.3f * ctx.dp).roundToInt()))

        titleView.letterSpacing = 0f
        mediaInfo.addView(titleView, LinearLayout.LayoutParams(MATCH_PARENT, (20.2f * ctx.dp).roundToInt()).apply {
            bottomMargin = ctx.px(4f)
        })
        mediaInfo.addView(descriptionView, LinearLayout.LayoutParams(MATCH_PARENT, (22.3f * ctx.dp).roundToInt()))
        titleControls.setPadding(0, ctx.px(16f), 0, 0)
        titleControls.addView(mediaInfo, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        controls.addView(start, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        controls.addView(end, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        // `.controls { margin-inline-start: -12px }`: the first button's ripple lines up its icon with the name.
        titleControls.addView(controls, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { marginStart = -ctx.px(12f) })
        lower.addView(titleControls, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        lower.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, ctx.px(8f)))
        player.addView(lower, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        // `.more-info { position: absolute; top: 4px; inset-inline-end: 4px }`. Added before the player, so TalkBack
        // reads it in HA's order (top row first); the player's rows take no touches, so taps still reach it.
        addView(more, LayoutParams(ctx.px(48f), ctx.px(48f), Gravity.TOP or Gravity.END).apply {
            topMargin = ctx.px(4f); rightMargin = ctx.px(4f)
        })
        addView(player, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        if (eid != null) {
            more.setOnClickListener { ctx.openMoreInfo(eid) }
            observe(eid) { s -> render(s) }
        } else {
            render(null)
        }
        // HA's own static picture, served to anyone: it never needs the panel's token.
        ctx.loadImage(PLACEHOLDER_URL, this, public = true) { bmp ->
            if (bmp != null) {
                placeholder = bmp
                background.invalidate()
            }
        }
    }

    override fun measureCard(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // HA's _measureCard: narrow under 350 px, very narrow under 300.
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val w = MeasureSpec.getSize(widthMeasureSpec) / ctx.dp
            val n = w < 350f
            val vn = w < 300f
            if (n != narrow || vn != veryNarrow) {
                narrow = n
                veryNarrow = vn
                render(last)
            }
        }
        super.measureCard(widthMeasureSpec, heightMeasureSpec)
    }

    private fun label(sp: Float, weight: Int): TextView = TextView(ctx.context).apply {
        val scaled = ctx.scaledSp(sp)
        textSize = scaled
        typeface = VitroFonts.typeface(ctx.context, weight, scaled)
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    /** An `ha-icon-button`: a [sizeDp] circle with its ripple, the icon [iconDp] in the middle. */
    private fun button(icon: MdiIconView, sizeDp: Float, iconDp: Float): FrameLayout = FrameLayout(ctx.context).apply {
        isClickable = true
        addView(icon, LayoutParams(ctx.px(iconDp), ctx.px(iconDp), Gravity.CENTER))
        applyHaRipple(this, sizeDp / 2f * ctx.dp, ctx.theme.dark)
    }

    private fun render(s: EntityState?) {
        last = s
        val state = s?.state
        val unavailable = state == "unavailable"
        val active = s != null && isActive(s)
        val off = s != null && !active && state != "unavailable" && state != "unknown"
        val features = s?.let { featuresOf(it) } ?: 0
        val unavailableLook = unavailable || (off && features and TURN_ON == 0)
        val image = s?.let { imageOf(it) }
        loadArtwork(image)
        val art = if (image != null && image == colors?.url) colors else null

        // Colors: the artwork's, else white text on the primary color.
        val fg = art?.foreground ?: TEXT_PRIMARY
        background.apply(
            color = art?.background ?: ctx.theme.primaryColor,
            artwork = if (image != null && !off && !unavailableLook) art?.bitmap else null,
            showPlaceholder = !unavailable && (image == null || off || unavailableLook),
            gray = off || unavailableLook,
        )

        stateIcon.setIcon(if (s != null) MdiIcons.resolveIconName(null, s) else "mdi:cast", fg)
        nameView.setTextColor(fg)
        nameView.text = config.name(s) ?: s?.displayName ?: eid.orEmpty()
        moreIcon.setIcon("mdi:dots-vertical", fg)

        val buttons = if (s != null && !unavailableLook) removeSecondaryStop(s, mediaControls(s)) else null
        val showControls = !unavailableLook && buttons != null &&
            (!veryNarrow || off || state == "idle" || state == "on")
        val title = cleanupMediaTitle(s?.attributes?.get("media_title") as? String)
        val description = s?.let { mediaDescription(it) }.orEmpty()
        val showLower = !unavailableLook && (description.isNotEmpty() || title != null || showControls)
        val showProgress = !unavailableLook && !narrow && !veryNarrow && s != null && showsProgress(s)
        lower.visibility = if (showLower) VISIBLE else GONE

        mediaInfo.visibility = if (description.isEmpty() && title == null) GONE else VISIBLE
        titleView.text = title ?: description
        titleView.setTextColor(fg)
        descriptionView.text = if (title != null) description else ""
        descriptionView.visibility = if (title != null && description.isNotEmpty()) VISIBLE else GONE
        descriptionView.setTextColor(fg)

        start.removeAllViews()
        end.removeAllViews()
        controls.visibility = if (showControls) VISIBLE else GONE
        if (showControls && s != null) {
            for (b in buttons.orEmpty()) {
                val main = b.action in MAIN_ACTIONS && !(narrow && b.action == "media_pause")
                val size = when {
                    narrow && !veryNarrow -> if (main) 50f else 40f
                    else -> if (main) 56f else 44f
                }
                val iconSize = when {
                    narrow && !veryNarrow -> if (main) 36f else 28f
                    else -> if (main) 40f else 30f
                }
                val icon = MdiIconView(ctx.context).apply { setIcon(b.icon, fg) }
                start.addView(button(icon, size, iconSize).apply {
                    contentDescription = ACTION_LABELS[b.action]
                    setOnClickListener { call(b.action) }
                }, LinearLayout.LayoutParams(ctx.px(size), ctx.px(size)))
            }
            val endSize = if (narrow && !veryNarrow) 40f else 44f
            if (features and BROWSE_MEDIA != 0) {
                end.addView(button(MdiIconView(ctx.context).apply { setIcon("mdi:play-box-multiple", fg) }, endSize, 24f).apply {
                    contentDescription = HaText.ui("ui.card.media_player.browse_media")
                    // Vitro has no media browser: More info is the nearest place to pick media.
                    setOnClickListener { eid?.let(ctx.openMoreInfo) }
                }, LinearLayout.LayoutParams(ctx.px(endSize), ctx.px(endSize)))
            }
            if (features and GROUPING != 0) {
                val join = button(MdiIconView(ctx.context).apply { setIcon("mdi:speaker-multiple", fg) }, endSize, 24f).apply {
                    contentDescription = HaText.ui("ui.card.media_player.join")
                    setOnClickListener { eid?.let(ctx.openMoreInfo) }
                }
                val members = (s.attributes["group_members"] as? List<*>)?.size ?: 0
                if (members > 1) join.addView(groupBadge(members), FrameLayout.LayoutParams(WRAP_CONTENT, ctx.px(16f), Gravity.TOP or Gravity.END))
                end.addView(join, LinearLayout.LayoutParams(ctx.px(endSize), ctx.px(endSize)))
            }
        }
        // `.controls { padding: 8px 8px 8px 0 }`; none without artwork, no bottom one without the progress bar.
        val pad = if (image == null) 0 else ctx.px(8f)
        controls.setPadding(0, pad, pad, if (showProgress) pad else 0)

        progress.visibility = if (showProgress) VISIBLE else GONE
        progress.indicator = art?.foreground ?: ctx.theme.accentColor
        // HA's ha-slider seeks only for a player with SEEK; for the rest it just shows the position.
        progress.duration = if (s != null && featuresOf(s) and SEEK != 0) (s.attributes["media_duration"] as? Number)?.toDouble() ?: 0.0 else 0.0
        updateProgress(s)
        setTicking(showProgress && state == "playing" && isAttachedToWindow && isShown)

        // `.player { padding: 16px }`, no bottom padding when there are controls but no progress bar.
        val p = ctx.px(16f)
        player.setPadding(p, p, p, if (!showProgress && showControls && showLower) 0 else p)
        contentDescription = listOfNotNull(nameView.text?.toString(), title, description.ifEmpty { null }).joinToString(", ")
    }

    private fun groupBadge(count: Int): TextView = label(12f, Inter.REGULAR).apply {
        text = count.toString()
        gravity = Gravity.CENTER
        minWidth = ctx.px(16f)
        setPadding(ctx.px(4f), 0, ctx.px(4f), 0)
        setTextColor(TEXT_PRIMARY)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = ctx.px(10f).toFloat()
            setColor(ctx.theme.accentColor)
        }
    }

    private fun call(action: String) {
        val id = eid ?: return
        ctx.callService("media_player", action, JSONObject().put("entity_id", id), null)
    }

    private fun loadArtwork(url: String?) {
        if (url == imageUrl) return
        imageUrl = url
        if (url == null) {
            colors = null
            return
        }
        ctx.loadImage(url, this) { bmp ->
            if (bmp == null || imageUrl != url) return@loadImage
            EXTRACTOR.execute {
                val result = runCatching { ArtworkColors.extract(bmp) }.getOrNull()
                ctx.main.post {
                    if (imageUrl != url) return@post
                    colors = result?.let { ArtworkColors.Result(url, bmp, it.first, it.second) }
                    render(last)
                }
            }
        }
    }

    private fun updateProgress(s: EntityState?) {
        if (s == null || progress.visibility != VISIBLE) return
        val duration = (s.attributes["media_duration"] as? Number)?.toDouble() ?: 0.0
        var position = (s.attributes["media_position"] as? Number)?.toDouble() ?: 0.0
        if (s.state == "playing") {
            val updated = EntityStore.parseTimestamp(s.attributes["media_position_updated_at"])
            if (updated > 0.0) position += (ctx.now() / 1000.0 - updated).coerceAtLeast(0.0)
        }
        // Not under a finger or a held key: the edit shows where it will seek to.
        if (!progress.held) progress.fraction = if (duration > 0.0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f
    }

    /** HA's `_handleSeek`: `media_player.media_seek` to the whole second the slider was let go at. */
    private fun seek(seconds: Int) {
        val id = eid ?: return
        ctx.callService("media_player", "media_seek", JSONObject().put("entity_id", id).put("seek_position", seconds), null)
    }

    private fun setTicking(on: Boolean) {
        if (on == ticking) return
        ticking = on
        ctx.main.removeCallbacks(progressTick)
        if (on) ctx.main.postDelayed(progressTick, 1_000L)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setTicking(progress.visibility == VISIBLE && last?.state == "playing" && isShown)
    }

    /** A cached view another one hides stays attached: the position stops ticking, and is caught up on return. */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!isAttachedToWindow) return
        if (isShown) updateProgress(last)
        setTicking(progress.visibility == VISIBLE && last?.state == "playing" && isShown)
    }

    override fun onDetachedFromWindow() {
        setTicking(false)
        super.onDetachedFromWindow()
    }

    /**
     * The card's background: the color block, HA's placeholder picture on the right half, the artwork in a square
     * at the right fading into the color on its left, and grayscale for an off player.
     */
    private inner class MediaBackgroundView : View(ctx.context) {
        private var color = 0
        private var artwork: Bitmap? = null
        private var showPlaceholder = true
        private var gray = false
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val gradient = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shape = Path()
        private val bounds = RectF()
        private val dst = RectF()
        private val matrix = android.graphics.Matrix()
        private var artShader: BitmapShader? = null
        private var shaderBitmap: Bitmap? = null

        fun apply(color: Int, artwork: Bitmap?, showPlaceholder: Boolean, gray: Boolean) {
            if (color == this.color && artwork === this.artwork && showPlaceholder == this.showPlaceholder && gray == this.gray) return
            this.color = color
            this.artwork = artwork
            if (artwork == null) {
                // The old picture goes with its shader: a cached card mustn't keep it alive after the cache let go.
                artShader = null
                shaderBitmap = null
                artPaint.shader = null
            }
            this.showPlaceholder = showPlaceholder
            this.gray = gray
            bitmapPaint.colorFilter = if (gray) ColorMatrixColorFilter(GRAYSCALE) else null
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            // The card's padding keeps this view inside the border; ha-card's `overflow: hidden` rounds it with the
            // radius less the border, drawn as anti-aliased paths (a clip wouldn't be).
            val radius = (ctx.theme.cardBorderRadiusDp * ctx.dp - borderInsetPx()).coerceAtLeast(0f)
            shape.reset()
            bounds.set(0f, 0f, w, h)
            shape.addRoundRect(bounds, radius, radius, Path.Direction.CW)
            val c = if (gray) grayscale(color) else color
            fill.color = c
            canvas.drawPath(shape, fill)
            val ph = placeholder
            if (showPlaceholder && ph != null) {
                // `.no-img`: the right half, the picture at its own size (in CSS px), centered.
                val pw = ph.width * ctx.dp / PLACEHOLDER_DENSITY
                val phh = ph.height * ctx.dp / PLACEHOLDER_DENSITY
                val cx = w * 0.75f
                val cy = h / 2f
                dst.set(cx - pw / 2f, cy - phh / 2f, cx + pw / 2f, cy + phh / 2f)
                canvas.drawBitmap(ph, null, dst, bitmapPaint)
            }
            val art = artwork ?: return
            // `.image`: as wide as the card is tall (its offsetHeight, border included) and as tall as the inside,
            // at the right, `background-size: cover`.
            val side = h + 2 * borderInsetPx()
            dst.set(w - side, 0f, w, h)
            val scale = max(side / art.width, h / art.height)
            matrix.setScale(scale, scale)
            matrix.postTranslate(dst.centerX() - art.width * scale / 2f, dst.centerY() - art.height * scale / 2f)
            artShader = artShader?.takeIf { shaderBitmap === art } ?: BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shaderBitmap = art
            artShader!!.setLocalMatrix(matrix)
            artPaint.shader = artShader
            canvas.save()
            canvas.clipRect(dst)
            canvas.drawPath(shape, artPaint)
            // `.color-gradient`: the background color fading out to the right, over the picture.
            gradient.shader = LinearGradient(dst.left, 0f, dst.right, 0f, c, c and 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawPath(shape, gradient)
            canvas.restore()
        }
    }

    private fun borderInsetPx(): Int = (ctx.theme.cardBorderWidthDp * ctx.dp).toInt()

    /**
     * `ha-slider` with its thumb hidden: an 8 px rounded track, filled in the foreground color. With a [duration]
     * (a player that can seek) a drag or tap seeks where it's let go, in whole seconds (`step="1"`).
     */
    private inner class MediaProgressBar : View(ctx.context) {
        var fraction = 0f
            set(v) { if (field != v) { field = v; invalidate() } }
        var indicator = 0
            set(v) { if (field != v) { field = v; invalidate() } }

        /** Seconds the track spans when the player can seek; 0: it only shows the position. */
        var duration = 0.0
            set(v) {
                field = v
                // HA's ha-slider takes focus and keys; one that can't seek has nothing to move.
                isFocusable = v > 0.0
                if (v <= 0.0) access.cancel()
                // A key edit means a second, not a place on the bar: drawn on the new length (the release clamps it).
                editSeconds?.let { fraction = fractionOfSeconds(it) }
            }

        /** The second a key edit under way shows, kept in seconds so a new duration doesn't move it; null with none. */
        private var editSeconds: Int? = null

        private fun fractionOfSeconds(sec: Int): Float = if (duration > 0.0) (sec / duration).toFloat().coerceIn(0f, 1f) else 0f
        var dragging = false
            private set

        /** Under a finger or a held key: the bar shows where it will seek to, not HA's position. */
        val held: Boolean get() = dragging || access.editing

        /**
         * TalkBack and keys, as HA's `ha-slider` (0 to `media_duration`, step 1): a key seeks when it comes up, as a
         * drag does when it's let go; TalkBack's steps seek at once.
         */
        private val access = AdjustableAccess(
            min = { 0 }, max = { duration.toInt() }, step = { 1 },
            // Within the current length: a key edit held at 240 s reads 100 s once HA shortens the track to 100 s.
            value = { (editSeconds ?: (fraction * duration).roundToInt()).coerceIn(0, duration.toInt().coerceAtLeast(0)) },
            preview = { v ->
                editSeconds = v
                fraction = fractionOfSeconds(v)
                VitroAccess.valueChanged(this)
            },
            commit = { v ->
                editSeconds = null
                seek(v)
            },
            // A cancelled key or focus gone: HA's position again, nothing sent.
            restore = {
                editSeconds = null
                updateProgress(last)
            },
        )

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            if (duration > 0.0) access.onInitialize(info)
        }

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean =
            (if (duration > 0.0) access.perform(action, arguments) else null) ?: super.performAccessibilityAction(action, arguments)

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
            (duration > 0.0 && access.keyDown(keyCode)) || super.onKeyDown(keyCode, event)

        override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
            access.keyUp(event) || super.onKeyUp(keyCode, event)

        override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
            if (!gainFocus) access.cancel()
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (duration <= 0.0) {
                // No longer seekable mid-drag (HA dropped SEEK, or the duration): end the drag, send nothing, and
                // show the playback position again.
                if (dragging && (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)) {
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    updateProgress(last)
                    return true
                }
                return super.onTouchEvent(event)
            }
            val w = width.toFloat().coerceAtLeast(1f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
                    dragging = true
                    fraction = (event.x / w).coerceIn(0f, 1f)
                }
                MotionEvent.ACTION_UP -> {
                    fraction = (event.x / w).coerceIn(0f, 1f)
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    seek((fraction * duration).roundToInt())
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    updateProgress(last)
                }
            }
            return true
        }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clip = Path()
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val r = h / 2f
            rect.set(0f, 0f, w, h)
            clip.reset()
            clip.addRoundRect(rect, r, r, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            paint.color = TRACK
            canvas.drawRect(rect, paint)
            paint.color = indicator
            canvas.drawRect(0f, 0f, w * fraction, h, paint)
            canvas.restore()
        }
    }

    /** One of HA's media control buttons: its icon and the `media_player` service it calls. */
    data class Control(val icon: String, val action: String)

    companion object {
        const val PAUSE = 1
        const val SEEK = 2
        const val PREVIOUS_TRACK = 16
        const val NEXT_TRACK = 32
        const val TURN_ON = 128
        const val TURN_OFF = 256
        const val STOP = 4096
        const val PLAY = 16384
        const val BROWSE_MEDIA = 131072
        const val GROUPING = 524288

        private const val PLACEHOLDER_URL = "/static/images/card_media_player_bg.png"
        /** The placeholder is drawn at its own pixel size in CSS px (`background-size: initial`). */
        private const val PLACEHOLDER_DENSITY = 1f
        private const val TEXT_PRIMARY = Color.WHITE
        /** `--ha-slider-track-color: rgba(200, 200, 200, 0.5)`. */
        private val TRACK = Color.argb(128, 200, 200, 200)
        private val MAIN_ACTIONS = setOf("media_play", "media_play_pause", "media_pause", "media_stop")
        private val ACTION_LABELS = mapOf(
            "turn_on" to "Turn on", "turn_off" to "Turn off", "media_previous_track" to "Previous track",
            "media_play" to "Play", "media_pause" to "Pause", "media_stop" to "Stop", "media_next_track" to "Next track",
        )
        /** CSS `filter: grayscale(1)`'s matrix. */
        private val GRAYSCALE = ColorMatrix(floatArrayOf(
            0.2126f, 0.7152f, 0.0722f, 0f, 0f,
            0.2126f, 0.7152f, 0.0722f, 0f, 0f,
            0.2126f, 0.7152f, 0.0722f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
        private val EXTRACTOR = Executors.newSingleThreadExecutor { r -> Thread(r, "vitro-artwork-colors").apply { isDaemon = true } }

        internal fun grayscale(c: Int): Int {
            val v = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)).roundToInt().coerceIn(0, 255)
            return Color.argb(Color.alpha(c), v, v, v)
        }

        internal fun featuresOf(s: EntityState): Int = (s.attributes["supported_features"] as? Number)?.toInt() ?: 0

        /** HA's `stateActive` for a media player: anything but off, standby, unavailable and unknown. */
        internal fun isActive(s: EntityState): Boolean = s.state !in setOf("off", "standby", "unavailable", "unknown")

        internal fun imageOf(s: EntityState): String? =
            (s.attributes["entity_picture_local"] as? String)?.takeIf { it.isNotEmpty() } ?: s.entityPicture

        /** HA's `_showProgressBar` (before the narrow check): playing or paused, with a duration and a position. */
        internal fun showsProgress(s: EntityState): Boolean =
            (s.state == "playing" || s.state == "paused") && s.attributes.containsKey("media_duration") &&
                s.attributes.containsKey("media_position")

        /** HA's `computeMediaControls` (no extended controls). */
        internal fun mediaControls(s: EntityState): List<Control>? {
            val state = s.state
            if (state == "unavailable") return null
            val f = featuresOf(s)
            fun has(bit: Int) = f and bit != 0
            val assumed = s.attributes["assumed_state"] == true
            if (!isActive(s) && !assumed) return if (has(TURN_ON)) listOf(Control("mdi:power-standby", "turn_on")) else null
            val out = ArrayList<Control>()
            if (assumed && has(TURN_ON)) out += Control("mdi:power-on", "turn_on")
            if (has(TURN_OFF)) out += Control(if (assumed) "mdi:power-off" else "mdi:power-standby", "turn_off")
            val playingOrPaused = state == "playing" || state == "paused" || assumed
            if (playingOrPaused && has(PREVIOUS_TRACK)) out += Control("mdi:skip-previous", "media_previous_track")
            if (!assumed && state == "on" && (has(PLAY) || has(PAUSE))) out += Control("mdi:play-pause", "media_play")
            if ((assumed || state == "paused" || state == "idle") && has(PLAY)) out += Control("mdi:play", "media_play")
            if ((assumed || state == "playing") && has(PAUSE)) out += Control("mdi:pause", "media_pause")
            if ((assumed || state == "playing" || state == "paused") && has(STOP)) out += Control("mdi:stop", "media_stop")
            if (playingOrPaused && has(NEXT_TRACK)) out += Control("mdi:skip-next", "media_next_track")
            return out.ifEmpty { null }
        }

        /** HA's `removeSecondaryStopControl`: stop goes when play or pause is there (assumed-state players keep it). */
        internal fun removeSecondaryStop(s: EntityState, controls: List<Control>?): List<Control>? {
            if (controls == null || s.attributes["assumed_state"] == true ||
                controls.none { it.action == "media_play" || it.action == "media_pause" }
            ) return controls
            return controls.filter { it.action != "media_stop" }
        }

        /** HA's `computeMediaDescription`. */
        internal fun mediaDescription(s: EntityState): String {
            val a = s.attributes
            fun str(k: String) = (a[k] as? String).orEmpty()
            return when (a["media_content_type"]) {
                "music", "image" -> str("media_artist")
                "playlist" -> str("media_playlist").ifEmpty { str("media_artist") }
                "tvshow" -> buildString {
                    append(str("media_series_title"))
                    a["media_season"]?.takeIf { it.toString().isNotEmpty() }?.let { season ->
                        append(" S").append(season)
                        a["media_episode"]?.takeIf { it.toString().isNotEmpty() }?.let { append("E").append(it) }
                    }
                }
                "channel" -> str("media_channel")
                else -> str("app_name")
            }
        }

        /** HA's `cleanupMediaTitle`: no `?authSig=` tail, and a URL's last path segment. */
        internal fun cleanupMediaTitle(title: String?): String? {
            if (title.isNullOrEmpty()) return null
            val i = title.indexOf("?authSig=")
            var clean = if (i > 0) title.substring(0, i) else title
            if (clean.startsWith("http")) clean = runCatching { java.net.URLDecoder.decode(clean.substringAfterLast('/'), "UTF-8") }.getOrDefault(clean.substringAfterLast('/'))
            return clean
        }
    }
}

/**
 * HA's `extractColors` (node-vibrant 4 with HA's own generator): the artwork scaled to a fifth, near-white and
 * translucent pixels dropped, quantized to 16 colors with MMCQ; the background is the most common color, the
 * foreground the next most common one with a WCAG contrast over 4.5 against it (or a similar color that has it), else
 * white or black by the background's YIQ brightness.
 */
object ArtworkColors {
    /** What the card keeps for one artwork. */
    class Result(val url: String, val bitmap: Bitmap, val background: Int, val foreground: Int)

    private const val SIGBITS = 5
    private const val RSHIFT = 8 - SIGBITS
    private const val COLOR_COUNT = 16
    private const val FRACT_BY_POPULATIONS = 0.75
    private const val CONTRAST_RATIO = 4.5
    private const val COLOR_SIMILARITY_THRESHOLD = 150

    /** (background, foreground) for [bitmap]. */
    fun extract(bitmap: Bitmap): Pair<Int, Int> {
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val (small, _, _) = downscale(px, bitmap.width, bitmap.height)
        return fromPixels(small)
    }

    /**
     * node-vibrant's `quality: 5`: a canvas a fifth the size (its width and height truncated) with the picture drawn
     * scaled into it. Chrome draws that through mipmaps: halved with a 2x2 box until the next halving would go under
     * the target, then bilinear. Done here on the pixels, the same on every device. The background color this feeds
     * is robust to the resampling; the foreground can land on a neighboring swatch when two are nearly tied.
     */
    internal fun downscale(argb: IntArray, width: Int, height: Int): Triple<IntArray, Int, Int> {
        val tw = max(1, (width * 0.2).toInt())
        val th = max(1, (height * 0.2).toInt())
        var px = argb
        var w = width
        var h = height
        while (w / 2 >= tw && h / 2 >= th) {
            val nw = w / 2
            val nh = h / 2
            val out = IntArray(nw * nh)
            for (y in 0 until nh) for (x in 0 until nw) {
                val i = 2 * y * w + 2 * x
                out[y * nw + x] = average4(px[i], px[i + 1], px[i + w], px[i + w + 1])
            }
            px = out; w = nw; h = nh
        }
        if (w == tw && h == th) return Triple(px, tw, th)
        val out = IntArray(tw * th)
        for (y in 0 until th) {
            val sy = ((y + 0.5) * h / th - 0.5).coerceIn(0.0, (h - 1).toDouble())
            val y0 = sy.toInt(); val y1 = kotlin.math.min(y0 + 1, h - 1); val fy = sy - y0
            for (x in 0 until tw) {
                val sx = ((x + 0.5) * w / tw - 0.5).coerceIn(0.0, (w - 1).toDouble())
                val x0 = sx.toInt(); val x1 = kotlin.math.min(x0 + 1, w - 1); val fx = sx - x0
                out[y * tw + x] = bilinear(px[y0 * w + x0], px[y0 * w + x1], px[y1 * w + x0], px[y1 * w + x1], fx, fy)
            }
        }
        return Triple(out, tw, th)
    }

    private fun average4(a: Int, b: Int, c: Int, d: Int): Int {
        fun ch(shift: Int) = (((a ushr shift) and 0xFF) + ((b ushr shift) and 0xFF) + ((c ushr shift) and 0xFF) + ((d ushr shift) and 0xFF) + 2) / 4
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun bilinear(a: Int, b: Int, c: Int, d: Int, fx: Double, fy: Double): Int {
        fun ch(shift: Int): Int {
            val top = ((a ushr shift) and 0xFF) * (1 - fx) + ((b ushr shift) and 0xFF) * fx
            val bottom = ((c ushr shift) and 0xFF) * (1 - fx) + ((d ushr shift) and 0xFF) * fx
            return (top * (1 - fy) + bottom * fy + 0.5).toInt().coerceIn(0, 255)
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** The same from ARGB pixels: the part tests drive without a bitmap. */
    fun fromPixels(argb: IntArray): Pair<Int, Int> {
        val swatches = quantize(argb)
        if (swatches.isEmpty()) return Pair(Color.BLACK, Color.WHITE)
        return pick(swatches)
    }

    /** One MMCQ color: its RGB and how many pixels it stands for. */
    class Swatch(val rgb: IntArray, val population: Int)

    internal fun quantize(argb: IntArray): List<Swatch> {
        val hist = IntArray(1 shl (3 * SIGBITS))
        var rmin = Int.MAX_VALUE; var gmin = Int.MAX_VALUE; var bmin = Int.MAX_VALUE
        var rmax = 0; var gmax = 0; var bmax = 0
        var any = false
        for (p in argb) {
            val a = p ushr 24
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // node-vibrant's default filter.
            if (a < 125 || (r > 250 && g > 250 && b > 250)) continue
            val rq = r shr RSHIFT; val gq = g shr RSHIFT; val bq = b shr RSHIFT
            hist[index(rq, gq, bq)]++
            any = true
            if (rq > rmax) rmax = rq; if (rq < rmin) rmin = rq
            if (gq > gmax) gmax = gq; if (gq < gmin) gmin = gq
            if (bq > bmax) bmax = bq; if (bq < bmin) bmin = bq
        }
        if (!any) return emptyList()
        val pq = PQueue<VBox> { a, b -> a.count().compareTo(b.count()) }
        pq.push(VBox(rmin, rmax, gmin, gmax, bmin, bmax, hist))
        splitBoxes(pq, FRACT_BY_POPULATIONS * COLOR_COUNT)
        val pq2 = PQueue<VBox> { a, b -> (a.count().toLong() * a.volume()).compareTo(b.count().toLong() * b.volume()) }
        pq2.contents.addAll(pq.contents)
        splitBoxes(pq2, (COLOR_COUNT - pq2.size()).toDouble())
        val out = ArrayList<Swatch>()
        while (pq2.size() > 0) {
            val v = pq2.pop()!!
            out += Swatch(v.avg(), v.count())
        }
        return out
    }

    private fun splitBoxes(pq: PQueue<VBox>, target: Double) {
        var lastSize = pq.size()
        while (pq.size() < target) {
            val vbox = pq.pop() ?: break
            if (vbox.count() <= 0) break
            val parts = vbox.split()
            val v1 = parts.getOrNull(0) ?: break
            pq.push(v1)
            val v2 = parts.getOrNull(1)
            if (v2 != null && v2.count() > 0) pq.push(v2)
            if (pq.size() == lastSize) break else lastSize = pq.size()
        }
    }

    /** HA's generator: the most common color behind, a contrasting one in front. */
    internal fun pick(swatches: List<Swatch>): Pair<Int, Int> {
        val colors = swatches.sortedByDescending { it.population }
        val bg = colors[0]
        val ratios = HashMap<Int, Double>()
        fun approved(s: Swatch): Boolean = ratios.getOrPut(rgbInt(s.rgb)) { contrast(bg.rgb, s.rgb) } > CONTRAST_RATIO
        var fg: IntArray? = null
        loop@ for (i in 1 until colors.size) {
            if (approved(colors[i])) { fg = colors[i].rgb; break }
            val current = colors[i]
            for (j in i + 1 until colors.size) {
                val c = colors[j]
                val diff = abs(current.rgb[0] - c.rgb[0]) + abs(current.rgb[1] - c.rgb[1]) + abs(current.rgb[2] - c.rgb[2])
                if (diff > COLOR_SIMILARITY_THRESHOLD) continue
                if (approved(c)) { fg = c.rgb; break@loop }
            }
        }
        val foreground = fg?.let(::rgbInt) ?: if (yiq(bg.rgb) < 200) Color.WHITE else Color.BLACK
        return Pair(rgbInt(bg.rgb), foreground)
    }

    private fun rgbInt(rgb: IntArray) = Color.rgb(rgb[0], rgb[1], rgb[2])
    private fun yiq(rgb: IntArray) = (rgb[0] * 299 + rgb[1] * 587 + rgb[2] * 114) / 1000.0

    /** HA's `getRGBContrastRatio`: WCAG contrast, rounded to two decimals. */
    internal fun contrast(a: IntArray, b: IntArray): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val ratio = (max(la, lb) + 0.05) / (kotlin.math.min(la, lb) + 0.05)
        return Math.round((ratio + Math.ulp(1.0)) * 100) / 100.0
    }

    private fun luminance(rgb: IntArray): Double {
        fun lin(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin(rgb[0]) + 0.7152 * lin(rgb[1]) + 0.0722 * lin(rgb[2])
    }

    private fun index(r: Int, g: Int, b: Int) = (r shl (2 * SIGBITS)) + (g shl SIGBITS) + b

    /** node-vibrant's PQueue: sorted lazily, popped from the end (the largest). The sort is stable, like V8's. */
    private class PQueue<T>(private val comparator: Comparator<T>) {
        val contents = ArrayList<T>()
        private var sorted = false
        fun push(item: T) { contents += item; sorted = false }
        fun pop(): T? {
            if (!sorted) { contents.sortWith(comparator); sorted = true }
            return if (contents.isEmpty()) null else contents.removeAt(contents.size - 1)
        }
        fun size() = contents.size
    }

    /** node-vibrant's VBox, its median cut and its quirks included. */
    private class VBox(
        var r1: Int, var r2: Int, var g1: Int, var g2: Int, var b1: Int, var b2: Int,
        val hist: IntArray,
    ) {
        private var countCache = -1
        fun volume(): Int = (r2 - r1 + 1) * (g2 - g1 + 1) * (b2 - b1 + 1)

        fun count(): Int {
            if (countCache < 0) {
                var c = 0
                for (r in r1..r2) for (g in g1..g2) for (b in b1..b2) c += hist[index(r, g, b)]
                countCache = c
            }
            return countCache
        }

        fun clone() = VBox(r1, r2, g1, g2, b1, b2, hist)

        fun avg(): IntArray {
            var ntot = 0L
            val mult = 1 shl RSHIFT
            var rs = 0.0; var gs = 0.0; var bs = 0.0
            for (r in r1..r2) for (g in g1..g2) for (b in b1..b2) {
                val h = hist[index(r, g, b)]
                if (h == 0) continue
                ntot += h
                rs += h * (r + 0.5) * mult
                gs += h * (g + 0.5) * mult
                bs += h * (b + 0.5) * mult
            }
            return if (ntot > 0) intArrayOf((rs / ntot).toInt(), (gs / ntot).toInt(), (bs / ntot).toInt())
            else intArrayOf(mult * (r1 + r2 + 1) / 2, mult * (g1 + g2 + 1) / 2, mult * (b1 + b2 + 1) / 2)
        }

        fun split(): List<VBox> {
            val count = count()
            if (count == 0) return emptyList()
            if (count == 1) return listOf(clone())
            val rw = r2 - r1 + 1
            val gw = g2 - g1 + 1
            val bw = b2 - b1 + 1
            val maxw = maxOf(rw, gw, bw)
            val dim: Char
            val lo: Int
            val hi: Int
            val acc: IntArray
            var total = 0
            when (maxw) {
                rw -> {
                    dim = 'r'; lo = r1; hi = r2; acc = IntArray(r2 + 1)
                    for (r in r1..r2) { var s = 0; for (g in g1..g2) for (b in b1..b2) s += hist[index(r, g, b)]; total += s; acc[r] = total }
                }
                gw -> {
                    dim = 'g'; lo = g1; hi = g2; acc = IntArray(g2 + 1)
                    for (g in g1..g2) { var s = 0; for (r in r1..r2) for (b in b1..b2) s += hist[index(r, g, b)]; total += s; acc[g] = total }
                }
                else -> {
                    dim = 'b'; lo = b1; hi = b2; acc = IntArray(b2 + 1)
                    for (b in b1..b2) { var s = 0; for (r in r1..r2) for (g in g1..g2) s += hist[index(r, g, b)]; total += s; acc[b] = total }
                }
            }
            var splitPoint = -1
            val reverse = IntArray(acc.size)
            for (i in acc.indices) {
                val d = acc[i]
                if (d == 0) continue
                if (splitPoint < 0 && d > total / 2.0) splitPoint = i
                reverse[i] = total - d
            }
            val left = splitPoint - lo
            val right = hi - splitPoint
            var d2: Int
            if (left <= right) {
                d2 = kotlin.math.min(hi - 1, (splitPoint + right / 2.0).toInt())
                d2 = max(0, d2)
            } else {
                d2 = max(lo, (splitPoint - 1 - left / 2.0).toInt())
                d2 = kotlin.math.min(hi, d2)
            }
            // JS reads past the array as undefined (falsy): the loops stop there the same way.
            fun accAt(i: Int) = if (i in acc.indices) acc[i] else 0
            while (accAt(d2) == 0 && d2 < acc.size) d2++
            var c2 = if (d2 in reverse.indices) reverse[d2] else 0
            while (c2 == 0 && accAt(d2 - 1) != 0) { d2--; c2 = reverse[d2] }
            val v1 = clone()
            val v2 = clone()
            when (dim) {
                'r' -> { v1.r2 = d2; v2.r1 = d2 + 1 }
                'g' -> { v1.g2 = d2; v2.g1 = d2 + 1 }
                else -> { v1.b2 = d2; v2.b1 = d2 + 1 }
            }
            return listOf(v1, v2)
        }
    }
}
