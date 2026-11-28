package dev.glassworks.setup

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.glassworks.pane.PaneChoice
import dev.glassworks.pane.SetupCard
import dev.glassworks.pane.SetupHost
import dev.glassworks.pane.SetupScreen
import dev.glassworks.pane.SetupStep
import dev.glassworks.auth.OAuth
import dev.glassworks.ui.Glass
import dev.glassworks.ui.GroupedRail
import dev.glassworks.ui.Glass.Companion.AMBER
import dev.glassworks.ui.Glass.Companion.FA
import dev.glassworks.ui.Glass.Companion.GREEN
import dev.glassworks.ui.Glass.Companion.MU
import dev.glassworks.ui.Glass.Companion.ROSE
import dev.glassworks.ui.Glass.Companion.TX
import dev.glassworks.ui.Inter
import dev.glassworks.frame.Frame
import dev.glassworks.frame.Input
import dev.glassworks.frame.friendlyName
import dev.glassworks.glassworks
import dev.glassworks.ui.QrCode
import dev.glassworks.ui.StackView
import dev.glassworks.ui.StackView.Look

/**
 * The first-boot setup screen (docs/onboarding.md, step 4), as drawn in #63: a native, full-screen
 * overlay over the page. Left, the steps (Wi-Fi, Home Assistant, Panes) and the stack, whose
 * plates set one per step. Right, a glass card for the current step: one idea, a short heading, one
 * white button. Done is the lit stack on its own, the same picture as boot.
 *
 * The rail groups the steps (#63 round 2): Wi-Fi, Home Assistant (sign in, add it), Panes (choose which,
 * then each step of a pane that is on, drawn by the pane through [SetupCard]), Done. A step that is done
 * shows so, with Continue; the rail and Continue move between steps, also in a preview. Panes only asks
 * which panes are on: one card per pane that offers one ([PaneChoice]). The Wi-Fi step shows Improv (Wi-Fi over Bluetooth) inline, Allow included, so
 * no separate ImprovOverlay window covers this screen. Laid out for 960×480 dp (1440×720 px at density
 * 1.5); on a screen taller than wide (the Mirror's 1080×1920) the same card stacks into one column. Main thread only.
 */
