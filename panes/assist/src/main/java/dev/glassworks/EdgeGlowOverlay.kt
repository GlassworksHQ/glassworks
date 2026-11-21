package dev.glassworks

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.assist.AudioLevel
import dev.glassworks.assist.EdgeGlow
import dev.glassworks.assist.LevelSmoother
import dev.glassworks.assist.VoiceLevels
import dev.glassworks.assist.VoiceTexts
import dev.glassworks.assist.VoiceUi
import dev.glassworks.theme.Themes
import dev.glassworks.ui.Inter
import kotlin.math.max

/**
 * The voice run as Spark's edge glow ([EdgeGlow], #691), the Assist look next to the orb ([VoiceOverlay]): the dashboard
 * stays in view, the theme's lights run along the screen's edge, and a glass caption at the bottom shows what was
 * heard and the reply. The same API as the orb's overlay, so the app hands each voice event to whichever look is
 * picked (KioskPrefs.assistLook).
 *
 * A run: Spark's entrance on wake, then listening (the real mic level), thinking, speaking (the real playback), an
 * error in rose, and Spark's exit when the run ends. A tap anywhere cancels, as on the orb. Idle, it is GONE and its
 * frame loop stops. Main thread only.
 */
class EdgeGlowOverlay(context: Context) : FrameLayout(context) {

    /** Stops the current run (streaming and playback); a tap anywhere while it shows calls it. */
    var onCancel: (() -> Unit)? = null

    /** The overlay started appearing (true) or going (false): TimerOverlay times a voice timer's fly-out by it. */
    var onShowingChanged: ((Boolean) -> Unit)? = null

    /** The overlays' theme now: read when a run starts. The app's setting by default; a test gives its own. */
    var theme: () -> Themes.Pick = { KioskPrefs(context).overlayPick }

