package sh.edm.s1kiosk.notifications

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
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.glassworks.frame.Input
import dev.glassworks.notifications.ImageFetch
import dev.glassworks.notifications.NotificationImages
import dev.glassworks.notifications.NotificationStore.Entry
import dev.glassworks.notifications.NotificationsDefinition
import dev.glassworks.notifications.NotificationsHub
import dev.glassworks.notifications.Priority
import dev.glassworks.notifications.ViewButton
import dev.glassworks.notifications.ViewSize
import sh.edm.s1kiosk.setup.Backdrop
import sh.edm.s1kiosk.ui.Glass
import sh.edm.s1kiosk.ui.Glass.Companion.AMBER
import sh.edm.s1kiosk.ui.Glass.Companion.BD
import sh.edm.s1kiosk.ui.Glass.Companion.FA
import sh.edm.s1kiosk.ui.Glass.Companion.MU
import sh.edm.s1kiosk.ui.Glass.Companion.ROSE
import sh.edm.s1kiosk.ui.Glass.Companion.SKY
import sh.edm.s1kiosk.ui.Glass.Companion.TX
import sh.edm.s1kiosk.ui.Glass.Companion.VIOLET
import sh.edm.s1kiosk.ui.Inter
import sh.edm.s1kiosk.voice.OrbArt
import sh.edm.s1kiosk.voice.OrbBakes
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
    private val refold = Runnable { render() }
    private val pageTick = Runnable { renderPage(state.page) }

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
    }

    override fun onDetachedFromWindow() {
        // A picture that arrives later, or a tick, must not keep this (and its activity) alive.
        detached = true
        main.removeCallbacks(refold)
        main.removeCallbacks(pageTick)
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
        stageContent.addView(ScrollView(context).apply {
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(px(20f))
            addView(text, LayoutParams(-1, -2))
        }, LayoutParams(px(860f), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = px(STAGE_TEXT_TOP_DP)
            bottomMargin = px(STAGE_BUTTONS_BOTTOM_DP + STAGE_BUTTONS_DP + 16f)
        })
        stageContent.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            // A critical one's view opens from here, never by itself (#230): the stage is what can't be missed.
            val buttons = listOfNotNull(
                n.url?.takeIf { input == Input.TOUCH }?.let { OPEN_THE_VIEW to { hub.openView(e.id) } },
            ) + n.actions.map { a -> a.title to { tap(e, a.id, a.uri) } }
            buttons.forEachIndexed { i, (title, onTap) ->
                val b = if (i == 0) g.primary(title) else g.ghost(title)
                b.setOnClickListener { onTap() }
                addView(b, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = px(12f) })
            }
            addView(g.ghost("Dismiss").apply { setOnClickListener { hub.dismiss(e.id) } },
                LinearLayout.LayoutParams(-2, -2).apply { if (buttons.isNotEmpty()) marginStart = px(12f) })
        }, LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = px(STAGE_BUTTONS_BOTTOM_DP) })
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
                capture { b ->
                    if (!detached && capturing == p.id) {
                        capturing = null
                        openHalf(p, b)
                        render()
                    }
                }
            }
            else -> openHalf(p, null)
        }
    }

    private fun openHalf(p: Entry, picture: Bitmap?) {
        behind = picture
        shownHalf = true
        placePage()
        onPage?.invoke(p.notification.url)
    }

    private fun closeHalf() {
        shownHalf = false
        behind = null
        placed = null
        onPageArea?.invoke(null)
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
        if (shownHalf) post { if (shownHalf) render() }
    }

    private fun renderPage(e: Entry?) {
        main.removeCallbacks(pageTick)
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
        val left = e.expiresAt?.let { (it - now()).coerceAtLeast(0) }
        val waiting = state.waiting
        val button = ViewButton.of(waiting, if (half) ViewSize.HALF else ViewSize.FULL, input)
        val line = when {
            waiting > 0 -> listOfNotNull(left?.let { "Next in ${clock(it)}" }, "$waiting more waiting").joinToString("  ·  ")
            left != null -> if (half) "Closes in ${clock(left)}" else "Back to your dashboard in ${clock(left)}"
            half -> "Open until you close it"
            else -> "Your dashboard is one tap away"
        }
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
            addView(g.text(13f, MU).apply { text = line })
        }, if (half) LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(18f) } else LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(18f) })
        if (left != null && n.timeoutS != null) {
            addView(TimeLeft(context, left / (n.timeoutS * 1000f)), LinearLayout.LayoutParams(px(80f), px(3f)).apply { marginEnd = px(if (button != null) 18f else 0f) })
            if (!detached) main.postDelayed(pageTick, 1_000)
        }
        button?.let { b -> addView(smallPill(b.label, white = true).apply { setOnClickListener { hub.dismiss(e.id) } }) }
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
        if (left != null && n.timeoutS != null && !compact) {
            addView(TimeLeft(context, left / (n.timeoutS * 1000f)), LinearLayout.LayoutParams(-1, px(3f)).apply { topMargin = px(14f) })
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
        if (state.list.isEmpty()) rows.addView(g.text(15f, MU).apply { text = "Nothing on the panel." })
        state.list.forEachIndexed { i, e -> rows.addView(row(e), LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = px(10f) }) }
        s.addView(ScrollView(context).apply {
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(px(24f))
            addView(rows, LayoutParams(-1, -2))
            if (scrolled > 0) post { scrollTo(0, scrolled) }
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        s.addView(g.text(13f, FA).apply { text = "Kept until the panel restarts. Home Assistant can send them again." },
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
    private class TimeLeft(context: Context, private val left: Float) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val h = height.toFloat()
            p.shader = null; p.color = Color.argb(20, 255, 255, 255)
            c.drawRoundRect(0f, 0f, width.toFloat(), h, h / 2, h / 2, p)
            // The track's colour set the paint's alpha: back to opaque before the gradient.
            p.color = Color.WHITE
            p.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, SKY, VIOLET, Shader.TileMode.CLAMP)
            c.drawRoundRect(0f, 0f, width * left.coerceIn(0f, 1f), h, h / 2, h / 2, p)
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
        const val STAGE_BUTTONS_DP = 52f
        const val LONG_TITLE = 32
        val CARD = Color.parseColor("#16171c")
        /** The list's scrim, and the one behind a half view. */
        val SCRIM = Color.argb(110, 10, 11, 15)
        /** The half view's bar, at the dialog's foot. */
        const val FOOTER_DP = 72f
        const val SHEET_RADIUS_DP = 28f
        const val OPEN_THE_VIEW = "Open the view"

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