class SetupOverlay(
    context: Context,
    /**
     * The device the screen runs on. Its name ([friendlyName], what Home Assistant shows) goes on the HA step, and its
     * [Frame.input] decides whether anything takes a tap. A test builds the screen for a device from that device's frame.
     */
    frame: Frame,
    /**
     * The panes on the Panes step, each with its steps (GlassworksRuntime.setupPanes: only usable ones).
     * None: no Panes group.
     */
    private val setupPanes: List<SetupPane> = emptyList(),
    /** The device's own steps (#422's Silent updates): each a row of its own after Panes (Glassworks.deviceSetupSteps). */
    private val ownSteps: List<SetupStep> = emptyList(),
    /**
     * The Panes step's answer, shared with the setup channel's `panes.set` (Glassworks.panesStep): Kiln's answer is this
     * screen's too. Over [setupPanes].
     */
    private val panesStep: PanesStep = PanesStep(setupPanes),
) : FrameLayout(context) {

    private val haName = frame.friendlyName()

    private val choices = setupPanes.map { it.choice }

    data class Model(
        /** The current step's id (SetupFlow). */
        val step: String,
        /** The ids of the steps that are done. */
        val done: Set<String>,
        val wifi: WifiSetupStatus.Snapshot,
        /** Home Assistant's URL, null while it hasn't been found. */
        val haUrl: String?,
        /** How it was found (`mdns`, `esphome`, `manual`, or "" for a saved dashboard URL). */
        val haSource: String,
        val panelIp: String?,
        val signedIn: Boolean,
        val preview: Boolean = false,
        /** Improv's status block for the Wi-Fi step (SetupDecision.improv). */
        val improv: WifiStep.ImprovLine = WifiStep.improv(ImprovFacts()),
        /** A Wi-Fi join sent from the computer (web installer), if any. */
        val installer: InstallerWifi? = null,
        /** The setup hotspot's part of the Wi-Fi step (#197); null where the app has none, and in a preview. */
        val hotspot: dev.glassworks.setup.HotspotStep.Card? = null,
        /** Signed in for an address Home Assistant left (#467): Sign in says why it asks again. */
        val signinMoved: Boolean = false,
        /** [SetupDecision.haChecking]: Add it says it checks whether Home Assistant knows the panel already (#665). */
        val haChecking: Boolean = false,
        /** [SetupDecision.signinChecking]: Sign in says it connects until the page shows whether one is needed. */
        val signinChecking: Boolean = false,
    )

    var onChooseNetwork: (() -> Unit)? = null
    var onSkipHa: (() -> Unit)? = null
    /** "Sign in on this panel instead". */
    var onUsePanel: (() -> Unit)? = null
    /** Manual URL submitted; call [manualResult] with the outcome. */
    var onManualUrl: ((String) -> Unit)? = null
    var onClosePreview: (() -> Unit)? = null
    /** An inline Improv button (Allow / Not now / Stop / Use Bluetooth / Try again). */
    var onImprovAction: ((WifiStep.Action) -> Unit)? = null
    /** The setup hotspot's button (Set up from a phone / Allow / Close it / Try again). */
    var onHotspotAction: ((dev.glassworks.setup.HotspotStep.Action) -> Unit)? = null
    /** Panes applied with Continue. [restart]: a card that only takes effect at start changed. */
    var onPanesDone: (() -> Unit)? = null
    /** A pane's step was answered on the screen ([SetupHost.next]). */
    var onStepDone: (() -> Unit)? = null

    /**
     * Whether the screen takes a tap ([Frame.input]). Without ([Input.NONE]) every step shows where to set the panel up
     * from a computer (the setup page over the network, #34), and no card has a button. Sign in shows its phone code
     * instead once Home Assistant has added the panel, and once signed in, Add it waits for Home Assistant (#484).
     */
    val input: Input = frame.input

    /** The frame has its own presence sensor ([dev.glassworks.screen.PresenceRelay.ownSensor]). The frame's, unless a test sets it. */
    var presenceSensor: Boolean = runCatching { context.glassworks.presence.ownSensor(context) }.getOrDefault(true)

    /** Turns the screen on ([dev.glassworks.screen.ScreenPolicy.wake]). The runtime's, unless a test sets it. */
    var wakeScreen: (String) -> Unit = { reason -> context.glassworks.screen.wake(reason) }

    /**
     * Home Assistant hasn't added the panel yet (its step isn't done), so it can't wake the screen, or Home Assistant
     * moved and only a person scanning the Sign in step's code can sign the panel in again (#467). A panel that is set
     * up and only lost Wi-Fi or Home Assistant has neither: the setup screen neither wakes it nor keeps it on (#484).
     */
    val needsHomeAssistant: Boolean get() = model?.let { SetupFlow.HA !in it.done || it.signinMoved } == true

    /** True while visible (including the Done fade-out). */
    var isShowing = false
        private set

    var model: Model? = null
        private set

    private val g = Glass(context)
    private fun px(v: Float) = g.px(v)
    private var finishing: Runnable? = null
    private var qrFor: String? = null
    private val main = Handler(Looper.getMainLooper())

    /** Each pane's steps, by id, and which pane (its index in [choices]) they belong to; the device's own, [OWN]. */
    private val stepsById = (setupPanes.flatMapIndexed { i, p -> p.steps.map { it.id to (i to it) } } + ownSteps.map { it.id to (OWN to it) }).toMap()

    private val ownIds = ownSteps.map { it.id }

    /**
     * The step on screen when it isn't the current one: one picked on the rail or reached with Continue
     * on a step that is done (always reachable in a preview).
     */
    private var viewing: String? = null

    /** Every step in order, with the steps of the panes that are on ([wanted]). */
    private fun flow(): List<String> = SetupFlow.ids(
        choices.isNotEmpty(),
        stepsById.filter { (_, v) -> v.first != OWN && (model?.preview == true || wanted(v.first)) }.keys.toList(),
        ownIds,
    )

    // ---- left: the brand, the steps, the stack
    private val railRows = GroupedRail(context).apply { onTap = { id -> open(id) } }
    private val railStack = StackView(context)
    private val previewChip = g.plain("Preview · Close").apply { textSize = 14f; setOnClickListener { onClosePreview?.invoke() } }

    /** Pushes the rail's stack art to its foot. */
    private val railSpacer = View(context)

    private val railFoot = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        addView(railStack, LinearLayout.LayoutParams(px(78f), px(76f)).apply { marginStart = px(6f); bottomMargin = px(12f) })
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(previewChip)
    }

    /**
     * The brand, the steps and the stack art at the foot. In landscape the art steps aside when the rows need its room:
     * on the S1, Silent updates is a seventh row while Sign in or a pane's step is open (#422).
     */
    private val rail = object : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            if (portrait || compact) return
            val above = (0 until childCount).map(::getChildAt).filter { it !== railSpacer && it !== railFoot }
                .sumOf { it.measuredHeight + (it.layoutParams as LayoutParams).let { lp -> lp.topMargin + lp.bottomMargin } }
            val fits = above + px(RAIL_ART_DP) <= MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom
            val want = if (fits) View.VISIBLE else View.GONE
            if (railStack.visibility != want) {
                railStack.visibility = want
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(4f), 0, 0, 0)
            addView(StackView(context), LinearLayout.LayoutParams(px(24f), px(23f)).apply { marginEnd = px(14f) })
            addView(g.text(19f, TX, Inter.REGULAR).apply { text = "Glassworks" })
        })
        addView(railRows, railRowsLp)
        addView(railSpacer, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(railFoot, LinearLayout.LayoutParams(-1, -2))
    }

    /** Where the rail's rows sit under the brand: 33 dp down on the S1, closer on a phone. */
    private val railRowsLp get() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(if (compact) 14f else 33f) }

    /** A phone held upright: the rows in a strip that scrolls sideways, under the brand ([arrange]). */
    private val railStrip = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength(px(24f))
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    // ---- right: the current step's card
    private val cardLabel = g.label()
    private val cardTitle = g.heading(45f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val cardBody = g.text(16f, MU).apply { setLineSpacing(0f, 1.3f) }
    /** The step's own view: the Bluetooth tile, the waiting line, the URL field, the pane cards. */
    private val slot = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val slotLp = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(SECTION_GAP_DP) }
    /** Pushes the buttons to the card's foot; a step whose view fills the room ([setContent]) takes its place. */
    private val stretch = View(context)
    private val stretchLp = LinearLayout.LayoutParams(-1, 0, 1f)
    private val status = g.text(15f, MU).apply { setLineSpacing(0f, 1.2f) }
    private val foot = g.text(15f, FA)
    private val primary = g.primary("").apply { setPadding(px(30f), px(15f), px(30f), px(15f)) }
    private val ghost = g.ghost("").apply { setPadding(px(28f), px(15f), px(28f), px(15f)) }
    private val plainLeft = g.plain("").apply { setPadding(0, px(14f), px(12f), px(14f)) }
    private val plain = g.plain("")

    /** The quietest action at the foot: Bluetooth's Not now (or Stop) on the Wi-Fi step (#468). */
    private val quiet = g.text(15f, FA, Inter.MEDIUM).apply {
        gravity = Gravity.CENTER
        setPadding(px(12f), px(14f), px(12f), px(14f))
        isClickable = true
        isFocusable = true
    }

    // ---- the Wi-Fi step's other ways (#468): folded behind one link, which opens them in its place
    private val waysLink = g.plain("Other ways to set up ›").apply { setSingleLine(); setPadding(0, px(14f), px(12f), px(14f)) }
    /** Each lines up with the card's text, where the link was; the gap to the next is its own end padding. */
    private val wayButtons = List(2) { g.plain("").apply { setSingleLine(); setPadding(0, px(14f), px(24f), px(14f)) } }
    private val waysRow = object : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // Side by side, unless the room is too narrow for them (a small phone): then one under the other.
            val room = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            val need = shownChildren().sumOf { it.measure(free, free); it.measuredWidth }
            orientation = if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && need > room) VERTICAL else HORIZONTAL
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }.apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(waysLink)
        wayButtons.forEach { addView(it) }
    }

    /** The other ways are open: their link was tapped on this step. */
    private var waysOpen = false

    /**
     * The card's buttons in a row: the quiet ones at the left, the primary at the right. When they don't all fit across
     * the card (a phone), one under the other across its whole width instead: the foot's words, then the primary, then
     * the rest. A row that fits is laid out as before, untouched.
     */
    private val buttons = object : LinearLayout(context) {
        private var stacked = false
        private val stackOrder get() = listOf(foot, primary, ghost, plain, plainLeft, quiet).filter { it.visibility != View.GONE }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            stacked = false
            if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
                val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                val need = stackOrder.sumOf { v ->
                    v.measure(free, free)
                    v.measuredWidth + (v.layoutParams as LayoutParams).let { it.marginStart + it.marginEnd }
                }
                stacked = need > MeasureSpec.getSize(widthMeasureSpec)
            }
            if (!stacked) { super.onMeasure(widthMeasureSpec, heightMeasureSpec); return }
            val w = MeasureSpec.getSize(widthMeasureSpec)
            var h = 0
            stackOrder.forEachIndexed { i, v ->
                v.measure(exactly(w), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                h += v.measuredHeight + if (i > 0) px(STACK_GAP_DP) else 0
            }
            setMeasuredDimension(w, h)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            if (!stacked) { super.onLayout(changed, l, t, r, b); return }
            var y = 0
            stackOrder.forEach { v ->
                v.layout(0, y, r - l, y + v.measuredHeight)
                y += v.measuredHeight + px(STACK_GAP_DP)
            }
        }
    }.apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ghost, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(22f) })
        addView(plainLeft, LinearLayout.LayoutParams(-2, -2))
        addView(foot, LinearLayout.LayoutParams(-2, -2))
        addView(View(context), LinearLayout.LayoutParams(0, 0, 1f)) // no height: without buttons the foot takes no room
        addView(plain, LinearLayout.LayoutParams(-2, -2))
        addView(quiet, LinearLayout.LayoutParams(-2, -2))
        addView(primary, LinearLayout.LayoutParams(-2, -2))
    }

    /**
     * The card's foot: its [buttons], and on the Wi-Fi step its other ways at the left (#468). They sit beside the
     * buttons when the widest of them fits in the room the buttons leave, else on a line of their own above them (a
     * phone, or a card the hotspot's button fills), so every size keeps the whole foot.
     */
    private val buttonRow = object : ViewGroup(context) {
        private var above = false

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            if (waysRow.visibility == View.GONE) {
                buttons.measure(exactly(w), free)
                setMeasuredDimension(w, buttons.measuredHeight)
                return
            }
            buttons.measure(free, free)
            val room = w - buttons.measuredWidth
            above = waysRow.shownChildren().maxOf { it.measure(free, free); it.measuredWidth } > room
            waysRow.measure(MeasureSpec.makeMeasureSpec(if (above) w else room, MeasureSpec.AT_MOST), free)
            buttons.measure(exactly(if (above) w else buttons.measuredWidth), free)
            val h = if (above) waysRow.measuredHeight + buttons.measuredHeight else maxOf(waysRow.measuredHeight, buttons.measuredHeight)
            setMeasuredDimension(w, h)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l
            val h = b - t
            if (waysRow.visibility == View.GONE) { buttons.layout(0, 0, w, buttons.measuredHeight); return }
            if (above) {
                waysRow.layout(0, 0, waysRow.measuredWidth, waysRow.measuredHeight)
                buttons.layout(0, waysRow.measuredHeight, w, h)
            } else {
                val wy = (h - waysRow.measuredHeight) / 2
                val by = (h - buttons.measuredHeight) / 2
                waysRow.layout(0, wy, waysRow.measuredWidth, wy + waysRow.measuredHeight)
                buttons.layout(w - buttons.measuredWidth, by, w, by + buttons.measuredHeight)
            }
        }
    }.apply {
        addView(waysRow)
        addView(buttons)
    }

    private fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)

    private fun ViewGroup.shownChildren() = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != View.GONE }
    private val qrImage = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    /** Square, [QR_DP] at most: smaller only where the room under a portrait card's text is shorter. */
    private val qrTile = object : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // A side it may take any of (the card's scroll on a phone) doesn't limit it.
            fun room(spec: Int) = if (MeasureSpec.getMode(spec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(spec)
            val side = MeasureSpec.makeMeasureSpec(minOf(px(QR_DP), room(widthMeasureSpec), room(heightMeasureSpec)), MeasureSpec.EXACTLY)
            super.onMeasure(side, side)
        }
    }.apply {
        background = GradientDrawable().apply { cornerRadius = 20 * g.dp; setColor(Color.WHITE) }
        setPadding(px(14f), px(14f), px(14f), px(14f))
        addView(qrImage, LayoutParams(-1, -1))
    }
    private val qrGlow = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = 125 * g.dp
            colors = intArrayOf(Color.argb(70, 56, 189, 248), Color.TRANSPARENT)
        }
        addView(qrTile, LayoutParams(-1, -1, Gravity.CENTER))
        visibility = View.GONE
    }

    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(cardLabel)
        addView(cardTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14f) })
        addView(cardBody, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16f) })
        addView(slot, slotLp)
        addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
        addView(stretch, stretchLp)
    }
    // Landscape's places (the S1, tablets); portrait swaps them in stack().
    private val columnBeside = LinearLayout.LayoutParams(0, -1, 1f)
    /**
     * 6 dp more gap before the code (12 dp to its tile), so text that runs the column's full width doesn't touch it
     * (#422). Any more and the Mirror's "Scan to set it up." wraps. Gone with the code.
     */
    private val qrBeside = LinearLayout.LayoutParams(px(238f), -1).apply { marginStart = px(6f) }
    private val railBeside = LinearLayout.LayoutParams(px(227f), -1).apply { marginEnd = px(26f) }
    private val cardBeside = LinearLayout.LayoutParams(0, -1, 1f)
    /** The text and the QR code beside it (under it in portrait). */
    private val textAndQr = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(column, columnBeside)
        addView(qrGlow, qrBeside)
    }
    /**
     * The buttons are the card's foot, across its whole width (#422, #403): Continue stays in the corner with a QR
     * code beside the text too, not at the text's edge in the middle of the card.
     */
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = g.surface(34f)
        setPadding(px(41f), px(36f), px(28f), px(30f))
        addView(textAndQr, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(buttonRow, LinearLayout.LayoutParams(-1, -2))
    }
    /**
     * On a phone the card's text and the step's view scroll above its buttons, so a long step never loses its end
     * ([arrange]). It fills the room it has, so a short step's buttons still sit at the card's foot.
     */
    private val cardScroll = ScrollView(context).apply {
        isFillViewport = true
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(px(24f))
        overScrollMode = View.OVER_SCROLL_NEVER
    }
    private val wizard = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(px(49f), px(44f), px(45f), px(40f))
        addView(rail, railBeside)
        addView(card, cardBeside)
    }

    /** The screen stands taller than wide (the Mirror's 1080×1920): one column. */
    private var portrait = false

    /** In [portrait], the QR code is on and sits under the card's text, filling the room left. */
    private var qrBelow = false

    /**
     * The screen is a phone's: its short side under [COMPACT_DP] (the S1's is 480 dp, the Mirror's 720 dp). From the
     * screen's own size, so a keyboard that takes half of it doesn't change the layout under the person typing.
     */
    private var compact = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val c = resources.configuration
        val small = minOf(c.screenWidthDp, c.screenHeightDp) in 1 until COMPACT_DP
        val upright = MeasureSpec.getSize(heightMeasureSpec) > MeasureSpec.getSize(widthMeasureSpec)
        if (small != compact) { compact = small; arrange(upright) }
        stack(upright)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /**
     * A phone's screen, [compact], or a panel's: the paddings, the rail's size, where the rail's rows sit, and whether
     * the card's text scrolls. [stack] then places them for the screen's way up.
     */
    private fun arrange(upright: Boolean) {
        val k = compact
        if (k) wizard.setPadding(px(16f), px(if (upright) 18f else 14f), px(16f), px(16f)) else wizard.setPadding(px(49f), px(44f), px(45f), px(40f))
        if (k) card.setPadding(px(22f), px(20f), px(20f), px(16f)) else card.setPadding(px(41f), px(36f), px(28f), px(30f))
        card.background = g.surface(if (k) 26f else 34f)
        cardTitle.maxLines = if (k) 3 else 2
        cardTitle.textSize = titleSp(titleBig)
        railRows.rowDp = if (k) 32f else 39f
        railRows.childDp = if (k) 28f else 34f
        railRows.gapDp = if (k) 2f else 4f
        railBeside.width = px(if (k) 176f else 227f)
        railBeside.marginEnd = px(if (k) 16f else 26f)
        qrBeside.width = px(if (k) 180f else 238f)
        // The card's text in its scroll on a phone, straight in the card on a panel.
        val inScroll = textAndQr.parent === cardScroll
        if (k != inScroll) {
            (textAndQr.parent as? ViewGroup)?.removeView(textAndQr)
            card.removeView(cardScroll)
            if (k) {
                cardScroll.addView(textAndQr, FrameLayout.LayoutParams(-1, -2))
                card.addView(cardScroll, 0, LinearLayout.LayoutParams(-1, 0, 1f))
            } else card.addView(textAndQr, 0, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        // The Panes list scrolls with the card on a phone, rather than inside it.
        model?.let { setContent(slot.getChildAt(0)) }
        railStrip(false)
        stripped = null
        portrait = !upright // stack() lays out again
    }

    /** The rail's rows are in [railStrip] (a phone held upright), or in the rail's column. */
    private var stripped: Boolean? = null

    private fun railStrip(on: Boolean) {
        if (stripped == on) return
        stripped = on
        (railRows.parent as? ViewGroup)?.removeView(railRows)
        rail.removeView(railStrip)
        railRows.horizontal = on
        if (on) {
            railStrip.addView(railRows, FrameLayout.LayoutParams(-2, -2))
            rail.addView(railStrip, 1, railRowsLp)
        } else rail.addView(railRows, 1, railRowsLp)
        model?.let(::bindRail)
    }

    /** The heading on the card is a core step's (Wi-Fi, Home Assistant, Panes), not a pane's own step's. */
    private var titleBig = true

    /** A step's heading: [big] for Wi-Fi, Home Assistant and Panes, a little smaller for a pane's own step. */
    private fun titleSp(big: Boolean) = when {
        compact && big -> 30f
        compact -> 28f
        big -> 45f
        else -> 40f
    }

    /**
     * The same card, stacked when [on] (#412): the rail over the card, and in the card the text over the QR code. The
     * rail's stack art stays out, so the QR code keeps its full size. Landscape keeps its own layout params, untouched.
     */
    private fun stack(on: Boolean) {
        val below = on && qrGlow.visibility == View.VISIBLE
        if (on == portrait && below == qrBelow) return
        portrait = on
        qrBelow = below
        // A phone held upright: the rows across the top, under the brand.
        railStrip(on && compact)
        wizard.orientation = if (on) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        textAndQr.orientation = wizard.orientation
        rail.layoutParams = if (on) LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(26f) } else railBeside
        card.layoutParams = if (on) LinearLayout.LayoutParams(-1, 0, 1f) else cardBeside
        column.layoutParams = when {
            !on -> columnBeside
            below -> LinearLayout.LayoutParams(-1, -2)
            else -> LinearLayout.LayoutParams(-1, 0, 1f)
        }
        // A gap between the text and the code, and between the code and the buttons (#422's portrait tablet). On a
        // phone the card scrolls, so the code takes its own size rather than the room left.
        qrGlow.layoutParams = if (on) LinearLayout.LayoutParams(-1, if (compact) -2 else 0, if (compact) 0f else 1f).apply { topMargin = px(TIGHT_GAP_DP); bottomMargin = px(TIGHT_GAP_DP) } else qrBeside
        // A plain View measured to wrap takes all the room it may: the spacers go, or the rail fills the screen and
        // the text the card.
        railSpacer.visibility = if (on) View.GONE else View.VISIBLE
        stretch.visibility = if (below) View.GONE else View.VISIBLE
        // On a phone the rail has no room for the art either way up.
        railStack.visibility = if (on || compact) View.GONE else View.VISIBLE
    }

    // ---- Done: the lit stack on its own
    private val donePreviewChip = g.plain("Preview · Close").apply { textSize = 14f; setOnClickListener { onClosePreview?.invoke() } }
    private val doneView = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(StackView(context), LinearLayout.LayoutParams(px(108f), px(103f)))
        addView(g.heading(44f).apply { text = "You're all set" }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(40f) })
        addView(g.text(17f, MU).apply { text = "Opening your dashboard…" }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(18f) })
        addView(donePreviewChip, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(28f) })
        visibility = View.GONE
    }

    // ---- the steps' own views
    private val improvTile = ImprovTile()
    private val waitingText = g.text(17f, MU)
    private val waiting = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(Pulse(), LinearLayout.LayoutParams(px(44f), px(44f)).apply { marginEnd = px(19f) })
        addView(waitingText, LinearLayout.LayoutParams(0, -2, 1f))
    }
    /** The same spinner while the setup hotspot starts (#460), with its own line. */
    private val spotWaitingText = g.text(17f, MU)
    private val spotWaiting = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(Pulse(), LinearLayout.LayoutParams(px(44f), px(44f)).apply { marginEnd = px(19f) })
        addView(spotWaitingText, LinearLayout.LayoutParams(0, -2, 1f))
    }
    private val urlField = EditText(context).apply {
        textSize = 18f
        typeface = Inter.typeface(context, Inter.REGULAR, 18f)
        setTextColor(TX)
        setHintTextColor(FA)
        hint = "http://homeassistant.local:8123"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_FULLSCREEN
        setSingleLine()
        background = g.surface(16f, Glass.S2)
        setPadding(px(18f), px(14f), px(18f), px(14f))
        setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { submit(); true } else false
        }
    }
    private val panes = PanesView()

    init {
        background = Backdrop()
        isClickable = true // swallow touches meant for the page underneath
        isFocusable = true
        elevation = 20 * g.dp
        addView(wizard, LayoutParams(-1, -1))
        addView(doneView, LayoutParams(-1, -1))
        visibility = View.GONE
    }

    /** Shows (or updates) the screen for [m]. */
    fun render(m: Model) {
        cancelFinish()
        val before = shown()
        if (model?.step != m.step && !m.preview) viewing = null // progress: back to the current step
        model = m
        bind(m, shown() != before)
        reveal()
    }

    /** The step on screen. */
    private fun shown(): String? = viewing ?: model?.step

    /** The rail or Continue picked [id]: show it, if it can be shown. */
    private fun open(id: String) {
        val m = model ?: return
        val f = flow()
        // Live, only steps that are done and the current one; a preview shows them all.
        if (!m.preview && id != m.step && id !in m.done) return
        if (id !in f) return
        viewing = if (id == m.step) null else id
        bind(m, true)
    }

    /**
     * Continue on a step that is done: the next one, or back to the current step when the next one can't open yet
     * (Done, or Panes after an Add it that Home Assistant did before the sign-in).
     */
    private fun advance() {
        val m = model ?: return
        val f = flow()
        val next = f.getOrNull(f.indexOf(shown()) + 1) ?: return
        if (!m.preview && next != m.step && next !in m.done) { viewing = null; bind(m, true); return }
        open(next)
    }

    /**
     * Shows Done, calls [loaded] (load the dashboard behind it), then fades out after [holdMs]. A
     * preview (`--es setup_step done`) holds Done until it is closed, like every other step.
     */
    fun finish(from: Model? = model, holdMs: Long = 1_800, loaded: () -> Unit) {
        val m = (from ?: return).copy(step = SetupFlow.DONE, done = flow().toSet())
        cancelFinish()
        viewing = null
        model = m
        bind(m, true)
        reveal()
        loaded()
        if (m.preview) return
        val r = Runnable { finishing = null; hide() }
        finishing = r
        postDelayed(r, holdMs)
    }

    fun manualResult(ok: Boolean, message: String) {
        primary.isEnabled = true
        primary.alpha = 1f
        showStatus(message, if (ok) GREEN else ROSE)
    }

    /**
     * Panes is applying, or its last Continue failed: the step stays up whatever the rules say. A pane's
     * step can look done mid-way (the radio stores its mode before the helper answers), and a failure
     * must stay where it can be retried.
     */
    val holdsPanes: Boolean get() = isShowing && shown() == SetupFlow.PANES && (panes.applying || panes.problem != null)

    /** Continue on the Panes step, in this process (SetupInputs.panesChosen). */
    val panesChosen: Boolean get() = panes.chosen

    /** The step [id] of a pane belongs to a pane that is on: as chosen on the Panes step, else as it runs. */
    fun wanted(id: String): Boolean = stepsById[id]?.let { wanted(it.first) } ?: true

    private fun wanted(pane: Int): Boolean = pane == OWN || panes.wanted(pane)

    /** Every couple of seconds while the app runs: the panes' steps follow their state. */
    fun tick() {
        screens.forEach { (id, screen) -> screen.tick(current = isShowing && shown() == id) }
    }

    /** Improv identify: pulse the Bluetooth tile a few times. */
    fun flashImprov() {
        val v = improvTile.view
        if (!isShowing || v.parent == null) return
        v.animate().cancel()
        android.animation.ObjectAnimator.ofFloat(v, "scaleX", 1f, 1.03f).apply {
            duration = 220; repeatMode = ValueAnimator.REVERSE; repeatCount = 7
            addUpdateListener { v.scaleY = v.scaleX }
            start()
        }
    }

    fun hide() {
        cancelFinish()
        panes.forgetPreview()
        viewing = null // opened again, it starts on its own step, not the one last looked at
        model = null // so the next render counts as a step change (Add it by hand folds), also when not shown
        manualShown = false
        waysOpen = false // opened again, the Wi-Fi step's other ways start folded
        if (!isShowing) return
        isShowing = false
        hideKeyboard()
        animate().cancel()
        animate().alpha(0f).setDuration(300).withEndAction { if (!isShowing) visibility = View.GONE }.start()
    }

    private fun cancelFinish() {
        finishing?.let { removeCallbacks(it) }
        finishing = null
    }

    // ------------------------------------------------------------------ binding

    private fun bind(m: Model, stepChanged: Boolean) {
        if (stepChanged) manualShown = false // "Add it by hand" folds again on another step, or when opened again
        val done = shown() == SetupFlow.DONE
        doneView.visibility = if (done) View.VISIBLE else View.GONE
        donePreviewChip.visibility = if (m.preview) View.VISIBLE else View.GONE
        wizard.visibility = if (done) View.GONE else View.VISIBLE
        if (done) { hideKeyboard(); return }
        bindRail(m)
        bindCard(m, stepChanged)
    }

    private fun bindRail(m: Model) {
        val flow = flow()
        val shown = shown()
        railRows.bind(
            flow.map { id ->
                GroupedRail.Row(
                    id, title(id), SetupFlow.group(id, ownIds),
                    mark = when { id == shown || id == m.step -> GroupedRail.Mark.CURRENT; id in m.done -> GroupedRail.Mark.DONE; else -> GroupedRail.Mark.PENDING },
                    // Without touch (Input.NONE) nothing on this screen takes a tap, the rail included.
                    enabled = input == Input.TOUCH && (m.preview || id == m.step || id in m.done),
                )
            },
            GROUPS,
            shown,
        )
        // A phone's strip brings the step on screen into view.
        if (stripped == true) railStrip.post {
            val v = railRows.current ?: return@post
            val x = v.left - (railStrip.width - v.width) / 2
            railStrip.scrollTo(x.coerceAtLeast(0), 0)
        }
        // The plates set one per step: none on the first, all three on the last.
        val current = flow.indexOf(m.step).coerceAtLeast(0)
        val set = if (flow.size < 2) 3 else 3 * current / (flow.size - 1)
        railStack.looks = List(3) { plate -> if (2 - plate < set) Look.OUTLINE else Look.GHOST }
        previewChip.visibility = if (m.preview) View.VISIBLE else View.GONE
    }

    /** The step the card was last drawn for: another one clears what that step left (its status line). */
    private var cardStep: String? = null

    private fun bindCard(m: Model, stepChanged: Boolean) {
        // Each refresh draws the card again, and the reset in drawCard hides its buttons for a moment. Android drops a
        // key's focus from a view that goes GONE, so a button that is still shown after the draw gets it back (#468).
        val focused = findFocus()
        drawCard(m, stepChanged)
        if (focused != null && !focused.isFocused && focused.isShown) focused.requestFocus()
    }

    private fun drawCard(m: Model, stepChanged: Boolean) {
        qrGlow.visibility = View.GONE
        cardBody.visibility = View.VISIBLE
        titleBig = true
        cardTitle.textSize = titleSp(true)
        slotLp.topMargin = px(SECTION_GAP_DP)
        primary.visibility = View.GONE; ghost.visibility = View.GONE
        plain.visibility = View.GONE; plainLeft.visibility = View.GONE; foot.visibility = View.GONE
        quiet.visibility = View.GONE; waysRow.visibility = View.GONE
        primary.isEnabled = true; primary.alpha = 1f
        listOf(primary, ghost, plain, plainLeft, quiet).forEach { it.setOnClickListener(null) }
        val shown = shown()
        // Also when the caller saw no change: a pane step's Continue clears `viewing` before the screen draws
        // the current step again, so that draw can't tell (the tablet run on 28c157d: the camera's
        // "Android allows the camera." stayed on Sign in).
        if (stepChanged || shown != cardStep) status.visibility = View.GONE
        // The other ways fold when the card shows another step, not when the rail opens the one already shown.
        if (shown != cardStep) waysOpen = false
        cardStep = shown
        if (shown == null) return
        // A step that is done says so, also as a preview's current step (reopened on a set-up panel).
        val done = shown in m.done
        cardLabel.text = SetupFlow.group(shown, ownIds)?.let { grp -> "${GROUPS.getValue(grp)} · ${title(shown)}" } ?: title(shown)
        // Moments after the app started (it restarts once it finds Home Assistant): no sign-in asked before the page
        // shows whether one is needed, so a session it has goes on to the dashboard with nothing in between.
        if (shown == SetupFlow.SIGNIN && !done && m.signinChecking) {
            cardTitle.text = SIGNIN_CHECKING
            cardBody.text = "Checking whether the panel needs to sign in."
            setContent(null)
            hideKeyboard()
            return
        }
        if (input == Input.NONE) {
            // Nothing here takes a tap: the steps happen on a computer, or on a phone for Sign in. Before Home Assistant
            // added the panel, Sign in is the setup page's, which also finds Home Assistant.
            if (shown == SetupFlow.SIGNIN && (done || SetupFlow.HA in m.done || m.signinMoved)) {
                bindSignin(m, done)
                // Neither "on this panel instead" nor a done step's Continue: the next step comes by itself.
                plainLeft.visibility = View.GONE
                primary.visibility = View.GONE
                setContent(null)
            } else setContent(bindFromComputer(m))
            hideKeyboard()
            return
        }
        // The URL field never goes GONE and back while shown: a view that goes GONE (even for one
        // frame) loses focus, and this runs on every discovery/Wi-Fi refresh, i.e. while the user types.
        var content: View? = null
        when (shown) {
            SetupFlow.WIFI -> content = bindWifi(m, done)
            // Both Home Assistant steps need its address first.
            SetupFlow.SIGNIN, SetupFlow.HA -> content = when {
                !done && m.haUrl == null -> bindFind()
                shown == SetupFlow.HA -> bindHa(m, done)
                else -> { bindSignin(m, done); null }
            }
            SetupFlow.PANES -> content = bindPanes(m, stepChanged)
            else -> {
                // A pane's step fills the card itself (SetupCard.content), slot included, and sets its status
                // on every draw: it starts from none (SetupCard's promise). Its view stays attached when the step
                // sets the same one again, so a redraw every couple of seconds never cuts a drag short (#524).
                status.visibility = View.GONE
                stepContentSet = false
                stepsById[shown]?.let { (_, step) -> bindPaneStep(step, stepChanged) }
                if (!stepContentSet) setContent(null)
                hideKeyboard()
                return
            }
        }
        setContent(content)
        if (content !== urlField) hideKeyboard()
    }

    /**
     * The step's own view under the heading. The Panes step's list fills the room down to the buttons
     * and scrolls there, a section gap above them, so Continue stays put however many panes there are.
     */
    private fun setContent(v: View?) {
        // On a phone the whole card scrolls ([cardScroll]): the list takes its own height in it.
        val fills = v != null && v === panes.view && !compact
        fill(fills)
        if (v != null && v === panes.view) (v.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            val h = if (fills) -1 else -2
            if (lp.height != h) { lp.height = h; v.layoutParams = lp }
        }
        if (v == null) { if (slot.childCount > 0) slot.removeAllViews(); slot.visibility = View.GONE; return }
        slot.visibility = View.VISIBLE
        if (slot.childCount == 1 && slot.getChildAt(0) === v) return
        slot.removeAllViews()
        (v.parent as? ViewGroup)?.removeView(v)
        slot.addView(v, LinearLayout.LayoutParams(-1, if (v === panes.view && !compact) -1 else -2))
    }

    private fun fill(on: Boolean) {
        if ((slotLp.weight == 1f) == on) return
        slotLp.height = if (on) 0 else -2
        slotLp.weight = if (on) 1f else 0f
        slotLp.bottomMargin = if (on) px(SECTION_GAP_DP) else 0
        stretchLp.weight = if (on) 0f else 1f
        slot.layoutParams = slotLp
        stretch.layoutParams = stretchLp
    }

    private fun bindWifi(m: Model, done: Boolean): View? {
        val w = m.wifi
        val inst = m.installer
        val spot = m.hotspot?.takeIf { !w.online && !done }
        if (spot?.title != null) return bindHotspotCard(spot)
        if (w.online || done) {
            cardTitle.text = if (w.ethernet) "Connected over Ethernet" else "Connected to ${w.ssid ?: "Wi-Fi"}"
            cardBody.text = "The panel is on the network."
            // Nor an older line: a failed join from before it connected (a preview keeps the step across refreshes).
            status.visibility = View.GONE
            // Already there: on to the next step, or change the network.
            continueButton()
            ghost.text = "Use another network"
            ghost.visibility = View.VISIBLE
            ghost.setOnClickListener { onChooseNetwork?.invoke() }
            return null
        }
        val plan = WifiWays.plan(m.improv, spot)
        // A way that can't open says what fixes it (Turn on Wi-Fi), whichever way leads. It opens Android's Wi-Fi
        // settings, as Choose a network does, so it stands in for that way too.
        val turnOnWifi = spot?.action?.takeIf { plan.lead != WifiWays.Way.HOTSPOT && it == dev.glassworks.setup.HotspotStep.Action.WIFI_SETTINGS }
        val others = if (turnOnWifi != null) plan.others - WifiWays.Way.MANUAL else plan.others
        // The hotspot's line goes with its button (Allow, Try again, Turn on Wi-Fi), so it wins over an older line
        // (CodeRabbit on #418): a failed installer join, then the Wi-Fi status. A line about opening the hotspot shows
        // only while its button does: as the lead, or among the opened other ways (#468).
        val spotButtonShown = plan.lead == WifiWays.Way.HOTSPOT || (waysOpen && WifiWays.Way.HOTSPOT in others)
        val spotStatus = spot?.status?.takeIf { spot.action !in WifiWays.OPENS || spotButtonShown }
        when {
            spotStatus != null -> showStatus(spotStatus, tone(spot.tone))
            !w.online && inst?.phase == InstallerWifi.Phase.FAILED -> showStatus("Couldn't join ${inst.ssid}: ${inst.detail ?: "unknown error"}", ROSE)
            else -> w.message?.let { showStatus(it, AMBER) } ?: run { status.visibility = View.GONE } // nor the hotspot card's line from a moment ago
        }
        if (inst?.phase == InstallerWifi.Phase.CONNECTING) {
            // The computer is setting it up (Kiln, over USB): nothing to choose here.
            cardTitle.text = "Connecting to ${inst.ssid}…"
            cardBody.text = "Wi-Fi details sent from your computer." + (inst.detail?.let { " $it" } ?: " This can take up to a minute.")
            return null
        }
        val joining = w.state == WifiSetupStatus.State.CONNECTING
        // Turn on Wi-Fi: quiet words at the left, so a phone keeps it on one line with the lead's button.
        turnOnWifi?.let { a ->
            plainLeft.text = a.label
            plainLeft.visibility = View.VISIBLE
            plainLeft.setOnClickListener { onHotspotAction?.invoke(a) }
        }
        val content: View? = when (plan.lead) {
            WifiWays.Way.IMPROV -> {
                cardTitle.text = if (joining) "Connecting to ${w.ssid ?: "Wi-Fi"}…" else "Connect to Wi-Fi"
                cardBody.text = "Home Assistant sends it over Bluetooth, from its app or its settings." // one line on the S1
                improvTile.bind(m.improv)
                // Not now and Stop end the Bluetooth window: the quietest words on the card.
                m.improv.actions.firstOrNull { it == WifiStep.Action.NOT_NOW || it == WifiStep.Action.STOP }?.let { a ->
                    quiet.text = a.label
                    quiet.visibility = View.VISIBLE
                    quiet.setOnClickListener { onImprovAction?.invoke(a) }
                }
                improvTile.view
            }
            WifiWays.Way.HOTSPOT -> {
                val a = spot!!.action!!
                cardTitle.text = if (joining) "Connecting to ${w.ssid ?: "Wi-Fi"}…" else "Set up from a phone"
                cardBody.text = "Join the panel's setup Wi-Fi on your phone to send your network — nothing to type here."
                primary.text = if (a == dev.glassworks.setup.HotspotStep.Action.ALLOW) a.label else dev.glassworks.setup.HotspotStep.Action.OPEN.label
                primary.visibility = View.VISIBLE
                primary.setOnClickListener { onHotspotAction?.invoke(a) }
                null
            }
            WifiWays.Way.MANUAL -> {
                cardTitle.text = if (joining) "Connecting to ${w.ssid ?: "Wi-Fi"}…" else "Choose your Wi-Fi"
                cardBody.text = "Pick your network and type its password here."
                primary.text = "Choose a network"
                primary.visibility = View.VISIBLE
                primary.setOnClickListener { onChooseNetwork?.invoke() }
                null
            }
        }
        bindOtherWays(others, m, spot)
        return content
    }

    /**
     * The Wi-Fi step's other ways, in [WifiWays] order: folded behind "Other ways to set up ›", which opens them in its
     * place, one quiet tap each. They stay open until the card shows another step.
     */
    private fun bindOtherWays(others: List<WifiWays.Way>, m: Model, spot: dev.glassworks.setup.HotspotStep.Card?) {
        if (others.isEmpty()) return
        waysRow.visibility = View.VISIBLE
        waysLink.visibility = if (waysOpen) View.GONE else View.VISIBLE
        waysLink.setOnClickListener {
            // The link goes, so a key's focus moves on to the first way that took its place.
            val focused = waysLink.isFocused
            waysOpen = true
            bind(m, false)
            if (focused) wayButtons.first().requestFocus()
        }
        wayButtons.forEachIndexed { i, b ->
            val way = others.getOrNull(i)
            b.visibility = if (waysOpen && way != null) View.VISIBLE else View.GONE
            b.setOnClickListener(null)
            when (way) {
                WifiWays.Way.IMPROV -> {
                    // Off: start it. Couldn't start: start it again, and say so.
                    val a = if (WifiStep.Action.RETRY in m.improv.actions) WifiStep.Action.RETRY else WifiStep.Action.START
                    b.text = if (a == WifiStep.Action.RETRY) "Try Bluetooth again" else a.label
                    b.setOnClickListener { onImprovAction?.invoke(a) }
                }
                WifiWays.Way.HOTSPOT -> {
                    val a = spot?.action ?: dev.glassworks.setup.HotspotStep.Action.OPEN
                    b.text = dev.glassworks.setup.HotspotStep.Action.OPEN.label
                    b.setOnClickListener { onHotspotAction?.invoke(a) }
                }
                WifiWays.Way.MANUAL -> { b.text = "Choose a network"; b.setOnClickListener { onChooseNetwork?.invoke() } }
                null -> {}
            }
        }
    }

    /**
     * The setup hotspot fills the Wi-Fi card: starting (the spinner), on (its QR code, to join it or, once a phone is on
     * it, to open the setup page), or a start the person asked for that failed (why, in the status line). Returns the
     * step's own view: the spinner's row while it starts.
     */
    private fun bindHotspotCard(spot: dev.glassworks.setup.HotspotStep.Card): View? {
        spot.label?.let { cardLabel.text = it }
        cardTitle.text = spot.title
        if (spot.body == null) cardBody.visibility = View.GONE else cardBody.text = spot.body
        spot.qr?.let(::showQr)
        // Also clears a line from a moment ago, on the same step (CodeRabbit on #418).
        spot.status?.let { showStatus(it, tone(spot.tone)) } ?: run { status.visibility = View.GONE }
        val content = spot.waiting?.let { spotWaitingText.text = it; spotWaiting }
        if (input == Input.NONE) return content
        ghost.text = "Choose a network"
        ghost.visibility = View.VISIBLE
        ghost.setOnClickListener { onChooseNetwork?.invoke() }
        spot.action?.let { a ->
            plainLeft.text = a.label
            plainLeft.visibility = View.VISIBLE
            plainLeft.setOnClickListener { onHotspotAction?.invoke(a) }
        }
        return content
    }

    private fun showQr(text: String) {
        if (qrFor != text) {
            qrImage.setImageBitmap(QrCode.bitmap(text, px(200f)))
            qrFor = text
        }
        qrGlow.visibility = View.VISIBLE
    }

    private fun tone(t: dev.glassworks.setup.HotspotStep.Tone) = when (t) {
        dev.glassworks.setup.HotspotStep.Tone.MUTED -> MU
        dev.glassworks.setup.HotspotStep.Tone.AMBER -> AMBER
        dev.glassworks.setup.HotspotStep.Tone.ROSE -> ROSE
    }

    private fun bindHa(m: Model, done: Boolean): View? {
        if (done) {
            cardTitle.text = "Added to Home Assistant"
            cardBody.text = SpannableStringBuilder().apply {
                append("As ")
                val start = length
                append(haName)
                setSpan(ForegroundColorSpan(TX), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append(", under ESPHome.")
            }
            continueButton()
            // Re-adding by hand: the address and port, on request.
            plainLeft.text = if (manualShown) "${m.panelIp ?: "<panel IP>"} · port 6053" else "Add it by hand"
            plainLeft.visibility = View.VISIBLE
            plainLeft.setOnClickListener { manualShown = true; bind(m, false) }
            return null
        }
        cardTitle.text = "Add it in Home Assistant"
        waitingText.text = if (m.haChecking) HA_CHECKING else "Waiting for Home Assistant…"
        cardBody.text = SpannableStringBuilder().apply {
            append("Settings → Devices & services → ")
            val start = length
            append(haName)
            setSpan(ForegroundColorSpan(TX), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(" → Add")
        }
        // Manual add: Add integration → ESPHome → host + port.
        foot.text = "Adding by hand? ${m.panelIp ?: "<panel IP>"} · port 6053"
        foot.visibility = View.VISIBLE
        plain.text = "Skip"
        plain.visibility = View.VISIBLE
        plain.setOnClickListener { onSkipHa?.invoke() }
        return waiting
    }

    /** Home Assistant not found yet: the panel looks for it, or takes its address. */
    private fun bindFind(): View {
        cardTitle.text = "Find Home Assistant"
        cardBody.text = "Looking for Home Assistant on your network. If it doesn't appear, enter its address below."
        primary.text = "Connect"
        primary.visibility = View.VISIBLE
        primary.setOnClickListener { submit() }
        return urlField
    }

    private fun bindPanes(m: Model, stepChanged: Boolean): View {
        cardTitle.text = "What should it do?"
        cardBody.visibility = View.GONE
        if (stepChanged) { panes.load(); panes.view.scrollTo(0, 0) }
        // The tighter gap under the heading leaves the list room to show the top of a row it hides.
        slotLp.topMargin = px(TIGHT_GAP_DP)
        primary.text = "Continue"
        primary.visibility = View.VISIBLE
        primary.isEnabled = !panes.applying
        primary.alpha = if (panes.applying) 0.5f else 1f
        primary.setOnClickListener { panes.apply(m.preview) }
        // Nothing to say: no line left from an earlier draw (a refusal an answer since cleared).
        if (panes.problem == null && !panes.applying) status.visibility = View.GONE
        panes.problem?.let { showStatus(it, ROSE) }
        if (panes.applying) showStatus("Setting up…", AMBER)
        return panes.view
    }

    /** A pane's step: the screen sets the label, the step fills the rest ([paneCard]). */
    private fun bindPaneStep(step: SetupStep, stepChanged: Boolean) {
        titleBig = false
        cardTitle.textSize = titleSp(false)
        slotLp.topMargin = px(TIGHT_GAP_DP)
        screens.getValue(step.id).bind(paneCard, stepChanged)
    }

    private fun continueButton() {
        primary.text = "Continue"
        primary.visibility = View.VISIBLE
        primary.setOnClickListener { advance() }
    }

    /** How long Continue on Panes waits for every card in all ([BUDGET_MS]; tests shorten it). Public for tests. */
    var panesBudgetMs = BUDGET_MS

    /** "Add it by hand" was tapped on the Home Assistant step that is done. */
    private var manualShown = false

    private fun bindSignin(m: Model, done: Boolean) {
        if (done) {
            cardTitle.text = "Signed in"
            cardBody.text = "The panel can show your Home Assistant dashboard."
            continueButton()
            return
        }
        val ip = m.panelIp ?: "<panel-ip>"
        val url = "http://$ip:${OAuth.SIGNIN_PORT}/signin"
        if (qrFor != url) {
            qrImage.setImageBitmap(QrCode.bitmap(url, px(200f)))
            qrFor = url
        }
        qrGlow.visibility = View.VISIBLE
        cardTitle.text = "Scan to sign in"
        cardBody.text = if (m.signinMoved) "Home Assistant moved to a new address. Scan to sign in again."
            else "Scan with your phone to sign in to Home Assistant — nothing to type here."
        plainLeft.text = "Sign in on this panel instead"
        plainLeft.visibility = View.VISIBLE
        plainLeft.setOnClickListener { onUsePanel?.invoke() }
    }

    /**
     * Without touch ([Input.NONE]): where to set the panel up from a computer, as a QR code and an address. Returns the
     * step's own view, if any.
     */
    private fun bindFromComputer(m: Model): View? {
        // No Wi-Fi works and the panel opens its own: a phone joins it first (#197).
        m.hotspot?.takeIf { it.title != null && !m.wifi.online }?.let { return bindHotspotCard(it) }
        // Bluetooth setup open by itself for its first minutes (#611): a phone or Home Assistant sends the Wi-Fi.
        if (!m.wifi.online && m.improv.phase in NO_TOUCH_IMPROV) {
            cardLabel.text = m.improv.label
            cardTitle.text = m.improv.title
            cardBody.text = m.improv.detail
            status.visibility = View.GONE // nor the hotspot's line from before: the join turns Wi-Fi on itself
            return null
        }
        val ip = m.panelIp
        if (ip == null) {
            cardTitle.text = "Waiting for Wi-Fi"
            cardBody.text = "Connect this panel to Wi-Fi using Kiln on a computer over USB. Once online, this screen will show where to finish setup."
            // Why the panel's own Wi-Fi isn't up (Wi-Fi off, Android closed it): nothing to press here, but said (A-N3).
            m.hotspot?.status?.let { showStatus(it, tone(m.hotspot.tone)) }
            return null
        }
        val url = "http://$ip:${OAuth.SIGNIN_PORT}${dev.glassworks.setup.LanSetup.PAGE}"
        // Signed in, and only Home Assistant's add is left: nothing to scan any more (#484).
        if (shown() == SetupFlow.HA && SetupFlow.SIGNIN in m.done && SetupFlow.HA !in m.done) return bindAddWait(url, m.haChecking)
        if (qrFor != url) {
            qrImage.setImageBitmap(QrCode.bitmap(url, px(200f)))
            qrFor = url
        }
        qrGlow.visibility = View.VISIBLE
        cardTitle.text = "Scan to set it up"
        cardBody.text = "Or open $url on a computer on this network. When it asks for a code, it will appear here."
        return null
    }

    /** Without touch, signed in: waiting for Home Assistant to add the panel, and where to do that. */
    private fun bindAddWait(url: String, checking: Boolean): View {
        cardTitle.text = "Add it in Home Assistant"
        cardBody.text = SpannableStringBuilder().apply {
            append("Settings → Devices & services, where ")
            val start = length
            append(haName)
            setSpan(ForegroundColorSpan(TX), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(" shows up as discovered. If Home Assistant knew this panel before a reinstall, its entry asks to re-authenticate.")
        }
        waitingText.text = if (checking) HA_CHECKING else "Waiting for Home Assistant to add it…"
        foot.text = "Another computer? $url"
        foot.visibility = View.VISIBLE
        return waiting
    }

    private fun showStatus(text: String, color: Int) {
        status.text = text
        status.setTextColor(color)
        status.visibility = View.VISIBLE
    }

    private fun reveal() {
        if (isShowing && visibility == View.VISIBLE && alpha == 1f) return
        // Nothing else can turn this screen on: no touch, no presence sensor, and Home Assistant hasn't added the panel
        // (#484) or has moved (#467).
        if (!isShowing && input == Input.NONE && !presenceSensor && needsHomeAssistant) wakeScreen("setup")
        isShowing = true
        animate().cancel()
        if (visibility != View.VISIBLE) alpha = 0f
        visibility = View.VISIBLE
        bringToFront()
        animate().alpha(1f).setDuration(250).start()
    }

    private fun submit() {
        val v = urlField.text.toString().trim()
        if (v.isEmpty()) return
        primary.isEnabled = false
        primary.alpha = 0.5f
        showStatus("Checking…", AMBER)
        hideKeyboard()
        onManualUrl?.invoke(v)
    }

    private fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(windowToken, 0)
    }

    // ------------------------------------------------------------------ pieces

    /** "Waiting for Home Assistant": a ring with a sky-to-violet dot that breathes. */
    private inner class Pulse : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var t = 0f
        private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
        }

        // Runs only while it can be seen: a hidden overlay (a closed preview) keeps its views attached.
        private fun follow() { if (isShown && isAttachedToWindow) { if (!anim.isStarted) anim.start() } else anim.cancel() }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); follow() }
        override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
        override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); post { follow() } }

        override fun onDraw(c: Canvas) {
            val r = width / 2f
            paint.shader = null
            paint.style = Paint.Style.FILL; paint.color = Glass.S1
            c.drawCircle(r, r, r, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * g.dp; paint.color = Color.WHITE
            paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), Color.argb(90, 56, 189, 248), Color.argb(90, 167, 139, 250), Shader.TileMode.CLAMP)
            c.drawCircle(r, r, r - g.dp, paint)
            paint.shader = null
            paint.color = Color.argb((140 + 80 * kotlin.math.sin(t * Math.PI * 2).toFloat()).toInt().coerceIn(0, 255), 56, 189, 248)
            c.drawCircle(r, r, r * 0.6f, paint)
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(r * 0.7f, r * 0.7f, r * 1.3f, r * 1.3f, Glass.SKY, Glass.VIOLET, Shader.TileMode.CLAMP)
            c.drawCircle(r, r, r * 0.3f, paint)
        }
    }

    /** Improv inline: a status dot with its halo, what is happening, and the one thing to do. */
    private inner class ImprovTile {
        private val dot = View(context)
        private val halo = FrameLayout(context).apply { addView(dot, LayoutParams(px(9f), px(9f), Gravity.CENTER)) }
        private val title = g.text(16.5f, TX, Inter.MEDIUM).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        private val detail = g.text(15f, MU).apply { setLineSpacing(0f, 1.2f); maxLines = 3; ellipsize = TextUtils.TruncateAt.END }
        private val action = g.primary("").apply { setPadding(px(26f), px(12f), px(26f), px(12f)) }
        private val words = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(5f) })
        }
        private val actionBeside = LinearLayout.LayoutParams(-2, -2)
        private val actionUnder = LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(12f) }
        val view = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                // A narrow card (a phone) puts the button under the words, so neither is cut (#468).
                val under = MeasureSpec.getSize(widthMeasureSpec) < px(NARROW_TILE_DP)
                if (under != (action.parent === words)) {
                    (action.parent as ViewGroup).removeView(action)
                    if (under) words.addView(action, actionUnder) else addView(action, actionBeside)
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = g.surface(19f, Glass.S1)
            setPadding(px(13f), px(15f), px(18f), px(15f))
            addView(halo, LinearLayout.LayoutParams(px(18f), px(18f)).apply { marginEnd = px(14f) })
            addView(words, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(14f) })
            addView(action, actionBeside)
        }

        fun bind(line: WifiStep.ImprovLine) {
            val color = when (line.tone) {
                WifiStep.Tone.MUTED -> FA; WifiStep.Tone.AMBER -> AMBER; WifiStep.Tone.GREEN -> GREEN; WifiStep.Tone.ROSE -> ROSE
            }
            dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            halo.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(46, Color.red(color), Color.green(color), Color.blue(color))) }
            title.text = line.title
            detail.text = line.detail
            val a = line.actions.firstOrNull { it != WifiStep.Action.NOT_NOW && it != WifiStep.Action.STOP }
            if (a == null) {
                action.visibility = View.GONE
                action.setOnClickListener(null)
            } else {
                action.text = a.label
                action.visibility = View.VISIBLE
                action.setOnClickListener { onImprovAction?.invoke(a) }
            }
        }
    }

    /**
     * The Panes step: one card per [PaneChoice], two to a row, in a list that scrolls above Continue.
     * It only chooses which panes are on: a
     * pane that is on is set up in its own steps after this one. What the person sets stays here; Continue
     * applies every card through [panesStep] (the setup channel's too), each within [BUDGET_MS].
     */
    private inner class PanesView {
        private val on = BooleanArray(choices.size)
        /**
         * A preview's Continue, kept apart: a preview never changes the live answer (verifier 4bc205dc:
         * it marked the live Panes step chosen). Cleared when the screen hides.
         */
        private var previewApplied: BooleanArray? = null
        private val toggles = ArrayList<Glass.Toggle>()
        var applying = false
            private set
        /**
         * The last refusal of this screen's Continue, while it is current: an outcome published since, from anywhere
         * (Kiln's panes.set), clears it; a BUSY while Kiln applies stays until Kiln's is in (CodeRabbit on #699).
         */
        var problem: String? = null
            get() = field?.takeIf { panesStep.answers == problemAt }
            private set
        private var problemAt = -1
        val chosen: Boolean get() = panesStep.chosen

        /** Two cards to a row, in a list that scrolls when they don't all fit (#121, the maintainer's review). */
        val view = ScrollView(context).apply {
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(px(28f))
            isScrollbarFadingEnabled = false
            // The bar in a gutter of its own, beside the cards rather than over them.
            scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
            setPadding(0, 0, px(10f), 0)
            addView(CardGrid().apply { choices.forEachIndexed { i, c -> addView(card(i, c)) } }, FrameLayout.LayoutParams(-1, -2))
        }

        /** Card [i] is on: as Continue applied it, else as the pane runs. A preview reads its own answer. */
        fun wanted(i: Int): Boolean = if (model?.preview == true) previewApplied?.get(i) ?: choices[i].on() else panesStep.wanted(i)

        fun forgetPreview() { previewApplied = null }

        private fun card(i: Int, c: PaneChoice): View {
            val t = g.toggle(false) { v -> if (!applying) on[i] = v; sync() } // locked while Continue applies
            toggles += t
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = g.surface(19f, Glass.S1)
                minimumHeight = px(68f)
                setPadding(px(17f), px(12f), px(16f), px(12f))
                // Names and lines wrap rather than end in "…": a card grows, and its row with it.
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(g.text(16.5f, TX, Inter.MEDIUM).apply { text = c.title })
                    addView(g.text(15f, MU).apply { text = c.detail },
                        LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(5f) })
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(10f) })
                addView(t)
                isClickable = true
                setOnClickListener { t.performClick() }
            }
        }

        /** Reads every card as it is (the step was just entered). */
        fun load() {
            choices.indices.forEach { on[it] = wanted(it) }
            problem = null
            sync()
        }

        private fun sync() = toggles.forEachIndexed { i, t -> t.checked = on[i] }

        /** Continue: every card, each on its own thread, within [BUDGET_MS] in all; then on, or every problem. */
        fun apply(preview: Boolean) {
            if (applying) return
            if (preview) { previewApplied = on.copyOf(); advance(); return } // nothing applied, nothing told
            applying = true
            problem = null
            val wanted = on.copyOf()
            redraw()
            val budget = panesBudgetMs
            Thread({
                val o = panesStep.attempt(wanted, budget)
                main.post {
                    applying = false
                    problem = o.problem
                    problemAt = o.answer
                    if (o.problem == null) onPanesDone?.invoke() else redraw()
                }
            }, "glassworks-setup-panes").apply { isDaemon = true; start() }
        }

        private fun redraw() { sync(); model?.let { bindCard(it, stepChanged = false) } }
    }

    /**
     * The pane cards two to a row, each row as tall as its tallest card; one to a row where two would each get less
     * than [PANE_CARD_MIN_DP] (a phone held upright).
     */
    private inner class CardGrid : ViewGroup(context) {
        private var columns = 2
        private val rowHeights = ArrayList<Int>()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val gap = px(CARD_GAP_DP)
            columns = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED || w >= 2 * px(PANE_CARD_MIN_DP) + gap) 2 else 1
            val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            rowHeights.clear()
            for (row in (0 until childCount).chunked(columns)) {
                // Measured to wrap at its column's width, then all at the row's height.
                val tallest = row.maxOf { i -> getChildAt(i).let { it.measure(exactly(colWidth(w, i % columns)), free); it.measuredHeight } }
                row.forEach { i -> getChildAt(i).measure(exactly(colWidth(w, i % columns)), exactly(tallest)) }
                rowHeights += tallest
            }
            setMeasuredDimension(w, rowHeights.sum() + gap * (rowHeights.size - 1).coerceAtLeast(0))
        }

        /** Column [c]'s width: the room less the gap, shared out as a row of weighted views shares it. */
        private fun colWidth(w: Int, c: Int): Int {
            if (columns == 1) return w
            val room = w - px(CARD_GAP_DP)
            return if (c == 0) room / 2 else room - room / 2
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l
            var y = 0
            for ((n, row) in (0 until childCount).chunked(columns).withIndex()) {
                var x = 0
                row.forEach { i ->
                    val v = getChildAt(i)
                    v.layout(x, y, x + v.measuredWidth, y + v.measuredHeight)
                    x += v.measuredWidth + px(CARD_GAP_DP)
                }
                y += rowHeights[n] + px(CARD_GAP_DP)
            }
        }
    }

    /** The step being drawn set its own view ([SetupCard.content]) during this draw. */
    private var stepContentSet = false

    /** What the setup screen offers a pane's step. */
    private val stepHost = object : SetupHost {
        override val preview: Boolean get() = model?.preview == true
        override fun redraw() { model?.let { bindCard(it, stepChanged = false) } }
        override fun next() { if (model?.preview == true) advance() else { viewing = null; onStepDone?.invoke() } }
    }

    /** Each pane step's part of this screen. */
    private val screens: Map<String, SetupScreen> by lazy { stepsById.mapValues { (_, v) -> v.second.screen(context, stepHost) } }

    private val paneCard = object : SetupCard {
        override fun title(text: String) { cardTitle.text = text }

        override fun body(text: String?) {
            if (text == null) cardBody.visibility = View.GONE
            else { cardBody.text = text; cardBody.visibility = View.VISIBLE }
        }

        override fun status(text: String?, tone: SetupCard.Tone) {
            if (text == null) { status.visibility = View.GONE; return }
            showStatus(text, when (tone) { SetupCard.Tone.MUTED -> FA; SetupCard.Tone.AMBER -> AMBER; SetupCard.Tone.GREEN -> GREEN; SetupCard.Tone.ROSE -> ROSE })
        }

        override fun qr(url: String?) {
            if (url == null) { qrGlow.visibility = View.GONE; return }
            if (qrFor != url) { qrImage.setImageBitmap(QrCode.bitmap(url, px(200f))); qrFor = url }
            qrGlow.visibility = View.VISIBLE
        }

        override fun content(view: View?) {
            stepContentSet = true
            setContent(view)
        }

        override fun primary(text: String, enabled: Boolean, onClick: () -> Unit) {
            primary.text = text
            primary.isEnabled = enabled
            primary.alpha = if (enabled) 1f else 0.5f
            primary.visibility = View.VISIBLE
            primary.setOnClickListener { onClick() }
        }

        override fun secondary(text: String?, onClick: () -> Unit) {
            if (text == null) { plainLeft.visibility = View.GONE; return }
            plainLeft.text = text
            plainLeft.visibility = View.VISIBLE
            plainLeft.setOnClickListener { onClick() }
        }

        override fun tightButtons() { slotLp.topMargin = px(12f) }
    }

    /** A step's name on the rail and its card's label. */
    private fun title(id: String): String = when (id) {
        SetupFlow.WIFI -> "Wi-Fi"
        SetupFlow.HA -> "Add it"
        SetupFlow.SIGNIN -> "Sign in"
        SetupFlow.PANES -> "Choose"
        SetupFlow.DONE -> "Done"
        else -> stepsById[id]?.second?.title ?: id
    }

    companion object {
        private val GROUPS = mapOf(SetupFlow.HA to "Home Assistant", SetupFlow.PANES to "Panes")

        /** Sign in's title while the page hasn't shown yet whether one is needed ([Model.signinChecking]). */
        const val SIGNIN_CHECKING = "Connecting to Home Assistant…"

        /** Add it's waiting line while Home Assistant may still connect on its own ([Model.haChecking]). */
        const val HA_CHECKING = "Checking whether Home Assistant knows this panel…"

        /** Without touch, Bluetooth setup fills the Wi-Fi card while it takes Wi-Fi: open by itself, a retry, a join. */
        private val NO_TOUCH_IMPROV = setOf(WifiStep.Phase.AUTHORIZED, WifiStep.Phase.FAILED, WifiStep.Phase.CONNECTING)

        /**
         * The card's spacing (#63): the gap between its heading and the step's view, and between the
         * Panes list and its buttons; and the gap between two pane cards.
         */
        const val SECTION_GAP_DP = 26f
        /** The gap under the heading when the step's view needs the room (the Panes list). */
        const val TIGHT_GAP_DP = 18f
        const val CARD_GAP_DP = 11f
        /** The narrowest a pane card gets two to a row, in dp: narrower, one to a row. */
        const val PANE_CARD_MIN_DP = 160f

        /** The QR code's white tile, its quiet zone included. */
        const val QR_DP = 226f

        /** A screen whose short side is under this, in dp, is a phone's ([compact]): the S1's is 480 dp. */
        const val COMPACT_DP = 460

        /** The gap between buttons stacked on a phone. */
        const val STACK_GAP_DP = 6f

        /** The rail's stack art with the gap under it. */
        const val RAIL_ART_DP = 88f

        /** The Bluetooth tile narrower than this (a phone's card) puts its button under its words (#468). */
        const val NARROW_TILE_DP = 360f

        /** [stepsById]'s pane index for the device's own steps: always wanted. */
        private const val OWN = -1

        /** Continue on Panes waits this long for every card in all (the radio's Off waits for its helper). */
        const val BUDGET_MS = PanesStep.BUDGET_MS
    }
}