    private val dp = resources.displayMetrics.density
    private var glow: EdgeGlow? = null
    private var gluedTo: Themes.Palette? = null
    private val caption = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isClickable = false
    }
    private val label = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f); letterSpacing = 0.14f; isAllCaps = true; gravity = Gravity.CENTER
        typeface = Inter.typeface(context, Inter.MEDIUM, 11.5f); setSingleLine(true); ellipsize = TextUtils.TruncateAt.END
    }
    private val main = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); gravity = Gravity.CENTER; maxLines = 3; ellipsize = TextUtils.TruncateAt.END
        typeface = Inter.typeface(context, Inter.LIGHT, 26f); letterSpacing = -0.02f
    }
    private var palette = Themes.DEFAULT.palette

    private enum class Phase { ENTER, LISTENING, THINKING, SPEAKING, ERROR }

    private var ui = VoiceUi()
    private var errorUi = VoiceUi() // the error's own snapshot: idle may follow before it shows
    private var active = false
    private var phase = Phase.ENTER
    private var pending = Phase.LISTENING
    private var phaseAt = 0L
    private var now = 0L
    private var lastFrame = 0L
    private var leavingAt = -1L
    private var lastRipple = 0L
    private var lap = -1
    private var bottomLap = -1
    private var looping = false
    private val mic = LevelSmoother()
    private val rnd = java.util.Random()
    private var lastGlint = 0L

    /**
     * What the voice did to the glow in this run, logged when it ends ("Glow run: …", tag Glassworks.Voice): the raw mic level
     * and the glow's voice, mean and peak while listening, and how many ripples it sent. For tuning [EdgeGlow.voice] on a
     * real panel, next to the engine's "Run audio" line.
     */
    private class RunLevels {
        var ms = 0f; var micSum = 0f; var micPeak = 0f; var voiceSum = 0f; var voicePeak = 0f; var ripples = 0
        fun add(mic: Float, voice: Float, dt: Float) {
            ms += dt; micSum += mic * dt; voiceSum += voice * dt
            micPeak = max(micPeak, mic); voicePeak = max(voicePeak, voice)
        }
        fun line() = "Glow run: listening %.1f s, mic mean %.2f peak %.2f, voice mean %.2f peak %.2f, %d ripple(s)".format(
            java.util.Locale.ROOT, ms / 1000f, if (ms > 0) micSum / ms else 0f, micPeak, if (ms > 0) voiceSum / ms else 0f, voicePeak, ripples)
    }
    private var run = RunLevels()
    private val tts = LevelSmoother(attackMs = 350f, releaseMs = 700f) // speaking breathes slowly (calm)

    private val holdEnd = Runnable { leave() }
    // The entrance's end and the exit's end are timed, not counted in frames: they keep their length on a busy panel.
    private val entered = Runnable { if (active && leavingAt < 0 && phase == Phase.ENTER) set(pending) }
    private val gone = Runnable { finish() }
    // The run's clock is uptime, as the orb's overlay keeps it.
    private val frame = Choreographer.FrameCallback { looping = false; tick(SystemClock.uptimeMillis()) }

    init {
        visibility = View.GONE
        clipChildren = false
        // Over the timers (12 dp) and the notifications (14 dp), as the orb's overlay (16 dp); no outline, so no shadow.
        outlineProvider = null
        elevation = 16 * dp
        caption.addView(label, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * dp).toInt() })
        caption.addView(main, LinearLayout.LayoutParams(-1, -2))
        caption.setPadding((30 * dp).toInt(), (16 * dp).toInt(), (30 * dp).toInt(), (18 * dp).toInt())
        setOnClickListener { if (active) { onCancel?.invoke(); leave() } }
        isClickable = false
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frame)
        looping = false
        removeCallbacks(holdEnd); removeCallbacks(entered); removeCallbacks(gone)
        super.onDetachedFromWindow()
    }

    /** Applies the latest voice snapshot (the app keeps it); [enabled] is VoicePrefs.overlay, and false hides it. */
    fun update(next: VoiceUi, enabled: Boolean) {
        val prev = ui
        ui = next
        if (!enabled) { leave(); return }
        val fresh = next.seq != prev.seq
        val st = next.state
        when {
            st == "wake" || st == "listening" -> {
                // A new run while the last one is going: it comes back. Thinking or speaking left over from the
                // cancelled run don't; only a fresh wake or listening starts one.
                if (fresh && leavingAt >= 0) abandonExit()
                request(Phase.LISTENING)
            }
            st == "thinking" -> request(Phase.THINKING)
            st == "speaking" -> request(Phase.SPEAKING)
            st.startsWith("error") -> if (fresh) {
                // A panel-side problem (mic, models) outside a run doesn't bring the glow up, as with the orb.
                if (next.errorLocal && !active) return
                errorUi = next
                request(Phase.ERROR)
                removeCallbacks(holdEnd); postDelayed(holdEnd, ERROR_HOLD_MS)
            }
            // Idle or off: the run is over. An error showing, or waiting for the entrance to end, keeps its hold.
            else -> if (fresh && active && phase != Phase.ERROR && !(phase == Phase.ENTER && pending == Phase.ERROR)) leave()
        }
        if (active) captionFor(phase)
    }

    private fun request(p: Phase) {
        removeCallbacks(holdEnd)
        if (leavingAt >= 0) return // going: only a fresh wake or listening brings it back (abandonExit)
        if (!active) start()
        if (phase == Phase.ENTER) pending = p else set(p)
    }

    private fun start() {
        applyTheme()
        val g = glow ?: return
        active = true; leavingAt = -1L; now = 0L; lastFrame = 0L; lastRipple = 0L; phaseAt = SystemClock.uptimeMillis()
        isClickable = true
        visibility = View.VISIBLE
        phase = Phase.ENTER; pending = Phase.LISTENING
        run = RunLevels(); lastGlint = 0L
        lap = -1; bottomLap = -1; mic.reset(); tts.reset()
        removeCallbacks(gone)
        postDelayed(entered, ENTER_MS)
        onShowingChanged?.invoke(true)
        // The entrance needs the layer's size: once it is laid out.
        if (g.width > 0) g.enter() else g.post { if (active && leavingAt < 0) g.enter() }
        caption.alpha = 0f
        caption.animate().alpha(1f).setStartDelay(360).setDuration(200).start()
        captionFor(Phase.LISTENING)
        loop()
    }

    private fun set(p: Phase) {
        if (p == phase || leavingAt >= 0) return
        phase = p; phaseAt = SystemClock.uptimeMillis(); lap = -1; bottomLap = -1
        val g = glow ?: return
        when (p) {
            Phase.LISTENING -> g.listening()
            Phase.SPEAKING -> g.speakingStart()
            Phase.ERROR -> g.error()
            else -> Unit
        }
        captionFor(p)
    }

    /** Spark's exit; the overlay goes once it has run. Touches go to the dashboard from now on. */
    private fun leave() {
        removeCallbacks(holdEnd)
        if (!active || leavingAt >= 0) return
        leavingAt = SystemClock.uptimeMillis()
        if (run.ms > 0f) dev.glassworks.assist.VoiceLog.i(run.line())
        isClickable = false
        onShowingChanged?.invoke(false)
        glow?.exit()
        caption.animate().alpha(0f).setStartDelay(0).setDuration(200).start()
        removeCallbacks(entered)
        postDelayed(gone, EXIT_MS)
    }

    /** Drops the exit underway, so a new run can start from the top. */
    private fun abandonExit() {
        removeCallbacks(gone)
        finish()
    }

    private fun loop() {
        if (looping) return
        looping = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    /** Each frame of a run: the voice levels and the thinking laps. Stops when the run starts leaving. */
    private fun tick(t: Long) {
        if (!active || leavingAt >= 0) return
        val dt = if (lastFrame == 0L) 16f else (t - lastFrame).toFloat().coerceIn(0f, 100f)
        lastFrame = t
        now = t
        val g = glow
        if (g != null) when (phase) {
            Phase.LISTENING -> {
                val raw = VoiceLevels.mic
                val l = max(mic.step(raw, dt), AudioLevel.LISTENING_FLOOR)
                val v = EdgeGlow.voice(l)
                g.level(v)
                if (t - lastRipple >= RIPPLE_MS) { if (v > RIPPLE_VOICE) { g.ripple(v); run.ripples++ }; lastRipple = t }
                run.add(raw, v, dt)
            }
            Phase.THINKING -> {
                // The top and bottom edges on their own laps, so neither is cut off mid-run.
                val since = t - phaseAt
                val k = (since / EdgeGlow.LAP_MS).toInt(); if (k != lap) { lap = k; g.thinkingTop(k) }
                if (since >= EdgeGlow.BOTTOM_LAG_MS) {
                    val b = ((since - EdgeGlow.BOTTOM_LAG_MS) / EdgeGlow.LAP_MS).toInt(); if (b != bottomLap) { bottomLap = b; g.thinkingBottom() }
                }
                // glints catch here and there, as in the render: a chance every 90 ms
                if (t - lastGlint >= GLINT_MS) { lastGlint = t; if (rnd.nextFloat() >= .45f) g.glint(rnd.nextFloat() * width, rnd.nextBoolean(), rnd.nextBoolean()) }
            }
            Phase.SPEAKING -> g.speaking(tts.step(if (VoiceLevels.ttsPlaying) AudioLevel.ttsPulse((t - phaseAt).toDouble()) else 0f, dt))
            else -> Unit
        }
        loop()
    }

    private fun finish() {
        removeCallbacks(gone)
        active = false; leavingAt = -1L; lastFrame = 0L
        glow?.reset()
        visibility = View.GONE // costs nothing until the next run
    }

    // ---------------------------------------------------------------- theme and caption

    /** Builds the glow for the theme now, once per theme: its bitmaps are baked when it is made. */
    private fun applyTheme() {
        val p = theme().palette
        palette = p
        if (glow != null && gluedTo == p) return
        glow?.let { removeView(it) }
        removeView(caption)
        glow = EdgeGlow(context, p).also { addView(it, 0, LayoutParams(-1, -1)) }
        gluedTo = p
        val portrait = resources.displayMetrics.let { it.heightPixels > it.widthPixels }
        val width = minOf((640 * dp).toInt(), resources.displayMetrics.widthPixels - (64 * dp).toInt())
        caption.background = GradientDrawable().apply {
            cornerRadius = 26 * dp
            setColor((p.ground and 0x00ffffff) or (0xdb shl 24)) // the ground at 86 %
            setStroke(dp.toInt().coerceAtLeast(1), Themes.mix(p.ground, p.ink, 0.12f))
        }
        // Low on a landscape screen, higher on a portrait one (the Mirror), as in the mockups.
        addView(caption, LayoutParams(width, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ((if (portrait) 120 else 34) * dp).toInt() })
        main.setTextColor(p.ink)
    }

    private fun captionFor(p: Phase) {
        val ink = palette.ink
        val muted = Themes.mix(ink, palette.ground, 0.35f)
        val faint = Themes.mix(ink, palette.ground, 0.58f)
        val (l, lc, m, mc) = when (p) {
            Phase.ENTER, Phase.LISTENING -> Quad("Listening", palette.readable(palette.a), VoiceTexts.PROMPT, muted)
            Phase.THINKING -> Quad("Thinking", faint, if (ui.heard.isBlank()) VoiceTexts.PROMPT else VoiceTexts.quote(ui.heard), if (ui.heard.isBlank()) muted else ink)
            Phase.SPEAKING -> Quad(
                if (ui.announcement) VoiceTexts.ANNOUNCEMENT else VoiceTexts.quote(ui.heard), muted,
                ui.reply, ink,
            )
            Phase.ERROR -> Quad("Something went wrong", palette.readable(ROSE), VoiceTexts.friendlyError(errorUi.state, errorUi.errorLocal), muted)
        }
        label.isAllCaps = p != Phase.SPEAKING
        label.text = l; label.setTextColor(lc)
        label.visibility = if (l.isEmpty()) View.GONE else View.VISIBLE
        main.text = m; main.setTextColor(mc)
    }

    private data class Quad(val label: String, val labelColour: Int, val main: String, val mainColour: Int)

    private companion object {
        const val ENTER_MS = 650L
        const val EXIT_MS = 420L
        const val RIPPLE_MS = 140L
        /** A ripple goes out every [RIPPLE_MS] while the voice is above this (the render's .3, on its level). */
        const val RIPPLE_VOICE = .3f
        const val GLINT_MS = 90L
        const val ERROR_HOLD_MS = 1_600L
        val ROSE = 0xfffb7185.toInt()
    }
}
