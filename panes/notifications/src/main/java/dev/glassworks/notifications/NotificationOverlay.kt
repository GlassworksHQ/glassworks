package dev.glassworks.notifications

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.glassworks.engine.PageCapture
import dev.glassworks.frame.Input
import dev.glassworks.notifications.ImageFetch
import dev.glassworks.notifications.NotificationImages
import dev.glassworks.notifications.NotificationStore.Entry
import dev.glassworks.notifications.NotificationsDefinition
import dev.glassworks.notifications.NotificationsHub
import dev.glassworks.notifications.Priority
import dev.glassworks.notifications.ViewButton
import dev.glassworks.notifications.ViewSize
import dev.glassworks.setup.Backdrop
import dev.glassworks.ui.Glass
import dev.glassworks.ui.Glass.Companion.AMBER
import dev.glassworks.ui.Glass.Companion.BD
import dev.glassworks.ui.Glass.Companion.FA
import dev.glassworks.ui.Glass.Companion.MU
import dev.glassworks.ui.Glass.Companion.ROSE
import dev.glassworks.ui.Glass.Companion.SKY
import dev.glassworks.ui.Glass.Companion.TX
import dev.glassworks.ui.Glass.Companion.VIOLET
import dev.glassworks.ui.Inter
import dev.glassworks.assist.OrbArt
import dev.glassworks.assist.OrbBakes
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.ceil

/**
 * The notifications over the dashboard (#118, the approved design): cards at top right, the stack and
 * its "N notifications · Show all" pill, Do not disturb's pill, the list as a sheet from the right, the
 * critical alert on the timer-done stage, and a notification's view: full screen with its bar, or half
 * screen as a dialog over a still picture of the dashboard (#230).
 *
 * It draws what [NotificationsHub] holds ([update]) and sends taps back to it. Which cards are in view
 * is [Folds]'. Pictures come from [images] on a worker thread. Above the timers (12 dp) and under
 * the voice overlay (16 dp). Main thread only.
 */
class NotificationOverlay(
    context: Context,
    private val hub: NotificationsHub,
    private val images: NotificationImages?,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    /** The orb layers the critical alert shares with the timer-done stage. */
    orbs: OrbBakes = OrbBakes(),
) : FrameLayout(context) {

    /** Whether the screen is on: a card's fold time counts only then. */
    var screenOn: () -> Boolean = { true }

    /** A view opened ([url]) or closed (null). The activity moves the dashboard. */
    var onPage: ((url: String?) -> Unit)? = null

    /** Where the page goes: into a half view's dialog ([Rect], px), or back to full screen (null). */
    var onPageArea: ((area: Rect?) -> Unit)? = null

    /** Takes the dashboard's picture before a half view covers it; null, or a null picture: the scrim alone. */
    var capturePage: ((done: (Bitmap?) -> Unit) -> Unit)? = null

    /**
     * Whether the screen takes a tap ([dev.glassworks.frame.Frame.input]). Without ([Input.NONE]) a view has
     * no button, nothing outside a half view takes a tap, and the critical stage has no "Open the view".
     */
    var input: Input = Input.TOUCH

    private val g = Glass(context)
    private val dp = g.dp
    private fun px(v: Float) = g.px(v)
    private val main = Handler(Looper.getMainLooper())
    private val folds = Folds()
    private var state = NotificationsHub.State()
    private var shownPage: Long? = null
    /** The open view is drawn as a half dialog, and the page sits in it. */
    private var shownHalf = false
    /** A half view waiting for the dashboard's picture before it opens. */
    private var capturing: Long? = null
    /** The dashboard's still picture behind a half view. */
    private var behind: Bitmap? = null
    /** Where the page was last put for a half view. */
    private var placed: Rect? = null
    private val pictures = HashMap<Long, Picture>()
    private val loading = HashSet<Long>()
    private val worker: ExecutorService by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "s1-notify-img").apply { isDaemon = true } } }
    private var detached = false
    /** The window is on screen: false while the activity is stopped, as it is with the screen off. */
    private var windowShown = false
    private val refold = Runnable { render() }
    private val pageTick = Runnable { tickPage() }
    /** The open view's bar, for the tick: its line of text and its time left. */
    private var pageLine: TextView? = null
    private var pageTime: TimeLeft? = null

    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END; clipChildren = false }
    private val stack = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
    private val pill = pill()
    private val pageBar = FrameLayout(context)
    private val dialog = HalfDialog(context)
    private val footer = FrameLayout(context)
    private val stage = FrameLayout(context)
    /** The critical alert's animated orb: one, kept while the stage's text is rebuilt, so its motion runs on. Public for tests. */
    val criticalOrb = CriticalOrb(context, orbs)
    /** The stage's text and buttons, rebuilt on each change. */
    private val stageContent = FrameLayout(context)
    private val sheetLayer = FrameLayout(context)

    /** A picture loaded for an entry, or why not. */
    private class Picture(val bitmap: Bitmap?, val reason: String?)

    init {
        clipChildren = false
        elevation = 14 * dp
        // Under the cards: a card that arrives over a half view sits on top of it, as over the dashboard.
        addView(dialog, LayoutParams(-1, -1))
        addView(footer, LayoutParams(0, 0))
        addView(column, LayoutParams(px(CARD_DP), -2, Gravity.TOP or Gravity.END).apply { topMargin = px(MARGIN_DP); marginEnd = px(MARGIN_DP) })
        column.addView(stack, LinearLayout.LayoutParams(-1, -2))
        column.addView(pill, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(12f) })
        addView(pageBar, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = px(MARGIN_DP) })
        addView(stage, LayoutParams(-1, -1))
        stage.addView(View(context).apply { background = Backdrop(); alpha = 0.97f }, LayoutParams(-1, -1))
        // The timer-done stage's layout: the orb's glow box (300 dp) centred on 162 dp.
        stage.addView(criticalOrb, LayoutParams(px(OrbArt.GLOW_DP), px(OrbArt.GLOW_DP), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = px(162f - OrbArt.GLOW_DP / 2) })
        stage.addView(stageContent, LayoutParams(-1, -1))
        stage.visibility = View.GONE
        addView(sheetLayer, LayoutParams(-1, -1))
        render()
    }

    /** What to draw changed. */
    fun update(s: NotificationsHub.State) {
        state = s
        pictures.keys.retainAll(s.list.map { it.id }.toSet())
        render()
    }

    val listOpen: Boolean get() = sheetLayer.childCount > 0

    fun openList() {
        sheetLayer.removeAllViews()
        sheetLayer.addView(View(context).apply {
            setBackgroundColor(SCRIM)
            setOnClickListener { closeList() }
        }, LayoutParams(-1, -1))
        sheetLayer.addView(sheet(), LayoutParams(px(SHEET_DP), -1, Gravity.END))
        render()
    }

    fun closeList() {
        sheetLayer.removeAllViews()
        render()
    }

    /** Back: closes the list, then a full-screen page. False when there was nothing to close. */
    fun back(): Boolean {
        if (listOpen) { closeList(); return true }
        state.page?.let { hub.dismiss(it.id); return true }
        return false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        detached = false
        windowShown = windowVisibility == VISIBLE
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowShown = visibility == VISIBLE
        if (windowShown) armDeadline() else main.removeCallbacks(captureDeadline)
    }

    override fun onDetachedFromWindow() {
        // A picture that arrives later, or a tick, must not keep this (and its activity) alive.
        detached = true
        main.removeCallbacks(refold)
        main.removeCallbacks(pageTick)
        main.removeCallbacks(captureDeadline)
        capturing = null
        // Nothing draws it any more.
        behind?.recycle()
        behind = null
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ drawing

    private fun render() {
        main.removeCallbacks(refold)
        criticalOrb.screenOn = screenOn()
        // The critical alert is never folded, and covers the cards while it is up.
        val critical = state.cards.firstOrNull { it.notification.priority == Priority.CRITICAL }
        val view = folds.update(state.cards.filter { it !== critical }, screenOn(), now(), covered = critical != null)
        view.nextFoldAt?.takeIf { !detached }?.let { main.postDelayed(refold, (it - now()).coerceAtLeast(0) + 50) }
        if (state.page?.id != shownPage) switchPage(state.page)

        val cards = view.cards
        renderStack(cards.take(Folds.IN_VIEW))
        renderPill(cards.size)
        renderStage(critical)
        renderPage(state.page)
        if (listOpen) (sheetLayer.getChildAt(1) as? LinearLayout)?.let { fillSheet(it) }
        visibility = if (state.list.isEmpty() && state.page == null && !state.doNotDisturb && !listOpen) View.GONE else View.VISIBLE
    }

    private fun renderStack(cards: List<Entry>) {
        stack.removeAllViews()
        if (cards.isEmpty()) { stack.visibility = View.GONE; return }
        stack.visibility = View.VISIBLE
        val front = card(cards[0])
        // Two behind, peeking out below the front card, smaller and fainter.
        for ((i, e) in cards.drop(1).withIndex().reversed()) {
            val k = if (i == 0) 0.94f else 0.88f
            val back = card(e, compact = true).apply {
                alpha = if (i == 0) 0.85f else 0.6f
                scaleX = k; scaleY = k
                pivotX = px(CARD_DP / 2).toFloat(); pivotY = 0f
                translationY = px(if (i == 0) 11f else 22f).toFloat()
                isClickable = false
            }
            stack.addView(back, LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        stack.addView(front, LayoutParams(-1, -2))
    }

    private fun renderPill(inView: Int) {
        val held = state.list.count { it.held }
        // The open view and the ones waiting behind it have their own bar: they aren't more to show.
        val count = state.list.count { it.id != state.page?.id && !it.queued }
        val text = when {
            state.doNotDisturb -> "Do not disturb" to if (held > 0) "  ·  $held held" else ""
            count > 1 || count > inView -> "$count notification${if (count == 1) "" else "s"}" to "  ·  Show all"
            else -> null
        }
        if (text == null || listOpen) { pill.visibility = View.GONE; return }
        pill.visibility = View.VISIBLE
        (pill.getChildAt(0)).visibility = if (state.doNotDisturb) View.VISIBLE else View.GONE
        (pill.getChildAt(1) as TextView).text = text.first
        (pill.getChildAt(2) as TextView).text = text.second
        // Below the cards when there are some, at the top when there are none.
        (pill.layoutParams as LinearLayout.LayoutParams).topMargin = if (inView > 0) px(if (inView > 1) 34f else 12f) else 0
    }

    private fun renderStage(e: Entry?) {
        stageContent.removeAllViews()
        if (e == null) {
            criticalOrb.stop()
            stage.visibility = View.GONE
            return
        }
        stage.visibility = View.VISIBLE
        stage.isClickable = true // nothing behind it takes a tap until it's answered
        // The ripple and the pulse, until it's answered or dismissed; the orb pauses itself while the
        // screen is off or the window hidden.
        criticalOrb.start()
        val n = e.notification
        // The buttons sit at the bottom of the screen, so Dismiss is always there; the text gets the
        // room between the orb and them, and scrolls if HA sent more than fits on 480 dp.
        val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(g.label(if (n.tag != null) "Critical · ${n.tag.replace('_', ' ')}" else "Critical").apply { setTextColor(ROSE); gravity = Gravity.CENTER })
            val title = n.title.ifEmpty { n.message }
            addView(g.heading(if (title.length > LONG_TITLE) 32f else 44f).apply { this.text = title; gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(12f) })
            if (n.title.isNotEmpty() && n.message.isNotEmpty()) addView(g.text(17f, MU).apply { this.text = n.message; gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(8f) })
        }
        // The text above, the buttons below; on a narrow screen the buttons take more rows and the text the rest.
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(ScrollView(context).apply {
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(px(20f))
            addView(text, LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(minOf(px(860f), screenW() - 2 * px(STAGE_SIDE_DP)), 0, 1f).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = px(STAGE_TEXT_TOP_DP)
            bottomMargin = px(16f)
        })
        column.addView(ButtonRows(context, px(12f)).apply {
            // A critical one's view opens from here, never by itself (#230): the stage is what can't be missed.
            val buttons = listOfNotNull(
                n.url?.takeIf { input == Input.TOUCH }?.let { OPEN_THE_VIEW to { hub.openView(e.id) } },
            ) + n.actions.map { a -> a.title to { tap(e, a.id, a.uri) } }
            buttons.forEachIndexed { i, (title, onTap) ->
                addView((if (i == 0) g.primary(title) else g.ghost(title)).apply { setOnClickListener { onTap() } })
            }
            addView(g.ghost("Dismiss").apply { setOnClickListener { hub.dismiss(e.id) } })
        }, LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = px(STAGE_SIDE_DP)
            marginEnd = px(STAGE_SIDE_DP)
            bottomMargin = px(STAGE_BUTTONS_BOTTOM_DP)
        })
        stageContent.addView(column, LayoutParams(-1, -1))
    }

    /**
     * The stage's buttons in one centred row, as on the S1. When they don't fit (a narrow portrait screen, or many
     * actions), they go on as many centred rows as it takes, in the same order, so Dismiss keeps its natural width.
     */
    private class ButtonRows(context: Context, private val gap: Int) : ViewGroup(context) {
        private fun rows(max: Int): List<List<View>> {
            val rows = mutableListOf<MutableList<View>>()
            var x = 0
            for (c in (0 until childCount).map(::getChildAt).filter { it.visibility != GONE }) {
                if (rows.isEmpty() || x + gap + c.measuredWidth > max) { rows += mutableListOf(c); x = c.measuredWidth }
                else { rows.last() += c; x += gap + c.measuredWidth }
            }
            return rows
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val max = MeasureSpec.getSize(widthMeasureSpec)
            for (i in 0 until childCount) getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val rows = rows(max)
            val h = rows.sumOf { r -> r.maxOf { it.measuredHeight } } + gap * (rows.size - 1).coerceAtLeast(0)
            setMeasuredDimension(max, h)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            var y = 0
            for (row in rows(r - l)) {
                val rowH = row.maxOf { it.measuredHeight }
                var x = (r - l - (row.sumOf { it.measuredWidth } + gap * (row.size - 1))) / 2
                for (c in row) {
                    val top = y + (rowH - c.measuredHeight) / 2
                    c.layout(x, top, x + c.measuredWidth, top + c.measuredHeight)
                    x += c.measuredWidth + gap
                }
                y += rowH + gap
            }
        }
    }

    /**
     * The open view changed. A full one: the page goes to it. A half one opening over the dashboard: the
     * dashboard's picture first, while the page still shows it, then the page moves into the dialog and goes
     * to the view. From a full view to a half one there is no dashboard to take: the scrim alone.
     */
    private fun switchPage(p: Entry?) {
        val was = shownPage
        shownPage = p?.id
        capturing = null
        main.removeCallbacks(captureDeadline)
        val half = p?.notification?.size == ViewSize.HALF
        val capture = capturePage
        when {
            p == null || !half -> {
                if (shownHalf) closeHalf()
                onPage?.invoke(p?.notification?.url)
            }
            shownHalf -> onPage?.invoke(p.notification.url)
            was == null && capture != null -> {
                capturing = p.id
                armDeadline()
                capture { b ->
                    if (!detached && capturing == p.id) {
                        capturing = null
                        main.removeCallbacks(captureDeadline)
                        openHalf(p, b)
                        render()
                    } else {
                        b?.recycle()
                    }
                }
            }
            else -> openHalf(p, null)
        }
    }

    /**
     * A picture that is late isn't worth the wait: past the deadline the view opens over the dark backdrop. It counts
     * only while the window is shown. With the screen off GeckoView holds the capture until the screen is back on
     * (S1, 2026-11-16: 5.8 s, of which 0.45 s from the activity's resume to the picture; 58-86 ms with the screen on).
     */
    private fun armDeadline() {
        main.removeCallbacks(captureDeadline)
        if (capturing != null && windowShown) main.postDelayed(captureDeadline, CAPTURE_DEADLINE_MS)
    }

    private val captureDeadline = Runnable {
        val p = state.page?.takeIf { it.id == capturing } ?: return@Runnable
        capturing = null
        if (Log.isLoggable(PageCapture.TAG, Log.DEBUG)) Log.d(PageCapture.TAG, "capture: past $CAPTURE_DEADLINE_MS ms, the view opens without the picture")
        openHalf(p, null)
        render()
    }

    private fun openHalf(p: Entry, picture: Bitmap?) {
        dropPicture()
        behind = picture
        shownHalf = true
        placePage()
        onPage?.invoke(p.notification.url)
    }

    private fun closeHalf() {
        shownHalf = false
        dropPicture()
        placed = null
        onPageArea?.invoke(null)
    }

    /**
     * Lets the dashboard's picture go (4 MB on the S1). It is recycled after the next frame, which no longer draws it:
     * a frame already recorded with it must not meet a recycled bitmap.
     */
    private fun dropPicture() {
        val old = behind ?: return
        behind = null
        dialog.invalidate()
        if (isAttachedToWindow) postOnAnimation { main.post { old.recycle() } } else old.recycle()
    }

    /** Puts the page in the dialog, above its bar, when that moved (the first time, or the screen's size changed). */
    private fun placePage() {
        val area = dialogRect(screenW(), screenH()).apply { bottom -= px(FOOTER_DP) }
        if (area == placed) return
        placed = area
        onPageArea?.invoke(area)
    }

    /** The overlay's size, or the screen's before its first layout. */
    private fun screenW() = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
    private fun screenH() = height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The half dialog and the stage's text column follow the screen's size.
        if (shownHalf || stage.visibility == View.VISIBLE) post { if (shownHalf || stage.visibility == View.VISIBLE) render() }
    }

    private fun renderPage(e: Entry?) {
        main.removeCallbacks(pageTick)
        pageLine = null
        pageTime = null
        pageBar.removeAllViews()
        footer.removeAllViews()
        val half = e != null && shownHalf
        pageBar.visibility = if (e != null && !half && capturing == null) View.VISIBLE else View.GONE
        dialog.visibility = if (half) View.VISIBLE else View.GONE
        footer.visibility = dialog.visibility
        if (e == null || capturing != null) return
        if (!half) {
            pageBar.addView(bar(e, half = false))
            return
        }
        placePage()
        val side = side(screenW(), screenH())
        dialog.isClickable = input == Input.TOUCH
        footer.layoutParams = if (side) LayoutParams(screenW() / 2, px(FOOTER_DP), Gravity.BOTTOM or Gravity.END)
        else LayoutParams(-1, px(FOOTER_DP), Gravity.BOTTOM)
        footer.background = GradientDrawable().apply {
            // The dialog's outer corner, at the foot of a side panel.
            val r = SHEET_RADIUS_DP * dp
            cornerRadii = if (side) floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, r, r) else FloatArray(8)
            setColor(CARD)
        }
        footer.addView(View(context).apply { setBackgroundColor(Color.argb(30, 255, 255, 255)) }, LayoutParams(-1, px(1f).coerceAtLeast(1)))
        footer.addView(bar(e, half = true), LayoutParams(-1, -1))
    }

    /**
     * The open view's bar: its title, what comes next and when, the time left, and the one button
     * ([ViewButton]), which closes the view so the next one opens. A pill at the foot of a full view; the
     * dialog's own foot in a half one.
     */
    private fun bar(e: Entry, half: Boolean): View = LinearLayout(context).apply {
        val n = e.notification
        val button = ViewButton.of(state.waiting, if (half) ViewSize.HALF else ViewSize.FULL, input)
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (half) {
            setPadding(px(24f), 0, px(if (button != null) 16f else 24f), 0)
        } else {
            background = glassBg(999f)
            elevation = 10 * dp
            setPadding(px(20f), px(8f), px(if (button != null) 8f else 20f), px(8f))
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(g.text(15f, TX, Inter.MEDIUM).apply { text = n.title.ifEmpty { n.message }; setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
            addView(g.text(13f, MU).apply { text = barLine(e, half) }.also { pageLine = it })
        }, if (half) LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(18f) } else LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(18f) })
        val ends = e.expiresAt
        if (ends != null && n.timeoutS != null) {
            addView(TimeLeft(context, ends, n.timeoutS * 1000L).also { pageTime = it },
                LinearLayout.LayoutParams(px(80f), px(3f)).apply { marginEnd = px(if (button != null) 18f else 0f) })
            if (!detached) main.postDelayed(pageTick, 1_000)
        }
        button?.let { b -> addView(smallPill(b.label, white = true).apply { setOnClickListener { hub.dismiss(e.id) } }) }
    }

    /** The bar's line: what comes next and when. */
    private fun barLine(e: Entry, half: Boolean): String {
        val left = e.expiresAt?.let { (it - now()).coerceAtLeast(0) }
        val waiting = state.waiting
        return when {
            waiting > 0 -> listOfNotNull(left?.let { "Next in ${clock(it)}" }, "$waiting more waiting").joinToString("  ·  ")
            left != null -> if (half) "Closes in ${clock(left)}" else "Back to your dashboard in ${clock(left)}"
            half -> "Open until you close it"
            else -> "Your dashboard is one tap away"
        }
    }

    /** Once a second: the bar's text, and its time left when it steps rather than slides. Nothing is rebuilt. */
    private fun tickPage() {
        val e = state.page ?: return
        pageLine?.text = barLine(e, shownHalf)
        pageTime?.step()
        if (!detached && pageTime != null) main.postDelayed(pageTick, 1_000)
    }

    /**
     * Behind a half view: the dashboard's still picture under the list's scrim, everywhere but the dialog, whose
     * outer corners it rounds. A tap on it closes the view, as the button does; every tap inside the dialog goes
     * through to the page.
     */
    private inner class HalfDialog(context: Context) : View(context) {
        private val path = Path()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val scale = Matrix()

        override fun onDraw(c: Canvas) {
            val r = SHEET_RADIUS_DP * dp
            val radii = if (side(width, height)) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) else floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            path.reset()
            path.fillType = Path.FillType.EVEN_ODD
            path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            path.addRoundRect(RectF(dialogRect(width, height)), radii, Path.Direction.CW)
            val b = behind
            if (b != null) {
                paint.color = Color.WHITE
                paint.shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    scale.setScale(width / b.width.toFloat(), height / b.height.toFloat())
                    setLocalMatrix(scale)
                }
            } else {
                paint.shader = null
                paint.color = Glass.BG
            }
            c.drawPath(path, paint)
            paint.shader = null
            paint.color = SCRIM
            c.drawPath(path, paint)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (input != Input.TOUCH || dialogRect(width, height).contains(e.x.toInt(), e.y.toInt())) return false
            if (e.actionMasked == MotionEvent.ACTION_UP) state.page?.let { hub.dismiss(it.id) }
            return true
        }
    }

    // ------------------------------------------------------------------ the card

    private fun card(e: Entry, compact: Boolean = false): LinearLayout = LinearLayout(context).apply {
        val n = e.notification
        orientation = LinearLayout.VERTICAL
        background = glassBg(24f)
        elevation = 12 * dp
        isClickable = true // a tap on the card doesn't reach the dashboard under it
        setPadding(px(20f), px(16f), px(20f), px(if (n.timeoutS != null) 12f else 18f))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val important = n.priority == Priority.HIGH
            val label = (if (important) "Important · " else "") + NotificationsDefinition.ago(now() - e.postedAt).replaceFirstChar { it.uppercase() }
            addView(g.label(label).apply { setTextColor(if (important) AMBER else FA) }, LinearLayout.LayoutParams(0, -2, 1f))
            if (n.priority == Priority.LOW) addView(g.text(12f, FA).apply { text = "Silent" }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(12f) })
            addView(g.text(22f, MU, Inter.LIGHT).apply {
                text = "×"
                contentDescription = "Close"
                setPadding(px(10f), 0, 0, 0)
                setOnClickListener { hub.dismiss(e.id) }
            })
        })
        if (n.title.isNotEmpty()) addView(g.text(19f, TX, Inter.MEDIUM).apply { text = n.title; maxLines = 2; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
        if (n.message.isNotEmpty()) addView(g.text(15.5f, MU).apply { text = n.message; setLineSpacing(0f, 1.2f); maxLines = 3; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(5f) })
        if (n.image != null && !compact) addView(picture(e), LinearLayout.LayoutParams(-1, px(190f)).apply { topMargin = px(12f) })
        if (n.actions.isNotEmpty() && !compact) addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            n.actions.forEachIndexed { i, a ->
                addView(smallPill(a.title).apply { setOnClickListener { tap(e, a.id, a.uri) } },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = px(8f) })
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14f) })
        val left = e.expiresAt?.let { (it - now()).coerceAtLeast(0) }
        val ends = e.expiresAt
        if (ends != null && n.timeoutS != null && !compact) {
            addView(TimeLeft(context, ends, n.timeoutS * 1000L), LinearLayout.LayoutParams(-1, px(3f)).apply { topMargin = px(14f) })
        }
    }

    private fun tap(e: Entry, actionId: String, uri: String?) {
        hub.tapAction(e.id, actionId)
        uri?.let { hub.openPage(e.notification.title.ifEmpty { "Home Assistant" }, it, e.notification.size) }
    }

    private fun picture(e: Entry): View {
        val p = pictures[e.id]
        if (p?.bitmap != null) return Rounded(context, p.bitmap)
        if (p == null) load(e)
        return g.text(14f, MU).apply {
            text = p?.reason?.let { "No picture: $it" } ?: ""
            gravity = Gravity.CENTER
            setPadding(px(16f), 0, px(16f), 0)
            background = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(Glass.S1); setStroke(px(1f).coerceAtLeast(1), BD) }
        }
    }

    private fun load(e: Entry) {
        val url = e.notification.image ?: return
        val src = images ?: return
        if (!loading.add(e.id)) return
        val width = px(CARD_DP - 40f)
        worker.execute {
            val p = when (val r = src.fetch(url)) {
                is ImageFetch.Ok -> decode(r.bytes, width)?.let { Picture(it, null) } ?: Picture(null, "the picture couldn't be read")
                is ImageFetch.Failed -> Picture(null, r.reason)
            }
            main.post {
                loading.remove(e.id)
                if (!detached && state.list.any { it.id == e.id }) { pictures[e.id] = p; render() }
            }
        }
    }

    // ------------------------------------------------------------------ the list

    private fun sheet() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        background = GradientDrawable().apply {
            cornerRadii = floatArrayOf(28 * dp, 28 * dp, 0f, 0f, 0f, 0f, 28 * dp, 28 * dp)
            setColor(Color.argb(250, 0x12, 0x13, 0x18))
            setStroke(px(1f).coerceAtLeast(1), Color.argb(30, 255, 255, 255))
        }
        elevation = 16 * dp
        setPadding(px(24f), px(22f), px(20f), px(16f))
        fillSheet(this)
    }

    private fun fillSheet(s: LinearLayout) {
        // The list is rebuilt on every change: keep where the person had scrolled to.
        val scrolled = (0 until s.childCount).map { s.getChildAt(it) }.filterIsInstance<ScrollView>().firstOrNull()?.scrollY ?: 0
        s.removeAllViews()
        s.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(g.heading(28f).apply { text = "Notifications" }, LinearLayout.LayoutParams(0, -2, 1f))
            if (state.list.isNotEmpty()) addView(g.plain("Clear all").apply { setPadding(px(10f), px(8f), px(10f), px(8f)); setOnClickListener { hub.clearAll() } })
        })
        s.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(10f), 0, px(12f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(g.text(16.5f, TX).apply { text = "Do not disturb" })
                addView(g.text(14f, MU).apply { text = if (state.doNotDisturb) "On · critical ones still ring" else "Off" },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(g.toggle(state.doNotDisturb) { on -> onDoNotDisturb?.invoke(on) })
        })
        s.addView(View(context).apply { setBackgroundColor(BD) }, LinearLayout.LayoutParams(-1, px(1f).coerceAtLeast(1)).apply { bottomMargin = px(12f) })
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (state.list.isEmpty()) rows.addView(g.text(15f, MU).apply { text = NotificationsDefinition.NOTHING })
        state.list.forEachIndexed { i, e -> rows.addView(row(e), LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = px(10f) }) }
        s.addView(ScrollView(context).apply {
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(px(24f))
            addView(rows, LayoutParams(-1, -2))
            if (scrolled > 0) post { scrollTo(0, scrolled) }
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        s.addView(g.text(13f, FA).apply { text = NotificationsDefinition.KEPT },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
    }

    /** Do not disturb changed in the list. The activity saves it (the pane's setting). */
    var onDoNotDisturb: ((Boolean) -> Unit)? = null

    private fun row(e: Entry) = LinearLayout(context).apply {
        val n = e.notification
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = g.surface(18f)
        setPadding(px(16f), px(12f), px(14f), px(12f))
        setOnClickListener {
            folds.pin(e.id)
            hub.show(e.id)
            closeList()
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(g.text(16f, TX, Inter.MEDIUM).apply { text = n.title.ifEmpty { n.message }; setSingleLine(); ellipsize = TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(g.text(13f, FA).apply { text = NotificationsDefinition.ago(now() - e.postedAt) })
            })
            if (n.title.isNotEmpty() && n.message.isNotEmpty()) addView(g.text(14.5f, MU).apply { text = n.message; setSingleLine(); ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f) })
            val critical = n.priority == Priority.CRITICAL
            if (e.held || n.url != null || critical) addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                if (critical) addView(g.label("Critical").apply { setTextColor(ROSE) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(12f) })
                if (e.held && n.url == null) addView(g.label(if (state.doNotDisturb) "Held · Do not disturb" else "Held"), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(12f) })
                if (n.url != null) addView(g.text(13.5f, TX, Inter.MEDIUM).apply { text = "Open the page ›" })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        pictures[e.id]?.bitmap?.let { addView(Rounded(context, it), LinearLayout.LayoutParams(px(84f), px(56f)).apply { marginStart = px(12f) }) }
    }

    // ------------------------------------------------------------------ pieces

    private fun glassBg(r: Float) = GradientDrawable().apply {
        cornerRadius = r * dp
        // Opaque night glass: it reads the same on a light dashboard and a dark one.
        setColor(CARD)
        setStroke(px(1f).coerceAtLeast(1), Color.argb(30, 255, 255, 255))
    }

    private fun pill() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = glassBg(999f)
        elevation = 8 * dp
        setPadding(px(14f), px(9f), px(16f), px(9f))
        setOnClickListener { openList() }
        addView(Moon(context), LinearLayout.LayoutParams(px(16f), px(16f)).apply { marginEnd = px(9f) })
        addView(g.text(14.5f, TX, Inter.MEDIUM))
        addView(g.text(14.5f, MU))
    }

    private fun smallPill(label: String, white: Boolean = false) = g.text(15f, if (white) Glass.BG else TX, Inter.MEDIUM).apply {
        text = label
        gravity = Gravity.CENTER
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.END
        isClickable = true
        background = GradientDrawable().apply {
            cornerRadius = 999f
            setColor(if (white) Glass.PRIMARY else Glass.S2)
            if (!white) setStroke(px(1f).coerceAtLeast(1), BD)
        }
        setPadding(px(16f), px(10f), px(16f), px(10f))
    }

    /** The time left: a thin line in the brand's gradient (not a status colour). */
    /**
     * The time left until [endsAt], of [totalMs], as a bar that empties. It slides: one linear animation over the
     * time left, which sets the fill's scaleX in whole pixels of its width. A scaleX that doesn't change doesn't
     * invalidate, so the window redraws only when the bar moves a pixel (the S1's 120 px pill over 90 s: about one
     * frame in 0.75 s), never a draw on every vsync; no onDraw or layout either way. Android has no public way to run
     * it on the RenderThread (API 34's ViewPropertyAnimator has no RenderThread backend; RenderNodeAnimator is hidden).
     * It runs only while the window is shown (not on a sleeping panel) and stops when the bar goes; a new bar
     * starts from where the time really is. With the system's animations off (duration scale 0, which is also what
     * Remove animations sets) there is no animation: the fill steps once a second with the text ([step]).
     */
    private inner class TimeLeft(context: Context, private val endsAt: Long, private val totalMs: Long) : FrameLayout(context) {
        private val fill = View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(SKY, VIOLET)).apply { cornerRadius = 999f }
            pivotX = 0f
        }
        private var animator: ValueAnimator? = null

        init {
            background = GradientDrawable().apply { setColor(Color.argb(20, 255, 255, 255)); cornerRadius = 999f }
            addView(fill, LayoutParams(-1, -1))
            step()
        }

        private fun leftMs() = (endsAt - now()).coerceAtLeast(0)

        /**
         * The system's animator duration scale: Developer options, and 0 with Remove animations. From the setting
         * the system copies into ValueAnimator, because ValueAnimator.getDurationScale is API 33 and
         * areAnimatorsEnabled API 26 (the S1 is API 29; the legacy build API 23).
         */
        private fun animatorScale(): Float =
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

        /** [f] of the fill, rounded up to its next whole pixel: it reaches 0 only when the time does. */
        private fun wholePixels(f: Float): Float {
            val w = fill.width
            return if (w > 0) ceil(f * w) / w else f
        }

        /** The fill where the time is, unless the animation is moving it. */
        fun step() {
            if (animator == null) fill.scaleX = (leftMs() / totalMs.toFloat()).coerceIn(0f, 1f)
        }

        private fun slide() {
            stop()
            step()
            val left = leftMs()
            val scale = animatorScale()
            if (!windowShown || !isAttachedToWindow || left == 0L || scale <= 0f) return
            animator = ValueAnimator.ofFloat(fill.scaleX, 0f).apply {
                // The system scales every animator's duration; a countdown keeps real time.
                duration = (left / scale).toLong()
                interpolator = LinearInterpolator()
                addUpdateListener { a -> fill.scaleX = wholePixels(a.animatedValue as Float) }
                // Ended by itself: at the end of the time, or at once where the system turned animations off past
                // the setting (Battery Saver on Android 10 keeps the setting at 1). The bar steps from where it is.
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) {
                        if (a !== animator) return
                        animator = null
                        step()
                    }
                })
                start()
            }
        }

        private fun stop() {
            val a = animator
            animator = null
            a?.cancel()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            slide()
        }

        override fun onDetachedFromWindow() {
            stop()
            super.onDetachedFromWindow()
        }

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            if (visibility == VISIBLE) slide() else stop()
        }
    }

    /** A picture with the card's rounded corners, cropped to fill. */
    private class Rounded(context: Context, private val bmp: Bitmap) : View(context) {
        private val clip = Path()
        private val p = Paint(Paint.FILTER_BITMAP_FLAG)
        private val dst = RectF()
        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            clip.reset(); clip.addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 14 * d, 14 * d, Path.Direction.CW)
            val s = maxOf(width / bmp.width.toFloat(), height / bmp.height.toFloat())
            val w = bmp.width * s; val h = bmp.height * s
            dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            c.save(); c.clipPath(clip); c.drawBitmap(bmp, null, dst, p); c.restore()
        }
    }

    /** Do not disturb's moon, in the brand's gradient. */
    private class Moon(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val r = width / 2f
            p.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), SKY, VIOLET, Shader.TileMode.CLAMP)
            val moon = Path().apply { addCircle(r, r, r, Path.Direction.CW) }
            moon.op(Path().apply { addCircle(r * 1.55f, r * 0.6f, r * 0.85f, Path.Direction.CW) }, Path.Op.DIFFERENCE)
            c.drawPath(moon, p)
        }
    }

    companion object {
        const val CARD_DP = 400f
        const val SHEET_DP = 460f
        const val MARGIN_DP = 16f
        /** The critical alert's layout: the text starts under the orb, the buttons end 28 dp above the bottom. */
        const val STAGE_TEXT_TOP_DP = 236f
        const val STAGE_BUTTONS_BOTTOM_DP = 28f
        /** The stage's text and buttons keep this far from a narrow screen's sides. */
        const val STAGE_SIDE_DP = 24f
        const val LONG_TITLE = 32
        val CARD = Color.parseColor("#16171c")
        /** The list's scrim, and the one behind a half view. */
        val SCRIM = Color.argb(110, 10, 11, 15)
        /** The half view's bar, at the dialog's foot. */
        const val FOOTER_DP = 72f
        const val SHEET_RADIUS_DP = 28f
        const val OPEN_THE_VIEW = "Open the view"
        /** How long a half view waits for the dashboard's picture, with the screen on, before it opens without it. */
        const val CAPTURE_DEADLINE_MS = 750L

        /** The half view's dialog on a [w] x [h] screen: the long side's half, on the right of a landscape one, at the bottom of a portrait one. */
        fun dialogRect(w: Int, h: Int): Rect = if (side(w, h)) Rect(w / 2, 0, w, h) else Rect(0, h / 2, w, h)

        private fun side(w: Int, h: Int) = w >= h

        fun clock(ms: Long): String {
            val s = (ms + 999) / 1000
            return "%d:%02d".format(s / 60, s % 60)
        }

        /** Decodes [bytes] at no more than about [width] px wide, so a camera's full frame doesn't cost the S1's RAM. */
        fun decode(bytes: ByteArray, width: Int): Bitmap? {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth <= 0) return null
            var sample = 1
            while (o.outWidth / (sample * 2) >= width) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
